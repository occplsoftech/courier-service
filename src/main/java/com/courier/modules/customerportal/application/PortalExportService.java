package com.courier.modules.customerportal.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerportal.rates.application.PortalShipmentService;
import com.courier.modules.customerportal.rates.application.PortalShipmentService.BookingSummary;
import com.courier.modules.finance.application.CustomerWalletService;
import com.courier.modules.finance.domain.TransactionType;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.shared.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The customer's own data as Excel, for their records: the wallet statement for a period and the shipments list with the filters of
 * the Shipments page. Read-only. Every row is included (not only the page on screen), within the limits below.
 */
@Service
@RequiredArgsConstructor
public class PortalExportService {

    static final int MAX_STATEMENT_DAYS = 366;
    static final int MAX_SHIPMENT_ROWS = 5000;
    private static final int MAX_LEDGER_ROWS_SCANNED = 20000;
    private static final int LEDGER_PAGE = 100;
    private static final int SHIPMENT_PAGE = 50;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(INDIA);

    private final CurrentCustomerResolver resolver;
    private final CustomerWalletService wallets;
    private final CustomerPortalLookup customers;
    private final PortalShipmentService shipments;

    // ------------------------------------------------------------------ wallet statement

    /** The signed-in customer's wallet transactions from {@code from} to {@code to} (India dates, inclusive), with opening and closing balance. */
    public byte[] walletStatement(LocalDate from, LocalDate to) {
        ActingCustomer me = resolver.require();
        LocalDate today = LocalDate.now(INDIA);
        LocalDate start = from == null ? today.withDayOfMonth(1) : from;
        LocalDate end = to == null ? today : to;
        if (start.isAfter(end)) {
            throw new BusinessRuleException("The start date must be on or before the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_STATEMENT_DAYS) {
            throw new BusinessRuleException("A statement covers at most " + MAX_STATEMENT_DAYS + " days. Choose a shorter period.");
        }
        Instant fromAt = start.atStartOfDay(INDIA).toInstant();
        Instant toAt = end.plusDays(1).atStartOfDay(INDIA).toInstant();
        Wallet wallet = wallets.provisionForCustomer(me.companyId(), me.customerId());

        // The ledger comes newest first: keep what falls in the period, and the first older entry gives the opening balance.
        List<WalletTransaction> inPeriod = new ArrayList<>();
        BigDecimal openingFromEarlier = null;
        int scanned = 0;
        outer:
        for (int page = 0; scanned < MAX_LEDGER_ROWS_SCANNED; page++) {
            Page<WalletTransaction> p = wallets.ledgerOfWallet(me.companyId(), wallet.getId(), page, LEDGER_PAGE);
            for (WalletTransaction t : p.getContent()) {
                scanned++;
                Instant at = t.getCreatedAt();
                if (at == null || !at.isBefore(toAt)) {
                    continue;
                }
                if (at.isBefore(fromAt)) {
                    openingFromEarlier = t.getBalanceAfter();
                    break outer;
                }
                inPeriod.add(t);
            }
            if (!p.hasNext()) {
                break;
            }
        }
        Collections.reverse(inPeriod); // oldest first, as a statement reads
        BigDecimal opening = !inPeriod.isEmpty() && inPeriod.get(0).getBalanceBefore() != null ? inPeriod.get(0).getBalanceBefore()
                : openingFromEarlier != null ? openingFromEarlier : BigDecimal.ZERO;
        BigDecimal closing = inPeriod.isEmpty() ? opening
                : inPeriod.get(inPeriod.size() - 1).getBalanceAfter() == null ? opening : inPeriod.get(inPeriod.size() - 1).getBalanceAfter();
        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal debits = BigDecimal.ZERO;
        for (WalletTransaction t : inPeriod) {
            BigDecimal amount = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
            if (t.getTransactionType() == TransactionType.CR) {
                credits = credits.add(amount);
            } else {
                debits = debits.add(amount);
            }
        }
        String customerName = customers.findActiveBusinessCustomer(me.companyId(), me.customerId())
                .map(c -> c.getCompanyName()).orElse("");

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Wallet statement");
            CellStyle bold = boldStyle(wb);
            int r = 0;
            r = label(sheet, r, "Wallet statement", "", bold);
            r = label(sheet, r, "Customer", customerName, bold);
            r = label(sheet, r, "Wallet number", wallet.getWalletNumber(), bold);
            r = label(sheet, r, "Period", start + " to " + end, bold);
            r = labelAmount(sheet, r, "Opening balance", opening, bold);
            r = labelAmount(sheet, r, "Total credits", credits, bold);
            r = labelAmount(sheet, r, "Total debits", debits, bold);
            r = labelAmount(sheet, r, "Closing balance", closing, bold);
            r++;
            String[] cols = {"Date", "Transaction No", "Type", "Reason", "Reference", "Amount", "Balance After", "Remarks"};
            header(sheet, r++, cols, bold, new int[]{18, 22, 10, 26, 30, 14, 16, 50});
            if (inPeriod.isEmpty()) {
                sheet.createRow(r).createCell(0).setCellValue("No transactions in this period.");
            }
            for (WalletTransaction t : inPeriod) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(t.getCreatedAt() == null ? "" : WHEN.format(t.getCreatedAt()));
                row.createCell(1).setCellValue(safe(t.getTransactionNo()));
                row.createCell(2).setCellValue(t.getTransactionType() == null ? "" : t.getTransactionType().getLabel());
                row.createCell(3).setCellValue(t.getSubTransactionType() == null ? "" : t.getSubTransactionType().getLabel());
                row.createCell(4).setCellValue(safe(t.getReferenceId()));
                number(row, 5, t.getAmount());
                number(row, 6, t.getBalanceAfter());
                row.createCell(7).setCellValue(safe(t.getRemarks()));
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessRuleException("Could not build the wallet statement.");
        }
    }

