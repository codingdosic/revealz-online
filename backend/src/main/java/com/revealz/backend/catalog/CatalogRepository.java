package com.revealz.backend.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
class CatalogRepository {

    private static final String PRODUCTS_SQL = """
            SELECT product_id, product_type, display_name, description, price_gold,
                   pack_size, weight_n, weight_r, weight_sr, weight_ur,
                   pool_mode, pool_json::text AS pool_json, accessory_type,
                   accessory_id, sort_order
              FROM shop_products
             WHERE enabled = TRUE
             ORDER BY sort_order ASC, product_id ASC
            """;

    private final CatalogDataSourceProperties properties;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final Counter selectCounter;

    CatalogRepository(
            CatalogDataSourceProperties properties,
            JdbcTemplate jdbc,
            JsonMapper jsonMapper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.selectCounter = Counter.builder("catalog.db.select")
                .description("Catalog SELECT attempts made by this application instance")
                .register(meterRegistry);
    }

    List<CatalogProduct> findEnabledProducts() {
        requireConfigured();
        selectCounter.increment();
        return jdbc.query(PRODUCTS_SQL, (resultSet, rowNumber) -> new CatalogProduct(
                String.valueOf(resultSet.getObject("product_id")),
                String.valueOf(resultSet.getObject("product_type")),
                stringOrDefault(resultSet.getObject("display_name"), ""),
                stringOrDefault(resultSet.getObject("description"), ""),
                intOrDefault(resultSet.getObject("price_gold"), 0, Integer.MIN_VALUE),
                intOrDefault(resultSet.getObject("pack_size"), 1, 1),
                intOrDefault(resultSet.getObject("weight_n"), 0, 0),
                intOrDefault(resultSet.getObject("weight_r"), 0, 0),
                intOrDefault(resultSet.getObject("weight_sr"), 0, 0),
                intOrDefault(resultSet.getObject("weight_ur"), 0, 0),
                stringOrDefault(resultSet.getObject("pool_mode"), "explicit"),
                normalizePool(resultSet.getString("pool_json")),
                stringOrDefault(resultSet.getObject("accessory_type"), ""),
                stringOrDefault(resultSet.getObject("accessory_id"), ""),
                intOrDefault(resultSet.getObject("sort_order"), 0, Integer.MIN_VALUE)));
    }

    BigDecimal findRevision() {
        requireConfigured();
        selectCounter.increment();
        List<String> values = jdbc.query(
                "SELECT config_value::text FROM app_config WHERE config_key = 'shop_catalog_revision'",
                (resultSet, rowNumber) -> resultSet.getString(1));
        return values.isEmpty() ? BigDecimal.ZERO : normalizeRevision(values.getFirst());
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new CatalogDatabaseNotConfiguredException();
        }
    }

    private BigDecimal normalizeRevision(String json) {
        try {
            JsonNode value = jsonMapper.readTree(json);
            if (value == null || value.isNull()) {
                return BigDecimal.ZERO;
            }
            String text = value.isString() ? value.stringValue().trim() : value.toString();
            if (text.isEmpty()) {
                return BigDecimal.ZERO;
            }
            BigDecimal number = new BigDecimal(text).stripTrailingZeros();
            return number.scale() < 0 ? number.setScale(0) : number;
        } catch (RuntimeException exception) {
            return BigDecimal.ZERO;
        }
    }

    private List<Long> normalizePool(String json) {
        try {
            JsonNode value = jsonMapper.readTree(json);
            if (value == null || !value.isArray()) {
                return List.of();
            }
            List<Long> pool = new ArrayList<>();
            for (JsonNode item : value) {
                try {
                    double number = item.isNull() ? 0 : Double.parseDouble(
                            item.isString() ? item.stringValue().trim() : item.toString());
                    long normalized = (long) Math.floor(number);
                    if (Double.isFinite(number) && normalized > 0) {
                        pool.add(normalized);
                    }
                } catch (NumberFormatException ignored) {
                    // Node filters only the invalid item and keeps the rest of the pool.
                }
            }
            return List.copyOf(pool);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static String stringOrDefault(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intOrDefault(Object value, int fallback, int minimum) {
        if (value == null) {
            return fallback;
        }
        try {
            double number = Double.parseDouble(String.valueOf(value));
            if (!Double.isFinite(number) || number == 0) {
                return fallback;
            }
            return Math.max(minimum, (int) number);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    record CatalogProduct(
            String productId,
            String productType,
            String displayName,
            String description,
            int priceGold,
            int packSize,
            int weightN,
            int weightR,
            int weightSr,
            int weightUr,
            String poolMode,
            List<Long> pool,
            String accessoryType,
            String accessoryId,
            int sortOrder) {
    }
}
