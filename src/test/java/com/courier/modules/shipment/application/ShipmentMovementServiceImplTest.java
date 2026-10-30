package com.courier.modules.shipment.application;

import com.courier.modules.company.application.BranchService;
import com.courier.modules.company.application.UserService;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.PaymentModeService;
import com.courier.modules.master.application.RouteService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.PaymentMode;
import com.courier.modules.pricing.application.PricingEngine;
import com.courier.modules.pricing.application.PricingProperties;
import com.courier.modules.rate.application.RateService;
import com.courier.modules.shipment.application.event.ShipmentEvent;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import com.courier.modules.shipment.domain.BranchShipmentSequenceRepository;
import com.courier.modules.shipment.domain.ReleasedShipmentNumberRepository;
import com.courier.modules.shipment.domain.CompanyShipmentSequenceRepository;
import com.courier.modules.shipment.domain.DeliveryAssignment;
import com.courier.modules.shipment.domain.DeliveryAssignmentRepository;
import com.courier.modules.shipment.domain.DeliveryAssignmentStatus;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentAsset;
import com.courier.modules.shipment.domain.ShipmentAssetRepository;
import com.courier.modules.shipment.domain.ShipmentAssetType;
import com.courier.modules.shipment.domain.ShipmentCharge;
import com.courier.modules.shipment.domain.ShipmentChargeRepository;
import com.courier.modules.shipment.domain.ShipmentDocumentRepository;
import com.courier.modules.shipment.domain.ShipmentItem;
import com.courier.modules.shipment.domain.ShipmentItemRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Shipment Movement's own four steps (the shipment-side half of Dispatch, In
 * Scan, Out For Delivery, Deliver) — booking/pricing collaborators are unused here and
 * left un-stubbed (lenient), the same style {@code ShipmentServiceImplTest} uses for its
 * own cancel/update-only tests. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShipmentMovementServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID BOOKING_BRANCH = UUID.randomUUID();
    private static final UUID DELIVERY_BRANCH = UUID.randomUUID();
    private static final UUID CROSSING_BRANCH = UUID.randomUUID();
    private static final UUID SECOND_CROSSING_BRANCH = UUID.randomUUID();
    private static final UUID MANIFEST = UUID.randomUUID();

    @Mock private ShipmentRepository shipmentRepository;
    @Mock private ShipmentItemRepository itemRepository;
    @Mock private ShipmentChargeRepository chargeRepository;
    @Mock private ShipmentStatusHistoryRepository historyRepository;
    @Mock private ShipmentDocumentRepository documentRepository;
    @Mock private DeliveryAssignmentRepository deliveryAssignmentRepository;
    @Mock private BranchShipmentSequenceRepository branchShipmentSequenceRepository;
    @Mock private ReleasedShipmentNumberRepository releasedShipmentNumberRepository;
    @Mock private CompanyShipmentSequenceRepository companyShipmentSequenceRepository;
    @Mock private com.courier.modules.shipment.domain.CompanyDrsSequenceRepository companyDrsSequenceRepository;
    @Mock private ServiceTypeService serviceTypeService;
    @Mock private PackageTypeService packageTypeService;
    @Mock private PaymentModeService paymentModeService;
    @Mock private RateService rateService;
    @Mock private RouteService routeService;
    @Mock private PricingEngine pricingEngine;
    @Mock private WalletService walletService;
    @Mock private UserService userService;
    @Mock private BranchService branchService;
    @Mock private com.courier.modules.customer.application.CustomerService customerService;
    @Mock private com.courier.modules.crossing.application.CrossingService crossingService;
    @Mock private com.courier.modules.support.application.TicketService ticketService;
    @Mock private com.courier.modules.support.application.TicketCategoryService ticketCategoryService;
    @Mock private com.courier.modules.ewaybill.application.EwayBillService ewayBillService;
    @Mock private com.courier.modules.districtfreight.application.FreightCalculationService freightCalculationService;
    @Mock private com.courier.modules.pricing.application.calculator.ApplicableChargesCalculator applicableChargesCalculator;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private FileStoragePort fileStoragePort;
    @Mock private ShipmentAssetRepository shipmentAssetRepository;
    @Mock private com.courier.modules.shipment.domain.DeliveryDispatchOtpRepository deliveryDispatchOtpRepository;
    @Mock private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock private com.courier.modules.company.application.CompanySettingsService companySettingsService;
    @Mock private com.courier.modules.communication.application.CommunicationSettingService communicationSettingService;
    @Mock private com.courier.modules.communication.application.provider.SmsProvider smsProvider;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private ShipmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShipmentServiceImpl(shipmentRepository, itemRepository, chargeRepository,
                historyRepository, documentRepository, deliveryAssignmentRepository,
                branchShipmentSequenceRepository, releasedShipmentNumberRepository, companyShipmentSequenceRepository, companyDrsSequenceRepository,
                serviceTypeService, packageTypeService, paymentModeService,
                rateService, routeService, pricingEngine, new PricingProperties(), walletService,
                userService, branchService, org.mockito.Mockito.mock(com.courier.modules.shipment.domain.BranchLookupPort.class), org.mockito.Mockito.mock(com.courier.modules.master.domain.PaymentModeRepository.class), org.mockito.Mockito.mock(com.courier.modules.master.domain.ServiceTypeRepository.class), org.mockito.Mockito.mock(com.courier.modules.master.domain.PackageTypeRepository.class), customerService, crossingService, ticketService, ticketCategoryService,
                ewayBillService, freightCalculationService,
                applicableChargesCalculator, auditService, eventPublisher, fileStoragePort,
                shipmentAssetRepository, deliveryDispatchOtpRepository, passwordEncoder, companySettingsService,
                communicationSettingService, smsProvider, objectMapper);
        CompanyContext.setCompanyId(COMPANY);
        AuthenticatedUser principal = new AuthenticatedUser(
                CALLER, COMPANY, "ops@test.com", Set.of(Roles.COMPANY_ADMIN), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
        // Default: collect-at-booking, so scanOneIn's finalDestination branch (which now
        // unconditionally reads the payment mode) doesn't publish ToPayReceivedAtDeliveryBranch
        // for tests that don't care about payment mode at all. Overridden per test below.
        when(paymentModeService.getById(any())).thenReturn(paymentMode(true));
        // Collect-at-booking now also fetches the charge and checks instantCommission at
        // scanOneIn's finalDestination — harmless defaults (no commission, since
        // instantCommission is off) for tests that don't care about commission at all.
        // Overridden per test below.
        when(chargeRepository.findByShipmentIdWithinCompany(any(), any()))
                .thenReturn(Optional.of(ShipmentCharge.builder().build()));
        when(branchService.getById(any())).thenReturn(Branch.builder().branchCode("PUNE").build());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------- attachToManifest

    @Test
    @DisplayName("attachToManifest refuses a shipment that is not BOOKED")
    void attachRefusesNotBooked() {
        Shipment shipment = shipment(ShipmentStatus.MANIFEST_CREATED);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> service.attachToManifest(
                shipment.getId(), MANIFEST, BOOKING_BRANCH, DELIVERY_BRANCH, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BOOKED");
    }

    @Test
    @DisplayName("attachToManifest refuses a shipment travelling a different lane")
    void attachRefusesDifferentLane() {
        Shipment shipment = shipment(ShipmentStatus.BOOKED);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> service.attachToManifest(
                shipment.getId(), MANIFEST, UUID.randomUUID(), DELIVERY_BRANCH, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different lane");
    }

    @Test
    @DisplayName("attachToManifest moves a BOOKED shipment on the right lane to MANIFEST_CREATED")
    void attachHappyPath() {
        Shipment shipment = shipment(ShipmentStatus.BOOKED);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        Shipment result = service.attachToManifest(shipment.getId(), MANIFEST, BOOKING_BRANCH, DELIVERY_BRANCH, null);

        assertThat(result.getStatus()).isEqualTo(ShipmentStatus.MANIFEST_CREATED);
        assertThat(result.getManifestId()).isEqualTo(MANIFEST);
    }

    @Test
    @DisplayName("attachToManifest matches a freshly booked shipment by destination city "
            + "when Load Sheet hasn't assigned it a delivery branch yet")
    void attachMatchesByDestinationCityWhenUnassigned() {
        Shipment shipment = shipment(ShipmentStatus.BOOKED);
        shipment.setDeliveryBranchId(null);
        shipment.setNextLocationId(null);
        shipment.setToCity("Pune");
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        Shipment result = service.attachToManifest(shipment.getId(), MANIFEST, BOOKING_BRANCH, DELIVERY_BRANCH, "Pune");

        assertThat(result.getStatus()).isEqualTo(ShipmentStatus.MANIFEST_CREATED);
        assertThat(result.getDeliveryBranchId()).isEqualTo(DELIVERY_BRANCH);
        assertThat(result.getNextLocationId()).isEqualTo(DELIVERY_BRANCH);
    }

    @Test
    @DisplayName("hub transfer attaches an unassigned shipment with no destination city, "
            + "whatever its own city, and sets only the next stop")
    void attachHubTransferNeedsNoDestinationCity() {
        Shipment shipment = shipment(ShipmentStatus.BOOKED);
        shipment.setDeliveryBranchId(null);
        shipment.setNextLocationId(null);
        shipment.setToCity("Mumbai");
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        Shipment result = service.attachToManifest(
                shipment.getId(), MANIFEST, BOOKING_BRANCH, DELIVERY_BRANCH, null, true);

        assertThat(result.getStatus()).isEqualTo(ShipmentStatus.MANIFEST_CREATED);
        assertThat(result.getNextLocationId()).isEqualTo(DELIVERY_BRANCH);
        assertThat(result.getDeliveryBranchId()).isNull();
    }

    @Test
    @DisplayName("attachToManifest refuses an unassigned shipment going to a different city")
    void attachRefusesWrongDestinationCity() {
        Shipment shipment = shipment(ShipmentStatus.BOOKED);
        shipment.setDeliveryBranchId(null);
        shipment.setNextLocationId(null);
        shipment.setToCity("Mumbai");
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> service.attachToManifest(
                shipment.getId(), MANIFEST, BOOKING_BRANCH, DELIVERY_BRANCH, "Pune"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Pune");
    }

    @Test
    @DisplayName("attachToManifest sends an unassigned shipment straight to the chosen branch "
            + "when the manifest names no destination city")
    void attachWithNoDestinationCityAssignsChosenBranch() {
        Shipment shipment = shipment(ShipmentStatus.BOOKED);
        shipment.setDeliveryBranchId(null);
        shipment.setNextLocationId(null);
        shipment.setToCity("Mumbai");
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        Shipment result = service.attachToManifest(
                shipment.getId(), MANIFEST, BOOKING_BRANCH, DELIVERY_BRANCH, null);

        assertThat(result.getStatus()).isEqualTo(ShipmentStatus.MANIFEST_CREATED);
        assertThat(result.getDeliveryBranchId()).isEqualTo(DELIVERY_BRANCH);
        assertThat(result.getNextLocationId()).isEqualTo(DELIVERY_BRANCH);
    }

    @Test
    @DisplayName("attachToManifest accepts a READY_FOR_MANIFEST shipment for its second leg, "
            + "matched on current/next location rather than its fixed booking/delivery branch")
    void attachAcceptsSecondLegAfterCrossingHop() {
        Shipment shipment = shipment(ShipmentStatus.READY_FOR_MANIFEST);
        shipment.setCurrentLocationId(CROSSING_BRANCH);
        shipment.setNextLocationId(DELIVERY_BRANCH);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        Shipment result = service.attachToManifest(shipment.getId(), MANIFEST, CROSSING_BRANCH, DELIVERY_BRANCH, null);

        assertThat(result.getStatus()).isEqualTo(ShipmentStatus.MANIFEST_CREATED);
    }

    @Test
    @DisplayName("attachToManifest still refuses a second-leg shipment on the wrong lane")
    void attachRefusesSecondLegWrongLane() {
        Shipment shipment = shipment(ShipmentStatus.READY_FOR_MANIFEST);
        shipment.setCurrentLocationId(CROSSING_BRANCH);
        shipment.setNextLocationId(DELIVERY_BRANCH);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> service.attachToManifest(
                shipment.getId(), MANIFEST, BOOKING_BRANCH, DELIVERY_BRANCH, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different lane");
    }

    @Test
    @DisplayName("detachFromManifest reverts a shipment past its first crossing hop to "
            + "READY_FOR_MANIFEST, not BOOKED")
    void detachRevertsToReadyForManifestPastFirstHop() {
        Shipment shipment = shipment(ShipmentStatus.MANIFEST_CREATED);
        shipment.setCurrentLocationId(CROSSING_BRANCH);
        shipment.setNextLocationId(DELIVERY_BRANCH);
        shipment.setManifestId(MANIFEST);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        Shipment result = service.detachFromManifest(shipment.getId(), MANIFEST);

        assertThat(result.getStatus()).isEqualTo(ShipmentStatus.READY_FOR_MANIFEST);
        assertThat(result.getManifestId()).isNull();
    }

    @Test
    @DisplayName("detachFromManifest still reverts a first-leg shipment to BOOKED")
    void detachRevertsToBookedOnFirstLeg() {
        Shipment shipment = shipment(ShipmentStatus.MANIFEST_CREATED);
        shipment.setCurrentLocationId(BOOKING_BRANCH);
        shipment.setNextLocationId(DELIVERY_BRANCH);
        shipment.setManifestId(MANIFEST);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        Shipment result = service.detachFromManifest(shipment.getId(), MANIFEST);

        assertThat(result.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
    }

    // -------------------------------------------------------------------- inScan

    @Test
    @DisplayName("inScan refuses a shipment that is not DISPATCHED")
    void inScanRefusesWrongStatus() {
        Shipment shipment = shipment(ShipmentStatus.MANIFEST_CREATED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));

        var result = service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        assertThat(result.failureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("inScan refuses when the receiving branch does not match the shipment's delivery branch")
    void inScanRefusesWrongBranch() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));

        var result = service.inScan(UUID.randomUUID(), List.of(shipment.getTrackingNumber()), null, null, null, null);

        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DISPATCHED);
    }

    @Test
    @DisplayName("inScan moves a DISPATCHED shipment received at its own delivery branch to IN_SCAN")
    void inScanHappyPath() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));

        var result = service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.IN_SCAN);
    }

    @Test
    @DisplayName("inScan resolves a label's package id (AWB-002) to its shipment when within numberOfPackages")
    void inScanAcceptsPackageId() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        org.springframework.test.util.ReflectionTestUtils.setField(shipment, "numberOfPackages", 3);
        String awb = shipment.getTrackingNumber();
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, awb + "-002"))
                .thenReturn(Optional.empty());
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, awb))
                .thenReturn(Optional.of(shipment));

        var result = service.inScan(DELIVERY_BRANCH, List.of(awb + "-002"), null, null, null, null);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.IN_SCAN);
    }

    @Test
    @DisplayName("inScan rejects a package id beyond the shipment's numberOfPackages")
    void inScanRejectsOutOfRangePackageId() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        String awb = shipment.getTrackingNumber();
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, awb + "-002"))
                .thenReturn(Optional.empty());
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, awb))
                .thenReturn(Optional.of(shipment));

        var result = service.inScan(DELIVERY_BRANCH, List.of(awb + "-002"), null, null, null, null);

        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DISPATCHED);
    }

    @Test
    @DisplayName("inScan records the caller's remark on the status-history row and the photo as "
            + "an IN_SCAN ShipmentAsset when both are supplied")
    void inScanRecordsRemarkAndPhoto() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));

        var result = service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), null, null,
                "box damaged, seal broken", "https://x/in-scan.png");

        assertThat(result.successCount()).isEqualTo(1);
        var historyCaptor = org.mockito.ArgumentCaptor.forClass(com.courier.modules.shipment.domain.ShipmentStatusHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getRemarks()).isEqualTo("box damaged, seal broken");

        var assetCaptor = org.mockito.ArgumentCaptor.forClass(ShipmentAsset.class);
        verify(shipmentAssetRepository).save(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getAssetType()).isEqualTo(ShipmentAssetType.IN_SCAN);
        assertThat(assetCaptor.getValue().getKind()).isEqualTo("PHOTO");
        assertThat(assetCaptor.getValue().getAssetUrl()).isEqualTo("https://x/in-scan.png");
    }

    @Test
    @DisplayName("inScan at a crossing hub (not the final delivery branch) moves the shipment "
            + "to READY_FOR_MANIFEST, not IN_SCAN, and advances nextLocationId")
    void inScanAtCrossingHubAdvancesRoute() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        shipment.setCurrentLocationId(BOOKING_BRANCH);
        shipment.setNextLocationId(CROSSING_BRANCH);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));
        when(crossingService.arriveAt(shipment.getId(), CROSSING_BRANCH))
                .thenReturn(Optional.of(SECOND_CROSSING_BRANCH));

        var result = service.inScan(CROSSING_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY_FOR_MANIFEST);
        assertThat(shipment.getCurrentLocationId()).isEqualTo(CROSSING_BRANCH);
        assertThat(shipment.getNextLocationId()).isEqualTo(SECOND_CROSSING_BRANCH);
    }

    @Test
    @DisplayName("inScan auto-raises a shortage ticket when the operator names shipments that were "
            + "not physically received, and reports the ticket number back")
    void inScanRaisesShortageTicketForMissing() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));
        when(ticketCategoryService.listCategories()).thenReturn(List.of(
                com.courier.modules.support.domain.TicketCategory.builder().name("Shipment Issue").active(true).build()));
        com.courier.modules.support.domain.Ticket ticket =
                com.courier.modules.support.domain.Ticket.builder().ticketNumber("TKT-000042").build();
        when(ticketService.create(any())).thenReturn(ticket);

        var result = service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), "MFT-000001",
                List.of("AWB-MISSING-1", "AWB-MISSING-2"), null, null);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.shortageTicketNumber()).isEqualTo("TKT-000042");
        verify(ticketService).create(any());
    }

    @Test
    @DisplayName("inScan never touches the ticket service when nothing is reported missing")
    void inScanWithoutShortageRaisesNoTicket() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));

        var result = service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), null, List.of(), null, null);

        assertThat(result.shortageTicketNumber()).isNull();
        verify(ticketService, never()).create(any());
        assertThat(shipment.getManifestId()).isNull();
    }

    @Test
    @DisplayName("inScan at the last crossing hub routes nextLocationId straight to the "
            + "delivery branch once CrossingService reports no further hop")
    void inScanAtLastCrossingHopRoutesToDeliveryBranch() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        shipment.setCurrentLocationId(BOOKING_BRANCH);
        shipment.setNextLocationId(CROSSING_BRANCH);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));
        when(crossingService.arriveAt(shipment.getId(), CROSSING_BRANCH)).thenReturn(Optional.empty());

        service.inScan(CROSSING_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY_FOR_MANIFEST);
        assertThat(shipment.getNextLocationId()).isEqualTo(DELIVERY_BRANCH);
    }

    @Test
    @DisplayName("inScan at the shipment's own final delivery branch publishes a TO_PAY wallet-debit "
            + "event immediately, ahead of actual delivery")
    void inScanAtFinalDestinationPublishesToPayEvent() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(false));
        ShipmentCharge charge = ShipmentCharge.builder().netAmount(new BigDecimal("300.0000")).build();
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(charge));

        service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(ShipmentEvent.ToPayReceivedAtDeliveryBranch.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().deliveryBranchId()).isEqualTo(DELIVERY_BRANCH);
        assertThat(captor.getValue().shipmentNumber()).isEqualTo(shipment.getShipmentNumber());
        assertThat(captor.getValue().netAmount()).isEqualByComparingTo("300.0000");
    }

    @Test
    @DisplayName("inScan at the shipment's own final delivery branch never publishes the TO_PAY "
            + "event for COD — the consignee's amount, only real once actually collected at delivery")
    void inScanAtFinalDestinationSkipsToPayEventForCod() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(codPaymentMode());

        service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.ToPayReceivedAtDeliveryBranch.class));
    }

    @Test
    @DisplayName("inScan at a crossing hub never publishes the TO_PAY event even for a TO_PAY "
            + "shipment — only its own final delivery branch's in-scan does")
    void inScanAtCrossingHubSkipsToPayEvent() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        shipment.setCurrentLocationId(BOOKING_BRANCH);
        shipment.setNextLocationId(CROSSING_BRANCH);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));
        when(crossingService.arriveAt(shipment.getId(), CROSSING_BRANCH))
                .thenReturn(Optional.of(SECOND_CROSSING_BRANCH));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(false));

        service.inScan(CROSSING_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.ToPayReceivedAtDeliveryBranch.class));
    }

    @Test
    @DisplayName("inScan at the shipment's own final delivery branch credits only the "
            + "booking branch's own commission (not the company's) for a PREPAID shipment "
            + "when its booking branch has instantCommission on")
    void inScanAtFinalDestinationPublishesCommissionWhenInstant() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(ShipmentCharge.builder()
                        .commissionOnBasicFreight(new BigDecimal("10.0000"))
                        .branchCommissionOnOtherAmount(new BigDecimal("5.0000"))
                        .build()));
        when(branchService.getById(shipment.getBookingBranchId())).thenReturn(
                Branch.builder().branchCode("PUNE").instantCommission(true).build());
        when(branchService.instantCommissionOf(shipment.getBookingBranchId())).thenReturn(true);

        service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(ShipmentEvent.InScanCommissionEarned.class);
        verify(eventPublisher).publishEvent(captor.capture());
        // commissionOnBasicFreight (10) + branchCommissionOnOtherAmount (5), never the
        // stored totalCommission, which also folds in the company's own cut.
        assertThat(captor.getValue().branchCommission()).isEqualByComparingTo("15.0000");
        assertThat(captor.getValue().shipmentId()).isEqualTo(shipment.getId());
        assertThat(captor.getValue().bookingBranchId()).isEqualTo(shipment.getBookingBranchId());
    }

    @Test
    @DisplayName("inScan at the shipment's own final delivery branch publishes no commission "
            + "when the booking branch has instantCommission off")
    void inScanAtFinalDestinationSkipsCommissionWhenNotInstant() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(ShipmentCharge.builder()
                        .commissionOnBasicFreight(new BigDecimal("10.0000"))
                        .branchCommissionOnOtherAmount(new BigDecimal("5.0000"))
                        .build()));
        when(branchService.getById(shipment.getBookingBranchId())).thenReturn(
                Branch.builder().branchCode("PUNE").instantCommission(false).build());

        service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.InScanCommissionEarned.class));
    }

    @Test
    @DisplayName("inScan at the shipment's own final delivery branch publishes no booking-branch "
            + "commission for a TO_PAY/COD shipment — that credits later, on actual delivery")
    void inScanAtFinalDestinationSkipsCommissionWhenNotCollectAtBooking() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByCompanyIdAndTrackingNumber(COMPANY, shipment.getTrackingNumber()))
                .thenReturn(Optional.of(shipment));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(false));
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(ShipmentCharge.builder().netAmount(BigDecimal.ZERO).build()));

        service.inScan(DELIVERY_BRANCH, List.of(shipment.getTrackingNumber()), null, null, null, null);

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.InScanCommissionEarned.class));
    }

    // -------------------------------------------------------------------- assignOutForDelivery

    @Test
    @DisplayName("assignOutForDelivery refuses a shipment that is not IN_SCAN")
    void outForDeliveryRefusesWrongStatus() {
        Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        var result = service.assignOutForDelivery(List.of(shipment.getId()), UUID.randomUUID(), null, null, null);

        assertThat(result.failureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("assignOutForDelivery creates a DeliveryAssignment and moves the shipment to OUT_FOR_DELIVERY")
    void outForDeliveryHappyPath() {
        Shipment shipment = shipment(ShipmentStatus.IN_SCAN);
        UUID deliveryUser = UUID.randomUUID();
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.empty());
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = service.assignOutForDelivery(List.of(shipment.getId()), deliveryUser, null, null, null);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    // -------------------------------------------------------------------- deliver

    @Test
    @DisplayName("deliver refuses a shipment that is not OUT_FOR_DELIVERY")
    void deliverRefusesWrongStatus() {
        Shipment shipment = shipment(ShipmentStatus.IN_SCAN);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul", null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("OUT_FOR_DELIVERY");
    }

    @Test
    @DisplayName("deliver requires a receiver name")
    void deliverRequiresReceiverName() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand(" ", null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("receiver name");
    }

    @Test
    @DisplayName("deliver closes the assignment and moves the shipment to DELIVERED")
    void deliverHappyPath() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(true));
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE").build());

        Shipment delivered = service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        assertThat(delivered.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(assignment.getStatus()).isEqualTo(DeliveryAssignmentStatus.DELIVERED);
        assertThat(assignment.getReceiverName()).isEqualTo("Rahul Verma");
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.CodCollectedAtDelivery.class));
    }

    private Shipment partnerDelivery(boolean podApproved) {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        shipment.setPodApproved(podApproved);
        shipment.setDeliveryPincode("411001");
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY)).thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(true));
        when(branchService.getById(shipment.getDeliveryBranchId())).thenReturn(Branch.builder()
                .branchCode("PDP-1").branchType(com.courier.modules.company.domain.BranchType.PICKUP_DELIVERY_PARTNER).build());
        return shipment;
    }

    @Test
    @DisplayName("a Pickup-Delivery Partner cannot mark a shipment delivered without a POD photo")
    void partnerDeliveryRequiresPod() {
        Shipment shipment = partnerDelivery(false);

        assertThatThrownBy(() -> service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("POD photo is required");
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("a partner delivery with a POD photo and an approved POD earns via the partner event, "
            + "not the branch DRS / weight commission")
    void partnerDeliveryPublishesPartnerEarning() {
        Shipment shipment = partnerDelivery(true);

        Shipment delivered = service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", null, null, null, "https://pod/photo.jpg"));

        assertThat(delivered.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        verify(eventPublisher).publishEvent(any(ShipmentEvent.PartnerDeliveryEarned.class));
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DrsChargeApplicable.class));
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DeliveryWeightCommissionApplicable.class));
        assertThat(delivered.isCommissionCredited()).isTrue();
    }

    @Test
    @DisplayName("a partner delivery whose POD is not approved yet earns nothing until approval")
    void partnerDeliveryWaitsForPodApproval() {
        Shipment shipment = partnerDelivery(false);

        service.deliver(shipment.getId(), new ShipmentService.DeliverCommand("Rahul Verma", null, null, null, "https://pod/photo.jpg"));

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.PartnerDeliveryEarned.class));
    }

    @Test
    @DisplayName("delivering a Direct Company Delivery shipment (no delivery branch) publishes none "
            + "of the delivery-branch-attributed money — no DRS charge, no weight commission, no COD debit")
    void deliverOnDirectDeliverySkipsAllDeliveryBranchMoney() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        shipment.setDeliveryBranchId(null);
        shipment.setPodApproved(true);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(codPaymentMode());
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(ShipmentCharge.builder().netAmount(new BigDecimal("450.0000")).build()));

        Shipment delivered = service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        assertThat(delivered.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.CodCollectedAtDelivery.class));
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DrsChargeApplicable.class));
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DeliveryWeightCommissionApplicable.class));
        verify(branchService, never()).getById(isNull());
    }

    @Test
    @DisplayName("delivering a Direct Company Delivery PAID shipment credits the booking branch's "
            + "own commission here, since it never got a real in-scan to credit it at")
    void deliverOnDirectDeliveryCreditsCollectAtBookingCommission() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        shipment.setDeliveryBranchId(null);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(true));
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(ShipmentCharge.builder()
                        .commissionOnBasicFreight(new BigDecimal("10.0000"))
                        .branchCommissionOnOtherAmount(new BigDecimal("5.0000"))
                        .build()));
        when(branchService.instantCommissionOf(shipment.getBookingBranchId())).thenReturn(true);

        service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(ShipmentEvent.InScanCommissionEarned.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().branchCommission()).isEqualByComparingTo("15.0000");
        assertThat(captor.getValue().bookingBranchId()).isEqualTo(shipment.getBookingBranchId());
    }

    @Test
    @DisplayName("deliver publishes a DRS charge debit event for the delivery branch, "
            + "drsChargePerQty * total item quantity — once POD is already approved")
    void deliverPublishesDrsChargeEvent() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        // Commission crediting is gated on podApproved (2026-09-08) — this test covers the
        // "POD already approved before delivery" ordering; see
        // deliverDoesNotPublishCommissionEventsWhenPodNotApproved for the gate itself.
        shipment.setPodApproved(true);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(true));
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE")
                        .drsChargePerQty(new BigDecimal("5.00")).build());
        when(itemRepository.findAllByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(List.of(
                        ShipmentItem.builder().quantity(2).build(),
                        ShipmentItem.builder().quantity(1).build()));

        service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(ShipmentEvent.DrsChargeApplicable.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().deliveryBranchId()).isEqualTo(DELIVERY_BRANCH);
        assertThat(captor.getValue().shipmentNumber()).isEqualTo(shipment.getShipmentNumber());
        assertThat(captor.getValue().drsCharge()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("deliver publishes a weight-based delivery commission event, rate * "
            + "max(chargeable weight, branch's minimum chargeable weight)")
    void deliverPublishesDeliveryWeightCommissionEvent() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        // Commission crediting is gated on podApproved (2026-09-08) — this test covers the
        // "POD already approved before delivery" ordering.
        shipment.setPodApproved(true);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(true));
        // shipment's own chargeableWeight is 5.000 (see shipment()) — below the branch's
        // 10.00 kg floor, so the floor, not the actual weight, drives the amount.
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE")
                        .deliveryCommissionRatePerKg(new BigDecimal("1.50"))
                        .deliveryCommissionMinWeightKg(new BigDecimal("10.00")).build());

        service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(ShipmentEvent.DeliveryWeightCommissionApplicable.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().deliveryBranchId()).isEqualTo(DELIVERY_BRANCH);
        assertThat(captor.getValue().shipmentNumber()).isEqualTo(shipment.getShipmentNumber());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("15.0000");
    }

    @Test
    @DisplayName("deliver publishes a wallet-debit event for the delivery branch for a real COD "
            + "(cash-on-delivery) payment mode")
    void deliverCollectAtDeliveryPublishesCodEvent() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(codPaymentMode());
        ShipmentCharge charge = ShipmentCharge.builder().netAmount(new BigDecimal("450.0000")).build();
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(charge));
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE").build());
        when(branchService.getById(BOOKING_BRANCH))
                .thenReturn(Branch.builder().branchCode("MUMBAI").build());

        service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(ShipmentEvent.CodCollectedAtDelivery.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().deliveryBranchId()).isEqualTo(DELIVERY_BRANCH);
        assertThat(captor.getValue().shipmentNumber()).isEqualTo(shipment.getShipmentNumber());
        assertThat(captor.getValue().netAmount()).isEqualByComparingTo("450.0000");
    }

    @Test
    @DisplayName("deliver does NOT publish CodCollectedAtDelivery for TO_PAY — its freight was "
            + "already debited earlier, at in-scan")
    void deliverSkipsCodEventForToPay() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(false));
        ShipmentCharge charge = ShipmentCharge.builder().netAmount(new BigDecimal("450.0000")).build();
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(charge));
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE").build());
        when(branchService.getById(BOOKING_BRANCH))
                .thenReturn(Branch.builder().branchCode("MUMBAI").build());

        service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.CodCollectedAtDelivery.class));
    }

    @Test
    @DisplayName("delivering a TO_PAY/COD shipment credits the booking branch's own commission "
            + "(not the company's) once payment is actually collected, when instantCommission is on")
    void deliverCollectAtDeliveryPublishesCommissionWhenInstant() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        // Commission crediting is gated on podApproved (2026-09-08) — this test covers the
        // "POD already approved before delivery" ordering.
        shipment.setPodApproved(true);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(false));
        ShipmentCharge charge = ShipmentCharge.builder()
                .netAmount(new BigDecimal("450.0000"))
                .commissionOnBasicFreight(new BigDecimal("10.0000"))
                .branchCommissionOnOtherAmount(new BigDecimal("5.0000"))
                .build();
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(charge));
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE").build());
        when(branchService.getById(BOOKING_BRANCH))
                .thenReturn(Branch.builder().branchCode("MUMBAI").instantCommission(true).build());
        when(branchService.instantCommissionOf(BOOKING_BRANCH)).thenReturn(true);

        service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(ShipmentEvent.DeliveryCommissionEarned.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().bookingBranchId()).isEqualTo(BOOKING_BRANCH);
        assertThat(captor.getValue().shipmentNumber()).isEqualTo(shipment.getShipmentNumber());
        // commissionOnBasicFreight (10) + branchCommissionOnOtherAmount (5), never the
        // company's own cut.
        assertThat(captor.getValue().branchCommission()).isEqualByComparingTo("15.0000");
    }

    @Test
    @DisplayName("delivering a TO_PAY/COD shipment publishes no commission when the booking "
            + "branch has instantCommission off")
    void deliverCollectAtDeliverySkipsCommissionWhenNotInstant() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(false));
        ShipmentCharge charge = ShipmentCharge.builder()
                .netAmount(new BigDecimal("450.0000"))
                .commissionOnBasicFreight(new BigDecimal("10.0000"))
                .branchCommissionOnOtherAmount(new BigDecimal("5.0000"))
                .build();
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(charge));
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE").build());
        when(branchService.getById(BOOKING_BRANCH))
                .thenReturn(Branch.builder().branchCode("MUMBAI").instantCommission(false).build());

        service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DeliveryCommissionEarned.class));
    }

    @Test
    @DisplayName("deliver publishes no delivery commission at all (DRS, weight, or booking-branch "
            + "commission) when POD hasn't been approved yet — commission crediting is gated on "
            + "podApproved (2026-09-08), not just DELIVERED")
    void deliverDoesNotPublishCommissionEventsWhenPodNotApproved() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        // podApproved defaults false — this shipment has never had a POD run against it.
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(false));
        ShipmentCharge charge = ShipmentCharge.builder()
                .netAmount(new BigDecimal("450.0000"))
                .commissionOnBasicFreight(new BigDecimal("10.0000"))
                .branchCommissionOnOtherAmount(new BigDecimal("5.0000"))
                .build();
        when(chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(charge));
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE")
                        .drsChargePerQty(new BigDecimal("5.00"))
                        .deliveryCommissionRatePerKg(new BigDecimal("1.50"))
                        .deliveryCommissionMinWeightKg(new BigDecimal("10.00")).build());
        when(branchService.getById(BOOKING_BRANCH))
                .thenReturn(Branch.builder().branchCode("MUMBAI").instantCommission(true).build());
        when(itemRepository.findAllByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(List.of(ShipmentItem.builder().quantity(2).build()));

        service.deliver(shipment.getId(),
                new ShipmentService.DeliverCommand("Rahul Verma", "Left at gate", "1234", null, null));

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DrsChargeApplicable.class));
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DeliveryWeightCommissionApplicable.class));
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DeliveryCommissionEarned.class));
        assertThat(shipment.isCommissionCredited()).isFalse();
    }

    @Test
    @DisplayName("markPodApproved credits delivery commission for an already-DELIVERED shipment "
            + "that had no POD approved at delivery time")
    void markPodApprovedCreditsCommissionForAlreadyDeliveredShipment() {
        Shipment shipment = shipment(ShipmentStatus.DELIVERED);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(true));
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE")
                        .drsChargePerQty(new BigDecimal("5.00"))
                        .deliveryCommissionRatePerKg(BigDecimal.ZERO)
                        .deliveryCommissionMinWeightKg(BigDecimal.ZERO).build());
        when(itemRepository.findAllByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(List.of(ShipmentItem.builder().quantity(3).build()));

        service.markPodApproved(shipment.getId());

        assertThat(shipment.isPodApproved()).isTrue();
        assertThat(shipment.isCommissionCredited()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(ShipmentEvent.DrsChargeApplicable.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().drsCharge()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("markPodApproved is idempotent — a second call for an already-credited shipment "
            + "publishes nothing more")
    void markPodApprovedIsIdempotent() {
        Shipment shipment = shipment(ShipmentStatus.DELIVERED);
        shipment.setPodApproved(true);
        shipment.setCommissionCredited(true);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        service.markPodApproved(shipment.getId());

        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DrsChargeApplicable.class));
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DeliveryWeightCommissionApplicable.class));
    }

    @Test
    @DisplayName("markPodApproved on a not-yet-delivered shipment only sets the flag — no commission "
            + "to credit until it's actually delivered")
    void markPodApprovedOnUndeliveredShipmentOnlySetsFlag() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        service.markPodApproved(shipment.getId());

        assertThat(shipment.isPodApproved()).isTrue();
        assertThat(shipment.isCommissionCredited()).isFalse();
        verify(eventPublisher, never()).publishEvent(any(ShipmentEvent.DrsChargeApplicable.class));
    }

    @Test
    @DisplayName("deliver records signature/photo urls as POD ShipmentAsset rows, not on the assignment")
    void deliverRecordsPodAssets() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        DeliveryAssignment assignment = DeliveryAssignment.builder()
                .shipmentId(shipment.getId()).status(DeliveryAssignmentStatus.ASSIGNED).build();
        when(deliveryAssignmentRepository.findByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(assignment));
        when(deliveryAssignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentModeService.getById(shipment.getPaymentModeId())).thenReturn(paymentMode(true));
        when(branchService.getById(shipment.getDeliveryBranchId()))
                .thenReturn(Branch.builder().branchCode("PUNE").build());

        service.deliver(shipment.getId(), new ShipmentService.DeliverCommand(
                "Rahul Verma", "Left at gate", "1234", "https://x/sig.png", "https://x/photo.png"));

        var captor = org.mockito.ArgumentCaptor.forClass(ShipmentAsset.class);
        verify(shipmentAssetRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ShipmentAsset::getKind)
                .containsExactlyInAnyOrder("SIGNATURE", "PHOTO");
        assertThat(captor.getAllValues()).allMatch(a -> a.getAssetType() == ShipmentAssetType.POD);
    }

    // -------------------------------------------------------------------- POD upload

    @Test
    @DisplayName("uploadPodFile refuses a kind other than PHOTO/SIGNATURE")
    void uploadPodFileRefusesUnknownKind() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> service.uploadPodFile(shipment.getId(),
                new ShipmentService.UploadPodFileCommand(new byte[]{1}, "proof.jpg", "image/jpeg", "VIDEO")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("kind");
        verify(fileStoragePort, never()).upload(any());
    }

    @Test
    @DisplayName("uploadPodFile refuses a file extension outside the accepted set")
    void uploadPodFileRefusesUnacceptedExtension() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> service.uploadPodFile(shipment.getId(),
                new ShipmentService.UploadPodFileCommand(new byte[]{1}, "proof.exe", "application/octet-stream", "PHOTO")))
                .isInstanceOf(BusinessRuleException.class);
        verify(fileStoragePort, never()).upload(any());
    }

    @Test
    @DisplayName("uploadPodFile stores the file and returns the URL the port hands back")
    void uploadPodFileHappyPath() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        when(fileStoragePort.upload(any())).thenReturn(
                new FileStoragePort.StoredFile("https://bucket.s3.amazonaws.com/pod/x.jpg", "pod/x.jpg"));

        String url = service.uploadPodFile(shipment.getId(),
                new ShipmentService.UploadPodFileCommand(new byte[]{1, 2, 3}, "photo.JPG", "image/jpeg", "photo"));

        assertThat(url).isEqualTo("https://bucket.s3.amazonaws.com/pod/x.jpg");
        var captor = org.mockito.ArgumentCaptor.forClass(FileStoragePort.UploadRequest.class);
        verify(fileStoragePort).upload(captor.capture());
        assertThat(captor.getValue().keyPrefix()).isEqualTo("pod");
        assertThat(captor.getValue().filename()).contains(shipment.getId().toString()).endsWith(".jpg");
    }

    @Test
    @DisplayName("uploadPodFile surfaces the port's refusal when no storage backend is configured")
    void uploadPodFileBubblesUnconfiguredStorage() {
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        when(fileStoragePort.upload(any()))
                .thenThrow(new BusinessRuleException("File upload is not available: no storage backend is configured."));

        assertThatThrownBy(() -> service.uploadPodFile(shipment.getId(),
                new ShipmentService.UploadPodFileCommand(new byte[]{1}, "photo.jpg", "image/jpeg", "PHOTO")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no storage backend");
    }

    // -------------------------------------------------------------------- timeline

    @Test
    @DisplayName("timeline reports every named step, completed only for statuses actually reached")
    void timelineMarksCompletedSteps() {
        Shipment shipment = shipment(ShipmentStatus.MANIFEST_CREATED);
        when(shipmentRepository.findByIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(Optional.of(shipment));
        when(historyRepository.findAllByShipmentIdWithinCompany(shipment.getId(), COMPANY))
                .thenReturn(List.of());

        var steps = service.timeline(shipment.getId());

        assertThat(steps).hasSize(6);
        assertThat(steps).allMatch(s -> !s.completed());
    }

    // ---------------------------------------------------------------------- helpers

    private static Shipment shipment(ShipmentStatus status) {
        Shipment shipment = Shipment.builder()
                .shipmentNumber("SHP2607300000001")
                .trackingNumber("AWB" + UUID.randomUUID().toString().substring(0, 12))
                .bookingDate(LocalDate.of(2026, 7, 30))
                .bookingBranchId(BOOKING_BRANCH)
                .deliveryBranchId(DELIVERY_BRANCH)
                .pickupPincode("411001")
                .deliveryPincode("400008")
                .senderName("Asha Shah")
                .senderAddress("221B Baker Street, Pune")
                .senderContact("9876543210")
                .receiverName("Rahul Verma")
                .receiverAddress("12 MG Road, Mumbai")
                .receiverContact("9876500000")
                .serviceTypeId(UUID.randomUUID())
                .packageTypeId(UUID.randomUUID())
                .paymentModeId(UUID.randomUUID())
                .actualWeight(new BigDecimal("5.000"))
                .volumetricWeight(BigDecimal.ZERO)
                .chargeableWeight(new BigDecimal("5.000"))
                .numberOfPackages(1)
                .status(status)
                .build();
        shipment.setId(UUID.randomUUID());
        shipment.setCompanyId(COMPANY);
        shipment.setVersion(0L);
        return shipment;
    }

    /** {@code collectAtBooking=false} here means collect-at-delivery generically — in
     *  practice this is a TO_PAY-shaped mode ({@code cashOnDelivery} left false). Use
     *  {@link #codPaymentMode()} for a real cash-on-delivery mode. */
    private static PaymentMode paymentMode(boolean collectAtBooking) {
        PaymentMode paymentMode = new PaymentMode();
        paymentMode.setCode(collectAtBooking ? "PAID" : "TO_PAY");
        paymentMode.setCollectAtBooking(collectAtBooking);
        paymentMode.setCollectAtDelivery(!collectAtBooking);
        return paymentMode;
    }

    /** A real cash-on-delivery mode — collects at delivery, and {@code cashOnDelivery} is
     *  the consignee's amount, not the freight. */
    private static PaymentMode codPaymentMode() {
        PaymentMode paymentMode = new PaymentMode();
        paymentMode.setCode("COD");
        paymentMode.setCollectAtDelivery(true);
        paymentMode.setCashOnDelivery(true);
        return paymentMode;
    }
}
