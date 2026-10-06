/**
 * Dedicated match-log final persistence.
 * The caller authenticates the worker credential before invoking storeFinal().
 */

"use strict";

const crypto = require("crypto");
const db = require("./db");

const SCHEMA_VERSION = 1;
const RETENTION_DAYS = 30;
const MAX_EVENTS = 20_000;
const MAX_SNAPSHOTS = 1_000;
const MAX_STRING = 256;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const FINAL_STATUSES = new Set(["completed", "interrupted"]);

function error(code, message, status = 400) {
  const err = new Error(message || code);
  err.code = code;
  err.status = status;
  return err;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function requiredString(value, field, max = MAX_STRING) {
  const text = String(value || "").trim();
  if (!text || text.length > max) {
    throw error("invalid_payload", `${field}_invalid`);
  }
  return text;
}

function optionalString(value, max = MAX_STRING) {
  return String(value || "").trim().slice(0, max);
}

function integer(value, field, min = 0) {
  if (!Number.isSafeInteger(value) || value < min) {
    throw error("invalid_payload", `${field}_invalid`);
  }
  return value;
}

function optionalUnixMs(value, field) {
  if (value === undefined || value === null || value === 0) return null;
  return integer(value, field, 1);
}

function normalizePlayers(value) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > 2) {
    throw error("invalid_payload", "summary_players_invalid");
  }
  const sides = new Set();
  return value.map((raw, index) => {
    if (!isPlainObject(raw)) {
      throw error("invalid_payload", `summary_players_${index}_invalid`);
    }
    const side = integer(raw.side, `summary_players_${index}_side`, 0);
    if (side > 1 || sides.has(side)) {
      throw error("invalid_payload", `summary_players_${index}_side_invalid`);
    }
    sides.add(side);
    const accountKey = optionalString(raw.accountKey, 128);
    return {
      side,
      accountKey,
      // accountKey identifies the client profile used by the authenticated worker;
      // it is not proof that the remote client owns that account.
      identityTrust: "worker_reported_unverified_account_key",
    };
  });
}

function normalizeSummary(raw) {
  if (!isPlainObject(raw)) {
    throw error("invalid_payload", "summary_required");
  }
  const status = requiredString(raw.status, "summary_status", 32);
  if (!FINAL_STATUSES.has(status)) {
    throw error("invalid_payload", "summary_status_invalid");
  }
  const startedAtUnixMs = optionalUnixMs(raw.startedAtUnixMs, "summary_started_at");
  const endedAtUnixMs = optionalUnixMs(raw.endedAtUnixMs, "summary_ended_at");
  if (startedAtUnixMs !== null && endedAtUnixMs !== null && endedAtUnixMs < startedAtUnixMs) {
    throw error("invalid_payload", "summary_time_order_invalid");
  }
  let winnerSide = null;
  if (raw.winnerSide !== undefined && raw.winnerSide !== null) {
    winnerSide = integer(raw.winnerSide, "summary_winner_side", 0);
    if (winnerSide > 1) throw error("invalid_payload", "summary_winner_side_invalid");
  }
  if (status === "completed" && (startedAtUnixMs === null || endedAtUnixMs === null || winnerSide === null)) {
    throw error("invalid_payload", "summary_completed_fields_required");
  }
  if (status === "interrupted" && winnerSide !== null) {
    throw error("invalid_payload", "summary_interrupted_winner_invalid");
  }
  return {
    status,
    startedAtUnixMs,
    endedAtUnixMs,
    winnerSide,
    reason: optionalString(raw.reason, 128),
    players: normalizePlayers(raw.players),
  };
}

