# Communication Center

**Status:** DONE (v0.31.0, 2026-08-21). New package `com.courier.modules.communication`,
migration `V50`. Event-driven multi-channel (WhatsApp/SMS/Email) customer notifications for
shipment lifecycle events.

## Purpose

Business modules must never send messages themselves. `ShipmentServiceImpl` (still the only
writer of Shipment Booking/Movement state — this module adds no new writer) publishes plain
`ShipmentEvent` records at the moment something happens; a listener in this module is the only
place that turns one into an actual send. This module orchestrates the notification, it never
decides shipment business logic — the same "orchestrate, don't re-decide" posture Shipment
Booking itself follows toward Pricing/Customer/Wallet.

## Flow, as code

```
ShipmentServiceImpl.{create,cancel,transitionToDispatched,scanOneIn,assignOneOutForDelivery,deliver}
  -> eventPublisher.publishEvent(ShipmentEvent.{Booked,Cancelled,Dispatched,ReceivedAtBranch,OutForDelivery,Delivered})
       (plain scalars only — shipmentId/companyId/occurredAt, same discipline every other
        ShipmentEvent record already follows; never the entity itself)
  |
  v  AFTER_COMMIT, REQUIRES_NEW  (ShipmentCommunicationListener)
CommunicationOrchestrator.handle(companyId, shipmentId, eventType)
  -> ShipmentDirectoryPort.findSnapshot   (re-reads full detail — an event-carried copy
                                            would be stale by the time a retry re-processes it)
  -> for each channel (WHATSAPP, SMS, EMAIL):
       - CommunicationSettingService.findEnabled(companyId, channel)   channel master switch
       - CommunicationTemplateService.findActive(companyId, event, channel)  per-event switch
       - recipient's own channel preference (Customer.whatsappEnabled/smsEnabled/emailEnabled)
       - recipient address present for this channel
     -> queue one CommunicationLog row: PENDING (all four checks passed) or CANCELLED
        (with the specific reason) — a fast DB insert, no network call on this thread
  |
  v  CommunicationDispatchJob, @Scheduled, cross-tenant sweep
CommunicationSendService.processOne(logId, companyId)
  -> re-check setting/template are still active (a company could disable between queue and send)
  -> TemplateRenderer.render(content, snapshot, eventType, recipientName)
  -> WhatsAppProvider / SmsProvider / EmailProvider . send(...)
  -> row.status = SENT (+ providerMessageId) | FAILED (+ error, nextRetryAt) | CANCELLED
```

## Two deliberately separate on/off switches

