package com.courier.modules.forwarding.application;

import com.courier.modules.company.application.UserService;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.company.domain.User;
import com.courier.modules.customerportal.rates.application.RateComparisonService;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.forwarding.application.ForwardingService.ForwardOptions;
import com.courier.modules.forwarding.application.ForwardingService.ForwardResult;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.domain.PaymentMode;
import com.courier.modules.master.domain.PaymentModeRepository;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.modules.shipment.application.command.ForwardRecord;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentItemRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ForbiddenException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForwardingServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID HUB = UUID.randomUUID();
    private static final UUID OTHER_HUB = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    @Mock private ShipmentRepository shipments;
    @Mock private ShipmentItemRepository items;
    @Mock private ShipmentService shipmentService;
    @Mock private BranchRepository branches;
    @Mock private PaymentModeRepository paymentModes;
    @Mock private UserService users;
    @Mock private PincodeService pincodes;
    @Mock private RateComparisonService comparison;
    private final CourierProviderAdapter delhivery = mock(CourierProviderAdapter.class);

    private ForwardingService service;
    private Shipment shipment;

    @BeforeEach
    void setUp() {
        CompanyContext.setCompanyId(COMPANY);
        when(delhivery.code()).thenReturn("DELHIVERY");
        when(delhivery.displayName()).thenReturn("Delhivery");
        when(delhivery.external()).thenReturn(true);
        service = new ForwardingService(shipments, items, shipmentService, branches, paymentModes, users, pincodes, comparison, List.of(delhivery));

        shipment = Shipment.builder().shipmentNumber("TEST_BR-000009").trackingNumber("26110000009").bookingBranchId(HUB)
                .currentLocationId(HUB).status(ShipmentStatus.BOOKED).actualWeight(new BigDecimal("3")).declaredValue(new BigDecimal("2000"))
                .numberOfPackages(1).deliveryPincode("560001").receiverName("Ravi").receiverContact("9800000002").receiverAddress("5 Park St")
                .senderName("Acme").build();
        shipment.setId(UUID.randomUUID());
        shipment.setCompanyId(COMPANY);
        when(shipments.findByIdWithinCompany(shipment.getId(), COMPANY)).thenReturn(Optional.of(shipment));
        Branch hub = Branch.builder().branchCode("TEST_BR").branchName("Pune Hub").postalCode("411016").mobile("9999900001")
                .addressLine1("Model Colony").city("Pune").state("Maharashtra (MAHARASHTRA)").build();
        hub.setId(HUB);
        when(branches.findByIdWithinCompany(HUB, COMPANY)).thenReturn(Optional.of(hub));
        when(pincodes.geography(anyString())).thenReturn(Optional.empty());
        as(ADMIN, Roles.COMPANY_ADMIN);
        when(comparison.askExternalForForwarding(any())).thenReturn(List.of(
                ProviderResult.available("DELHIVERY", "Delhivery", List.of(
                        rate("EXPRESS", "Delhivery Express", "300"), rate("SURFACE", "Delhivery Surface", "180"))),
                ProviderResult.unavailable("SHIPROCKET", "Shiprocket", "Shiprocket is not connected yet.")));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static ProviderRate rate(String code, String name, String amount) {
        return new ProviderRate(code, name, "SURFACE", 3, 5, new BigDecimal("3"),
                List.of(new ChargeLine("FREIGHT", "Freight", ChargeComponent.FREIGHT, new BigDecimal(amount))), null, null);
    }

    private void as(UUID userId, String role) {
        AuthenticatedUser p = new AuthenticatedUser(userId, COMPANY, "u@test", Set.of(role), "jti");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    @Test
    @DisplayName("options lists the couriers' own prices cheapest first, with the unavailable ones and where the courier collects")
    void optionsAreSortedByCost() {
        ForwardOptions o = service.options(shipment.getId());

        assertThat(o.blocker()).isNull();
        assertThat(o.collectFrom()).isEqualTo("Pune Hub, 411016");
        assertThat(o.options()).extracting(x -> x.serviceCode()).containsExactly("SURFACE", "EXPRESS");
        assertThat(o.options().get(0).cost()).isEqualByComparingTo("180");
        assertThat(o.unavailable()).extracting(u -> u.providerCode()).containsExactly("SHIPROCKET");
        ArgumentCaptor<RateRequest> req = ArgumentCaptor.forClass(RateRequest.class);
        verify(comparison).askExternalForForwarding(req.capture());
        assertThat(req.getValue().pickupPincode()).isEqualTo("411016");
        assertThat(req.getValue().deliveryPincode()).isEqualTo("560001");
        assertThat(req.getValue().paymentMode()).isEqualTo(RateRequest.PaymentMode.PREPAID);
    }

    @Test
    @DisplayName("forward books the chosen service at the courier's fresh price and records its AWB, label and cost")
    void forwardBooksAndRecords() {
        when(delhivery.book(any(BookingRequest.class))).thenReturn(BookingOutcome.booked("ORD-1", "DL123", "https://lbl/1", "ok"));
        when(shipmentService.completeForward(eq(shipment.getId()), any())).thenReturn(shipment);

        ForwardResult r = service.forward(shipment.getId(), "DELHIVERY", "SURFACE");

        assertThat(r.outcome()).isEqualTo("FORWARDED");
        assertThat(r.awb()).isEqualTo("DL123");
        assertThat(r.cost()).isEqualByComparingTo("180");
        verify(shipmentService).beginForward(shipment.getId(), "DELHIVERY", "Delhivery Surface");
        ArgumentCaptor<BookingRequest> book = ArgumentCaptor.forClass(BookingRequest.class);
        verify(delhivery).book(book.capture());
        assertThat(book.getValue().reference()).isEqualTo("TEST_BR-000009");
        assertThat(book.getValue().pickup().pincode()).isEqualTo("411016");
        assertThat(book.getValue().pickup().state()).isEqualTo("Maharashtra");
        assertThat(book.getValue().delivery().pincode()).isEqualTo("560001");
        ArgumentCaptor<ForwardRecord> rec = ArgumentCaptor.forClass(ForwardRecord.class);
        verify(shipmentService).completeForward(eq(shipment.getId()), rec.capture());
        assertThat(rec.getValue().labelUrl()).isEqualTo("https://lbl/1");
        assertThat(rec.getValue().cost()).isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("a courier that refuses leaves the shipment where it was and the reason comes back")
    void refusalIsClearedAndExplained() {
        when(delhivery.book(any(BookingRequest.class))).thenReturn(BookingOutcome.rejected("BAD_PIN", "Pincode not serviceable",
                "Unit Tax is Required for all Products."));

        assertThatThrownBy(() -> service.forward(shipment.getId(), "DELHIVERY", "SURFACE"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Pincode not serviceable")
                // staff see the courier's own words
                .hasMessageContaining("Delhivery says: Unit Tax is Required for all Products.");

        verify(shipmentService).abandonForward(shipment.getId(), false, "Pincode not serviceable");
        verify(shipmentService, never()).completeForward(any(), any());
    }

    @Test
    @DisplayName("when we cannot tell whether the courier accepted, it is flagged for checking and not retried")
    void unknownOutcomeIsFlagged() {
        when(delhivery.book(any(BookingRequest.class))).thenThrow(new IllegalStateException("timeout"));

        ForwardResult r = service.forward(shipment.getId(), "DELHIVERY", "SURFACE");

        assertThat(r.outcome()).isEqualTo("UNCONFIRMED");
        verify(shipmentService).abandonForward(eq(shipment.getId()), eq(true), anyString());
        verify(shipmentService, never()).completeForward(any(), any());
    }

    @Test
    @DisplayName("a shipment already forwarded, being forwarded, or no longer at the hub cannot be forwarded")
    void blockers() {
        shipment.setForwardState("BOOKED");
        shipment.setForwardProvider("DELHIVERY");
        shipment.setForwardAwb("DL1");
        assertThat(service.options(shipment.getId()).blocker()).contains("already forwarded to DELHIVERY").contains("DL1");
        assertThatThrownBy(() -> service.forward(shipment.getId(), "DELHIVERY", "SURFACE")).hasMessageContaining("already forwarded");

        shipment.setForwardState("UNKNOWN");
        assertThat(service.options(shipment.getId()).blocker()).contains("could not be confirmed");

        shipment.setForwardState(null);
        shipment.setForwardProvider(null);
        shipment.setStatus(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(service.options(shipment.getId()).blocker()).contains("only while it is at your hub");
        verify(delhivery, never()).book(any());
    }

    @Test
    @DisplayName("a To Pay shipment cannot be forwarded: a courier cannot collect OCCPL's freight")
    void toPayIsBlocked() {
        UUID toPayId = UUID.randomUUID();
        PaymentMode toPay = new PaymentMode();
        toPay.setCollectAtDelivery(true);
        when(paymentModes.findByIdWithinCompany(toPayId, COMPANY)).thenReturn(Optional.of(toPay));
        shipment.setPaymentModeId(toPayId);

        assertThat(service.options(shipment.getId()).blocker()).contains("To Pay");
    }

    @Test
    @DisplayName("only the admin or the manager of the hub holding the shipment may forward it")
    void whoMayForward() {
        User mine = mock(User.class);
        when(mine.getBranchId()).thenReturn(HUB);
        User theirs = mock(User.class);
        when(theirs.getBranchId()).thenReturn(OTHER_HUB);
        UUID other = UUID.randomUUID();
        when(users.getById(MANAGER)).thenReturn(mine);
        when(users.getById(other)).thenReturn(theirs);

        as(MANAGER, Roles.BRANCH_MANAGER);
        assertThat(service.options(shipment.getId()).blocker()).isNull();

        as(other, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.options(shipment.getId())).isInstanceOf(ForbiddenException.class);

        as(UUID.randomUUID(), Roles.CUSTOMER);
        assertThatThrownBy(() -> service.forward(shipment.getId(), "DELHIVERY", "SURFACE")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a hub with no pincode is told to set it, since the courier collects from there")
    void hubNeedsAPincode() {
        Branch noPin = Branch.builder().branchCode("TEST_BR").branchName("Pune Hub").build();
        noPin.setId(HUB);
        when(branches.findByIdWithinCompany(HUB, COMPANY)).thenReturn(Optional.of(noPin));

        assertThat(service.options(shipment.getId()).blocker()).contains("Set the pincode of Pune Hub");
    }
}
