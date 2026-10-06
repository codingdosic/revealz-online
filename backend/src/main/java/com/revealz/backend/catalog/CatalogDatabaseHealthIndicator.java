package com.revealz.backend.catalog;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("catalogDatabase")
class CatalogDatabaseHealthIndicator implements HealthIndicator {

    private final CatalogDataSourceProperties properties;
    private final JdbcTemplate jdbc;

    CatalogDatabaseHealthIndicator(CatalogDataSourceProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        if (!properties.isConfigured()) {
            return Health.down().withDetail("reason", "not_configured").build();
        }
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return Health.up().build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("reason", "unavailable").build();
        }
    }
}
