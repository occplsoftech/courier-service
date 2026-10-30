package com.courier.modules.shipment.application;

import com.courier.modules.communication.application.CommunicationConfigJson;
import com.courier.modules.communication.application.CommunicationSettingService;
import com.courier.modules.communication.application.provider.ProviderSendException;
import com.courier.modules.communication.application.provider.SmsProvider;
import com.courier.modules.communication.domain.CommunicationChannel;
import com.courier.modules.communication.domain.CommunicationSetting;
import com.courier.modules.crossing.application.CrossingService;
import com.courier.modules.customer.application.CustomerService;
import com.courier.modules.districtfreight.application.FreightCalculationResult;
import com.courier.modules.districtfreight.application.FreightCalculationService;
import com.courier.modules.ewaybill.application.EwayBillService;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.PaymentModeService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.PaymentMode;
import com.courier.modules.master.application.RouteService;
import com.courier.modules.master.domain.Route;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.pricing.application.PricingEngine;
import com.courier.modules.pricing.application.PricingProperties;
import com.courier.modules.pricing.application.PricingResult;
import com.courier.modules.pricing.application.command.PricingCommand;
import com.courier.modules.pricing.domain.ChargeableWeightCalculator;
import com.courier.modules.pricing.domain.RoundingRule;
import com.courier.modules.pricing.domain.VolumetricCalculator;
import com.courier.modules.pricing.domain.WeightCalculator;
import com.courier.modules.rate.application.RateService;
import com.courier.modules.rate.domain.Rate;
import com.courier.modules.shipment.application.command.CreateShipmentCommand;
import com.courier.modules.shipment.application.command.ShipmentItemCommand;
import com.courier.modules.shipment.application.command.UpdateShipmentCommand;
import com.courier.modules.shipment.application.event.ShipmentEvent;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import com.courier.modules.shipment.domain.BranchShipmentSequenceRepository;
import com.courier.modules.shipment.domain.ReleasedShipmentNumberRepository;
import com.courier.modules.shipment.domain.CompanyDrsSequenceRepository;
import com.courier.modules.shipment.domain.CompanyShipmentSequenceRepository;
import com.courier.modules.shipment.domain.DeliveryAssignment;
import com.courier.modules.shipment.domain.FlowType;
import com.courier.modules.shipment.domain.DeliveryAssignmentRepository;
import com.courier.modules.shipment.domain.DeliveryAssignmentStatus;
import com.courier.modules.shipment.domain.DeliveryDispatchOtp;
import com.courier.modules.shipment.domain.DeliveryDispatchOtpRepository;
import com.courier.modules.shipment.domain.DeliveryType;
import com.courier.modules.shipment.domain.BranchCommissionSummary;
import com.courier.modules.shipment.domain.BranchPerformanceSummary;
import com.courier.modules.shipment.domain.PackageId;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentCharge;
import com.courier.modules.shipment.domain.ShipmentChargeRepository;
import com.courier.modules.shipment.domain.ShipmentCriteria;
import com.courier.modules.shipment.domain.ShipmentDocument;
import com.courier.modules.shipment.domain.ShipmentDocumentRepository;
import com.courier.modules.shipment.domain.ShipmentDocumentType;
import com.courier.modules.shipment.domain.ShipmentItem;
import com.courier.modules.shipment.domain.ShipmentItemRepository;
import com.courier.modules.shipment.domain.ShipmentAsset;
import com.courier.modules.shipment.domain.ShipmentAssetRepository;
import com.courier.modules.shipment.domain.ShipmentAssetType;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentSpecifications;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.shipment.domain.ShipmentStatusHistory;
import com.courier.modules.shipment.domain.ShipmentSummaryStats;
import com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository;
import com.courier.modules.shipment.domain.ShipmentType;
import com.courier.modules.shipment.domain.VendorAuditRow;
import com.courier.modules.support.application.TicketCategoryService;
import com.courier.modules.support.application.TicketService;
import com.courier.modules.support.application.command.CreateTicketCommand;
import com.courier.modules.support.domain.Ticket;
import com.courier.modules.support.domain.TicketCategory;
import com.courier.modules.support.domain.TicketPriority;
import com.courier.shared.activity.application.ActivityContext;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.domain.TimeOrderedUuid;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Comparator;
import com.courier.modules.finance.application.command.BookingDebitCommand;
import com.courier.modules.finance.application.command.ShipmentReversalCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shipment Booking use cases. Per-method {@code @PreAuthorize} gates by JWT role tier —
 * see {@link ShipmentService} for the audiences. Company isolation is the project's usual
 * two layers: the Hibernate filter, plus {@code findByIdWithinCompany} on every single-row
 * load, so a foreign shipment id 404s.
 *
 * <p>This class orchestrates; it does not re-decide. Sender/receiver are plain text typed
 * at booking time, stored on the shipment row exactly as typed — {@link Shipment} itself
 * still carries no {@code customerId} FK. On {@link #create}, both are separately
 * find-or-created as {@code Customer} master data by exact mobile match
 * ({@link CustomerService#findOrCreateForBooking}), purely so the next booking's
 * name/mobile search surfaces them — that write never affects the shipment's own fields
 * and its failure is not expected (see the method for why it isn't wrapped defensively).
 * Every cross-module id it does still carry
 * (branch, service/package/payment type, route, rate) is validated by the module that owns
 * it — most of that validation happens inside one call to
 * {@link PricingEngine#calculate}, which already runs route, serviceability, rate and
 * weight-slab validation. See the class-level notes on each private helper for exactly
 * which module answers which question.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentServiceImpl implements ShipmentService {

    private static final String ENTITY = "Shipment";

    private static final String WRITERS = "hasAnyRole('" + Roles.COMPANY_ADMIN + "', '"
            + Roles.BRANCH_MANAGER + "', '" + Roles.OPERATOR + "')";
    private static final String READERS = "isAuthenticated()";

    /** Narrower than {@link #WRITERS} on purpose — {@link #overrideStatus} bypasses the
     *  normal per-screen flow entirely, so it stays out of an {@code OPERATOR}'s reach. */
    private static final String OVERRIDE_WRITERS =
            "hasAnyRole('" + Roles.COMPANY_ADMIN + "', '" + Roles.BRANCH_MANAGER + "')";

    private static final int DEFAULT_OTP_EXPIRY_MINUTES = 5;
    private static final SecureRandom OTP_RANDOM = new SecureRandom();

    /** The YYMM prefix of a tracking number — UTC, so the month never depends on the
     *  server's local timezone. */
    private static final DateTimeFormatter YEAR_MONTH =
            DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentItemRepository itemRepository;
    private final ShipmentChargeRepository chargeRepository;
    private final ShipmentStatusHistoryRepository historyRepository;
    private final ShipmentDocumentRepository documentRepository;
    private final DeliveryAssignmentRepository deliveryAssignmentRepository;
    private final BranchShipmentSequenceRepository branchShipmentSequenceRepository;
    private final ReleasedShipmentNumberRepository releasedShipmentNumberRepository;
    private final CompanyShipmentSequenceRepository companyShipmentSequenceRepository;
    private final CompanyDrsSequenceRepository companyDrsSequenceRepository;

    private final ServiceTypeService serviceTypeService;
    private final PackageTypeService packageTypeService;
    private final PaymentModeService paymentModeService;
    private final RateService rateService;
    private final RouteService routeService;
    private final PricingEngine pricingEngine;
    private final PricingProperties pricingProperties;
    private final WalletService walletService;
    private final com.courier.modules.company.application.UserService userService;
    private final com.courier.modules.company.application.BranchService branchService;
    private final com.courier.modules.shipment.domain.BranchLookupPort branchLookup;
    private final com.courier.modules.master.domain.PaymentModeRepository paymentModeRepository;
    private final com.courier.modules.master.domain.ServiceTypeRepository serviceTypeRepository;
    private final com.courier.modules.master.domain.PackageTypeRepository packageTypeRepository;
    private final CustomerService customerService;
    private final CrossingService crossingService;
    private final TicketService ticketService;
    private final TicketCategoryService ticketCategoryService;
    private final EwayBillService ewayBillService;
    private final FreightCalculationService freightCalculationService;
    private final com.courier.modules.pricing.application.calculator.ApplicableChargesCalculator applicableChargesCalculator;

    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final FileStoragePort fileStoragePort;
    private final ShipmentAssetRepository shipmentAssetRepository;

    private final DeliveryDispatchOtpRepository deliveryDispatchOtpRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.courier.modules.company.application.CompanySettingsService companySettingsService;
    private final CommunicationSettingService communicationSettingService;
    private final SmsProvider smsProvider;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------- create

    @Override
    @Transactional
    // Narrower than WRITERS: booking is the one method named in the Menu + Permission
    // Management example (Shipment Booking -> SHIPMENT_CREATE), retrofit onto the
    // permission catalogue rather than the coarser role-tier check every other WRITERS
    // method here still uses.
    @PreAuthorize("hasAuthority('SHIPMENT_CREATE')")
    public Shipment create(CreateShipmentCommand command) {
        UUID companyId = requireCompany();

        List<ShipmentItem> items = buildItems(command.items(), command.actualWeight(),
                command.length(), command.width(), command.height());
        WeightSummary weight = summariseWeight(items);
        requireWithinPackageTypeCeiling(command.packageTypeId(), weight.actualWeight());

        LocalDate bookingDate = command.bookingDate() == null ? LocalDate.now() : command.bookingDate();

        boolean crossing = Boolean.TRUE.equals(command.crossing());
        if (crossing && (command.crossingBranchIds() == null || command.crossingBranchIds().isEmpty())) {
            throw new BusinessRuleException(
                    "Pick at least one branch this shipment is crossing through, or turn Crossing off.");
        }
        UUID firstCrossingBranch = crossing ? command.crossingBranchIds().get(0) : null;

        com.courier.modules.company.domain.Branch bookingBranch = branchService.getById(command.bookingBranchId());

        // MSRTC books straight to a delivery branch picked (or resolved from the pincode) at
        // the counter, so it is the one flow that writes deliveryBranchId at booking. STANDARD
        // is untouched: its delivery branch is still assigned later, by Load Sheet.
        FlowType flowType = command.flowType() == null ? FlowType.STANDARD : command.flowType();
        UUID msrtcDeliveryBranchId = null;
        if (flowType == FlowType.MSRTC) {
            msrtcDeliveryBranchId = requireMsrtcDeliveryBranch(companyId, command.bookingBranchId(),
                    command.deliveryBranchId(), crossing);
        }

        String manualShipmentNumber = command.manualShipmentNumber() == null
                ? null : command.manualShipmentNumber().trim();
        if (manualShipmentNumber != null && !manualShipmentNumber.isEmpty()
                && shipmentRepository.existsByCompanyIdAndShipmentNumber(companyId, manualShipmentNumber)) {
            throw new BusinessRuleException(
                    "Shipment number '%s' is already in use.".formatted(manualShipmentNumber));
        }

        // E-Way Bill gate, ahead of pricing and AWB generation, exactly where the brief's
        // own booking flow places it: no-op when invoiceValue does not exceed the
        // company's threshold, otherwise requires command.ewayBill() to carry at least an
        // invoice number/date — the minimum a Part-A request needs. Only missing *data*
        // blocks the booking; the actual provider call (below, after the shipment has an
        // id) never does — its failure is caught and persisted as FAILED, retryable later.
        boolean ewayBillRequired = ewayBillService.isRequired(command.invoiceValue());
        ewayBillService.requireBookingData(command.invoiceValue(), command.ewayBill());

        // Freight is District Level Freight's own job now, not the Pricing Engine's —
        // authoritative, mandatory, never trusted from the frontend. Computed ahead of
        // `priceIt` (which still supplies fuel/handling/insurance/discount/round-off/GST%,
        // untouched) so a missing configuration blocks the booking before anything else
        // is validated. See `FreightCalculationServiceImpl` for the full sequence.
        FreightCalculationResult freightCalc = freightCalculationService.calculate(
                command.bookingBranchId(), command.deliveryPincode(), command.destinationAreaId(),
                weight.chargeableWeight(), command.businessCustomerId(), command.declaredValue(),
                new FreightCalculationService.TariffInput(command.serviceTypeId(), command.packageTypeId(),
                        totalQuantity(items), bookingDate));
        requireRateNotDecreased(command.ratePerKgOverride(), freightCalc);
        requireCustomerExtraCharges(command.otherCharges(), freightCalc);

        // Delivery Branch is no longer picked, decided, or even resolved at booking —
        // Load Sheet is the only place a shipment's deliveryBranchId gets written, by
        // destination city (see ShipmentServiceImpl.attachToManifest), or never at all
        // for Direct Company Delivery. Pricing (Route/Rate's old branch-pair matching)
        // runs with no delivery branch every time now; District Level Freight (the
        // authoritative freight figure) never needed one — it keys off district, not
        // branch.
        PricingResult priced = priceIt(command.bookingBranchId(), null,
                command.pickupPincode(), command.deliveryPincode(), command.serviceTypeId(),
                command.packageTypeId(), command.paymentModeId(), weight.chargeableWeight(),
                command.declaredValue(), bookingDate, command.freightFactorOverride(),
                weight.actualWeight(), totalQuantity(items));

        PaymentMode paymentMode = paymentModeService.getById(command.paymentModeId());
        ServiceType serviceType = serviceTypeService.getById(command.serviceTypeId());

        BigDecimal otherCharges = command.otherCharges() == null ? BigDecimal.ZERO : command.otherCharges();
        BigDecimal appointmentDeliveryCharge = command.appointmentDeliveryCharge() == null
                ? BigDecimal.ZERO : command.appointmentDeliveryCharge();
        boolean insuranceApplicable = Boolean.TRUE.equals(command.insuranceApplicable());
        DeliveryType deliveryType = command.deliveryType() == null ? DeliveryType.DOOR : command.deliveryType();
        BigDecimal doorDeliveryCharge = deliveryType == DeliveryType.DOOR && command.doorDeliveryCharge() != null
                ? command.doorDeliveryCharge() : BigDecimal.ZERO;
        BigDecimal toPayCharge = toPayChargeFor(paymentMode);
        BigDecimal netAmount = netAmountWithOtherCharges(priced, otherCharges, command.odaCharge(), bookingBranch,
                freightCalc, command.ratePerKgOverride(), appointmentDeliveryCharge, insuranceApplicable,
                doorDeliveryCharge, toPayCharge, command.invoiceValue());

        if (paymentMode.isCollectAtBooking()) {
            requireSufficientBalance(command.bookingBranchId(), netAmount);
        }

        Shipment shipment = Shipment.builder()
                .shipmentNumber(manualShipmentNumber != null && !manualShipmentNumber.isEmpty()
                        ? manualShipmentNumber
                        : nextShipmentNumber(command.bookingBranchId(), bookingBranch.getBranchCode()))
                .trackingNumber(nextTrackingNumber(companyId))
                .bookingDate(bookingDate)
                .bookingBranchId(command.bookingBranchId())
                .flowType(flowType)
                .deliveryBranchId(msrtcDeliveryBranchId)
                .fromCity(bookingBranch.getCity())
                .toCity(freightCalc.destinationCityName())
                .currentLocationId(command.bookingBranchId())
                .nextLocationId(flowType == FlowType.MSRTC ? msrtcDeliveryBranchId
                        : (crossing ? firstCrossingBranch : null))
                .pickupPincode(command.pickupPincode())
                .deliveryPincode(command.deliveryPincode())
                .destinationAreaId(command.destinationAreaId())
                .businessCustomerId(command.businessCustomerId())
                .senderName(command.senderName())
                .senderAddress(command.senderAddress())
                .senderContact(command.senderContact())
                .receiverName(command.receiverName())
                .receiverAddress(command.receiverAddress())
                .receiverContact(command.receiverContact())
                .serviceTypeId(command.serviceTypeId())
                .packageTypeId(command.packageTypeId())
                .paymentModeId(command.paymentModeId())
                .shipmentType(command.shipmentType() == null ? ShipmentType.NON_DOCUMENT : command.shipmentType())
                .expectedDeliveryDate(expectedDeliveryDate(bookingDate, serviceType))
                .actualWeight(weight.actualWeight())
                .volumetricWeight(weight.volumetricWeight())
                .chargeableWeight(weight.chargeableWeight())
                .declaredValue(command.declaredValue())
                .numberOfPackages(command.numberOfPackages() == null ? 1 : command.numberOfPackages())
                .status(ShipmentStatus.BOOKED)
                .remarks(command.remarks())
                .invoiceValue(command.invoiceValue())
                .ewayBillRequired(ewayBillRequired)
                .appointmentDelivery(Boolean.TRUE.equals(command.appointmentDelivery()))
                .appointmentDate(command.appointmentDate())
                .appointmentTimeSlot(command.appointmentTimeSlot())
                .insuranceApplicable(insuranceApplicable)
                .deliveryType(deliveryType)
                .build();
        shipment.applyInvariants();
        Shipment saved = shipmentRepository.save(shipment);

        if (ewayBillRequired) {
            ewayBillService.generatePartAForShipment(saved.getId(), command.ewayBill(),
                    ewayBillContext(command.senderName(), command.senderAddress(), command.pickupPincode(),
                            command.receiverName(), command.receiverAddress(), command.deliveryPincode(), items));
        } else if (command.ewayBill() != null) {
            ewayBillService.upsertForShipment(saved.getId(), command.ewayBill());
        }

        // Reuse-or-create master data for next time's search suggestion; not a shipment
        // field, so a bad name/mobile here fails the whole booking same as any other
        // write in this transaction — no separate try/catch carve-out for it.
        customerService.findOrCreateForBooking(command.senderName(), command.senderContact());
        customerService.findOrCreateForBooking(command.receiverName(), command.receiverContact());

        persistItems(saved, companyId, items);
        persistCharges(saved, companyId, priced, otherCharges, command.odaCharge(), bookingBranch, freightCalc,
                command.ratePerKgOverride(), appointmentDeliveryCharge, insuranceApplicable, doorDeliveryCharge, toPayCharge,
                command.invoiceValue());
        appendHistory(saved, companyId, null, ShipmentStatus.BOOKED, "Shipment booked");

        if (crossing) {
            crossingService.createLegs(saved.getId(), command.crossingBranchIds(), command.crossingCharge());
        }

        log.info("Shipment {} ({}) booked in company {} by {}", saved.getShipmentNumber(),
                saved.getId(), companyId, currentActor());
        auditService.record(AuditAction.SHIPMENT_BOOKED, ENTITY, saved.getId(),
                Map.of("shipmentNumber", saved.getShipmentNumber(),
                        "trackingNumber", saved.getTrackingNumber(),
                        "netAmount", netAmount.toPlainString()));

        if (paymentMode.isCollectAtBooking()) {
            // Commission itself is credited later, when the shipment is in-scanned at its
            // own final delivery branch — see ShipmentEvent.InScanCommissionEarned,
            // published from scanOneIn.
            eventPublisher.publishEvent(new ShipmentEvent.PrepaidBookingConfirmed(
                    saved.getId(), companyId, saved.getBookingBranchId(), saved.getShipmentNumber(),
                    netAmount, Instant.now()));
        }
        eventPublisher.publishEvent(new ShipmentEvent.Booked(saved.getId(), companyId, Instant.now()));

        return saved;
    }


    // ------------------------------------------------------------------- portal booking

    @Override
    @Transactional
    public Shipment createFromPortalBooking(com.courier.modules.shipment.application.command.PortalShipmentCommand c) {
        UUID companyId = requireCompany();
        var branch = branchLookup.findBranch(c.bookingBranchId(), companyId).filter(b -> b.active())
                .orElseThrow(() -> new BusinessRuleException("The booking branch is not available."));
        PaymentMode paymentMode = portalPaymentMode(companyId, c.paymentKind());
        ServiceType serviceType = portalServiceType(companyId, c.serviceCode());
        com.courier.modules.master.domain.PackageType packageType = packageTypeRepository.findAll().stream()
                .filter(p -> companyId.equals(p.getCompanyId()) && p.isActive())
                .min(java.util.Comparator.comparing(com.courier.modules.master.domain.PackageType::isDocumentType)
                        .thenComparing(p -> p.getCode() == null ? "" : p.getCode()))
                .orElseThrow(() -> new BusinessRuleException("No active package type is set up."));

        List<ShipmentItem> items = buildItems(List.of(), c.actualWeight(), c.length(), c.breadth(), c.height());
        WeightSummary weight = summariseWeight(items);
        LocalDate bookingDate = LocalDate.now();

        Shipment shipment = Shipment.builder()
                .shipmentNumber(nextShipmentNumber(c.bookingBranchId(), branch.branchCode()))
                .trackingNumber(nextTrackingNumber(companyId))
                .bookingDate(bookingDate)
                .bookingBranchId(c.bookingBranchId())
                .flowType(FlowType.STANDARD)
                .fromCity(c.fromCity())
                .toCity(c.toCity())
                .currentLocationId(c.bookingBranchId())
                .pickupPincode(c.pickupPincode())
                .deliveryPincode(c.deliveryPincode())
                .businessCustomerId(c.businessCustomerId())
                .senderName(c.senderName()).senderAddress(c.senderAddress()).senderContact(c.senderContact())
                .receiverName(c.receiverName()).receiverAddress(c.receiverAddress()).receiverContact(c.receiverContact())
                .serviceTypeId(serviceType.getId())
                .packageTypeId(packageType.getId())
                .paymentModeId(paymentMode.getId())
                .shipmentType(ShipmentType.NON_DOCUMENT)
                .expectedDeliveryDate(expectedDeliveryDate(bookingDate, serviceType))
                .actualWeight(weight.actualWeight())
                .volumetricWeight(weight.volumetricWeight())
                .chargeableWeight(c.chargeableWeight() == null ? weight.chargeableWeight() : c.chargeableWeight())
                .declaredValue(c.declaredValue())
                .numberOfPackages(Math.max(1, c.numberOfPackages()))
                .status(ShipmentStatus.BOOKED)
                .remarks(("Customer portal booking " + c.reference() + (c.contents() == null ? "" : ". Contents: " + c.contents())))
                .deliveryType(DeliveryType.DOOR)
                .build();
        shipment.applyInvariants();
        Shipment saved = shipmentRepository.save(shipment);
        persistItems(saved, companyId, items);

        BigDecimal zero = BigDecimal.ZERO;
        BigDecimal known = nz(c.freight()).add(nz(c.fuel())).add(nz(c.handling())).add(nz(c.oda())).add(nz(c.insurance()))
                .add(nz(c.gst())).add(nz(c.other()));
        ShipmentCharge charge = ShipmentCharge.builder().build();
        charge.setCompanyId(companyId);
        charge.setShipmentId(saved.getId());
        charge.setFreight(nz(c.freight()));
        charge.setFuelCharge(nz(c.fuel()));
        charge.setHandlingCharge(nz(c.handling()));
        charge.setOdaCharge(nz(c.oda()));
        charge.setInsuranceCharge(nz(c.insurance()));
        charge.setGstAmount(nz(c.gst()));
        charge.setOtherCharges(nz(c.other()));
        charge.setRoundOff(c.total().subtract(known));
        charge.setNetAmount(c.total());
        charge.setTotalCommission(zero);
        chargeRepository.save(charge);

        appendHistory(saved, companyId, null, ShipmentStatus.BOOKED, "Booked from the customer portal; awaiting pickup");
        auditService.record(AuditAction.SHIPMENT_BOOKED, ENTITY, saved.getId(),
                Map.of("shipmentNumber", saved.getShipmentNumber(), "trackingNumber", saved.getTrackingNumber(),
                        "netAmount", c.total().toPlainString(), "source", "CUSTOMER_PORTAL"));
        log.info("Shipment {} issued for portal booking {} at branch {}", saved.getShipmentNumber(), c.reference(), branch.branchCode());
        // Booked only: the branch wallet is not debited (the customer paid the quote), so no PrepaidBookingConfirmed.
        eventPublisher.publishEvent(new ShipmentEvent.Booked(saved.getId(), companyId, Instant.now()));
        return saved;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** The company's payment mode for a portal booking, found by what it does (not by its code, which the company chose). */
    private PaymentMode portalPaymentMode(UUID companyId, String kind) {
        java.util.function.Predicate<PaymentMode> match = switch (kind == null ? "PAID" : kind) {
            case "TOPAY" -> m -> m.isCollectAtDelivery() && !m.isCashOnDelivery();
            case "COD" -> PaymentMode::isCashOnDelivery;
            case "CREDIT" -> PaymentMode::isRequiresCreditAccount;
            default -> m -> m.isCollectAtBooking() && !m.isCollectAtDelivery() && !m.isCashOnDelivery() && !m.isRequiresCreditAccount();
        };
        return paymentModeRepository.findAll().stream()
                .filter(m -> companyId.equals(m.getCompanyId()) && m.isActive() && match.test(m))
                .min(java.util.Comparator.comparing(m -> m.getCode() == null ? "" : m.getCode()))
                .orElseThrow(() -> new BusinessRuleException("No " + kind + " payment mode is set up for this company."));
    }

    /** The quoted service's type when its code matches ("OCCPL_SURFACE" -> SURFACE), else the company's first active one. */
    private ServiceType portalServiceType(UUID companyId, String serviceCode) {
        List<ServiceType> active = serviceTypeRepository.findAll().stream()
                .filter(t -> companyId.equals(t.getCompanyId()) && t.isActive()).toList();
        String wanted = serviceCode == null ? "" : serviceCode.replaceFirst("^OCCPL_", "");
        return active.stream().filter(t -> t.getCode() != null && t.getCode().equalsIgnoreCase(wanted)).findFirst()
                .or(() -> active.stream().min(java.util.Comparator.comparing(ServiceType::isExpress)
                        .thenComparing(t -> t.getPriority() == null ? 0 : t.getPriority())))
                .orElseThrow(() -> new BusinessRuleException("No active service type is set up."));
    }

    // ------------------------------------------------------------------- update

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public Shipment update(UUID id, UpdateShipmentCommand command) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(id, companyId);
        requireCurrentVersion(shipment, command.expectedVersion());

        if (!shipment.isEditable()) {
            throw new BusinessRuleException("Shipment %s can only be edited while BOOKED."
                    .formatted(shipment.getShipmentNumber()));
        }

        // Charges are an admin-only edit after booking: anyone else's edit keeps every charge
        // input exactly as booked (and the admin's Rate/KG, if any) and only re-derives from
        // the changed weight/route. The admin changes Rate/KG through updateRatePerKg.
        boolean chargesPinned = !SecurityUtils.requireCurrentUser().hasRole(Roles.COMPANY_ADMIN);
        if (chargesPinned) {
            ShipmentCharge stored = chargeRepository.findByShipmentIdWithinCompany(id, companyId).orElse(null);
            if (stored != null) {
                command = pinCharges(command, stored);
            }
        }

        List<ShipmentItem> items = buildItems(command.items(), command.actualWeight(),
                command.length(), command.width(), command.height());
        WeightSummary weight = summariseWeight(items);
        requireWithinPackageTypeCeiling(command.packageTypeId(), weight.actualWeight());

        LocalDate bookingDate = command.bookingDate() == null ? shipment.getBookingDate() : command.bookingDate();

        // Same gate as create() — an edit that raises invoiceValue past the threshold
        // must not leave the shipment BOOKED without at least the data to attempt one.
        boolean ewayBillRequired = ewayBillService.isRequired(command.invoiceValue());
        ewayBillService.requireBookingData(command.invoiceValue(), command.ewayBill());

        FreightCalculationResult freightCalc = freightCalculationService.calculate(
                shipment.getBookingBranchId(), command.deliveryPincode(), command.destinationAreaId(),
                weight.chargeableWeight(), shipment.getBusinessCustomerId(), command.declaredValue(),
                new FreightCalculationService.TariffInput(command.serviceTypeId(), command.packageTypeId(),
                        totalQuantity(items), bookingDate));
        if (!chargesPinned) {
            requireRateNotDecreased(command.ratePerKgOverride(), freightCalc);
            requireCustomerExtraCharges(command.otherCharges(), freightCalc);
        }

        // No delivery branch to resolve any more — pricing runs with none, same as
        // create(). A BOOKED shipment's deliveryBranchId stays whatever it already was
        // (always null pre-Load-Sheet, since isEditable() only allows this method to run
        // before any manifest has touched the shipment).
        PricingResult priced = priceIt(shipment.getBookingBranchId(), null,
                command.pickupPincode(), command.deliveryPincode(), command.serviceTypeId(),
                command.packageTypeId(), command.paymentModeId(), weight.chargeableWeight(),
                command.declaredValue(), bookingDate, command.freightFactorOverride(),
                weight.actualWeight(), totalQuantity(items));

        ServiceType serviceType = serviceTypeService.getById(command.serviceTypeId());

        shipment.setToCity(freightCalc.destinationCityName());
        shipment.setPickupPincode(command.pickupPincode());
        shipment.setDeliveryPincode(command.deliveryPincode());
        shipment.setDestinationAreaId(command.destinationAreaId());
        shipment.setSenderName(command.senderName());
        shipment.setSenderAddress(command.senderAddress());
        shipment.setSenderContact(command.senderContact());
        shipment.setReceiverName(command.receiverName());
        shipment.setReceiverAddress(command.receiverAddress());
        shipment.setReceiverContact(command.receiverContact());
        shipment.setServiceTypeId(command.serviceTypeId());
        shipment.setPackageTypeId(command.packageTypeId());
        shipment.setPaymentModeId(command.paymentModeId());
        shipment.setShipmentType(command.shipmentType());
        shipment.setBookingDate(bookingDate);
        shipment.setExpectedDeliveryDate(expectedDeliveryDate(bookingDate, serviceType));
        shipment.setActualWeight(weight.actualWeight());
        shipment.setVolumetricWeight(weight.volumetricWeight());
        shipment.setChargeableWeight(weight.chargeableWeight());
        shipment.setDeclaredValue(command.declaredValue());
        shipment.setNumberOfPackages(command.numberOfPackages() == null ? 1 : command.numberOfPackages());
        shipment.setRemarks(command.remarks());
        shipment.setInvoiceValue(command.invoiceValue());
        shipment.setEwayBillRequired(ewayBillRequired);
        shipment.setAppointmentDelivery(Boolean.TRUE.equals(command.appointmentDelivery()));
        shipment.setAppointmentDate(command.appointmentDate());
        shipment.setAppointmentTimeSlot(command.appointmentTimeSlot());
        boolean insuranceApplicable = Boolean.TRUE.equals(command.insuranceApplicable());
        shipment.setInsuranceApplicable(insuranceApplicable);
        DeliveryType deliveryType = command.deliveryType() == null ? DeliveryType.DOOR : command.deliveryType();
        shipment.setDeliveryType(deliveryType);
        shipment.applyInvariants();
        Shipment saved = shipmentRepository.save(shipment);

        if (ewayBillRequired) {
            ewayBillService.generatePartAForShipment(saved.getId(), command.ewayBill(),
                    ewayBillContext(command.senderName(), command.senderAddress(), command.pickupPincode(),
                            command.receiverName(), command.receiverAddress(), command.deliveryPincode(), items));
        } else if (command.ewayBill() != null) {
            ewayBillService.upsertForShipment(saved.getId(), command.ewayBill());
        }

        BigDecimal otherCharges = command.otherCharges() == null ? BigDecimal.ZERO : command.otherCharges();
        BigDecimal appointmentDeliveryCharge = command.appointmentDeliveryCharge() == null
                ? BigDecimal.ZERO : command.appointmentDeliveryCharge();
        BigDecimal doorDeliveryCharge = deliveryType == DeliveryType.DOOR && command.doorDeliveryCharge() != null
                ? command.doorDeliveryCharge() : BigDecimal.ZERO;
        BigDecimal toPayCharge = toPayChargeFor(paymentModeService.getById(command.paymentModeId()));
        com.courier.modules.company.domain.Branch bookingBranch =
                branchService.getById(shipment.getBookingBranchId());

        itemRepository.deleteAllByShipmentIdAndCompanyId(saved.getId(), companyId);
        persistItems(saved, companyId, items);
        replaceCharges(saved, companyId, priced, otherCharges, command.odaCharge(), bookingBranch, freightCalc,
                command.ratePerKgOverride(), appointmentDeliveryCharge, insuranceApplicable, doorDeliveryCharge, toPayCharge,
                command.invoiceValue());

        log.info("Shipment {} ({}) updated in company {} by {}", saved.getShipmentNumber(),
                saved.getId(), companyId, currentActor());
        auditService.record(AuditAction.SHIPMENT_UPDATED, ENTITY, saved.getId(),
                Map.of("shipmentNumber", saved.getShipmentNumber(),
                        "netAmount", netAmountWithOtherCharges(priced, otherCharges, command.odaCharge(), bookingBranch,
                                freightCalc, command.ratePerKgOverride(), appointmentDeliveryCharge, insuranceApplicable,
                                doorDeliveryCharge, toPayCharge, command.invoiceValue())
                                .toPlainString()));

        return saved;
    }

    private static UpdateShipmentCommand pinCharges(UpdateShipmentCommand c, ShipmentCharge stored) {
        return new UpdateShipmentCommand(c.expectedVersion(), c.pickupPincode(), c.deliveryPincode(),
                c.senderName(), c.senderAddress(), c.senderContact(), c.receiverName(), c.receiverAddress(),
                c.receiverContact(), c.serviceTypeId(), c.packageTypeId(), c.paymentModeId(), c.shipmentType(),
                c.bookingDate(), c.declaredValue(), c.numberOfPackages(), c.remarks(),
                stored.getOtherCharges(), stored.getOdaCharge(), c.freightFactorOverride(), c.items(),
                c.actualWeight(), c.length(), c.width(), c.height(), c.invoiceValue(), c.ewayBill(),
                c.destinationAreaId(), stored.getRatePerKgOverride(), c.appointmentDelivery(),
                c.appointmentDate(), c.appointmentTimeSlot(), stored.getAppointmentDeliveryCharge(),
                c.insuranceApplicable(), c.deliveryType(), stored.getDoorDeliveryCharge());
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('" + Roles.COMPANY_ADMIN + "')")
    public Shipment updateRatePerKg(UUID id, Long expectedVersion, BigDecimal ratePerKg) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(id, companyId);
        requireCurrentVersion(shipment, expectedVersion);
        ShipmentCharge stored = chargeRepository.findByShipmentIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("ShipmentCharge", id));
        ShipmentCharge recalculated = recalculateAtRate(shipment, stored, companyId, ratePerKg);

        BigDecimal previousNet = stored.getNetAmount();
        copyChargeValues(recalculated, stored);
        chargeRepository.save(stored);

        log.info("Shipment {} ({}) Rate/KG set to {} by {}", shipment.getShipmentNumber(), id,
                recalculated.getRatePerKgOverride(), currentActor());
        auditService.record(AuditAction.SHIPMENT_UPDATED, ENTITY, id,
                Map.of("shipmentNumber", shipment.getShipmentNumber(),
                        "ratePerKg", recalculated.getRatePerKgOverride().toPlainString(),
                        "previousNetAmount", String.valueOf(previousNet),
                        "netAmount", stored.getNetAmount().toPlainString()));
        return shipmentRepository.save(shipment);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('" + Roles.COMPANY_ADMIN + "')")
    public ShipmentCharges previewRatePerKg(UUID id, BigDecimal ratePerKg) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(id, companyId);
        ShipmentCharge stored = chargeRepository.findByShipmentIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("ShipmentCharge", id));
        ShipmentCharge preview = recalculateAtRate(shipment, stored, companyId, ratePerKg);
        List<com.courier.modules.pricing.application.calculator.ApplicableChargesCalculator.Line> lines =
                applicableChargesCalculator.resolve(shipment.getServiceTypeId(), shipment.getChargeableWeight(),
                        shipment.getActualWeight(), shipment.getNumberOfPackages(),
                        shipment.getBookingBranchId(), shipment.getDeliveryBranchId(), preview.getFreight());
        return new ShipmentCharges(preview, resolveRouteCode(preview), resolveRateCode(preview), lines);
    }

    /** A fresh, unsaved charge for {@code shipment} at {@code ratePerKg} — every other input
     *  (other/ODA/appointment/door/To-Pay charges, insurance, weights) is the booked value. */
    private ShipmentCharge recalculateAtRate(Shipment shipment, ShipmentCharge stored, UUID companyId,
                                             BigDecimal ratePerKg) {
        if (!shipment.isEditable()) {
            throw new BusinessRuleException("Rate/KG can only be changed while shipment %s is BOOKED."
                    .formatted(shipment.getShipmentNumber()));
        }
        if (ratePerKg == null || ratePerKg.signum() <= 0) {
            throw new BusinessRuleException("Rate/KG must be greater than zero.");
        }
        return recalculate(shipment, stored, companyId, shipment.getPaymentModeId(),
                ratePerKg.setScale(2, java.math.RoundingMode.HALF_UP), stored.getToPayCharge());
    }

    /** A fresh, unsaved charge for {@code shipment} priced under {@code paymentModeId}, with an
     *  explicit Rate/KG override (null keeps the slab rate) and To-Pay charge. */
    private ShipmentCharge recalculate(Shipment shipment, ShipmentCharge stored, UUID companyId,
                                       UUID paymentModeId, BigDecimal ratePerKgOverride,
                                       BigDecimal toPayCharge) {
        List<ShipmentItem> items = itemRepository.findAllByShipmentIdWithinCompany(shipment.getId(), companyId);
        FreightCalculationResult freightCalc = freightCalculationService.calculate(
                shipment.getBookingBranchId(), shipment.getDeliveryPincode(), shipment.getDestinationAreaId(),
                shipment.getChargeableWeight(), shipment.getBusinessCustomerId(), shipment.getDeclaredValue(),
                new FreightCalculationService.TariffInput(shipment.getServiceTypeId(), shipment.getPackageTypeId(),
                        totalQuantity(items), shipment.getBookingDate()));
        PricingResult priced = priceIt(shipment.getBookingBranchId(), null,
                shipment.getPickupPincode(), shipment.getDeliveryPincode(), shipment.getServiceTypeId(),
                shipment.getPackageTypeId(), paymentModeId, shipment.getChargeableWeight(),
                shipment.getDeclaredValue(), shipment.getBookingDate(), stored.getAppliedFreightFactor(),
                shipment.getActualWeight(), totalQuantity(items));
        com.courier.modules.company.domain.Branch bookingBranch =
                branchService.getById(shipment.getBookingBranchId());
        ShipmentCharge fresh = toCharge(priced, stored.getOtherCharges(), stored.getOdaCharge(), bookingBranch,
                freightCalc, ratePerKgOverride,
                stored.getAppointmentDeliveryCharge(), shipment.isInsuranceApplicable(),
                stored.getDoorDeliveryCharge(), toPayCharge, shipment.getInvoiceValue());
        fresh.setShipmentId(shipment.getId());
        fresh.setCompanyId(companyId);
        return fresh;
    }

    // ------------------------------------------------------- payment mode switch

    @Override
    @Transactional
    @PreAuthorize("hasRole('" + Roles.COMPANY_ADMIN + "')")
    public Shipment changePaymentMode(UUID id, Long expectedVersion, UUID paymentModeId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(id, companyId);
        requireCurrentVersion(shipment, expectedVersion);
        if (!shipment.isEditable()) {
            throw new BusinessRuleException(
                    "Payment mode can only be changed while shipment %s is BOOKED."
                            .formatted(shipment.getShipmentNumber()));
        }
        PaymentMode current = paymentModeService.getById(shipment.getPaymentModeId());
        PaymentMode target = paymentModeService.getById(paymentModeId);
        requireSwitchable(current, target);

        ShipmentCharge stored = chargeRepository.findByShipmentIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("ShipmentCharge", id));
        ShipmentCharge recalculated = recalculate(shipment, stored, companyId, target.getId(),
                stored.getRatePerKgOverride(), toPayChargeFor(target));

        BigDecimal previousNet = stored.getNetAmount();
        String direction;
        if (target.isCollectAtBooking()) {
            // TO_PAY -> PAID: debit now, inside this transaction, so a short balance refuses the
            // whole switch instead of leaving a PAID shipment undebited.
            requireSufficientBalance(shipment.getBookingBranchId(), recalculated.getNetAmount());
            walletService.debitForBooking(new BookingDebitCommand(shipment.getBookingBranchId(),
                    recalculated.getNetAmount(), shipment.getShipmentNumber(),
                    "Freight for shipment " + shipment.getShipmentNumber() + " (changed TO_PAY to PAID)"));
            direction = "debited";
        } else {
            // PAID -> TO_PAY: give back exactly what the ledger says was debited.
            walletService.refundBookingDebit(new ShipmentReversalCommand(shipment.getShipmentNumber(),
                    "Shipment " + shipment.getShipmentNumber() + " changed PAID to TO_PAY"));
            direction = "refunded";
        }

        copyChargeValues(recalculated, stored);
        chargeRepository.save(stored);
        shipment.setPaymentModeChangedFromId(current.getId());
        shipment.setPaymentModeChangedAt(Instant.now());
        shipment.setPaymentModeChangedBy(SecurityUtils.getCurrentUserId().orElse(null));
        shipment.setPaymentModeId(target.getId());
        Shipment saved = shipmentRepository.save(shipment);

        log.info("Shipment {} ({}) payment mode {} -> {} by {}, wallet {}", saved.getShipmentNumber(), id,
                current.getName(), target.getName(), currentActor(), direction);
        auditService.record(AuditAction.SHIPMENT_UPDATED, ENTITY, id,
                Map.of("shipmentNumber", saved.getShipmentNumber(),
                        "paymentModeFrom", current.getName(), "paymentModeTo", target.getName(),
                        "previousNetAmount", String.valueOf(previousNet),
                        "netAmount", stored.getNetAmount().toPlainString()));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('" + Roles.COMPANY_ADMIN + "')")
    public com.courier.modules.shipment.api.dto.PaymentModeChangeResponse paymentModeChangeOptions(UUID id) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(id, companyId);
        PaymentMode current = paymentModeService.getById(shipment.getPaymentModeId());
        ShipmentCharge stored = chargeRepository.findByShipmentIdWithinCompany(id, companyId).orElse(null);
        BigDecimal balance = walletService.getForBranch(shipment.getBookingBranchId()).getAvailableBalance();

        List<com.courier.modules.shipment.api.dto.PaymentModeChangeResponse.Target> targets = new ArrayList<>();
        if (shipment.isEditable() && stored != null && (current.isCollectAtBooking() || isToPay(current))) {
            for (PaymentMode candidate : paymentModeService.search(com.courier.modules.master.domain.MasterDataCriteria.of(
                    companyId, java.util.Set.of(com.courier.modules.master.domain.MasterStatus.ACTIVE), null),
                    org.springframework.data.domain.Pageable.unpaged()).getContent()) {
                if (!candidate.isActive() || candidate.getId().equals(current.getId())
                        || !isSwitchPair(current, candidate)) {
                    continue;
                }
                ShipmentCharge fresh;
                try {
                    fresh = recalculate(shipment, stored, companyId, candidate.getId(),
                            stored.getRatePerKgOverride(), toPayChargeFor(candidate));
                } catch (BusinessRuleException e) {
                    // Pricing config for this lane is gone since booking — the switch can't be
                    // priced, so don't offer it (the Track page must still load).
                    log.warn("Payment mode switch of shipment {} to {} not offered: {}",
                            shipment.getShipmentNumber(), candidate.getName(), e.getMessage());
                    continue;
                }
                boolean debit = candidate.isCollectAtBooking();
                // A debit moves the new net; a credit moves what the ledger holds, which is the
                // booked net for a normally-debited shipment.
                BigDecimal walletAmount = debit ? fresh.getNetAmount() : stored.getNetAmount();
                targets.add(new com.courier.modules.shipment.api.dto.PaymentModeChangeResponse.Target(
                        candidate.getId(), candidate.getName(), debit ? "DEBIT" : "CREDIT",
                        fresh.getNetAmount(), walletAmount, !debit || balance.compareTo(walletAmount) >= 0));
            }
        }
        String fromName = shipment.getPaymentModeChangedFromId() == null ? null
                : paymentModeService.getById(shipment.getPaymentModeChangedFromId()).getName();
        return new com.courier.modules.shipment.api.dto.PaymentModeChangeResponse(
                current.getId(), current.getName(), !targets.isEmpty(),
                stored == null ? null : stored.getNetAmount(), balance,
                shipment.getPaymentModeChangedAt() != null, fromName,
                shipment.getPaymentModeChangedAt(), targets);
    }

    private static boolean isToPay(PaymentMode mode) {
        return mode.isCollectAtDelivery() && !mode.isCashOnDelivery() && !mode.isRequiresCreditAccount();
    }

    private static boolean isSwitchPair(PaymentMode from, PaymentMode to) {
        return (from.isCollectAtBooking() && isToPay(to)) || (isToPay(from) && to.isCollectAtBooking());
    }

    private static void requireSwitchable(PaymentMode from, PaymentMode to) {
        if (!isSwitchPair(from, to)) {
            throw new BusinessRuleException(
                    "Payment mode can only be switched between PAID and TO_PAY (from %s to %s)."
                            .formatted(from.getName(), to.getName()));
        }
    }

    /** Copies every computed money/commission field of {@code from} onto the managed {@code to}. */
    private static void copyChargeValues(ShipmentCharge from, ShipmentCharge to) {
        to.setFreight(from.getFreight());
        to.setFuelCharge(from.getFuelCharge());
        to.setHandlingCharge(from.getHandlingCharge());
        to.setOdaCharge(from.getOdaCharge());
        to.setInsuranceCharge(from.getInsuranceCharge());
        to.setApplicableCharges(from.getApplicableCharges());
        to.setGstAmount(from.getGstAmount());
        to.setDiscountAmount(from.getDiscountAmount());
        to.setRoundOff(from.getRoundOff());
        to.setOtherCharges(from.getOtherCharges());
        to.setAppointmentDeliveryCharge(from.getAppointmentDeliveryCharge());
        to.setDoorDeliveryCharge(from.getDoorDeliveryCharge());
        to.setToPayCharge(from.getToPayCharge());
        to.setCommissionOnBasicFreight(from.getCommissionOnBasicFreight());
        to.setBranchCommissionOnOtherAmount(from.getBranchCommissionOnOtherAmount());
        to.setCompanyCommissionOnBasicFreight(from.getCompanyCommissionOnBasicFreight());
        to.setTotalCommission(from.getTotalCommission());
        to.setNetAmount(from.getNetAmount());
        to.setMatchedRouteId(from.getMatchedRouteId());
        to.setMatchedRateId(from.getMatchedRateId());
        to.setAppliedFreightFactor(from.getAppliedFreightFactor());
        to.setRatePerKgOverride(from.getRatePerKgOverride());
    }

    // --------------------------------------------------------------------- reads

    @Override
    @Transactional(readOnly = true)
    // Narrower than READERS: the three nav leaves the Menu + Permission Management
    // example names (Shipment Booking's own reads, Shipment List, Shipment Tracking) —
    // retrofit onto the permission catalogue. Every other READERS method here is
    // untouched.
    @PreAuthorize("hasAuthority('SHIPMENT_READ')")
    public Shipment getById(UUID id) {
        return loadOrThrow(id, requireCompany());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SHIPMENT_READ')")
    public Shipment getByTrackingNumber(String trackingNumber) {
        UUID companyId = requireCompany();
        return findByTrackingOrPackageId(companyId, trackingNumber)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, trackingNumber));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<Shipment> bulkTrack(Collection<String> numbers) {
        return shipmentRepository.findAllByCompanyIdAndTrackingNumberInOrShipmentNumberIn(
                requireCompany(), numbers);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public DeliveryAssignment getDeliveryAssignment(UUID shipmentId) {
        return deliveryAssignmentRepository
                .findByShipmentIdWithinCompany(shipmentId, requireCompany())
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SHIPMENT_READ')")
    public Page<Shipment> search(ShipmentCriteria criteria, Pageable pageable) {
        return shipmentRepository.findAll(buildSpecification(criteria), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public ShipmentSummaryStats summaryStats(ShipmentCriteria criteria) {
        // Report summary row: aggregates over every matching shipment, not just the current
        // page — unpaged on purpose. This module's data volumes are a company's own shipment
        // history, not a platform-wide table, so an in-memory reduce (matching the existing
        // netAmountsFor/deliveredAtFor "batch by id" style) is simpler than hand-written JPQL
        // aggregate queries for what stays a modest row count.
        List<Shipment> matches = shipmentRepository.findAll(buildSpecification(criteria));
        List<UUID> ids = matches.stream().map(Shipment::getId).toList();
        BigDecimal totalAmount = netAmountsFor(ids).values().stream()
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWeight = matches.stream().map(Shipment::getChargeableWeight)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<ShipmentStatus, Long> statusCounts = matches.stream()
                .collect(Collectors.groupingBy(Shipment::getStatus, Collectors.counting()));
        return new ShipmentSummaryStats(matches.size(), totalWeight, totalAmount, statusCounts);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<BranchCommissionSummary> commissionSummary(ShipmentCriteria criteria) {
        List<Shipment> matches = shipmentRepository.findAll(buildSpecification(criteria));
        Map<UUID, ShipmentCharge> charges = chargesFor(matches.stream().map(Shipment::getId).toList());
        return matches.stream()
                .filter(s -> charges.containsKey(s.getId()))
                .collect(Collectors.groupingBy(Shipment::getBookingBranchId))
                .entrySet().stream()
                .map(e -> {
                    List<ShipmentCharge> branchCharges = e.getValue().stream()
                            .map(s -> charges.get(s.getId())).toList();
                    return new BranchCommissionSummary(
                            e.getKey(),
                            branchCharges.size(),
                            sum(branchCharges, ShipmentCharge::getNetAmount),
                            sum(branchCharges, ShipmentCharge::getCommissionOnBasicFreight),
                            sum(branchCharges, ShipmentCharge::getBranchCommissionOnOtherAmount),
                            sum(branchCharges, ShipmentCharge::getCompanyCommissionOnBasicFreight),
                            sum(branchCharges, ShipmentCharge::getTotalCommission));
                })
                .sorted(Comparator.comparing(BranchCommissionSummary::totalCommission).reversed())
                .toList();
    }

    private static BigDecimal sum(List<ShipmentCharge> charges, java.util.function.Function<ShipmentCharge, BigDecimal> field) {
        return charges.stream().map(field).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<BranchPerformanceSummary> branchPerformance(ShipmentCriteria criteria) {
        List<Shipment> matches = shipmentRepository.findAll(buildSpecification(criteria));
        Map<UUID, BigDecimal> netAmounts = netAmountsFor(matches.stream().map(Shipment::getId).toList());
        return matches.stream()
                .collect(Collectors.groupingBy(Shipment::getBookingBranchId))
                .entrySet().stream()
                .map(e -> {
                    List<Shipment> branchShipments = e.getValue();
                    Map<ShipmentStatus, Long> statusCounts = branchShipments.stream()
                            .collect(Collectors.groupingBy(Shipment::getStatus, Collectors.counting()));
                    long delivered = statusCounts.getOrDefault(ShipmentStatus.DELIVERED, 0L);
                    long returned = statusCounts.getOrDefault(ShipmentStatus.RETURNED, 0L);
                    long cancelled = statusCounts.getOrDefault(ShipmentStatus.CANCELLED, 0L);
                    long total = branchShipments.size();
                    BigDecimal weight = branchShipments.stream().map(Shipment::getChargeableWeight)
                            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal amount = branchShipments.stream()
                            .map(s -> netAmounts.get(s.getId()))
                            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new BranchPerformanceSummary(e.getKey(), total, delivered,
                            total - delivered - returned - cancelled, returned, cancelled, weight, amount);
                })
                .sorted(Comparator.comparing(BranchPerformanceSummary::shipmentCount).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<VendorAuditRow> vendorAudit(ShipmentCriteria criteria) {
        List<Shipment> matches = shipmentRepository.findAll(buildSpecification(criteria));
        List<UUID> ids = matches.stream().map(Shipment::getId).toList();
        Map<UUID, ShipmentCharge> charges = chargesFor(ids);
        Map<UUID, Long> quantities = itemRepository.findByShipmentIdIn(ids).stream()
                .collect(Collectors.groupingBy(ShipmentItem::getShipmentId,
                        Collectors.summingLong(ShipmentItem::getQuantity)));
        Map<UUID, PaymentMode> paymentModes = matches.stream()
                .map(Shipment::getPaymentModeId).distinct()
                .collect(Collectors.toMap(id -> id, paymentModeService::getById));

        Map<UUID, List<Shipment>> byBookingBranch = matches.stream()
                .collect(Collectors.groupingBy(Shipment::getBookingBranchId));
        Map<UUID, List<Shipment>> byDeliveryBranch = matches.stream()
                .filter(s -> s.getStatus() == ShipmentStatus.DELIVERED && s.getDeliveryBranchId() != null)
                .collect(Collectors.groupingBy(Shipment::getDeliveryBranchId));

        Map<UUID, BigDecimal> drsRates = new java.util.HashMap<>();
        byDeliveryBranch.keySet().forEach(branchId ->
                drsRates.put(branchId, branchService.drsChargePerQtyOf(branchId)));

        Set<UUID> branchIds = new java.util.HashSet<>(byBookingBranch.keySet());
        branchIds.addAll(byDeliveryBranch.keySet());

        return branchIds.stream().map(branchId -> {
            List<Shipment> booked = byBookingBranch.getOrDefault(branchId, List.of());
            List<Shipment> delivered = byDeliveryBranch.getOrDefault(branchId, List.of());

            List<Shipment> paid = booked.stream()
                    .filter(s -> paymentModes.get(s.getPaymentModeId()).isCollectAtBooking()).toList();
            List<Shipment> topay = booked.stream()
                    .filter(s -> paymentModes.get(s.getPaymentModeId()).isCollectAtDelivery()).toList();

            BigDecimal paidAmount = sumCharge(paid, charges, ShipmentCharge::getNetAmount);
            BigDecimal topayAmount = sumCharge(topay, charges, ShipmentCharge::getNetAmount);
            BigDecimal paidCommission = sumCharge(paid, charges, ShipmentCharge::getTotalCommission);
            BigDecimal bookingTotalCommission = sumCharge(booked, charges, ShipmentCharge::getTotalCommission);
            BigDecimal odaCharges = sumCharge(booked, charges, ShipmentCharge::getOdaCharge);
            BigDecimal otherCharges = sumCharge(booked, charges, ShipmentCharge::getOtherCharges);
            long cancelledCount = booked.stream()
                    .filter(s -> s.getStatus() == ShipmentStatus.CANCELLED).count();

            BigDecimal drsRate = drsRates.getOrDefault(branchId, BigDecimal.ZERO);
            long deliveredQty = sumQty(delivered, quantities);
            BigDecimal deliveryCommission = drsRate.multiply(BigDecimal.valueOf(deliveredQty));

            return new VendorAuditRow(branchId,
                    paid.size(), sumQty(paid, quantities), paidAmount,
                    topay.size(), sumQty(topay, quantities), topayAmount,
                    paidCommission, deliveryCommission,
                    booked.size(), delivered.size(),
                    bookingTotalCommission, deliveryCommission,
                    odaCharges, otherCharges, cancelledCount);
        })
        .sorted(Comparator.comparing(VendorAuditRow::totalBookedOrderCount).reversed())
        .toList();
    }

    private static BigDecimal sumCharge(List<Shipment> shipments, Map<UUID, ShipmentCharge> charges,
            java.util.function.Function<ShipmentCharge, BigDecimal> field) {
        return shipments.stream().map(s -> charges.get(s.getId())).filter(Objects::nonNull)
                .map(field).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static long sumQty(List<Shipment> shipments, Map<UUID, Long> quantities) {
        return shipments.stream().mapToLong(s -> quantities.getOrDefault(s.getId(), 0L)).sum();
    }

    /** Shared by {@link #search} and {@link #summaryStats} — see the class-level note on
     *  {@code ShipmentCriteria.deliveredDateFrom}/{@code deliveredDateTo} for why this can't
     *  live entirely inside {@link ShipmentSpecifications}. */
    private Specification<Shipment> buildSpecification(ShipmentCriteria criteria) {
        ShipmentCriteria safe = criteria == null ? ShipmentCriteria.none() : criteria;
        var spec = ShipmentSpecifications.matching(safe);
        if (safe.deliveredDateFrom() != null || safe.deliveredDateTo() != null) {
            Instant from = safe.deliveredDateFrom() != null
                    ? safe.deliveredDateFrom().atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.EPOCH;
            Instant to = safe.deliveredDateTo() != null
                    ? safe.deliveredDateTo().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.now();
            List<UUID> deliveredIds = deliveryAssignmentRepository.findShipmentIdsDeliveredBetween(from, to);
            spec = spec.and((root, query, cb) -> deliveredIds.isEmpty()
                    ? cb.disjunction() : root.get("id").in(deliveredIds));
        }
        return spec.and(ownBranchScope());
    }

    /** A branch-bound caller (not a company/platform admin) only sees shipments their own
     *  branch booked, delivers, holds right now, or is next in line for. */
    private Specification<Shipment> ownBranchScope() {
        com.courier.shared.security.AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.isPlatformTier() || caller.hasRole(Roles.COMPANY_ADMIN)) {
            return (root, query, cb) -> cb.conjunction();
        }
        UUID ownBranchId = userService.getById(caller.userId()).getBranchId();
        if (ownBranchId == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("bookingBranchId"), ownBranchId),
                cb.equal(root.get("deliveryBranchId"), ownBranchId),
                cb.equal(root.get("currentLocationId"), ownBranchId),
                cb.equal(root.get("nextLocationId"), ownBranchId));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Map<UUID, BigDecimal> netAmountsFor(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) return Map.of();
        return chargeRepository.findByShipmentIdIn(shipmentIds).stream()
                .collect(Collectors.toMap(ShipmentCharge::getShipmentId, ShipmentCharge::getNetAmount));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Map<UUID, ShipmentCharge> chargesFor(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) return Map.of();
        return chargeRepository.findByShipmentIdIn(shipmentIds).stream()
                .collect(Collectors.toMap(ShipmentCharge::getShipmentId, c -> c));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Map<UUID, Instant> deliveredAtFor(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) return Map.of();
        return deliveryAssignmentRepository.findByShipmentIdIn(shipmentIds).stream()
                .filter(a -> a.getDeliveredAt() != null)
                .collect(Collectors.toMap(DeliveryAssignment::getShipmentId, DeliveryAssignment::getDeliveredAt));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Map<UUID, Instant> receivedAtFor(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) return Map.of();
        return historyRepository
                .findAllByCompanyIdAndShipmentIdInAndStatus(requireCompany(), shipmentIds, ShipmentStatus.IN_SCAN)
                .stream()
                .collect(Collectors.toMap(ShipmentStatusHistory::getShipmentId, ShipmentStatusHistory::getChangedAt,
                        (a, b) -> a.isAfter(b) ? a : b));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Map<UUID, String> invoiceNumbersFor(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) return Map.of();
        return ewayBillService.findLatestForShipments(shipmentIds).entrySet().stream()
                .filter(e -> e.getValue().invoiceNumber() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().invoiceNumber()));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Map<UUID, String> ewayBillNumbersFor(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) return Map.of();
        return ewayBillService.findLatestForShipments(shipmentIds).entrySet().stream()
                .filter(e -> e.getValue().ewayBillNumber() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().ewayBillNumber()));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Map<UUID, List<ShipmentAsset>> podAssetsFor(Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) return Map.of();
        return shipmentAssetRepository
                .findByShipmentIdInAndAssetTypeWithinCompany(shipmentIds, ShipmentAssetType.POD, requireCompany())
                .stream()
                .collect(Collectors.groupingBy(ShipmentAsset::getShipmentId));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<Shipment> findByManifestIds(Collection<UUID> manifestIds) {
        if (manifestIds.isEmpty()) return List.of();
        return shipmentRepository.findAllByCompanyIdAndManifestIdIn(requireCompany(), manifestIds);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<ShipmentItem> getItems(UUID shipmentId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);
        return itemRepository.findAllByShipmentIdWithinCompany(shipment.getId(), companyId);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public ShipmentCharges getCharges(UUID shipmentId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);
        ShipmentCharge charge = chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), companyId)
                .orElseThrow(() -> new ResourceNotFoundException("ShipmentCharge", shipmentId));

        List<com.courier.modules.pricing.application.calculator.ApplicableChargesCalculator.Line> applicableChargeLines =
                applicableChargesCalculator.resolve(shipment.getServiceTypeId(), shipment.getChargeableWeight(),
                        shipment.getActualWeight(), shipment.getNumberOfPackages(),
                        shipment.getBookingBranchId(), shipment.getDeliveryBranchId(), charge.getFreight());

        return new ShipmentCharges(charge, resolveRouteCode(charge), resolveRateCode(charge), applicableChargeLines);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<ShipmentStatusHistory> getHistory(UUID shipmentId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);
        return historyRepository.findAllByShipmentIdWithinCompany(shipment.getId(), companyId);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<ShipmentDocument> getDocuments(UUID shipmentId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);
        return documentRepository.findAllByShipmentIdWithinCompany(shipment.getId(), companyId);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<ShipmentAsset> getAssets(UUID shipmentId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);
        return shipmentAssetRepository.findAllByShipmentIdWithinCompany(shipment.getId(), companyId);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Optional<EwayBillService.EwayBillSnapshot> getEwayBill(UUID shipmentId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);
        return ewayBillService.findLatestForShipment(shipment.getId());
    }

    // ------------------------------------------------------------------- cancel

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public Shipment cancel(UUID id, String remarks) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(id, companyId);

        if (!shipment.getStatus().isCancellable()) {
            throw new BusinessRuleException(
                    "Shipment %s is %s and can no longer be cancelled — it has left the branch."
                            .formatted(shipment.getShipmentNumber(), shipment.getStatus()));
        }
        if (remarks == null || remarks.isBlank()) {
            throw new BusinessRuleException("A reason is required to cancel a shipment.");
        }

        ShipmentStatus previous = shipment.getStatus();
        shipment.transitionTo(ShipmentStatus.CANCELLED);
        Shipment saved = shipmentRepository.save(shipment);
        appendHistory(saved, companyId, previous, ShipmentStatus.CANCELLED, remarks.trim());
        releaseShipmentNumber(saved);

        log.info("Shipment {} ({}) cancelled in company {} by {}", saved.getShipmentNumber(),
                saved.getId(), companyId, currentActor());
        auditService.record(AuditAction.SHIPMENT_CANCELLED, ENTITY, saved.getId(),
                Map.of("shipmentNumber", saved.getShipmentNumber(), "previousStatus", previous.name()));
        ActivityContext.recordChange("Shipments", "Cancel",
                "Cancelled shipment %s: %s".formatted(saved.getShipmentNumber(), remarks.trim()),
                "Shipment", saved.getId().toString(),
                Map.of("status", previous.name()),
                Map.of("status", saved.getStatus().name(), "reason", remarks.trim()));
        eventPublisher.publishEvent(new ShipmentEvent.Cancelled(
                saved.getId(), companyId, saved.getShipmentNumber(), Instant.now()));

        return saved;
    }

    // ---------------------------------------------------------------- documents

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public ShipmentDocument addDocument(UUID shipmentId, AddDocumentCommand command) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);

        ShipmentDocumentType type = parseDocumentType(command.documentType());

        ShipmentDocument document = ShipmentDocument.builder()
                .shipmentId(shipment.getId())
                .documentType(type)
                .documentName(command.documentName())
                .documentUrl(command.documentUrl())
                .remarks(command.remarks())
                .build();
        document.applyInvariants();
        ShipmentDocument saved = documentRepository.save(document);

        auditService.record(AuditAction.SHIPMENT_DOCUMENT_UPLOADED, ENTITY, shipment.getId(),
                Map.of("shipmentNumber", shipment.getShipmentNumber(), "documentType", type.name()));

        return saved;
    }

    private ShipmentDocumentType parseDocumentType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessRuleException("A document type is required.");
        }
        try {
            return ShipmentDocumentType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("No such document type: " + raw);
        }
    }

    // ============================================================= Shipment Movement (V19)

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(WRITERS)
    public void requireFlowType(Collection<UUID> shipmentIds, FlowType flowType) {
        UUID companyId = requireCompany();
        for (UUID shipmentId : shipmentIds) {
            Shipment shipment = loadOrThrow(shipmentId, companyId);
            if (shipment.getFlowType() != flowType) {
                throw new BusinessRuleException(
                        "Shipment %s is a %s shipment — it cannot go on a %s manifest.".formatted(
                                shipment.getShipmentNumber(), shipment.getFlowType(), flowType));
            }
        }
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public Shipment attachToManifest(UUID shipmentId, UUID manifestId, UUID expectedBookingBranchId,
                                     UUID expectedDeliveryBranchId, String manifestDestinationCity) {
        return attachToManifest(shipmentId, manifestId, expectedBookingBranchId,
                expectedDeliveryBranchId, manifestDestinationCity, false);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public Shipment attachToManifest(UUID shipmentId, UUID manifestId, UUID expectedBookingBranchId,
                                     UUID expectedDeliveryBranchId, String manifestDestinationCity,
                                     boolean hubTransfer) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);

        if (shipment.getStatus() != ShipmentStatus.BOOKED && shipment.getStatus() != ShipmentStatus.READY_FOR_MANIFEST) {
            throw new BusinessRuleException("Shipment %s is %s — only BOOKED or READY_FOR_MANIFEST shipments can be "
                    .formatted(shipment.getShipmentNumber(), shipment.getStatus())
                    + "added to a manifest.");
        }
        // Compared against where the shipment actually is right now, not its fixed booking
        // branch — the same value for a single-leg shipment, but different once a crossing
        // shipment has advanced past its first hop (see ShipmentServiceImpl.scanOneIn).
        UUID actualPosition = shipment.getCurrentLocationId() != null
                ? shipment.getCurrentLocationId() : shipment.getBookingBranchId();

        if (shipment.getNextLocationId() == null && shipment.getDeliveryBranchId() == null) {
            // Load Sheet's normal case since delivery-branch assignment moved here: nothing
            // has decided this shipment's next stop yet, so it can't be matched by branch —
            // matched by its own resolved destination city instead. Assigning the branch for
            // real, right here, is this shipment's very first delivery-branch assignment.
            if (!Objects.equals(actualPosition, expectedBookingBranchId)) {
                throw new BusinessRuleException(
                        "Shipment %s is not at this branch.".formatted(shipment.getShipmentNumber()));
            }
            // A hub Load Sheet carries mixed destinations — the hub sorts them onward — and a
            // branch Load Sheet with no destination city is a direct "send these to this branch"
            // pick, so the city match only applies when the sheet names a destination city.
            if (!hubTransfer && manifestDestinationCity != null && !manifestDestinationCity.isBlank()) {
                if (shipment.getToCity() == null
                        || !shipment.getToCity().trim().equalsIgnoreCase(manifestDestinationCity.trim())) {
                    throw new BusinessRuleException(
                            "Shipment %s is going to %s, not %s.".formatted(
                                    shipment.getShipmentNumber(), shipment.getToCity(), manifestDestinationCity));
                }
            }
            // A hub stop is only a next stop: the real delivery branch is assigned later, by
            // the hub's own Load Sheet, once the shipment has been received there.
            if (!hubTransfer) {
                shipment.setDeliveryBranchId(expectedDeliveryBranchId);
            }
            shipment.setNextLocationId(expectedDeliveryBranchId);
        } else {
            // Already has a real next stop — a crossing hop still ahead, or a legacy row
            // booked before Load Sheet deferred branch assignment. Same lane check as ever.
            UUID actualNextStop = shipment.getNextLocationId() != null
                    ? shipment.getNextLocationId() : shipment.getDeliveryBranchId();
            if (!Objects.equals(actualPosition, expectedBookingBranchId)
                    || !Objects.equals(actualNextStop, expectedDeliveryBranchId)) {
                throw new BusinessRuleException(
                        "Shipment %s travels a different lane than this manifest.".formatted(
                                shipment.getShipmentNumber()));
            }
        }

        ShipmentStatus previous = shipment.getStatus();
        shipment.setManifestId(manifestId);
        shipment.transitionTo(ShipmentStatus.MANIFEST_CREATED);
        Shipment saved = shipmentRepository.save(shipment);
        appendHistory(saved, companyId, previous, ShipmentStatus.MANIFEST_CREATED,
                "Added to manifest", shipment.getBookingBranchId(), manifestId, null);
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public Shipment detachFromManifest(UUID shipmentId, UUID manifestId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);

        if (shipment.getStatus() != ShipmentStatus.MANIFEST_CREATED
                || !Objects.equals(shipment.getManifestId(), manifestId)) {
            throw new BusinessRuleException("Shipment %s is not on this manifest.".formatted(
                    shipment.getShipmentNumber()));
        }

        ShipmentStatus previous = shipment.getStatus();
        // A shipment still sitting at its own booking branch reverts to BOOKED, same as
        // ever. One that has already advanced past a crossing hop (its current location is
        // no longer where it was booked) reverts to READY_FOR_MANIFEST instead, so it stays
        // eligible for a future leg from wherever it physically is — not BOOKED, which would
        // let it be re-attached to a manifest starting from its original booking branch.
        boolean atOriginalBookingBranch = shipment.getCurrentLocationId() == null
                || Objects.equals(shipment.getCurrentLocationId(), shipment.getBookingBranchId());
        ShipmentStatus revertTo = atOriginalBookingBranch ? ShipmentStatus.BOOKED : ShipmentStatus.READY_FOR_MANIFEST;
        shipment.setManifestId(null);
        shipment.transitionTo(revertTo);
        Shipment saved = shipmentRepository.save(shipment);
        appendHistory(saved, companyId, previous, revertTo, "Removed from manifest");
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<Shipment> findManifestCreatedShipments(UUID manifestId) {
        ShipmentCriteria criteria = new ShipmentCriteria(
                java.util.Set.of(ShipmentStatus.MANIFEST_CREATED), null, null, null, null, manifestId,
                null, null, null, null, null, null, null, null);
        return shipmentRepository.findAll(ShipmentSpecifications.matching(criteria));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<String> findEligibleDestinationCities(UUID currentLocationId) {
        UUID companyId = requireCompany();
        return shipmentRepository.findDistinctToCityByCompanyIdAndCurrentLocationIdAndStatusIn(
                companyId, currentLocationId,
                java.util.Set.of(ShipmentStatus.BOOKED, ShipmentStatus.READY_FOR_MANIFEST));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public long countArrivalsAt(UUID branchId, Instant from, Instant to) {
        UUID companyId = requireCompany();
        return historyRepository.countByCompanyIdAndBranchIdAndStatusInAndChangedAtBetween(
                companyId, branchId, java.util.Set.of(ShipmentStatus.IN_SCAN, ShipmentStatus.READY_FOR_MANIFEST),
                from, to);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public List<Shipment> transitionToDispatched(List<UUID> shipmentIds, UUID manifestId, UUID vehicleId,
                                                 UUID bookingBranchId) {
        UUID companyId = requireCompany();
        List<Shipment> shipments = shipmentRepository.findAllByCompanyIdAndIdIn(companyId, shipmentIds);
        List<Shipment> saved = new ArrayList<>(shipments.size());
        for (Shipment shipment : shipments) {
            ShipmentStatus previous = shipment.getStatus();
            shipment.transitionTo(ShipmentStatus.DISPATCHED);
            Shipment s = shipmentRepository.save(shipment);
            appendHistory(s, companyId, previous, ShipmentStatus.DISPATCHED, "Manifest dispatched",
                    bookingBranchId, manifestId, vehicleId);
            saved.add(s);
            eventPublisher.publishEvent(new ShipmentEvent.Dispatched(s.getId(), companyId, Instant.now()));
        }
        auditService.record(AuditAction.MANIFEST_DISPATCHED, "Manifest", manifestId,
                Map.of("shipmentCount", saved.size(), "vehicleId", vehicleId.toString()));
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public List<Shipment> markPickedUpForDirectDelivery(List<UUID> shipmentIds) {
        UUID companyId = requireCompany();
        List<Shipment> shipments = shipmentRepository.findAllByCompanyIdAndIdIn(companyId, shipmentIds);
        List<Shipment> saved = new ArrayList<>(shipments.size());
        for (Shipment shipment : shipments) {
            ShipmentStatus previous = shipment.getStatus();
            shipment.transitionTo(ShipmentStatus.IN_SCAN);
            Shipment s = shipmentRepository.save(shipment);
            appendHistory(s, companyId, previous, ShipmentStatus.IN_SCAN,
                    "Picked up by company vehicle for direct delivery — no delivery branch",
                    null, s.getManifestId(), null);
            saved.add(s);
        }
        return saved;
    }

    // Branch commission is credited here, when the shipment is in-scanned at its own final
    // delivery branch — not at Trip Challan (manifest dispatch) creation, per direct user
    // request. Same condition booking used to gate the credit on: the payment mode collects
    // at booking, and the booking branch itself has instantCommission on. Caller already
    // checked paymentMode.isCollectAtBooking().
    private void publishInScanCommissionIfEarned(Shipment shipment, UUID companyId, ShipmentCharge charge) {
        BigDecimal branchCommission = eligibleBranchCommission(shipment.getBookingBranchId(), charge);
        if (branchCommission.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        eventPublisher.publishEvent(new ShipmentEvent.InScanCommissionEarned(
                shipment.getId(), companyId, shipment.getBookingBranchId(), shipment.getShipmentNumber(),
                branchCommission, Instant.now()));
    }

    // Delivery-side mirror of publishInScanCommissionIfEarned, for collect-at-delivery
    // (TO_PAY/COD) shipments only — their booking branch never gets an InScanCommissionEarned
    // (gated to collect-at-booking payment modes), so without this the booking branch's
    // commission on a TO_PAY order was never credited at all. Caller already checked
    // paymentMode.isCollectAtDelivery().
    private void publishDeliveryCommissionIfEarned(Shipment shipment, UUID companyId, ShipmentCharge charge) {
        BigDecimal branchCommission = eligibleBranchCommission(shipment.getBookingBranchId(), charge);
        if (branchCommission.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        eventPublisher.publishEvent(new ShipmentEvent.DeliveryCommissionEarned(
                shipment.getId(), companyId, shipment.getBookingBranchId(), shipment.getShipmentNumber(),
                branchCommission, Instant.now()));
    }

    // Zero when the booking branch has instantCommission off — same as if there were
    // nothing to credit, so callers don't need a separate check. instantCommissionOf(), not
    // getById(): the caller in-scanning here is routinely a different branch than the one
    // that booked the shipment, and getById()'s own-branch-only visibility check 404s on
    // exactly that — a real, active branch the caller just isn't allowed to *view* directly.
    private BigDecimal eligibleBranchCommission(UUID bookingBranchId, ShipmentCharge charge) {
        if (!branchService.instantCommissionOf(bookingBranchId)) {
            return BigDecimal.ZERO;
        }
        // Only the branch's own two lines — totalCommission (V28) also folds in the
        // company's own commissionOnBasicFreight, which is company revenue and must
        // never land in the branch's wallet.
        return charge.getCommissionOnBasicFreight().add(charge.getBranchCommissionOnOtherAmount());
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public BulkMovementResult inScan(UUID receivingBranchId, List<String> trackingNumbers, String manifestNumber,
                                      List<String> missingTrackingNumbers, String remarks, String photoUrl) {
        UUID companyId = requireCompany();
        List<MovementOutcome> outcomes = new ArrayList<>();
        for (String trackingNumber : trackingNumbers) {
            outcomes.add(scanOneIn(companyId, receivingBranchId, trackingNumber, remarks, photoUrl));
        }
        String shortageTicketNumber = (missingTrackingNumbers == null || missingTrackingNumbers.isEmpty())
                ? null
                : raiseShortageTicket(receivingBranchId, manifestNumber, missingTrackingNumbers);
        return new BulkMovementResult(outcomes, null, shortageTicketNumber);
    }

    /**
     * The operator's own explicit "not physically on this THC" checklist answer — see
     * {@link ShipmentService#inScan}'s own doc. Never blocks or rolls back the in-scan
     * itself: a missing "Shipment Issue" category (SUPER_ADMIN deactivated/renamed it) just
     * skips the ticket with a warning, same graceful-degradation precedent every other
     * best-effort side effect in this module follows.
     */
    private String raiseShortageTicket(UUID receivingBranchId, String manifestNumber,
                                        List<String> missingTrackingNumbers) {
        Optional<TicketCategory> category = ticketCategoryService.listCategories().stream()
                .filter(TicketCategory::isActive)
                .filter(c -> "Shipment Issue".equalsIgnoreCase(c.getName()))
                .findFirst();
        if (category.isEmpty()) {
            log.warn("No 'Shipment Issue' ticket category found — skipping auto-raised shortage ticket "
                    + "for {} short-received shipment(s) at branch {}", missingTrackingNumbers.size(), receivingBranchId);
            return null;
        }
        String manifestRef = (manifestNumber == null || manifestNumber.isBlank()) ? "" : " (" + manifestNumber + ")";
        String subject = "Short shipment received — %d shipment(s)%s".formatted(missingTrackingNumbers.size(), manifestRef);
        String description = ("The following %d shipment(s) were left unchecked as \"not physically available\" "
                + "during In Scan%s and are still showing DISPATCHED: %s.")
                .formatted(missingTrackingNumbers.size(), manifestRef, String.join(", ", missingTrackingNumbers));
        Ticket ticket = ticketService.create(new CreateTicketCommand(
                subject, description, category.get().getId(), null, TicketPriority.HIGH,
                null, null, receivingBranchId, null));
        log.info("Auto-raised ticket {} ({}) for {} short-received shipment(s) at branch {}",
                ticket.getTicketNumber(), ticket.getId(), missingTrackingNumbers.size(), receivingBranchId);
        return ticket.getTicketNumber();
    }

    /** Exact tracking number first; failing that, a label's package id ({@code AWB-002}) resolves
     *  to its shipment when the package number is within the shipment's {@code numberOfPackages}. */
    private Optional<Shipment> findByTrackingOrPackageId(UUID companyId, String scanned) {
        Optional<Shipment> exact = shipmentRepository.findByCompanyIdAndTrackingNumber(companyId, scanned);
        if (exact.isPresent()) return exact;
        return PackageId.parse(scanned)
                .flatMap(p -> shipmentRepository.findByCompanyIdAndTrackingNumber(companyId, p.awb())
                        .filter(s -> p.packageNo() <= (s.getNumberOfPackages() == null ? 1 : s.getNumberOfPackages())));
    }

    private MovementOutcome scanOneIn(UUID companyId, UUID receivingBranchId, String trackingNumber,
                                       String remarks, String photoUrl) {
        Optional<Shipment> found = findByTrackingOrPackageId(companyId, trackingNumber);
        if (found.isEmpty()) {
            return new MovementOutcome(trackingNumber, false, "No such tracking number.");
        }
        Shipment shipment = found.get();
        if (shipment.getStatus() != ShipmentStatus.DISPATCHED) {
            return new MovementOutcome(trackingNumber, false,
                    "Cannot be received — currently " + shipment.getStatus() + ".");
        }
        // A shipment's next stop is its crossing hop when it has one still ahead, otherwise
        // its own delivery branch — the same value for a non-crossing shipment either way
        // (pre-V37 rows fall back to deliveryBranchId, since they never got nextLocationId
        // written).
        UUID expectedNextStop = shipment.getNextLocationId() != null
                ? shipment.getNextLocationId() : shipment.getDeliveryBranchId();
        if (!Objects.equals(expectedNextStop, receivingBranchId)) {
            return new MovementOutcome(trackingNumber, false,
                    "Receiving branch does not match this shipment's next stop.");
        }

        boolean finalDestination = Objects.equals(shipment.getDeliveryBranchId(), receivingBranchId);
        ShipmentStatus previous = shipment.getStatus();
        shipment.setCurrentLocationId(receivingBranchId);

        if (finalDestination) {
            shipment.transitionTo(ShipmentStatus.IN_SCAN);
            Shipment saved = shipmentRepository.save(shipment);
            appendHistory(saved, companyId, previous, ShipmentStatus.IN_SCAN,
                    remarks == null || remarks.isBlank() ? "In scan" : remarks.trim(),
                    receivingBranchId, saved.getManifestId(), null);
            recordAsset(saved.getId(), companyId, ShipmentAssetType.IN_SCAN, "PHOTO", photoUrl);
            auditService.record(AuditAction.SHIPMENT_IN_SCANNED, ENTITY, saved.getId(),
                    Map.of("shipmentNumber", saved.getShipmentNumber()));
            eventPublisher.publishEvent(new ShipmentEvent.ReceivedAtBranch(saved.getId(), companyId, Instant.now()));

            // TO_PAY's freight is the branch's liability the instant the shipment is
            // physically here, not deferred to actual delivery like COD (the consignee's
            // amount, only real once collected) — see ShipmentEvent.ToPayReceivedAtDeliveryBranch.
            // Collect-at-booking's booking-branch commission also credits here now, on
            // arrival at the final delivery branch, not on Trip Challan creation — see
            // ShipmentEvent.InScanCommissionEarned.
            PaymentMode paymentMode = paymentModeService.getById(saved.getPaymentModeId());
            boolean toPay = paymentMode.isCollectAtDelivery() && !paymentMode.isCashOnDelivery();
            if (paymentMode.isCollectAtBooking() || toPay) {
                ShipmentCharge charge = chargeRepository.findByShipmentIdWithinCompany(saved.getId(), companyId)
                        .orElseThrow(() -> new ResourceNotFoundException("ShipmentCharge", saved.getId()));
                if (toPay) {
                    eventPublisher.publishEvent(new ShipmentEvent.ToPayReceivedAtDeliveryBranch(
                            saved.getId(), companyId, receivingBranchId, saved.getShipmentNumber(),
                            charge.getNetAmount(), Instant.now()));
                } else {
                    publishInScanCommissionIfEarned(saved, companyId, charge);
                }
            }
            return new MovementOutcome(trackingNumber, true, null);
        }

        // Received at an intermediate crossing hub, not the final branch: this hop of the
        // route is done, and the shipment is ready for its next leg's manifest — not
        // IN_SCAN, which this codebase's out-for-delivery worklist would otherwise treat as
        // "arrived, ready to go out for delivery" at the wrong branch.
        UUID nextHop = crossingService.arriveAt(shipment.getId(), receivingBranchId)
                .orElse(shipment.getDeliveryBranchId());
        shipment.setNextLocationId(nextHop);
        shipment.setManifestId(null);
        shipment.transitionTo(ShipmentStatus.READY_FOR_MANIFEST);
        Shipment saved = shipmentRepository.save(shipment);
        appendHistory(saved, companyId, previous, ShipmentStatus.READY_FOR_MANIFEST,
                remarks == null || remarks.isBlank() ? "In scan at crossing hub, ready for next leg" : remarks.trim(),
                receivingBranchId, null, null);
        recordAsset(saved.getId(), companyId, ShipmentAssetType.IN_SCAN, "PHOTO", photoUrl);
        auditService.record(AuditAction.SHIPMENT_IN_SCANNED, ENTITY, saved.getId(),
                Map.of("shipmentNumber", saved.getShipmentNumber(), "crossingHub", true));
        return new MovementOutcome(trackingNumber, true, null);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public BulkMovementResult assignOutForDelivery(Collection<UUID> shipmentIds, UUID deliveryUserId,
                                                    UUID vehicleId, BigDecimal fuelCost, BigDecimal deliveryCharge) {
        UUID companyId = requireCompany();
        userService.getById(deliveryUserId); // 404s if foreign/missing — no role restriction, see module doc

        String drsNumber = nextDrsNumber(companyId);
        List<MovementOutcome> outcomes = new ArrayList<>();
        for (UUID shipmentId : shipmentIds) {
            outcomes.add(assignOneOutForDelivery(companyId, shipmentId, deliveryUserId, drsNumber,
                    vehicleId, fuelCost, deliveryCharge));
        }
        return new BulkMovementResult(outcomes, drsNumber);
    }

    private MovementOutcome assignOneOutForDelivery(UUID companyId, UUID shipmentId, UUID deliveryUserId,
                                                      String drsNumber, UUID vehicleId, BigDecimal fuelCost,
                                                      BigDecimal deliveryCharge) {
        Shipment shipment = shipmentRepository.findByIdWithinCompany(shipmentId, companyId).orElse(null);
        if (shipment == null) {
            return new MovementOutcome(shipmentId.toString(), false, "No such shipment.");
        }
        // A shipment received at a hub (READY_FOR_MANIFEST, no delivery branch assigned, no
        // longer at its booking branch) may be delivered straight from the hub.
        boolean atHubUnassigned = shipment.getStatus() == ShipmentStatus.READY_FOR_MANIFEST
                && shipment.getDeliveryBranchId() == null
                && shipment.getCurrentLocationId() != null
                && !Objects.equals(shipment.getCurrentLocationId(), shipment.getBookingBranchId());
        if (shipment.getStatus() != ShipmentStatus.IN_SCAN && !atHubUnassigned) {
            return new MovementOutcome(shipment.getShipmentNumber(), false,
                    "Cannot be assigned — currently " + shipment.getStatus() + ".");
        }

        DeliveryAssignment assignment = deliveryAssignmentRepository
                .findByShipmentIdWithinCompany(shipmentId, companyId)
                .orElseGet(() -> {
                    DeliveryAssignment fresh = DeliveryAssignment.builder().build();
                    fresh.setCompanyId(companyId);
                    fresh.setShipmentId(shipmentId);
                    return fresh;
                });
        if (assignment.isNew()) {
            assignment.setDeliveryBranchId(shipment.getDeliveryBranchId());
            assignment.setDeliveryUserId(deliveryUserId);
            assignment.setAssignedAt(Instant.now());
        } else {
            assignment.reassign(deliveryUserId, shipment.getDeliveryBranchId());
        }
        assignment.setDrsNumber(drsNumber);
        assignment.setVehicleId(vehicleId);
        assignment.setFuelCost(fuelCost);
        assignment.setDeliveryCharge(deliveryCharge);
        deliveryAssignmentRepository.save(assignment);

        ShipmentStatus previous = shipment.getStatus();
        shipment.transitionTo(ShipmentStatus.OUT_FOR_DELIVERY);
        Shipment saved = shipmentRepository.save(shipment);
        appendHistory(saved, companyId, previous, ShipmentStatus.OUT_FOR_DELIVERY, "DRS",
                shipment.getDeliveryBranchId(), saved.getManifestId(), null);
        auditService.record(AuditAction.SHIPMENT_OUT_FOR_DELIVERY_ASSIGNED, ENTITY, saved.getId(),
                Map.of("shipmentNumber", saved.getShipmentNumber(), "deliveryUserId", deliveryUserId.toString()));
        eventPublisher.publishEvent(new ShipmentEvent.OutForDelivery(saved.getId(), companyId, Instant.now()));
        return new MovementOutcome(saved.getShipmentNumber(), true, null);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public DeliveryOtpIssued requestDeliveryDispatchOtp(UUID deliveryUserId) {
        UUID companyId = requireCompany();
        // Same "any real user of this company" check assignOutForDelivery itself uses.
        com.courier.modules.company.domain.User deliveryUser = userService.getById(deliveryUserId);
        String mobile = deliveryUser.getMobile();
        if (mobile == null || mobile.isBlank()) {
            throw new BusinessRuleException(
                    "%s has no mobile number on file — add one before requesting an OTP."
                            .formatted(deliveryUser.effectiveDisplayName()));
        }

        DeliveryDispatchOtp challenge = deliveryDispatchOtpRepository
                .findByCompanyIdAndDeliveryUserId(companyId, deliveryUserId)
                .orElseGet(() -> {
                    DeliveryDispatchOtp fresh = DeliveryDispatchOtp.builder().build();
                    fresh.setCompanyId(companyId);
                    fresh.setDeliveryUserId(deliveryUserId);
                    return fresh;
                });

        int expiryMinutes = Optional.ofNullable(companySettingsService.get().getOtpExpiryMinutes())
                .orElse(DEFAULT_OTP_EXPIRY_MINUTES);
        String otp = generateOtp();
        challenge.issue(passwordEncoder.encode(otp), Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES));
        deliveryDispatchOtpRepository.save(challenge);

        sendOtpSms(companyId, mobile, otp, expiryMinutes);

        log.info("Delivery OTP issued for delivery user {} in company {} by {}",
                deliveryUserId, companyId, currentActor());

        return new DeliveryOtpIssued(maskMobile(mobile), expiryMinutes);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public void verifyDeliveryDispatchOtp(UUID deliveryUserId, String otp) {
        UUID companyId = requireCompany();
        DeliveryDispatchOtp challenge = deliveryDispatchOtpRepository
                .findByCompanyIdAndDeliveryUserId(companyId, deliveryUserId)
                .orElseThrow(() -> new BusinessRuleException("Request a delivery OTP before verifying it."));
        boolean codeMatched = challenge.getOtpHash() != null
                && otp != null && passwordEncoder.matches(otp.trim(), challenge.getOtpHash());
        challenge.registerVerificationAttempt(codeMatched);
        deliveryDispatchOtpRepository.save(challenge);

        log.info("Delivery OTP verified for delivery user {} in company {} by {}",
                deliveryUserId, companyId, currentActor());
    }

    /** Sends the raw OTP over the company's configured SMS channel (Communication Center) —
     *  same mechanism {@code ManifestServiceImpl.sendOtpSms} uses for the driver dispatch
     *  OTP, duplicated rather than shared since {@code modules.shipment} must not depend on
     *  {@code modules.manifest} (see {@code ManifestServiceImpl}'s class doc). Falls back to
     *  log-only credentials when the company hasn't configured SMS at all. */
    private void sendOtpSms(UUID companyId, String mobile, String otp, int expiryMinutes) {
        Optional<CommunicationSetting> setting =
                communicationSettingService.findEnabled(companyId, CommunicationChannel.SMS);
        SmsProvider.SmsCredentials credentials = setting.map(s -> {
            Map<String, String> config = CommunicationConfigJson.read(objectMapper, s.getConfigJson());
            return new SmsProvider.SmsCredentials(s.getProvider(), config.get("apiUrl"), s.getSecret(),
                    config.get("senderId"));
        }).orElseGet(() -> new SmsProvider.SmsCredentials(null, null, null, null));

        String body = "Your OTP to receive shipments for delivery is %s. Valid for %d minute(s). "
                .formatted(otp, expiryMinutes) + "Do not share it with anyone.";
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
    public Shipment deliver(UUID shipmentId, DeliverCommand command) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);

        if (shipment.getStatus() != ShipmentStatus.OUT_FOR_DELIVERY) {
            throw new BusinessRuleException("Shipment %s is %s — only an OUT_FOR_DELIVERY shipment "
                    .formatted(shipment.getShipmentNumber(), shipment.getStatus())
                    + "can be delivered.");
        }
        requirePodForPartnerDelivery(shipment, companyId, command);
        DeliveryAssignment assignment = deliveryAssignmentRepository
                .findByShipmentIdWithinCompany(shipmentId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("DeliveryAssignment", shipmentId));
        assignment.markDelivered(command.receiverName(), command.remarks(), command.otp());
        deliveryAssignmentRepository.save(assignment);
        recordAsset(shipmentId, companyId, ShipmentAssetType.POD, "SIGNATURE", command.signatureUrl());
        recordAsset(shipmentId, companyId, ShipmentAssetType.POD, "PHOTO", command.photoUrl());

        ShipmentStatus previous = shipment.getStatus();
        shipment.transitionTo(ShipmentStatus.DELIVERED);
        Shipment saved = shipmentRepository.save(shipment);
        appendHistory(saved, companyId, previous, ShipmentStatus.DELIVERED,
                command.remarks() == null || command.remarks().isBlank()
                        ? "Delivered to " + command.receiverName() : command.remarks().trim(),
                shipment.getDeliveryBranchId(), saved.getManifestId(), null);
        auditService.record(AuditAction.SHIPMENT_DELIVERED, ENTITY, saved.getId(),
                Map.of("shipmentNumber", saved.getShipmentNumber(), "receiverName", command.receiverName()));
        eventPublisher.publishEvent(new ShipmentEvent.Delivered(saved.getId(), companyId, Instant.now()));

        PaymentMode paymentMode = paymentModeService.getById(saved.getPaymentModeId());
        // Direct Company Delivery (no deliveryBranchId — see DeliveryMode's own doc) has no
        // delivery branch wallet to debit at all, so COD's collected-cash debit below is
        // skipped for it, same as TO_PAY's own in-scan debit already never fires for it
        // (scanOneIn never runs on a Direct Company Delivery shipment — see
        // ManifestServiceImpl.dispatch/markPickedUpForDirectDelivery). That money is
        // deliberately not attributed to any branch, on direct request.
        if (paymentMode.isCollectAtDelivery() && saved.getDeliveryBranchId() != null) {
            // COD's amount is the consignee's, only real once actually collected here — TO_PAY
            // already debited its freight earlier, at in-scan (see scanOneIn), so it does not
            // repeat that debit on delivery. This is cash actually collected, a debit not a
            // commission, so it fires regardless of POD status, unlike the commission credits
            // creditDeliveryCommissionsIfEligible below.
            if (paymentMode.isCashOnDelivery()) {
                ShipmentCharge charge = chargeRepository.findByShipmentIdWithinCompany(saved.getId(), companyId)
                        .orElseThrow(() -> new ResourceNotFoundException("ShipmentCharge", saved.getId()));
                eventPublisher.publishEvent(new ShipmentEvent.CodCollectedAtDelivery(
                        saved.getId(), companyId, saved.getDeliveryBranchId(), saved.getShipmentNumber(),
                        charge.getNetAmount(), Instant.now()));
            }
        }

        // Collect-at-booking (PAID) commission normally credits at in-scan (see
        // publishInScanCommissionIfEarned/scanOneIn) — a Direct Company Delivery shipment
        // never gets a real in-scan, so it credits here instead, at the one moment every
        // Direct Company Delivery shipment does pass through. Booking-branch commission,
        // not delivery-branch — unaffected by there being no delivery branch.
        if (saved.getDeliveryBranchId() == null && paymentMode.isCollectAtBooking()) {
            ShipmentCharge charge = chargeRepository.findByShipmentIdWithinCompany(saved.getId(), companyId)
                    .orElseThrow(() -> new ResourceNotFoundException("ShipmentCharge", saved.getId()));
            publishInScanCommissionIfEarned(saved, companyId, charge);
        }

        // Covers the common case: POD Auto Verification already reached PASS before this
        // shipment was delivered (verify() runs against OUT_FOR_DELIVERY, ahead of "Complete
        // Delivery"). If POD isn't approved yet, this is a no-op — markPodApproved credits
        // it later instead, once approval actually happens.
        creditDeliveryCommissionsIfEligible(saved, companyId);

        return saved;
    }

    // ------------------------------------------------------------- manual override

    @Override
    @Transactional
    @PreAuthorize(OVERRIDE_WRITERS)
    public OverrideStatusResult overrideStatus(UUID shipmentId, OverrideStatusCommand command) {
        UUID companyId = requireCompany();
        if (!companySettingsService.get().isManualStatusOverrideEnabled()) {
            throw new BusinessRuleException("Manual status override is not enabled for this company "
                    + "— turn it on in Company Settings > Shipment first.");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new BusinessRuleException("A reason is required to override a shipment's status.");
        }
        if (command.targetStatus() == null) {
            throw new BusinessRuleException("A target status is required.");
        }

        Shipment shipment = loadOrThrow(shipmentId, companyId);
        ShipmentStatus previous = shipment.getStatus();
        ShipmentStatus target = command.targetStatus();
        if (previous.isTerminal()) {
            throw new BusinessRuleException("Shipment %s is %s, a final status — it cannot be "
                    .formatted(shipment.getShipmentNumber(), previous) + "overridden any further.");
        }
        if (previous == target) {
            throw new BusinessRuleException(
                    "Shipment %s is already %s.".formatted(shipment.getShipmentNumber(), target));
        }
        String reason = command.reason().trim();

        // Prefer the real service method when it covers this target — money/wallet/POD side
        // effects fire exactly as they would from the normal screen. Any precondition it
        // refuses (wrong current status, missing DeliveryAssignment, ...) falls through to the
        // raw write below instead of failing the whole call — an override must never block.
        try {
            switch (target) {
                case OUT_FOR_DELIVERY -> {
                    if (command.deliveryUserId() == null) {
                        throw new BusinessRuleException(
                                "A delivery user is required to override to OUT_FOR_DELIVERY.");
                    }
                    BulkMovementResult result = assignOutForDelivery(List.of(shipmentId), command.deliveryUserId(),
                            command.vehicleId(), command.fuelCost(), command.deliveryCharge());
                    MovementOutcome outcome = result.results().get(0);
                    if (!outcome.success()) {
                        throw new BusinessRuleException(outcome.message());
                    }
                    return new OverrideStatusResult(loadOrThrow(shipmentId, companyId), true, null);
                }
                case DELIVERED -> {
                    if (command.receiverName() == null || command.receiverName().isBlank()) {
                        throw new BusinessRuleException(
                                "A receiver name is required to override to DELIVERED.");
                    }
                    Shipment delivered = deliver(shipmentId, new DeliverCommand(command.receiverName(),
                            reason, command.otp(), command.signatureUrl(), command.photoUrl()));
                    return new OverrideStatusResult(delivered, true, null);
                }
                case CANCELLED -> {
                    return new OverrideStatusResult(cancel(shipmentId, reason), true, null);
                }
                default -> {
                    // No single-shipment real method covers this edge: DISPATCHED/IN_SCAN only
                    // ever happen as part of a manifest-wide THC dispatch (vehicle/driver, every
                    // shipment on that manifest together); BOOKED/READY_FOR_MANIFEST/
                    // MANIFEST_CREATED/RETURNED have no dedicated writer at all. Falls through.
                }
            }
        } catch (BusinessRuleException | ResourceNotFoundException preconditionNotMet) {
            // Real method exists but refused this shipment's current state — fall through.
        }

        shipment.forceStatus(target);
        Shipment saved = shipmentRepository.save(shipment);
        appendManualOverrideHistory(saved, companyId, previous, target, reason);
        auditService.record(AuditAction.SHIPMENT_STATUS_OVERRIDDEN, ENTITY, saved.getId(),
                Map.of("shipmentNumber", saved.getShipmentNumber(), "from", previous.name(),
                        "to", target.name(), "reason", reason));
        log.info("Shipment {} ({}) status manually force-overridden {} -> {} in company {} by {}: {}",
                saved.getShipmentNumber(), saved.getId(), previous, target, companyId, currentActor(), reason);

        String warning = previous + " -> " + target + " has no matching normal step, so no automatic "
                + "money/wallet/POD processing ran for this jump — check manually if needed.";
        return new OverrideStatusResult(saved, false, warning);
    }

    private void appendManualOverrideHistory(Shipment shipment, UUID companyId, ShipmentStatus previous,
                                              ShipmentStatus status, String reason) {
        ShipmentStatusHistory entry = ShipmentStatusHistory.builder()
                .shipmentId(shipment.getId())
                .manifestId(shipment.getManifestId())
                .status(status)
                .previousStatus(previous)
                .remarks("Manual override: " + reason)
                .changedBy(SecurityUtils.getCurrentUserId().orElse(null))
                .changedAt(Instant.now())
                .manualOverride(true)
                .build();
        entry.setCompanyId(companyId);
        historyRepository.save(entry);
    }

    /**
     * Credits DRS charge, weight-based delivery commission and (for a collect-at-delivery
     * shipment) the booking branch's TO_PAY/COD commission — but only once, and only once
     * the shipment is both {@code DELIVERED} and {@code podApproved}. Called from {@link
     * #deliver} itself (POD already approved before delivery) and from {@link
     * #markPodApproved} (POD approved after delivery already happened) — whichever of the
     * two conditions is satisfied second is the one that actually triggers the credit.
     */
    private void creditDeliveryCommissionsIfEligible(Shipment shipment, UUID companyId) {
        if (shipment.getStatus() != ShipmentStatus.DELIVERED || !shipment.isPodApproved()
                || shipment.isCommissionCredited()) {
            return;
        }

        PaymentMode paymentMode = paymentModeService.getById(shipment.getPaymentModeId());
        if (paymentMode.isCollectAtDelivery()) {
            ShipmentCharge charge = chargeRepository.findByShipmentIdWithinCompany(shipment.getId(), companyId)
                    .orElseThrow(() -> new ResourceNotFoundException("ShipmentCharge", shipment.getId()));
            publishDeliveryCommissionIfEarned(shipment, companyId, charge);
        }

        // DRS charge and delivery-weight commission are both a *delivery branch's* own
        // configured rates, credited to that branch's wallet — a Direct Company Delivery
        // shipment has no delivery branch to own either rate or receive either credit, so
        // both are skipped for it entirely, on direct request (not deferred anywhere else).
        if (shipment.getDeliveryBranchId() != null) {
            int totalQty = itemRepository.findAllByShipmentIdWithinCompany(shipment.getId(), companyId).stream()
                    .mapToInt(ShipmentItem::getQuantity).sum();
            com.courier.modules.company.domain.Branch deliveryBranch = branchService.getById(shipment.getDeliveryBranchId());
            if (deliveryBranch.getBranchType() == com.courier.modules.company.domain.BranchType.PICKUP_DELIVERY_PARTNER) {
                // A partner is paid from its own delivery rate card (pdp module), not the branch
                // DRS / delivery-weight commission fields — publish for it and skip those two.
                eventPublisher.publishEvent(new ShipmentEvent.PartnerDeliveryEarned(
                        shipment.getId(), companyId, shipment.getDeliveryBranchId(), shipment.getShipmentNumber(),
                        shipment.getDeliveryPincode(),
                        shipment.getChargeableWeight() != null && shipment.getChargeableWeight().signum() > 0
                                ? shipment.getChargeableWeight() : shipment.getActualWeight(),
                        Instant.now()));
                shipment.setCommissionCredited(true);
                shipmentRepository.save(shipment);
                return;
            }
            BigDecimal drsCharge = deliveryBranch.getDrsChargePerQty().multiply(BigDecimal.valueOf(totalQty));
            if (drsCharge.compareTo(BigDecimal.ZERO) > 0) {
                eventPublisher.publishEvent(new ShipmentEvent.DrsChargeApplicable(
                        shipment.getId(), companyId, shipment.getDeliveryBranchId(), shipment.getShipmentNumber(),
                        drsCharge, Instant.now()));
            }

            // Independent of DRS charge: a per-kg commission on the shipment's own chargeable
            // weight, with a floor so a very light shipment still earns the floor's worth.
            BigDecimal chargeableWeight = shipment.getChargeableWeight() == null
                    ? BigDecimal.ZERO : shipment.getChargeableWeight();
            BigDecimal billableWeight = chargeableWeight.max(deliveryBranch.getDeliveryCommissionMinWeightKg());
            BigDecimal deliveryWeightCommission = deliveryBranch.getDeliveryCommissionRatePerKg()
                    .multiply(billableWeight);
            if (deliveryWeightCommission.compareTo(BigDecimal.ZERO) > 0) {
                eventPublisher.publishEvent(new ShipmentEvent.DeliveryWeightCommissionApplicable(
                        shipment.getId(), companyId, shipment.getDeliveryBranchId(), shipment.getShipmentNumber(),
                        deliveryWeightCommission, Instant.now()));
            }
        }

        shipment.setCommissionCredited(true);
        shipmentRepository.save(shipment);
    }

    /**
     * A Pickup-Delivery Partner may only mark a shipment delivered with a POD photo on file —
     * sent with this call, or already captured (POD Auto Verification stores it before
     * "Complete Delivery"). Every other delivery branch keeps POD optional at this step.
     */
    private void requirePodForPartnerDelivery(Shipment shipment, UUID companyId, DeliverCommand command) {
        if (shipment.getDeliveryBranchId() == null) {
            return;
        }
        com.courier.modules.company.domain.Branch deliveryBranch = branchService.getById(shipment.getDeliveryBranchId());
        if (deliveryBranch.getBranchType() != com.courier.modules.company.domain.BranchType.PICKUP_DELIVERY_PARTNER) {
            return;
        }
        boolean photoOnFile = command.photoUrl() != null && !command.photoUrl().isBlank()
                || shipmentAssetRepository.findAllByShipmentIdWithinCompany(shipment.getId(), companyId).stream()
                        .anyMatch(a -> a.getAssetType() == ShipmentAssetType.POD && "PHOTO".equals(a.getKind()));
        if (!photoOnFile) {
            throw new BusinessRuleException("A POD photo is required before a Pickup-Delivery Partner can mark "
                    + "shipment %s delivered. Upload the POD first.".formatted(shipment.getShipmentNumber()));
        }
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public void markPodApproved(UUID shipmentId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);
        if (!shipment.isPodApproved()) {
            shipment.setPodApproved(true);
            shipment = shipmentRepository.save(shipment);
        }
        creditDeliveryCommissionsIfEligible(shipment, companyId);
    }

    /** Persists one asset row if {@code url} is actually present — {@code deliver()} calls
     *  this once for signature and once for photo, either of which is commonly blank. */
    private void recordAsset(UUID shipmentId, UUID companyId, ShipmentAssetType type, String kind, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        shipmentAssetRepository.save(ShipmentAsset.builder()
                .shipmentId(shipmentId)
                .assetType(type)
                .kind(kind)
                .assetUrl(url.trim())
                .build());
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public ShipmentAsset attachPodAsset(UUID shipmentId, String kind, String url) {
        UUID companyId = requireCompany();
        loadOrThrow(shipmentId, companyId);
        ShipmentAsset asset = ShipmentAsset.builder()
                .shipmentId(shipmentId)
                .assetType(ShipmentAssetType.POD)
                .kind(kind)
                .assetUrl(url.trim())
                .build();
        return shipmentAssetRepository.save(asset);
    }

    private static final Set<String> BOOKING_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "heic");

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public String uploadShipmentImage(UUID shipmentId, UploadShipmentImageCommand command) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);

        String extension = extensionOf(command.filename());
        if (!BOOKING_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BusinessRuleException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Only JPEG/PNG/WEBP/HEIC images are accepted for a shipment photo.");
        }

        String key = "%s/%s/photo-%s.%s".formatted(companyId, shipmentId, UUID.randomUUID(), extension);
        FileStoragePort.StoredFile stored = fileStoragePort.upload(new FileStoragePort.UploadRequest(
                command.content(), key, command.contentType(), "shipment-photo"));

        recordAsset(shipmentId, companyId, ShipmentAssetType.BOOKING, "PHOTO", stored.url());

        auditService.record(AuditAction.SHIPMENT_IMAGE_UPLOADED, ENTITY, shipment.getId(),
                Map.of("shipmentNumber", shipment.getShipmentNumber()));

        return stored.url();
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public String uploadInScanPhoto(UploadInScanPhotoCommand command) {
        UUID companyId = requireCompany();

        String extension = extensionOf(command.filename());
        if (!BOOKING_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BusinessRuleException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Only JPEG/PNG/WEBP/HEIC images are accepted for an In Scan photo.");
        }

        String key = "%s/in-scan/photo-%s.%s".formatted(companyId, UUID.randomUUID(), extension);
        FileStoragePort.StoredFile stored = fileStoragePort.upload(new FileStoragePort.UploadRequest(
                command.content(), key, command.contentType(), "in-scan-photo"));
        return stored.url();
    }

    private static final Set<String> POD_FILE_KINDS = Set.of("PHOTO", "SIGNATURE");

    /** Original-filename extension -> accepted, deliberately not the declared
     *  {@code Content-Type} header: browsers set that inconsistently for some video/HEIC
     *  variants, while the extension the user actually picked is what ends up in the S3
     *  key anyway. */
    private static final Set<String> POD_FILE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "heic", "mp4", "mov", "pdf");

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public String uploadPodFile(UUID shipmentId, UploadPodFileCommand command) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);

        String kind = command.kind() == null ? "" : command.kind().trim().toUpperCase();
        if (!POD_FILE_KINDS.contains(kind)) {
            throw new BusinessRuleException("File kind must be one of " + POD_FILE_KINDS + ".");
        }
        String extension = extensionOf(command.filename());
        if (!POD_FILE_EXTENSIONS.contains(extension)) {
            throw new BusinessRuleException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Only JPEG/PNG/WEBP/HEIC images, MP4/MOV video, or PDF are accepted for "
                            + "proof of delivery.");
        }

        String key = "%s/%s/%s-%s.%s".formatted(companyId, shipmentId, kind.toLowerCase(),
                UUID.randomUUID(), extension);
        FileStoragePort.StoredFile stored = fileStoragePort.upload(new FileStoragePort.UploadRequest(
                command.content(), key, command.contentType(), "pod"));

        auditService.record(AuditAction.SHIPMENT_POD_UPLOADED, ENTITY, shipment.getId(),
                Map.of("shipmentNumber", shipment.getShipmentNumber(), "kind", kind));

        return stored.url();
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    // MANIFEST_CREATED doubles as "Loading Sheet Created" (V20, on direct request) — one
    // milestone, not two; see ShipmentStatus's own class doc.
    private static final Map<ShipmentStatus, String> TIMELINE_LABELS = Map.of(
            ShipmentStatus.BOOKED, "Booked",
            ShipmentStatus.MANIFEST_CREATED, "Loading Sheet Created",
            ShipmentStatus.DISPATCHED, "Dispatched",
            ShipmentStatus.IN_SCAN, "Received",
            ShipmentStatus.OUT_FOR_DELIVERY, "DRS",
            ShipmentStatus.DELIVERED, "Delivered");

    private static final List<ShipmentStatus> TIMELINE_ORDER = List.of(
            ShipmentStatus.BOOKED, ShipmentStatus.MANIFEST_CREATED,
            ShipmentStatus.DISPATCHED, ShipmentStatus.IN_SCAN, ShipmentStatus.OUT_FOR_DELIVERY,
            ShipmentStatus.DELIVERED);

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<TimelineStep> timeline(UUID shipmentId) {
        UUID companyId = requireCompany();
        Shipment shipment = loadOrThrow(shipmentId, companyId);
        Map<ShipmentStatus, ShipmentStatusHistory> reached = historyRepository
                .findAllByShipmentIdWithinCompany(shipmentId, companyId).stream()
                .collect(Collectors.toMap(ShipmentStatusHistory::getStatus, h -> h,
                        (first, second) -> second));

        List<TimelineStep> steps = new ArrayList<>(TIMELINE_ORDER.size());
        for (ShipmentStatus status : TIMELINE_ORDER) {
            ShipmentStatusHistory entry = reached.get(status);
            steps.add(new TimelineStep(status, TIMELINE_LABELS.get(status),
                    entry == null ? null : entry.getChangedAt(),
                    entry == null ? null : entry.getChangedBy(),
                    entry != null));
        }
        return steps;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<DrsSummary> listDrs(LocalDate from, LocalDate to, UUID deliveryBranchId) {
        UUID companyId = requireCompany();
        LocalDate rangeFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate rangeTo = to != null ? to : LocalDate.now();
        List<DeliveryAssignment> rows = deliveryAssignmentRepository.findByCompanyAndAssignedAtBetween(
                companyId, rangeFrom.atStartOfDay(ZoneOffset.UTC).toInstant(),
                rangeTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        if (deliveryBranchId != null) {
            rows = rows.stream().filter(d -> d.getDeliveryBranchId().equals(deliveryBranchId)).toList();
        }

        return rows.stream()
                .collect(Collectors.groupingBy(d -> new DrsGroupKey(d.getDeliveryUserId(),
                        d.getDeliveryBranchId(), d.getAssignedAt().atZone(ZoneOffset.UTC).toLocalDate())))
                .entrySet().stream()
                .map(e -> {
                    long delivered = e.getValue().stream()
                            .filter(d -> d.getStatus() == DeliveryAssignmentStatus.DELIVERED).count();
                    return new DrsSummary(e.getKey().deliveryUserId(), e.getKey().deliveryBranchId(),
                            e.getKey().runDate(), representativeDrsNumber(e.getValue()), e.getValue().size(),
                            (int) delivered, e.getValue().size() - (int) delivered);
                })
                .sorted(Comparator.comparing(DrsSummary::runDate).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public DrsDetail getDrsDetail(UUID deliveryUserId, UUID deliveryBranchId, LocalDate runDate) {
        UUID companyId = requireCompany();
        List<DeliveryAssignment> assignments = deliveryAssignmentRepository.findByCompanyAndAssignedAtBetween(
                companyId, runDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                runDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                .stream()
                .filter(d -> d.getDeliveryUserId().equals(deliveryUserId)
                        && d.getDeliveryBranchId().equals(deliveryBranchId))
                .toList();
        if (assignments.isEmpty()) {
            throw new ResourceNotFoundException("DRS", deliveryUserId);
        }

        List<UUID> shipmentIds = assignments.stream().map(DeliveryAssignment::getShipmentId).toList();
        Map<UUID, Shipment> shipments = shipmentRepository.findAllByCompanyIdAndIdIn(companyId, shipmentIds)
                .stream().collect(Collectors.toMap(Shipment::getId, s -> s));
        Map<UUID, BigDecimal> netAmounts = chargeRepository.findByShipmentIdIn(shipmentIds).stream()
                .collect(Collectors.toMap(ShipmentCharge::getShipmentId, ShipmentCharge::getNetAmount));
        Map<UUID, String> ewayBillNumbers = ewayBillNumbersFor(shipmentIds);

        List<DrsShipmentRow> rows = assignments.stream()
                .map(a -> {
                    Shipment s = shipments.get(a.getShipmentId());
                    if (s == null) return null;
                    return new DrsShipmentRow(s.getId(), s.getShipmentNumber(), s.getTrackingNumber(),
                            s.getReceiverName(), s.getReceiverAddress(), s.getReceiverContact(), s.getPaymentModeId(),
                            netAmounts.get(s.getId()), s.getStatus(), a.getDeliveredAt(),
                            ewayBillNumbers.get(s.getId()), s.getFromCity(), s.getToCity());
                })
                .filter(Objects::nonNull)
                .toList();
        return new DrsDetail(deliveryUserId, deliveryBranchId, runDate, representativeDrsNumber(assignments), rows);
    }

    /** {@code assignments} is already ordered newest-first (the repository query's own
     *  {@code order by assignedAt desc}) — the most recently generated number in the group
     *  represents the run; null once no assignment in it carries one (pre-V31 rows only). */
    private String representativeDrsNumber(List<DeliveryAssignment> assignments) {
        return assignments.stream().map(DeliveryAssignment::getDrsNumber)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private record DrsGroupKey(UUID deliveryUserId, UUID deliveryBranchId, LocalDate runDate) {
    }

    // ------------------------------------------------------------- cross-module

    /** MSRTC's booking-time delivery branch: mandatory, a different branch from the booking
     *  one, and never combined with Crossing (MSRTC only moves branch to branch). */
    private UUID requireMsrtcDeliveryBranch(UUID companyId, UUID bookingBranchId, UUID deliveryBranchId,
                                            boolean crossing) {
        if (crossing) {
            throw new BusinessRuleException("An MSRTC shipment goes branch to branch — Crossing is not available.");
        }
        if (deliveryBranchId == null) {
            throw new BusinessRuleException("An MSRTC shipment needs a delivery branch.");
        }
        if (deliveryBranchId.equals(bookingBranchId)) {
            throw new BusinessRuleException("The delivery branch must be a different branch from the booking branch.");
        }
        // Through the company-wide lookup, not BranchService.getById: that one is scoped to
        // the caller's own branch, and a branch desk must be able to book to another branch.
        return branchLookup.findBranch(deliveryBranchId, companyId)
                .filter(com.courier.modules.shipment.domain.BranchLookupPort.BranchRef::active)
                .orElseThrow(() -> new BusinessRuleException("The delivery branch was not found or is inactive."))
                .branchId();
    }

    /**
     * Route validation, serviceability and rate matching are all the Pricing Engine's own
     * job — this is the one call that covers all three of the brief's "Serviceability
     * Check", "Route Validation" and "Pricing Engine" workflow steps, since {@link
     * PricingEngine#calculate} already performs every one of them internally. Calling
     * {@code RouteService}/{@code RateService} separately first would duplicate logic
     * Pricing already owns — see {@code MEMORY/modules/shipment-booking.md}.
     *
     * <p>{@code actualWeight} passed here is this module's own already-computed
     * <b>chargeable</b> weight, with no dimensions — {@code ChargeableWeightCalculator
     * .calculate(chargeableWeight, 0) == chargeableWeight}, so the Pricing Engine's own
     * internal volumetric/chargeable recomputation is a no-op and the weight it prices
     * against is exactly the one this module already derived from the item grid.
     *
     * <p>Falling back to the company's distance+weight Freight Factor grid when no
     * route/rate is available for this lane is the Pricing Engine's own job now (any
     * caller of {@link PricingEngine#calculate} gets it, not just this one) — see {@code
     * PricingEngineImpl.priceByDistanceAndWeight}.
     */
    private PricingResult priceIt(UUID bookingBranchId, UUID deliveryBranchId,
                                  String pickupPincode, String deliveryPincode,
                                  UUID serviceTypeId, UUID packageTypeId, UUID paymentModeId,
                                  BigDecimal chargeableWeight, BigDecimal declaredValue,
                                  LocalDate bookingDate, BigDecimal freightFactorOverride,
                                  BigDecimal totalActualWeight, Integer numberOfPackages) {
        PricingCommand command = new PricingCommand(bookingBranchId, deliveryBranchId,
                pickupPincode, deliveryPincode, serviceTypeId, packageTypeId, paymentModeId,
                chargeableWeight, null, null, null, declaredValue, bookingDate, null, null,
                freightFactorOverride, totalActualWeight, numberOfPackages);
        return pricingEngine.calculate(command);
    }

    private void requireSufficientBalance(UUID bookingBranchId, BigDecimal required) {
        Wallet wallet = walletService.getForBranch(bookingBranchId);
        if (wallet.getAvailableBalance().compareTo(required) < 0) {
            throw new BusinessRuleException(
                    "Insufficient wallet balance: available %s, required %s."
                            .formatted(wallet.getAvailableBalance().toPlainString(),
                                    required.toPlainString()));
        }
    }

    /**
     * {@code PackageType.maxWeightKg} — "refuse a booking above this, if set" is that
     * master's own documented rule, otherwise unenforced anywhere until a caller actually
     * books against it. Read-only; this module adds no ceiling of its own.
     */
    private void requireWithinPackageTypeCeiling(UUID packageTypeId, BigDecimal actualWeight) {
        if (packageTypeId == null) {
            return;
        }
        PackageType packageType = packageTypeService.getById(packageTypeId);
        BigDecimal ceiling = packageType.getMaxWeightKg();
        if (ceiling != null && actualWeight.compareTo(ceiling) > 0) {
            throw new BusinessRuleException(
                    "Actual weight %s kg exceeds the %s kg maximum for package type %s."
                            .formatted(actualWeight.toPlainString(), ceiling.toPlainString(),
                                    packageType.getCode()));
        }
    }

    private LocalDate expectedDeliveryDate(LocalDate bookingDate, ServiceType serviceType) {
        Integer days = serviceType.getDeliveryDays();
        return days == null ? null : bookingDate.plusDays(days);
    }

    // ------------------------------------------------------------------- weight

    private record WeightSummary(BigDecimal actualWeight, BigDecimal volumetricWeight,
                                 BigDecimal chargeableWeight) {
    }

    /** Everything {@code EwayBillService.generatePartAForShipment} needs beyond the
     *  user-typed {@code EwayBillDataCommand} — consignor/consignee identity and a short
     *  product description, all already on hand from the shipment being booked/edited.
     *  Product description is a short join of item names since {@code ShipmentItem} has
     *  no free-text description field of its own; "General cargo" when every item is
     *  unnamed, since Part-A needs some non-blank description. */
    private EwayBillService.ShipmentEwayBillContext ewayBillContext(String senderName, String senderAddress,
            String pickupPincode, String receiverName, String receiverAddress, String deliveryPincode,
            List<ShipmentItem> items) {
        String productDescription = items.stream()
                .map(ShipmentItem::getItemName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .limit(3)
                .collect(Collectors.joining(", "));
        return new EwayBillService.ShipmentEwayBillContext(senderName, senderAddress, pickupPincode,
                receiverName, receiverAddress, deliveryPincode,
                productDescription.isBlank() ? "General cargo" : productDescription);
    }

    private List<ShipmentItem> buildItems(List<ShipmentItemCommand> commands,
                                          BigDecimal fallbackWeight, BigDecimal fallbackLength,
                                          BigDecimal fallbackWidth, BigDecimal fallbackHeight) {
        List<ShipmentItemCommand> effective = (commands == null || commands.isEmpty())
                ? List.of(new ShipmentItemCommand("Package", 1, fallbackWeight, fallbackLength,
                        fallbackWidth, fallbackHeight, null, false, false))
                : commands;

        List<ShipmentItem> items = new ArrayList<>(effective.size());
        for (ShipmentItemCommand c : effective) {
            ShipmentItem item = ShipmentItem.builder()
                    .itemName(c.itemName())
                    .quantity(c.quantity() == null ? 1 : c.quantity())
                    .weight(c.weight())
                    .lengthCm(c.lengthCm())
                    .widthCm(c.widthCm())
                    .heightCm(c.heightCm())
                    .declaredValue(c.declaredValue())
                    .fragile(Boolean.TRUE.equals(c.fragile()))
                    .dangerousGoods(Boolean.TRUE.equals(c.dangerousGoods()))
                    .build();
            item.applyInvariants();
            items.add(item);
        }
        return items;
    }

    /**
     * Sums the item grid using the Pricing Engine's own reusable weight utilities —
     * {@link WeightCalculator#normalise}, {@link VolumetricCalculator#calculate} and
     * {@link ChargeableWeightCalculator#calculate} — rather than reimplementing the
     * volumetric formula here. {@code item.getWeight()} is the row's own total (not
     * multiplied by quantity — an operator weighing a packed carton enters what the
     * scale says, not a per-unit figure); each item's own dimensions still contribute
     * their own volumetric weight multiplied by quantity, since L/W/H describe one unit.
     * A shipment with no captured dimensions on any item prices on actual weight alone,
     * the same "not refused" rule {@code VolumetricCalculator} documents for a single
     * parcel.
     *
     * <p>Chargeable weight never prices below the company's own {@code
     * CompanySettings.defaultChargeableWeightKg} — a real pricing floor, not just the
     * item grid's booking-screen prefill: a 10kg actual/volumetric shipment still prices
     * at the company's 15kg minimum, while a 16kg one prices at its own 16kg.
     */
    private WeightSummary summariseWeight(List<ShipmentItem> items) {
        BigDecimal divisor = pricingProperties.toConfiguration().volumetricDivisor();
        BigDecimal actual = BigDecimal.ZERO;
        BigDecimal volumetric = BigDecimal.ZERO;
        for (ShipmentItem item : items) {
            int quantity = item.getQuantity() == null ? 1 : item.getQuantity();
            actual = actual.add(item.getWeight());
            BigDecimal itemVolumetric = VolumetricCalculator.calculate(
                    item.getLengthCm(), item.getWidthCm(), item.getHeightCm(), divisor);
            volumetric = volumetric.add(itemVolumetric.multiply(BigDecimal.valueOf(quantity)));
        }
        BigDecimal normalisedActual = WeightCalculator.normalise(actual);
        BigDecimal normalisedVolumetric = WeightCalculator.normalise(volumetric);
        BigDecimal minimumChargeable = companySettingsService.get().getDefaultChargeableWeightKg();
        BigDecimal chargeable = ChargeableWeightCalculator.calculate(
                normalisedActual, normalisedVolumetric, minimumChargeable);
        return new WeightSummary(normalisedActual, normalisedVolumetric, chargeable);
    }

    /** Sum of every item row's own {@code quantity} — the piece count a qty-level
     *  {@code Charge} (e.g. "Hamali") slab-matches and pays out on, per {@link
     *  ApplicableChargesCalculator}. Deliberately not {@code command.numberOfPackages()},
     *  a separate operator-entered box/carton count that can undercount the real piece
     *  total (e.g. one carton of 10 identical pieces booked as 1 package, 10 quantity). */
    private int totalQuantity(List<ShipmentItem> items) {
        int total = 0;
        for (ShipmentItem item : items) {
            total += item.getQuantity() == null ? 1 : item.getQuantity();
        }
        return total;
    }

    // -------------------------------------------------------------- persistence

    private void persistItems(Shipment shipment, UUID companyId, List<ShipmentItem> items) {
        for (ShipmentItem item : items) {
            item.setCompanyId(companyId);
            item.setShipmentId(shipment.getId());
            itemRepository.save(item);
        }
    }

    /** District Level Freight's own matched-slab rate/KG is a floor, never a ceiling — an
     *  operator may raise it at booking time (e.g. a negotiated premium), never lower it
     *  below what the system calculated. */
    private void requireRateNotDecreased(BigDecimal ratePerKgOverride, FreightCalculationResult freightCalc) {
        if (ratePerKgOverride != null && ratePerKgOverride.compareTo(freightCalc.ratePerKg()) < 0) {
            throw new BusinessRuleException(
                    "Rate/KG cannot be lowered below the calculated rate of %s/KG for %s -> %s."
                            .formatted(freightCalc.ratePerKg().toPlainString(), freightCalc.bookingBranchCode(),
                                    freightCalc.districtName()));
        }
    }

    /** A Business Customer zone's docket/mathadi/pack/fuel/FOV lines ride as Other Charges —
     *  the operator may raise them, never go below the rate card. No-op for District Level Freight. */
    private void requireCustomerExtraCharges(BigDecimal otherCharges, FreightCalculationResult freightCalc) {
        BigDecimal required = freightCalc.customerExtraCharges();
        if (freightCalc.customerZoneName() == null || required == null || required.signum() <= 0) {
            return;
        }
        BigDecimal given = otherCharges == null ? BigDecimal.ZERO : otherCharges;
        if (given.compareTo(required) < 0) {
            throw new BusinessRuleException(
                    "Other Charges must be at least %s — %s (its docket, fuel and other charges)."
                            .formatted(required.toPlainString(), freightCalc.customerZoneName().startsWith("Zone tariff")
                                    ? freightCalc.customerZoneName() : "zone '" + freightCalc.customerZoneName() + "' rate card"));
        }
    }

    /** {@code freightCalc.baseFreight()} unless the operator raised Rate/KG above the
     *  matched slab's own rate — {@link #requireRateNotDecreased} has already refused a
     *  lower one by the time this runs. */
    private static BigDecimal effectiveFreight(FreightCalculationResult freightCalc, BigDecimal ratePerKgOverride) {
        if (ratePerKgOverride == null) {
            return freightCalc.baseFreight();
        }
        return ratePerKgOverride.multiply(freightCalc.chargeableWeight())
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private ShipmentCharge persistCharges(Shipment shipment, UUID companyId, PricingResult priced,
                                          BigDecimal otherCharges, BigDecimal odaCharge,
                                          com.courier.modules.company.domain.Branch bookingBranch,
                                          FreightCalculationResult freightCalc, BigDecimal ratePerKgOverride,
                                          BigDecimal appointmentDeliveryCharge, boolean insuranceApplicable,
                                          BigDecimal doorDeliveryCharge, BigDecimal toPayCharge, BigDecimal invoiceValue) {
        ShipmentCharge charge = toCharge(priced, otherCharges, odaCharge, bookingBranch, freightCalc,
                ratePerKgOverride, appointmentDeliveryCharge, insuranceApplicable, doorDeliveryCharge, toPayCharge, invoiceValue);
        charge.setCompanyId(companyId);
        charge.setShipmentId(shipment.getId());
        return chargeRepository.save(charge);
    }

    private void replaceCharges(Shipment shipment, UUID companyId, PricingResult priced, BigDecimal otherCharges,
                                BigDecimal odaCharge, com.courier.modules.company.domain.Branch bookingBranch,
                                FreightCalculationResult freightCalc, BigDecimal ratePerKgOverride,
                                BigDecimal appointmentDeliveryCharge, boolean insuranceApplicable,
                                BigDecimal doorDeliveryCharge, BigDecimal toPayCharge, BigDecimal invoiceValue) {
        ShipmentCharge existing = chargeRepository
                .findByShipmentIdWithinCompany(shipment.getId(), companyId)
                .orElseGet(() -> {
                    ShipmentCharge fresh = ShipmentCharge.builder().build();
                    fresh.setCompanyId(companyId);
                    fresh.setShipmentId(shipment.getId());
                    return fresh;
                });
        copyCharge(priced, otherCharges, odaCharge, bookingBranch, freightCalc, ratePerKgOverride,
                appointmentDeliveryCharge, insuranceApplicable, doorDeliveryCharge, toPayCharge, invoiceValue, existing);
        chargeRepository.save(existing);
    }

    private ShipmentCharge toCharge(PricingResult priced, BigDecimal otherCharges, BigDecimal odaCharge,
                                    com.courier.modules.company.domain.Branch bookingBranch,
                                    FreightCalculationResult freightCalc, BigDecimal ratePerKgOverride,
                                    BigDecimal appointmentDeliveryCharge, boolean insuranceApplicable,
                                    BigDecimal doorDeliveryCharge, BigDecimal toPayCharge, BigDecimal invoiceValue) {
        ShipmentCharge charge = ShipmentCharge.builder().build();
        copyCharge(priced, otherCharges, odaCharge, bookingBranch, freightCalc, ratePerKgOverride,
                appointmentDeliveryCharge, insuranceApplicable, doorDeliveryCharge, toPayCharge, invoiceValue, charge);
        return charge;
    }

    /** The company's To-Pay charge when {@code paymentMode} collects at delivery (To-Pay), else
     *  zero. Cash-on-delivery also collects at delivery but is the consignee's money, not a To-Pay
     *  freight collection, so it is excluded. Backend-decided — never taken from the request. */
    private BigDecimal toPayChargeFor(PaymentMode paymentMode) {
        if (!paymentMode.isCollectAtDelivery() || paymentMode.isCashOnDelivery()) {
            return BigDecimal.ZERO;
        }
        BigDecimal configured = companySettingsService.get().getToPayCharge();
        return configured == null ? BigDecimal.ZERO : configured;
    }

    /** Scale/rounding for money derived from a percentage — matches the column's own
     *  {@code DECIMAL(19,4)}. */
    private static final int MONEY_SCALE = 4;

    /** {@code insuranceApplicable}'s own rate — direct user request ("2% of Invoice Value
     *  as FOV"), independent of the Pricing Engine's rate-driven {@code
     *  InsuranceCalculator} (which this overrides when checked). */
    private static final BigDecimal INSURANCE_PERCENTAGE = BigDecimal.valueOf(2);

    /**
     * {@code otherCharges} is manual, typed at booking time — not part of the Pricing
     * Engine's own rate-driven breakup — so it is added on top of {@code priced.netAmount()}
     * rather than folded into the engine's calculation. GST on it is likewise computed here,
     * not by the Pricing Engine (which never sees {@code otherCharges} at all): at the
     * <b>booking</b> branch's own {@code gstPercentage} (V25, the same source the commission
     * percentages below already use), folded straight into the persisted {@code gstAmount} —
     * one combined GST figure, not a second line, so every report/receipt that already reads
     * {@code gstAmount} picks it up for free.
     *
     * <p>{@code freight} itself is District Level Freight's own authoritative figure
     * ({@code freightCalc.baseFreight()}), never {@code priced.freight()} — the Pricing
     * Engine is still called (see {@code priceIt}) purely for fuel/handling/insurance/
     * discount/round-off/GST%, which this integration does not change. Same delta trick as
     * {@code odaCharge} below: {@code freightDelta = freightCalc.baseFreight() -
     * priced.freight()} needs fresh GST at the booking branch's own {@code gstPercentage},
     * since {@code priced.gstAmount()} only ever taxed the engine's own (now superseded)
     * freight figure.
     *
     * <p>The commission breakdown (V28) is computed here from the <b>booking</b> branch's
     * own percentages (V25) — "use this percentage from login branch config", each branch
     * carries its own rate. The branch's commission is taken on freight <b>after</b> the
     * company's service charge is deducted, not on the original freight:
     * <ul>
     *   <li>{@code companyCommissionOnBasicFreight = freight *
     *       companyServiceChargePercentage%} — company's cut, off the original freight</li>
     *   <li>{@code remainingFreight = freight - companyCommissionOnBasicFreight}</li>
     *   <li>{@code commissionOnBasicFreight = remainingFreight *
     *       commissionOnBasicFreight%} — branch's cut, off what's left after the company's</li>
     *   <li>{@code branchCommissionOnOtherAmount = otherCharges * (100 -
     *       commissionOnOtherCharges)%} — the branch's remainder after the company's cut</li>
     *   <li>{@code totalCommission = commissionOnBasicFreight +
     *       branchCommissionOnOtherAmount + companyCommissionOnBasicFreight} — every
     *       commission line on this shipment, summed and stored rather than re-derived on
     *       every report</li>
     * </ul>
     * The remaining freight share (after both cuts) is company/head-office revenue, not
     * stored separately.
     *
     * <p>{@code odaCharge} is an optional override of District Level Freight's own {@code
     * freightCalc.odaCharge()} — same manual-at-booking-time idea as {@code otherCharges},
     * except it replaces that line instead of adding a new one. GST is recomputed only on
     * the difference from {@code priced.odaCharge()} (the Pricing Engine's own, now-
     * superseded figure — {@code priced.gstAmount()} only ever taxed that one), at the
     * booking branch's own {@code gstPercentage} — same source {@code gstOnOtherCharges}
     * uses. An unedited ODA still nets out correctly by the same delta-algebra {@code
     * freightDelta} above relies on.
     *
     * <p>{@code appointmentDeliveryCharge} (Appointment Delivery, manual at booking time)
     * is deliberately never taxed with GST, unlike {@code otherCharges} — direct user
     * request. Added straight into {@code netAmount}, with no corresponding line folded
     * into {@code gstAmount}.
     *
     * <p>{@code insuranceApplicable} (checkbox, manual at booking time), when true,
     * replaces the Pricing Engine's own rate-driven {@code priced.insuranceCharge()} with
     * {@code invoiceValue * 2%} (FOV) instead — same delta-and-tax treatment as {@code
     * odaCharge}: only the difference from the engine's figure needs fresh GST, since
     * {@code priced.gstAmount()} only ever taxed the engine's (now possibly superseded)
     * figure. Zero when {@code invoiceValue} isn't set.
     *
     * <p>{@code doorDeliveryCharge} (Door Delivery, manual at booking time, meaningful only
     * when {@code Shipment.deliveryType} is {@code DOOR}) is taxed with GST at the booking
     * branch's own {@code gstPercentage} — same treatment as {@code otherCharges} (direct
     * user request; was previously GST-free like {@code appointmentDeliveryCharge}, which
     * stays untaxed). Callers already zero {@code doorDeliveryCharge} out for {@code OFFICE}
     * before it reaches here, so this method trusts it verbatim.
     *
     * <p>{@code toPayCharge} (the company's To-Pay charge, decided by {@link #toPayChargeFor} —
     * not typed) is taxed with GST at the booking branch's {@code gstPercentage}, same as
     * {@code doorDeliveryCharge}.
     */
    private void copyCharge(PricingResult priced, BigDecimal otherCharges, BigDecimal odaCharge,
                            com.courier.modules.company.domain.Branch bookingBranch,
                            FreightCalculationResult freightCalc, BigDecimal ratePerKgOverride,
                            BigDecimal appointmentDeliveryCharge, boolean insuranceApplicable,
                            BigDecimal doorDeliveryCharge, BigDecimal toPayCharge, BigDecimal invoiceValue, ShipmentCharge charge) {
        BigDecimal safeOtherCharges = otherCharges == null ? BigDecimal.ZERO : otherCharges;
        BigDecimal safeAppointmentDeliveryCharge =
                appointmentDeliveryCharge == null ? BigDecimal.ZERO : appointmentDeliveryCharge;
        BigDecimal safeDoorDeliveryCharge = doorDeliveryCharge == null ? BigDecimal.ZERO : doorDeliveryCharge;
        BigDecimal safeToPayCharge = toPayCharge == null ? BigDecimal.ZERO : toPayCharge;
        BigDecimal gstOnToPayCharge = percentOf(safeToPayCharge, bookingBranch.getGstPercentage());
        BigDecimal safeInvoiceValue = invoiceValue == null ? BigDecimal.ZERO : invoiceValue;
        BigDecimal freight = effectiveFreight(freightCalc, ratePerKgOverride);
        BigDecimal freightDelta = freight.subtract(priced.freight());
        BigDecimal gstOnOtherCharges = gstOnOtherCharges(safeOtherCharges, bookingBranch);
        BigDecimal gstOnDoorDeliveryCharge = percentOf(safeDoorDeliveryCharge, bookingBranch.getGstPercentage());
        BigDecimal gstOnFreightDelta = percentOf(freightDelta, bookingBranch.getGstPercentage());

        BigDecimal finalOdaCharge = odaCharge == null ? freightCalc.odaCharge() : odaCharge;
        BigDecimal odaChargeDelta = finalOdaCharge.subtract(priced.odaCharge());
        BigDecimal gstOnOdaChargeDelta = percentOf(odaChargeDelta, bookingBranch.getGstPercentage());

        BigDecimal finalInsuranceCharge = insuranceApplicable
                ? percentOf(safeInvoiceValue, INSURANCE_PERCENTAGE) : priced.insuranceCharge();
        BigDecimal insuranceChargeDelta = finalInsuranceCharge.subtract(priced.insuranceCharge());
        BigDecimal gstOnInsuranceChargeDelta = percentOf(insuranceChargeDelta, bookingBranch.getGstPercentage());

        BigDecimal branchShareOfOtherCharges = BigDecimal.valueOf(100)
                .subtract(bookingBranch.getCommissionOnOtherCharges());
        BigDecimal companyCommissionOnBasicFreight =
                percentOf(freight, bookingBranch.getCompanyServiceChargePercentage());
        BigDecimal remainingFreight = freight.subtract(companyCommissionOnBasicFreight);
        BigDecimal commissionOnBasicFreight =
                percentOf(remainingFreight, bookingBranch.getCommissionOnBasicFreight());
        BigDecimal branchCommissionOnOtherAmount = percentOf(safeOtherCharges, branchShareOfOtherCharges);
        BigDecimal totalCommission = commissionOnBasicFreight
                .add(branchCommissionOnOtherAmount)
                .add(companyCommissionOnBasicFreight);

        charge.setFreight(freight);
        charge.setFuelCharge(priced.fuelCharge());
        charge.setHandlingCharge(priced.handlingCharge());
        charge.setOdaCharge(finalOdaCharge);
        charge.setInsuranceCharge(finalInsuranceCharge);
        charge.setApplicableCharges(priced.applicableCharges());
        charge.setGstAmount(priced.gstAmount().add(gstOnOtherCharges).add(gstOnOdaChargeDelta).add(gstOnFreightDelta)
                .add(gstOnInsuranceChargeDelta).add(gstOnDoorDeliveryCharge).add(gstOnToPayCharge));
        charge.setDiscountAmount(priced.discountAmount());
        // Other Charges/ODA/Door Delivery/Freight/Insurance deltas are added after the
        // Pricing Engine already rounded its own totalBeforeRoundOff — re-round the full
        // total here rather than reusing priced.roundOff(), or the persisted Net Amount
        // drifts off the company's round-off rule (e.g. no longer a multiple of 5).
        BigDecimal totalBeforeRoundOff = priced.netAmount().subtract(priced.roundOff())
                .add(safeOtherCharges).add(gstOnOtherCharges)
                .add(odaChargeDelta).add(gstOnOdaChargeDelta).add(freightDelta).add(gstOnFreightDelta)
                .add(safeAppointmentDeliveryCharge).add(safeDoorDeliveryCharge).add(gstOnDoorDeliveryCharge)
                .add(insuranceChargeDelta).add(gstOnInsuranceChargeDelta)
                .add(safeToPayCharge).add(gstOnToPayCharge);
        BigDecimal roundedNetAmount = roundOffRule().apply(totalBeforeRoundOff);
        charge.setRoundOff(roundedNetAmount.subtract(totalBeforeRoundOff));
        charge.setOtherCharges(safeOtherCharges);
        charge.setAppointmentDeliveryCharge(safeAppointmentDeliveryCharge);
        charge.setDoorDeliveryCharge(safeDoorDeliveryCharge);
        charge.setToPayCharge(safeToPayCharge);
        charge.setCommissionOnBasicFreight(commissionOnBasicFreight);
        charge.setBranchCommissionOnOtherAmount(branchCommissionOnOtherAmount);
        charge.setCompanyCommissionOnBasicFreight(companyCommissionOnBasicFreight);
        charge.setTotalCommission(totalCommission);
        charge.setNetAmount(roundedNetAmount);
        charge.setMatchedRouteId(priced.matchedRoute() == null ? null : priced.matchedRoute().getId());
        charge.setMatchedRateId(priced.matchedRate() == null ? null : priced.matchedRate().getId());
        charge.setAppliedFreightFactor(priced.appliedFreightFactor());
        charge.setRatePerKgOverride(ratePerKgOverride);
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal percentage) {
        return amount.multiply(percentage)
                .divide(BigDecimal.valueOf(100), MONEY_SCALE, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal gstOnOtherCharges(BigDecimal otherCharges,
                                                com.courier.modules.company.domain.Branch bookingBranch) {
        return percentOf(otherCharges, bookingBranch.getGstPercentage());
    }

    /** Same total {@link #copyCharge} persists as {@code netAmount} — used ahead of that
     *  call too, for the pre-booking wallet-sufficiency check and audit logging, so both
     *  never drift apart. Re-rounds per the company's {@link #roundOffRule()} the same way
     *  {@link #copyCharge} does, rather than reusing {@code priced.roundOff()}. */
    private BigDecimal netAmountWithOtherCharges(PricingResult priced, BigDecimal otherCharges,
                                                  BigDecimal odaCharge,
                                                  com.courier.modules.company.domain.Branch bookingBranch,
                                                  FreightCalculationResult freightCalc,
                                                  BigDecimal ratePerKgOverride,
                                                  BigDecimal appointmentDeliveryCharge,
                                                  boolean insuranceApplicable,
                                                  BigDecimal doorDeliveryCharge,
                                                  BigDecimal toPayCharge,
                                                  BigDecimal invoiceValue) {
        BigDecimal safeOtherCharges = otherCharges == null ? BigDecimal.ZERO : otherCharges;
        BigDecimal safeAppointmentDeliveryCharge =
                appointmentDeliveryCharge == null ? BigDecimal.ZERO : appointmentDeliveryCharge;
        BigDecimal safeDoorDeliveryCharge = doorDeliveryCharge == null ? BigDecimal.ZERO : doorDeliveryCharge;
        BigDecimal safeToPayCharge = toPayCharge == null ? BigDecimal.ZERO : toPayCharge;
        BigDecimal gstOnToPayCharge = percentOf(safeToPayCharge, bookingBranch.getGstPercentage());
        BigDecimal safeInvoiceValue = invoiceValue == null ? BigDecimal.ZERO : invoiceValue;
        BigDecimal gstOnDoorDeliveryCharge = percentOf(safeDoorDeliveryCharge, bookingBranch.getGstPercentage());
        BigDecimal freight = effectiveFreight(freightCalc, ratePerKgOverride);
        BigDecimal freightDelta = freight.subtract(priced.freight());
        BigDecimal gstOnFreightDelta = percentOf(freightDelta, bookingBranch.getGstPercentage());
        BigDecimal finalOdaCharge = odaCharge == null ? freightCalc.odaCharge() : odaCharge;
        BigDecimal odaChargeDelta = finalOdaCharge.subtract(priced.odaCharge());
        BigDecimal gstOnOdaChargeDelta = percentOf(odaChargeDelta, bookingBranch.getGstPercentage());
        BigDecimal finalInsuranceCharge = insuranceApplicable
                ? percentOf(safeInvoiceValue, INSURANCE_PERCENTAGE) : priced.insuranceCharge();
        BigDecimal insuranceChargeDelta = finalInsuranceCharge.subtract(priced.insuranceCharge());
        BigDecimal gstOnInsuranceChargeDelta = percentOf(insuranceChargeDelta, bookingBranch.getGstPercentage());
        BigDecimal totalBeforeRoundOff = priced.netAmount().subtract(priced.roundOff())
                .add(safeOtherCharges).add(gstOnOtherCharges(safeOtherCharges, bookingBranch))
                .add(odaChargeDelta).add(gstOnOdaChargeDelta).add(freightDelta).add(gstOnFreightDelta)
                .add(safeAppointmentDeliveryCharge).add(safeDoorDeliveryCharge).add(gstOnDoorDeliveryCharge)
                .add(insuranceChargeDelta).add(gstOnInsuranceChargeDelta)
                .add(safeToPayCharge).add(gstOnToPayCharge);
        return roundOffRule().apply(totalBeforeRoundOff);
    }

    /** The company's configured {@link RoundingRule} for Shipment Booking's final Net
     *  Amount — see {@code CompanySettings.roundOffRule}. Same resolution (and same blank/
     *  since-renamed-value fallback to {@link PricingProperties#getRoundingRule()}) as
     *  {@code PricingEngineImpl.resolveRoundingRule}, so the two never disagree. */
    private RoundingRule roundOffRule() {
        String stored = companySettingsService.get().getRoundOffRule();
        if (stored == null || stored.isBlank()) {
            return pricingProperties.getRoundingRule();
        }
        try {
            return RoundingRule.valueOf(stored);
        } catch (IllegalArgumentException invalid) {
            return pricingProperties.getRoundingRule();
        }
    }

    private void appendHistory(Shipment shipment, UUID companyId, ShipmentStatus previous,
                               ShipmentStatus status, String remarks) {
        appendHistory(shipment, companyId, previous, status, remarks, null, null, null);
    }

    /**
     * @param branchId   which branch this transition happened at — null for BOOKED/CANCELLED
     * @param manifestId which manifest this transition belongs to — set from MANIFEST_CREATED onward
     * @param vehicleId  which vehicle carried it — set only on the DISPATCHED entry
     */
    private void appendHistory(Shipment shipment, UUID companyId, ShipmentStatus previous,
                               ShipmentStatus status, String remarks, UUID branchId, UUID manifestId,
                               UUID vehicleId) {
        ShipmentStatusHistory entry = ShipmentStatusHistory.builder()
                .shipmentId(shipment.getId())
                .branchId(branchId)
                .manifestId(manifestId)
                .vehicleId(vehicleId)
                .status(status)
                .previousStatus(previous)
                .remarks(remarks)
                .changedBy(SecurityUtils.getCurrentUserId().orElse(null))
                .changedAt(Instant.now())
                .build();
        entry.setCompanyId(companyId);
        historyRepository.save(entry);
    }

    /**
     * Informational only — which lane priced this shipment, for a support desk reading
     * the charge breakup later. Resolved fresh rather than stored, so a route renamed
     * after booking still shows its current code; a route deleted since simply shows
     * none, since {@code matchedRouteId} carries no physical FK (see the migration).
     */
    private String resolveRouteCode(ShipmentCharge charge) {
        if (charge.getMatchedRouteId() == null) {
            return null;
        }
        Route route = tryResolve(() -> routeService.getById(charge.getMatchedRouteId()));
        return route == null ? null : route.getCode();
    }

    private String resolveRateCode(ShipmentCharge charge) {
        if (charge.getMatchedRateId() == null) {
            return null;
        }
        Rate rate = tryResolve(() -> rateService.getById(charge.getMatchedRateId()));
        return rate == null ? null : rate.getRateCode();
    }

    private <T> T tryResolve(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Shipment loadOrThrow(UUID id, UUID companyId) {
        return shipmentRepository.findByIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    /** Frees an auto-generated number ({@code "<BRANCH_CODE>-<6 digits>"} of the booking
     *  branch) for the branch's next booking and renames the cancelled row to
     *  {@code "<number>-CXL"}. Manually typed numbers are left alone. */
    private void releaseShipmentNumber(Shipment shipment) {
        String number = shipment.getShipmentNumber();
        if (shipment.getBookingBranchId() == null || number.length() + 4 > 30) {
            return;
        }
        String branchCode = branchService.getById(shipment.getBookingBranchId()).getBranchCode();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^" + java.util.regex.Pattern.quote(branchCode) + "-(\\d{6})$").matcher(number);
        if (!m.matches()) {
            return;
        }
        shipmentRepository.renameShipmentNumber(TimeOrderedUuid.toBytes(shipment.getId()), number + "-CXL");
        releasedShipmentNumberRepository.release(
                TimeOrderedUuid.toBytes(shipment.getBookingBranchId()), Long.parseLong(m.group(1)));
    }

    /**
     * {@code "<BRANCH_CODE>-<6-digit serial>"} — a per-branch counter, not a random
     * candidate: {@link BranchShipmentSequenceRepository#advance} takes the row's own
     * lock, so no existence-check-and-retry is needed, the same as {@link
     * #nextTrackingNumber}.
     */
    private String nextShipmentNumber(UUID bookingBranchId, String branchCode) {
        byte[] branchIdBytes = TimeOrderedUuid.toBytes(bookingBranchId);
        // A cancelled shipment's number is handed out again before the counter moves.
        java.util.Optional<Long> released = releasedShipmentNumberRepository.take(branchIdBytes);
        if (released.isPresent()) {
            return "%s-%06d".formatted(branchCode, released.get());
        }
        branchShipmentSequenceRepository.advance(branchIdBytes);
        long serial = branchShipmentSequenceRepository.nextValue();
        return "%s-%06d".formatted(branchCode, serial);
    }

    /**
     * {@code "<YYMM><7-digit serial>"}, 11 digits total (e.g. {@code 260800000001}) — a
     * company-wide counter (every branch of a company shares one series), on direct
     * request replacing the old dated-random AWB format. Same collision-free counter
     * contract as {@link #nextShipmentNumber}.
     */
    private String nextTrackingNumber(UUID companyId) {
        byte[] companyIdBytes = TimeOrderedUuid.toBytes(companyId);
        companyShipmentSequenceRepository.advance(companyIdBytes);
        long serial = companyShipmentSequenceRepository.nextValue();
        String yearMonth = YEAR_MONTH.format(Instant.now());
        return "%s%07d".formatted(yearMonth, serial);
    }

    /**
     * {@code "DRS" + 6-digit serial}, e.g. {@code DRS000001} — a company-wide counter, one
     * value per bulk {@link #assignOutForDelivery} call. Same collision-free counter
     * contract as {@link #nextShipmentNumber}/{@link #nextTrackingNumber}.
     */
    private String nextDrsNumber(UUID companyId) {
        byte[] companyIdBytes = TimeOrderedUuid.toBytes(companyId);
        companyDrsSequenceRepository.advance(companyIdBytes);
        long serial = companyDrsSequenceRepository.nextValue();
        return "DRS%06d".formatted(serial);
    }

    private void requireCurrentVersion(Shipment shipment, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        if (!Objects.equals(shipment.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(Shipment.class, shipment.getId());
        }
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Shipments belong to a company, so this "
                        + "operation must be performed by a user of that company."));
    }

    private String currentActor() {
        return SecurityUtils.getCurrentUser()
                .map(user -> user.email() == null ? user.userId().toString() : user.email())
                .orElse("system");
    }
}
