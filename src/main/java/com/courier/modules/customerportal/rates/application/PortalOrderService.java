package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.application.RateComparisonService.BulkQuote;
import com.courier.modules.customerportal.rates.application.RateComparisonService.CompareCommand;
import com.courier.modules.customerportal.rates.application.RateComparisonService.OptionView;
import com.courier.modules.customerportal.rates.application.RateComparisonService.PackageInput;
import com.courier.modules.customerportal.rates.application.RateComparisonService.PartyInput;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.PortalOrder;
import com.courier.modules.customerportal.rates.domain.PortalOrderRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.ApiException;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The orders inbox (13.3): orders arrive from the customer's own system (an API key now; Shopify and WooCommerce next), the customer
 * checks them in "Orders to ship" and books them through the normal quote and booking (courier access, markup, wallet all apply). A
 * booked order points at its booking and reports its tracking number back to the customer's system.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalOrderService {

    static final int MAX_QUOTE = 100;
    private static final Pattern PINCODE = Pattern.compile("^[1-9][0-9]{5}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9#._/-]{1,80}$");
    private static final BigDecimal DEFAULT_WEIGHT = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_SIDE_CM = BigDecimal.TEN;
    private static final BigDecimal MAX_WEIGHT = new BigDecimal("1000");
    private static final BigDecimal MAX_SIDE = new BigDecimal("500");
    private static final BigDecimal MAX_VALUE = new BigDecimal("10000000");

    private final PortalOrderRepository orders;
    private final PortalBookingRepository bookings;
    private final CurrentCustomerResolver resolver;
    private final RateComparisonService comparison;
    private final PortalBulkBookingService bulk;
    private final AuditService auditService;

    // ------------------------------------------------------------------ input

    public record Buyer(String name, String phone, String address, String pincode, String city, String state) {
    }

    public record Parcel(BigDecimal weightKg, BigDecimal lengthCm, BigDecimal breadthCm, BigDecimal heightCm, Integer count, String contents,
                         BigDecimal declaredValue) {
    }

    /** {@code mode}: PREPAID (default) or COD; {@code codAmount} defaults to the declared value for COD. */
    public record Payment(String mode, BigDecimal codAmount) {
    }

    public record Item(String name, Integer quantity, BigDecimal price) {
    }

    public record OrderInput(String orderNumber, Buyer buyer, Parcel parcel, Payment payment, List<Item> items, String notes) {
    }

    // ------------------------------------------------------------------ output

    public record OrderView(UUID id, String source, String orderNumber, String status, Buyer buyer, Parcel parcel, Payment payment,
                            List<Map<String, Object>> items, String notes, Instant receivedAt, UUID bookingId, Instant bookedAt,
                            String trackingNumber, String courier) {
    }

    /** What the customer's system gets back. */
    public record Received(UUID orderId, String orderNumber, String status, boolean created) {
    }

    public record Status(String orderNumber, String status, Instant receivedAt, Instant bookedAt, String trackingNumber, String courier,
                         String bookingReference) {
    }

    public record Counts(long newOrders, long booked, long archived) {
    }

    /** One order priced for booking: like a bulk preview row, keyed by the order. */
    public record QuoteRow(UUID orderId, String orderNumber, String deliveryName, String deliveryPincode, boolean ok, String error,
                           UUID quoteId, UUID optionId, String providerCode, String providerName, String serviceName, String basis,
                           BigDecimal total) {
    }

    // ------------------------------------------------------------------ the public API (an API key)

    /** Saves an order from the customer's system; the same order number again returns the order already saved. */
    @Transactional
    public Received receive(CustomerApiKeyService.KeyOwner owner, OrderInput in) {
        return CompanyContext.runAs(owner.companyId(), () -> {
            String ref = ref(in == null ? null : in.orderNumber());
            Optional<PortalOrder> existing = orders.findByRef(owner.companyId(), owner.customerId(), PortalOrder.Source.API, ref);
            if (existing.isPresent()) {
                return new Received(existing.get().getId(), ref, existing.get().getStatus().name(), false);
            }
            PortalOrder o = PortalOrder.builder().customerId(owner.customerId()).source(PortalOrder.Source.API).externalRef(ref).build();
            apply(o, in);
            try {
                PortalOrder saved = orders.saveAndFlush(o);
                auditService.record(AuditAction.PORTAL_ORDER_RECEIVED, "PortalOrder", saved.getId(),
                        Map.of("customerId", owner.customerId().toString(), "orderNumber", ref, "source", "API", "keyId", owner.keyId().toString()));
                return new Received(saved.getId(), ref, saved.getStatus().name(), true);
            } catch (DataIntegrityViolationException raced) {
                PortalOrder other = orders.findByRef(owner.companyId(), owner.customerId(), PortalOrder.Source.API, ref).orElseThrow(() -> raced);
                return new Received(other.getId(), ref, other.getStatus().name(), false);
            }
        });
    }

    @Transactional(readOnly = true)
    public Status status(CustomerApiKeyService.KeyOwner owner, String orderNumber) {
        return CompanyContext.runAs(owner.companyId(), () -> {
            String ref = ref(orderNumber);
            PortalOrder o = orders.findByRef(owner.companyId(), owner.customerId(), PortalOrder.Source.API, ref)
                    .orElseThrow(() -> new ResourceNotFoundException("Order", ref));
            Optional<PortalBooking> b = booking(o);
            return new Status(ref, o.getStatus().name(), o.getCreatedAt(), o.getBookedAt(), b.map(PortalBooking::getAwbNumber).orElse(null),
                    b.map(PortalBooking::getProviderCode).orElse(null), b.map(PortalBooking::getProviderOrderRef).orElse(null));
        });
    }

    // ------------------------------------------------------------------ the portal (signed-in customer)

    @Transactional(readOnly = true)
    public Page<OrderView> list(String status, int page, int size) {
        ActingCustomer acting = resolver.require();
        Set<PortalOrder.Status> statuses = status == null || status.isBlank() ? EnumSet.of(PortalOrder.Status.NEW)
                : "ALL".equalsIgnoreCase(status) ? EnumSet.allOf(PortalOrder.Status.class)
                : EnumSet.of(parseStatus(status));
        return orders.findForCustomer(acting.companyId(), acting.customerId(), statuses,
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)))).map(this::view);
    }

    @Transactional(readOnly = true)
    public Counts counts() {
        ActingCustomer acting = resolver.require();
        Map<String, Long> by = new LinkedHashMap<>();
        for (Object[] row : orders.countByStatus(acting.companyId(), acting.customerId())) {
            by.put(String.valueOf(row[0]), (Long) row[1]);
        }
        return new Counts(by.getOrDefault("NEW", 0L), by.getOrDefault("BOOKED", 0L), by.getOrDefault("ARCHIVED", 0L));
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID id) {
        return view(owned(id));
    }

    /** Corrects an order before it is booked (the same checks as when it arrived). */
    @Transactional
    public OrderView update(UUID id, OrderInput in) {
        PortalOrder o = owned(id);
        requireNew(o);
        apply(o, new OrderInput(o.getExternalRef(), in.buyer(), in.parcel(), in.payment(), in.items() == null ? null : in.items(), in.notes()));
        return view(orders.save(o));
    }

    @Transactional
    public OrderView archive(UUID id) {
        PortalOrder o = owned(id);
        requireNew(o);
        o.setStatus(PortalOrder.Status.ARCHIVED);
        return view(orders.save(o));
    }

    @Transactional
    public OrderView restore(UUID id) {
        PortalOrder o = owned(id);
        if (o.getStatus() == PortalOrder.Status.ARCHIVED) {
            o.setStatus(PortalOrder.Status.NEW);
            orders.save(o);
        }
        return view(o);
    }

    /**
     * Prices several orders for booking at once, as bulk booking does: the customer's own business rate when OCCPL Network has one,
     * otherwise the cheapest allowed courier. Pickup is the customer's default pickup address. Each row is booked with the bulk booking's
     * "book" call; the booking then marks its order booked.
     */
    public List<QuoteRow> quote(List<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new BusinessRuleException("Choose the orders to book.");
        }
        if (orderIds.size() > MAX_QUOTE) {
            throw new BusinessRuleException("At most " + MAX_QUOTE + " orders at a time.");
        }
        PortalBulkBookingService.Contact contact = bulk.contact();
        List<QuoteRow> rows = new ArrayList<>();
        for (UUID id : orderIds) {
            PortalOrder o;
            try {
                o = owned(id);
            } catch (ApiException e) {
                rows.add(new QuoteRow(id, null, null, null, false, e.getMessage(), null, null, null, null, null, null, null));
                continue;
            }
            try {
                requireNew(o);
                BulkQuote q = comparison.compareForBulk(command(o, contact));
                if (q.optionId() == null) {
                    String why = q.view().unavailable().stream().map(u -> u.providerName() + ": " + u.reason()).findFirst()
                            .orElse("No courier is available for this order.");
                    rows.add(failed(o, why));
                    continue;
                }
                OptionView opt = q.view().options().stream().filter(x -> x.optionId().equals(q.optionId())).findFirst().orElseThrow();
                rows.add(new QuoteRow(o.getId(), o.getExternalRef(), o.getDeliveryName(), o.getDeliveryPincode(), true, null,
                        q.view().quoteId(), opt.optionId(), opt.providerCode(), opt.providerName(), opt.serviceName(), q.basis(), opt.totalAmount()));
            } catch (ApiException e) {
                rows.add(failed(o, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Pricing order {} failed", o.getId(), e);
                rows.add(failed(o, "This order could not be priced."));
            }
        }
        return rows;
    }

    /** The quote request for one order: pickup from the customer's profile, delivery and parcel from the order. */
    CompareCommand command(PortalOrder o, PortalBulkBookingService.Contact contact) {
        String city = o.getDeliveryCity() != null ? o.getDeliveryCity() : bulk.city(o.getDeliveryPincode());
        String state = o.getDeliveryState() != null ? o.getDeliveryState() : bulk.state(o.getDeliveryPincode());
        return new CompareCommand(
                new PartyInput(contact.name(), contact.phone(), contact.address(), contact.pincode(), null, null),
                new PartyInput(o.getDeliveryName(), o.getDeliveryPhone(), o.getDeliveryAddress(), o.getDeliveryPincode(), city, state),
                new PackageInput(o.getWeightKg(), o.getLengthCm(), o.getBreadthCm(), o.getHeightCm(), "CM", o.getPackageCount(), o.getContents(),
                        o.getDeclaredValue(), o.getPaymentMode(), "COD".equals(o.getPaymentMode()) ? o.getCodAmount() : null),
                null, o.getId());
    }

    /** A booking made from an order succeeded: the order is booked (once). Called by the booking step, in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBooked(UUID companyId, UUID orderId, UUID bookingId) {
        CompanyContext.runAs(companyId, () -> orders.findById(orderId).filter(o -> companyId.equals(o.getCompanyId()))
                .filter(o -> o.getStatus() == PortalOrder.Status.NEW).ifPresent(o -> {
                    o.setStatus(PortalOrder.Status.BOOKED);
                    o.setBookingId(bookingId);
                    o.setBookedAt(Instant.now());
                    orders.save(o);
                    auditService.record(AuditAction.PORTAL_ORDER_BOOKED, "PortalOrder", o.getId(),
                            Map.of("orderNumber", o.getExternalRef(), "bookingId", bookingId.toString()));
                }));
    }

    // ------------------------------------------------------------------ helpers

    private PortalOrder owned(UUID id) {
        ActingCustomer acting = resolver.require();
        return orders.findOwned(id, acting.companyId(), acting.customerId()).orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }

    private static void requireNew(PortalOrder o) {
        if (o.getStatus() != PortalOrder.Status.NEW) {
            throw new BusinessRuleException("Order " + o.getExternalRef() + " is already " + o.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
    }

    private static QuoteRow failed(PortalOrder o, String why) {
        return new QuoteRow(o.getId(), o.getExternalRef(), o.getDeliveryName(), o.getDeliveryPincode(), false, why,
                null, null, null, null, null, null, null);
    }

    private Optional<PortalBooking> booking(PortalOrder o) {
        return o.getBookingId() == null ? Optional.empty() : bookings.findById(o.getBookingId()).filter(b -> o.getCompanyId().equals(b.getCompanyId()));
    }

    private OrderView view(PortalOrder o) {
        Optional<PortalBooking> b = booking(o);
        return new OrderView(o.getId(), o.getSource().name(), o.getExternalRef(), o.getStatus().name(),
                new Buyer(o.getDeliveryName(), o.getDeliveryPhone(), o.getDeliveryAddress(), o.getDeliveryPincode(), o.getDeliveryCity(),
                        o.getDeliveryState()),
                new Parcel(o.getWeightKg(), o.getLengthCm(), o.getBreadthCm(), o.getHeightCm(), o.getPackageCount(), o.getContents(),
                        o.getDeclaredValue()),
                new Payment(o.getPaymentMode(), o.getCodAmount()), o.getItems(), o.getNotes(), o.getCreatedAt(), o.getBookingId(),
                o.getBookedAt(), b.map(PortalBooking::getAwbNumber).orElse(null), b.map(PortalBooking::getProviderCode).orElse(null));
    }

    private static PortalOrder.Status parseStatus(String raw) {
        try {
            return PortalOrder.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Status must be NEW, BOOKED, ARCHIVED or ALL.");
        }
    }

    static String ref(String raw) {
        String r = raw == null ? "" : raw.trim();
        if (!REF.matcher(r).matches()) {
            throw new BusinessRuleException("orderNumber is required: up to 80 letters, digits or # . _ / -.");
        }
        return r;
    }

    /** Checks and copies the order's fields; a missing size or weight takes the defaults (0.5 kg, 10 x 10 x 10 cm, 1 package). */
    static void apply(PortalOrder o, OrderInput in) {
        Buyer b = in == null ? null : in.buyer();
        if (b == null) {
            throw new BusinessRuleException("buyer is required: name, phone, address and pincode.");
        }
        o.setDeliveryName(text(b.name(), "buyer.name", 100));
        o.setDeliveryPhone(phone(b.phone()));
        String address = text(b.address(), "buyer.address", 300);
        if (address.length() < 10) {
            throw new BusinessRuleException("buyer.address is too short: give the full delivery address.");
        }
        o.setDeliveryAddress(address);
        String pin = b.pincode() == null ? "" : b.pincode().trim();
        if (!PINCODE.matcher(pin).matches()) {
            throw new BusinessRuleException("buyer.pincode must be a 6-digit Indian pincode.");
        }
        o.setDeliveryPincode(pin);
        o.setDeliveryCity(optional(b.city(), 80));
        o.setDeliveryState(optional(b.state(), 80));

        Parcel p = in.parcel() == null ? new Parcel(null, null, null, null, null, null, null) : in.parcel();
        o.setWeightKg(positive(p.weightKg() == null ? DEFAULT_WEIGHT : p.weightKg(), "parcel.weightKg", MAX_WEIGHT).setScale(3, RoundingMode.HALF_UP));
        o.setLengthCm(positive(p.lengthCm() == null ? DEFAULT_SIDE_CM : p.lengthCm(), "parcel.lengthCm", MAX_SIDE));
        o.setBreadthCm(positive(p.breadthCm() == null ? DEFAULT_SIDE_CM : p.breadthCm(), "parcel.breadthCm", MAX_SIDE));
        o.setHeightCm(positive(p.heightCm() == null ? DEFAULT_SIDE_CM : p.heightCm(), "parcel.heightCm", MAX_SIDE));
        int count = p.count() == null ? 1 : p.count();
        if (count < 1 || count > 999) {
            throw new BusinessRuleException("parcel.count must be 1 to 999.");
        }
        o.setPackageCount(count);
        o.setContents(p.contents() == null || p.contents().isBlank() ? "General goods" : text(p.contents(), "parcel.contents", 200));
        if (p.declaredValue() == null) {
            throw new BusinessRuleException("parcel.declaredValue is required (the value of the goods, in rupees).");
        }
        o.setDeclaredValue(positive(p.declaredValue(), "parcel.declaredValue", MAX_VALUE).setScale(2, RoundingMode.HALF_UP));

        Payment pay = in.payment() == null ? new Payment("PREPAID", null) : in.payment();
        String mode = pay.mode() == null || pay.mode().isBlank() ? "PREPAID" : pay.mode().trim().toUpperCase(Locale.ROOT);
        if (!mode.equals("PREPAID") && !mode.equals("COD")) {
            throw new BusinessRuleException("payment.mode must be PREPAID or COD.");
        }
        o.setPaymentMode(mode);
        o.setCodAmount(mode.equals("COD")
                ? positive(pay.codAmount() == null ? o.getDeclaredValue() : pay.codAmount(), "payment.codAmount", MAX_VALUE).setScale(2, RoundingMode.HALF_UP)
                : null);

        if (in.items() != null) {
            if (in.items().size() > 100) {
                throw new BusinessRuleException("At most 100 items per order.");
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (Item i : in.items()) {
                if (i == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", i.name() == null ? "" : i.name().trim().substring(0, Math.min(150, i.name().trim().length())));
                m.put("quantity", i.quantity() == null ? 1 : i.quantity());
                if (i.price() != null) {
                    m.put("price", i.price());
                }
                items.add(m);
            }
            o.setItems(items);
        }
        o.setNotes(optional(in.notes(), 500));
    }

    private static String text(String v, String field, int max) {
        String t = v == null ? "" : v.trim();
        if (t.isEmpty()) {
            throw new BusinessRuleException(field + " is required.");
        }
        if (t.length() > max) {
            throw new BusinessRuleException(field + " is longer than " + max + " characters.");
        }
        return t;
    }

    private static String optional(String v, int max) {
        String t = v == null ? null : v.trim();
        return t == null || t.isEmpty() ? null : (t.length() > max ? t.substring(0, max) : t);
    }

    /** An Indian mobile: 10 digits, optionally with +91 / 91 / 0 in front. */
    static String phone(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (!digits.matches("^[6-9][0-9]{9}$")) {
            throw new BusinessRuleException("buyer.phone must be a 10-digit Indian mobile number.");
        }
        return digits;
    }

    private static BigDecimal positive(BigDecimal v, String field, BigDecimal max) {
        if (v.signum() <= 0 || v.compareTo(max) > 0) {
            throw new BusinessRuleException(field + " must be above 0 and at most " + max.toPlainString() + ".");
        }
        return v;
    }
}
