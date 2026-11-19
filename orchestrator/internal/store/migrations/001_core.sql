-- The orchestrator's database: layer 4 of docs/02-architecture.md.
-- Part 6 (banks and wallets), parts 1 to 3 (success candidates, successes, failures),
-- part 4 (the payments view), the double-entry ledger, outbox events and the audit log.
-- Part 5 (clients) and API keys belong to the gateway service and its own database.
-- Money is always bigint santim.

-- Part 6: banks and wallets (providers)
CREATE TABLE providers (
    id          text PRIMARY KEY,
    code        text NOT NULL UNIQUE,
    name_en     text NOT NULL,
    name_am     text,
    type        text NOT NULL CHECK (type IN ('bank', 'wallet', 'switch', 'card')),
    connector   text NOT NULL,
    status      text NOT NULL DEFAULT 'DRAFT'
                CHECK (status IN ('DRAFT', 'TESTING', 'ACTIVE', 'PAUSED', 'RETIRED')),
    min_amount  bigint,
    max_amount  bigint,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- A small helper: some tables only ever get new rows.
CREATE FUNCTION forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows can never be changed or deleted', TG_TABLE_NAME
        USING ERRCODE = 'check_violation';
END $$;

------------------------------------------------------------------
-- Part 1: success candidates
------------------------------------------------------------------
CREATE TABLE payment_sessions (
    id                 text PRIMARY KEY,
    create_key         text NOT NULL UNIQUE,   -- the gateway's idempotency key, so a retried call never makes two payments
    merchant_id        text NOT NULL,   -- the client, owned by the gateway service
    mode               text NOT NULL CHECK (mode IN ('test', 'live')),
    order_reference    text NOT NULL,
    amount             bigint NOT NULL CHECK (amount > 0),
    currency           text NOT NULL CHECK (currency = 'ETB'),
    description        text,
    customer_hint_enc  bytea,
    return_url         text NOT NULL,
    webhook_url        text,
    metadata           jsonb NOT NULL DEFAULT '{}',
    expires_at         timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_sessions_by_order ON payment_sessions (merchant_id, mode, order_reference, created_at DESC);
CREATE INDEX payment_sessions_by_expiry ON payment_sessions (expires_at);

-- A session never changes. The one exception: the customer hint is wiped after 90 days.
CREATE FUNCTION sessions_only_wipe_hint() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payment_sessions rows can never be deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.customer_hint_enc IS NOT NULL
       OR (to_jsonb(NEW) - 'customer_hint_enc') <> (to_jsonb(OLD) - 'customer_hint_enc') THEN
        RAISE EXCEPTION 'a payment session can only have its customer hint wiped' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER payment_sessions_guard BEFORE UPDATE OR DELETE ON payment_sessions
    FOR EACH ROW EXECUTE FUNCTION sessions_only_wipe_hint();

CREATE TABLE payment_attempts (
    id            text PRIMARY KEY,
    session_id    text NOT NULL REFERENCES payment_sessions (id),
    mode          text NOT NULL CHECK (mode IN ('test', 'live')),
    method        text NOT NULL CHECK (method IN ('push', 'otp', 'redirect', 'qr', 'request_to_pay', 'bank_transfer', 'card')),
    provider_id   text NOT NULL REFERENCES providers (id),
    payer_masked  text,
    provider_ref  text,
    step          text NOT NULL DEFAULT 'ACTION_REQUIRED' CHECK (step IN ('ACTION_REQUIRED', 'PROCESSING', 'PENDING')),
    next_action   jsonb,
    otp_tries     int NOT NULL DEFAULT 0 CHECK (otp_tries BETWEEN 0 AND 3),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_attempts_by_session ON payment_attempts (session_id, created_at DESC);

------------------------------------------------------------------
-- The ledger (double entry). Written in the same transaction as a success.
------------------------------------------------------------------
CREATE TABLE ledger_accounts (
    id        bigserial PRIMARY KEY,
    code      text NOT NULL,              -- for example merchant:m_123:pending
    mode      text NOT NULL CHECK (mode IN ('test', 'live')),
    type      text NOT NULL CHECK (type IN ('asset', 'liability', 'revenue', 'expense')),
    currency  text NOT NULL CHECK (currency = 'ETB'),
    UNIQUE (code, mode)
);

CREATE TABLE ledger_journals (
    id                   text PRIMARY KEY,
    source_event_id      text NOT NULL UNIQUE,   -- the same business event can never post twice
    type                 text NOT NULL,
    mode                 text NOT NULL CHECK (mode IN ('test', 'live')),
    value_date           date NOT NULL,
    posted_at            timestamptz NOT NULL DEFAULT now(),
    reverses_journal_id  text REFERENCES ledger_journals (id)
);

CREATE TABLE ledger_entries (
    id          bigserial PRIMARY KEY,
    journal_id  text NOT NULL REFERENCES ledger_journals (id),
    account_id  bigint NOT NULL REFERENCES ledger_accounts (id),
    direction   text NOT NULL CHECK (direction IN ('debit', 'credit')),
    amount      bigint NOT NULL CHECK (amount > 0),
    currency    text NOT NULL CHECK (currency = 'ETB')
);
CREATE INDEX ledger_entries_by_journal ON ledger_entries (journal_id);
CREATE INDEX ledger_entries_by_account ON ledger_entries (account_id);

-- At commit, every journal must have at least two lines, and debits must equal credits.
CREATE FUNCTION check_journal_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    debits  bigint;
    credits bigint;
    lines   int;
BEGIN
    SELECT coalesce(sum(amount) FILTER (WHERE direction = 'debit'), 0),
           coalesce(sum(amount) FILTER (WHERE direction = 'credit'), 0),
           count(*)
      INTO debits, credits, lines
      FROM ledger_entries WHERE journal_id = NEW.journal_id;
    IF lines < 2 OR debits <> credits THEN
        RAISE EXCEPTION 'journal % is not balanced: debits %, credits %, lines %',
            NEW.journal_id, debits, credits, lines USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ledger_entries_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();

CREATE TRIGGER ledger_journals_no_change BEFORE UPDATE OR DELETE ON ledger_journals
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER ledger_entries_no_change BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

------------------------------------------------------------------
-- Part 2: successes. Part 3: failures. Both only ever get new rows.
------------------------------------------------------------------
CREATE TABLE payment_successes (
    id                   text PRIMARY KEY,
    session_id           text NOT NULL UNIQUE REFERENCES payment_sessions (id),   -- paid only once
    attempt_id           text NOT NULL UNIQUE REFERENCES payment_attempts (id),
    mode                 text NOT NULL CHECK (mode IN ('test', 'live')),
    amount               bigint NOT NULL CHECK (amount > 0),
    fee                  bigint NOT NULL CHECK (fee >= 0),
    net                  bigint NOT NULL CHECK (net >= 0),
    provider_ref         text,
    confirmed_by         text NOT NULL CHECK (confirmed_by IN ('status_check', 'reconciliation')),
    journal_id           text NOT NULL UNIQUE REFERENCES ledger_journals (id),
    corrects_failure_id  text,
    approval_id          text,
    confirmed_at         timestamptz NOT NULL DEFAULT now(),
    CHECK (fee + net = amount),
    CHECK (corrects_failure_id IS NULL OR approval_id IS NOT NULL)
);

CREATE TABLE payment_failures (
    id             text PRIMARY KEY,
    session_id     text NOT NULL REFERENCES payment_sessions (id),
    attempt_id     text REFERENCES payment_attempts (id),   -- empty: the whole payment ended
    mode           text NOT NULL CHECK (mode IN ('test', 'live')),
    reason         text NOT NULL CHECK (reason IN ('DECLINED', 'OTP_FAILED', 'NOT_APPROVED', 'AMOUNT_MISMATCH', 'EXPIRED', 'CANCELLED')),
    provider_code  text,
    failed_at      timestamptz NOT NULL DEFAULT now(),
    -- EXPIRED and CANCELLED end the whole payment; the other reasons belong to one try.
    CHECK ((attempt_id IS NULL) = (reason IN ('EXPIRED', 'CANCELLED')))
);
CREATE UNIQUE INDEX payment_failures_one_per_try ON payment_failures (attempt_id) WHERE attempt_id IS NOT NULL;
CREATE UNIQUE INDEX payment_failures_one_ending ON payment_failures (session_id) WHERE attempt_id IS NULL;
CREATE INDEX payment_failures_by_session ON payment_failures (session_id, failed_at DESC);

ALTER TABLE payment_successes
    ADD CONSTRAINT payment_successes_corrects_fk FOREIGN KEY (corrects_failure_id) REFERENCES payment_failures (id);

CREATE TRIGGER payment_successes_no_change BEFORE UPDATE OR DELETE ON payment_successes
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER payment_failures_no_change BEFORE UPDATE OR DELETE ON payment_failures
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

-- Rules for a new success. The session row is locked so two results can't race.
CREATE FUNCTION check_new_success() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    s payment_sessions%ROWTYPE;
    attempt_session text;
    try_failure text;
BEGIN
    SELECT * INTO s FROM payment_sessions WHERE id = NEW.session_id FOR UPDATE;
    IF NEW.amount <> s.amount THEN
        RAISE EXCEPTION 'success amount % is not the session amount %', NEW.amount, s.amount USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.mode <> s.mode THEN
        RAISE EXCEPTION 'success mode does not match the session' USING ERRCODE = 'check_violation';
    END IF;
    SELECT session_id INTO attempt_session FROM payment_attempts WHERE id = NEW.attempt_id;
    IF attempt_session IS DISTINCT FROM NEW.session_id THEN
        RAISE EXCEPTION 'attempt % does not belong to session %', NEW.attempt_id, NEW.session_id USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM payment_failures WHERE session_id = NEW.session_id AND attempt_id IS NULL) THEN
        RAISE EXCEPTION 'session % already ended without payment', NEW.session_id USING ERRCODE = 'check_violation';
    END IF;
    SELECT id INTO try_failure FROM payment_failures WHERE attempt_id = NEW.attempt_id;
    IF try_failure IS NOT NULL AND NEW.corrects_failure_id IS DISTINCT FROM try_failure THEN
        RAISE EXCEPTION 'attempt % already failed; only an approved correction can add a success', NEW.attempt_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER payment_successes_rules BEFORE INSERT ON payment_successes
    FOR EACH ROW EXECUTE FUNCTION check_new_success();

-- Rules for a new failure.
CREATE FUNCTION check_new_failure() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    attempt_session text;
BEGIN
    PERFORM 1 FROM payment_sessions WHERE id = NEW.session_id FOR UPDATE;
    IF NEW.attempt_id IS NOT NULL THEN
        SELECT session_id INTO attempt_session FROM payment_attempts WHERE id = NEW.attempt_id;
        IF attempt_session IS DISTINCT FROM NEW.session_id THEN
            RAISE EXCEPTION 'attempt % does not belong to session %', NEW.attempt_id, NEW.session_id USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (SELECT 1 FROM payment_successes WHERE attempt_id = NEW.attempt_id) THEN
            RAISE EXCEPTION 'attempt % already succeeded', NEW.attempt_id USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM payment_successes WHERE session_id = NEW.session_id) THEN
            RAISE EXCEPTION 'session % is already paid', NEW.session_id USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (SELECT 1 FROM payment_attempts a
                    WHERE a.session_id = NEW.session_id
                      AND NOT EXISTS (SELECT 1 FROM payment_failures f WHERE f.attempt_id = a.id)) THEN
            RAISE EXCEPTION 'session % has a try still running; money may be moving', NEW.session_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER payment_failures_rules BEFORE INSERT ON payment_failures
    FOR EACH ROW EXECUTE FUNCTION check_new_failure();

-- A try can move forward while it runs. Once it has a result, it is locked.
CREATE FUNCTION check_attempt_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payment_attempts rows can never be deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM payment_successes WHERE attempt_id = OLD.id)
       OR EXISTS (SELECT 1 FROM payment_failures WHERE attempt_id = OLD.id) THEN
        RAISE EXCEPTION 'attempt % has a result and is locked', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF (NEW.id, NEW.session_id, NEW.mode, NEW.method, NEW.provider_id, NEW.created_at)
       IS DISTINCT FROM (OLD.id, OLD.session_id, OLD.mode, OLD.method, OLD.provider_id, OLD.created_at) THEN
        RAISE EXCEPTION 'only the step, next action, bank reference and code tries of an attempt can change'
            USING ERRCODE = 'check_violation';
    END IF;
    IF array_position(ARRAY['ACTION_REQUIRED', 'PROCESSING', 'PENDING'], NEW.step)
       < array_position(ARRAY['ACTION_REQUIRED', 'PROCESSING', 'PENDING'], OLD.step) THEN
        RAISE EXCEPTION 'an attempt step can only move forward' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.provider_ref IS NOT NULL AND NEW.provider_ref IS DISTINCT FROM OLD.provider_ref THEN
        RAISE EXCEPTION 'the bank reference of an attempt is set once' USING ERRCODE = 'check_violation';
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END $$;
CREATE TRIGGER payment_attempts_rules BEFORE UPDATE OR DELETE ON payment_attempts
    FOR EACH ROW EXECUTE FUNCTION check_attempt_change();

