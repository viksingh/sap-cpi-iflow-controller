package com.sakiv.cpi.iflowctl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sakiv.cpi.iflowctl.config.CpiConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// @author Vikas Singh
// Created: 2026-06-18
public class IFlowLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(IFlowLifecycleService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static final String NOT_DEPLOYED = "NOT_DEPLOYED";
    public static final String STARTED = "STARTED";
    public static final String ERROR = "ERROR";

    private static final Pattern EDM_DATE = Pattern.compile("/Date\\((-?\\d+)");
    private static final DateTimeFormatter DEPLOYED_ON_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CpiConfiguration config;
    private final CpiHttpClient httpClient;

    private final Map<String, Map<String, String>> packageFlowCache = new HashMap<>();
    private final Set<String> resolvedPackages = new HashSet<>();
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
        JsonNode results = null;
        try {
            results = fetchAllResults(path);
        } catch (IOException directLookupFailed) {
            log.debug("Direct package lookup for '{}' failed: {}", pkg, directLookupFailed.getMessage());
        }
        if (results == null) {
            String resolvedId = resolvePackageIdByName(pkg);
            if (resolvedId != null && !resolvedId.equalsIgnoreCase(pkg)) {
                log.info("Package '{}' resolved by name to Id '{}'", pkg, resolvedId);
                results = fetchAllResults(
                        String.format(pattern, encode(resolvedId)) + "?$format=json");
            }
        }
        if (results == null) {
            log.warn("Package '{}' not found when resolving iFlow names", pkg);
            packageFlowCache.put(pkg, map);
            return map;
        }
        resolvedPackages.add(pkg.toLowerCase());
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
        log.debug("Resolved {} iFlow id(s) in package '{}'", map.size(), pkg);
        packageFlowCache.put(pkg, map);
        return map;
    }

    // Whether the given package (Id or display Name) was located in the tenant.
    // Only meaningful after resolveIflowId/getPackageFlows has run for it; lets
    // callers tell "package missing" apart from "iFlow missing within package".
    public boolean packageResolved(String pkg) {
        return pkg == null || pkg.isBlank() || resolvedPackages.contains(pkg.toLowerCase());
    }

    // Fetches every page of an OData v1 collection, following the d.__next
    // continuation links CPI returns when a result set spans multiple pages.
    // Returns null only when the first page 404s (collection/key not found);
    // otherwise an array (possibly empty) of all rows across pages.
    private JsonNode fetchAllResults(String path) throws IOException {
        ArrayNode all = mapper.createArrayNode();
        String next = path;
        boolean firstPageSeen = false;
        int guard = 0;
        while (next != null && guard++ < 10_000) {
            String body = httpClient.getAllowNotFound(next);
            if (body == null) {
                return firstPageSeen ? all : null;
            }
            firstPageSeen = true;
            JsonNode root = mapper.readTree(body);
            JsonNode page = extractResults(root);
            if (page != null && page.isArray()) {
                all.addAll((ArrayNode) page);
            }
            next = nextLink(root);
        }
        return all;
    }

    // Extracts the OData continuation link (v1 d.__next, or v4 @odata.nextLink)
    // and ensures it still requests JSON so paging does not silently flip to XML.
    private String nextLink(JsonNode root) {
        JsonNode v1 = root.path("d").path("__next");
        JsonNode link = v1.isTextual() ? v1 : root.path("@odata.nextLink");
        if (!link.isTextual()) {
            return null;
        }
        String url = link.asText();
        if (!url.contains("$format")) {
            url += (url.contains("?") ? "&" : "?") + "$format=json";
        }
        return url;
    }

    // Looks up a package Id from its display Name. Returns null when no package
    // matches. Used as a fallback so the CSV may reference a package by name.
    // The CPI OData v1 API does not support $filter on IntegrationPackages, so
    // the full list is fetched once and matched client-side (case-insensitive).
    private String resolvePackageIdByName(String name) throws IOException {
        if (packageIdByName == null) {
            packageIdByName = new HashMap<>();
            JsonNode results = fetchAllResults("/api/v1/IntegrationPackages?$format=json");
            if (results != null) {
                for (JsonNode node : results) {
                    String id = text(node, "Id");
                    String pkgName = text(node, "Name");
                    if (id != null && pkgName != null) {
                        packageIdByName.putIfAbsent(pkgName.toLowerCase(), id);
                    }
                }
            }
            log.debug("Loaded {} package name->id mapping(s)", packageIdByName.size());
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

    // Runtime deployment metadata for an iFlow: status plus who deployed it and
    // when. DeployedOn is returned by CPI in the Edm.DateTime wire format
    // (/Date(<epochMillis>)/); it is parsed to a local "yyyy-MM-dd HH:mm:ss".
    public record RuntimeInfo(String status, String deployedOn, String deployedBy) {}

    public RuntimeInfo getRuntimeInfo(String iflowId) throws IOException {
        String body = httpClient.getAllowNotFound(runtimePath(iflowId));
        if (body == null) {
            return new RuntimeInfo(NOT_DEPLOYED, "", "");
        }
        JsonNode root = mapper.readTree(body);
        JsonNode node = root.has("d") ? root.get("d") : root;
        String status = text(node, "Status");
        return new RuntimeInfo(
                status != null ? status : "UNKNOWN",
                formatEdmDate(text(node, "DeployedOn")),
                orEmpty(text(node, "DeployedBy")));
    }

    // CPI returns DeployedOn either as the Edm.DateTime wire format
    // (/Date(<epochMillis>)/) or as an ISO-8601 string (e.g.
    // 2026-07-14T11:06:35.176). Both are normalised to "yyyy-MM-dd HH:mm:ss";
    // anything unrecognised is passed through unchanged.
    private String formatEdmDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Matcher m = EDM_DATE.matcher(raw);
        if (m.find()) {
            return Instant.ofEpochMilli(Long.parseLong(m.group(1)))
                    .atZone(ZoneId.systemDefault())
                    .format(DEPLOYED_ON_FORMAT);
        }
        try {
            return OffsetDateTime.parse(raw)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(DEPLOYED_ON_FORMAT);
        } catch (DateTimeParseException noOffset) {
            try {
                return LocalDateTime.parse(raw).format(DEPLOYED_ON_FORMAT);
            } catch (DateTimeParseException notIso) {
                return raw;
            }
        }
    }

    private String orEmpty(String s) {
        return s == null ? "" : s;
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