The brief's own DB schema section (`communication_setting`: `id, company_id, channel, enabled,
provider, created_at, updated_at` — channel-grain only) contradicts its own "Default Events"
section ("Company Admin can enable/disable **each channel per event**" — event+channel grain).
Resolved by giving each grain its own switch, never conflating them:

- `communication_setting.enabled` — the channel-level master switch per company ("is WhatsApp
  usable at all for this company").
- `communication_template.status` (`ACTIVE`/`INACTIVE`) — the actual per-event-per-channel
  switch. An `INACTIVE` template means that one event never sends on that one channel,
  independent of the channel's own master switch.

Effective sending = company channel `enabled` AND that event's template `ACTIVE` on that
channel AND the recipient's own channel preference. The brief's simpler "Company enabled AND
Customer enabled" formula is the two-of-three-condition shorthand; the template check is the
condition it left implicit.

## Default templates — seeded lazily, not by migration

`CommunicationTemplateServiceImpl.seedDefaultsIfEmpty` inserts the four default events
(`SHIPMENT_BOOKED`/`SHIPMENT_DISPATCHED`/`OUT_FOR_DELIVERY`/`SHIPMENT_DELIVERED`) x three
channels = 12 rows the first time a company's templates are read (`list()`) or the orchestrator
needs one (`findActive`) — the same get-or-create precedent `CompanySettings` already set,
not a migration-time `INSERT` across every existing company (simpler, needs no company
enumeration, and a company created after this module shipped gets the same treatment for free).
`SHIPMENT_RECEIVED`/`SHIPMENT_CANCELLED` and the two RTO events are **not** seeded — they exist
for a Company Admin to create manually via `POST /communication/templates`, per the brief's own
"Default Events" list naming only four.

## Recipient choice

Decided by event type, not per-message: `CommunicationEventType.notifiesSender()` — booking/
dispatch/cancellation notify the **sender** (they made the booking); receipt/out-for-delivery/
delivery notify the **receiver** (it's arriving at them). `{{customerName}}` fills with
whichever party is actually being addressed; `{{receiverName}}` always resolves to the
shipment's own receiver regardless of who the message is addressed to. Both parties travel on
one `ShipmentSnapshot` (`sender`/`receiver` nested `Party` records) so neither the orchestrator
nor the send service needs a second lookup once the snapshot is read.

## RTO — declared, never published

`CommunicationEventType.RTO_INITIATED`/`RTO_DELIVERED` exist for architecture readiness (the
brief's own "architecture must allow future events") but **nothing publishes into them today**
— this codebase has no return-to-origin flow. `ShipmentStatus.RETURNED` is a generic terminal
state reachable from `IN_SCAN`/`OUT_FOR_DELIVERY` in the transition graph but no service method
writes it yet (per that enum's own doc comment, predating this module). A future RTO module can
start publishing `ShipmentEvent` records into these two rows with zero schema or enum change
here — flagged as a real gap, not guessed at or faked with a fabricated RTO flow.

## Providers — the brief's own "keep provider implementation replaceable"

Each channel gets an interface (`WhatsAppProvider`/`SmsProvider`/`EmailProvider`,
`application/provider/`) with a message/credentials record pair, plus two implementations
selected by an explicit `app.communication.<channel>.enabled` property
(`@ConditionalOnProperty`, never `@ConditionalOnMissingBean` — same reasoning
`PaymentGatewayConfig` documents for Razorpay: bean-selection order between a scanned component
and a conditional `@Bean` is not something to leave to chance):

| Channel | Default (`enabled=false`, matchIfMissing) | Real (`enabled=true`) |
|---|---|---|
| WhatsApp | `LogOnlyWhatsAppProvider` — logs, synthetic message id | `MetaWhatsAppProvider` — Meta Cloud API, plain `RestClient`, no SDK, approved-template sends only (Meta requires an approved template for any business-initiated message) |
| SMS | `LogOnlySmsProvider` | `GenericHttpSmsProvider` — POSTs `{to,message,senderId}` to whatever `apiUrl` a company configures; "do not hardcode provider" taken literally, no named vendor SDK |
| Email | `LogOnlyEmailProvider` | `SmtpEmailProvider` — new `spring-boot-starter-mail` dependency, platform-level `spring.mail.*` (one shared authenticated relay, same as most real deployments), a company only sets its own `fromName`/`fromEmail` identity |

No dev-environment vendor account exists for any of the three, so every deployment of this
codebase runs log-only by default — the same accepted-gap class auth's own
`LogOnlyNotificationSender` already is. Real credentials are per-company (WhatsApp/SMS) and
live in `communication_setting`, not per-JVM like Razorpay's platform-wide key pair — the
`app.communication.*.enabled` flags only select whether this deployment makes real outbound
calls **at all**, never one company's own configuration.

## Secrets

`communication_setting.secret` (Java field name; column `secret_encrypted`) is AES-256-GCM via
`EncryptedStringConverter` — the exact same converter `CompanyRazorpayConfig` (V46) already
uses for its own key secret. Plaintext in memory once loaded (Hibernate applies the converter
transparently); ciphertext only exists in the database column. Never returned by any API
response — `CommunicationSettingResponse` carries `secretConfigured: boolean` only.
`UpsertCommunicationSettingCommand.secret == null` means "keep the one already stored", the
same convention `CompanyRazorpayConfigRequest.keySecret` uses. Email carries no per-company
secret at all — SMTP credentials are environment-configured, not stored in this table.

`config` (non-secret provider config — WhatsApp `phoneNumberId`/`businessAccountId`, SMS
`apiUrl`/`senderId`, Email `fromName`/`fromEmail`) is a plain JSON-text column
(`communication_setting.config_json`), encoded/decoded via the small shared
`CommunicationConfigJson` helper — not a real `jsonb` column (no existing precedent in this
MySQL/Hibernate setup, and the shape genuinely differs per channel, so a fixed set of typed
columns would leave two of the three channels' columns always null).

## Database (`V50`)

Three new company-owned tables, the project's usual shape (UUID PK, `company_id`, soft delete,
audit columns, optimistic locking):

- **`communication_template`** — `(company_id, event_type, channel)` unique. `status`
  (`ACTIVE`/`INACTIVE`) is the per-event-per-channel switch (see above).
- **`communication_setting`** — `(company_id, channel)` unique. `enabled` is the channel master
  switch; `config_json`/`secret_encrypted` hold provider config (see Secrets above).
- **`communication_log`** — `(shipment_id, event_type, channel)` unique — this is what makes
  "no duplicate sends unless explicitly retried" a physical guarantee, not just application
  discipline: a retry updates this **same row** (`status`/`attempt_count`/`next_retry_at`)
  rather than inserting a new one. `next_retry_at`/`last_attempt_at` back the retry sweep;
  `ix_communication_log_next_retry (status, next_retry_at)` is the index that query needs.

`customers` gained `whatsapp_enabled`/`sms_enabled`/`email_enabled` (`BOOLEAN NOT NULL DEFAULT
TRUE` — opt-out, not opt-in), threaded through `CreateCustomerCommand`/`UpdateCustomerCommand`,
both request DTOs, `CustomerResponse`, `CustomerMapper`, and `CustomerServiceImpl.create`/
`update` (`null` in the create command means "default true").

## Cross-module seams

- **`ShipmentDirectoryPort`** (owned by `communication.domain`, implemented by
  `shipment.infrastructure.CommunicationShipmentDirectoryAdapter`) — same "interface owned by
  the consumer, implemented by the data owner" arrangement `TicketDirectoryPort`/
  `BranchDirectoryPort` already established. Goes straight to repositories
  (`ShipmentRepository`/`ShipmentChargeRepository`/`ShipmentAssetRepository`/
  `BranchRepository`/`CompanyRepository`/`CustomerRepository`), **never** through a
  `@PreAuthorize`-guarded service method — `CommunicationDispatchJob` calls this from a bare
  scheduler thread with no authenticated caller at all (confirmed the hard way: an early draft
  called `CustomerService.findOrCreateForBooking`/`BranchService.getById`, both
  `@PreAuthorize`-gated, which would `AccessDeniedException` the instant the dispatch job tried
  to re-read a snapshot with no `Authentication` in `SecurityContextHolder`). Sender/receiver
  `Customer` rows are **looked up, never created**, by exact mobile match — reusing the row
  `ShipmentServiceImpl.create`'s own `CustomerService.findOrCreateForBooking` call already wrote
  synchronously inside the booking transaction, not a second write path.
- **`ShipmentEvent`** — extended (not a new sealed interface) with six records (`Booked`,
  `Dispatched`, `ReceivedAtBranch`, `OutForDelivery`, `Delivered`, `Cancelled`), each carrying
  only `shipmentId`/`companyId`/`occurredAt`. This is a new *direction* of dependency versus the
  existing wallet listeners (`shipment` module depends on `finance`'s `WalletService` interface
  from inside its own package for those; here `communication` depends on `shipment`'s own event
  class) — deliberate, and exactly what the brief's own "business modules must NOT directly send
  messages" requires: `ShipmentServiceImpl` publishes and has no idea this module exists;
  `communication` is the one that listens.

## RBAC

Role-based like every module since Ticket Support (no new `PermissionModule`/
`PermissionAction` catalogue rows — the "authorise on permissions" capstone in `BACKLOG.md` is
still the only gap, not new here):

| Action | Roles |
|---|---|
| Settings/Templates read (dashboard implicitly too) | `COMPANY_ADMIN`, `BRANCH_MANAGER` |
| Settings/Templates write | `COMPANY_ADMIN` only |
| Log read/retry | `COMPANY_ADMIN`, `BRANCH_MANAGER` |

The brief's own four permission-code names (`COMMUNICATION_VIEW`/`COMMUNICATION_TEMPLATE_MANAGE`/
`COMMUNICATION_SETTINGS_MANAGE`/`COMMUNICATION_RETRY`) map onto this role split conceptually,
not as physical catalogue rows — same treatment Ticket/POD/E-Way Bill's own permission-shaped
brief language already got.

## API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/communication/templates` | Seeds the 12 defaults on first read |
| `GET` | `/api/v1/communication/templates/{id}` | |
| `POST` | `/api/v1/communication/templates` | 409-shaped refusal on a duplicate (event, channel) |
| `PUT` | `/api/v1/communication/templates/{id}` | Full replacement incl. `status` — the enable/disable switch |
| `GET` | `/api/v1/communication/templates/{id}/preview` | Renders against synthetic sample data |
| `GET` | `/api/v1/communication/settings` | Seeds the 3 channel rows on first read |
| `GET` | `/api/v1/communication/settings/{channel}` | |
| `PUT` | `/api/v1/communication/settings/{channel}` | `secret` blank/omitted keeps the one stored |
| `POST` | `/api/v1/communication/settings/{channel}/test-connection` | Validates config completeness, not a live vendor handshake — no real credential in this dev environment to genuinely test |
| `GET` | `/api/v1/communication/logs` | Paged/filtered (shipment/customer/event/channel/status) |
| `GET` | `/api/v1/communication/logs/{id}` | |
| `GET` | `/api/v1/communication/logs/shipment/{shipmentId}` | Backs the Shipment Details Communication tab |
| `POST` | `/api/v1/communication/logs/{id}/retry` | `FAILED` only; requeues immediately, bypassing backoff |
| `GET` | `/api/v1/communication/dashboard` | Today's Sent/Delivered/Failed/Pending, per-channel |

