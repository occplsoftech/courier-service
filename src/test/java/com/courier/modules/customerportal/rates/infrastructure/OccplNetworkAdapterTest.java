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

    @Test
    @DisplayName("prices from the customer's own rate card, one line per charge, no zero lines")
    void pricesFromRateCard() {
        served();
        when(zoneRates.calculate(any())).thenReturn(new ZoneRateService.Quote(UUID.randomUUID(), "North",
                new BigDecimal("12"), new BigDecimal("12"), new BigDecimal("40"), new BigDecimal("480.00"),
                new BigDecimal("50"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("48.00"),
                new BigDecimal("10.00"), new BigDecimal("588.00")));

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
        when(zoneRates.calculate(any())).thenThrow(new BusinessRuleException("No active pricing zone"));

        ProviderResult result = adapter.getRates(request());

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("No OCCPL network rate");
    }

    @Test
    @DisplayName("no contract rate: the company's zone tariff, one option per service type, with its charges and GST")
    void zoneTariffFallback() {
        served();
        when(zoneRates.calculate(any())).thenThrow(new BusinessRuleException("No active pricing zone"));
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
        when(shipments.timeline(company, customer, shipmentId)).thenReturn(Optional.of(new ShipmentPortalQueries.PortalTimeline(
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
    @DisplayName("tracking: another customer's pickup number finds nothing")
    void foreignPickup() {
        when(pickups.findByNumberForCustomer(any(), any())).thenReturn(Optional.empty());

        TrackingResult t = adapter.track(new ShipmentRef(UUID.randomUUID(), UUID.randomUUID(), "b-1", "PU-7", null, null));

        assertThat(t.available()).isFalse();
    }

    @Test
    @DisplayName("labels are printed at the branch, never downloaded")
    void noLabels() {
        assertThat(adapter.label(new ShipmentRef(null, null, null, "PU-7", null, null)).available()).isFalse();
        assertThat(adapter.labelHosts()).isEmpty();
    }
}
