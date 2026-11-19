# Gateway service (Java)

Java 17+, Spring Boot 3.3 and PostgreSQL 16. This is layer 1 of [the four layers](../docs/02-architecture.md#the-four-layers), the front door. It answers one question: **is the request real and allowed?** Then it hands the payment to the [orchestrator](../orchestrator/README.md), a separate service written in Go.

The gateway never decides a payment result and never moves money.

## What it does

| Check | How |
|---|---|
| **Who is calling?** | API key check. Only a SHA-256 hash of each key is stored. Test and live keys are kept apart, and the client's status is checked (a live key needs an approved client, a suspended client can only read). |
| **Too many calls?** | Rate limits per key, per IP and for creating payments |
| **Is the request right?** | Format, amount (a string like `"1500.00"`, never a number), the client's limit per payment, registered return and webhook URLs, with clear error codes |
| **Is it new?** | Idempotency keys. A retry gets the first answer back. The same key with a different body is refused. |
| **Which request?** | A request ID on every answer. It is passed on to the orchestrator and into its audit log. |

Its own database holds only part 5 (clients, their settings and API keys) and the idempotency keys. Payments, the ledger and banks live in the orchestrator's database.

The Payment API has three endpoints so far:

- `POST /api/v1/payment-sessions`: create a payment
- `GET /api/v1/payment-sessions/{id}`: read it
- `GET /api/v1/payments/verify/{orderReference}`: check it before delivering goods

### How a retry stays safe across two services

The gateway sends the orchestrator a **create key** made from the client, the mode and the Idempotency-Key. The orchestrator makes only one payment per create key. So if the orchestrator is slow or down, the client gets `503` and can retry with the same Idempotency-Key, and it still gets exactly one payment.

## Run it on your computer

You need **Java 17 or newer**. Maven is not needed: `./mvnw` downloads it. Start PostgreSQL and the orchestrator first (see the [orchestrator README](../orchestrator/README.md)).

```bash
docker compose up -d postgres                # from the repository root
cd gateway
export DB_PASSWORD=postgres
export INTERNAL_TOKEN=a-long-random-secret-of-at-least-32-characters   # the same value as the orchestrator
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Or run everything in Docker from the repository root: `docker compose --profile apps up -d --build`.

The `dev` profile creates a demo client and prints a new **test** secret key. Then:

```bash
curl -X POST http://localhost:8080/api/v1/payment-sessions \
  -H "Authorization: Bearer sk_test_YOUR_KEY" \
  -H "Idempotency-Key: order-10001-try-1" \
  -H "Content-Type: application/json" \
  -d '{"orderReference":"ORDER_10001","amount":"1500.00","currency":"ETB","returnUrl":"https://shop.example.com/payment/return"}'
```

| Setting | Default | Meaning |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/gateway`, `postgres`, empty | The gateway's own database |
| `ORCHESTRATOR_URL` | `http://localhost:8090` | Where the orchestrator is |
| `INTERNAL_TOKEN` | none, required | The shared secret. At least 32 characters. |
| `PORT` | `8080` | Port to listen on |

## Run the tests

```bash
DB_PASSWORD=postgres ./mvnw test
```

The tests use the `gateway_test` database and an in-memory stand-in for the orchestrator (`FakeOrchestrator`), so they don't need the orchestrator running. They cover keys, modes, client status, request checks, idempotency, and what happens when the orchestrator is down. The orchestrator has its own tests.

## Next steps (phase 1 build order)

1. Hosted checkout pages and the checkout API (start a try, enter a code), calling the orchestrator.
2. Provider callbacks, passed on to the right connector.
3. The webhook worker: signed webhooks from the orchestrator's outbox, with retries.
4. Back Office: banks, clients, KYB, keys (this replaces the `dev` demo client).
5. Refunds, settlement and reconciliation.

The connectors are built by others, against the [connector contract](../orchestrator/README.md#the-connector-contract).
