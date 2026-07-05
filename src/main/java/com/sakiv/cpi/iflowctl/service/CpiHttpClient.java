package com.sakiv.cpi.iflowctl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakiv.cpi.iflowctl.config.CpiConfiguration;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

// @author Vikas Singh
// Created: 2026-06-15
public class CpiHttpClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(CpiHttpClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final CpiConfiguration config;
    private final CloseableHttpClient httpClient;
    private final int maxRetries;
    private final long retryDelayMs;

    private String accessToken;
    private Instant tokenExpiry;
    private String csrfToken;

    public CpiHttpClient(CpiConfiguration config) {
        this.config = config;
        this.maxRetries = config.getInt("http.max.retries", 3);
        this.retryDelayMs = config.getInt("http.retry.delay.ms", 2000);

        RequestConfig reqConfig = RequestConfig.custom()
                .setConnectTimeout(config.getInt("http.connect.timeout.ms", 30000))
                .setSocketTimeout(config.getInt("http.read.timeout.ms", 60000))
                .build();

        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(reqConfig)
                .setDefaultCookieStore(new BasicCookieStore())
                .build();
    }

    public String getAllowNotFound(String urlOrPath) throws IOException {
        String fullUrl = resolveUrl(urlOrPath);
        log.debug("GET {}", fullUrl);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpGet req = new HttpGet(fullUrl);
                req.setHeader(HttpHeaders.ACCEPT, "application/json");
                req.setHeader(HttpHeaders.AUTHORIZATION, getAuthHeader());

                try (CloseableHttpResponse resp = httpClient.execute(req)) {
                    int code = resp.getStatusLine().getStatusCode();
                    String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                    if (code == 200) {
                        return body;
                    } else if (code == 404) {
                        return null;
                    } else if (code == 401 && attempt < maxRetries) {
                        log.warn("Got 401, refreshing token and retrying (attempt {}/{})", attempt, maxRetries);
                        invalidateToken();
                        continue;
                    } else if (code == 429) {
                        log.warn("Rate limited (429), waiting before retry (attempt {}/{})", attempt, maxRetries);
                        Thread.sleep(retryDelayMs * attempt);
                        continue;
                    } else if (code >= 500 && attempt < maxRetries) {
                        log.warn("Server error {}, retrying (attempt {}/{})", code, attempt, maxRetries);
                        Thread.sleep(retryDelayMs);
                        continue;
                    } else {
                        throw new IOException(String.format("HTTP %d from %s: %s", code, urlOrPath, body));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", e);
            }
        }
        throw new IOException("Max retries exceeded for: " + urlOrPath);
    }

    public void delete(String urlOrPath) throws IOException {
        executeWrite("DELETE", urlOrPath);
    }

    public void post(String urlOrPath) throws IOException {
        executeWrite("POST", urlOrPath);
    }

    private void executeWrite(String method, String urlOrPath) throws IOException {
        String fullUrl = resolveUrl(urlOrPath);
        log.debug("{} {}", method, fullUrl);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequestBase req = "DELETE".equals(method)
                        ? new HttpDelete(fullUrl)
                        : new HttpPost(fullUrl);
                req.setHeader(HttpHeaders.ACCEPT, "application/json");
                req.setHeader(HttpHeaders.AUTHORIZATION, getAuthHeader());
                String token = getCsrfToken();
                if (token != null) {
                    req.setHeader("X-CSRF-Token", token);
                }
                if (req instanceof HttpPost post) {
                    post.setEntity(new StringEntity("", StandardCharsets.UTF_8));
                }

                try (CloseableHttpResponse resp = httpClient.execute(req)) {
                    int code = resp.getStatusLine().getStatusCode();
                    String body = resp.getEntity() != null
                            ? EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8) : "";

                    if (code >= 200 && code < 300) {
                        return;
                    } else if (code == 401 && attempt < maxRetries) {
                        log.warn("Got 401, refreshing token and retrying (attempt {}/{})", attempt, maxRetries);
                        invalidateToken();
                        csrfToken = null;
                        continue;
                    } else if (code == 403 && attempt < maxRetries) {
                        log.warn("Got 403 (CSRF token rejected), refetching token and retrying (attempt {}/{})", attempt, maxRetries);
                        csrfToken = null;
                        continue;
                    } else if (code == 429) {
                        log.warn("Rate limited (429), waiting before retry (attempt {}/{})", attempt, maxRetries);
                        Thread.sleep(retryDelayMs * attempt);
                        continue;
                    } else if (code >= 500 && attempt < maxRetries) {
                        log.warn("Server error {}, retrying (attempt {}/{})", code, attempt, maxRetries);
                        Thread.sleep(retryDelayMs);
                        continue;
                    } else {
                        throw new IOException(String.format("HTTP %d from %s %s: %s", code, method, urlOrPath, body));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", e);
            }
        }
        throw new IOException("Max retries exceeded for: " + method + " " + urlOrPath);
    }

    private synchronized String getCsrfToken() throws IOException {
        if (csrfToken != null) {
            return csrfToken;
        }
        log.debug("Fetching CSRF token");
        HttpGet req = new HttpGet(config.getBaseUrl() + "/api/v1/");
        req.setHeader(HttpHeaders.AUTHORIZATION, getAuthHeader());
        req.setHeader("X-CSRF-Token", "Fetch");

        try (CloseableHttpResponse resp = httpClient.execute(req)) {
            EntityUtils.consumeQuietly(resp.getEntity());
            Header header = resp.getFirstHeader("X-CSRF-Token");
            csrfToken = header != null ? header.getValue() : null;
            return csrfToken;
        }
    }

    private String resolveUrl(String urlOrPath) {
        if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            return urlOrPath;
        }
        return config.getBaseUrl() + urlOrPath;
    }

    private String getAuthHeader() throws IOException {
        return switch (config.getAuthType().toLowerCase()) {
            case "oauth2" -> "Bearer " + getOAuth2Token();
            case "basic" -> "Basic " + Base64.getEncoder().encodeToString(
                    (config.getBasicUsername() + ":" + config.getBasicPassword())
                            .getBytes(StandardCharsets.UTF_8));
            default -> throw new IllegalStateException("Unknown auth type: " + config.getAuthType());
        };
    }

    private synchronized String getOAuth2Token() throws IOException {
        if (accessToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }

        log.info("Requesting new OAuth2 access token from {}", config.getOAuthTokenUrl());

        HttpPost tokenReq = new HttpPost(config.getOAuthTokenUrl());
        tokenReq.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");

        String creds = config.getOAuthClientId() + ":" + config.getOAuthClientSecret();
        tokenReq.setHeader(HttpHeaders.AUTHORIZATION,
                "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8)));

        tokenReq.setEntity(new StringEntity("grant_type=client_credentials"));

        try (CloseableHttpResponse resp = httpClient.execute(tokenReq)) {
            int code = resp.getStatusLine().getStatusCode();
            String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

            if (code != 200) {
                throw new IOException("OAuth2 token request failed with HTTP " + code + ": " + body);
            }

            JsonNode json = mapper.readTree(body);
            accessToken = json.get("access_token").asText();
            int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 3600;
            tokenExpiry = Instant.now().plusSeconds(expiresIn - 60);

            log.info("OAuth2 token obtained, expires in {} seconds", expiresIn);
            return accessToken;
        }
    }

    private void invalidateToken() {
        accessToken = null;
        tokenExpiry = null;
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
