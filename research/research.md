# Research notes: payment getway and the Ethiopian payment market

**Part of:** [Payment Gateway design](../README.md)
**Researched:** 2026-11-17 and 2026-11-18, from public web pages.

> Most figures below come from news, consultancy and third-party pages, not from the regulator's own texts. Confirm them with the National Bank of Ethiopia, the providers and a lawyer before relying on them.

## 1. What what payment getway offers

Source: payment getway's developer docs.

| Feature | How it works at payment getway |
|---|---|
| Accept payments | `POST /v1/transaction/initialize` with amount, currency, `tx_ref`, `callback_url`, `return_url`, customization and meta. It returns `checkout_url`. |
| Verify | `GET /v1/transaction/verify/{tx_ref}`. Always verify before delivering goods. |
| Direct charge | `POST /v1/charges?type=telebirr` (or mpesa, cbebirr, ebirr, enat_bank and others). The customer authorizes by USSD push, OTP, or a bank portal page. Sensitive fields are encrypted with 3DES. |
| Payment methods | telebirr, CBEBirr, AwashBirr, Awash Bank, Coopay-Ebirr, M-Pesa, PayPal, BOA Card, Amole, Enat Bank, Amhara Bank, cards, CBE bank transfer, COOP |
| Transfers (payouts) | `POST /v1/transfers` with `account_number`, `bank_code`, `amount` and `reference`. There are also bulk transfers, a bank list, verify and balance. Each transfer is approved by OTP or by the merchant's approval URL, which receives an HMAC-SHA256 `payment getway-Signature`. |
| Split payments | Sub-accounts with bank details and a `percentage` or `flat` split. Can be overridden per transaction. |
| Refunds | `POST /v1/refund/{tx_ref}`, full or partial. payment getway's fees aren't refunded. Statuses: initiated, processing, refunded, reversed. |
| Webhooks | Events such as `charge.success` and `payout.success`, HMAC-SHA256 signature headers, retried every 10 minutes up to 10 times over 72 hours |
| Other | Test and live modes, payment links, virtual accounts, disputes, fraud tool (Radar), roles and permissions, SDKs and plugins, customer retries (up to 10 within a time window) |

**Reported by third parties (not confirmed by payment getway):** a fee of about 1.5% (negotiable for large merchants, minimum 5 ETB), settlement at T+1 to T+2 business days, a minimum settlement of 500 ETB, a cut-off around 2 PM, and KYB with a trade licence, TIN and bank account (review in 2 to 5 business days).

