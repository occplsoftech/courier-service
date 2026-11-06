package com.courier.modules.customerbilling.application;

import com.courier.modules.customer.domain.BillingFrequency;
import com.courier.modules.customerbilling.domain.CustomerInvoice;
import com.courier.modules.customerbilling.domain.CustomerInvoiceLine;
import com.courier.modules.customerbilling.domain.CustomerInvoiceLineRepository;
import com.courier.modules.customerbilling.domain.CustomerInvoiceRepository;
import com.courier.modules.customerbilling.domain.InvoiceSource;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.domain.TimeOrderedUuid;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds one invoice for one business customer from every non-cancelled booking that is not
 * already on a live invoice. No authorisation of its own — callers are the admin service
 * (COMPANY_ADMIN) and the billing-cycle job (no user at all) — but the company must be bound
 * in {@link CompanyContext}: every query is scoped to it explicitly, since native SQL bypasses
 * the Hibernate company filter.
 *
 * <p>Shipments are matched to the customer by {@code business_customer_id} (set on business-customer bookings, including every
 * customer-portal booking) or, for older counter bookings without it, on the consignor's mobile. A booking on payment mode PAID is listed but counted as
 * {@code prepaid} — it was settled at the counter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerInvoiceGenerator {

    private static final int NUMBER_ATTEMPTS = 20;

    private final CustomerInvoiceRepository invoices;
    private final CustomerInvoiceLineRepository lines;
    private final AuditService auditService;

    @PersistenceContext
    private EntityManager em;

    /** An unbilled booking, as read from {@code shipments}. */
    public record Unbilled(UUID shipmentId, String shipmentNumber, LocalDate bookingDate, String consignee,
                           String destinationPincode, int quantity, BigDecimal weight, String paymentMode,
                           boolean prepaid, BigDecimal gst, BigDecimal amount) {
    }

    public record CustomerBillingInfo(UUID id, String mobile, String type, String status, BillingFrequency frequency) {
    }

    @Transactional(readOnly = true)
    public CustomerBillingInfo customerInfo(UUID customerId) {
        UUID company = requireCompany();
        @SuppressWarnings("unchecked")
        List<Object[]> r = em.createNativeQuery("SELECT mobile, customer_type, status, billing_frequency FROM customers "
                        + "WHERE id = :id AND company_id = :c AND deleted = 0")
                .setParameter("id", TimeOrderedUuid.toBytes(customerId))
                .setParameter("c", TimeOrderedUuid.toBytes(company)).getResultList();
        if (r.isEmpty()) {
            throw new BusinessRuleException("No such customer: " + customerId);
        }
        Object[] row = r.get(0);
        return new CustomerBillingInfo(customerId, String.valueOf(row[0]), String.valueOf(row[1]),
                String.valueOf(row[2]), BillingFrequency.valueOf(String.valueOf(row[3])));
    }

    /** Every booking of the customer, booked on or before {@code upTo}, not cancelled and not on a live invoice. */
    @Transactional(readOnly = true)
    public List<Unbilled> unbilled(UUID customerId, LocalDate upTo) {
        UUID company = requireCompany();
        CustomerBillingInfo c = customerInfo(customerId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT s.id, s.shipment_number, s.created_at, s.receiver_name, s.delivery_pincode, "
                        + "s.number_of_packages, s.actual_weight, pm.name, pm.code, sc.gst_amount, sc.net_amount "
                        + "FROM shipments s LEFT JOIN shipment_charges sc ON sc.shipment_id = s.id AND sc.deleted = 0 "
                        + "LEFT JOIN master_payment_modes pm ON pm.id = s.payment_mode_id "
                        + "WHERE s.company_id = :c AND s.deleted = 0 AND s.status <> 'CANCELLED' "
                        + "AND (s.business_customer_id = :cid OR s.sender_contact = :mobile) AND s.created_at < :before "
                        + "AND NOT EXISTS (SELECT 1 FROM customer_invoice_lines l WHERE l.shipment_id = s.id AND l.deleted = 0) "
                        + "ORDER BY s.created_at, s.shipment_number")
                .setParameter("c", TimeOrderedUuid.toBytes(company))
                .setParameter("mobile", c.mobile())
                .setParameter("cid", TimeOrderedUuid.toBytes(customerId))
                .setParameter("before", Timestamp.from(upTo.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)))
                .getResultList();
        List<Unbilled> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new Unbilled(uuid(r[0]), String.valueOf(r[1]), ((Timestamp) r[2]).toLocalDateTime().toLocalDate(),
                    r[3] == null ? null : r[3].toString(), r[4] == null ? null : r[4].toString(),
                    r[5] == null ? 0 : ((Number) r[5]).intValue(), dec(r[6], 3), r[7] == null ? null : r[7].toString(),
                    "PAID".equals(r[8]), dec(r[9], 2), dec(r[10], 2)));
        }
        return out;
    }

    /**
     * Invoices everything unbilled up to {@code periodTo}. Empty when there is nothing to bill.
     */
    @Transactional
    public Optional<CustomerInvoice> generate(UUID customerId, LocalDate periodTo, InvoiceSource source) {
        UUID company = requireCompany();
        CustomerBillingInfo customer = customerInfo(customerId);
        List<Unbilled> items = unbilled(customerId, periodTo);
        if (items.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        BigDecimal gst = BigDecimal.ZERO, total = BigDecimal.ZERO, prepaid = BigDecimal.ZERO, weight = BigDecimal.ZERO;
        int qty = 0;
        for (Unbilled u : items) {
            gst = gst.add(u.gst()); total = total.add(u.amount()); weight = weight.add(u.weight()); qty += u.quantity();
            if (u.prepaid()) prepaid = prepaid.add(u.amount());
        }
        CustomerInvoice invoice = CustomerInvoice.builder()
                .customerId(customerId).invoiceNumber(nextNumber(company, today)).invoiceDate(today)
                .periodFrom(items.get(0).bookingDate()).periodTo(periodTo)
                .billingFrequency(customer.frequency()).source(source)
                .shipmentsCount(items.size()).totalQuantity(qty).totalWeight(weight)
                .gstAmount(gst).totalAmount(total).prepaidAmount(prepaid).build();
        invoice.refreshStatus();
        CustomerInvoice saved = invoices.save(invoice);
        for (Unbilled u : items) {
            lines.save(CustomerInvoiceLine.builder().invoiceId(saved.getId()).shipmentId(u.shipmentId())
                    .shipmentNumber(u.shipmentNumber()).bookingDate(u.bookingDate()).consignee(u.consignee())
                    .destinationPincode(u.destinationPincode()).quantity(u.quantity()).actualWeight(u.weight())
                    .paymentMode(u.paymentMode()).prepaid(u.prepaid()).gstAmount(u.gst()).amount(u.amount()).build());
        }
        log.info("Customer invoice {} ({}) generated for customer {} in company {}: {} shipment(s), total {}",
                saved.getInvoiceNumber(), source, customerId, company, items.size(), total);
        auditService.record(AuditAction.CUSTOMER_INVOICE_GENERATED, "CustomerInvoice", saved.getId(),
                Map.of("invoiceNumber", saved.getInvoiceNumber(), "customerId", customerId.toString(),
                        "source", source.name(), "shipments", items.size(), "total", total.toPlainString()));
        return Optional.of(saved);
    }

    private String nextNumber(UUID company, LocalDate date) {
        byte[] c = TimeOrderedUuid.toBytes(company);
        long seq = invoices.countEverIssued(c);
        String prefix = "BI%02d%02d-".formatted(date.getYear() % 100, date.getMonthValue());
        for (int i = 0; i < NUMBER_ATTEMPTS; i++) {
            String candidate = prefix + "%04d".formatted(++seq);
            if (invoices.countNumber(c, candidate) == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate an invoice number");
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Invoices belong to a company."));
    }

    private static UUID uuid(Object bytes) {
        ByteBuffer b = ByteBuffer.wrap((byte[]) bytes);
        return new UUID(b.getLong(), b.getLong());
    }

    private static BigDecimal dec(Object o, int scale) {
        return o == null ? BigDecimal.ZERO.setScale(scale) : new BigDecimal(o.toString()).setScale(scale, RoundingMode.HALF_UP);
    }
}
