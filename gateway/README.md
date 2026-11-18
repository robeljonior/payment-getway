# Payment gateway: phase 1 code

Java 17+, Spring Boot 3.3 and PostgreSQL 16. The design is in [`../docs`](../docs) and [`../README.md`](../README.md).

## What is built so far

| Layer ([the four layers](../docs/02-architecture.md#the-four-layers)) | Code | Built now |
|---|---|---|
| **1. Gateway**: is the request real and allowed? | `gateway/` | API key check (only a hash of each key is stored), test and live keys kept apart, client status checks, rate limits, request checks with clear error codes, idempotency keys, a request ID on every answer |
| **2. Orchestrator**: what happens next? | `orchestrator/` | Create a payment, start and move tries, record a success (with ledger journal J1 and the `payment.succeeded` event in one transaction), record a failed try, end a payment (expired or cancelled), the expiry job |
| **3. Third parties** | `connectors/` | The connector contract only. The test wallet and test bank come next. |
| **4. Ledger and database** | `record/` and `src/main/resources/db/migration` | The [six parts](../docs/08-data-model.md#the-six-parts-of-layer-4), the double-entry ledger, outbox events, idempotency keys and the audit log |

The Payment API has three endpoints so far:

- `POST /api/v1/payment-sessions`: create a payment
- `GET /api/v1/payment-sessions/{id}`: read it
- `GET /api/v1/payments/verify/{orderReference}`: check it before delivering goods

### The rules are inside the database

The database itself refuses anything that breaks a rule, even when the code has a bug:

- A payment can be paid only once.
- Successes, failures, ledger journals and the audit log can never be changed or deleted.
- A success must be for the exact amount, and the payment must still be open.
- A payment can't end (expire or be cancelled) while a try is still running, because money may be moving.
- A failed try can become a success only through an approved correction.
- Every ledger journal must balance (debits = credits).
- A try's step only moves forward, and it is locked once it has a result.

## Run it on your computer

You need **Java 17 or newer** and **Docker** (for PostgreSQL). Maven is not needed: `./mvnw` downloads it.

```bash
cd payment_getway/gateway
docker compose up -d                        # starts PostgreSQL
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile creates a demo client and prints a new **test** secret key. Then:

```bash
curl -X POST http://localhost:8080/api/v1/payment-sessions \
  -H "Authorization: Bearer sk_test_YOUR_KEY" \
  -H "Idempotency-Key: order-10001-try-1" \
  -H "Content-Type: application/json" \
  -d '{"orderReference":"ORDER_10001","amount":"1500.00","currency":"ETB","returnUrl":"https://shop.example.com/payment/return"}'
```

Database settings can be changed with `DB_URL`, `DB_USER` and `DB_PASSWORD`. With the Docker database, set `DB_PASSWORD=postgres`.

## Run the tests

```bash
DB_PASSWORD=postgres ./mvnw test
```

The tests use the `gateway_test` database. They cover layer 1 (keys, modes, request checks, idempotency), the payments view (every status), the ledger, and every database rule above.

## Next steps (phase 1 build order)

1. Test providers behind the connector contract: test wallet (push), test bank (OTP), test redirect bank.
2. Hosted checkout pages and the checkout API (start a try, enter a code).
3. Provider callbacks and the pending checker.
4. The webhook worker: signed webhooks from the outbox, with retries.
5. Back Office: banks, clients, KYB, keys (this replaces the `dev` demo client).
6. Refunds, settlement and reconciliation.
