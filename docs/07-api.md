# 7. APIs

**Part of:** [Payment Gateway design](../README.md)

The gateway has four APIs and sends one kind of outgoing message (webhooks).

| API | Who calls it | How they sign in |
|---|---|---|
| **Payment API** | Clients' servers | Secret key: `Authorization: Bearer sk_test_...` or `sk_live_...` |
| **Checkout API** | Only our own hosted checkout page | The session ID in the URL plus a short-lived checkout token |
| **Provider callbacks** | Banks and wallets | Each provider's signature, and its IP addresses |
| **Back Office API** | Only the Back Office web app | Staff session with MFA |
| **Webhooks** (outgoing) | We call the client's server | Our signature in `X-Signature` |

The rules for errors, idempotency, versions, rate limits and retries are already written in the [API conventions](../../docs/core/api-conventions.md). The formal contract for the first endpoints is the [OpenAPI file](../../docs/payment-gateway/payment-api.openapi.yaml).

## Payment API

Base URL: `https://api.<our-domain>/api/v1`. The key decides test or live mode, and the client is always found from the key, never from the request body.

| Endpoint | What it does | Phase |
|---|---|---|
| `POST /payment-sessions` | Create a session and get `checkoutUrl` | 1 |
| `GET /payment-sessions/{id}` | Read a session (the true status) | 1 |
| `GET /payments/verify/{orderReference}` | Find the latest session for the client's own order reference | 1 |
| `POST /payment-sessions/{id}/refunds` | Refund all or part | 1 |
| `GET /payment-sessions/{id}/refunds` | List refunds | 1 |
| `GET /balances` | Pending, available and reserved balance | 1 |
| `GET /banks` | Banks and wallets we support for payouts, with their codes | 1 (new, to add to the OpenAPI file) |
| `POST /test/payment-sessions/{id}/simulate` | Test mode only: force an outcome | 1 |
| `POST /payouts`, `GET /payouts/{id}`, `POST /payouts/bulk` | Send money to any bank account | 2 |
| `POST /payment-links`, `GET /payment-links/{id}` | Payment links | 2 |
| `GET /settlements` | Settlement history | 2 |
| `POST /charges` | Direct charge without checkout (phone push) | 4 |
| `POST /subaccounts` | Split payments | 4 |

### Example: create a session

```bash
curl https://api.<our-domain>/api/v1/payment-sessions \
  -H "Authorization: Bearer sk_test_..." \
  -H "Idempotency-Key: ORDER_10001_PAYMENT" \
  -H "Content-Type: application/json" \
  -d '{
    "orderReference": "ORDER_10001",
    "amount": "1500.00",
    "currency": "ETB",
    "description": "Groceries",
    "customerHint": { "phone": "+251911234567", "name": "Abebe" },
    "returnUrl": "https://shop.example.com/payment/return",
    "webhookUrl": "https://shop.example.com/api/payment-webhook"
  }'
```

```json
{
  "id": "ps_8fK2mPq7",
  "mode": "test",
  "status": "CREATED",
  "orderReference": "ORDER_10001",
  "amount": "1500.00",
  "currency": "ETB",
  "amountRefunded": "0.00",
  "fee": null,
  "net": null,
  "checkoutUrl": "https://checkout.<our-domain>/ps_8fK2mPq7",
  "expiresAt": "2026-11-18T08:15:00Z",
  "createdAt": "2026-11-18T08:00:00Z"
}
```

