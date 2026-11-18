-- Layer 1, check "is it new?" (docs/core/api-conventions.md section 6).
-- The first answer to each Idempotency-Key is kept, so a retried request gets the same answer.
CREATE TABLE idempotency_keys (
    merchant_id      text NOT NULL REFERENCES merchants (id),
    mode             text NOT NULL CHECK (mode IN ('test', 'live')),
    endpoint         text NOT NULL,
    key              text NOT NULL CHECK (length(key) BETWEEN 1 AND 255),
    request_hash     text NOT NULL,
    status           text NOT NULL CHECK (status IN ('IN_PROGRESS', 'DONE')),
    response_status  int,
    response_body    text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz NOT NULL,
    PRIMARY KEY (merchant_id, mode, endpoint, key)
);
CREATE INDEX idempotency_keys_by_expiry ON idempotency_keys (expires_at);
