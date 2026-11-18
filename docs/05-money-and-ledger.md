# 5. Money and the ledger

**Part of:** [Payment Gateway design](../README.md)
**Based on:** the [accounting model](../../docs/ledger/accounting-model.md) in `../docs`. This page uses the same accounts and rules, applied bank-first. Ledger account names use `merchant`, as `../docs` does; a merchant is a client.

## Where the money really is

- **Real money** is in our **collection accounts** at banks and wallets, or on its way to them from a provider.
- **The ledger** says who that money belongs to: each client's balance, our fees, and money on its way in or out.
- **Client money is kept apart from company money.** The legal form (a ring-fenced or trust account) must be confirmed with the National Bank and our bank (decision D7).

## Basic rules

1. **Double entry.** Every money event is one journal, with debits equal to credits.
2. **Append only.** Entries are never changed or deleted. A correction is a new journal that points to the old one.
3. **Whole santim.** Amounts are stored as whole numbers of santim (1 ETB = 100 santim). APIs use strings such as `"1500.00"`, never floating-point numbers.
4. **One rounding.** Fees are calculated in santim and rounded half-up, once per payment.
5. **No double posting.** Each journal has a unique source event ID, so posting the same event twice returns the first journal.
6. **No money moves without a journal.** A payment, refund or payout with no journal is a bug and raises an alert.
7. **The session state decides.** The ledger follows the payment state; it never decides whether a payment happened.

## The accounts

| Account | Type | Meaning |
|---|---|---|
| `provider_receivable:{provider}` | Asset | A provider owes us money for successful payments that hasn't reached our bank yet |
| `collection_bank:{account}` | Asset | One of our real bank or wallet accounts. It must match the statement. |
| `merchant:{id}:pending` | Liability | Paid, but still inside the settlement delay |
| `merchant:{id}:available` | Liability | Ready to settle or pay out |
| `merchant:{id}:reserve` | Liability | Held back for risk or disputes |
| `merchant:{id}:negative` | Asset | The client owes us, for example after a refund larger than its balance |
| `payout_in_transit` | Liability | Settlement or payout sent to a bank, not yet confirmed |
| `provider_payable:{provider}` | Liability | We owe a provider, for example for a refund |
| `fees:gateway` | Revenue | Our fees |
| `fees:provider_cost` | Expense | What providers charge us |
| `suspense:gateway` | Liability | Items we can't match yet, under investigation |

## A worked example

A customer pays **1,000.00 ETB** by telebirr. Our fee is **1.5% (15.00)**, and telebirr charges us **0.8% (8.00)**. These are example numbers, not real prices. Our collection account is at CBE, and the client wants its money in its Awash Bank account.

| # | What happens | Journal |
|---|---|---|
| 1 | Payment succeeds (day 0) | Dr `provider_receivable:TELEBIRR` 1,000.00 / Cr `merchant:C1:pending` 985.00 / Cr `fees:gateway` 15.00 |
| 2 | telebirr pays us next day, minus its fee | Dr `collection_bank:CBE-01` 992.00 / Dr `fees:provider_cost` 8.00 / Cr `provider_receivable:TELEBIRR` 1,000.00 |
| 3 | The settlement delay has passed and telebirr's money is in | Dr `merchant:C1:pending` 985.00 / Cr `merchant:C1:available` 985.00 |
| 4 | Settlement batch created | Dr `merchant:C1:available` 985.00 / Cr `payout_in_transit` 985.00 |
| 5 | Awash confirms the money arrived | Dr `payout_in_transit` 985.00 / Cr `collection_bank:CBE-01` 985.00 |

**The result:** the client received 985.00. We earned 15.00 − 8.00 = **7.00**, and that 7.00 is what's left in `CBE-01`.

### If the client refunds 400.00 before settlement

Our fee isn't returned by default ([ADR 016](../../docs/payment-gateway/adr/016-settlement-and-fees.md)). The refund comes from the client's `available` balance first, then `pending`, then `negative`.

| What happens | Journal |
|---|---|
| Refund approved (available is still 0) | Dr `merchant:C1:pending` 400.00 / Cr `provider_payable:TELEBIRR` 400.00 |
| The refund is paid to the customer | Dr `provider_payable:TELEBIRR` 400.00 / Cr the account the refund is paid from |
| If it fails at the provider | Reverse the refund journal |

The client's settlement later becomes 585.00.

### Other journals

| Event | Journal |
|---|---|
| Bank payment straight into our account at the same bank | Dr `provider_receivable:CBE` / Cr `merchant:pending` / Cr `fees:gateway`, then Dr `collection_bank:CBE-01` / Cr `provider_receivable:CBE` when it shows on the statement |
| Settlement fails or the bank returns it | Dr `payout_in_transit` / Cr `merchant:available` |
| Moving money between our own banks (treasury) | Dr `collection_bank:AWASH-01` / Cr `collection_bank:CBE-01`, plus any transfer fee to `fees:provider_cost` |
| A provider reverses a payment it had confirmed | Reverse the success journal. If the client's balance is too small, the rest goes to `merchant:negative`. |
| Moving our earned fees to the company's own account | To design with the accountant. The rule is that we never move more than fees earned minus costs. |

## Fees and settlement rules

These are placeholders until the business sets them (decision D6).

| Rule | Placeholder | Where it's set |
|---|---|---|
| Fee per method | Percentage plus an optional fixed amount, with a minimum and maximum. For example 1.5%, minimum 5 ETB. | Fee plan, per client |
| Fee on refunds | Not returned | Fee plan |
| Settlement delay | T+1 business day for wallets and banks, after the provider's money arrives. Cards later at T+7 for new clients. | Per client and method |
| Minimum settlement | For example 100 ETB. Smaller amounts wait for the next day. | Settings |
| Cut-off time | For example 14:00 Addis Ababa time. Business days only, holidays skipped. | Settings |
| Settlement account change | Two-person check that the name matches the KYB legal name, then settlement paused for 48 hours | [Security plan section 8](../../docs/core/security-plan.md#8-dual-approval-for-money-sensitive-admin-actions) |

## Automatic checks

| Check | When | If it fails |
|---|---|---|
| Every journal balances | Every write (database rule) | The write is refused |
| No client `available` below zero | Every write | The write is refused |
| Every `SUCCEEDED` session has exactly one success journal | Every hour | Alert |
| Stored balances equal the sum of entries | Every hour | Alert, and the balances are rebuilt from entries |
| Each `collection_bank` balance equals the real statement | Every day | Alert, and the difference goes to suspense |
| **What we hold covers what we owe:** collection accounts + provider receivables ≥ client balances + provider payables + payouts in transit | Every day | Stop settlements and alert management |
| Provider receivables are older than the provider's settlement time | Every day | Alert finance and chase the provider |
| Payouts in transit older than 3 business days | Every day | Alert finance |

## Reconciliation every day

1. **Import** every bank and wallet statement, by API where the provider has one or as a file uploaded in the Back Office.
2. **Match** each line with our attempts, refunds and settlements, by provider reference, amount and date.
3. **Close** the matched items.
4. Put **unmatched** items into `suspense:gateway` with a reason: missing at the provider, missing at us, amount differs, duplicate, or late.
5. **Resolve** each one with a new journal, prepared by one person and approved by another. Items older than 3 business days go to the finance lead, and older than 10 go to management.

Phase 1 runs this with tools in the Back Office and a finance person. Automation comes in phase 3.
