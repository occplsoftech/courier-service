package com.courier.modules.customerbilling.application;

import com.courier.modules.communication.application.provider.EmailProvider;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.modules.customerbilling.domain.BookingSummaryEmailLog;
import com.courier.modules.customerbilling.domain.BookingSummaryEmailLogRepository;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.modules.master.domain.MasterDataSpecifications;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.domain.TimeOrderedUuid;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The nightly booking summary e-mail: every business customer who booked in the window gets one e-mail listing those
 * shipments — AWB, booked at, consignee, destination, pieces, service and status. Deliberately no rate and no weight.
 * A customer without an e-mail address, or with e-mail notifications switched off, gets nothing. Sent as the company
 * (its e-mail channel identity), through the platform's {@link EmailProvider}.
 *
 * <p>No {@code @PreAuthorize}: called only from {@link BookingSummaryEmailJob} on a scheduler thread, never from a
 * controller. Each company runs inside {@link CompanyContext#runAs}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingSummaryEmailService {

    static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ROWS = 500;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final ShipmentRepository shipments;
    private final CustomerRepository customers;
    private final ServiceTypeRepository serviceTypes;
    private final BookingSummaryEmailLogRepository logs;
    private final EmailProvider emailProvider;
    private final InvoiceEmailSender companyIdentity;

    @Value("${app.booking-summary-email.zone:Asia/Kolkata}")
    private String zone = "Asia/Kolkata";

    @PersistenceContext
    private EntityManager em;

    /**
     * Sends the summaries for bookings created in [from, to), recorded against {@code reportDate}.
     *
     * @return how many sends were attempted (customers already sent, or out of attempts, don't count)
     */
    public int sendFor(LocalDate reportDate, Instant from, Instant to) {
        int attempted = 0;
        for (UUID companyId : companiesWithBusinessBookings(from, to)) {
            try {
                attempted += CompanyContext.runAs(companyId, () -> processCompany(companyId, reportDate, from, to));
            } catch (RuntimeException e) {
                log.error("Booking summary e-mail: unexpected failure for company {} on {}", companyId, reportDate, e);
            }
        }
        return attempted;
    }

    @SuppressWarnings("unchecked")
    List<UUID> companiesWithBusinessBookings(Instant from, Instant to) {
        List<Object> rows = em.createNativeQuery("SELECT DISTINCT company_id FROM shipments WHERE deleted = 0"
                        + " AND business_customer_id IS NOT NULL AND created_at >= :f AND created_at < :t")
                .setParameter("f", java.sql.Timestamp.from(from)).setParameter("t", java.sql.Timestamp.from(to))
                .getResultList();
        return rows.stream().map(r -> TimeOrderedUuid.fromBytes((byte[]) r)).toList();
    }

    int processCompany(UUID companyId, LocalDate reportDate, Instant from, Instant to) {
        Map<UUID, List<Shipment>> byCustomer = shipments
                .findBusinessBookingsCreatedBetween(companyId, from, to, ShipmentStatus.CANCELLED).stream()
                .collect(Collectors.groupingBy(Shipment::getBusinessCustomerId, LinkedHashMap::new, Collectors.toList()));
        if (byCustomer.isEmpty()) {
            return 0;
        }
        Map<UUID, String> services = serviceTypes.findAll(MasterDataSpecifications.matching(
                        MasterDataCriteria.none().withCompanyId(companyId))).stream()
                .collect(Collectors.toMap(ServiceType::getId, ServiceType::getName, (a, b) -> a));
        EmailProvider.EmailIdentity identity = null;
        Logo logo = null;
        int attempted = 0;
        for (Map.Entry<UUID, List<Shipment>> e : byCustomer.entrySet()) {
            Customer customer = customers.findByIdWithinCompany(e.getKey(), companyId).orElse(null);
            if (customer == null || !customer.isEmailEnabled() || customer.getEmail() == null
                    || customer.getEmail().isBlank()) {
                continue;
            }
            BookingSummaryEmailLog row = claim(companyId, customer.getId(), reportDate, customer.getEmail().trim());
            if (row == null) {
                continue;
            }
            if (identity == null) {
                identity = companyIdentity.identity(companyId);
                logo = logo(companyId);
            }
            send(row, identity, logo, customer, e.getValue(), services, from, to, reportDate);
            attempted++;
        }
        return attempted;
    }

    /** The row to retry, a fresh one, or null when this customer is already done for the day or out of attempts. */
    private BookingSummaryEmailLog claim(UUID companyId, UUID customerId, LocalDate reportDate, String email) {
        BookingSummaryEmailLog existing = logs
                .findByCompanyIdAndCustomerIdAndReportDate(companyId, customerId, reportDate).orElse(null);
        if (existing != null) {
            return existing.isSent() || existing.getAttemptCount() >= MAX_ATTEMPTS ? null : existing;
        }
        BookingSummaryEmailLog fresh = new BookingSummaryEmailLog();
        fresh.setCompanyId(companyId);
        fresh.setCustomerId(customerId);
        fresh.setReportDate(reportDate);
        fresh.setRecipient(email);
        try {
            return logs.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException e) {
            return null; // another node or tick inserted it between the lookup and here
        }
    }

    private void send(BookingSummaryEmailLog row, EmailProvider.EmailIdentity identity, Logo logo, Customer customer,
                      List<Shipment> list, Map<UUID, String> services, Instant from, Instant to, LocalDate reportDate) {
        row.setRecipient(customer.getEmail().trim());
        row.setBookingCount(list.size());
        try {
            String subject = subject(reportDate, list.size());
            List<EmailProvider.InlineImage> images = logo == null || logo.inline() == null ? List.of() : List.of(logo.inline());
            emailProvider.send(new EmailProvider.EmailMessage(row.getRecipient(), subject,
                    html(identity.fromName(), logo == null ? null : logo.src(), customer.displayName(), list, services, from, to),
                    images), identity);
            row.setStatus(BookingSummaryEmailLog.SENT);
            row.setErrorMessage(null);
            row.setSentAt(Instant.now());
        } catch (RuntimeException e) {
            row.setStatus(BookingSummaryEmailLog.FAILED);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            row.setErrorMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
            log.warn("Booking summary e-mail failed: company={} customer={} day={} attempt={} — {}", row.getCompanyId(),
                    row.getCustomerId(), reportDate, row.getAttemptCount() + 1, message);
        }
        row.setAttemptCount(row.getAttemptCount() + 1);
        row.setLastAttemptAt(Instant.now());
        logs.save(row);
    }

    /** "Your booking details dated: 28 Nov 2026 - 4 shipments". */
    static String subject(LocalDate day, int count) {
        return "Your booking details dated: %s - %d shipment%s".formatted(day.format(DAY), count, count == 1 ? "" : "s");
    }

    /**
     * A plain HTML e-mail: the company logo on top (when it has one), a greeting, the window, and one row per booking —
     * AWB, consignee, destination city, pieces, service. No rate, no weight, no booking time, no pincode, no status.
     *
     * @param logoSrc {@code cid:…} for a logo sent inside the e-mail, an https link, or null for no logo
     */
    String html(String company, String logoSrc, String customerName, List<Shipment> list, Map<UUID, String> services,
                Instant from, Instant to) {
        ZoneId z = ZoneId.of(zone);
        String cell = "padding:6px 8px;border-bottom:1px solid #e5e7eb;text-align:left;vertical-align:top";
        StringBuilder rows = new StringBuilder();
        int i = 0;
        for (Shipment s : list) {
            if (i >= MAX_ROWS) {
                rows.append("<tr><td colspan=\"6\" style=\"").append(cell).append("\">… and ").append(list.size() - MAX_ROWS)
                        .append(" more.</td></tr>");
                break;
            }
            i++;
            rows.append("<tr>")
                    .append(td(cell, String.valueOf(i)))
                    .append(td(cell, awb(s)))
                    .append(td(cell, nz(s.getReceiverName())))
                    .append(td(cell, nz(s.getToCity())))
                    .append(td(cell, s.getNumberOfPackages() == null ? "" : String.valueOf(s.getNumberOfPackages())))
                    .append(td(cell, nz(services.get(s.getServiceTypeId()))))
                    .append("</tr>");
        }
        String head = "padding:6px 8px;background:#f3f4f6;border-bottom:1px solid #d1d5db;text-align:left;font-weight:600";
        String header = List.of("#", "AWB No.", "Consignee", "Destination", "Pieces", "Service").stream()
                .map(h -> "<th style=\"" + head + "\">" + esc(h) + "</th>").collect(Collectors.joining());
        String team = company == null || company.isBlank() ? "" : company;
        String logoHtml = logoSrc == null ? "" : "<p style=\"margin:0 0 16px\"><img src=\"" + esc(logoSrc) + "\" alt=\""
                + esc(team) + "\" style=\"max-height:60px;max-width:240px;display:block\"></p>";
        return "<!doctype html><html><body style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#111827\">"
                + logoHtml
                + "<p>Dear " + esc(customerName == null || customerName.isBlank() ? "Customer" : customerName) + ",</p>"
                + "<p>Here are the " + list.size() + " shipment" + (list.size() == 1 ? "" : "s") + " booked for you between <b>"
                + esc(WHEN.format(from.atZone(z))) + "</b> and <b>" + esc(WHEN.format(to.atZone(z))) + "</b>.</p>"
                + "<table cellspacing=\"0\" cellpadding=\"0\" style=\"border-collapse:collapse;font-size:13px\"><thead><tr>"
                + header + "</tr></thead><tbody>" + rows + "</tbody></table>"
                + "<p style=\"margin-top:16px\">Thanks,<br>Team " + esc(team) + "</p>"
                + "<p style=\"color:#6b7280;font-size:12px\">This is an automatic daily summary of your bookings.</p>"
                + "</body></html>";
    }

    /** The company logo for the e-mail: an uploaded one goes inside the e-mail (Gmail does not show data: images). */
    record Logo(String src, EmailProvider.InlineImage inline) {
    }

    static final String LOGO_CID = "company-logo";

    /** The uploaded logo (Settings → Company Logo), else the logo link on the company record, else none. */
    @SuppressWarnings("unchecked")
    Logo logo(UUID companyId) {
        try {
            List<Object[]> rows = em.createNativeQuery("SELECT logo_data_url, logo FROM companies WHERE company_id = :c")
                    .setParameter("c", TimeOrderedUuid.toBytes(companyId)).getResultList();
            if (rows.isEmpty()) return null;
            return logoFrom((String) rows.get(0)[0], (String) rows.get(0)[1]);
        } catch (RuntimeException e) {
            log.debug("Booking summary e-mail: no company logo for {}: {}", companyId, e.getMessage());
            return null;
        }
    }

    static Logo logoFrom(String dataUrl, String link) {
        if (dataUrl != null && dataUrl.startsWith("data:")) {
            int comma = dataUrl.indexOf(',');
            String meta = comma < 0 ? "" : dataUrl.substring(5, comma);          // image/png;base64
            if (comma > 0 && meta.endsWith(";base64")) {
                try {
                    byte[] bytes = java.util.Base64.getDecoder().decode(dataUrl.substring(comma + 1));
                    String type = meta.substring(0, meta.length() - ";base64".length());
                    return new Logo("cid:" + LOGO_CID, new EmailProvider.InlineImage(LOGO_CID, bytes, type));
                } catch (IllegalArgumentException ignored) {
                    // a broken upload: fall through to the link
                }
            }
        }
        if (link != null && (link.startsWith("https://") || link.startsWith("http://"))) {
            return new Logo(link, null);
        }
        return null;
    }

    /** The AWB: the tracking number, else the shipment number. */
    static String awb(Shipment s) {
        return s.getTrackingNumber() != null && !s.getTrackingNumber().isBlank() ? s.getTrackingNumber() : nz(s.getShipmentNumber());
    }

    private static String td(String style, String value) {
        return "<td style=\"" + style + "\">" + esc(value) + "</td>";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
