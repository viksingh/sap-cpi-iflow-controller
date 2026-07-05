package com.sakiv.cpi.iflowctl.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// @author Vikas Singh
// Created: 2026-06-10
public class CpiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CpiConfiguration.class);
    private final Properties properties;
    private String configSource = "defaults only";

    public CpiConfiguration() {
        this.properties = new Properties();
        loadDefaults();
        loadFromClasspath();
        overrideFromEnvironment();
    }

    public CpiConfiguration(String configFilePath) {
        this.properties = new Properties();
        loadDefaults();
        loadFromClasspath();
        loadFromFile(configFilePath);
        this.configSource = configFilePath;
        overrideFromEnvironment();
    }

    private void loadDefaults() {
        properties.setProperty("http.connect.timeout.ms", "30000");
        properties.setProperty("http.read.timeout.ms", "60000");
        properties.setProperty("http.max.retries", "3");
        properties.setProperty("http.retry.delay.ms", "2000");
        properties.setProperty("cpi.api.runtime", "/api/v1/IntegrationRuntimeArtifacts");
        properties.setProperty("cpi.api.deploy", "/api/v1/DeployIntegrationDesigntimeArtifact");
        properties.setProperty("cpi.api.packageFlows",
                "/api/v1/IntegrationPackages('%s')/IntegrationDesigntimeArtifacts");
    }

    private void loadFromClasspath() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (is != null) {
                properties.load(is);
                log.info("Loaded configuration from classpath");
            }
        } catch (IOException e) {
            log.warn("Could not load application.properties from classpath: {}", e.getMessage());
        }
    }

    private void loadFromFile(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw new IllegalStateException("Config file not found: " + filePath);
        }
        try (InputStream is = new FileInputStream(filePath)) {
            properties.load(is);
            log.info("Loaded configuration from: {}", filePath);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load config from " + filePath + ": " + e.getMessage(), e);
        }
    }

    private void overrideFromEnvironment() {
        mapEnvToProperty("CPI_BASE_URL", "cpi.base.url");
        mapEnvToProperty("CPI_AUTH_TYPE", "cpi.auth.type");
        mapEnvToProperty("CPI_OAUTH_TOKEN_URL", "cpi.oauth.token.url");
        mapEnvToProperty("CPI_OAUTH_CLIENT_ID", "cpi.oauth.client.id");
        mapEnvToProperty("CPI_OAUTH_CLIENT_SECRET", "cpi.oauth.client.secret");
        mapEnvToProperty("CPI_BASIC_USERNAME", "cpi.basic.username");
        mapEnvToProperty("CPI_BASIC_PASSWORD", "cpi.basic.password");
    }

    private void mapEnvToProperty(String envVar, String propKey) {
        String val = System.getenv(envVar);
        if (val != null && !val.isBlank()) {
            properties.setProperty(propKey, val);
            log.debug("Override from env: {} -> {}", envVar, propKey);
        }
    }

    public String get(String key) {
        return properties.getProperty(key);
    }

    public String get(String key, String def) {
        return properties.getProperty(key, def);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String getBaseUrl() {
        return get("cpi.base.url");
    }

    public String getAuthType() {
        return get("cpi.auth.type", "oauth2");
    }

    public String getOAuthTokenUrl() {
        return get("cpi.oauth.token.url");
    }

    public String getOAuthClientId() {
        return get("cpi.oauth.client.id");
    }

    public String getOAuthClientSecret() {
        return get("cpi.oauth.client.secret");
    }

    public String getBasicUsername() {
        return get("cpi.basic.username");
    }

    public String getBasicPassword() {
        return get("cpi.basic.password");
    }

    public String getConfigSource() {
        return configSource;
    }

    public void validate() {
        String hint = " - provide it in the connection config file or via environment variables."
                + " See config.properties.template for reference.";

        if (getBaseUrl() == null || getBaseUrl().isBlank()) {
            throw new IllegalStateException("cpi.base.url must be configured" + hint);
        }
        if ("oauth2".equals(getAuthType())) {
            requireNonBlank("cpi.oauth.token.url", "OAuth token URL", hint);
            requireNonBlank("cpi.oauth.client.id", "OAuth client ID", hint);
            requireNonBlank("cpi.oauth.client.secret", "OAuth client secret", hint);
        } else if ("basic".equals(getAuthType())) {
            requireNonBlank("cpi.basic.username", "Basic auth username", hint);
            requireNonBlank("cpi.basic.password", "Basic auth password", hint);
        }
    }

    private void requireNonBlank(String key, String label, String hint) {
        if (get(key) == null || get(key).isBlank()) {
            throw new IllegalStateException(label + " (" + key + ") must be configured" + hint);
        }
    }
}
