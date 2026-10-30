package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customer.application.CustomerPortalAddressBook;
import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.application.PortalBookingService.BookingView;
import com.courier.modules.customerportal.rates.application.RateComparisonService.BulkQuote;
import com.courier.modules.customerportal.rates.application.RateComparisonService.CompareCommand;
import com.courier.modules.customerportal.rates.application.RateComparisonService.OptionView;
import com.courier.modules.customerportal.rates.application.RateComparisonService.PackageInput;
import com.courier.modules.customerportal.rates.application.RateComparisonService.PartyInput;
import com.courier.shared.exception.ApiException;
import com.courier.shared.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Many shipments from one Excel sheet, for the signed-in business customer. {@link #preview} reads the sheet and prices every row
 * (the customer's own business rate when OCCPL Network has one for it, otherwise the cheapest courier the customer may use) and
 * stores each as an ordinary rate quote; nothing is booked. {@link #book} then books the rows the customer confirmed through the
 * normal single-booking path, so wallet, To Pay and credit rules are exactly the same. Rows succeed or fail independently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalBulkBookingService {

    public static final int MAX_ROWS = 100;

    private static final String[] HEADERS = {
            "Delivery Name", "Delivery Phone", "Delivery Address", "Delivery Pincode", "Delivery City", "Delivery State",
            "Weight (kg)", "Packages", "Contents", "Declared Value (Rs)",
            "Payment Mode (PREPAID/COD/TOPAY)"};

    /** Parcel size is not asked: every shipment is priced at 10 x 10 x 10 cm, so the weight (not volume) sets the charge. */
    private static final BigDecimal DEFAULT_DIMENSION_CM = BigDecimal.TEN;

    private final RateComparisonService comparison;
    private final PortalBookingService booking;
    private final CurrentCustomerResolver resolver;
    private final CustomerPortalLookup customers;
    private final CustomerPortalAddressBook addressBook;

    /** Where and by whom the parcels are handed over: the customer's own profile, so the sheet does not repeat it on every row. */
    private record Contact(String name, String phone, String address, String pincode) {
    }

    private Contact contact() {
        ActingCustomer acting = resolver.require();
        var c = customers.findActiveBusinessCustomer(acting.companyId(), acting.customerId())
                .orElseThrow(() -> new BusinessRuleException("Your customer profile could not be found."));
        var addresses = addressBook.list(acting.companyId(), acting.customerId());
        var pickup = addresses.stream().filter(a -> a.defaultPickup()).findFirst()
                .or(() -> addresses.stream().findFirst())
                .orElseThrow(() -> new BusinessRuleException("Add a pickup address to your profile first: bulk booking picks up from it."));
        String line = java.util.stream.Stream.of(pickup.addressLine1(), pickup.addressLine2(), pickup.landmark())
                .filter(t -> t != null && !t.isBlank()).map(String::trim).collect(java.util.stream.Collectors.joining(", "));
        return new Contact(blank(c.getContactPerson()) ? c.displayName() : c.getContactPerson().trim(), c.getMobile(), line, pickup.pincode());
    }

    public record PreviewRow(int row, String deliveryName, String deliveryPincode, BigDecimal weightKg, String paymentMode,
                             boolean ok, String error, UUID quoteId, UUID optionId, String providerCode, String providerName,
                             String serviceName, String basis, BigDecimal total,
                             Map<String, String> fields) {
    }

    public record Preview(List<PreviewRow> rows, int okCount, int errorCount, BigDecimal okTotal) {
    }

    public record BookItem(Integer row, UUID quoteId, UUID optionId) {
    }

    public record BookResult(Integer row, boolean booked, String status, String awb, String reference, String message) {
    }

    public byte[] template() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Shipments");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            Row h = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
                sheet.setColumnWidth(i, 20 * 256);
            }
            String[] sample = {
                    "Ravi Kumar", "9800000002", "5 Park Street, Mumbai", "400001", "Mumbai", "Maharashtra",
                    "2.5", "1", "Garments", "1500", "PREPAID"};
            Row r = sheet.createRow(1);
            for (int i = 0; i < sample.length; i++) {
                r.createCell(i).setCellValue(sample[i]);
            }
            Sheet help = wb.createSheet("Instructions");
            String[] lines = {
                    "Pickup name, phone and address are your own contact person, mobile and default pickup address from your profile; you do not enter them. One row per shipment, starting on row 2 of the Shipments sheet (replace the sample row). Up to " + MAX_ROWS + " rows.",
                    "Pincodes are 6 digits. Phone numbers are 10 digits. Weight is in kg, for the whole shipment.",
                    "Payment mode: PREPAID, COD or TOPAY (left blank means PREPAID).",
                    "Parcel size is not needed: every shipment is priced by its weight.",
                    "Each row is priced at your business rate when you have one for that destination, otherwise at the cheapest courier you are allowed to use.",
                    "After upload you see every row checked, with its price. Nothing is booked until you confirm."};
            for (int i = 0; i < lines.length; i++) {
                help.createRow(i).createCell(0).setCellValue(lines[i]);
            }
            help.setColumnWidth(0, 120 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessRuleException("Could not build the template.");
        }
    }

    public Preview preview(InputStream file) {
        List<Map<String, String>> sheetRows = read(file);
        if (sheetRows.isEmpty()) {
            throw new BusinessRuleException("The sheet has no shipments. Fill one row per shipment under the headings.");
        }
        Contact contact = contact();
        List<PreviewRow> rows = new ArrayList<>();
        for (int i = 0; i < sheetRows.size(); i++) {
            rows.add(previewRow(Integer.parseInt(sheetRows.get(i).get("#")), sheetRows.get(i), contact));
        }
        long ok = rows.stream().filter(PreviewRow::ok).count();
        BigDecimal total = rows.stream().filter(PreviewRow::ok).map(PreviewRow::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Preview(rows, (int) ok, rows.size() - (int) ok, total);
    }

    public List<BookResult> book(List<BookItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessRuleException("Choose at least one shipment to book.");
        }
        if (items.size() > MAX_ROWS) {
            throw new BusinessRuleException("At most " + MAX_ROWS + " shipments at a time.");
        }
        List<BookResult> results = new ArrayList<>();
        for (BookItem item : items) {
            try {
                // The key is derived from the quote, so confirming the same sheet twice cannot book a row twice.
                BookingView v = booking.book(item.quoteId(), item.optionId(), "bulk-" + item.quoteId());
                boolean booked = "BOOKED".equals(v.status());
                results.add(new BookResult(item.row(), booked, v.status(), v.trackingNumber(), v.bookingReference(), booked ? "Booked" : message(v)));
            } catch (ApiException e) {
                results.add(new BookResult(item.row(), false, "FAILED", null, null, e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Bulk booking row {} failed", item.row(), e);
                results.add(new BookResult(item.row(), false, "FAILED", null, null, "Could not book this shipment."));
            }
        }
        return results;
    }

    private static String message(BookingView v) {
        return v.message() != null ? v.message() : "Not booked (" + v.status() + ").";
    }

    // ------------------------------------------------------------------ rows

    /** Re-checks one row after the customer edited it on screen: the same checks and pricing as the upload. */
    public PreviewRow checkRow(int rowNo, Map<String, String> edited) {
        Map<String, String> v = new LinkedHashMap<>();
        for (String h : HEADERS) {
            v.put(h, edited == null || edited.get(h) == null ? "" : edited.get(h).trim());
        }
        return previewRow(rowNo, v, contact());
    }

    private PreviewRow previewRow(int rowNo, Map<String, String> raw, Contact contact) {
        Map<String, String> v = new LinkedHashMap<>();
        for (String h : HEADERS) {
            v.put(h, raw.get(h) == null ? "" : raw.get(h));
        }
        String dName = v.get("Delivery Name");
        String dPin = v.get("Delivery Pincode");
        String mode = blank(v.get("Payment Mode (PREPAID/COD/TOPAY)")) ? "PREPAID" : v.get("Payment Mode (PREPAID/COD/TOPAY)").toUpperCase(Locale.ROOT);
        BigDecimal weight = null;
        try {
            weight = decimal(v.get("Weight (kg)"), "Weight");
                        require(v, "Delivery Name", "Delivery name");
            phone(v.get("Delivery Phone"), "Delivery phone");
            require(v, "Delivery Address", "Delivery address");
            PackageInput pkg = new PackageInput(weight, DEFAULT_DIMENSION_CM, DEFAULT_DIMENSION_CM, DEFAULT_DIMENSION_CM, "CM",
                    blank(v.get("Packages")) ? 1 : integer(v.get("Packages"), "Packages"),
                    blank(v.get("Contents")) ? "General goods" : v.get("Contents"),
                    decimal(v.get("Declared Value (Rs)"), "Declared value"), mode);
            CompareCommand cmd = new CompareCommand(
                    new PartyInput(contact.name(), contact.phone(), contact.address(), contact.pincode(), null, null),
                    new PartyInput(dName, v.get("Delivery Phone"), v.get("Delivery Address"), dPin, v.get("Delivery City"), v.get("Delivery State")),
                    pkg);
            BulkQuote q = comparison.compareForBulk(cmd);
            if (q.optionId() == null) {
                String why = q.view().unavailable().stream().map(u -> u.providerName() + ": " + u.reason()).findFirst()
                        .orElse("No courier is available for this shipment.");
                return failed(rowNo, dName, dPin, weight, mode, why, v);
            }
            OptionView o = q.view().options().stream().filter(x -> x.optionId().equals(q.optionId())).findFirst().orElseThrow();
            return new PreviewRow(rowNo, dName, dPin, weight, mode, true, null, q.view().quoteId(), o.optionId(), o.providerCode(),
                    o.providerName(), o.serviceName(), q.basis(), o.totalAmount(), v);
        } catch (ApiException e) {
            return failed(rowNo, dName, dPin, weight, mode, e.getMessage(), v);
        } catch (RuntimeException e) {
            log.warn("Bulk preview row {} failed", rowNo, e);
            return failed(rowNo, dName, dPin, weight, mode, "This row could not be priced.", v);
        }
    }

    private static PreviewRow failed(int row, String name, String pin, BigDecimal weight, String mode, String error,
                                     Map<String, String> fields) {
        return new PreviewRow(row, name, pin, weight, mode, false, error, null, null, null, null, null, null, null, fields);
    }

    private static void require(Map<String, String> v, String key, String label) {
        if (blank(v.get(key))) {
            throw new BusinessRuleException(label + " is required.");
        }
    }

    private static void phone(String raw, String label) {
        if (raw == null || !raw.replaceAll("[\\s+-]", "").matches("(91)?[6-9]\\d{9}")) {
            throw new BusinessRuleException(label + " must be a 10-digit mobile number.");
        }
    }

    private static BigDecimal decimal(String raw, String label) {
        if (blank(raw)) {
            throw new BusinessRuleException(label + " is required.");
        }
        try {
            return new BigDecimal(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new BusinessRuleException(label + " must be a number.");
        }
    }

    private static int integer(String raw, String label) {
        BigDecimal d = decimal(raw, label);
        if (d.stripTrailingZeros().scale() > 0) {
            throw new BusinessRuleException(label + " must be a whole number.");
        }
        return d.intValue();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------ sheet

    /** The data rows keyed by heading (plus "#" = the row number in the sheet); fully blank rows are skipped. */
    private List<Map<String, String>> read(InputStream file) {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(file)) {
            Sheet sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(0);
            if (head == null) {
                throw new BusinessRuleException("The first row must hold the column headings. Download the template.");
            }
            Map<Integer, String> cols = new HashMap<>();
            for (Cell c : head) {
                String t = fmt.formatCellValue(c).trim();
                for (String known : HEADERS) {
                    if (known.equalsIgnoreCase(t)) {
                        cols.put(c.getColumnIndex(), known);
                    }
                }
            }
            if (!cols.values().containsAll(java.util.Arrays.asList(HEADERS))) {
                throw new BusinessRuleException("The column headings do not match the template. Download the template again and use it as is.");
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                Map<String, String> m = new HashMap<>();
                boolean any = false;
                for (Map.Entry<Integer, String> e : cols.entrySet()) {
                    Cell c = r.getCell(e.getKey());
                    String val = c == null ? "" : fmt.formatCellValue(c).trim();
                    any |= !val.isEmpty();
                    m.put(e.getValue(), val);
                }
                if (!any) continue;
                for (String h : HEADERS) {
                    m.putIfAbsent(h, "");
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new BusinessRuleException("The sheet has more than " + MAX_ROWS + " shipments. Split it into smaller files.");
                }
                m.put("#", String.valueOf(i + 1));
                rows.add(m);
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException) throw (ApiException) e;
            throw new BusinessRuleException("This file could not be read. Upload an Excel (.xlsx) file made from the template.");
        }
    }
}
