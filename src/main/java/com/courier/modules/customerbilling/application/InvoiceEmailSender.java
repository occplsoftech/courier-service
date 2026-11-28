package com.courier.modules.customerbilling.application;

import com.courier.modules.communication.application.CommunicationConfigJson;
import com.courier.modules.communication.application.provider.EmailProvider;
import com.courier.modules.customerbilling.domain.CustomerInvoice;
import com.courier.modules.customerbilling.domain.CustomerInvoiceLine;
import com.courier.shared.domain.TimeOrderedUuid;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * E-mails an approved invoice to the business customer through the platform's {@link EmailProvider}
 * (real SMTP when {@code spring.mail.host} is configured, otherwise the log-only provider — see its
 * javadoc). The sender identity is the company's own e-mail channel identity from Communication
 * Center when one is set, else {@code app.billing.from-email}. The body is an HTML summary with a
 * line per booking; there is no attachment (the provider sends plain HTML).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceEmailSender {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final int MAX_LINES = 300;

    private final EmailProvider emailProvider;
    private final ObjectMapper objectMapper;

    @Value("${app.billing.from-email:billing@courier.local}")
    private String fallbackFromEmail;

    @PersistenceContext
    private EntityManager em;

    /** Outcome of one send attempt; {@code error} is null on success. */
    public record Result(boolean sent, String error) {
    }

    public Result send(UUID companyId, CustomerInvoice invoice, List<CustomerInvoiceLine> lines,
                       String customerName, String toEmail) {
        try {
            emailProvider.send(new EmailProvider.EmailMessage(toEmail,
                    "Invoice %s — %s".formatted(invoice.getInvoiceNumber(), identity(companyId).fromName()),
                    html(invoice, lines, customerName, identity(companyId).fromName())), identity(companyId));
            log.info("Invoice {} e-mailed to {}", invoice.getInvoiceNumber(), toEmail);
            return new Result(true, null);
        } catch (Exception e) {
            log.warn("Invoice {} could not be e-mailed to {}: {}", invoice.getInvoiceNumber(), toEmail, e.getMessage());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(false, msg.length() > 250 ? msg.substring(0, 250) : msg);
        }
    }

    @SuppressWarnings("unchecked")
    /** The company's sender identity: its e-mail channel identity, else its name and the billing fallback address.
     *  Package-private: the nightly booking summary sends as the company too. */
    EmailProvider.EmailIdentity identity(UUID companyId) {
        String name = null;
        String from = null;
        try {
            List<Object> rows = em.createNativeQuery("SELECT config_json FROM communication_setting "
                            + "WHERE company_id = :c AND channel = 'EMAIL' AND deleted = 0")
                    .setParameter("c", TimeOrderedUuid.toBytes(companyId)).getResultList();
            if (!rows.isEmpty() && rows.get(0) != null) {
                Map<String, String> cfg = CommunicationConfigJson.read(objectMapper, rows.get(0).toString());
                name = blankToNull(cfg.get("fromName"));
                from = blankToNull(cfg.get("fromEmail"));
            }
        } catch (RuntimeException e) {
            log.debug("No company e-mail identity, using the fallback: {}", e.getMessage());
        }
        if (name == null) {
            name = companyName(companyId);
        }
        return new EmailProvider.EmailIdentity(name == null ? "Billing" : name, from == null ? fallbackFromEmail : from);
    }

    @SuppressWarnings("unchecked")
    private String companyName(UUID companyId) {
        try {
            List<Object> rows = em.createNativeQuery("SELECT COALESCE(NULLIF(TRIM(display_name), ''), company_name) FROM companies WHERE company_id = :c")
                    .setParameter("c", TimeOrderedUuid.toBytes(companyId)).getResultList();
            return rows.isEmpty() || rows.get(0) == null ? null : blankToNull(rows.get(0).toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String html(CustomerInvoice i, List<CustomerInvoiceLine> lines, String customerName, String company) {
        StringBuilder rows = new StringBuilder();
        int shown = 0;
        for (CustomerInvoiceLine l : lines) {
            if (shown++ >= MAX_LINES) {
                rows.append("<tr><td colspan=\"6\" style=\"padding:6px\">… and ").append(lines.size() - MAX_LINES)
                        .append(" more booking(s) — see the full invoice.</td></tr>");
                break;
            }
            rows.append("<tr><td style=\"padding:6px;border-bottom:1px solid #eee\">").append(esc(l.getShipmentNumber()))
                    .append("</td><td style=\"padding:6px;border-bottom:1px solid #eee\">")
                    .append(l.getBookingDate() == null ? "" : l.getBookingDate().format(DATE))
                    .append("</td><td style=\"padding:6px;border-bottom:1px solid #eee\">").append(esc(l.getConsignee()))
                    .append("</td><td style=\"padding:6px;border-bottom:1px solid #eee;text-align:right\">").append(l.getQuantity())
                    .append("</td><td style=\"padding:6px;border-bottom:1px solid #eee;text-align:right\">").append(l.getActualWeight().stripTrailingZeros().toPlainString())
                    .append("</td><td style=\"padding:6px;border-bottom:1px solid #eee;text-align:right\">").append(money(l.getAmount()))
                    .append("</td></tr>");
        }
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222\">"
                + "<p>Dear " + esc(customerName) + ",</p>"
                + "<p>Please find your invoice <strong>" + esc(i.getInvoiceNumber()) + "</strong> from " + esc(company)
                + " for bookings between " + i.getPeriodFrom().format(DATE) + " and " + i.getPeriodTo().format(DATE) + ".</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:720px\"><thead><tr style=\"background:#f3f4f6\">"
                + "<th align=\"left\" style=\"padding:6px\">Shipment</th><th align=\"left\" style=\"padding:6px\">Date</th>"
                + "<th align=\"left\" style=\"padding:6px\">Consignee</th><th align=\"right\" style=\"padding:6px\">Qty</th>"
                + "<th align=\"right\" style=\"padding:6px\">Wt (kg)</th><th align=\"right\" style=\"padding:6px\">Amount</th></tr></thead><tbody>"
                + rows + "</tbody></table>"
                + "<p style=\"margin-top:12px\">Bookings: <strong>" + i.getShipmentsCount() + "</strong> · Packages: <strong>" + i.getTotalQuantity()
                + "</strong> · Weight: <strong>" + i.getTotalWeight().stripTrailingZeros().toPlainString() + " kg</strong></p>"
                + "<p>Invoice total (incl. GST): <strong>₹" + money(i.getTotalAmount()) + "</strong><br/>"
                + "Paid at booking: ₹" + money(i.getPrepaidAmount()) + "<br/>"
                + "<span style=\"font-size:16px\">Balance due: <strong>₹" + money(i.balance()) + "</strong></span></p>"
                + "<p>Thank you for your business.</p></div>";
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ENGLISH, "%,.2f", v == null ? BigDecimal.ZERO : v);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