    // ------------------------------------------------------------------ shipments

    /** Every shipment matching the Shipments page filters, newest first; refused beyond {@value #MAX_SHIPMENT_ROWS} rows. */
    public byte[] shipments(String status, String delivery, String provider, LocalDate from, LocalDate to) {
        List<BookingSummary> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            Page<BookingSummary> p = shipments.list(status, delivery, provider, from, to, page, SHIPMENT_PAGE);
            if (page == 0 && p.getTotalElements() > MAX_SHIPMENT_ROWS) {
                throw new BusinessRuleException("There are " + p.getTotalElements() + " shipments for these filters; an export holds at most "
                        + MAX_SHIPMENT_ROWS + ". Narrow the dates or the status.");
            }
            rows.addAll(p.getContent());
            if (!p.hasNext() || rows.size() >= MAX_SHIPMENT_ROWS) {
                break;
            }
        }
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Shipments");
            CellStyle bold = boldStyle(wb);
            String[] cols = {"Booked On", "Tracking Number", "Reference", "Courier", "Service", "Status", "Delivery Status",
                    "From Pincode", "To Pincode", "To City", "Receiver", "Packages", "Amount"};
            header(sheet, 0, cols, bold, new int[]{18, 18, 22, 18, 22, 14, 22, 12, 12, 16, 24, 10, 12});
            int r = 1;
            for (BookingSummary b : rows) {
                Row row = sheet.createRow(r++);
                Instant when = b.bookedAt() != null ? b.bookedAt() : b.createdAt();
                row.createCell(0).setCellValue(when == null ? "" : WHEN.format(when));
                row.createCell(1).setCellValue(safe(b.trackingNumber()));
                row.createCell(2).setCellValue(safe(b.bookingReference()));
                row.createCell(3).setCellValue(safe(b.providerName()));
                row.createCell(4).setCellValue(safe(b.serviceName()));
                row.createCell(5).setCellValue(statusLabel(b.status()));
                // Only a booked shipment has a delivery status; a booking cancelled or never placed keeps none (not its last check).
                row.createCell(6).setCellValue(!"BOOKED".equals(b.status()) || b.deliveryStatus() == null ? "" : safe(b.deliveryStatus().text() != null
                        ? b.deliveryStatus().text() : b.deliveryStatus().stage()));
                row.createCell(7).setCellValue(safe(b.pickupPincode()));
                row.createCell(8).setCellValue(safe(b.deliveryPincode()));
                row.createCell(9).setCellValue(safe(b.deliveryCity()));
                row.createCell(10).setCellValue(safe(b.deliveryName()));
                if (b.packageCount() != null) {
                    row.createCell(11).setCellValue(b.packageCount());
                }
                number(row, 12, b.totalAmount());
            }
            if (rows.isEmpty()) {
                sheet.createRow(1).createCell(0).setCellValue("No shipments match these filters.");
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessRuleException("Could not build the shipments export.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case "BOOKED" -> "Booked";
            case "FAILED" -> "Not booked";
            case "OUTCOME_UNKNOWN" -> "Awaiting confirmation";
            case "CANCELLED" -> "Cancelled";
            case "PENDING", "SUBMITTING" -> "Submitting";
            default -> status;
        };
    }

    private static CellStyle boldStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }

    private static void header(Sheet sheet, int rowNo, String[] cols, CellStyle bold, int[] widths) {
        Row h = sheet.createRow(rowNo);
        for (int i = 0; i < cols.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(bold);
            sheet.setColumnWidth(i, widths[i] * 256);
        }
    }

    private static int label(Sheet sheet, int r, String name, String value, CellStyle bold) {
        Row row = sheet.createRow(r);
        Cell c = row.createCell(0);
        c.setCellValue(name);
        c.setCellStyle(bold);
        row.createCell(1).setCellValue(safe(value));
        return r + 1;
    }

    private static int labelAmount(Sheet sheet, int r, String name, BigDecimal value, CellStyle bold) {
        Row row = sheet.createRow(r);
        Cell c = row.createCell(0);
        c.setCellValue(name);
        c.setCellStyle(bold);
        number(row, 1, value);
        return r + 1;
    }

    private static void number(Row row, int col, BigDecimal value) {
        if (value != null) {
            row.createCell(col).setCellValue(value.doubleValue());
        }
    }

    /** Text Excel would read as a formula (=, +, -, @) gets a leading space. */
    static String safe(String s) {
        if (s == null) {
            return "";
        }
        String t = s.length() > 500 ? s.substring(0, 500) : s;
        return !t.isEmpty() && "=+-@".indexOf(t.charAt(0)) >= 0 ? " " + t : t;
    }
}
