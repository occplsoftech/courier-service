package com.courier.modules.manifest.application;

import com.courier.modules.communication.application.CommunicationConfigJson;
import com.courier.modules.communication.application.CommunicationSettingService;
import com.courier.modules.communication.application.provider.ProviderSendException;
import com.courier.modules.communication.application.provider.SmsProvider;
import com.courier.modules.communication.domain.CommunicationChannel;
import com.courier.modules.communication.domain.CommunicationSetting;
import com.courier.modules.company.application.CompanySettingsService;
import com.courier.modules.company.application.UserService;
import com.courier.modules.company.domain.User;
import com.courier.modules.ewaybill.application.EwayBillService;
import com.courier.modules.manifest.application.command.CreateManifestCommand;
import com.courier.modules.manifest.domain.BranchDirectoryPort;
import com.courier.modules.manifest.domain.DeliveryMode;
import com.courier.modules.manifest.domain.HubOutScanCheckPort;
import com.courier.modules.manifest.domain.Manifest;
import com.courier.modules.manifest.domain.ManifestCriteria;
import com.courier.modules.manifest.domain.ManifestNumberGenerator;
import com.courier.modules.manifest.domain.ManifestRepository;
import com.courier.modules.manifest.domain.ManifestShipmentAggregate;
import com.courier.modules.manifest.domain.ManifestSpecifications;
import com.courier.modules.manifest.domain.ManifestStatus;
import com.courier.modules.manifest.domain.ManifestSummaryStats;
import com.courier.modules.manifest.domain.Vehicle;
import com.courier.shared.activity.application.ActivityContext;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.modules.shipment.domain.FlowType;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates both directions Shipment Movement needs from a manifest: creating one
 * (attaching shipments, each of which only {@code modules.shipment} can validate and
 * mutate) and dispatching one (assigning the vehicle/driver, then moving the manifest's
 * own {@code MANIFEST_CREATED} shipments to {@code DISPATCHED}). Manifest is the only module that
 * calls the other — see {@code ShipmentService}'s own "Shipment Movement" section header
 * for why that one-directional arrow was chosen over the reverse: a two-way Spring bean
 * dependency between the two modules' services would be a circular-dependency startup
 * failure, not just an architectural smell.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestServiceImpl implements ManifestService {

    private static final String ENTITY = "Manifest";
    private static final String WRITERS = "hasAnyRole('" + Roles.COMPANY_ADMIN + "', '"
            + Roles.BRANCH_MANAGER + "', '" + Roles.OPERATOR + "')";
    private static final String READERS = "isAuthenticated()";

    private static final int NUMBER_ATTEMPTS = 5;
    private static final int DEFAULT_OTP_EXPIRY_MINUTES = 5;
    private static final SecureRandom OTP_RANDOM = new SecureRandom();

    private final ManifestRepository manifestRepository;
    private final ShipmentService shipmentService;
    private final VehicleService vehicleService;
    private final CoLoaderService coLoaderService;
    private final UserService userService;
    private final AuditService auditService;
    private final EwayBillService ewayBillService;
    private final CompanySettingsService companySettingsService;
    private final CommunicationSettingService communicationSettingService;
    private final SmsProvider smsProvider;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final BranchDirectoryPort branchDirectory;
    private final HubOutScanCheckPort hubOutScanCheck;

    @Override
    @Transactional
    // Narrower than WRITERS: create/list/dispatch are the three methods the Menu +
    // Permission Management example names (Manifest -> Create Manifest/Manifest
    // List/Dispatch), retrofit onto the permission catalogue. removeShipment/
    // summaryStats stay on the class's coarser role-tier constants.
    @PreAuthorize("hasAuthority('MANIFEST_CREATE')")
    public Manifest create(CreateManifestCommand command) {
        UUID companyId = requireCompany();

        DeliveryMode deliveryMode = command.deliveryMode() == null ? DeliveryMode.BRANCH_DELIVERY : command.deliveryMode();
        if (command.bookingBranchId() == null) {
            throw new BusinessRuleException("A manifest needs a booking branch.");
        }
        if (deliveryMode == DeliveryMode.BRANCH_DELIVERY && command.deliveryBranchId() == null) {
            throw new BusinessRuleException("A manifest needs both a booking branch and a delivery branch.");
        }
        if (deliveryMode == DeliveryMode.DIRECT_COMPANY_DELIVERY && command.deliveryBranchId() != null) {
            throw new BusinessRuleException("Direct Company Delivery does not use a delivery branch.");
        }
        if (command.shipmentIds() == null || command.shipmentIds().isEmpty()) {
            throw new BusinessRuleException("A manifest needs at least one shipment.");
        }
        FlowType flowType = command.flowType() == null ? FlowType.STANDARD : command.flowType();
        if (flowType == FlowType.MSRTC) {
            // MSRTC only ever moves branch to branch: a real delivery branch, no hub stop,
            // no Direct Company Delivery.
            if (deliveryMode != DeliveryMode.BRANCH_DELIVERY || command.hubTransfer()) {
                throw new BusinessRuleException("An MSRTC manifest goes branch to branch only.");
            }
            if (command.bookingBranchId().equals(command.deliveryBranchId())) {
                throw new BusinessRuleException("The delivery branch must be a different branch from the booking branch.");
            }
        }
        // STANDARD and MSRTC shipments never share a manifest, in either direction.
        shipmentService.requireFlowType(command.shipmentIds(), flowType);
        if (command.hubTransfer()) {
            if (deliveryMode != DeliveryMode.BRANCH_DELIVERY) {
                throw new BusinessRuleException("A hub Load Sheet cannot use Direct Company Delivery.");
            }
            boolean toHub = branchDirectory.findBranch(command.deliveryBranchId(), companyId)
                    .map(branch -> "HUB".equals(branch.branchType()))
                    .orElse(false);
            if (!toHub) {
                throw new BusinessRuleException("The selected branch is not a hub.");
            }
        }

        Manifest manifest = Manifest.builder()
                .manifestNumber(nextManifestNumber(companyId))
                .bookingBranchId(command.bookingBranchId())
                .deliveryBranchId(command.deliveryBranchId())
                .deliveryMode(deliveryMode)
                .flowType(flowType)
                .destinationCity(command.destinationCity())
                .remarks(command.remarks())
                .build();
        Manifest saved = manifestRepository.save(manifest);

        for (UUID shipmentId : command.shipmentIds()) {
            if (command.hubTransfer()) {
                shipmentService.attachToManifest(shipmentId, saved.getId(), command.bookingBranchId(),
                        command.deliveryBranchId(), command.destinationCity(), true);
            } else {
                shipmentService.attachToManifest(shipmentId, saved.getId(),
                        command.bookingBranchId(), command.deliveryBranchId(), command.destinationCity());
            }
        }

        log.info("Manifest {} ({}) created in company {} with {} shipment(s) by {}",
                saved.getManifestNumber(), saved.getId(), companyId, command.shipmentIds().size(),
                currentActor());
        auditService.record(AuditAction.MANIFEST_CREATED, ENTITY, saved.getId(),
                Map.of("manifestNumber", saved.getManifestNumber(),
                        "shipmentCount", command.shipmentIds().size()));

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('MANIFEST_READ')")
    public Manifest getById(UUID id) {
        return loadOrThrow(id, requireCompany());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('MANIFEST_READ')")
    public Page<Manifest> search(ManifestCriteria criteria, Pageable pageable) {
        return manifestRepository.findAll(ManifestSpecifications.matching(criteria).and(ownBranchScope()), pageable);
    }

    /** A branch-bound caller (not a company/platform admin) only sees manifests their own
     *  branch books, or dispatched ones addressed to it — never another branch's load sheets. */
    private Specification<Manifest> ownBranchScope() {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.isPlatformTier() || caller.hasRole(Roles.COMPANY_ADMIN)) {
            return (root, query, cb) -> cb.conjunction();
        }
        UUID ownBranchId = userService.getById(caller.userId()).getBranchId();
        if (ownBranchId == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        // Until dispatch a load sheet belongs to the branch that created it alone; the
        // receiving branch only gets it once it is on the road (In Scan).
        return (root, query, cb) -> cb.or(cb.equal(root.get("bookingBranchId"), ownBranchId),
                cb.and(cb.equal(root.get("deliveryBranchId"), ownBranchId),
                        cb.notEqual(root.get("status"), ManifestStatus.CREATED)));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public ManifestSummaryStats summaryStats(ManifestCriteria criteria) {
        List<Manifest> matches = manifestRepository.findAll(ManifestSpecifications.matching(criteria));
        List<UUID> ids = matches.stream().map(Manifest::getId).toList();
        ManifestShipmentAggregate aggregate = ManifestShipmentAggregate.of(shipmentService.findByManifestIds(ids));
        BigDecimal totalTripExpenses = matches.stream()
                .map(ManifestServiceImpl::tripExpenseTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ManifestSummaryStats(matches.size(), aggregate.shipmentCount(),
                aggregate.totalWeight(), aggregate.totalPackages(), totalTripExpenses);
    }

    /** Fuel/advance/toll/other are each independently optional — nulls contribute 0
     *  rather than making the whole manifest's total null. */
    private static BigDecimal tripExpenseTotal(Manifest m) {
        return nz(m.getFuelCost()).add(nz(m.getDriverAdvance()))
                .add(nz(m.getTollAmount())).add(nz(m.getOtherAmount()));
    }

    /** "With Forwarder No.": each given number is saved on its shipment (saved with the DISPATCHED move below). */
    private static void applyForwarderNumbers(List<Shipment> onManifest, Map<UUID, String> forwarderNumbers) {
        if (forwarderNumbers == null || forwarderNumbers.isEmpty()) {
            return;
        }
        Map<UUID, Shipment> byId = new java.util.HashMap<>();
        onManifest.forEach(s -> byId.put(s.getId(), s));
        forwarderNumbers.forEach((shipmentId, number) -> {
            Shipment s = byId.get(shipmentId);
            if (s == null) {
                throw new BusinessRuleException("A forwarder number was given for a shipment that is not on this manifest.");
            }
            String v = number == null ? "" : number.trim();
            if (v.isEmpty()) {
                throw new BusinessRuleException("Enter the forwarder number for %s.".formatted(s.getShipmentNumber()));
            }
            if (v.length() > 60) {
                throw new BusinessRuleException("The forwarder number for %s is longer than 60 characters.".formatted(s.getShipmentNumber()));
            }
            s.setForwarderNumber(v);
        });
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('MANIFEST_DISPATCH')")
    public Manifest dispatch(UUID id, UUID vehicleId, UUID driverUserId, Instant departureTime,
            BigDecimal fuelCost, BigDecimal driverAdvance, BigDecimal tollAmount, BigDecimal otherAmount) {
        return dispatch(id, vehicleId, driverUserId, departureTime, fuelCost, driverAdvance, tollAmount, otherAmount, null, null, null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('MANIFEST_DISPATCH')")
    public Manifest dispatch(UUID id, UUID vehicleId, UUID driverUserId, Instant departureTime,
            BigDecimal fuelCost, BigDecimal driverAdvance, BigDecimal tollAmount, BigDecimal otherAmount,
            UUID coLoaderId, String forwardingNumber, Integer bagsCount) {
        return dispatch(id, vehicleId, driverUserId, departureTime, fuelCost, driverAdvance, tollAmount, otherAmount,
                coLoaderId, forwardingNumber, bagsCount, null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('MANIFEST_DISPATCH')")
    public Manifest dispatch(UUID id, UUID vehicleId, UUID driverUserId, Instant departureTime,
            BigDecimal fuelCost, BigDecimal driverAdvance, BigDecimal tollAmount, BigDecimal otherAmount,
            UUID coLoaderId, String forwardingNumber, Integer bagsCount, Map<UUID, String> forwarderNumbers) {
        UUID companyId = requireCompany();
        Manifest manifest = loadOrThrow(id, companyId);

        if (manifest.isDispatched()) {
            throw new BusinessRuleException(
                    "Manifest %s has already been dispatched.".formatted(manifest.getManifestNumber()));
        }

        List<Shipment> readyShipments = shipmentService.findManifestCreatedShipments(manifest.getId());
        if (readyShipments.isEmpty()) {
            throw new BusinessRuleException(
                    "Manifest %s has no shipment to dispatch.".formatted(manifest.getManifestNumber()));
        }

        // Hub Operations (2026-09-21): a Load Sheet booked from a hub (branch_type HUB)
        // may not dispatch until every shipment on it has been out-scanned — Branch
        // Delivery's own dispatch is untouched, this check never runs for it.
        boolean fromHub = branchDirectory.findBranch(manifest.getBookingBranchId(), companyId)
                .map(branch -> "HUB".equals(branch.branchType()))
                .orElse(false);
        if (fromHub) {
            List<UUID> readyShipmentIds = readyShipments.stream().map(Shipment::getId).toList();
            if (!hubOutScanCheck.allScanned(companyId, manifest.getId(), readyShipmentIds)) {
                throw new BusinessRuleException(
                        "Manifest %s has shipments that have not been out-scanned yet."
                                .formatted(manifest.getManifestNumber()));
            }
        }

        // A trip goes with our own vehicle and driver, or is handed to a co-loader (the earlier portal's outscan
        // asked only for the co-loader). Each one given is checked.
        if (vehicleId != null) {
            Vehicle vehicle = vehicleService.getById(vehicleId);
            if (!vehicle.isActive()) {
                throw new BusinessRuleException(
                        "Vehicle %s is not active.".formatted(vehicle.getVehicleNumber()));
            }
        }
        // A driver is any real user of this company — see the module doc for why no
        // company role currently models "driver" cleanly enough to restrict further.
        if (driverUserId != null) {
            userService.getById(driverUserId);
        }
        if (coLoaderId != null) {
            coLoaderService.requireActive(coLoaderId);
        }
        manifest.setCoLoaderId(coLoaderId);
        manifest.setForwardingNumber(forwardingNumber == null || forwardingNumber.isBlank() ? null : forwardingNumber.trim());
        manifest.setBagsCount(bagsCount);
        applyForwarderNumbers(readyShipments, forwarderNumbers);

        String statusBefore = manifest.getStatus().name();
        manifest.dispatch(vehicleId, driverUserId, departureTime, fuelCost, driverAdvance, tollAmount, otherAmount);
        Manifest saved = manifestRepository.save(manifest);

        ActivityContext.recordChange("Manifest", "Dispatch",
                "Dispatched manifest %s with vehicle %s".formatted(saved.getManifestNumber(), vehicleId),
                "Manifest", saved.getId().toString(),
                Map.of("status", statusBefore),
                Map.of("status", saved.getStatus().name(), "vehicleId", String.valueOf(vehicleId),
                        "driverUserId", String.valueOf(driverUserId)));

        List<UUID> dispatchedShipmentIds = readyShipments.stream().map(Shipment::getId).toList();
        shipmentService.transitionToDispatched(
                dispatchedShipmentIds, saved.getId(), vehicleId, saved.getBookingBranchId());

        // Direct Company Delivery has no delivery branch to receive the shipment — the
        // company vehicle/driver just assigned above takes it the rest of the way itself,
        // so it goes straight from DISPATCHED to IN_SCAN (ready for Out For Delivery) here
        // rather than waiting for a real branch's own in-scan that will never happen. No
        // wallet/commission event fires from this — see ShipmentServiceImpl.deliver for
        // where a Direct Company Delivery shipment's own commission is credited instead.
        if (saved.getDeliveryMode() == DeliveryMode.DIRECT_COMPANY_DELIVERY) {
            shipmentService.markPickedUpForDirectDelivery(dispatchedShipmentIds);
        }

        // Part-B: vehicle details are now available, so every shipment on this manifest
        // that already has a Part-A-generated E-Way Bill gets its transport details
        // completed. Defensively wrapped even though EwayBillServiceImpl itself never
        // throws for a provider-side reason — a manifest dispatch must never fail
        // because of an E-Way Bill provider outage.
        try {
            if (vehicleId != null) {
                // Part-B needs a vehicle number; a co-loader trip without our vehicle has none to give.
                ewayBillService.triggerPartBForShipments(dispatchedShipmentIds, vehicleService.getById(vehicleId).getVehicleNumber(),
                        null, "ROAD");
            }
        } catch (Exception e) {
            log.error("E-Way Bill Part-B trigger failed for manifest {} ({}) in company {}: {}",
                    saved.getManifestNumber(), saved.getId(), companyId, e.getMessage());
        }

        log.info("Manifest {} ({}) dispatched in company {} with vehicle {} by {}",
                saved.getManifestNumber(), saved.getId(), companyId, vehicleId, currentActor());

        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('MANIFEST_DISPATCH')")
    public DispatchOtpIssued requestDispatchOtp(UUID manifestId, UUID driverUserId) {
        UUID companyId = requireCompany();
        Manifest manifest = loadOrThrow(manifestId, companyId);
        // Same "any real user of this company" driver check dispatch() itself uses.
        User driver = userService.getById(driverUserId);
        String mobile = driver.getMobile();
        if (mobile == null || mobile.isBlank()) {
            throw new BusinessRuleException(
                    "%s has no mobile number on file — add one before requesting an OTP."
                            .formatted(driver.effectiveDisplayName()));
        }

        int expiryMinutes = Optional.ofNullable(companySettingsService.get().getOtpExpiryMinutes())
                .orElse(DEFAULT_OTP_EXPIRY_MINUTES);
        String otp = generateOtp();
        manifest.issueDispatchOtp(driverUserId, passwordEncoder.encode(otp),
                Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES));
        manifestRepository.save(manifest);

        sendOtpSms(companyId, mobile, otp, expiryMinutes);

        log.info("Dispatch OTP issued for manifest {} ({}) driver {} in company {} by {}",
                manifest.getManifestNumber(), manifestId, driverUserId, companyId, currentActor());

        return new DispatchOtpIssued(maskMobile(mobile), expiryMinutes);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('MANIFEST_DISPATCH')")
    public void verifyDispatchOtp(UUID manifestId, UUID driverUserId, String otp) {
        UUID companyId = requireCompany();
        Manifest manifest = loadOrThrow(manifestId, companyId);
        boolean codeMatched = manifest.getDispatchOtpHash() != null
                && otp != null && passwordEncoder.matches(otp.trim(), manifest.getDispatchOtpHash());
        manifest.registerOtpVerificationAttempt(driverUserId, codeMatched);
        manifestRepository.save(manifest);

        log.info("Dispatch OTP verified for manifest {} ({}) driver {} in company {} by {}",
                manifest.getManifestNumber(), manifestId, driverUserId, companyId, currentActor());
    }

    /** Sends the raw OTP over the company's configured SMS channel (Communication
     *  Center) — the same {@code SmsProvider} bean {@code CommunicationSendServiceImpl}
     *  uses for shipment-event SMS, bypassing its template/log/retry machinery since an
     *  OTP must be generated, sent and confirmed synchronously within one request rather
     *  than queued. Falls back to log-only credentials (accepted by {@code
     *  LogOnlySmsProvider} regardless) when the company hasn't configured SMS at all —
     *  the same "log instead of a real send" gap every other unwired notification in this
     *  project already has. */
    private void sendOtpSms(UUID companyId, String mobile, String otp, int expiryMinutes) {
        Optional<CommunicationSetting> setting =
                communicationSettingService.findEnabled(companyId, CommunicationChannel.SMS);
        SmsProvider.SmsCredentials credentials = setting.map(s -> {
            Map<String, String> config = CommunicationConfigJson.read(objectMapper, s.getConfigJson());
            return new SmsProvider.SmsCredentials(s.getProvider(), config.get("apiUrl"), s.getSecret(),
                    config.get("senderId"));
        }).orElseGet(() -> new SmsProvider.SmsCredentials(null, null, null, null));

        String body = "Your OTP to dispatch this trip is %s. Valid for %d minute(s). Do not share it with anyone."
                .formatted(otp, expiryMinutes);
        try {
            smsProvider.send(new SmsProvider.SmsMessage(mobile, body), credentials);
        } catch (ProviderSendException e) {
            throw new BusinessRuleException("Could not send the OTP SMS: " + e.getMessage());
        }
    }

    private static String generateOtp() {
        return String.valueOf(1000 + OTP_RANDOM.nextInt(9000));
    }

    private static String maskMobile(String mobile) {
        return mobile.length() < 4 ? "****" : "*".repeat(mobile.length() - 4) + mobile.substring(mobile.length() - 4);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public void removeShipment(UUID manifestId, UUID shipmentId) {
        UUID companyId = requireCompany();
        Manifest manifest = loadOrThrow(manifestId, companyId);

        if (manifest.isDispatched()) {
            throw new BusinessRuleException(
                    "Manifest %s has already been dispatched.".formatted(manifest.getManifestNumber()));
        }

        shipmentService.detachFromManifest(shipmentId, manifestId);

        log.info("Shipment {} removed from manifest {} ({}) in company {} by {}",
                shipmentId, manifest.getManifestNumber(), manifestId, companyId, currentActor());
        auditService.record(AuditAction.MANIFEST_SHIPMENT_REMOVED, ENTITY, manifestId,
                Map.of("shipmentId", shipmentId.toString()));
    }

    @Override
    @Transactional
    public boolean completeIfAllReceived(UUID manifestId) {
        UUID companyId = requireCompany();
        Manifest manifest = manifestRepository.findByIdWithinCompany(manifestId, companyId).orElse(null);
        if (manifest == null || manifest.getStatus() != ManifestStatus.DISPATCHED) {
            return false;
        }
        boolean stillOnTheRoad = shipmentService.findByManifestIds(List.of(manifestId)).stream()
                .anyMatch(s -> s.getStatus() == com.courier.modules.shipment.domain.ShipmentStatus.DISPATCHED);
        if (stillOnTheRoad) {
            return false;
        }
        manifest.complete();
        manifestRepository.save(manifest);
        log.info("Manifest {} ({}) received in full in company {}", manifest.getManifestNumber(), manifestId, companyId);
        return true;
    }

    private Manifest loadOrThrow(UUID id, UUID companyId) {
        return manifestRepository.findByIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    private String nextManifestNumber(UUID companyId) {
        for (int attempt = 0; attempt < NUMBER_ATTEMPTS; attempt++) {
            String candidate = ManifestNumberGenerator.generate();
            if (!manifestRepository.existsByCompanyIdAndManifestNumber(companyId, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique manifest number in " + NUMBER_ATTEMPTS + " attempts");
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Manifests belong to a company."));
    }

    private String currentActor() {
        return com.courier.shared.security.SecurityUtils.getCurrentUser()
                .map(u -> u.email() == null ? u.userId().toString() : u.email())
                .orElse("system");
    }
}
