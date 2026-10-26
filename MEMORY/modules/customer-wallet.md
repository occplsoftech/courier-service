# Module: customer wallet (finance)

**Status:** parts 1-3 of 4 built 2026-10-26 (V136, V137): ownership, automatic wallet for business customers, balance and ledger reads,
money in (online recharge, top-up requests, manual credit) and money out (portal booking payment, refunds, manual debit). The screens are part 4 — see "Parts".
**Package:** `com.courier.modules.finance` (same module as the branch wallet). Small extensions in `customer` (event) and
`customerportal` (wallet endpoints).

## What it is

The prepaid wallet of a **business customer** (an e-commerce portal client) with the same rules as a branch wallet: balance and
hold, an append-only ledger, no overdraft, row-locked money paths. There is **one `wallets` table** with two kinds of owner
(`owner_type`): `BRANCH` (every existing row) and `CUSTOMER`. A customer wallet has `customer_id` set and `branch_id` null; a CHECK
constraint allows only those two shapes, and `uk_wallets_company_customer` gives one wallet per customer.

Why one table and not a copy: the money code (`WalletServiceImpl`, ~1,100 lines: ledger, recharge, settlement, top-up approval,
reversal) is the part that must not be duplicated. Branch-only rules (commission, DRS charge, COD / to-pay at a branch) never apply
to a customer wallet.

## Who gets a wallet

- **Automatically**: every new **BUSINESS** customer created through `CustomerService.create`
  (`CustomerEvent.CustomerCreated` -> `CustomerWalletProvisioningListener`).
- **Not by default**: INDIVIDUAL customers, and the customers `findOrCreateForBooking` creates for every sender and receiver (no
  event is published for those): they are contact records, and a wallet each would be thousands of unused accounts.
