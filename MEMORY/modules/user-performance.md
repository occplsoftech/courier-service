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

## Cash handover flow (owner, 2026-11-28: "upload image … receiver views the proof and then approves")
1. The user who holds the cash taps **Hand over cash**: amount (prefilled with what they still hold), mode (cash / bank
   deposit / UPI), date, reference, and a **proof image** (required: deposit slip, UPI screenshot, photo of the cash).
   The image goes to `POST cash-handovers/upload-proof` (same S3 storage as top-up proofs), then `POST cash-handovers`.
2. It is **PENDING** ("Awaiting approval"): still counted as outstanding, shown in its own column.
3. A branch manager, the branch's ACCOUNTS desk or a company admin opens **Cash handovers → Waiting for approval**, views
   the proof full size, and **approves** (confirm) or **rejects** (reason required, shown to the user). Never their own.
4. Only **APPROVED** handovers reduce outstanding. An approved one found wrong later is **VOIDED** (whoever approved it or a
   company admin, reason required) and counts as outstanding again. Nothing submitted is ever edited.

## Notifications (in-app bell, `support.notifications`)
`CashHandoverServiceImpl` publishes `CashHandoverEvent`; `CashHandoverNotificationListener` acts on it **after commit**
(REQUIRES_NEW, failures logged and swallowed, so a missed notification never undoes a handover):
- **Submitted** (`CASH_HANDOVER_SUBMITTED`) → active users placed at or managing the branch with BRANCH_MANAGER or ACCOUNTS
  (company-role grant or legacy role: `BranchDirectoryPort.usersWithAnyRole`, native query in `CompanyUserRepository`);
  if the branch has none, the company admins. Never the sender.
- **Approved / rejected / voided** (`CASH_HANDOVER_APPROVED / _REJECTED / _VOIDED`) → the user who handed over, with the
  reason. The web opens Reports → User Performance on the Cash handovers tab (`?tab=handovers`) for all four.

## API (`/api/v1/branch-wallet/…`, company users only, never a platform operator)
| Call | What |
|---|---|
| `GET user-performance?branchId&from&to&attribution` | `UserPerformance.Report`: rows, total, reconciliation, trackingFrom |
| `GET user-performance/details?…&userId&metric` | drill-down rows; metric BOOKINGS / COUNTER / DELIVERIES / WALLET / HANDOVERS; no userId = system work / unattributed debits |
| `POST cash-handovers/upload-proof` | multipart `file` (JPEG/PNG/WEBP/HEIC ≤ 10 MB) → `{url}` |
| `POST cash-handovers` | the caller hands over their own cash `{branchId?, amount, mode CASH/BANK_DEPOSIT/UPI, reference?, remarks?, handoverDate?, proofImageUrl}` → PENDING |
| `POST cash-handovers/{id}/approve` / `reject` | `{reason}` (optional note / required reason); receivers only, never own |
| `POST cash-handovers/{id}/void` | `{reason}`; approved only; whoever approved it or a company admin |
| `GET cash-handovers?branchId&from&to&userId` | dated in the window, any status |
| `GET cash-handovers/pending?branchId` | everything waiting, any date (own only without team access) |
Days are IST, a window is at most 93 days, both ends default to today.

## Who sees what (`FinanceBranchScope`)
- Any branch: COMPANY_ADMIN, FINANCE_USER. Every user of their own branch: + BRANCH_MANAGER, HUB_MANAGER, ACCOUNTS, VIEWER.
- Anyone else (booking / delivery operators, drivers): only their own row ("My Performance"), no reconciliation.
- Hand over cash: any company user, for their own cash. Approve / reject: COMPANY_ADMIN, BRANCH_MANAGER, ACCOUNTS, never
  their own. Another branch than your own is a 404.

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
- **Outstanding** = counter cash + delivery cash − approved handovers (pending = "awaiting approval", still outstanding), counted from `cash_handover_settings.tracking_from`
  (seeded = migration day for existing companies; a company without a row counts from its creation day). Opening = before
  the window, closing = through its last day.

## Known limits / follow-ups
- Reads load the branch's shipments since `tracking_from` in memory (like the other reports); move to aggregate queries if
  a branch gets large.
- Delivery collections are assumed cash (no record of UPI on delivery yet).
- The mobile app (`courier-mobile-app`) has no hand-over screen yet; drivers use the web page.
- No UI to change `tracking_from`; set it in `cash_handover_settings` if a company wants another start day.
