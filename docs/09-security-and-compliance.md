# 9. Security and compliance

**Part of:** [Payment Gateway design](../README.md)
**Based on:** the [security plan](../../docs/core/security-plan.md) in `../docs`. Items tagged **MVP** there are needed in phase 1; items tagged **Launch** are needed before live money.

## Top risks and how we stop them

| Risk | How we stop it | When |
|---|---|---|
| A client's secret key is stolen | Keys stored only as hashes. Two active keys so clients can rotate. Revoking works within 60 seconds. Optional IP allowlist. Every key use is logged. | Phase 1 (allowlist at launch) |
| Someone fakes "payment succeeded" in the browser | Only the gateway decides. Redirects carry a reference only. Clients must verify by API before delivering. | Phase 1 |
| A fake callback pretends to be a bank | Signature and IP checks, then our own status check before any change | Phase 1 |
| A client's webhook URL is used to attack our network (SSRF) | HTTPS only, no private or internal addresses, an egress proxy with no route inside, no redirects followed, short timeouts | Phase 1 |
| A checkout link leaks | A 128-bit random session ID, a 15-minute default expiry, no caching. The customer still approves on their own phone or bank, so a leaked link can't take anyone's money. | Phase 1 |
| A staff member steals or makes mistakes | Roles, the [two-person rule](06-back-office.md#the-two-person-rule), audit log, masked data | Phase 1 |
| Money sent to the wrong account | Account name check, a second person for any change of settlement account, payouts paused for 48 hours after a change | Phase 1 (name check when the bank supports it) |
| Someone edits the ledger | Append-only tables, no `UPDATE` or `DELETE` rights for the application, hourly checks | Phase 1 |
| A fraudulent client or money laundering | KYB before live, risk levels, limits, banned business list, transaction monitoring, reports to the Financial Intelligence Service | KYB in phase 1, monitoring before launch |
| A staff account is taken over | MFA, VPN, company devices, session timeout | Phase 1 |
| A data leak | Encryption in transit and at rest, masking, in-country hosting for live, retention limits | Phase 1 (hosting at launch) |
| Overload or abuse | Rate limits per key and per IP, a firewall, provider health checks | Phase 1 |
| A provider's keys leak from our side | Vault storage, short-lived access, rotation, and nothing in code or logs | Phase 1 |

## Secrets and keys

- All secrets are in a **vault** (for example HashiCorp Vault, or the key service of the in-country host). Nothing is in code, config files or logs ([security plan section 1](../../docs/core/security-plan.md#1-keys-and-secrets-kms-and-hsm)).
- Sensitive columns are encrypted with keys from the vault.
- Webhook signing keys and provider private keys move to an HSM-backed key service before launch.

## Logging

- Every request has a correlation ID that follows it from the API to the connector and the ledger.
- Logs never contain secret keys, PINs, OTPs, full account numbers, full card numbers or session IDs. A shared filter removes them.
- The audit log is append-only and kept as long as the ledger, which is at least 10 years.

## Testing before live

- Dependency and code scanning on every change.
- An internal security review of the phase 1 controls.
- An external penetration test before live money.
- A restore test of the database backups every month.

## The business and legal track

This work is not code. It runs in parallel with the build, starting now. The figures come from public sources ([research notes](../research/research.md#2-licences-and-rules)) and **must be confirmed with the National Bank of Ethiopia and a lawyer**.

| Item | What we know (to confirm) | Who |
|---|---|---|
| **Licence route** (decision D4) | (a) The NBE **regulatory sandbox**, Directive FS/01/2026, in force since 17 November 2026: live testing for up to 12 months, a fee of ETB 35,000, joint applications with a bank allowed. (b) Operating **under a licensed partner** (bank or payment company) while our own licence is processed ([ADR 012](../../docs/wallet/adr/012-licensing-and-trust-account.md)). (c) A **payment system operator licence** for a payment gateway, Directive ONPS/02/2020: reported minimum capital ETB 3 million, fees ETB 3,000 plus 5,000, NBE answer in about 60 working days, a pilot of up to 2 months. That directive says owners must be Ethiopian or of Ethiopian origin. | Founder and lawyer |
| **Company** | An Ethiopian company with a clear purpose for payments | Founder and lawyer |
| **Bank and wallet agreements** | A contract, fees, settlement time and test credentials from each provider | Partnerships |
| **Collection accounts** | One per provider or through a settlement bank. Client money kept apart from company money (decision D7). | Finance |
| **EthSwitch access** | A sponsor bank for EthioPay-IPS (QR, payment requests, interbank payouts) | Partnerships |
| **AML and KYB programme** | KYB rules, sanctions screening, monitoring, suspicious transaction reports to the Financial Intelligence Service | Compliance |
| **Data in Ethiopia** | Live data hosted in Ethiopia ([ADR 008](../../docs/core/adr/008-data-residency-retention-privacy.md)) | Engineering and lawyer |
| **Tax and records** | Clean books, tax filings and transaction records ready for any authority request. In 2025 and 2026, other gateways had their accounts frozen and faced tax collection disputes. | Finance |
| **Incident reporting** | In the sandbox: cyberattacks, data breaches and fraud reported to the NBE within 24 hours (as reported) | Engineering and compliance |