## Frontend (`features/communication`)

`communication-dashboard.ts` (stat tiles + per-channel card, `Sent` folds in `Delivered` —
see below), `channel-settings.ts` (one `app-card` per channel: enable toggle, provider,
channel-specific config fields, secret input with "configured, leave blank to keep" hint, Test
Connection), `communication-templates.ts` (table, row click opens `TemplateEditDialog`),
`components/template-edit-dialog.ts` (enable toggle, subject for Email only, content textarea,
clickable `{{variable}}` chips, Preview — saves first so the preview reflects what's actually
being edited, tracks the returned `version` locally so a Preview-then-Save in one session never
409s against its own prior write), `communication-logs.ts` (filter/paginate/Retry Failed),
`components/shipment-communication-card.ts` (embedded in `shipment-view.ts` — one row per event
actually attempted, a chip per channel: `✓ WhatsApp Sent`, `✗ SMS Failed`, per the brief's own
worked example; only events with at least one log row render, nothing to show for a shipment
still awaiting its first movement). `customer-form.ts` gained a "Communication Preferences"
card (`mat-checkbox` x3, reusing the same Material component `module-permission-card.ts`/
`branch-form.ts`'s flags already use).

**Dashboard's `Sent` folds in `Delivered`** (`CommunicationDashboardServiceImpl.today()`,
mirrored client-side): the brief's own worked example shows Delivered as always a subset of
Sent, never counted separately — WhatsApp 1200 Sent / 1150 Delivered / 50 Failed sums to 1250,
not 1200, unless Sent already includes Delivered. `DELIVERED` itself stays at zero in every
environment this module has run in — see below.