- **On first access**: any customer can get one (`getForCustomer`, or the portal's own wallet call). This also covers business
  customers that existed before this feature, so there is **no backfill migration**.

## Rules

- The wallet row is created in `CustomerWalletServiceImpl.open` only. No method here changes a balance.
- `provisionForCustomer(companyId, customerId)` is idempotent and has no `@PreAuthorize` on purpose (called by the listener, for any
  creating role, and by the portal after it resolved the customer). Never expose it through a controller that takes a customer id.
- Staff reads (`getForCustomer`, `ledgerForCustomer`) are `COMPANY_ADMIN` / `FINANCE_USER`; a customer of another company is 404.
- The portal never takes a customer id from the request: `CurrentCustomerResolver` derives it from the signed-in account.
- Dashboard "Total Wallet Balance" / "Low Wallet Balance" count **branch wallets only**.

## REST

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/customers/{customerId}/wallet` | `COMPANY_ADMIN`, `FINANCE_USER` |
| GET | `/api/v1/customers/{customerId}/wallet/transactions?page&size` | same |
| GET | `/api/v1/customer-portal/wallet` | the signed-in `CUSTOMER` |
| GET | `/api/v1/customer-portal/wallet/transactions?page&size` | same |
| POST | `/api/v1/customer-portal/wallet/recharge/order` `{amount}` | the signed-in `CUSTOMER` (step 1: gateway order) |
| POST | `/api/v1/customer-portal/wallet/recharge` `{gatewayOrderId, paymentReference, signature}` | same (step 2: settle) |
| POST | `/api/v1/customer-portal/wallet/topup-requests/upload-proof` (multipart `file`) | same |
| POST / GET | `/api/v1/customer-portal/wallet/topup-requests` | same (raise / list own) |
| POST | `/api/v1/customers/{customerId}/wallet/credit` `{amount, subTransactionType?, referenceId?, remarks?}` | `COMPANY_ADMIN` |
| POST | `/api/v1/customers/{customerId}/wallet/debit` `{amount, subTransactionType?, referenceId?, remarks?}` | `COMPANY_ADMIN` |
| PATCH | `/api/v1/branch-wallet/topup-requests/{id}/approve` \| `/reject` | `COMPANY_ADMIN` (existing endpoints; work for customer requests too) |

## Money in (part 2)

- **One owner abstraction, no copied money code.** `WalletServiceImpl` has a private `Owner(branchId, customerId)`. `openRecharge`,
  `completeRecharge`, `settlePayment`, `reconcileRecharge` and `credit` are split into "find the owner" and a shared body, so the customer
  methods (`openRechargeForCustomer`, `completeRechargeForCustomer`, `settleFromWebhookForCustomer`, `creditForCustomer`) run the branch code.
- **Gateway notes**: a customer's Razorpay order carries `customerId` (a branch's carries `branchId`). The webhook routes on whichever is there; a
  payment naming neither is acknowledged and ignored.
- **Top-up requests**: customer raises (proof of payment mandatory), a company admin approves or rejects with the existing endpoints. Approval
  credits through `creditForCustomer` (an `MCR` credit), so a request, a recharge row and a ledger entry exist for every approved top-up.
- **Authorization**: customer entry points are `hasRole('CUSTOMER')` and take only the customer the portal resolved from the signed-in account;
  staff reads/credits are `COMPANY_ADMIN` (`FINANCE_USER` for reads). `checkRechargeAttempt` on a customer's attempt is company-wide staff only.
- **Statements**: a customer's recharges appear in the company-wide recharge report (with `customerId`, no branch); a branch caller never sees them.

## Money out (part 3)

- **Paying for a portal booking** (`PortalBookingService.book`): the wallet is debited *before* the courier is called (ledger first), refunded only if the
  courier **certainly** refused, and kept if the outcome is unknown. The debit is `SBK` with `ReferenceType.PORTAL_BOOKING` and the booking id as reference, so the
  customer's statement shows one line per booking and branch/shipment reversals (`reverseForShipment`, which looks up `SHIPMENT`) can never pick it up.
- **Refunds**: `refundCustomerBooking` credits back the booking's own `SBK` debit once (matched oldest-first by amount, the same rule `refundBookingDebit` uses).
  Triggers: a courier refusal at booking time; `TrackingSyncService` seeing stage `CANCELLED` (courier webhook or the job). A courier-cancelled
  shipment is refunded in full — if a courier ever charges a cancellation fee, that is a manual debit. RTO / returned shipments are **not** refunded.
- **Switch**: `app.customer-portal.wallet-payment.enabled` (env `CUSTOMER_PORTAL_WALLET_PAYMENT_ENABLED`), default **off**. When on, every booking on a covered provider is
  paid from the wallet, including by customers who never topped up (they get a clear "Insufficient wallet balance" failure). `excluded-providers` defaults to
  `OCCPL_NETWORK`: that provider only creates a pickup request which the branch bills at the counter, so a wallet debit would double-charge.
- **Manual debit**: `debitForCustomer` (`MDB` default, `ADJ` allowed), admin only, no overdraft.
- **Reversal for any owner**: `postReversal` locks via the wallet's owner (branch or customer) and keeps the original's reference type.

## Parts

1. **Foundation (this)**: ownership, automatic wallet, reads.
2. **Money in (built, V137)**: online recharge (company Razorpay config, webhook and reconcile), top-up requests with admin approval, manual credit.
3. **Money out (built, no migration)**: portal booking debit, refund on refusal / courier cancellation, manual debit.
4. **Screens**: e-commerce portal wallet page; customer wallet tab in the staff app.

## Verification status

Unit tests only (part 1: `CustomerWalletTest`, `CustomerWalletServiceImplTest`, `CustomerWalletProvisioningListenerTest`,
`CustomerServiceImplTest`; part 2: `CustomerWalletMoneyInTest`, `WalletTopupRequestServiceImplTest`, `RazorpayWebhookControllerTest`; part 3: `CustomerWalletMoneyOutTest`, plus cases in `PortalBookingServiceTest` and `TrackingSyncServiceTest`). `V136` was applied by
the dev deploy of part 1 (the deploy stayed healthy). The `V137` migration (two tables, same pattern) has not been run against MySQL yet. The `V136` migration (nullable `branch_id` under an FK, new CHECK and unique key) and the repository
queries have **not** been run against MySQL; CI's integration tests must confirm them before merge.
