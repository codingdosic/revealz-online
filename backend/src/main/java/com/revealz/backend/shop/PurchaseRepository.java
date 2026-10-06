package com.revealz.backend.shop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
class PurchaseRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    PurchaseRepository(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    Product findEnabled(String id) {
        List<Product> rows = jdbc.query("SELECT * FROM shop_products WHERE product_id = ? AND enabled = TRUE",
                (rs, n) -> new Product(rs.getString("product_id"), rs.getString("product_type"),
                        rs.getInt("price_gold"), Math.max(1, rs.getInt("pack_size")),
                        List.of(rs.getInt("weight_n"), rs.getInt("weight_r"), rs.getInt("weight_sr"), rs.getInt("weight_ur")),
                        rs.getString("pool_mode"), pool(rs.getString("pool_json")),
                        rs.getString("accessory_type"), rs.getString("accessory_id")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Integer lockGold(String key) {
        List<Integer> rows = jdbc.query("SELECT gold FROM wallets WHERE account_key = ? FOR UPDATE",
                (rs, n) -> rs.getInt(1), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void setGold(String key, int gold) { jdbc.update("UPDATE wallets SET gold = ? WHERE account_key = ?", gold, key); }
    boolean ownsAccessory(String key, String type, String id) {
        return !jdbc.query("SELECT 1 FROM owned_accessories WHERE account_key = ? AND accessory_type = ? AND accessory_id = ?",
                (rs, n) -> 1, key, type, id).isEmpty();
    }
    void addAccessory(String key, String type, String id) {
        jdbc.update("INSERT INTO owned_accessories (account_key, accessory_type, accessory_id) VALUES (?, ?, ?)", key, type, id);
    }
    void addCard(String key, int cardId, int rarity) {
        jdbc.update("""
                INSERT INTO owned_cards (account_key, card_id, rarity, count) VALUES (?, ?, ?, 1)
                ON CONFLICT (account_key, card_id, rarity)
                DO UPDATE SET count = owned_cards.count + 1
                """, key, cardId, rarity);
    }
    long bumpRevision(String key) {
        return jdbc.queryForObject("UPDATE accounts SET meta_revision = meta_revision + 1 WHERE account_key = ? "
                + "RETURNING meta_revision", Long.class, key);
    }
    Map<String, Map<String, Integer>> ownedCards(String key) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        jdbc.query("SELECT card_id, rarity, count FROM owned_cards WHERE account_key = ?", (RowCallbackHandler) rs ->
                result.computeIfAbsent(String.valueOf(rs.getInt(1)), ignored -> new LinkedHashMap<>())
                        .put(String.valueOf(rs.getInt(2)), rs.getInt(3)), key);
        return result;
    }
    Map<String, List<String>> accessories(String key) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("icon", new ArrayList<>()); result.put("card_back", new ArrayList<>()); result.put("field", new ArrayList<>());
        jdbc.query("SELECT accessory_type, accessory_id FROM owned_accessories WHERE account_key = ?", rs -> {
            List<String> values = result.get(rs.getString(1)); if (values != null) values.add(rs.getString(2));
        }, key);
        return result;
    }

    private List<Integer> pool(String json) {
        try {
            JsonNode node = jsonMapper.readTree(json);
            if (node == null || !node.isArray()) return List.of();
            List<Integer> result = new ArrayList<>();
            for (JsonNode item : node) {
                try {
                    double value = Double.parseDouble(item.isString() ? item.stringValue() : item.toString());
                    int id = (int) Math.floor(value); if (Double.isFinite(value) && id > 0) result.add(id);
                } catch (NumberFormatException ignored) { }
            }
            return result;
        } catch (RuntimeException exception) { return List.of(); }
    }

    record Product(String id, String type, int price, int packSize, List<Integer> weights,
                   String poolMode, List<Integer> pool, String accessoryType, String accessoryId) { }
}
