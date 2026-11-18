# 1. Product and scope

**Part of:** [Payment Gateway design](../README.md)

## The goal

> "Anybody can pay from any bank to any bank, with a Back Office to add banks and clients, like Chapa."

In product words: a business (the **client**) connects once to our gateway. Its customers can then pay with any supported bank or wallet. The client gets its money, minus our fee, in its own bank account at any bank.

## Who uses it

| Who | What they do | Where they do it |
|---|---|---|
| **Customer** (payer) | Pays for an order | Our hosted checkout page in a browser |
| **Client** (business) | Accepts payments, checks them, gets settled | Payment API from its server. In phase 1, our staff set it up. From phase 2 it has its own Merchant Dashboard. |
| **Client's developer** | Connects the client's website or app to the gateway | API docs, test keys, test banks |
| **Your staff** | Add banks and clients, approve clients, support, finance | Back Office |
| **Banks, wallets, EthSwitch** (providers) | Move the real money | Through our connectors |
| **National Bank of Ethiopia** | Licence, rules, reports | Reports produced from the Back Office |

## Features and when they come

The feature list follows what Chapa offers (see [research notes](../research/research.md#1-what-chapa-offers)).

| Feature | Phase 1 | Later |
|---|---|---|
| Add banks and wallets in the Back Office | Yes | |
| Add clients, review their documents (KYB), approve them | Yes, by staff | Self sign-up in phase 2 |
| Test and live API keys | Yes | |
| Payment sessions API and hosted checkout | Yes | |
| Pay with a wallet push (telebirr, M-Pesa, CBE Birr and others) | Yes: test wallet first, then the first real one | More wallets |
| Pay from a bank account with an SMS code (OTP) | Yes: test bank first | Each real bank after an agreement |
| Pay on the bank's own page (redirect) | Yes: test bank first | Each real bank after an agreement |
| Pay from any bank app by QR or payment request, through EthSwitch | | Phase 3 (needs a sponsor bank) |
| Verify API | Yes | |
| Signed webhooks | Yes | |
| Full and partial refunds | Yes | |
| Settlement to the client's bank, any bank | Yes: daily batch prepared by the system, sent by finance | Automatic in phase 3 |
| Payouts API (the client sends money to any bank account) | | Phase 2 |
| Payment links (no code needed) | | Phase 2 |
| Merchant Dashboard for clients | | Phase 2 |
| Split payments (sub-accounts) | | Phase 4 |
| Cards (Visa, Mastercard) | | Phase 4, through a licensed card processor |
| Server SDKs and e-commerce plugins | | Phase 4 |
| Disputes and chargebacks | | Phase 4, with cards |

## What phase 1 is not

- **Not live money.** Phase 1 runs in test mode. Live mode opens only after the licence route, bank agreements and the security review are done ([section 9](09-security-and-compliance.md)).
- **Not our own wallet.** The telebirr-style wallet is the other product in `../docs`. It can become one more payment method later.
- **Not the marketplace.** Partner apps, the catalog and "Connect with wallet" stay in `../docs` for later.

## Phase 1 is done when

1. A staff member adds a test wallet and a test bank in the Back Office.
2. Staff add a client, review its documents, approve it and issue test API keys.
3. The client's server creates a payment session and sends the customer to checkout.
4. The customer pays with the test wallet (phone push) and, in another payment, with the test bank (SMS code).
5. The client receives a signed webhook and confirms the payment with the verify API.
6. Staff refund part of a payment, with a second staff member approving.
7. The ledger shows every entry, and every automatic check passes.
8. The daily settlement report shows how much to pay each client and to which bank.
9. These hard cases work: the customer closes the browser, double-clicks Pay, the webhook fails, the payment stays pending, the provider is down. See [payment flows section 4.5](04-payment-flows.md#45-hard-cases).
