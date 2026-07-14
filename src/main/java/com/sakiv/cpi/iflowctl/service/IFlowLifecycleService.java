package com.sakiv.cpi.iflowctl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakiv.cpi.iflowctl.config.CpiConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// @author Vikas Singh
// Created: 2026-06-18
public class IFlowLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(IFlowLifecycleService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static final String NOT_DEPLOYED = "NOT_DEPLOYED";
    public static final String STARTED = "STARTED";
    public static final String ERROR = "ERROR";

    private final CpiConfiguration config;
    private final CpiHttpClient httpClient;

    private final Map<String, Map<String, String>> packageFlowCache = new HashMap<>();
    private Map<String, String> packageIdByName;

    public IFlowLifecycleService(CpiConfiguration config, CpiHttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    public String resolveIflowId(String pkg, String nameOrId) throws IOException {
        if (pkg == null || pkg.isBlank()) {
            return nameOrId;
        }
        Map<String, String> flows = getPackageFlows(pkg);
        String key = nameOrId.toLowerCase();
        return flows.get(key);
    }

    private Map<String, String> getPackageFlows(String pkg) throws IOException {
        Map<String, String> cached = packageFlowCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        String pattern = config.get("cpi.api.packageFlows",
                "/api/v1/IntegrationPackages('%s')/IntegrationDesigntimeArtifacts");
        String path = String.format(pattern, encode(pkg)) + "?$format=json";

        Map<String, String> map = new LinkedHashMap<>();
        // The package segment is an OData key (Id). Try it directly; if it fails
        // (404, or 400 when a display name with special characters is supplied)
        // resolve the name to an Id and retry once.
        String body = null;
        try {
            body = httpClient.getAllowNotFound(path);
        } catch (IOException directLookupFailed) {
            log.debug("Direct package lookup for '{}' failed: {}", pkg, directLookupFailed.getMessage());
        }
        if (body == null) {
            String resolvedId = resolvePackageIdByName(pkg);
            if (resolvedId != null && !resolvedId.equals(pkg)) {
                log.info("Package '{}' resolved by name to Id '{}'", pkg, resolvedId);
                body = httpClient.getAllowNotFound(
                        String.format(pattern, encode(resolvedId)) + "?$format=json");
            }
        }
        if (body == null) {
            log.warn("Package '{}' not found when resolving iFlow names", pkg);
            packageFlowCache.put(pkg, map);
            return map;
        }
        JsonNode results = extractResults(mapper.readTree(body));
        if (results != null && results.isArray()) {
            for (JsonNode node : results) {
                String id = text(node, "Id");
                String name = text(node, "Name");
                if (id != null) {
                    map.putIfAbsent(id.toLowerCase(), id);
                }
                if (name != null && id != null) {
                    map.putIfAbsent(name.toLowerCase(), id);
                }
            }
        }
        log.debug("Resolved {} iFlow id(s) in package '{}'", map.size(), pkg);
        packageFlowCache.put(pkg, map);
        return map;
    }

    // Looks up a package Id from its display Name. Returns null when no package
    // matches. Used as a fallback so the CSV may reference a package by name.
    // The CPI OData v1 API does not support $filter on IntegrationPackages, so
    // the full list is fetched once and matched client-side (case-insensitive).
    private String resolvePackageIdByName(String name) throws IOException {
        if (packageIdByName == null) {
            packageIdByName = new HashMap<>();
            String body = httpClient.getAllowNotFound(
                    "/api/v1/IntegrationPackages?$format=json");
            JsonNode results = body != null ? extractResults(mapper.readTree(body)) : null;
            if (results != null && results.isArray()) {
                for (JsonNode node : results) {
                    String id = text(node, "Id");
                    String pkgName = text(node, "Name");
                    if (id != null && pkgName != null) {
                        packageIdByName.putIfAbsent(pkgName.toLowerCase(), id);
                    }
                }
            }
        }
        return packageIdByName.get(name.toLowerCase());
    }

    private JsonNode extractResults(JsonNode root) {
        JsonNode d = root.path("d");
        if (d.has("results") && d.get("results").isArray()) {
            return d.get("results");
        }
        if (d.isArray()) {
            return d;
        }
        if (root.has("results") && root.get("results").isArray()) {
            return root.get("results");
        }
        if (root.has("value") && root.get("value").isArray()) {
            return root.get("value");
        }
        if (root.isArray()) {
            return root;
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    public String getStatus(String iflowId) throws IOException {
        String path = runtimePath(iflowId);
        String body = httpClient.getAllowNotFound(path);
        if (body == null) {
            return NOT_DEPLOYED;
        }
        JsonNode root = mapper.readTree(body);
        JsonNode node = root.has("d") ? root.get("d") : root;
        JsonNode status = node.get("Status");
        return status != null && !status.isNull() ? status.asText() : "UNKNOWN";
    }

    public void undeploy(String iflowId) throws IOException {
        log.info("Undeploying iFlow '{}'", iflowId);
        httpClient.delete(runtimePath(iflowId));
        log.info("Undeploy request accepted for iFlow '{}'", iflowId);
    }

    public void deploy(String iflowId) throws IOException {
        log.info("Deploying iFlow '{}'", iflowId);
        String base = config.get("cpi.api.deploy", "/api/v1/DeployIntegrationDesigntimeArtifact");
        String path = base + "?Id='" + encode(iflowId) + "'&Version='active'";
        httpClient.post(path);
        log.info("Deploy request accepted for iFlow '{}'", iflowId);
    }

    // Polls runtime status until the iFlow reaches STARTED/ERROR or the timeout
    // elapses. Returns the last observed status. NOT_DEPLOYED is not terminal
    // here: right after a deploy the runtime artifact may not exist yet.
    public String awaitDeployed(String iflowId) throws IOException {
        long interval = config.getInt("deploy.poll.interval.ms", 5000);
        long deadline = System.currentTimeMillis() + config.getInt("deploy.wait.timeout.ms", 300000);
        String status = "STARTING";
        while (System.currentTimeMillis() < deadline) {
            sleep(interval);
            status = getStatus(iflowId);
            log.debug("Deploy poll for '{}': {}", iflowId, status);
            if (STARTED.equals(status) || ERROR.equals(status)) {
                return status;
            }
        }
        log.warn("Timed out waiting for iFlow '{}' to deploy (last status: {})", iflowId, status);
        return status;
    }

    // Polls runtime status until the artifact is gone (NOT_DEPLOYED) or the
    // timeout elapses. Returns the last observed status.
    public String awaitUndeployed(String iflowId) throws IOException {
        long interval = config.getInt("deploy.poll.interval.ms", 5000);
        long deadline = System.currentTimeMillis() + config.getInt("deploy.wait.timeout.ms", 300000);
        String status = "STOPPING";
        while (System.currentTimeMillis() < deadline) {
            sleep(interval);
            status = getStatus(iflowId);
            log.debug("Undeploy poll for '{}': {}", iflowId, status);
            if (NOT_DEPLOYED.equals(status)) {
                return status;
            }
        }
        log.warn("Timed out waiting for iFlow '{}' to undeploy (last status: {})", iflowId, status);
        return status;
    }

    private void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for iFlow runtime state", e);
        }
    }

    // Fetches the runtime error detail SAP records for a failed deployment.
    // Returns null when no detail is available. Never throws: this is only ever
    // used to enrich an already-known failure, so a lookup problem must not mask it.
    public String getErrorInformation(String iflowId) {
        try {
            String body = httpClient.getAllowNotFound(runtimePath(iflowId) + "/ErrorInformation/$value");
            if (body == null || body.isBlank()) {
                return null;
            }
            try {
                JsonNode node = mapper.readTree(body);
                JsonNode msg = node.get("message");
                if (msg != null && !msg.isNull()) {
                    return msg.asText();
                }
            } catch (IOException notJson) {
                // ErrorInformation/$value may be plain text; fall through to raw body
            }
            return body.trim();
        } catch (IOException e) {
            log.debug("Could not fetch error information for '{}': {}", iflowId, e.getMessage());
            return null;
        }
    }

    private String runtimePath(String iflowId) {
        String base = config.get("cpi.api.runtime", "/api/v1/IntegrationRuntimeArtifacts");
        return base + "('" + encode(iflowId) + "')";
    }

    private String encode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
