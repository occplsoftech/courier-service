# Module: customer wallet (finance)

**Status:** part 1 of 4 built 2026-10-26 (V136): ownership, automatic wallet for business customers, balance and ledger reads.
Recharge, top-up requests, booking payments/refunds and the screens are later parts — see "Parts".
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

## Parts

1. **Foundation (this)**: ownership, automatic wallet, reads.
2. **Money in**: online recharge (company Razorpay config, settlement and reconcile jobs) and top-up requests with admin approval,
   generalised from the branch versions (`RechargeOrder`, `WalletTopupRequest` currently require a branch).
3. **Money out**: debit a customer wallet for a portal booking, refund on cancellation; admin credit / debit adjustments.
4. **Screens**: e-commerce portal wallet page; customer wallet tab in the staff app.

## Verification status

Unit tests only (`CustomerWalletTest`, `CustomerWalletServiceImplTest`, `CustomerWalletProvisioningListenerTest`,
`CustomerServiceImplTest`). The `V136` migration (nullable `branch_id` under an FK, new CHECK and unique key) and the repository
queries have **not** been run against MySQL; CI's integration tests must confirm them before merge.
