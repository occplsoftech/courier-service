package com.courier.modules.forwarding.application;

import com.courier.modules.company.application.UserService;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.customerportal.rates.application.RateComparisonService;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.CancelOutcome;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.domain.PaymentMode;
import com.courier.modules.master.domain.PaymentModeRepository;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.modules.shipment.application.command.ForwardRecord;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentItem;
import com.courier.modules.shipment.domain.ShipmentItemRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import com.courier.shared.exception.ForbiddenException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Hands a shipment that OCCPL collected and brought to a hub or branch to a connected courier (Delhivery, Shiprocket...). The
 * customer's price is unchanged and they keep the OCCPL tracking number; the courier's AWB, label and cost are recorded on the
 * shipment, which becomes FORWARDED.
 *
 * <p>Who: the company admin, or the manager of the hub or branch that holds the shipment. The courier collects from that hub. The
 * courier is only ever booked once: a forward in progress, unconfirmed or done blocks another, the shipment number is the order
 * reference the courier sees (a courier that refuses duplicate order ids protects us a second time), and an answer we cannot read is
 * kept as UNKNOWN for a person to check with the courier — never retried by itself.
 *
 * <p>Not a transaction: the courier is called outside of any, between the small steps on {@link ShipmentService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForwardingService {

    private static final String AUTHENTICATED = "isAuthenticated()";
    private static final BigDecimal DEFAULT_DIMENSION_CM = BigDecimal.TEN;

    private final ShipmentRepository shipments;
    private final ShipmentItemRepository items;
    private final ShipmentService shipmentService;
    private final BranchRepository branches;
    private final PaymentModeRepository paymentModes;
    private final UserService users;
    private final PincodeService pincodes;
    private final RateComparisonService comparison;
    private final List<CourierProviderAdapter> adapters;

    public record Line(String label, BigDecimal amount) {
    }

    public record ForwardOption(String providerCode, String providerName, String serviceCode, String serviceName, String transportMode,
                                Integer etaMinDays, Integer etaMaxDays, BigDecimal chargeableWeightKg, BigDecimal cost, List<Line> breakdown) {
    }

    public record Unavailable(String providerCode, String providerName, String reason) {
    }

    /** What a hub sees: where the courier would collect, why forwarding is not possible (if so), and the couriers' prices. */
    public record ForwardOptions(UUID shipmentId, String shipmentNumber, String blocker, String collectFrom,
                                 List<ForwardOption> options, List<Unavailable> unavailable) {
    }

    public record ForwardResult(UUID shipmentId, String outcome, String message, String provider, String serviceName, String awb,
                                String labelUrl, BigDecimal cost) {
    }

    // ------------------------------------------------------------------ options

    @PreAuthorize(AUTHENTICATED)
    public ForwardOptions options(UUID shipmentId) {
        UUID companyId = requireCompany();
        Shipment s = load(shipmentId, companyId);
        requireMayForward(s);
        String blocker = blocker(s, companyId);
        if (blocker != null) {
            return new ForwardOptions(s.getId(), s.getShipmentNumber(), blocker, null, List.of(), List.of());
        }
        Branch hub = hub(s, companyId);
        List<ForwardOption> options = new ArrayList<>();
        List<Unavailable> unavailable = new ArrayList<>();
        for (ProviderResult r : comparison.askExternalForForwarding(rateRequest(s, hub, companyId))) {
            if (!r.available()) {
                unavailable.add(new Unavailable(r.providerCode(), r.providerName(), r.reason()));
                continue;
            }
            for (ProviderRate rate : r.rates()) {
                options.add(new ForwardOption(r.providerCode(), r.providerName(), rate.serviceCode(), rate.serviceName(), rate.transportMode(),
                        rate.etaMinDays(), rate.etaMaxDays(), rate.chargeableWeightKg(), rate.providerTotal(),
                        rate.charges().stream().map(c -> new Line(c.label(), c.amount())).toList()));
            }
        }
        options.sort(Comparator.comparing(ForwardOption::cost));
        return new ForwardOptions(s.getId(), s.getShipmentNumber(), null, hub.getBranchName() + ", " + hub.getPostalCode(), options, unavailable);
    }

    // ------------------------------------------------------------------ forward

    /** Books the chosen courier service, priced afresh (nothing the browser sends is a price). */
    @PreAuthorize(AUTHENTICATED)
    public ForwardResult forward(UUID shipmentId, String providerCode, String serviceCode) {
        UUID companyId = requireCompany();
        Shipment s = load(shipmentId, companyId);
        requireMayForward(s);
        String blocker = blocker(s, companyId);
        if (blocker != null) {
            throw new BusinessRuleException(blocker);
        }
        if (providerCode == null || providerCode.isBlank() || serviceCode == null || serviceCode.isBlank()) {
            throw new BusinessRuleException("Choose a courier service to forward to.");
        }
        CourierProviderAdapter adapter = adapters.stream().filter(a -> a.external() && a.code().equals(providerCode)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("Unknown courier " + providerCode + "."));
        Branch hub = hub(s, companyId);
        RateRequest rateRequest = rateRequest(s, hub, companyId);
        ProviderResult priced = comparison.askExternalForForwarding(rateRequest).stream()
                .filter(r -> r.providerCode().equals(providerCode)).findFirst()
                .orElseThrow(() -> new BusinessRuleException(adapter.displayName() + " is not available for this shipment."));
        if (!priced.available()) {
            throw new BusinessRuleException(priced.reason() == null ? adapter.displayName() + " is not available for this shipment." : priced.reason());
        }
        ProviderRate rate = priced.rates().stream().filter(r -> serviceCode.equals(r.serviceCode())).findFirst()
                .orElseThrow(() -> new BusinessRuleException("That service is no longer offered. Check the prices again."));
        BigDecimal cost = rate.providerTotal();

        shipmentService.beginForward(s.getId(), providerCode, rate.serviceName());
        BookingOutcome outcome;
        try {
            outcome = adapter.book(bookingRequest(s, hub, rateRequest, rate, cost, companyId));
        } catch (RuntimeException e) {
            log.error("Forwarding shipment {} to {} failed unexpectedly", s.getShipmentNumber(), providerCode, e);
            // The call may have reached the courier: we cannot say it did not, so it is flagged, not retried.
            outcome = BookingOutcome.unknown(null, "We could not confirm this with " + adapter.displayName() + ".");
        }
        return switch (outcome.result()) {
            case BOOKED -> {
                Shipment done = shipmentService.completeForward(s.getId(), new ForwardRecord(providerCode, rate.serviceName(),
                        outcome.awbNumber(), outcome.providerOrderRef(), outcome.labelUrl(), cost));
                yield new ForwardResult(done.getId(), "FORWARDED", "Forwarded to " + adapter.displayName()
                        + (outcome.awbNumber() == null ? "" : ", AWB " + outcome.awbNumber()) + ".", providerCode, rate.serviceName(),
                        outcome.awbNumber(), outcome.labelUrl(), cost);
            }
            case REJECTED -> {
                shipmentService.abandonForward(s.getId(), false, outcome.message());
                // Staff get the courier's own words when it gave any (a customer would only ever see the generic message).
                throw new BusinessRuleException(adapter.displayName() + " did not accept it: "
                        + (outcome.message() == null ? "no reason given" : outcome.message())
                        + (outcome.detail() == null || outcome.detail().isBlank() ? "" : " " + adapter.displayName() + " says: " + outcome.detail()));
            }
            default -> {
                shipmentService.abandonForward(s.getId(), true, outcome.message(), outcome.providerOrderRef());
                yield new ForwardResult(s.getId(), "UNCONFIRMED", "We could not confirm whether " + adapter.displayName()
                        + " accepted it. Check with the courier before trying again.", providerCode, rate.serviceName(), null, null, cost);
            }
        };
    }

    // ------------------------------------------------------------------ resolving an unconfirmed forward

    public record ResolveResult(UUID shipmentId, String outcome, String message) {
    }

    /**
     * Settles a forward the courier never confirmed. {@code courierTookIt} false: the order is cancelled at the courier and, only once
     * the courier says it is cancelled (or holds no such order), the flag is cleared so the shipment can be forwarded again. True: the
     * AWB the courier gave is recorded by hand and the shipment becomes FORWARDED.
     */
    @PreAuthorize(AUTHENTICATED)
    public ResolveResult resolve(UUID shipmentId, boolean courierTookIt, String awb) {
        UUID companyId = requireCompany();
        Shipment s = load(shipmentId, companyId);
        requireMayForward(s);
        if (!"UNKNOWN".equals(s.getForwardState())) {
            throw new BusinessRuleException("Only a forward that could not be confirmed can be resolved.");
        }
        String providerCode = s.getForwardProvider();
        if (courierTookIt) {
            if (awb == null || awb.isBlank() || awb.trim().length() > 80) {
                throw new BusinessRuleException("Enter the tracking number (AWB) the courier gave.");
            }
            Shipment done = shipmentService.completeForward(s.getId(), new ForwardRecord(providerCode, s.getForwardService(),
                    awb.trim(), s.getForwardOrderRef(), null, null));
            return new ResolveResult(done.getId(), "FORWARDED", "Recorded as forwarded to " + providerCode + ", AWB " + awb.trim() + ".");
        }
        CourierProviderAdapter adapter = adapters.stream().filter(a -> a.external() && a.code().equals(providerCode)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("Unknown courier " + providerCode + "."));
        CancelOutcome cancel;
        try {
            cancel = adapter.cancelOrder(companyId, s.getForwardOrderRef(), s.getShipmentNumber());
        } catch (RuntimeException e) {
            log.error("Cancelling the {} order of shipment {} failed unexpectedly", providerCode, s.getShipmentNumber(), e);
            cancel = CancelOutcome.failed("Could not reach " + adapter.displayName() + ".");
        }
        if (cancel.result() == CancelOutcome.Result.FAILED) {
            throw new BusinessRuleException(cancel.message());
        }
        shipmentService.clearUnconfirmedForward(s.getId(), cancel.message());
        return new ResolveResult(s.getId(), "CLEARED", cancel.message() + " You can forward the shipment again.");
    }

    // ------------------------------------------------------------------ building the request

    private RateRequest rateRequest(Shipment s, Branch hub, UUID companyId) {
        BigDecimal[] dims = dimensions(s, companyId);
        BigDecimal declared = s.getDeclaredValue() == null || s.getDeclaredValue().signum() <= 0 ? BigDecimal.ONE : s.getDeclaredValue();
        return new RateRequest(companyId, s.getBusinessCustomerId(), hub.getPostalCode().trim(), s.getDeliveryPincode(),
                s.getActualWeight(), dims[0], dims[1], dims[2], s.getNumberOfPackages() == null ? 1 : Math.max(1, s.getNumberOfPackages()),
                declared, paymentMode(s, companyId));
    }

    private BookingRequest bookingRequest(Shipment s, Branch hub, RateRequest rate, ProviderRate quoted, BigDecimal cost, UUID companyId) {
        BookingRequest.Party from = new BookingRequest.Party(hub.getBranchName(), hub.getMobile(), address(hub), hub.getPostalCode().trim(),
                clean(hub.getCity()), clean(hub.getState()));
        var geo = pincodes.geography(s.getDeliveryPincode());
        BookingRequest.Party to = new BookingRequest.Party(s.getReceiverName(), s.getReceiverContact(), s.getReceiverAddress(),
                s.getDeliveryPincode(), blank(s.getToCity()) ? geo.map(g -> g.cityName()).orElse(null) : s.getToCity(),
                geo.map(g -> g.stateName()).orElse(null));
        List<ChargeLine> lines = quoted.charges();
        return new BookingRequest(companyId, s.getBusinessCustomerId(), s.getShipmentNumber(), quoted.serviceCode(), rate, from, to,
                blank(s.getRemarks()) ? "Shipment " + s.getShipmentNumber() : s.getRemarks(), cost, lines, quoted.chargeableWeightKg());
    }

    private BigDecimal[] dimensions(Shipment s, UUID companyId) {
        for (ShipmentItem i : items.findAllByShipmentIdWithinCompany(s.getId(), companyId)) {
            if (i.getLengthCm() != null && i.getWidthCm() != null && i.getHeightCm() != null
                    && i.getLengthCm().signum() > 0 && i.getWidthCm().signum() > 0 && i.getHeightCm().signum() > 0) {
                return new BigDecimal[]{i.getLengthCm(), i.getWidthCm(), i.getHeightCm()};
            }
        }
        return new BigDecimal[]{DEFAULT_DIMENSION_CM, DEFAULT_DIMENSION_CM, DEFAULT_DIMENSION_CM};
    }

    private RateRequest.PaymentMode paymentMode(Shipment s, UUID companyId) {
        PaymentMode m = s.getPaymentModeId() == null ? null : paymentModes.findByIdWithinCompany(s.getPaymentModeId(), companyId).orElse(null);
        if (m != null && m.isCashOnDelivery()) {
            return RateRequest.PaymentMode.COD;
        }
        return RateRequest.PaymentMode.PREPAID;
    }

    // ------------------------------------------------------------------ rules

    /** Why this shipment cannot be forwarded now, or null. */
    private String blocker(Shipment s, UUID companyId) {
        String why = s.forwardBlocker();
        if (why != null) {
            return why;
        }
        PaymentMode m = s.getPaymentModeId() == null ? null : paymentModes.findByIdWithinCompany(s.getPaymentModeId(), companyId).orElse(null);
        if (m != null && m.isCollectAtDelivery() && !m.isCashOnDelivery()) {
            return "A To Pay shipment cannot be forwarded: a courier cannot collect OCCPL's freight from the receiver.";
        }
        if (s.getActualWeight() == null || s.getActualWeight().signum() <= 0) {
            return "The shipment has no weight, so a courier cannot price it.";
        }
        if (s.getDeliveryPincode() == null || !s.getDeliveryPincode().matches("[1-9][0-9]{5}")) {
            return "The shipment's delivery pincode is not valid.";
        }
        Branch hub = hub(s, companyId);
        if (hub.getPostalCode() == null || !hub.getPostalCode().trim().matches("[1-9][0-9]{5}")) {
            return "Set the pincode of " + hub.getBranchName() + " first: the courier collects from the hub.";
        }
        return null;
    }

    private Branch hub(Shipment s, UUID companyId) {
        UUID id = s.getCurrentLocationId() != null ? s.getCurrentLocationId() : s.getBookingBranchId();
        return branches.findByIdWithinCompany(id, companyId).orElseThrow(() -> new ResourceNotFoundException("Branch", id));
    }

    /** The company admin, or the manager of the hub or branch that holds the shipment. */
    private void requireMayForward(Shipment s) {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.isPlatformTier() || caller.hasRole(Roles.COMPANY_ADMIN)) {
            return;
        }
        if (caller.hasRole(Roles.BRANCH_MANAGER) || caller.hasRole(Roles.HUB_MANAGER)) {
            UUID own = users.getById(caller.userId()).getBranchId();
            UUID holds = s.getCurrentLocationId() != null ? s.getCurrentLocationId() : s.getBookingBranchId();
            if (own != null && own.equals(holds)) {
                return;
            }
        }
        throw new ForbiddenException(ErrorCode.ACCESS_DENIED,
                "Only the company admin or the manager of the hub or branch holding this shipment can forward it.");
    }

    private Shipment load(UUID id, UUID companyId) {
        return shipments.findByIdWithinCompany(id, companyId).orElseThrow(() -> new ResourceNotFoundException("Shipment", id));
    }

    private static UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException("No company is bound to this request."));
    }

    private static String address(Branch b) {
        return java.util.stream.Stream.of(b.getAddressLine1(), b.getAddressLine2())
                .filter(t -> t != null && !t.isBlank()).map(String::trim).collect(java.util.stream.Collectors.joining(", "));
    }

    /** "Maharashtra (MAHARASHTRA)" as the branch form stores geography becomes "Maharashtra". */
    private static String clean(String name) {
        return name == null ? null : name.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
