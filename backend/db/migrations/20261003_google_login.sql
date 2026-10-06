CREATE TABLE IF NOT EXISTS external_identities (
  provider TEXT NOT NULL,
  provider_subject TEXT NOT NULL,
  account_key TEXT NOT NULL REFERENCES accounts(account_key) ON DELETE CASCADE,
  email TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (provider, provider_subject),
  UNIQUE (provider, account_key)
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash CHAR(64) PRIMARY KEY,
  account_key TEXT NOT NULL REFERENCES accounts(account_key) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_account
  ON refresh_tokens (account_key, expires_at);