function normalizeEvents(rawEvents, matchId) {
  if (!Array.isArray(rawEvents) || rawEvents.length > MAX_EVENTS) {
    throw error("invalid_payload", "events_invalid");
  }
  let previousSeq = 0;
  return rawEvents.map((raw, index) => {
    if (!isPlainObject(raw)) throw error("invalid_payload", `event_${index}_invalid`);
    const schemaVersion = integer(raw.schemaVersion, `event_${index}_schema_version`, 1);
    if (schemaVersion !== SCHEMA_VERSION || String(raw.matchId || "") !== matchId) {
      throw error("invalid_payload", `event_${index}_identity_invalid`);
    }
    const seq = integer(raw.seq, `event_${index}_seq`, 1);
    if (seq !== previousSeq + 1) {
      throw error("invalid_payload", `event_${index}_seq_order_invalid`);
    }
    previousSeq = seq;
    const actorSide = raw.actorSide === undefined || raw.actorSide === null
      ? null
      : integer(raw.actorSide, `event_${index}_actor_side`, 0);
    if (actorSide !== null && actorSide > 1) {
      throw error("invalid_payload", `event_${index}_actor_side_invalid`);
    }
    if (!isPlainObject(raw.payload)) {
      throw error("invalid_payload", `event_${index}_payload_invalid`);
    }
    return {
      schemaVersion,
      matchId,
      seq,
      occurredAtUnixMs: integer(raw.occurredAtUnixMs, `event_${index}_occurred_at`, 1),
      round: integer(raw.round, `event_${index}_round`, 0),
      phase: optionalString(raw.phase, 64),
      type: requiredString(raw.type, `event_${index}_type`, 64),
      actorSide,
      payload: raw.payload,
    };
  });
}

function normalizeSnapshots(rawSnapshots, matchId, lastEventSeq) {
  if (!Array.isArray(rawSnapshots) || rawSnapshots.length > MAX_SNAPSHOTS) {
    throw error("invalid_payload", "snapshots_invalid");
  }
  const keys = new Set();
  return rawSnapshots.map((raw, index) => {
    if (!isPlainObject(raw)) throw error("invalid_payload", `snapshot_${index}_invalid`);
    const schemaVersion = integer(raw.schemaVersion, `snapshot_${index}_schema_version`, 1);
    if (schemaVersion !== SCHEMA_VERSION || String(raw.matchId || "") !== matchId) {
      throw error("invalid_payload", `snapshot_${index}_identity_invalid`);
    }
    const afterSeq = integer(raw.afterSeq, `snapshot_${index}_after_seq`, 0);
    if (afterSeq > lastEventSeq) {
      throw error("invalid_payload", `snapshot_${index}_after_seq_invalid`);
    }
    const reason = requiredString(raw.reason, `snapshot_${index}_reason`, 128);
    const key = `${afterSeq}:${reason}`;
    if (keys.has(key)) throw error("invalid_payload", `snapshot_${index}_duplicate`);
    keys.add(key);
    if (!isPlainObject(raw.state)) {
      throw error("invalid_payload", `snapshot_${index}_state_invalid`);
    }
    return {
      schemaVersion,
      matchId,
      round: integer(raw.round, `snapshot_${index}_round`, 0),
      phase: optionalString(raw.phase, 64),
      reason,
      afterSeq,
      occurredAtUnixMs: integer(raw.occurredAtUnixMs, `snapshot_${index}_occurred_at`, 1),
      state: raw.state,
    };
  });
}

function normalizeFinalPayload(raw, expectedMatchId) {
  if (!isPlainObject(raw)) throw error("invalid_payload", "payload_object_required");
  const matchId = requiredString(raw.matchId, "match_id", 36).toLowerCase();
  if (!UUID_RE.test(matchId) || matchId !== String(expectedMatchId || "").toLowerCase()) {
    throw error("invalid_payload", "match_id_invalid");
  }
  const schemaVersion = integer(raw.schemaVersion, "schema_version", 1);
  if (schemaVersion !== SCHEMA_VERSION) throw error("invalid_payload", "schema_version_unsupported");
  const summary = normalizeSummary(raw.summary);
  const events = normalizeEvents(raw.events, matchId);
  if (summary.status === "completed" && !events.some((entry) => entry.type === "MATCH_FINISHED")) {
    throw error("invalid_payload", "completed_terminal_event_required");
  }
  const lastEventSeq = events.length > 0 ? events[events.length - 1].seq : 0;
  const snapshots = normalizeSnapshots(raw.snapshots, matchId, lastEventSeq);
  return { schemaVersion, matchId, summary, events, snapshots };
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (!isPlainObject(value)) return value;
  const result = {};
  for (const key of Object.keys(value).sort()) result[key] = canonicalize(value[key]);
  return result;
}