CREATE TABLE refunds (
    id            text PRIMARY KEY,
    session_id    text NOT NULL REFERENCES payment_sessions (id),
    mode          text NOT NULL CHECK (mode IN ('test', 'live')),
    amount        bigint NOT NULL CHECK (amount > 0),
    status        text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    reason        text NOT NULL CHECK (reason IN ('customer_request', 'duplicate', 'fraud', 'other')),
    requested_by  text NOT NULL CHECK (requested_by IN ('api', 'staff')),
    provider_ref  text,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refunds_by_session ON refunds (session_id);

------------------------------------------------------------------
-- Part 4: the payments view. It stores nothing.
------------------------------------------------------------------
CREATE VIEW payments_view AS
SELECT s.id                              AS session_id,
       s.merchant_id,
       s.mode,
       s.order_reference,
       s.amount,
       s.currency,
       s.description,
       s.return_url,
       s.metadata,
       s.expires_at,
       s.created_at,
       suc.fee,
       suc.net,
       suc.confirmed_at                  AS paid_at,
       paid.method                       AS paid_method,
       paid.payer_masked                 AS paid_payer_masked,
       coalesce(rf.refunded, 0)          AS amount_refunded,
       (SELECT count(*) FROM payment_attempts a WHERE a.session_id = s.id) AS tries,
       last_fail.reason                  AS last_failure_reason,
       CASE
           WHEN suc.id IS NOT NULL AND coalesce(rf.refunded, 0) >= s.amount THEN 'REFUNDED'
           WHEN suc.id IS NOT NULL               THEN 'SUCCEEDED'
           WHEN ending.reason = 'EXPIRED'        THEN 'EXPIRED'
           WHEN ending.reason IS NOT NULL        THEN 'FAILED'
           WHEN running.step = 'PENDING'         THEN 'PENDING'
           WHEN running.step = 'PROCESSING'      THEN 'PROCESSING'
           WHEN running.step = 'ACTION_REQUIRED' THEN 'AUTHORIZATION_REQUIRED'
           WHEN last_fail.reason IS NOT NULL     THEN 'AUTHORIZATION_REQUIRED'
           ELSE 'CREATED'
       END                               AS status
  FROM payment_sessions s
  LEFT JOIN payment_successes suc ON suc.session_id = s.id
  LEFT JOIN payment_attempts paid ON paid.id = suc.attempt_id
  LEFT JOIN payment_failures ending ON ending.session_id = s.id AND ending.attempt_id IS NULL
  LEFT JOIN LATERAL (SELECT sum(r.amount) AS refunded FROM refunds r
                      WHERE r.session_id = s.id AND r.status = 'SUCCEEDED') rf ON true
  LEFT JOIN LATERAL (SELECT a.step FROM payment_attempts a
                      WHERE a.session_id = s.id
                        AND NOT EXISTS (SELECT 1 FROM payment_successes x WHERE x.attempt_id = a.id)
                        AND NOT EXISTS (SELECT 1 FROM payment_failures f WHERE f.attempt_id = a.id)
                      ORDER BY a.created_at DESC LIMIT 1) running ON true
  LEFT JOIN LATERAL (SELECT f.reason FROM payment_failures f
                      WHERE f.session_id = s.id AND f.attempt_id IS NOT NULL
                      ORDER BY f.failed_at DESC LIMIT 1) last_fail ON true;

------------------------------------------------------------------
-- Events and audit
------------------------------------------------------------------
CREATE TABLE outbox_events (
    id            text PRIMARY KEY,
    merchant_id   text NOT NULL,
    mode          text NOT NULL CHECK (mode IN ('test', 'live')),
    type          text NOT NULL,
    payload       jsonb NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz
);
CREATE INDEX outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE audit_logs (
    id              bigserial PRIMARY KEY,
    actor_type      text NOT NULL CHECK (actor_type IN ('merchant_key', 'staff', 'system', 'provider', 'connector')),
    actor_id        text NOT NULL,
    action          text NOT NULL,
    object_type     text,
    object_id       text,
    before_data     jsonb,
    after_data      jsonb,
    ip              text,
    correlation_id  text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER audit_logs_no_change BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
