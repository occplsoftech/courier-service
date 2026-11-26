package com.courier.modules.manifest.application;

import com.courier.modules.company.application.UserService;
import com.courier.modules.company.domain.User;
import com.courier.modules.manifest.application.command.CreateManifestCommand;
import com.courier.modules.manifest.domain.DeliveryMode;
import com.courier.modules.manifest.domain.Manifest;
import com.courier.modules.manifest.domain.ManifestRepository;
import com.courier.modules.manifest.domain.ManifestStatus;
import com.courier.modules.manifest.domain.Vehicle;
import com.courier.modules.manifest.domain.VehicleStatus;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentStatus;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Manifest create/dispatch orchestration, with Shipment/Vehicle/User all mocked — mirrors
 * {@code shipment.application.ShipmentServiceImplTest}'s shape. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManifestServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID BOOKING_BRANCH = UUID.randomUUID();
    private static final UUID DELIVERY_BRANCH = UUID.randomUUID();

    @Mock private ManifestRepository manifestRepository;
    @Mock private ShipmentService shipmentService;
    @Mock private VehicleService vehicleService;
    @Mock private CoLoaderService coLoaderService;
    @Mock private UserService userService;
    @Mock private AuditService auditService;
    @Mock private com.courier.modules.ewaybill.application.EwayBillService ewayBillService;
    @Mock private com.courier.modules.company.application.CompanySettingsService companySettingsService;
    @Mock private com.courier.modules.communication.application.CommunicationSettingService communicationSettingService;
    @Mock private com.courier.modules.communication.application.provider.SmsProvider smsProvider;
    @Mock private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock private com.courier.modules.manifest.domain.BranchDirectoryPort branchDirectory;
    @Mock private com.courier.modules.manifest.domain.HubOutScanCheckPort hubOutScanCheck;

    private ManifestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ManifestServiceImpl(manifestRepository, shipmentService, vehicleService, coLoaderService,
                userService, auditService, ewayBillService, companySettingsService,
                communicationSettingService, smsProvider, passwordEncoder, objectMapper,
                branchDirectory, hubOutScanCheck);
        CompanyContext.setCompanyId(COMPANY);
        AuthenticatedUser principal = new AuthenticatedUser(
                CALLER, COMPANY, "ops@test.com", Set.of(Roles.COMPANY_ADMIN), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
        when(manifestRepository.save(any(Manifest.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("create attaches every shipment id, one call per id, through the shipment module's own seam")
    void createAttachesEveryShipment() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        when(manifestRepository.existsByCompanyIdAndManifestNumber(eq(COMPANY), any())).thenReturn(false);

        Manifest created = service.create(new CreateManifestCommand(
                BOOKING_BRANCH, DELIVERY_BRANCH, DeliveryMode.BRANCH_DELIVERY, "Pune", List.of(s1, s2), "remarks"));

        assertThat(created.getStatus()).isEqualTo(ManifestStatus.CREATED);
        assertThat(created.getBookingBranchId()).isEqualTo(BOOKING_BRANCH);
        assertThat(created.getDestinationCity()).isEqualTo("Pune");
        verify(shipmentService).attachToManifest(s1, created.getId(), BOOKING_BRANCH, DELIVERY_BRANCH, "Pune");
        verify(shipmentService).attachToManifest(s2, created.getId(), BOOKING_BRANCH, DELIVERY_BRANCH, "Pune");
    }

    @Test
    @DisplayName("create with hubTransfer attaches through the hub path when the delivery branch is a hub")
    void createHubTransferAttachesViaHub() {
        UUID s1 = UUID.randomUUID();
        when(manifestRepository.existsByCompanyIdAndManifestNumber(eq(COMPANY), any())).thenReturn(false);
        when(branchDirectory.findBranch(DELIVERY_BRANCH, COMPANY)).thenReturn(java.util.Optional.of(
                new com.courier.modules.manifest.domain.BranchDirectoryPort.BranchRef(DELIVERY_BRANCH, COMPANY, "HUB", true)));

        Manifest created = service.create(new CreateManifestCommand(
                BOOKING_BRANCH, DELIVERY_BRANCH, DeliveryMode.BRANCH_DELIVERY, "Pune", List.of(s1), null, true));

        verify(shipmentService).attachToManifest(s1, created.getId(), BOOKING_BRANCH, DELIVERY_BRANCH, "Pune", true);
    }

    @Test
    @DisplayName("create with hubTransfer refuses a delivery branch that is not a hub")
    void createHubTransferRefusesNonHub() {
        when(branchDirectory.findBranch(DELIVERY_BRANCH, COMPANY)).thenReturn(java.util.Optional.of(
                new com.courier.modules.manifest.domain.BranchDirectoryPort.BranchRef(DELIVERY_BRANCH, COMPANY, "BRANCH", true)));

        assertThatThrownBy(() -> service.create(new CreateManifestCommand(
                BOOKING_BRANCH, DELIVERY_BRANCH, DeliveryMode.BRANCH_DELIVERY, "Pune",
                List.of(UUID.randomUUID()), null, true)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not a hub");
        verify(manifestRepository, never()).save(any());
    }

    @Test
    @DisplayName("create refuses an empty shipment list")
    void createRefusesEmptyShipmentList() {
        assertThatThrownBy(() -> service.create(
                new CreateManifestCommand(BOOKING_BRANCH, DELIVERY_BRANCH, DeliveryMode.BRANCH_DELIVERY, "Pune",
                        List.of(), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least one shipment");
        verify(manifestRepository, never()).save(any());
    }

    @Test
    @DisplayName("create defaults to BRANCH_DELIVERY when deliveryMode is omitted")
    void createDefaultsToBranchDelivery() {
        UUID s1 = UUID.randomUUID();
        when(manifestRepository.existsByCompanyIdAndManifestNumber(eq(COMPANY), any())).thenReturn(false);

        Manifest created = service.create(new CreateManifestCommand(
                BOOKING_BRANCH, DELIVERY_BRANCH, null, "Pune", List.of(s1), null));

        assertThat(created.getDeliveryMode()).isEqualTo(DeliveryMode.BRANCH_DELIVERY);
    }

    @Test
    @DisplayName("create refuses BRANCH_DELIVERY with no delivery branch")
    void createRefusesBranchDeliveryWithoutBranch() {
        assertThatThrownBy(() -> service.create(new CreateManifestCommand(
                BOOKING_BRANCH, null, DeliveryMode.BRANCH_DELIVERY, "Pune", List.of(UUID.randomUUID()), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("delivery branch");
    }

    @Test
    @DisplayName("create refuses DIRECT_COMPANY_DELIVERY with a delivery branch")
    void createRefusesDirectDeliveryWithBranch() {
        assertThatThrownBy(() -> service.create(new CreateManifestCommand(
                BOOKING_BRANCH, DELIVERY_BRANCH, DeliveryMode.DIRECT_COMPANY_DELIVERY, "Pune",
                List.of(UUID.randomUUID()), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Direct Company Delivery");
    }

    @Test
    @DisplayName("create allows DIRECT_COMPANY_DELIVERY with no delivery branch, attaching with a null one")
    void createAllowsDirectDeliveryWithNoBranch() {
        UUID s1 = UUID.randomUUID();
        when(manifestRepository.existsByCompanyIdAndManifestNumber(eq(COMPANY), any())).thenReturn(false);

        Manifest created = service.create(new CreateManifestCommand(
                BOOKING_BRANCH, null, DeliveryMode.DIRECT_COMPANY_DELIVERY, "Pune", List.of(s1), null));

        assertThat(created.getDeliveryMode()).isEqualTo(DeliveryMode.DIRECT_COMPANY_DELIVERY);
        assertThat(created.getDeliveryBranchId()).isNull();
        verify(shipmentService).attachToManifest(s1, created.getId(), BOOKING_BRANCH, null, "Pune");
    }

    @Test
    @DisplayName("dispatch refuses a manifest with no shipment")
    void dispatchRefusesWithoutShipments() {
        Manifest manifest = existingManifest(ManifestStatus.CREATED);
        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY))
                .thenReturn(Optional.of(manifest));
        when(shipmentService.findManifestCreatedShipments(manifest.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.dispatch(manifest.getId(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no shipment");
        verify(vehicleService, never()).getById(any());
    }

    @Test
    @DisplayName("dispatch refuses an inactive vehicle")
    void dispatchRefusesInactiveVehicle() {
        Manifest manifest = existingManifest(ManifestStatus.CREATED);
        UUID vehicleId = UUID.randomUUID();
        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY))
                .thenReturn(Optional.of(manifest));
        when(shipmentService.findManifestCreatedShipments(manifest.getId()))
                .thenReturn(List.of(mock(Shipment.class)));
        Vehicle inactive = Vehicle.builder().vehicleNumber("MH12AB1234").status(VehicleStatus.INACTIVE)
                .active(false).build();
        when(vehicleService.getById(vehicleId)).thenReturn(inactive);

        assertThatThrownBy(() -> service.dispatch(manifest.getId(), vehicleId, UUID.randomUUID(), null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("dispatch with a co-loader and no vehicle or driver (Scan → Assign to Branch, as on the earlier portal)")
    void dispatchWithCoLoaderOnly() {
        Manifest manifest = existingManifest(ManifestStatus.CREATED);
        UUID coLoader = UUID.randomUUID();
        Shipment ready = mock(Shipment.class);
        when(ready.getId()).thenReturn(UUID.randomUUID());
        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY)).thenReturn(Optional.of(manifest));
        when(shipmentService.findManifestCreatedShipments(manifest.getId())).thenReturn(List.of(ready));

        Manifest dispatched = service.dispatch(manifest.getId(), null, null, null, null, null, null, null,
                coLoader, " FWD-8821 ", 3);

        assertThat(dispatched.getStatus()).isEqualTo(ManifestStatus.DISPATCHED);
        assertThat(dispatched.getCoLoaderId()).isEqualTo(coLoader);
        assertThat(dispatched.getForwardingNumber()).isEqualTo("FWD-8821");
        assertThat(dispatched.getBagsCount()).isEqualTo(3);
        verify(coLoaderService).requireActive(coLoader);
        verify(vehicleService, never()).getById(any());
        verify(shipmentService).transitionToDispatched(List.of(ready.getId()), manifest.getId(), null, manifest.getBookingBranchId());
        com.courier.shared.activity.application.ActivityContext.clear();
    }

    @Test
    @DisplayName("dispatch assigns vehicle+driver, moves the manifest to DISPATCHED, and transitions its MANIFEST_CREATED shipments")
    void dispatchHappyPath() {
        Manifest manifest = existingManifest(ManifestStatus.CREATED);
        UUID vehicleId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        Shipment ready = mock(Shipment.class);
        when(ready.getId()).thenReturn(UUID.randomUUID());
        when(ready.getStatus()).thenReturn(ShipmentStatus.MANIFEST_CREATED);

        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY))
                .thenReturn(Optional.of(manifest));
        when(shipmentService.findManifestCreatedShipments(manifest.getId())).thenReturn(List.of(ready));
        Vehicle active = Vehicle.builder().vehicleNumber("MH12AB1234").status(VehicleStatus.AVAILABLE).build();
        when(vehicleService.getById(vehicleId)).thenReturn(active);
        when(userService.getById(driverId)).thenReturn(mock(User.class));

        Manifest dispatched = service.dispatch(manifest.getId(), vehicleId, driverId, null, null, null, null, null);

        assertThat(dispatched.getStatus()).isEqualTo(ManifestStatus.DISPATCHED);
        assertThat(dispatched.getVehicleId()).isEqualTo(vehicleId);
        assertThat(dispatched.getDriverUserId()).isEqualTo(driverId);
        verify(shipmentService).transitionToDispatched(
                List.of(ready.getId()), manifest.getId(), vehicleId, manifest.getBookingBranchId());
        verify(shipmentService, never()).markPickedUpForDirectDelivery(any());

        var activity = com.courier.shared.activity.application.ActivityContext.get();
        assertThat(activity).isNotNull();
        assertThat(activity.oldValue()).containsEntry("status", "CREATED");
        assertThat(activity.newValue()).containsEntry("status", "DISPATCHED");
        com.courier.shared.activity.application.ActivityContext.clear();
    }

    @Test
    @DisplayName("dispatch on a DIRECT_COMPANY_DELIVERY manifest also marks its shipments picked up directly")
    void dispatchOnDirectDeliveryMarksPickedUp() {
        Manifest manifest = existingManifest(ManifestStatus.CREATED, DeliveryMode.DIRECT_COMPANY_DELIVERY, null);
        UUID vehicleId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        Shipment ready = mock(Shipment.class);
        when(ready.getId()).thenReturn(UUID.randomUUID());
        when(ready.getStatus()).thenReturn(ShipmentStatus.MANIFEST_CREATED);

        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY))
                .thenReturn(Optional.of(manifest));
        when(shipmentService.findManifestCreatedShipments(manifest.getId())).thenReturn(List.of(ready));
        Vehicle active = Vehicle.builder().vehicleNumber("MH12AB1234").status(VehicleStatus.AVAILABLE).build();
        when(vehicleService.getById(vehicleId)).thenReturn(active);
        when(userService.getById(driverId)).thenReturn(mock(User.class));

        service.dispatch(manifest.getId(), vehicleId, driverId, null, null, null, null, null);

        verify(shipmentService).markPickedUpForDirectDelivery(List.of(ready.getId()));
    }

    @Test
    @DisplayName("dispatch succeeds with no OTP ever requested — OTP is optional for now")
    void dispatchSucceedsWithoutAnyOtp() {
        Manifest manifest = existingManifest(ManifestStatus.CREATED);
        UUID vehicleId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY))
                .thenReturn(Optional.of(manifest));
        when(shipmentService.findManifestCreatedShipments(manifest.getId()))
                .thenReturn(List.of(mock(Shipment.class)));
        Vehicle active = Vehicle.builder().vehicleNumber("MH12AB1234").status(VehicleStatus.AVAILABLE).build();
        when(vehicleService.getById(vehicleId)).thenReturn(active);
        when(userService.getById(driverId)).thenReturn(mock(User.class));

        Manifest dispatched = service.dispatch(manifest.getId(), vehicleId, driverId, null, null, null, null, null);

        assertThat(dispatched.getStatus()).isEqualTo(ManifestStatus.DISPATCHED);
    }

    @Test
    @DisplayName("verifyDispatchOtp registers a wrong code as a failed attempt")
    void verifyDispatchOtpRejectsWrongCode() {
        Manifest manifest = existingManifest(ManifestStatus.CREATED);
        UUID driverId = UUID.randomUUID();
        manifest.issueDispatchOtp(driverId, "hashed", java.time.Instant.now().plusSeconds(300));
        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY))
                .thenReturn(Optional.of(manifest));
        when(passwordEncoder.matches("000000", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyDispatchOtp(manifest.getId(), driverId, "000000"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Incorrect OTP");
        assertThat(manifest.getDispatchOtpAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("dispatch refuses a manifest that has already been dispatched")
    void dispatchRefusedTwice() {
        Manifest manifest = existingManifest(ManifestStatus.DISPATCHED);
        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY))
                .thenReturn(Optional.of(manifest));

        assertThatThrownBy(() -> service.dispatch(manifest.getId(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already been dispatched");
        verify(shipmentService, never()).findManifestCreatedShipments(any());
    }

    @Test
    @DisplayName("dispatch refuses a hub-originated manifest when a shipment has not been out-scanned")
    void dispatchRefusesHubManifestWithoutOutScan() {
        Manifest manifest = existingManifest(ManifestStatus.CREATED);
        Shipment ready = mock(Shipment.class);
        when(ready.getId()).thenReturn(UUID.randomUUID());
        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY))
                .thenReturn(Optional.of(manifest));
        when(shipmentService.findManifestCreatedShipments(manifest.getId())).thenReturn(List.of(ready));
        when(branchDirectory.findBranch(BOOKING_BRANCH, COMPANY)).thenReturn(Optional.of(
                new com.courier.modules.manifest.domain.BranchDirectoryPort.BranchRef(
                        BOOKING_BRANCH, COMPANY, "HUB", true)));
        when(hubOutScanCheck.allScanned(COMPANY, manifest.getId(), List.of(ready.getId()))).thenReturn(false);

        assertThatThrownBy(() -> service.dispatch(manifest.getId(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("out-scanned");
        verify(vehicleService, never()).getById(any());
    }

    @Test
    @DisplayName("dispatch succeeds on a hub-originated manifest once every shipment has been out-scanned")
    void dispatchSucceedsHubManifestWhenFullyOutScanned() {
        Manifest manifest = existingManifest(ManifestStatus.CREATED);
        UUID vehicleId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        Shipment ready = mock(Shipment.class);
        when(ready.getId()).thenReturn(UUID.randomUUID());
        when(ready.getStatus()).thenReturn(ShipmentStatus.MANIFEST_CREATED);
        when(manifestRepository.findByIdWithinCompany(manifest.getId(), COMPANY))
                .thenReturn(Optional.of(manifest));
        when(shipmentService.findManifestCreatedShipments(manifest.getId())).thenReturn(List.of(ready));
        when(branchDirectory.findBranch(BOOKING_BRANCH, COMPANY)).thenReturn(Optional.of(
                new com.courier.modules.manifest.domain.BranchDirectoryPort.BranchRef(
                        BOOKING_BRANCH, COMPANY, "HUB", true)));
        when(hubOutScanCheck.allScanned(COMPANY, manifest.getId(), List.of(ready.getId()))).thenReturn(true);
        Vehicle active = Vehicle.builder().vehicleNumber("MH12AB1234").status(VehicleStatus.AVAILABLE).build();
        when(vehicleService.getById(vehicleId)).thenReturn(active);
        when(userService.getById(driverId)).thenReturn(mock(User.class));

        Manifest dispatched = service.dispatch(manifest.getId(), vehicleId, driverId, null, null, null, null, null);

        assertThat(dispatched.getStatus()).isEqualTo(ManifestStatus.DISPATCHED);
        com.courier.shared.activity.application.ActivityContext.clear();
    }

    private Manifest existingManifest(ManifestStatus status) {
        return existingManifest(status, DeliveryMode.BRANCH_DELIVERY, DELIVERY_BRANCH);
    }

    private Manifest existingManifest(ManifestStatus status, DeliveryMode deliveryMode, UUID deliveryBranchId) {
        Manifest manifest = Manifest.builder()
                .manifestNumber("MFT-250101-1234")
                .bookingBranchId(BOOKING_BRANCH)
                .deliveryBranchId(deliveryBranchId)
                .deliveryMode(deliveryMode)
                .status(status)
                .build();
        manifest.setCompanyId(COMPANY);
        return manifest;
    }
}
