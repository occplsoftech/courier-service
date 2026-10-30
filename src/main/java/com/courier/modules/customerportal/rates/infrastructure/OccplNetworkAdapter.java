package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.LabelResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import com.courier.modules.customerpricing.application.ZoneRateService;
import com.courier.modules.customerpricing.application.ZoneRateService.Quote;
import com.courier.modules.customerpricing.application.ZoneRateService.QuoteCommand;
import com.courier.modules.master.application.PincodeLocation;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.pickup.application.PickupService;
import com.courier.modules.pickup.domain.PickupRequest;
import com.courier.modules.pickup.domain.PickupStatus;
import com.courier.modules.shipment.application.ShipmentPortalQueries;
import com.courier.modules.tariff.application.TariffQuoteService;
import com.courier.modules.tariff.domain.TariffPriceCalculator;
import com.courier.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * OCCPL's own courier network, priced from the business customer's own rate card (pricing zones,
 * {@code ZoneRateService}) — the same rates booking at the counter uses. No markup. Runs on the request
 * thread: it needs the bound company and the signed-in principal.
 *
 * <p>The rate card charges actual weight (or the zone minimum), not volumetric weight.
 *
 * <p>A customer with no rate card for the destination gets the company's zone tariff instead (the cash rates, module
 * tariff): one option per service type that has a parcel rate to the delivery zone, with the card's charges and GST.
 * Its round-off is left out, so the portal shows the exact amount.
 *
 * <p>Booking creates a <b>pickup request</b> in OCCPL's own operations flow (module pickup): operations
 * assign it, collect the parcel and book the shipment at the branch, which issues the tracking number. The
 * delivery details, contents and quoted total go into the pickup's remarks for the booking clerk.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OccplNetworkAdapter implements CourierProviderAdapter {

    public static final String CODE = "OCCPL_NETWORK";
    /** Service code of the option priced from the customer's own rate card (the bulk upload prefers it). */
    public static final String BUSINESS_SERVICE_CODE = "OCCPL_BUSINESS_RATE";

    private final PincodeService pincodes;
    private final ZoneRateService zoneRates;
    private final PickupService pickups;
    private final ShipmentPortalQueries shipments;
    private final TariffQuoteService tariff;

    @Override public String code() { return CODE; }
    @Override public String displayName() { return "OCCPL Network"; }
    @Override public boolean external() { return false; }
    @Override public boolean configured() { return true; }

    @Override
    public ProviderResult getRates(RateRequest r) {
        Optional<Pincode> pickup = find(r.pickupPincode());
        if (pickup.isEmpty() || !pickup.get().isServiceable() || !pickup.get().isPickupAvailable()) {
            return unavailable("OCCPL network does not pick up from " + r.pickupPincode() + ".");
        }
        PincodeLocation delivery = pincodes.location(r.deliveryPincode()).orElse(null);
        if (delivery == null || !delivery.serviceable() || delivery.districtId() == null) {
            return unavailable("OCCPL network does not deliver to " + r.deliveryPincode() + ".");
        }
        if (r.cod() && !delivery.codAvailable()) {
            return unavailable("Cash on delivery is not available at " + r.deliveryPincode() + " on OCCPL network.");
        }
        Quote q;
        try {
            q = zoneRates.calculate(new QuoteCommand(r.customerId(), delivery.districtId(), delivery.cityId(),
                    r.actualWeightKg(), r.declaredValue(), delivery.odaApplicable(), LocalDate.now()));
        } catch (ApiException e) {
            // No zone / no active rate / no slab for this weight: the customer has no contract price here.
            log.debug("No OCCPL network rate for customer {} to {}: {}", r.customerId(), r.deliveryPincode(), e.getMessage());
            return tariffRates(r);
        }
        List<ChargeLine> lines = new ArrayList<>();
        lines.add(new ChargeLine("FREIGHT", "Freight", ChargeComponent.FREIGHT, q.freight()));
        add(lines, "DOCKET", "Docket charge", ChargeComponent.HANDLING, q.docketCharge());
        add(lines, "MATHADI", "Mathadi (loading) charge", ChargeComponent.HANDLING, q.mathadiCharge());
        add(lines, "PACKING", "Packing charge", ChargeComponent.HANDLING, q.packCharge());
        add(lines, "ODA", "Out-of-delivery-area charge", ChargeComponent.REMOTE_AREA, q.odaCharge());
        add(lines, "FUEL", "Fuel surcharge", ChargeComponent.FUEL_SURCHARGE, q.fuelSurcharge());
        add(lines, "FOV", "Risk cover (FOV)", ChargeComponent.INSURANCE, q.fovCharge());
        return ProviderResult.available(CODE, displayName(), List.of(new ProviderRate(
                BUSINESS_SERVICE_CODE, "OCCPL Standard", "SURFACE", null, null, q.chargeableWeight(), lines, null, null)));
    }

    /** The company's zone tariff for parcels, one option per service type; unavailable when it has none. */
    private ProviderResult tariffRates(RateRequest r) {
        List<TariffQuoteService.Quote> quotes;
        try {
            quotes = tariff.quoteOptions(new TariffQuoteService.QuoteInput(r.pickupPincode(), r.deliveryPincode(), null, null,
                    r.actualWeightKg(), r.lengthCm(), r.breadthCm(), r.heightCm(), r.packageCount(), r.declaredValue(),
                    r.cod(), null), false);
        } catch (ApiException e) {
            log.debug("No zone tariff rate from {} to {}: {}", r.pickupPincode(), r.deliveryPincode(), e.getMessage());
            quotes = List.of();
        }
        if (quotes.isEmpty()) {
            return unavailable("No OCCPL network rate is set up for this destination and weight. Contact your account manager.");
        }
        return ProviderResult.available(CODE, displayName(), quotes.stream().map(OccplNetworkAdapter::tariffRate).toList());
    }

    static ProviderRate tariffRate(TariffQuoteService.Quote q) {
        TariffPriceCalculator.Price p = q.price();
        List<ChargeLine> lines = new ArrayList<>();
        lines.add(new ChargeLine("FREIGHT", "Freight", ChargeComponent.FREIGHT, p.freight().amount()));
        for (TariffPriceCalculator.ChargeLine c : p.charges()) {
            add(lines, c.code(), c.name(), component(c.code()), c.amount());
        }
        for (TariffPriceCalculator.TaxLine t : p.taxes()) {
            add(lines, t.code(), t.code() + " " + t.percent().stripTrailingZeros().toPlainString() + "%", ChargeComponent.TAX, t.amount());
        }
        String mode = q.serviceTypeCode().length() <= 20 ? q.serviceTypeCode() : q.serviceTypeCode().substring(0, 20);
        return new ProviderRate("OCCPL_" + q.serviceTypeCode(), "OCCPL " + q.serviceTypeName(), mode,
                q.deliveryDays(), q.deliveryDays(), p.chargeableWeightKg(), lines, q.card().getCode(), null);
    }

    private static ChargeComponent component(String chargeCode) {
        return switch (chargeCode) {
            case "FUEL" -> ChargeComponent.FUEL_SURCHARGE;
            case "COD" -> ChargeComponent.COD;
            case "ODA" -> ChargeComponent.REMOTE_AREA;
            case "FOV", "INSURANCE" -> ChargeComponent.INSURANCE;
            case "DOCKET", "HAMALI", "MATHADI", "PACKING", "HANDLING" -> ChargeComponent.HANDLING;
            default -> ChargeComponent.OTHER;
        };
    }

    @Override
    public BookingOutcome book(BookingRequest b) {
        PincodeLocation pickupAt = pincodes.location(b.pickup().pincode()).orElse(null);
        try {
            PickupRequest p = pickups.createFromCustomerPortal(new PickupService.SaveCommand(
                    b.customerId(), b.pickup().name(), b.pickup().phone(), b.pickup().address(), b.pickup().city(),
                    pickupAt == null ? null : pickupAt.districtId(), b.pickup().pincode(), b.rate().packageCount(),
                    b.rate().actualWeightKg(), null, null, remarks(b), null, null));
            return BookingOutcome.booked(p.getPickupNo(), null, null,
                    "Pickup " + p.getPickupNo() + " requested. The tracking number is issued when the parcel is collected and booked.");
        } catch (ApiException e) {
            // Local validation refused it inside our own transaction: nothing was created.
            return BookingOutcome.rejected("PICKUP_REFUSED", e.getMessage());
        }
    }

    /**
     * The pickup's own steps, then — once the branch has booked the parcel — the shipment's status history.
     * Both are this customer's only (the pickup is looked up by number for the signed-in customer).
     */
    @Override
    public TrackingResult track(ShipmentRef ref) {
        Optional<PickupRequest> found = ref.providerOrderRef() == null ? Optional.empty()
                : pickups.findByNumberForCustomer(ref.providerOrderRef(), ref.customerId());
        if (found.isEmpty()) {
            return TrackingResult.unavailable("This pickup request could not be found. Please contact support.");
        }
        PickupRequest p = found.get();
        List<TrackingEvent> events = new ArrayList<>();
        event(events, p.getCreatedAt(), "Pickup requested", p.getCity(), "Pickup " + p.getPickupNo() + " requested");
        event(events, p.getAssignedAt(), "Pickup assigned", null, "Assigned to a pickup team");
        event(events, p.getAcceptedAt(), "Pickup accepted", null, null);
        event(events, p.getStartedAt(), "Out for pickup", null, null);
        event(events, p.getPickedUpAt(), "Picked up", p.getCity(), null);
        if (p.getStatus() == PickupStatus.CANCELLED) {
            event(events, p.getUpdatedAt(), "Pickup cancelled", null, p.getCancelReason());
        } else if (p.getStatus() == PickupStatus.FAILED) {
            event(events, p.getUpdatedAt(), "Pickup failed", null, p.getFailReason());
        }
        String status = ProviderTimes.label("PICKUP_" + p.getStatus().name());
        String trackingNumber = null;
        String location = null;
        if (p.getShipmentId() != null) {
            Optional<ShipmentPortalQueries.PortalTimeline> timeline =
                    shipments.timelineOfPickupShipment(ref.companyId(), p.getShipmentId());
            if (timeline.isPresent()) {
                ShipmentPortalQueries.PortalTimeline t = timeline.get();
                trackingNumber = t.shipment().trackingNumber() != null ? t.shipment().trackingNumber()
                        : t.shipment().shipmentNumber();
                status = ProviderTimes.label(t.shipment().status().name());
                for (ShipmentPortalQueries.PortalStep step : t.steps()) {
                    event(events, step.at(), ProviderTimes.label(step.status().name()), step.branchName(), null);
                    if (step.branchName() != null) {
                        location = step.branchName();
                    }
                }
            }
        }
        events.sort(Comparator.comparing(TrackingEvent::at).reversed());
        return TrackingResult.of(status, trackingNumber, location, events);
    }

    @Override
    public LabelResult label(ShipmentRef ref) {
        return LabelResult.notAvailable("OCCPL prints the shipping label when your parcel is booked at the branch.");
    }

    private static void event(List<TrackingEvent> events, Instant at, String status, String location,
                              String description) {
        if (at != null) {
            events.add(new TrackingEvent(at, status, location, description));
        }
    }

    /** What the pickup team needs to know about payment: To Pay means the freight is collected from the receiver. */
    private static String paymentText(BookingRequest b) {
        if (b.rate().topay()) {
            return "TO PAY: collect INR " + b.customerTotal().toPlainString() + " freight from the receiver on delivery";
        }
        return b.rate().cod() ? "COD" : "prepaid";
    }

    static String remarks(BookingRequest b) {
        String text = "Portal booking " + b.reference() + (b.serviceCode() == null ? "" : ", service " + b.serviceCode())
                + ". Deliver to " + b.delivery().name() + ", " + b.delivery().phone()
                + ", " + b.delivery().address() + ", " + b.delivery().city() + " " + b.delivery().pincode()
                + ". Contents: " + b.contents() + ". Value " + b.rate().declaredValue().toPlainString()
                + ", " + paymentText(b) + ", " + b.rate().packageCount() + " pkg, "
                + b.rate().lengthCm().toPlainString() + "x" + b.rate().breadthCm().toPlainString() + "x"
                + b.rate().heightCm().toPlainString() + " cm. Quoted total INR " + b.customerTotal().toPlainString() + ".";
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private Optional<Pincode> find(String code) {
        try {
            return Optional.of(pincodes.findByCode(code));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    private static void add(List<ChargeLine> lines, String code, String label, ChargeComponent c, BigDecimal amount) {
        if (amount != null && amount.signum() > 0) {
            lines.add(new ChargeLine(code, label, c, amount));
        }
    }

    private ProviderResult unavailable(String reason) {
        return ProviderResult.unavailable(CODE, displayName(), reason);
    }
}
