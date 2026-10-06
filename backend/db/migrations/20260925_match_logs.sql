BEGIN;

CREATE TABLE IF NOT EXISTS matches (
  match_id UUID PRIMARY KEY,
  schema_version SMALLINT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('completed', 'interrupted')),
  started_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  winner_side SMALLINT CHECK (winner_side IS NULL OR winner_side IN (0, 1)),
  end_reason TEXT NOT NULL DEFAULT '',
  players JSONB NOT NULL DEFAULT '[]'::jsonb,
  summary JSONB NOT NULL DEFAULT '{}'::jsonb,
  event_count INTEGER NOT NULL DEFAULT 0 CHECK (event_count >= 0),
  snapshot_count INTEGER NOT NULL DEFAULT 0 CHECK (snapshot_count >= 0),
  payload_sha256 CHAR(64) NOT NULL,
  stored_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_matches_expires_at ON matches (expires_at);
CREATE INDEX IF NOT EXISTS idx_matches_ended_at ON matches (ended_at DESC);

CREATE TABLE IF NOT EXISTS match_events (
  match_id UUID NOT NULL REFERENCES matches(match_id) ON DELETE CASCADE,
  seq BIGINT NOT NULL CHECK (seq > 0),
  schema_version SMALLINT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  round_number INTEGER NOT NULL CHECK (round_number >= 0),
  phase TEXT NOT NULL DEFAULT '',
  event_type TEXT NOT NULL,
  actor_side SMALLINT CHECK (actor_side IS NULL OR actor_side IN (0, 1)),
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (match_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_match_events_type ON match_events (event_type);

CREATE TABLE IF NOT EXISTS match_snapshots (
  match_id UUID NOT NULL REFERENCES matches(match_id) ON DELETE CASCADE,
  after_seq BIGINT NOT NULL CHECK (after_seq >= 0),
  reason TEXT NOT NULL,
  schema_version SMALLINT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  round_number INTEGER NOT NULL CHECK (round_number >= 0),
  phase TEXT NOT NULL DEFAULT '',
  state JSONB NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (match_id, after_seq, reason)
);

COMMIT;
