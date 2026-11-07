package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import com.courier.modules.customerpricing.application.ZoneRateService;
import com.courier.modules.master.application.PincodeLocation;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.pickup.application.PickupService;
import com.courier.modules.pickup.domain.PickupRequest;
import com.courier.modules.pickup.domain.PickupStatus;
import com.courier.modules.shipment.application.ShipmentPortalQueries;
import com.courier.modules.tariff.application.TariffQuoteService;
import com.courier.modules.tariff.domain.TariffPriceCalculator;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.application.TariffZoneService;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OccplNetworkAdapterTest {

    @Mock private PincodeService pincodes;
    @Mock private ZoneRateService zoneRates;
    @Mock private PickupService pickups;
    @Mock private ShipmentPortalQueries shipments;
    @Mock private TariffQuoteService tariff;
    @Mock private com.courier.modules.shipment.application.ShipmentService shipmentService;
    @Mock private com.courier.modules.company.application.BranchPincodeMappingService branchMappings;
    @Mock private com.courier.modules.customer.application.CustomerPortalLookup customers;
    @Mock private org.springframework.beans.factory.ObjectProvider<List<com.courier.modules.customerportal.rates.domain.CourierProviderAdapter>> allAdapters;
    @InjectMocks private OccplNetworkAdapter adapter;

    private static final UUID DISTRICT = UUID.randomUUID();

    private RateRequest request() {
        return new RateRequest(UUID.randomUUID(), UUID.randomUUID(), "411016", "110001", new BigDecimal("12"),
                new BigDecimal("30"), new BigDecimal("20"), new BigDecimal("10"), 1, new BigDecimal("5000"),
                RateRequest.PaymentMode.PREPAID);
    }

    private void served() {
        Pincode pickup = mock(Pincode.class);
        when(pickup.isServiceable()).thenReturn(true);
        when(pickup.isPickupAvailable()).thenReturn(true);
        when(pincodes.findByCode("411016")).thenReturn(pickup);
        when(pincodes.location("110001")).thenReturn(Optional.of(
                new PincodeLocation(UUID.randomUUID(), UUID.randomUUID(), null, DISTRICT, true, true, false)));
    }

    private static ZoneRateService.Quote quote(String total) {
        return new ZoneRateService.Quote(UUID.randomUUID(), "North", new BigDecimal("12"), new BigDecimal("12"), new BigDecimal("40"),
                new BigDecimal("480.00"), new BigDecimal("50"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("48.00"),
                new BigDecimal("10.00"), new BigDecimal(total));
    }

    @Test
    @DisplayName("a rate card per service type gives one option per type, marked as the customer's business rate")
    void rateCardPerServiceType() {
        served();
        when(zoneRates.calculateEach(any())).thenReturn(List.of(
                new ZoneRateService.ServiceQuote(UUID.randomUUID(), "AIR", "Air", quote("900.00")),
                new ZoneRateService.ServiceQuote(UUID.randomUUID(), "SRF", "Surface", quote("588.00"))));
        when(tariff.quoteOptions(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(List.of());

        ProviderResult result = adapter.getRates(request());

        assertThat(result.rates()).extracting(r -> r.serviceCode()).containsExactly("OCCPL_AIR", "OCCPL_SRF");
        assertThat(result.rates()).extracting(r -> r.serviceName()).containsExactly("OCCPL Air", "OCCPL Surface");
        // the mode label is the type's name, not its code: the portal shows and filters by it
        assertThat(result.rates()).extracting(r -> r.transportMode()).containsExactly("AIR", "SURFACE");
        assertThat(result.rates()).extracting(r -> r.providerQuoteRef()).containsOnly(OccplNetworkAdapter.BUSINESS_REFERENCE);
    }

    @Test
    @DisplayName("a card with a minimum chargeable weight above 1 kg carries a note saying so, whatever the parcel weighs")
    void minimumChargeableWeightNote() {
        served();
        ZoneRateService.Quote cargo = new ZoneRateService.Quote(UUID.randomUUID(), "North", new BigDecimal("25"), new BigDecimal("25"),
                new BigDecimal("35"), new BigDecimal("875"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("875"), false, new BigDecimal("20"));
        ZoneRateService.Quote surface = new ZoneRateService.Quote(UUID.randomUUID(), "North", new BigDecimal("25"), new BigDecimal("25"),
                new BigDecimal("40"), new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000"), false, BigDecimal.ONE);
        when(zoneRates.calculateEach(any())).thenReturn(List.of(
                new ZoneRateService.ServiceQuote(UUID.randomUUID(), "CRG", "Cargo", cargo),
                new ZoneRateService.ServiceQuote(UUID.randomUUID(), "SRF", "Surface", surface)));
        when(tariff.quoteOptions(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(List.of());

        ProviderResult result = adapter.getRates(request());

        assertThat(result.rates().get(0).note()).isEqualTo("Minimum chargeable weight 20 kg");
        assertThat(result.rates().get(1).note()).isNull();
    }

    @Test
    @DisplayName("a service type with no card of its own is priced at the company's rates, an all-services card covers every type")
    void typesWithoutACardUseCompanyRates() {
        served();
        TariffPriceCalculator.Price price = new TariffPriceCalculator.Price(new BigDecimal("12"), null, new BigDecimal("12"),
                new TariffPriceCalculator.Freight(new BigDecimal("1200.00"), null, null, 0, "Fixed"), List.of(),
                new BigDecimal("1200.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1200.00"));
        TariffZoneService.Resolution where = new TariffZoneService.Resolution(TariffZone.builder().code("PUNE").name("Pune").build(), null, null);
        TariffRateCard card = TariffRateCard.builder().code("PUNE_CASH").build();
        when(tariff.quoteOptions(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(List.of(
                new TariffQuoteService.Quote(card, where, where, UUID.randomUUID(), "AIR", "Air", 2, UUID.randomUUID(), "NONDOC", false, price),
                new TariffQuoteService.Quote(card, where, where, UUID.randomUUID(), "CARGO", "Cargo", 5, UUID.randomUUID(), "NONDOC", false, price)));

        when(zoneRates.calculateEach(any())).thenReturn(List.of(new ZoneRateService.ServiceQuote(UUID.randomUUID(), "AIR", "Air", quote("900.00"))));
        assertThat(adapter.getRates(request()).rates()).extracting(r -> r.serviceCode()).containsExactly("OCCPL_AIR", "OCCPL_CARGO");
        assertThat(adapter.getRates(request()).rates().get(1).providerQuoteRef()).isNotEqualTo(OccplNetworkAdapter.BUSINESS_REFERENCE);

        when(zoneRates.calculateEach(any())).thenReturn(List.of(new ZoneRateService.ServiceQuote(null, null, null, quote("588.00"))));
        assertThat(adapter.getRates(request()).rates()).extracting(r -> r.serviceCode()).containsExactly(OccplNetworkAdapter.BUSINESS_SERVICE_CODE);
    }

    @Test
    @DisplayName("prices from the customer's own rate card, one line per charge, no zero lines")
    void pricesFromRateCard() {
        served();
        when(zoneRates.calculateEach(any())).thenReturn(List.of(new ZoneRateService.ServiceQuote(null, null, null, quote("588.00"))));

        ProviderResult result = adapter.getRates(request());

        assertThat(result.available()).isTrue();
        assertThat(result.rates().get(0).providerTotal()).isEqualByComparingTo("588.00");
        assertThat(result.rates().get(0).charges()).extracting(l -> l.component()).containsExactly(
                ChargeComponent.FREIGHT, ChargeComponent.HANDLING, ChargeComponent.FUEL_SURCHARGE, ChargeComponent.INSURANCE);
    }

    @Test
    @DisplayName("no zone/rate for the destination means unavailable, not an error")
    void noRateCard() {
        served();
        when(zoneRates.calculateEach(any())).thenReturn(List.of());

        ProviderResult result = adapter.getRates(request());

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("No OCCPL network rate");
    }

    @Test
    @DisplayName("no contract rate: the company's zone tariff, one option per service type, with its charges and GST")
    void zoneTariffFallback() {
        served();
        when(zoneRates.calculateEach(any())).thenReturn(List.of());
        TariffPriceCalculator.Price price = new TariffPriceCalculator.Price(new BigDecimal("12"), null, new BigDecimal("12"),
                new TariffPriceCalculator.Freight(new BigDecimal("1200.00"), null, null, 0, "Fixed"),
                List.of(new TariffPriceCalculator.ChargeLine("FUEL", "Fuel surcharge", new BigDecimal("120.00"), true, "10% of freight", null)),
                new BigDecimal("1320.00"),
                List.of(new TariffPriceCalculator.TaxLine("IGST", new BigDecimal("18"), new BigDecimal("237.60"))),
                new BigDecimal("237.60"), BigDecimal.ZERO, new BigDecimal("0.40"), new BigDecimal("1558.00"));
        TariffZone zone = TariffZone.builder().code("PUNE").name("Pune").build();
        TariffZoneService.Resolution where = new TariffZoneService.Resolution(zone, null, null);
        TariffRateCard card = TariffRateCard.builder().code("PUNE_CASH").build();
        when(tariff.quoteOptions(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(List.of(
                new TariffQuoteService.Quote(card, where, where, UUID.randomUUID(), "SURFACE", "Surface", 4,
                        UUID.randomUUID(), "NONDOC", false, price)));

        ProviderResult result = adapter.getRates(request());

        assertThat(result.available()).isTrue();
        assertThat(result.rates()).singleElement().satisfies(r -> {
            assertThat(r.serviceCode()).isEqualTo("OCCPL_SURFACE");
            assertThat(r.etaMaxDays()).isEqualTo(4);
            assertThat(r.providerTotal()).isEqualByComparingTo("1557.60");
            assertThat(r.charges()).extracting(ChargeLine::component).containsExactly(
                    ChargeComponent.FREIGHT, ChargeComponent.FUEL_SURCHARGE, ChargeComponent.TAX);
        });
    }

    @Test
    @DisplayName("a delivery pincode outside the network is refused before pricing")
    void notServed() {
        served();
        when(pincodes.location("110001")).thenReturn(Optional.empty());

        assertThat(adapter.getRates(request()).available()).isFalse();
    }

    @Test
    @DisplayName("booking creates a pickup request for the customer, with delivery details for the booking clerk")
    void booksAsPickup() {
        served();
        when(pickups.createFromCustomerPortal(any())).thenAnswer(i -> {
            PickupService.SaveCommand c = i.getArgument(0);
            assertThat(c.remarks()).contains("Deliver to Ravi").contains("Quoted total INR 111.80");
            assertThat(c.remarks().length()).isLessThanOrEqualTo(500);
            return PickupRequest.builder().pickupNo("PU-0001").build();
        });

        BookingOutcome o = adapter.book(ShiprocketAdapterTest.booking());

        assertThat(o.result()).isEqualTo(BookingOutcome.Result.BOOKED);
        assertThat(o.providerOrderRef()).isEqualTo("PU-0001");
        assertThat(o.awbNumber()).isNull();
    }

    @Test
    @DisplayName("a return pickup tells the pickup team to collect from the buyer and bring it back")
    void returnPickupRemarks() {
        var b = booking();
        var ret = new com.courier.modules.customerportal.rates.domain.BookingRequest(b.companyId(), b.customerId(), b.reference(),
                b.serviceCode(), b.rate(), b.pickup(), b.delivery(), b.contents(), b.customerTotal(), b.charges(), b.chargeableWeightKg(),
                null, UUID.randomUUID());
        assertThat(OccplNetworkAdapter.remarks(ret)).startsWith("RETURN PICKUP: collect from the buyer and bring back to the customer.");
        assertThat(OccplNetworkAdapter.remarks(b)).doesNotContain("RETURN PICKUP");
    }

    @Test
    @DisplayName("a refused pickup is a definite failure (nothing was created)")
    void pickupRefused() {
        served();
        when(pickups.createFromCustomerPortal(any())).thenThrow(new BusinessRuleException("A valid contact mobile is required."));

        assertThat(adapter.book(ShiprocketAdapterTest.booking()).result()).isEqualTo(BookingOutcome.Result.REJECTED);
    }

    @Test
    @DisplayName("tracking: pickup steps, then the branch-booked shipment's history, newest first")
    void tracksPickupThenShipment() {
        UUID company = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-10-20T04:00:00Z");
        PickupRequest p = mock(PickupRequest.class);
        when(p.getPickupNo()).thenReturn("PU-7");
        when(p.getStatus()).thenReturn(PickupStatus.BOOKED);
        when(p.getCreatedAt()).thenReturn(t0);
        when(p.getPickedUpAt()).thenReturn(t0.plusSeconds(3600));
        when(p.getShipmentId()).thenReturn(shipmentId);
        when(pickups.findByNumberForCustomer("PU-7", customer)).thenReturn(Optional.of(p));
        when(shipments.timelineOfPickupShipment(company, shipmentId)).thenReturn(Optional.of(new ShipmentPortalQueries.PortalTimeline(
                new ShipmentPortalQueries.PortalShipment(shipmentId, "SH-1", "TRK-1", LocalDate.now(), ShipmentStatus.DISPATCHED,
                        "Ravi", 1, BigDecimal.ONE, null, t0),
                List.of(new ShipmentPortalQueries.PortalStep(ShipmentStatus.BOOKED, t0.plusSeconds(7200), "Pune Branch"),
                        new ShipmentPortalQueries.PortalStep(ShipmentStatus.DISPATCHED, t0.plusSeconds(9000), "Pune Branch")))));

        TrackingResult t = adapter.track(new ShipmentRef(company, customer, "b-1", "PU-7", null, null));

        assertThat(t.available()).isTrue();
        assertThat(t.trackingNumber()).isEqualTo("TRK-1");
        assertThat(t.status()).isEqualTo("Dispatched");
        assertThat(t.currentLocation()).isEqualTo("Pune Branch");
        assertThat(t.events()).extracting(TrackingEvent::status)
                .containsExactly("Dispatched", "Booked", "Picked up", "Pickup requested");
    }

    @Test
    @DisplayName("cancelBooking: cancels the issued shipment, then the pickup, while the parcel is not collected")
    void cancelsShipmentThenPickup() {
        UUID company = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        PickupRequest p = mock(PickupRequest.class);
        when(p.getShipmentId()).thenReturn(shipmentId);
        when(pickups.findByNumberForCustomer("PU-9", customer)).thenReturn(Optional.of(p));
        when(pickups.isCancellableForCustomer("PU-9", customer)).thenReturn(true);

        var out = adapter.cancelBooking(company, customer, "PU-9", "wrong address");

        assertThat(out.result()).isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.CANCELLED);
        var order = org.mockito.Mockito.inOrder(shipmentService, pickups);
        order.verify(shipmentService).cancelFromPortal(shipmentId, "wrong address");
        order.verify(pickups).cancelForCustomer("PU-9", customer, "wrong address");
        assertThat(adapter.canCancelBooking(company, customer, "PU-9")).isTrue();
    }

    @Test
    @DisplayName("pickupSchedule and reschedulePickup read and change the pickup's preferred date and slot")
    void readsAndChangesThePickupSchedule() {
        UUID company = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        LocalDate day = LocalDate.now().plusDays(1);
        PickupRequest p = mock(PickupRequest.class);
        when(p.getPreferredDate()).thenReturn(day);
        when(p.getPreferredSlot()).thenReturn("Evening (4-8)");
        when(pickups.findByNumberForCustomer("PU-3", customer)).thenReturn(Optional.of(p));

        var schedule = adapter.pickupSchedule(company, customer, "PU-3");
        assertThat(schedule.date()).isEqualTo(day);
        assertThat(schedule.slot()).isEqualTo("Evening (4-8)");

        var out = adapter.reschedulePickup(company, customer, "PU-3", day.plusDays(1), null);
        assertThat(out.result()).isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.CANCELLED);
        org.mockito.Mockito.verify(pickups).rescheduleForCustomer("PU-3", customer, day.plusDays(1), null);

        assertThat(adapter.reschedulePickup(company, customer, "PU-404", day, null).result())
                .isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.NOT_FOUND);
        assertThat(adapter.pickupSchedule(company, customer, null).date()).isNull();
    }

    @Test
    @DisplayName("chooseDelivery passes the customer's choice to the shipment; an unknown choice or a refusal comes back as a reason")
    void passesTheDeliveryChoice() {
        UUID company = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        PickupRequest p = mock(PickupRequest.class);
        when(p.getShipmentId()).thenReturn(shipmentId);
        when(pickups.findByNumberForCustomer("PU-5", customer)).thenReturn(Optional.of(p));
        LocalDate day = LocalDate.now().plusDays(1);

        assertThat(adapter.chooseDelivery(company, customer, "PU-5", "reattempt", day, null, null).result())
                .isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.CANCELLED);
        org.mockito.Mockito.verify(shipmentService).recordDeliveryChoice(shipmentId,
                com.courier.modules.shipment.domain.DeliveryChoice.REATTEMPT, day, null, null);

        assertThat(adapter.chooseDelivery(company, customer, "PU-5", "teleport", null, null, null).result())
                .isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.FAILED);
        when(shipmentService.recordDeliveryChoice(any(), any(), any(), any(), any()))
                .thenThrow(new com.courier.shared.exception.BusinessRuleException("There is no failed delivery waiting"));
        var refused = adapter.chooseDelivery(company, customer, "PU-5", "RETURN", null, null, null);
        assertThat(refused.result()).isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.FAILED);
        assertThat(refused.message()).contains("no failed delivery");
    }

    @Test
    @DisplayName("cancelBooking: a collected parcel is refused before anything is cancelled")
    void refusesACollectedParcel() {
        UUID customer = UUID.randomUUID();
        PickupRequest p = mock(PickupRequest.class);
        when(pickups.findByNumberForCustomer("PU-9", customer)).thenReturn(Optional.of(p));
        when(pickups.isCancellableForCustomer("PU-9", customer)).thenReturn(false);

        var out = adapter.cancelBooking(UUID.randomUUID(), customer, "PU-9", "x");

        assertThat(out.result()).isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.FAILED);
        assertThat(out.message()).contains("already been collected");
        org.mockito.Mockito.verify(shipmentService, org.mockito.Mockito.never()).cancelFromPortal(any(), any());
    }

    @Test
    @DisplayName("tracking a shipment the hub handed to a courier follows that courier too, and the customer keeps the OCCPL number")
    void tracksAForwardedShipmentThroughItsCourier() {
        UUID company = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-11-04T04:00:00Z");
        PickupRequest p = mock(PickupRequest.class);
        when(p.getPickupNo()).thenReturn("PU-8");
        when(p.getStatus()).thenReturn(PickupStatus.BOOKED);
        when(p.getCreatedAt()).thenReturn(t0);
        when(p.getShipmentId()).thenReturn(shipmentId);
        when(pickups.findByNumberForCustomer("PU-8", customer)).thenReturn(Optional.of(p));
        when(shipments.timelineOfPickupShipment(company, shipmentId)).thenReturn(Optional.of(new ShipmentPortalQueries.PortalTimeline(
                new ShipmentPortalQueries.PortalShipment(shipmentId, "SH-2", "TRK-2", LocalDate.now(), ShipmentStatus.FORWARDED,
                        "Ravi", 1, BigDecimal.ONE, null, t0),
                List.of(new ShipmentPortalQueries.PortalStep(ShipmentStatus.BOOKED, t0.plusSeconds(60), null),
                        new ShipmentPortalQueries.PortalStep(ShipmentStatus.FORWARDED, t0.plusSeconds(7200), "Pune Hub")),
                new ShipmentPortalQueries.Forwarded("DELHIVERY", "ORD-1", "DL123", null, t0.plusSeconds(7200)))));
        var delhivery = mock(com.courier.modules.customerportal.rates.domain.CourierProviderAdapter.class);
        when(delhivery.code()).thenReturn("DELHIVERY");
        when(delhivery.displayName()).thenReturn("Delhivery");
        when(delhivery.track(any(ShipmentRef.class))).thenReturn(TrackingResult.of("In Transit", "DL123", "Nagpur Hub",
                List.of(new TrackingEvent(t0.plusSeconds(20000), "In Transit", "Nagpur Hub", "Reached hub"))));
        when(allAdapters.getObject()).thenReturn(List.of(delhivery));

        TrackingResult t = adapter.track(new ShipmentRef(company, customer, "b-2", "PU-8", null, null));

        assertThat(t.available()).isTrue();
        assertThat(t.trackingNumber()).isEqualTo("TRK-2");
        assertThat(t.status()).isEqualTo("In Transit");
        assertThat(t.currentLocation()).isEqualTo("Nagpur Hub");
        assertThat(t.events()).extracting(TrackingEvent::status).contains("In Transit", "Handed to Delhivery", "Forwarded");
        org.mockito.ArgumentCaptor<ShipmentRef> asked = org.mockito.ArgumentCaptor.forClass(ShipmentRef.class);
        org.mockito.Mockito.verify(delhivery).track(asked.capture());
        assertThat(asked.getValue().awbNumber()).isEqualTo("DL123");
        assertThat(asked.getValue().providerOrderRef()).isEqualTo("ORD-1");
    }

    @Test
    @DisplayName("tracking a forwarded shipment still answers when the courier cannot be reached: it says it was handed over")
    void forwardedShipmentWhenTheCourierIsSilent() {
        UUID company = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-11-04T04:00:00Z");
        PickupRequest p = mock(PickupRequest.class);
        when(p.getPickupNo()).thenReturn("PU-9");
        when(p.getStatus()).thenReturn(PickupStatus.BOOKED);
        when(p.getCreatedAt()).thenReturn(t0);
        when(p.getShipmentId()).thenReturn(shipmentId);
        when(pickups.findByNumberForCustomer("PU-9", customer)).thenReturn(Optional.of(p));
        when(shipments.timelineOfPickupShipment(company, shipmentId)).thenReturn(Optional.of(new ShipmentPortalQueries.PortalTimeline(
                new ShipmentPortalQueries.PortalShipment(shipmentId, "SH-3", "TRK-3", LocalDate.now(), ShipmentStatus.FORWARDED,
                        "Ravi", 1, BigDecimal.ONE, null, t0),
                List.of(new ShipmentPortalQueries.PortalStep(ShipmentStatus.FORWARDED, t0.plusSeconds(7200), "Pune Hub")),
                new ShipmentPortalQueries.Forwarded("DELHIVERY", "ORD-2", "DL9", null, t0.plusSeconds(7200)))));
        var delhivery = mock(com.courier.modules.customerportal.rates.domain.CourierProviderAdapter.class);
        when(delhivery.code()).thenReturn("DELHIVERY");
        when(delhivery.displayName()).thenReturn("Delhivery");
        when(delhivery.track(any(ShipmentRef.class))).thenReturn(TrackingResult.unavailable("down"));
        when(allAdapters.getObject()).thenReturn(List.of(delhivery));

        TrackingResult t = adapter.track(new ShipmentRef(company, customer, "b-3", "PU-9", null, null));

        assertThat(t.available()).isTrue();
        assertThat(t.status()).isEqualTo("Handed to Delhivery");
        assertThat(t.trackingNumber()).isEqualTo("TRK-3");
    }

    @Test
    @DisplayName("tracking: another customer's pickup number finds nothing")
    void foreignPickup() {
        when(pickups.findByNumberForCustomer(any(), any())).thenReturn(Optional.empty());

        TrackingResult t = adapter.track(new ShipmentRef(UUID.randomUUID(), UUID.randomUUID(), "b-1", "PU-7", null, null));

        assertThat(t.available()).isFalse();
    }

    private com.courier.modules.customerportal.rates.domain.BookingRequest booking() {
        var party = new com.courier.modules.customerportal.rates.domain.BookingRequest.Party("Acme", "9800000001", "12 MG Road", "411016", "Pune", "MH");
        var to = new com.courier.modules.customerportal.rates.domain.BookingRequest.Party("Ravi", "9800000002", "5 Park St", "110001", "Delhi", "DL");
        return new com.courier.modules.customerportal.rates.domain.BookingRequest(UUID.randomUUID(), UUID.randomUUID(), "ref-1", "OCCPL_BUSINESS_RATE",
                request(), party, to, "Garments", new BigDecimal("295"), java.util.List.of(
                new com.courier.modules.customerportal.rates.domain.ChargeLine("f", "Freight", com.courier.modules.customerportal.rates.domain.ChargeComponent.FREIGHT, new BigDecimal("250")),
                new com.courier.modules.customerportal.rates.domain.ChargeLine("t", "GST", com.courier.modules.customerportal.rates.domain.ChargeComponent.TAX, new BigDecimal("45"))), new BigDecimal("2"));
    }

    @Test
    @DisplayName("booking issues the shipment and returns its tracking number at once, when the pickup pincode has a branch")
    void bookingIssuesTheShipment() {
        UUID pincodeId = UUID.randomUUID();
        when(pincodes.location("411016")).thenReturn(Optional.of(new PincodeLocation(pincodeId, null, null, DISTRICT, true, true, false)));
        PickupRequest pickup = PickupRequest.builder().pickupNo("PU-1").build();
        pickup.setId(UUID.randomUUID());
        when(pickups.createFromCustomerPortal(any())).thenReturn(pickup);
        var branch = com.courier.modules.company.domain.Branch.builder().branchCode("PUN").branchName("Pune HQ")
                .branchType(com.courier.modules.company.domain.BranchType.BRANCH).build();
        branch.setId(UUID.randomUUID());
        when(branchMappings.findBranchForPincode(pincodeId)).thenReturn(Optional.of(branch));
        var shipment = com.courier.modules.shipment.domain.Shipment.builder().shipmentNumber("PUN-000001").trackingNumber("261000000042").build();
        shipment.setId(UUID.randomUUID());
        when(shipmentService.createFromPortalBooking(any())).thenReturn(shipment);

        var outcome = adapter.book(booking());

        assertThat(outcome.result()).isEqualTo(com.courier.modules.customerportal.rates.domain.BookingOutcome.Result.BOOKED);
        assertThat(outcome.awbNumber()).isEqualTo("261000000042");
        var cmd = org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.application.command.PortalShipmentCommand.class);
        org.mockito.Mockito.verify(shipmentService).createFromPortalBooking(cmd.capture());
        assertThat(cmd.getValue().chargeableWeight()).isEqualByComparingTo("2");
        assertThat(cmd.getValue().freight()).isEqualByComparingTo("250");
        assertThat(cmd.getValue().gst()).isEqualByComparingTo("45");
        assertThat(cmd.getValue().total()).isEqualByComparingTo("295");
        assertThat(cmd.getValue().paymentKind()).isEqualTo("PAID");
        org.mockito.Mockito.verify(pickups).attachPortalShipment(pickup.getId(), shipment.getId(), "PUN-000001", branch.getId());
    }

    @Test
    @DisplayName("booking falls back to a plain pickup when no branch is mapped to the pickup pincode")
    void bookingWithoutBranchIsPickupOnly() {
        UUID pincodeId = UUID.randomUUID();
        when(pincodes.location("411016")).thenReturn(Optional.of(new PincodeLocation(pincodeId, null, null, DISTRICT, true, true, false)));
        PickupRequest pickup = PickupRequest.builder().pickupNo("PU-2").build();
        pickup.setId(UUID.randomUUID());
        when(pickups.createFromCustomerPortal(any())).thenReturn(pickup);
        when(branchMappings.findBranchForPincode(pincodeId)).thenReturn(Optional.empty());

        var outcome = adapter.book(booking());

        assertThat(outcome.result()).isEqualTo(com.courier.modules.customerportal.rates.domain.BookingOutcome.Result.BOOKED);
        assertThat(outcome.awbNumber()).isNull();
        org.mockito.Mockito.verify(shipmentService, org.mockito.Mockito.never()).createFromPortalBooking(any());
    }

    @Test
    @DisplayName("booking still succeeds as a plain pickup if issuing the shipment fails")
    void shipmentFailureKeepsThePickup() {
        UUID pincodeId = UUID.randomUUID();
        when(pincodes.location("411016")).thenReturn(Optional.of(new PincodeLocation(pincodeId, null, null, DISTRICT, true, true, false)));
        PickupRequest pickup = PickupRequest.builder().pickupNo("PU-3").build();
        pickup.setId(UUID.randomUUID());
        when(pickups.createFromCustomerPortal(any())).thenReturn(pickup);
        var branch = com.courier.modules.company.domain.Branch.builder().branchCode("PUN").branchName("Pune HQ")
                .branchType(com.courier.modules.company.domain.BranchType.BRANCH).build();
        branch.setId(UUID.randomUUID());
        when(branchMappings.findBranchForPincode(pincodeId)).thenReturn(Optional.of(branch));
        when(shipmentService.createFromPortalBooking(any())).thenThrow(new IllegalStateException("no payment mode"));

        var outcome = adapter.book(booking());

        assertThat(outcome.result()).isEqualTo(com.courier.modules.customerportal.rates.domain.BookingOutcome.Result.BOOKED);
        assertThat(outcome.awbNumber()).isNull();
    }

    @Test
    @DisplayName("labels are never downloaded from OCCPL; the portal prints them")
    void noLabels() {
        assertThat(adapter.label(new ShipmentRef(null, null, null, "PU-7", null, null)).available()).isFalse();
        assertThat(adapter.labelHosts()).isEmpty();
    }
}
