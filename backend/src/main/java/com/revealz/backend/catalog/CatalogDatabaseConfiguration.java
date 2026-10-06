package com.revealz.backend.catalog;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogDataSourceProperties.class)
class CatalogDatabaseConfiguration {

    @Bean(destroyMethod = "close")
    HikariDataSource catalogDataSource(CatalogDataSourceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("catalog-pool");
        dataSource.setMaximumPoolSize(properties.getMaximumPoolSize());
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(properties.getConnectionTimeoutMs());
        dataSource.setInitializationFailTimeout(-1);
        if (properties.isConfigured()) {
            dataSource.setJdbcUrl(properties.getUrl());
            dataSource.setUsername(properties.getUsername());
            dataSource.setPassword(properties.getPassword());
        }
        return dataSource;
    }

    @Bean
    JdbcTemplate catalogJdbcTemplate(HikariDataSource catalogDataSource) {
        return new JdbcTemplate(catalogDataSource);
    }
}