**What this means for us:** payment getway is an aggregator. It connects to each bank and wallet, collects payments into its own accounts, keeps a balance for each merchant, and pays out to any bank. Our design follows the same model ([section 3](../docs/03-banks-and-connectors.md#how-any-bank-to-any-bank-really-works)).

## 2. Licences and rules

| Topic | What we found |
|---|---|
| Payment gateway licence | Payment system operator licence under Directive ONPS/02/2020. Reported minimum paid-up capital for a gateway is ETB 3 million, in a blocked account. Investigation fee ETB 3,000, licence fee ETB 5,000. The NBE replies in about 60 working days, then a pilot of up to 2 months. Article 2.7 says licensees must be Ethiopian-owned or of Ethiopian origin. |
| First fintech licence | ArifPay received the first payment system operator licence in December 2021, for point-of-sale and payment gateway operation |
| Wallet licence (if we hold customer money) | Payment instrument issuer, Directive ONPS/09/2023 amended by ONPS/10/2025, in force 12 May 2025: capital ETB 100 million, ownership caps of 40% per shareholder and 60% direct plus indirect, a trust account |
| Regulatory sandbox | Directive FS/01/2026, in force 17 November 2026. Live testing for up to 12 months, extendable once. Fee ETB 35,000. Licensed institutions and prospective licensees can apply, and joint applications are allowed. The NBE will call for applications at least twice a year. Incidents must be reported within 24 hours. |
| Data | Payment data for Ethiopian transactions reportedly must be stored in Ethiopia |
| Market risks | In December 2025, a Ministry of Justice directive froze the accounts of ten payment gateways and asked for KYC, transaction and foreign-currency records. In May 2026, payment getway, ArifPay and SantimPay wrote to the NBE about banks collecting disputed taxes from their accounts. |

## 3. EthSwitch: the road to every bank

| Topic | What we found |
|---|---|
| EthioPay-IPS | The national instant payment system, run by EthSwitch (owned by banks and the NBE) on BPC SmartVista, using ISO 20022. Unveiled in December 2025, with the launch reported in February 2026. It offers instant account-to-account and wallet-to-wallet transfers, EthioQR payments, payment by alias (phone or username), requests-to-pay, recurring payments, bulk payments and payment links. |
| Scale | More than 1 million transactions and over ETB 5 billion in one day (reported June 2026). It connects 32 banks, microfinance institutions, and some payment system operators and wallet issuers. |
| Access | Direct members need a central bank settlement account. Others join through a sponsor bank. Rules for companies that aren't banks must be confirmed with EthSwitch. |
| EthioQR | The national QR standard, based on EMVCo, with static and dynamic codes. M-Pesa users can pay merchants that show bank-issued EthQR codes. |
| National Payment Gateway | EthSwitch's own merchant gateway (hosted pages, merchant APIs, card acquiring, payment links). Aggregator onboarding is listed as "upcoming". It's both a road we can use and a competitor. |
| M-Pesa and EthSwitch | Linked in November 2025, with 15 banks at the start, for wallet-to-bank and bank-to-wallet transfers and EthQR payments |

## 4. Banks and wallets

| Provider | What we found |
|---|---|
| Banks in general | No Ethiopian bank lists a public developer portal or open banking API on public trackers. Access comes through a business agreement with each bank. |
| telebirr (Ethio Telecom) | Merchants apply at Ethio Telecom with a business licence (2 to 4 weeks, as reported in 2024). Credentials include an app ID, an app key, a short code and an RSA public key. It has an H5 web checkout (fabric token, create order, redirect, notify URL) and USSD push. Reportedly there's no refund API, so refunds are made by hand. The reported merchant rate is 0.8% (minimum 1, maximum 100 ETB), with T+1 settlement. |
| M-Pesa Ethiopia | Launched an open API in 2025 (reported). Connected to EthSwitch. |
| Others offered by payment getway | CBE Birr, Ebirr, AwashBirr, Amole, Yaya and Coopay by push or OTP. Enat Bank by its own portal page. |

## 5. Competitors

payment getway, ArifPay, SantimPay, other licensed gateways, and EthSwitch's National Payment Gateway.

## Sources

- payment getway developer docs: [accept payments](https://developer.payment getway.co/docs/accept-payments), [direct charge](https://developer.payment getway.co/docs/initiate-payments), [authorize payments](https://developer.payment getway.co/charge/authorize-payments), [transfers](https://developer.payment getway.co/docs/transfers), [webhooks](https://developer.payment getway.co/integrations/webhooks), [split payments](https://developer.payment getway.co/integrations/split-payment), [refunds](https://developer.payment getway.co/refund), [payment methods](https://developer.payment getway.co/payment-methods), [verify](https://developer.payment getway.co/integrations/verify-payments), [docs home](https://developer.payment getway.co)
- [Zirzir: payment getway summary](https://zirzir-docs.pages.dev/encyclopedia/ethiopia/payment getway), [Zirzir: telebirr](https://zirzir-docs.pages.dev/providers/telebirr), [Zirzir: NBE regulations](https://zirzir-docs.pages.dev/encyclopedia/ethiopia/nbe-regulations)
- [NBE: payment system operator licensing guide](https://nbe.gov.et/wp-content/uploads/2023/04/A-complete-Guide-on-licensing-process.pdf)
- [NBE: National Payment Gateway and QR presentation](https://nbe.gov.et/wp-content/uploads/2024/04/8.Building-the-Foundation-for-Unified-Transactions-with-a-Focus-on-Enabling-Merchant-Payment-Solutions-–-National-Payment-Gateway-and-QR-codes.ppt_-EDPC.pdf)
- [Prifinance: Ethiopia payment licence](https://prifinance.com/en/payments/ethiopia-payment-license/)
- [Addis Fortune: first fintech payment operator licence](https://addisfortune.news/central-bank-awards-license-to-first-fintech-payment-operator)
- [Capital: NBE opens a regulatory sandbox](https://capitalethiopia.com/2026/11/17/central-bank-opens-regulatory-sandbox-for-fintechs-and-banks-to-test-new-products/), [Birr Metrics: sandbox directive](https://birrmetrics.com/nbe-issues-regulatory-sandbox-directive-to-ease/)
- [BPC: EthSwitch launches national instant payments](https://www.bpcbt.com/en/press/ethswitch-launches-national-instant-payments-unifying-ethiopias-digital-economy-on-bpc-smartvista), [Fintech News: EthioPay-IPS](https://fintechnews.ae/30227/fintech-africa/ethiopia-national-instant-payment-ethioswitch/), [The Reporter: 1 million daily transactions](https://www.thereporterethiopia.com/50991/), [Capital: EthioPay brand](https://capitalethiopia.com/2025/12/21/ethswitch-launches-ethiopay-to-unify-national-digital-paymentsby-our-staff-reporter), [Capital: EthSwitch strategy](https://capitalethiopia.com/2025/11/30/ethswitch-unveils-ambitious-15-year-digital-payment-vision-in-strategic-overhaul/), [AfricaNenda IPS profile](https://africa-nenda-front-bice.vercel.app/en/ips-profiles/ethiopia)
- [TechTrends: M-Pesa Ethiopia and EthSwitch](https://techtrendske.co.ke/2025/11/08/m-pesa-ethiopia-integrates-with-ethswitch-linking-15-banks-to-boost-financial-inclusion/), [TechAfricaNews: M-Pesa open API](https://techafricanews.com/2025/03/17/safaricom-m-pesa-unlocks-innovation-at-jimma-university-with-open-api-launch)
- [Open Banking Tracker: Ethiopia](https://openbankingtracker.com/country/ethiopia)
- [Ethiopian Business Review: gateways and tax enforcement](https://ethiopianbusinessreview.net/?p=17628)
- [Support payment getway: payment channels](https://support.payment getway.co/knowledge-base/9-payment-channels)