## Testing

36 new backend unit tests (`mvn test` 835 → 871): `CommunicationOrchestratorTest` (success,
disabled channel, customer opted out, no active template, duplicate event, shipment-not-found,
recipient-by-event-type, company isolation), `CommunicationSendServiceImplTest` (success,
failure+retry-scheduling, retry stops at `maxAttempts`, channel disabled at send time,
template deactivated at send time, terminal row never reprocessed), `CommunicationLogServiceImplTest`
(retry only from `FAILED`, company isolation), `CommunicationTemplateServiceImplTest`
(seed-on-first-read, no-reseed, duplicate refusal, stale-version conflict),
`CommunicationSettingServiceImplTest` (seed, blank-secret-keeps-stored, secret rotation,
test-connection), `CommunicationDashboardServiceImplTest` (Delivered-folds-into-Sent math,
empty-day zeros), `TemplateRendererTest` (every documented variable, unrecognised placeholder
left verbatim, blank fields render empty not `null`), `CommunicationEventTypeTest`
(sender/receiver-facing split, default-enabled set). 11 new frontend tests
(`communication.service.spec.ts`, HTTP contract per endpoint) — `ng test` 134 → 145, the one
pre-existing `reports-dashboard` nav failure (documented since 0.28.5/0.29.2) untouched.

