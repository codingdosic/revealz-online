package com.revealz.backend.mailbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
class MailboxRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    MailboxRepository(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    boolean accountExists(String key, boolean lock) {
        String sql = "SELECT 1 FROM accounts WHERE account_key = ?" + (lock ? " FOR UPDATE" : "");
        return !jdbc.query(sql, (rs, n) -> 1, key).isEmpty();
    }

    List<Map<String, Object>> pending(String key, boolean lock) {
        String sql = """
                SELECT id, account_key, source, title, payload::text AS payload,
                       status, created_at, claimed_at
                  FROM mailbox_items
                 WHERE account_key = ? AND status = 'pending'
                 ORDER BY created_at %s, id %s%s
                """.formatted(lock ? "ASC" : "DESC", lock ? "ASC" : "DESC", lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> item(rs), key);
    }

    Map<String, Object> lockItem(String key, long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, account_key, source, title, payload::text AS payload,
                       status, created_at, claimed_at
                  FROM mailbox_items WHERE id = ? AND account_key = ? FOR UPDATE
                """, (rs, n) -> item(rs), id, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> markClaimed(String key, long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                UPDATE mailbox_items SET status = 'claimed', claimed_at = NOW()
                 WHERE id = ? AND account_key = ? AND status = 'pending'
                 RETURNING id, account_key, source, title, payload::text AS payload,
                           status, created_at, claimed_at
                """, (rs, n) -> item(rs), id, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void markAllClaimed(String key) {
        jdbc.update("UPDATE mailbox_items SET status = 'claimed', claimed_at = NOW() "
                + "WHERE account_key = ? AND status = 'pending'", key);
    }

    void apply(String key, Map<String, Object> payload) {
        int gold = integer(payload.get("gold"), 0);
        if (gold > 0) {
            jdbc.update("INSERT INTO wallets (account_key, gold) VALUES (?, ?) "
                    + "ON CONFLICT (account_key) DO UPDATE SET gold = wallets.gold + EXCLUDED.gold", key, gold);
        }
        for (Object raw : list(payload.get("cards"))) {
            Map<String, Object> card = map(raw);
            int id = integer(card.containsKey("id") ? card.get("id") : card.get("cardId"), 0);
            int rarity = integer(card.get("rarity"), -1);
            int count = Math.max(1, integer(card.get("count"), 1));
            if (id <= 0 || rarity < 0 || rarity > 3) continue;
            jdbc.update("""
                    INSERT INTO owned_cards (account_key, card_id, rarity, count)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (account_key, card_id, rarity)
                    DO UPDATE SET count = owned_cards.count + EXCLUDED.count
                    """, key, id, rarity, count);
        }
    }

    void bumpRevision(String key) {
        jdbc.update("UPDATE accounts SET meta_revision = meta_revision + 1 WHERE account_key = ?", key);
    }

    Map<String, Object> enqueue(String key, String source, String title, Map<String, Object> payload) {
        return jdbc.queryForObject("""
                INSERT INTO mailbox_items (account_key, source, title, payload, status)
                VALUES (?, ?, ?, ?::jsonb, 'pending')
                RETURNING id, account_key, source, title, payload::text AS payload,
                          status, created_at, claimed_at
                """, (rs, n) -> item(rs), key, source, title, jsonMapper.writeValueAsString(payload));
    }

    private Map<String, Object> item(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(rs.getLong("id")));
        result.put("account_key", rs.getString("account_key"));
        result.put("source", rs.getString("source"));
        result.put("title", rs.getString("title"));
        result.put("payload", jsonObject(rs.getString("payload")));
        result.put("status", rs.getString("status"));
        result.put("created_at", iso(rs.getTimestamp("created_at")));
        Timestamp claimed = rs.getTimestamp("claimed_at");
        result.put("claimed_at", claimed == null ? "" : iso(claimed));
        return result;
    }

    private Map<String, Object> jsonObject(String json) {
        try {
            JsonNode node = jsonMapper.readTree(json);
            if (node == null || !node.isObject()) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            node.properties().forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
            return result;
        } catch (RuntimeException exception) { return Map.of(); }
    }

    private Object value(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            node.properties().forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
            return result;
        }
        if (node.isArray()) { List<Object> values = new ArrayList<>(); node.forEach(item -> values.add(value(item))); return values; }
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isFloatingPointNumber()) return node.doubleValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.stringValue();
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return (int) Math.floor(number.doubleValue());
        try { return (int) Math.floor(Double.parseDouble(String.valueOf(value))); }
        catch (RuntimeException exception) { return fallback; }
    }
    private static String iso(Timestamp value) { return value.toInstant().toString(); }
}
