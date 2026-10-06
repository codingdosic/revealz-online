package com.revealz.backend.meta;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
class MetaRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    MetaRepository(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    private AccountRow findAccount(String key) {
        String sql = "SELECT account_key, auth_kind, display_name, profile_icon_id, "
                + "client_migrated_at, meta_revision FROM accounts WHERE account_key = ?"
                ;
        List<AccountRow> rows = jdbc.query(sql, (rs, n) -> new AccountRow(
                rs.getString("account_key"), rs.getString("auth_kind"), rs.getString("display_name"),
                rs.getString("profile_icon_id"), instant(rs.getTimestamp("client_migrated_at")),
                rs.getLong("meta_revision")), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> loadSnapshot(String key) {
        AccountRow account = findAccount(key);
        if (account == null) return null;
        int gold = jdbc.query("SELECT gold FROM wallets WHERE account_key = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, key);
        Map<String, Map<String, Integer>> owned = new LinkedHashMap<>();
        jdbc.query("SELECT card_id, rarity, count FROM owned_cards WHERE account_key = ?", (RowCallbackHandler) rs ->
                owned.computeIfAbsent(String.valueOf(rs.getInt(1)), ignored -> new LinkedHashMap<>())
                        .put(String.valueOf(rs.getInt(2)), rs.getInt(3)), key);
        Map<String, List<String>> accessories = emptyAccessories();
        jdbc.query("SELECT accessory_type, accessory_id FROM owned_accessories WHERE account_key = ?", rs -> {
            List<String> values = accessories.get(rs.getString(1));
            if (values != null && !values.contains(rs.getString(2))) values.add(rs.getString(2));
        }, key);
        List<Map<String, Object>> decks = jdbc.query("""
                SELECT deck_id, name, format, payload::text AS payload
                  FROM decks WHERE account_key = ?
                """, (rs, n) -> deckResponse(rs), key);
        int pending = jdbc.query("SELECT COUNT(*) FROM mailbox_items WHERE account_key = ? AND status = 'pending'",
                rs -> rs.next() ? rs.getInt(1) : 0, key);

        Map<String, Object> accountBody = new LinkedHashMap<>();
        accountBody.put("accountKey", account.accountKey());
        accountBody.put("authKind", blankDefault(account.authKind(), "guest"));
        accountBody.put("displayName", blankDefault(account.displayName(), account.accountKey()));
        accountBody.put("profileIconId", blankDefault(account.profileIconId(), ""));
        accountBody.put("clientMigratedAt", account.clientMigratedAt() == null ? null : account.clientMigratedAt().toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", accountBody);
        result.put("gold", gold);
        result.put("owned", owned);
        result.put("ownedAccessories", accessories);
        result.put("decks", decks);
        result.put("metaRevision", account.metaRevision());
        result.put("mailboxPendingCount", pending);
        return result;
    }

    void createWallet(String key) {
        jdbc.update("INSERT INTO wallets (account_key, gold) VALUES (?, 0) ON CONFLICT DO NOTHING", key);
    }

    void addAccessory(String key, String type, String id) {
        jdbc.update("INSERT INTO owned_accessories (account_key, accessory_type, accessory_id) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", key, type, id);
    }

    boolean ownsAccessory(String key, String type, String id) {
        return !jdbc.query("SELECT 1 FROM owned_accessories WHERE account_key = ? AND accessory_type = ? AND accessory_id = ?",
                (rs, n) -> 1, key, type, id).isEmpty();
    }

    void replaceDecks(String key, List<Map<String, Object>> decks) {
        jdbc.update("DELETE FROM decks WHERE account_key = ?", key);
        for (Map<String, Object> deck : decks) {
            String id = text(deck.get("id")).trim();
            if (id.isEmpty() || id.startsWith("builtin_")) continue;
            String name = limited(textOr(deck.get("name"), "Deck"), 120);
            String format = limited(textOr(deck.get("format"), "mono"), 32);
            jdbc.update("INSERT INTO decks (account_key, deck_id, name, format, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
                    key, id, name, format, jsonMapper.writeValueAsString(normalizeDeck(deck, id, name, format)));
        }
    }

    void enqueueWelcome(String key) {
        jdbc.update("""
                INSERT INTO mailbox_items (account_key, source, title, payload, status)
                VALUES (?, 'welcome', '시작 골드', '{"gold":1000000}'::jsonb, 'pending')
                ON CONFLICT (account_key) WHERE source = 'welcome' DO NOTHING
                """, key);
    }

    Map<String, Object> validateOwnedDeck(String key, List<?> ids, List<?> rarities) {
        Map<String, Integer> need = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            int cardId = floor(ids.get(index), 0);
            if (cardId <= 0) return Map.of("ok", false, "error", "invalid_card_id", "detail", text(ids.get(index)));
            int rarity = index < rarities.size() ? floor(rarities.get(index), 0) : 0;
            if (rarity < 0 || rarity > 3) rarity = 0;
            need.merge(cardId + ":" + rarity, 1, Integer::sum);
        }
        Map<String, Integer> have = new LinkedHashMap<>();
        jdbc.query("SELECT card_id, rarity, count FROM owned_cards WHERE account_key = ?", (RowCallbackHandler)
                rs -> have.put(rs.getInt(1) + ":" + rs.getInt(2), rs.getInt(3)), key);
        for (Map.Entry<String, Integer> entry : need.entrySet()) {
            if (have.getOrDefault(entry.getKey(), 0) < entry.getValue())
                return Map.of("ok", false, "error", "not_owned", "detail", entry.getKey());
        }
        return Map.of("ok", true);
    }

    private Map<String, Object> deckResponse(ResultSet rs) throws SQLException {
        Map<String, Object> payload = jsonObject(rs.getString("payload"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rs.getString("deck_id"));
        result.put("name", blankDefault(rs.getString("name"), textOr(payload.get("name"), "Deck")));
        result.put("format", blankDefault(rs.getString("format"), textOr(payload.get("format"), "mono")));
        result.put("base_color", textOr(payload.get("base_color"), "black"));
        result.put("card_ids", list(payload.get("card_ids")));
        result.put("card_rarities", list(payload.get("card_rarities")));
        result.put("accessories", normalizeAccessories(payload));
        result.put("main_card", normalizeMainCard(payload));
        return result;
    }

    private Map<String, Object> normalizeDeck(Map<String, Object> deck, String id, String name, String format) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("name", name); result.put("format", format);
        result.put("base_color", textOr(deck.get("base_color"), "black"));
        result.put("card_ids", list(deck.get("card_ids")));
        result.put("card_rarities", list(deck.get("card_rarities")));
        result.put("accessories", normalizeAccessories(deck));
        result.put("main_card", normalizeMainCard(deck));
        return result;
    }

    private Map<String, Object> normalizeAccessories(Map<String, Object> source) {
        Map<String, Object> raw = map(source.get("accessories"));
        String back = limited(textOr(raw.get("card_back"), text(source.get("card_back"))).trim(), 128);
        String field = limited(textOr(raw.get("field"), text(source.get("field"))).trim(), 128);
        return Map.of("card_back", back, "field", field);
    }

    private Map<String, Object> normalizeMainCard(Map<String, Object> source) {
        Map<String, Object> raw = map(source.get("main_card"));
        int id = floor(raw.get("card_id"), 0);
        return id <= 0 ? Map.of() : Map.of("card_id", id,
                "rarity", Math.max(0, Math.min(3, floor(raw.get("rarity"), 0))));
    }

    private Map<String, Object> jsonObject(String json) {
        try {
            JsonNode node = jsonMapper.readTree(json);
            return node != null && node.isObject() ? objectValue(node) : Map.of();
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private Map<String, Object> objectValue(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> result.put(entry.getKey(), jsonValue(entry.getValue())));
        return result;
    }

    private Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) return objectValue(node);
        if (node.isArray()) { List<Object> values = new ArrayList<>(); node.forEach(item -> values.add(jsonValue(item))); return values; }
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isFloatingPointNumber()) return node.doubleValue();
        return node.stringValue();
    }

    static Map<String, List<String>> emptyAccessories() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("icon", new ArrayList<>()); result.put("card_back", new ArrayList<>()); result.put("field", new ArrayList<>());
        return result;
    }

    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
    static List<?> list(Object value) { return value instanceof List<?> values ? values : List.of(); }
    static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    static String textOr(Object value, String fallback) { String text = text(value); return text.isEmpty() ? fallback : text; }
    static int floor(Object value, int fallback) {
        try { double n = Double.parseDouble(text(value)); return Double.isFinite(n) ? (int) Math.floor(n) : fallback; }
        catch (NumberFormatException exception) { return fallback; }
    }
    static String limited(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
    private static String blankDefault(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    record AccountRow(String accountKey, String authKind, String displayName,
                      String profileIconId, Instant clientMigratedAt, long metaRevision) { }
}
