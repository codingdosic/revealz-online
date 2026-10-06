package com.revealz.backend.matchlog;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MatchLogRepository {
    private final JdbcTemplate jdbc;
    MatchLogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void lock(String matchId) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) locked", Integer.class, matchId);
    }
    String existingHash(String matchId) {
        List<String> rows = jdbc.query("SELECT payload_sha256 FROM matches WHERE match_id = ?::uuid FOR UPDATE",
                (rs, n) -> rs.getString(1), matchId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    void insertMatch(MatchLogNormalizer.Normalized payload, String hash, String summaryJson, String playersJson) {
        var summary = payload.summary();
        jdbc.update("""
                INSERT INTO matches (match_id, schema_version, status, started_at, ended_at,
                  winner_side, end_reason, players, summary, event_count, snapshot_count,
                  payload_sha256, expires_at)
                VALUES (?::uuid, 1, ?,
                  CASE WHEN ?::bigint IS NULL THEN NULL ELSE to_timestamp(?::double precision / 1000.0) END,
                  CASE WHEN ?::bigint IS NULL THEN NULL ELSE to_timestamp(?::double precision / 1000.0) END,
                  ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?,
                  COALESCE(CASE WHEN ?::bigint IS NULL THEN NULL ELSE to_timestamp(?::double precision / 1000.0) END, NOW()) + interval '30 days')
                """, payload.matchId(), summary.get("status"), summary.get("startedAtUnixMs"), summary.get("startedAtUnixMs"),
                summary.get("endedAtUnixMs"), summary.get("endedAtUnixMs"), summary.get("winnerSide"), summary.get("reason"),
                playersJson, summaryJson, payload.events().size(), payload.snapshots().size(), hash,
                summary.get("endedAtUnixMs"), summary.get("endedAtUnixMs"));
    }
    void insertEvents(String matchId, String eventsJson) {
        jdbc.update("""
                INSERT INTO match_events (match_id, seq, schema_version, occurred_at, round_number, phase, event_type, actor_side, payload)
                SELECT ?::uuid, (item->>'seq')::bigint, (item->>'schemaVersion')::smallint,
                  to_timestamp((item->>'occurredAtUnixMs')::double precision / 1000.0),
                  (item->>'round')::integer, item->>'phase', item->>'type',
                  NULLIF(item->>'actorSide', '')::smallint, item->'payload'
                FROM jsonb_array_elements(?::jsonb) item
                """, matchId, eventsJson);
    }
    void insertSnapshots(String matchId, String snapshotsJson) {
        jdbc.update("""
                INSERT INTO match_snapshots (match_id, after_seq, reason, schema_version, occurred_at, round_number, phase, state)
                SELECT ?::uuid, (item->>'afterSeq')::bigint, item->>'reason', (item->>'schemaVersion')::smallint,
                  to_timestamp((item->>'occurredAtUnixMs')::double precision / 1000.0),
                  (item->>'round')::integer, item->>'phase', item->'state'
                FROM jsonb_array_elements(?::jsonb) item
                """, matchId, snapshotsJson);
    }
    int prune() { return jdbc.update("DELETE FROM matches WHERE expires_at <= NOW()"); }
}
