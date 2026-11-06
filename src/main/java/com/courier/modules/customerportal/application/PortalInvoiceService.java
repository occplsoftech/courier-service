package com.courier.modules.customerportal.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerbilling.domain.CustomerInvoice;
import com.courier.modules.customerbilling.domain.CustomerInvoiceLineRepository;
import com.courier.modules.customerbilling.domain.CustomerInvoicePayment;
import com.courier.modules.customerbilling.domain.CustomerInvoicePaymentRepository;
import com.courier.modules.customerbilling.domain.CustomerInvoiceRepository;
import com.courier.modules.customerbilling.domain.InvoiceStatus;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The signed-in customer's own invoices (approved ones only), their payments, and a statement of account — read-only. Invoices and
 * payments are made by staff (Business Billing); the portal only shows them. The amount a customer owes on an invoice is its total
 * minus the part already paid at booking ({@code prepaid}).
 */
@Service
@RequiredArgsConstructor
public class PortalInvoiceService {

    static final int MAX_STATEMENT_DAYS = 366;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final CurrentCustomerResolver resolver;
    private final CustomerInvoiceRepository invoices;
    private final CustomerInvoiceLineRepository lines;
    private final CustomerInvoicePaymentRepository payments;
    private final CustomerPortalLookup customers;

    public record InvoiceRow(UUID id, String invoiceNumber, LocalDate invoiceDate, LocalDate periodFrom, LocalDate periodTo,
                             int shipments, BigDecimal total, BigDecimal prepaid, BigDecimal due, BigDecimal paid, BigDecimal balance,
                             String status) {
    }

    public record Summary(BigDecimal outstanding, int unpaidInvoices, LocalDate lastPaymentOn, BigDecimal lastPaymentAmount,
                          List<InvoiceRow> invoices) {
    }

    public record Line(String shipmentNumber, LocalDate bookingDate, String consignee, String destinationPincode, int quantity,
                       BigDecimal weight, String paymentMode, boolean prepaid, BigDecimal gst, BigDecimal amount) {
    }

    public record Payment(LocalDate paidOn, BigDecimal amount, String method, String reference) {
    }

    public record InvoiceDetail(InvoiceRow invoice, BigDecimal gst, String customerName, List<Line> lines, List<Payment> payments) {
    }

    public record StatementLine(LocalDate date, String kind, String reference, BigDecimal debit, BigDecimal credit, BigDecimal balance) {
    }

    public record Statement(LocalDate from, LocalDate to, BigDecimal opening, BigDecimal invoiced, BigDecimal paid, BigDecimal closing,
                            List<StatementLine> lines) {
    }

    // ------------------------------------------------------------------ invoices

