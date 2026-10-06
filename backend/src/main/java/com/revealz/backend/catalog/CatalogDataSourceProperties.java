package com.revealz.backend.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("catalog.datasource")
public class CatalogDataSourceProperties {

    private String url = "";
    private String username = "";
    private String password = "";
    private int maximumPoolSize = 5;
    private long connectionTimeoutMs = 2_000;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url.trim();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public boolean isConfigured() {
        return !url.isBlank();
    }
}
