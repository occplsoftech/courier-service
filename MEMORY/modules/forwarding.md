# Module: Forwarding a shipment to a courier partner (phase 8)

> Read before touching `com.courier.modules.forwarding`, the `forward_*` columns on `shipments` (V155) or `ShipmentStatus.FORWARDED`.

## What it is

A shipment OCCPL collected and brought to a hub or branch can be handed to a connected external courier (Delhivery, Shiprocket, Shipyaari, Porter...). The customer's price is unchanged and the customer keeps the OCCPL tracking number; the courier's own AWB, label and cost are stored on the shipment, which becomes `FORWARDED`.

## Rules

- **Who:** the company admin, or a branch / hub manager whose own branch holds the shipment (`current_location_id`, else the booking branch). Anyone else: 403.
- **When:** only while the shipment is `BOOKED`, `READY_FOR_MANIFEST` or `IN_SCAN`, and never twice (`Shipment.forwardBlocker()`).
- **Not To Pay:** a courier cannot collect OCCPL's freight from the receiver, so a To Pay shipment is refused. COD is allowed (rate request carries COD).
- **Collect from the hub:** the pickup address is the holding branch's address and pincode (a hub with no valid pincode is refused with a message to set it). Delivery is the shipment's receiver.
- **Prices are the courier's own** (no markup, no tax added) and are asked afresh at forward time; nothing a browser sends is a price. `RateComparisonService.askExternalForForwarding` asks every connected external courier and ignores the customer's courier-access list (a staff decision).
- **Booked once:** `ShipmentService.beginForward` marks `forward_state = SUBMITTING` (blocks a second attempt, optimistic lock), the courier is called with the shipment number as the order reference (a courier that refuses duplicate order ids protects us again), then `completeForward` (state `BOOKED`, status `FORWARDED`, history + audit) or `abandonForward`.
- **Refused** by the courier: the attempt is cleared, the shipment stays where it was, the reason comes back as a 422.
- **Unknown** (courier error or an answer we cannot read): `forward_state = UNKNOWN` with a note, outcome `UNCONFIRMED`; a person checks with the courier. Never retried by itself, and it blocks another forward. The courier's order id is kept. Staff settle it with `POST .../forwarding/resolve`: *courier did not take it* (cancel at the courier via `cancelOrder`, Shiprocket only for now; the flag is cleared only after the courier confirms) or *courier did take it* (AWB entered by hand → FORWARDED).
- A forwarded shipment cannot be cancelled here (cancel at the courier first).

## API (`/api/v1/shipments/{id}/forwarding`)

| Call | What |
|---|---|
| `GET /options` | Couriers' prices for this shipment (cheapest first), the unavailable ones with their reason, `collectFrom`, and `blocker` when it cannot be forwarded |
| `POST` `{providerCode, serviceCode}` | Forward; `outcome` FORWARDED or UNCONFIRMED; refusal = 422 |

The shipment response carries `forward { provider, service, state, awb, labelUrl, cost, forwardedAt, note }`.

## Customer tracking

`OccplNetworkAdapter.track` follows a forwarded shipment through its courier: it adds "Handed to <courier>" (with the courier's tracking number), asks the courier adapter for its status and events (via `ShipmentPortalQueries.Forwarded`), and merges them with OCCPL's own steps. The customer keeps the OCCPL tracking number. If the courier cannot be reached the answer is still "Handed to <courier>"; the background sync (every 2 minutes for OCCPL bookings) stores the stage, so "Delivered" from the courier finishes the booking.

## Staff screen

courier-web `shipment-view` (Forward to courier button, `ShipmentForwardDialog`, Courier partner card).

## Not built (later)

Bulk forwarding from a list; syncing a forwarded shipment's `DELIVERED` back onto the shipment itself (today the customer's booking shows it, the shipment stays FORWARDED); cancelling a forward at the courier.
