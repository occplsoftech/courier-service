# Module: user-performance (User Performance Report + cash handover)

**Status:** built 2026-11-28 (V191). Unit-tested (21 tests); V191 applied and the app booted against MySQL 8.0 (Flyway +
Hibernate `validate`). Not yet exercised end to end with real shipments on dev.
**Package:** `com.courier.modules.finance` (`CashHandover*`, `UserPerformance*`, `WalletAttribution`, `FinanceBranchScope`)
plus the adapter `shipment.infrastructure.ShipmentUserActivityDirectory`.
**Web:** `courier-web` `features/reports/user-performance-report.ts` (route `reports/user-performance`, Reports →
User Performance), `cash-handover-dialog.ts`, `user-performance.service.ts`.

## Purpose
A branch has many users and one wallet. Per user and per day range: what they booked, what they took at the counter, what
they delivered and collected on delivery, which branch-wallet debits are theirs, and the cash they still hold until they
hand it over to the branch manager / accounts desk.

## API (`/api/v1/branch-wallet/…`, company users only, never a platform operator)
| Call | What |
|---|---|
| `GET user-performance?branchId&from&to&attribution` | `UserPerformance.Report`: rows, total, reconciliation, trackingFrom |
| `GET user-performance/details?…&userId&metric` | drill-down rows; metric BOOKINGS / COUNTER / DELIVERIES / WALLET / HANDOVERS; no userId = system work / unattributed debits |
| `POST cash-handovers` | record cash received `{branchId?, userId, amount, mode CASH/BANK_DEPOSIT/UPI, reference?, remarks?, handoverDate?}` |
| `POST cash-handovers/{id}/void` | `{reason}`; whoever recorded it or a company admin |
| `GET cash-handovers?branchId&from&to&userId` | list, voided included |
Days are IST, a window is at most 93 days, both ends default to today.

## Who sees what (`FinanceBranchScope`)
- Any branch: COMPANY_ADMIN, FINANCE_USER. Every user of their own branch: + BRANCH_MANAGER, HUB_MANAGER, ACCOUNTS, VIEWER.
- Anyone else (booking / delivery operators, drivers): only their own row ("My Performance"), no reconciliation.
- Record a handover: COMPANY_ADMIN, BRANCH_MANAGER, ACCOUNTS; never from yourself (company admin excepted). Another
  branch than your own is a 404.

## Where each figure comes from (`BranchUserActivityPort`)
- **Booked**: `shipments` of the branch by `booking_date`, under `created_by`; null `created_by` = "System / customer
  portal" row. Cancelled ones count in Booked and Cancelled only.
- **Counter**: Paid-mode bookings' `shipment_payments` (CASH = cash in hand; UPI_QR / RAZORPAY = online; PENDING =
  pending). A Paid booking without a payment row (before counter payments existed) counts as cash.
- **Delivery**: `delivery_assignment` of the branch, under `delivery_user_id`; delivered = delivered in the window,
  pending = assigned in the window and not delivered. Collected = COD amount for a portal COD, else the freight (net
  amount) for To-Pay / COD — same rule as `ShipmentServiceImpl.deliver`. Treated as cash.
- **Wallet** (`WalletAttribution`): WORK (default) — SBK and SRF to the booker; COD, TPY, DRS, DWC to the DRS delivery boy
  (else whoever entered it); COM to the booker on the booking branch's wallet, the delivery boy elsewhere; RTO to whoever
  entered it; manual / penalty / transfer / adjustment / settlement = branch, unattributed. ENTERED_BY — `created_by`.
  Reconciliation: wallet debits = under users + unattributed.
- **Outstanding** = counter cash + delivery cash − live handovers, counted from `cash_handover_settings.tracking_from`
  (seeded = migration day for existing companies; a company without a row counts from its creation day). Opening = before
  the window, closing = through its last day.

## Known limits / follow-ups
- Reads load the branch's shipments since `tracking_from` in memory (like the other reports); move to aggregate queries if
  a branch gets large.
- Delivery collections are assumed cash (no record of UPI on delivery yet).
- No UI to change `tracking_from`; set it in `cash_handover_settings` if a company wants another start day.