function payloadHash(payload) {
  return crypto.createHash("sha256").update(JSON.stringify(canonicalize(payload))).digest("hex");
}

async function storeFinal(rawPayload, expectedMatchId) {
  const payload = normalizeFinalPayload(rawPayload, expectedMatchId);
  const hash = payloadHash(payload);
  const summary = payload.summary;
  return db.withTransaction(async (client) => {
    await client.query(`SELECT pg_advisory_xact_lock(hashtext($1))`, [payload.matchId]);
    const existing = await client.query(
      `SELECT payload_sha256 FROM matches WHERE match_id = $1 FOR UPDATE`,
      [payload.matchId]
    );
    if (existing.rowCount > 0) {
      if (String(existing.rows[0].payload_sha256) === hash) {
        return { stored: true, duplicate: true, matchId: payload.matchId };
      }
      throw error("match_payload_conflict", "match_payload_conflict", 409);
    }

    await client.query(
      `INSERT INTO matches (
         match_id, schema_version, status, started_at, ended_at, winner_side,
         end_reason, players, summary, event_count, snapshot_count, payload_sha256,
         expires_at
       ) VALUES (
         $1, $2, $3,
         CASE WHEN $4::BIGINT IS NULL THEN NULL ELSE TO_TIMESTAMP($4::DOUBLE PRECISION / 1000.0) END,
         CASE WHEN $5::BIGINT IS NULL THEN NULL ELSE TO_TIMESTAMP($5::DOUBLE PRECISION / 1000.0) END,
         $6, $7, $8::JSONB, $9::JSONB, $10, $11, $12,
         COALESCE(
           CASE WHEN $5::BIGINT IS NULL THEN NULL ELSE TO_TIMESTAMP($5::DOUBLE PRECISION / 1000.0) END,
           NOW()
         ) + ($13::TEXT || ' days')::INTERVAL
       )`,
      [
        payload.matchId,
        payload.schemaVersion,
        summary.status,
        summary.startedAtUnixMs,
        summary.endedAtUnixMs,
        summary.winnerSide,
        summary.reason,
        JSON.stringify(summary.players),
        JSON.stringify(summary),
        payload.events.length,
        payload.snapshots.length,
        hash,
        RETENTION_DAYS,
      ]
    );

    if (payload.events.length > 0) {
      await client.query(
        `INSERT INTO match_events (
           match_id, seq, schema_version, occurred_at, round_number, phase,
           event_type, actor_side, payload
         )
         SELECT $1, (item->>'seq')::BIGINT, (item->>'schemaVersion')::SMALLINT,
           TO_TIMESTAMP((item->>'occurredAtUnixMs')::DOUBLE PRECISION / 1000.0),
           (item->>'round')::INTEGER, item->>'phase', item->>'type',
           NULLIF(item->>'actorSide', '')::SMALLINT, item->'payload'
         FROM JSONB_ARRAY_ELEMENTS($2::JSONB) AS item`,
        [payload.matchId, JSON.stringify(payload.events)]
      );
    }
    if (payload.snapshots.length > 0) {
      await client.query(
        `INSERT INTO match_snapshots (
           match_id, after_seq, reason, schema_version, occurred_at,
           round_number, phase, state
         )
         SELECT $1, (item->>'afterSeq')::BIGINT, item->>'reason',
           (item->>'schemaVersion')::SMALLINT,
           TO_TIMESTAMP((item->>'occurredAtUnixMs')::DOUBLE PRECISION / 1000.0),
           (item->>'round')::INTEGER, item->>'phase', item->'state'
         FROM JSONB_ARRAY_ELEMENTS($2::JSONB) AS item`,
        [payload.matchId, JSON.stringify(payload.snapshots)]
      );
    }
    return { stored: true, duplicate: false, matchId: payload.matchId };
  });
}

async function pruneExpired() {
  const result = await db.query(`DELETE FROM matches WHERE expires_at <= NOW()`);
  return Number(result.rowCount) || 0;
}

module.exports = {
  SCHEMA_VERSION,
  RETENTION_DAYS,
  MAX_EVENTS,
  MAX_SNAPSHOTS,
  normalizeFinalPayload,
  payloadHash,
  storeFinal,
  pruneExpired,
};