    @Transactional(readOnly = true)
    public Summary summary() {
        ActingCustomer me = resolver.require();
        List<InvoiceRow> rows = approved(me).stream().map(this::row).toList();
        BigDecimal outstanding = rows.stream().filter(r -> !"CANCELLED".equals(r.status())).map(InvoiceRow::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int unpaid = (int) rows.stream().filter(r -> !"CANCELLED".equals(r.status()) && r.balance().signum() > 0).count();
        CustomerInvoicePayment last = null;
        for (CustomerInvoice i : approved(me)) {
            for (CustomerInvoicePayment p : payments.findByInvoice(me.companyId(), i.getId())) {
                if (last == null || (p.getPaidOn() != null && last.getPaidOn() != null && p.getPaidOn().isAfter(last.getPaidOn()))) {
                    last = p;
                }
            }
        }
        return new Summary(outstanding, unpaid, last == null ? null : last.getPaidOn(), last == null ? null : last.getAmount(), rows);
    }

    @Transactional(readOnly = true)
    public InvoiceDetail detail(UUID invoiceId) {
        ActingCustomer me = resolver.require();
        CustomerInvoice i = approved(me).stream().filter(x -> x.getId().equals(invoiceId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId));
        List<Line> ls = lines.findByInvoice(me.companyId(), i.getId()).stream()
                .map(l -> new Line(l.getShipmentNumber(), l.getBookingDate(), l.getConsignee(), l.getDestinationPincode(), l.getQuantity(),
                        l.getActualWeight(), l.getPaymentMode(), l.isPrepaid(), nz(l.getGstAmount()), nz(l.getAmount()))).toList();
        List<Payment> ps = payments.findByInvoice(me.companyId(), i.getId()).stream()
                .map(p -> new Payment(p.getPaidOn(), nz(p.getAmount()), p.getMethod() == null ? null : p.getMethod().name(), p.getReference()))
                .toList();
        String name = customers.findActiveBusinessCustomer(me.companyId(), me.customerId()).map(c -> c.getCompanyName()).orElse(null);
        return new InvoiceDetail(row(i), nz(i.getGstAmount()), name, ls, ps);
    }

    // ------------------------------------------------------------------ statement

    /** Invoices (amount due) and payments from {@code from} to {@code to}, with the balance before and after. */
    @Transactional(readOnly = true)
    public Statement statement(LocalDate from, LocalDate to) {
        ActingCustomer me = resolver.require();
        LocalDate today = LocalDate.now(INDIA);
        LocalDate start = from == null ? today.withDayOfYear(1) : from;
        LocalDate end = to == null ? today : to;
        if (start.isAfter(end)) {
            throw new BusinessRuleException("The start date must be on or before the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_STATEMENT_DAYS) {
            throw new BusinessRuleException("A statement covers at most " + MAX_STATEMENT_DAYS + " days. Choose a shorter period.");
        }
        List<StatementLine> moves = new ArrayList<>();
        for (CustomerInvoice i : approved(me)) {
            if (i.getStatus() == InvoiceStatus.CANCELLED) {
                continue;
            }
            moves.add(new StatementLine(i.getInvoiceDate(), "INVOICE", i.getInvoiceNumber(), due(i), BigDecimal.ZERO, null));
            for (CustomerInvoicePayment p : payments.findByInvoice(me.companyId(), i.getId())) {
                moves.add(new StatementLine(p.getPaidOn(), "PAYMENT",
                        i.getInvoiceNumber() + (p.getReference() == null || p.getReference().isBlank() ? "" : " · " + p.getReference()),
                        BigDecimal.ZERO, nz(p.getAmount()), null));
            }
        }
        moves.sort(Comparator.comparing(StatementLine::date, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(l -> "PAYMENT".equals(l.kind()) ? 1 : 0));
        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal invoiced = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        List<StatementLine> inPeriod = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (StatementLine m : moves) {
            running = running.add(m.debit()).subtract(m.credit());
            if (m.date() == null || m.date().isBefore(start)) {
                opening = running;
            } else if (!m.date().isAfter(end)) {
                invoiced = invoiced.add(m.debit());
                paid = paid.add(m.credit());
                inPeriod.add(new StatementLine(m.date(), m.kind(), m.reference(), m.debit(), m.credit(), running));
            }
        }
        BigDecimal closing = opening.add(invoiced).subtract(paid);
        return new Statement(start, end, opening, invoiced, paid, closing, inPeriod);
    }

    /** The statement as an Excel sheet. */
    public byte[] statementSheet(LocalDate from, LocalDate to) {
        Statement s = statement(from, to);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Statement");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            int r = 0;
            r = labelled(sheet, r, "Statement of account", null, bold);
            r = labelled(sheet, r, "Period", s.from() + " to " + s.to(), bold);
            r = amount(sheet, r, "Opening balance", s.opening(), bold);
            r = amount(sheet, r, "Invoiced", s.invoiced(), bold);
            r = amount(sheet, r, "Paid", s.paid(), bold);
            r = amount(sheet, r, "Closing balance (you owe)", s.closing(), bold);
            r++;
            Row h = sheet.createRow(r++);
            String[] cols = {"Date", "Type", "Reference", "Invoice amount", "Payment", "Balance"};
            for (int c = 0; c < cols.length; c++) {
                h.createCell(c).setCellValue(cols[c]);
                h.getCell(c).setCellStyle(bold);
                sheet.setColumnWidth(c, (c == 2 ? 34 : 16) * 256);
            }
            if (s.lines().isEmpty()) {
                sheet.createRow(r).createCell(0).setCellValue("No invoices or payments in this period.");
            }
            for (StatementLine l : s.lines()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(l.date() == null ? "" : l.date().toString());
                row.createCell(1).setCellValue("INVOICE".equals(l.kind()) ? "Invoice" : "Payment");
                row.createCell(2).setCellValue(PortalExportService.safe(l.reference()));
                if (l.debit().signum() != 0) {
                    row.createCell(3).setCellValue(l.debit().doubleValue());
                }
                if (l.credit().signum() != 0) {
                    row.createCell(4).setCellValue(l.credit().doubleValue());
                }
                row.createCell(5).setCellValue(l.balance().doubleValue());
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessRuleException("Could not build the statement.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<CustomerInvoice> approved(ActingCustomer me) {
        return invoices.findApprovedForCustomer(me.companyId(), me.customerId());
    }

    private InvoiceRow row(CustomerInvoice i) {
        BigDecimal due = due(i);
        BigDecimal paid = nz(i.getAmountPaid());
        BigDecimal balance = i.getStatus() == InvoiceStatus.CANCELLED ? BigDecimal.ZERO : due.subtract(paid).max(BigDecimal.ZERO);
        return new InvoiceRow(i.getId(), i.getInvoiceNumber(), i.getInvoiceDate(), i.getPeriodFrom(), i.getPeriodTo(), i.getShipmentsCount(),
                nz(i.getTotalAmount()), nz(i.getPrepaidAmount()), due, paid, balance, i.getStatus() == null ? null : i.getStatus().name());
    }

    /** What the customer owes on an invoice: its total minus what was already paid at booking. */
    private static BigDecimal due(CustomerInvoice i) {
        return nz(i.getTotalAmount()).subtract(nz(i.getPrepaidAmount())).max(BigDecimal.ZERO);
    }

    private static int labelled(Sheet sheet, int r, String name, String value, CellStyle bold) {
        Row row = sheet.createRow(r);
        row.createCell(0).setCellValue(name);
        row.getCell(0).setCellStyle(bold);
        if (value != null) {
            row.createCell(1).setCellValue(value);
        }
        return r + 1;
    }

    private static int amount(Sheet sheet, int r, String name, BigDecimal value, CellStyle bold) {
        Row row = sheet.createRow(r);
        row.createCell(0).setCellValue(name);
        row.getCell(0).setCellStyle(bold);
        row.createCell(1).setCellValue(value.doubleValue());
        return r + 1;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

}