**Verified fully live** on throwaway `:8083` (`:8100`/`:4200` untouched; a concurrent session's
own `:8082`/`:4300` also live throughout, also untouched) against real `courier_db`: `V50`
applied cleanly; settings/templates lazy-seed exactly 3/12 rows; a fresh test shipment
(`PUNE-000019`, own fixture) booked and its `SHIPMENT_BOOKED` event queued 3 log rows, the
dispatch sweep picked up WhatsApp/SMS within one interval and marked them `SENT` with a
synthetic `providerMessageId`, Email correctly `CANCELLED` ("No EMAIL address on file" — the
quick test customer had none); dashboard aggregation matched exactly; cancelling that same
shipment queued `SHIPMENT_CANCELLED` rows correctly `CANCELLED` ("No active template" — proving
the seed-only-four-events design live); `test-connection` correctly reported missing WhatsApp
credentials; a `BRANCH_MANAGER` token correctly 403'd on `PUT /communication/settings/WHATSAPP`;
template preview rendered correctly; the auto-created `Customer` row carried the expected
`whatsappEnabled=smsEnabled=emailEnabled=true` defaults.

**Same-day follow-up ("test it live"), full Chrome click-through as `first.admin@gmail.com`
(`COMPANY_ADMIN`) against the same throwaway `:8083`** (`:4301`, its own dev-server pair —
`:4200`/`:4300` again untouched): navigated every one of the four Communication Center nav
leaves, filled and saved real WhatsApp config (Provider/Phone Number ID/Business Account
ID/Access Token) end to end, confirmed `secretConfigured` flips true and the secret field
never round-trips ("Configured — leave blank to keep it"), `test-connection` correctly
flipped from "required" to "Phone Number ID and Access Token are set." after saving,
Templates list/edit dialog/Preview all confirmed with real rendered sample output, Logs
page rendered the same rows the earlier curl pass created with working filters, the
Shipment Details Communication tab rendered exactly the brief's own worked example
("✓ WhatsApp Sent ✓ SMS Sent … Email Cancelled"), and a real Customer create through the UI
persisted a deliberately-unchecked `smsEnabled=false` correctly (confirmed via a direct
`GET /customers/{id}`).

**Two real, live-found UI bugs, both fixed same session:**

1. **Chrome autofill silently overwrote unrelated Channel Settings fields.** `channel-settings.ts`'s
   secret `<app-input type="password">` sat beside plain-text config fields (Business Account
   ID, Sender ID) with no `autocomplete` override beyond `UiInput`'s own default `"off"` —
   Chrome's password-manager heuristic ignores `autocomplete="off"` once it decides a
   `type="password"` field on the page looks like a login form, and filled the *adjacent*
   text fields with the signed-in admin's own saved email, and the password field with their
   own saved login password. Confirmed via `element.value` (not just visually) before fixing
   — this was real form state a careless Save would have persisted as a company's WhatsApp
   Business Account ID / access token. **Fix**: `autocomplete="new-password"` on the secret
   input (the one hint Chrome actually respects for "this is not a login") — verified after
   the fix that all four fields render their real placeholders with no autofill pollution.
