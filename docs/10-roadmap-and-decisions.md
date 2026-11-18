# 10. Roadmap and decisions

**Part of:** [Payment Gateway design](../README.md)

## Phases

Two tracks run side by side: the **product** we build, and the **business** work that lets it touch real money.

| Phase | Product track | Business track | Done when |
|---|---|---|---|
| **0. Design** (now) | These documents reviewed and approved | Meet a lawyer and choose the licence route | You approve this design and decisions D1 to D8 |
| **1. Test gateway** | Back Office (banks, clients, keys, payments, refunds, settlement report, audit), Payment API, checkout, test providers (push, OTP, redirect), ledger and checks, webhooks | Talk to the first bank or wallet. Prepare the NBE sandbox or partner application. | The [phase 1 done list](01-product-and-scope.md#phase-1-is-done-when) passes in test mode |
| **1B. First real provider** | Connector for the first real wallet or bank, certified with its test credentials | Agreement signed, collection account open | The certification checklist passes on the provider's test system |
| **2. Clients serve themselves** | Merchant Dashboard (sign-up, document upload, keys, webhooks, payments), payouts API, payment links, more providers | Sandbox or partner approval, AML programme, security review | The first pilot clients use it with real money under the approved route |
| **3. Every bank** | EthSwitch through a sponsor bank: EthioQR, payment requests, interbank payouts. Automatic settlement and reconciliation. | Sponsor bank agreement, EthSwitch approval | A customer of any EthioPay bank can pay, and clients are paid to any bank automatically |
| **4. More features** | Cards through a licensed processor, split payments, SDKs and plugins, disputes | Card processor agreement, PCI DSS scope confirmed | Cards work end to end |

## Build order inside phase 1

This is for when you say "start coding". Each step works and is tested before the next one starts.

1. Project setup, database, migrations, test and live separation, audit log, staff sign-in with MFA.
2. Back Office: banks and wallets (providers) and their accounts.
3. Back Office: clients, KYB review, fee plans, settlement account, URLs, API keys.
4. Ledger: accounts, journals, the balance rule, automatic checks.
5. Payment API: sessions, read, verify, with idempotency, the standard errors and rate limits.
6. Connector interface and the test providers (push, OTP, redirect).
7. Hosted checkout pages.
8. Callbacks, the pending checker and session expiry.
9. Webhooks: outbox, signing, retries, resend from the Back Office.
10. Refunds, with the two-person rule for staff refunds.
11. Settlement: daily batch, approval, bank file export, mark paid.
12. Reconciliation: statement upload, matching, suspense.
13. End-to-end tests of every hard case, then an internal security review.

## Decisions to make

| # | Decision | Options | Recommendation |
|---|---|---|---|
| **D1** | Tech stack | Node.js with TypeScript, or Java with Spring Boot. Both use PostgreSQL. | **TypeScript and PostgreSQL:** one language for the API, Back Office and checkout, and faster to build ([section 2](02-architecture.md#tech-stack-decision-d1)) |
| **D2** | First real provider | telebirr, M-Pesa, CBE Birr, or one bank | **The one that gives us an agreement and test credentials fastest.** telebirr reaches the most people, so ask it first, but don't wait on it. |
| **D3** | How we reach banks | Direct one by one, EthSwitch through a sponsor bank, or a licensed partner | **Direct for the first one to three providers, and start EthSwitch talks now** for phase 3 ([section 3](03-banks-and-connectors.md#three-ways-to-reach-banks-and-wallets)) |
| **D4** | Licence route | NBE sandbox, operate under a licensed partner, or apply for the gateway licence directly | **Ask a lawyer first.** The new sandbox, applied for together with a bank, looks like the fastest legal start, but that must be confirmed ([section 9](09-security-and-compliance.md#the-business-and-legal-track)). |
| **D5** | Who creates clients in phase 1 | Staff only in the Back Office, or self sign-up | **Staff only in phase 1**, and self sign-up in the phase 2 Merchant Dashboard |
| **D6** | Fees and settlement timing | Your prices | Start with the placeholders (1.5%, T+1, 100 ETB minimum settlement) and set real values before live |
| **D7** | Where client money is held | A collection account at each provider, or one settlement bank | **Decide with the lawyer and the first bank.** Client money must be kept apart from company money. |
| **D8** | Refund states | Keep `REFUNDING` and `REFUND_FAILED` as session states, or track refunds on their own | **Track refunds on their own**, and keep the session `SUCCEEDED` until the full amount is refunded ([section 4.4](04-payment-flows.md#44-states)) |

**Decided on 18 November 2026:** D1 is **Java with Spring Boot for the gateway, Go for the orchestrator, and PostgreSQL** (your choice). They are two services in two folders: [gateway/](../gateway/README.md) and [orchestrator/](../orchestrator/README.md). The connectors are built by others, against the [connector contract](../orchestrator/README.md#the-connector-contract).

**Your design (18 November 2026):** payments are kept as success candidates, successes and failures, with one view that shows the current status ([section 8](08-data-model.md#the-six-parts-of-layer-4)).

## Changes to make in `../docs` after approval

Nothing in `../docs` has been changed yet. Once you approve this design, these updates keep the two sets of documents in step:

1. **Manifesto, "First release (MVP)":** change the first release to the bank-first gateway in this folder, and move "our own wallet only" to later.
2. **Back Office README:** add the Banks and wallets screens and the Clients screens from [section 6](06-back-office.md).
3. **Payment Gateway module:** add provider management (the provider record and statuses) and staff-created clients for phase 1.
4. **OpenAPI file:** add `GET /banks` to the phase 1 endpoints.
5. **Architecture section 7:** change the session example to match the OpenAPI shape (`id`, `ps_...`).
6. **Architecture section 6.1:** apply decision D8 on refund states. Also let a failed try send the session back to `AUTHORIZATION_REQUIRED`, so the customer can try another way, and keep `FAILED` for a session that ended without payment ([section 4.4](04-payment-flows.md#44-states)).
7. **ADR 004:** add the "two attempts both succeed" and "late success after failure" rules from [section 4.5](04-payment-flows.md#45-hard-cases).
8. **ADR 012:** add the NBE regulatory sandbox (Directive FS/01/2026) as a licence option.
9. **ADR 014:** note that EthioPay-IPS has been live since February 2026, and make it the planned route to every bank.
10. **Payment Gateway module, data model:** replace the `status` columns on `payment_sessions` and `payment_attempts` with the success and failure tables and the payments view ([section 8](08-data-model.md#the-six-parts-of-layer-4)).
