package com.revealz.backend.ops;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OpsRepository {
    private final JdbcTemplate jdbc;
    OpsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Map<String, Object> listAccounts(String q, String sort, String order, int page, int limit) {
        String pattern = "%" + q + "%";
        String where = q.isEmpty() ? "" : " WHERE display_name ILIKE ? OR account_key ILIKE ?";
        Object[] countArgs = q.isEmpty() ? new Object[]{} : new Object[]{pattern, pattern};
        int total = jdbc.queryForObject("SELECT COUNT(*) FROM accounts" + where, Integer.class, countArgs);
        String orderBy = "name".equals(sort) ? "LOWER(display_name)" : "created_at";
        String direction = "asc".equals(order) ? "ASC" : "DESC";
        String sql = "SELECT account_key, display_name, auth_kind, created_at FROM accounts" + where
                + " ORDER BY " + orderBy + " " + direction + ", account_key " + direction + " LIMIT ? OFFSET ?";
        Object[] args = q.isEmpty() ? new Object[]{limit, (page - 1) * limit}
                : new Object[]{pattern, pattern, limit, (page - 1) * limit};
        List<Map<String, Object>> accounts = jdbc.query(sql, (rs, n) -> Map.of(
                "accountKey", rs.getString(1), "displayName", rs.getString(2),
                "authKind", rs.getString(3), "createdAt", rs.getTimestamp(4).toInstant().toString()), args);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true); result.put("accounts", accounts); result.put("total", total);
        result.put("page", page); result.put("limit", limit); result.put("sort", sort); result.put("order", order); result.put("q", q);
        return result;
    }

    void setGold(String key, Object gold) {
        if (gold != null) jdbc.update("INSERT INTO wallets (account_key, gold) VALUES (?, ?) "
                + "ON CONFLICT (account_key) DO UPDATE SET gold = EXCLUDED.gold", key, gold);
    }
}
