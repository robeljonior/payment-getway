# Orchestrator service (Go)

Layer 2 of [the four layers](../docs/02-architecture.md#the-four-layers): it decides what happens next for every payment. It also owns layer 4, the record: the [six parts](../docs/08-data-model.md#the-six-parts-of-layer-4) (except part 5, clients, which the gateway owns), the double-entry ledger, outbox events and the audit log.

It is the **only** place that decides that a payment succeeded or failed. It always writes the result, the ledger journal and the event together, in one database transaction.

Nobody on the internet can call it. Only the [gateway](../gateway/README.md), checkout and the connectors call it, on the private network, with the shared secret in `X-Internal-Token`.

## What is in it

| Folder | What it does |
|---|---|
| `cmd/orchestrator` | Starts the service |
| `internal/payments` | Payments, tries, successes, failures, the ledger, events, the audit log, banks and wallets |
| `internal/api` | The internal HTTP API below |
| `internal/expiry` | Ends payments whose time ran out (never while a try is still running) |
| `internal/store` | Opens PostgreSQL and runs the migrations in `internal/store/migrations` |

### The rules are inside the database

The database refuses anything that breaks a rule, even when the code has a bug:

- A payment can be paid only once.
- Successes, failures, ledger journals and the audit log can never be changed or deleted.
- A success must be for the exact amount, and the payment must still be open.
- A payment can't end (expire or be cancelled) while a try is still running, because money may be moving.
- A failed try can become a success only through an approved correction.
- Every ledger journal must balance (debits = credits).
- A try's step only moves forward, and it is locked once it has a result.

## Run it

You need **Go 1.24 or newer** and PostgreSQL. From the repository root, `docker compose up -d postgres` starts PostgreSQL with all the databases.

```bash
cd orchestrator
export DATABASE_URL=postgres://postgres:postgres@localhost:5432/orchestrator
export INTERNAL_TOKEN=a-long-random-secret-of-at-least-32-characters   # the same value in the gateway
go run ./cmd/orchestrator
```

| Setting | Default | Meaning |
|---|---|---|
| `PORT` | `8090` | Port to listen on |
| `DATABASE_URL` | `postgres://postgres@localhost:5432/orchestrator` | The orchestrator's own database |
| `INTERNAL_TOKEN` | none, required | The shared secret. At least 32 characters. |
| `CHECKOUT_BASE_URL` | `http://localhost:8080/checkout` | Where customers pay. The payment ID is added to the end. |
| `FEE_BASIS_POINTS` | `150` | The fee, 1.5%. A placeholder from decision D6: set the real value before live. |
| `EXPIRY_INTERVAL` | `30s` | How often the expiry job runs. `0` turns it off. |

## Run the tests

```bash
DB_PASSWORD=postgres go test ./...
```

The tests use the `orchestrator_test` database (or `TEST_DATABASE_URL`). They cover every database rule above, the ledger, the payments view, the expiry job and the internal API.

## The internal API

Every call needs `X-Internal-Token`. Send `X-Request-Id` to follow one request through both services and into the audit log. Errors have the shape `{"error": {"type", "code", "message"}}`. Amounts are whole **santim** going in (`150000` = 1,500.00 ETB) and strings like `"1500.00"` coming out.

### For the gateway

| Call | What it does |
|---|---|
| `POST /internal/v1/payments` | Creates a payment. Answers `201`, or `200` with the same payment if the `createKey` was used before. |
| `GET /internal/v1/payments/{id}?merchantId=…&mode=…` | Reads one payment, only for its own client and mode |
| `GET /internal/v1/payments/by-order/{orderReference}?merchantId=…&mode=…` | The newest payment for an order |

The answer is always the PaymentSession object of the [Payment API](../docs/07-api.md), so the gateway passes it to the client as it is.

### The connector contract

The connectors (layer 3) are built by others. A connector talks to one bank or wallet in its own language, and tells the orchestrator what happened with these calls. **A connector never decides that a payment succeeded on its own:** it reports only what the bank confirmed, and the orchestrator and the database check every rule.

Send `X-Caller: <your connector name>` so the audit log shows who reported each result.

| Call | Body | What it does |
|---|---|---|
| `POST /internal/v1/payments/{id}/attempts` | `{"method": "push", "providerCode": "TELEBIRR", "payerMasked": "+2519****1234"}` | Starts a try on an open payment. Checkout usually calls this. Returns the try. |
| `GET /internal/v1/attempts/{id}` | | Reads a try: its step, the bank reference and its result |
| `POST /internal/v1/attempts/{id}/step` | `{"step": "PENDING", "providerRef": "TB-123"}` | Moves a running try forward: `ACTION_REQUIRED`, then `PROCESSING`, then `PENDING`. The bank reference can be set once. |
| `POST /internal/v1/attempts/{id}/success` | `{"confirmedBy": "status_check", "providerRef": "TB-123"}` | The bank confirmed the money. Writes the success, ledger journal J1 and the `payment.succeeded` event. |
| `POST /internal/v1/attempts/{id}/failure` | `{"reason": "DECLINED", "providerCode": "insufficient_balance"}` | This try failed. The payment stays open, so the customer can try another way. |
| `POST /internal/v1/payments/{id}/cancel` | | The customer cancelled. Refused while a try is still running. |

- `method` is one of `push`, `otp`, `redirect`, `qr`, `request_to_pay`, `bank_transfer`, `card`.
- `reason` for a failed try is one of `DECLINED`, `OTP_FAILED`, `NOT_APPROVED`, `AMOUNT_MISMATCH`.
- `confirmedBy` is `status_check` (we asked the bank and it said paid) or `reconciliation` (found in the bank statement). A callback alone is never enough: check the status with the bank first.
- A late success for a try that was already recorded as failed needs `correctsFailureId` and `approvalId` (the two-person rule).
- A repeated or late result is refused with `409`, and nothing is written.

### Banks and wallets (part 6)

| Call | What it does |
|---|---|
| `GET /internal/v1/providers` | Lists every bank and wallet |
| `PUT /internal/v1/providers/{code}` | Adds or changes one: `{"nameEn": "telebirr", "type": "wallet", "connector": "telebirr", "status": "TESTING"}` |

A provider starts as `DRAFT`. Test payments can use `TESTING` or `ACTIVE` providers. Live payments can use only `ACTIVE` ones. The Back Office will call these later.

## Try it

With the orchestrator and the gateway running, create a payment through the gateway (see its README), then pretend to be a connector:

```bash
T=a-long-random-secret-of-at-least-32-characters
O=http://localhost:8090/internal/v1
curl -X PUT $O/providers/TEST_WALLET -H "X-Internal-Token: $T" \
  -d '{"nameEn":"Test wallet","type":"wallet","connector":"test","status":"TESTING"}'
curl -X POST $O/payments/ps_YOUR_PAYMENT/attempts -H "X-Internal-Token: $T" \
  -d '{"method":"push","providerCode":"TEST_WALLET"}'
curl -X POST $O/attempts/att_YOUR_TRY/success -H "X-Internal-Token: $T" -H "X-Caller: my-test" \
  -d '{"confirmedBy":"status_check","providerRef":"TW-1"}'
```

Then `GET /api/v1/payments/verify/{orderReference}` on the gateway shows `SUCCEEDED`, with the fee and the net amount.
