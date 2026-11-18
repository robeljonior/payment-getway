-- The gateway service's own database.
-- Part 5: clients (merchants), their settings and their API keys. See docs/08-data-model.md.
-- Payments, banks (part 6) and the ledger belong to the orchestrator service and its database.

CREATE TABLE merchants (
    id          text PRIMARY KEY,
    legal_name  text NOT NULL,
    trade_name  text NOT NULL,
    risk_level  text NOT NULL DEFAULT 'medium' CHECK (risk_level IN ('low', 'medium', 'high')),
    status      text NOT NULL DEFAULT 'SIGNED_UP'
                CHECK (status IN ('SIGNED_UP', 'KYB_SUBMITTED', 'KYB_REVIEW', 'LIVE', 'SUSPENDED', 'CLOSED')),
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- One row per client and mode (test or live).
CREATE TABLE merchant_settings (
    merchant_id           text NOT NULL REFERENCES merchants (id),
    mode                  text NOT NULL CHECK (mode IN ('test', 'live')),
    limit_per_payment     bigint NOT NULL CHECK (limit_per_payment > 0),   -- santim
    return_url_allowlist  text[] NOT NULL DEFAULT '{}',
    webhook_url           text,
    PRIMARY KEY (merchant_id, mode)
);

-- We store only a SHA-256 hash of each secret key, never the key itself.
CREATE TABLE api_keys (
    id          text PRIMARY KEY,
    merchant_id text NOT NULL REFERENCES merchants (id),
    mode        text NOT NULL CHECK (mode IN ('test', 'live')),
    type        text NOT NULL CHECK (type IN ('secret', 'public', 'webhook')),
    key_hash    text NOT NULL UNIQUE,
    last4       text NOT NULL,
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz
);