- `returnUrl` and `webhookUrl` must exactly match the URLs registered for the client in the Back Office.
- `customerHint` is used only to fill in the checkout form and address the receipt. It never chooses a wallet or sends a push by itself ([architecture section 7](../../docs/core/architecture.md#payment-session-contract)).
- **Doc fix to make:** the example in `architecture.md` section 7 uses `paymentSessionId` and `PS_123456`, but the OpenAPI file uses `id` and `ps_...`. We follow the OpenAPI file and propose updating the architecture example (see [section 10](10-roadmap-and-decisions.md#changes-to-make-in-docs-after-approval)).

### Our API compared with Chapa's

| Chapa | Ours |
|---|---|
| `POST /v1/transaction/initialize` | `POST /api/v1/payment-sessions` |
| `GET /v1/transaction/verify/{tx_ref}` | `GET /api/v1/payments/verify/{orderReference}` |
| `POST /v1/refund/{tx_ref}` | `POST /api/v1/payment-sessions/{id}/refunds` |
| `POST /v1/transfers` and bulk | `POST /api/v1/payouts` and `/payouts/bulk` |
| Bank list | `GET /api/v1/banks` |
| `POST /v1/charges?type=...` | `POST /api/v1/charges` |
| `POST /v1/subaccount` | `POST /api/v1/subaccounts` |
| `callback_url` and `return_url` | `webhookUrl` (signed events) and `returnUrl` (reference only) |

The shape is the same: create, redirect, webhook, verify. Ours adds required idempotency keys, a standard error format and timestamped signatures.

## Checkout API (used only by our checkout page)

| Endpoint | What it does |
|---|---|
| `GET /checkout/{sessionId}` | The page data: client name and logo, amount, the methods turned on, time left |
| `POST /checkout/{sessionId}/attempts` | Start an attempt: `{ "method": "push", "provider": "TELEBIRR", "payer": { "phone": "+2519..." } }`. It returns the next step for the customer. |
| `POST /checkout/{sessionId}/attempts/{attemptId}/otp` | Send the SMS code |
| `GET /checkout/{sessionId}/status` | Poll for the result while the customer waits |

The checkout page sends `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, a strict Content Security Policy and `X-Frame-Options: DENY`. It loads no third-party scripts, and it has rate limits per session and per IP address ([security plan section 6](../../docs/core/security-plan.md#6-checkout-session-leakage)).

## Provider callbacks

- There's one URL per provider: `POST /callbacks/{providerCode}`.
- We check the provider's signature (and its IP addresses, where it gives them), **save the raw message**, answer quickly, and then confirm with a status check before changing anything.
- A callback we can't verify is saved and alerted on, and never acted on.

## Back Office API

The Back Office API is private, used only by the Back Office app, and every call is in the audit log. Main groups:

```text
/backoffice/providers                 list, create, update, test, request-live
/backoffice/providers/{id}/accounts   collection and payout accounts
/backoffice/clients                   list, create, update
/backoffice/clients/{id}/kyb          documents, approve, reject
/backoffice/clients/{id}/keys         create, revoke
/backoffice/clients/{id}/settings     fee plan, methods, limits, URLs, settlement account
/backoffice/payments                  search, detail, check-status, resend-webhook
/backoffice/refunds                   request, approve
/backoffice/settlements               batches, approve, send, mark-paid
/backoffice/reconciliation            statements, matches, suspense, resolve
/backoffice/approvals                 waiting approvals (two-person rule)
/backoffice/staff                     users, roles
/backoffice/audit-logs                search
```

## Webhooks to clients

```http
POST https://shop.example.com/api/payment-webhook
Content-Type: application/json
X-Event-Id: evt_9fK2mP
X-Signature: t=1794905400,v1=5257a869e7ec...
```

- **Events:** `payment.succeeded`, `payment.pending`, `payment.failed`, `payment.expired`, `refund.succeeded`, `refund.failed`, `payout.succeeded`, `payout.failed` and `settlement.completed`.
- **Signature:** HMAC-SHA256 of `t + "." + rawBody` with the client's webhook secret. The client must reject messages older than 5 minutes.
- **Retries:** at 1 min, 5 min, 30 min, 2 h, 6 h and 12 h, then daily up to 3 days. Delivery is at least once and in no fixed order, so clients must ignore events they've already seen (by `id`).
- Full details are in [API conventions section 9](../../docs/core/api-conventions.md#9-webhook-events).
