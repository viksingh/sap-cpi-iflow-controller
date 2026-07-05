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

    private final CpiConfiguration config;
    private final CpiHttpClient httpClient;

    private final Map<String, Map<String, String>> packageFlowCache = new HashMap<>();

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
        String body = httpClient.getAllowNotFound(path);
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

    private String runtimePath(String iflowId) {
        String base = config.get("cpi.api.runtime", "/api/v1/IntegrationRuntimeArtifacts");
        return base + "('" + encode(iflowId) + "')";
    }

    private String encode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
