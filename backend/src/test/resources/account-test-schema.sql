CREATE TABLE accounts (
  account_key TEXT PRIMARY KEY,
  auth_kind TEXT NOT NULL DEFAULT 'guest',
  display_name TEXT NOT NULL DEFAULT '',
  profile_icon_id TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  client_migrated_at TIMESTAMPTZ,
  meta_revision BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE deleted_accounts (
  account_key TEXT PRIMARY KEY,
  deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