2. **Customer Communication Preferences checkboxes were invisible.** `customer-form.ts`'s
   sticky `.cform__bar` action bar (`position: sticky; bottom: 0`, opaque `background:
   var(--surface)`) sits in normal flow after every card; once the new "Communication
   Preferences" card pushed the form's total height into the exact band where the sticky
   bar's pinned position overlaps the tail of the preceding card, it painted directly over
   the three `mat-checkbox` elements — confirmed via `getBoundingClientRect()`: the
   checkboxes' own box (`y=716..752`) sat entirely inside the bar's box (`y=683.5..755.5`),
   even though every individual computed style (color, opacity, display) looked completely
   normal in isolation. Real DOM elements, real colors, simply painted underneath an opaque
   sibling. **Fix**: `padding-bottom: 88px` on `.cform` (the bar's own height plus a gap) so
   the sticky bar can never overlap normal-flow content regardless of how many cards precede
   it — a structural fix, not specific to this one card, so any future card added to this
   form is covered too.

## Not verified live

- A genuine `FAILED` → retry cycle — no real vendor credentials in this dev environment to
  force a provider failure (log-only providers never fail). Covered by
  `CommunicationSendServiceImplTest.failure_marksFailedAndSchedulesRetry`/
  `retry_stopsAfterConfiguredMaxAttempts` and `CommunicationLogServiceImplTest.retry_*` instead.
- `DELIVERED` status — reachable only via a provider delivery-receipt webhook, which does not
  exist yet for any channel (no such endpoint was in scope for this task). Modelled fully
  (`CommunicationStatus.DELIVERED`, folded into dashboard `Sent`) but never reached anywhere
  this module has run.
- A `BRANCH_MANAGER`/other-role token against Templates read (only `COMPANY_ADMIN`'s own write
  path and `BRANCH_MANAGER`'s refusal were exercised live; the read-side role split is
  unit-untested but mirrors the same annotation pattern verified live for settings).
- The `RIVAL_CO` cross-company check (inherited gap, no active user there — same as every other
  module's own "Not exercised" list in this file).

## Deliberately not built

A real WhatsApp/SMS/Email delivery-receipt webhook (would light up `DELIVERED` for real — no
provider account exists in this dev environment to receive one from). A standalone RTO module
(would give `RTO_INITIATED`/`RTO_DELIVERED` a publisher). Per-company SMTP credentials (SMTP is
platform infrastructure here, not a per-company secret — see Providers above). The
authorise-on-permissions capstone (this module is role-gated like every module before it).

## 2026-10-14 additions

Built after the original module, all in `CHANGELOG.md` 2026-10-14: real Meta send fixes; the
daily 9:00 AM summary (`modules/communication-daily-report.md`, `V118`); template submission to
Meta (`V119`, `MetaTemplateConverter`, `WhatsAppTemplateSubmissionService`); the template page
with live phone preview (`features/communication/template-detail.ts`). Seeding is now additive
(`seedMissingDefaults`), not "only when empty". Still not built: a cost/billing view (Meta's WhatsApp Manager is the source of truth).
Template parts (category, language, header, footer, link/call buttons, sample values) and the
per-template Sending switch landed in `V122` — see the CHANGELOG entry "Template creation".
Delivery receipts: Meta posts them to `/api/v1/webhooks/whatsapp` (`V123`); that is the only thing that
makes `DELIVERED` and Read real. Setup: Meta app -> WhatsApp -> Configuration -> Webhook, callback
`https://<domain>/api/v1/webhooks/whatsapp`, verify token = `COMMUNICATION_WHATSAPP_WEBHOOK_VERIFY_TOKEN`,
server also needs `COMMUNICATION_WHATSAPP_APP_SECRET`, subscribe to `messages`. Public https only.

Also: New template page + Reset to default (`StarterTemplates`), and a WhatsApp cost estimate priced
only from rates the company enters (`rateUtility`/`rateMarketing`/`rateAuthentication` in the WhatsApp
channel config) — see the CHANGELOG entry "New template page, Reset to default, WhatsApp cost estimate".



## Picked up (Phase 10.1, 2026-11-06)

`SHIPMENT_PICKED_UP` is a default event (sender-facing). Published as `ShipmentEvent.PickedUp` by `PickupServiceImpl.complete` when the pickup already carries its portal shipment. Existing companies get its three templates from `seedMissingDefaults`.
