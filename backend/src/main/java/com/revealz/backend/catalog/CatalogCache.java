package com.revealz.backend.catalog;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class CatalogCache {

    static final String KEY = "revealz:catalog:v1";

    private static final Logger LOG = LoggerFactory.getLogger(CatalogCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final boolean enabled;
    private final Duration ttl;

    CatalogCache(
            StringRedisTemplate redis,
            JsonMapper jsonMapper,
            @Value("${catalog.cache.enabled:false}") boolean enabled,
            @Value("${catalog.cache.ttl-seconds:30}") long ttlSeconds) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    Lookup read() {
        if (!enabled) {
            return new Lookup(null, false);
        }
        try {
            String json = redis.opsForValue().get(KEY);
            if (json == null) {
                return new Lookup(null, true);
            }
            return new Lookup(jsonMapper.readValue(json, CatalogResponse.class), true);
        } catch (RuntimeException exception) {
            LOG.warn("catalog cache read failed; using database: {}", exception.getClass().getSimpleName());
            return new Lookup(null, false);
        }
    }

    void write(CatalogResponse response) {
        try {
            String json = jsonMapper.writeValueAsString(response);
            redis.opsForValue().set(KEY, json, ttl);
        } catch (RuntimeException exception) {
            LOG.warn("catalog cache write failed; returning database result: {}", exception.getClass().getSimpleName());
        }
    }

    record Lookup(CatalogResponse response, boolean cacheAvailable) {
    }
}
