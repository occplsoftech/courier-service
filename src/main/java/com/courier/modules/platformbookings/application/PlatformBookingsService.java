package com.courier.modules.platformbookings.application;

import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.shared.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every booking of every company, for the super admin (platform console → All Bookings): OCCPL shipments (counter or portal) and portal
 * bookings made directly with an outside courier, each with who carries it — OCCPL Network, the courier it was forwarded to from a hub, or
 * the courier it was booked with (aggregators show the real courier: "Delhivery via Shipyaari"). A portal booking on OCCPL Network shows
 * once, as its shipment. Read-only; plain SQL across companies (no company filter).
 */
@Service
@RequiredArgsConstructor
public class PlatformBookingsService {

    static final int MAX_PAGE = 100;
    static final int MAX_EXPORT = 20_000;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final NamedParameterJdbcTemplate jdbc;
    private final List<CourierProviderAdapter> adapters;

    /** OCCPL: own network; FORWARDED: handed to a courier at a hub; BOOKED_WITH: the customer booked the courier directly. */
    public enum CarriedBy { OCCPL, FORWARDED, BOOKED_WITH }

    public record Filter(UUID companyId, CarriedBy carriedBy, String providerCode, String courier, LocalDate from, LocalDate to,
                         String search) {
    }

    public record BookingRow(UUID id, String kind, UUID companyId, String companyName, Instant bookedAt, String number, String awb,
                             String channel, String customer, String fromPincode, String fromCity, String toPincode, String toCity,
                             CarriedBy carriedBy, String carrier, String providerCode, String service, String partnerAwb, String status,
                             BigDecimal amount, BigDecimal cost) {
    }

    /** A carrier total for the filter; {@code carriedBy}, {@code providerCode} and {@code courier} narrow the list to it. */
    public record CarrierTotal(String carrier, CarriedBy carriedBy, String providerCode, String courier, long count) {
    }

    public record Page(List<BookingRow> content, int page, int size, long totalElements, int totalPages, boolean hasNext,
                       List<CarrierTotal> totals, List<CompanyRef> companies) {
    }

    public record CompanyRef(UUID id, String name) {
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Page list(Filter f, int page, int size) {
        int s = Math.min(MAX_PAGE, Math.max(1, size));
        int p = Math.max(0, page);
        MapSqlParameterSource params = params(f);
        String where = where(f);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + UNION + ") x" + where, params, Long.class);
        params.addValue("limit", s).addValue("offset", (long) p * s);
        List<BookingRow> rows = jdbc.query(SELECT + where + " ORDER BY x.booked_at DESC, x.id LIMIT :limit OFFSET :offset", params, this::row);
        int pages = (int) Math.ceil(total / (double) s);
        return new Page(rows, p, s, total, pages, p + 1 < pages, totals(f), companies());
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public byte[] export(Filter f) {
        MapSqlParameterSource params = params(f).addValue("limit", MAX_EXPORT).addValue("offset", 0);
        List<BookingRow> rows = jdbc.query(SELECT + where(f) + " ORDER BY x.booked_at DESC, x.id LIMIT :limit OFFSET :offset", params, this::row);
        String[] head = {"Booked", "Company", "Number", "AWB", "Channel", "Customer", "From", "To", "Carried by", "Carrier", "Service",
                "Courier AWB", "Status", "Amount", "Our courier cost"};
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("All bookings");
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            Row h = sheet.createRow(0);
            for (int i = 0; i < head.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(head[i]);
                c.setCellStyle(bold);
            }
            int r = 1;
            for (BookingRow b : rows) {
                Row x = sheet.createRow(r++);
                int i = 0;
                x.createCell(i++).setCellValue(b.bookedAt() == null ? "" : b.bookedAt().atZone(IST).toLocalDateTime().toString().replace('T', ' '));
                x.createCell(i++).setCellValue(nz(b.companyName()));
                x.createCell(i++).setCellValue(nz(b.number()));
                x.createCell(i++).setCellValue(nz(b.awb()));
                x.createCell(i++).setCellValue(nz(b.channel()));
                x.createCell(i++).setCellValue(nz(b.customer()));
                x.createCell(i++).setCellValue(place(b.fromCity(), b.fromPincode()));
                x.createCell(i++).setCellValue(place(b.toCity(), b.toPincode()));
                x.createCell(i++).setCellValue(carriedWords(b.carriedBy()));
                x.createCell(i++).setCellValue(nz(b.carrier()));
                x.createCell(i++).setCellValue(nz(b.service()));
                x.createCell(i++).setCellValue(nz(b.partnerAwb()));
                x.createCell(i++).setCellValue(nz(b.status()));
                Cell amount = x.createCell(i++);
                if (b.amount() != null) {
                    amount.setCellValue(b.amount().doubleValue());
                }
                Cell cost = x.createCell(i);
                if (b.cost() != null) {
                    cost.setCellValue(b.cost().doubleValue());
                }
            }
            for (int i = 0; i < head.length; i++) {
                sheet.setColumnWidth(i, 18 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("The list could not be written", e);
        }
    }

    // ------------------------------------------------------------------ SQL

    /** Customer name as Customer.displayName() does: the business name, else the person's name. */
    private static final String CUSTOMER = "CASE WHEN cu.id IS NULL THEN NULL WHEN cu.customer_type = 'BUSINESS' AND TRIM(COALESCE(cu.company_name, '')) <> '' "
            + "THEN TRIM(cu.company_name) ELSE TRIM(CONCAT(COALESCE(cu.first_name, ''), ' ', COALESCE(cu.last_name, ''))) END";

    static final String UNION = ""
            // OCCPL shipments: counter bookings and portal bookings on OCCPL Network (forwarded ones show the courier)
            + "SELECT 'SHIPMENT' AS kind, s.id AS id, s.company_id AS company_id, s.created_at AS booked_at, s.shipment_number AS number, "
            + "s.tracking_number AS awb, CASE WHEN pb.id IS NULL THEN 'COUNTER' ELSE COALESCE(po.source, 'PORTAL') END AS channel, "
            + "COALESCE(" + CUSTOMER + ", s.sender_name) AS customer, s.pickup_pincode AS from_pin, s.from_city AS from_city, "
            + "s.delivery_pincode AS to_pin, s.to_city AS to_city, "
            + "CASE WHEN s.forward_state = 'BOOKED' THEN 'FORWARDED' ELSE 'OCCPL' END AS carried, "
            + "CASE WHEN s.forward_state = 'BOOKED' THEN s.forward_provider END AS provider, "
            + "CASE WHEN s.forward_state = 'BOOKED' THEN s.forward_service END AS service, "
            + "CASE WHEN s.forward_state = 'BOOKED' THEN s.forward_awb END AS partner_awb, s.status AS status, "
            + "COALESCE(pb.total_amount, sc.net_amount) AS amount, CASE WHEN s.forward_state = 'BOOKED' THEN s.forward_cost END AS cost "
            + "FROM shipments s "
            + "LEFT JOIN portal_bookings pb ON pb.shipment_id = s.id AND pb.deleted = FALSE "
            + "LEFT JOIN portal_orders po ON po.booking_id = pb.id AND po.deleted = FALSE "
            + "LEFT JOIN customers cu ON cu.id = COALESCE(pb.customer_id, s.business_customer_id) "
            + "LEFT JOIN shipment_charges sc ON sc.shipment_id = s.id AND sc.deleted = FALSE "
            + "WHERE s.deleted = FALSE "
            + "UNION ALL "
            // portal bookings with no OCCPL shipment: booked directly with a courier (or an OCCPL pickup not yet booked by the branch)
            + "SELECT 'PORTAL_BOOKING', pb.id, pb.company_id, pb.created_at, COALESCE(pb.provider_order_ref, pb.awb_number), pb.awb_number, "
            + "COALESCE(po.source, 'PORTAL'), " + CUSTOMER + ", rq.pickup_pincode, NULL, rq.delivery_pincode, NULL, "
            + "CASE WHEN pb.provider_code = 'OCCPL_NETWORK' THEN 'OCCPL' ELSE 'BOOKED_WITH' END, "
            + "CASE WHEN pb.provider_code = 'OCCPL_NETWORK' THEN NULL ELSE pb.provider_code END, qo.service_name, "
            + "CASE WHEN pb.provider_code = 'OCCPL_NETWORK' THEN NULL ELSE pb.awb_number END, pb.status, pb.total_amount, NULL "
            + "FROM portal_bookings pb "
            + "LEFT JOIN portal_orders po ON po.booking_id = pb.id AND po.deleted = FALSE "
            + "LEFT JOIN customers cu ON cu.id = pb.customer_id "
            + "LEFT JOIN rate_quotes rq ON rq.id = pb.quote_id "
            + "LEFT JOIN rate_quote_options qo ON qo.id = pb.quote_option_id "
            + "WHERE pb.deleted = FALSE AND pb.shipment_id IS NULL AND pb.status IN ('BOOKED', 'CANCELLED', 'OUTCOME_UNKNOWN')";

    private static final String SELECT = "SELECT x.*, COALESCE(NULLIF(TRIM(c.display_name), ''), c.company_name) AS company_name FROM ("
            + UNION + ") x LEFT JOIN companies c ON c.company_id = x.company_id";

    private static String where(Filter f) {
        List<String> w = new ArrayList<>();
        if (f.companyId() != null) {
            w.add("x.company_id = :companyId");
        }
        if (f.carriedBy() != null) {
            w.add("x.carried = :carried");
        }
        if (f.providerCode() != null) {
            w.add("x.provider = :provider");
        }
        if (f.courier() != null) {
            w.add("LOWER(x.service) REGEXP :courier");
        }
        if (f.from() != null) {
            w.add("x.booked_at >= :fromAt");
        }
        if (f.to() != null) {
            w.add("x.booked_at < :toAt");
        }
        if (f.search() != null) {
            w.add("(x.number LIKE :q OR x.awb LIKE :q OR x.partner_awb LIKE :q OR x.customer LIKE :q)");
        }
        return w.isEmpty() ? "" : " WHERE " + String.join(" AND ", w);
    }

    private static MapSqlParameterSource params(Filter f) {
        if (f.from() != null && f.to() != null && f.to().isBefore(f.from())) {
            throw new BusinessRuleException("The end date is before the start date.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (f.companyId() != null) {
            p.addValue("companyId", bytes(f.companyId()));
        }
        if (f.carriedBy() != null) {
            p.addValue("carried", f.carriedBy().name());
        }
        if (f.providerCode() != null) {
            p.addValue("provider", f.providerCode());
        }
        if (f.courier() != null) {
            p.addValue("courier", CarrierLabel.patternFor(f.courier()));
        }
        if (f.from() != null) {
            p.addValue("fromAt", Timestamp.from(f.from().atStartOfDay(IST).toInstant()));
        }
        if (f.to() != null) {
            p.addValue("toAt", Timestamp.from(f.to().plusDays(1).atStartOfDay(IST).toInstant()));
        }
        if (f.search() != null) {
            p.addValue("q", "%" + f.search().trim().replace("%", "").replace("_", "") + "%");
        }
        return p;
    }

    /** Counts per carrier for the filter (aggregators split by the real courier). */
    private List<CarrierTotal> totals(Filter f) {
        List<Map<String, Object>> groups = jdbc.queryForList("SELECT x.carried, x.provider, x.service, COUNT(*) AS n FROM (" + UNION + ") x"
                + where(f) + " GROUP BY x.carried, x.provider, x.service", params(f));
        Map<String, CarrierTotal> by = new LinkedHashMap<>();
        for (Map<String, Object> g : groups) {
            CarriedBy carried = CarriedBy.valueOf(String.valueOf(g.get("carried")));
            String provider = (String) g.get("provider");
            String service = (String) g.get("service");
            long n = ((Number) g.get("n")).longValue();
            String carrier = CarrierLabel.of(provider, providerName(provider), service);
            String courier = provider != null && CarrierLabel.AGGREGATORS.containsKey(provider) ? CarrierLabel.courierIn(service) : null;
            String key = carried + "|" + carrier;
            CarrierTotal old = by.get(key);
            by.put(key, new CarrierTotal(carrier, carried, provider, courier, n + (old == null ? 0 : old.count())));
        }
        return by.values().stream().sorted((a, b) -> Long.compare(b.count(), a.count())).collect(Collectors.toList());
    }

    private List<CompanyRef> companies() {
        return jdbc.query("SELECT company_id, COALESCE(NULLIF(TRIM(display_name), ''), company_name) AS name FROM companies "
                + "WHERE deleted = FALSE ORDER BY name", new MapSqlParameterSource(), (rs, i) -> new CompanyRef(uuid(rs, "company_id"), rs.getString("name")));
    }

    private BookingRow row(ResultSet rs, int i) throws SQLException {
        String provider = rs.getString("provider");
        String service = rs.getString("service");
        Timestamp at = rs.getTimestamp("booked_at");
        return new BookingRow(uuid(rs, "id"), rs.getString("kind"), uuid(rs, "company_id"), rs.getString("company_name"),
                at == null ? null : at.toInstant(), rs.getString("number"), rs.getString("awb"), rs.getString("channel"), rs.getString("customer"),
                rs.getString("from_pin"), rs.getString("from_city"), rs.getString("to_pin"), rs.getString("to_city"),
                CarriedBy.valueOf(rs.getString("carried")), CarrierLabel.of(provider, providerName(provider), service), provider, service,
                rs.getString("partner_awb"), rs.getString("status"), rs.getBigDecimal("amount"), rs.getBigDecimal("cost"));
    }

    private String providerName(String code) {
        if (code == null) {
            return null;
        }
        return adapters.stream().filter(a -> a.code().equals(code)).map(CourierProviderAdapter::displayName).findFirst().orElse(code);
    }

    private static UUID uuid(ResultSet rs, String col) throws SQLException {
        byte[] b = rs.getBytes(col);
        if (b == null || b.length != 16) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(b);
        return new UUID(buf.getLong(), buf.getLong());
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String place(String city, String pin) {
        return (nz(city) + " " + nz(pin)).trim();
    }

    static String carriedWords(CarriedBy c) {
        return switch (c) {
            case OCCPL -> "OCCPL Network";
            case FORWARDED -> "Forwarded to courier";
            case BOOKED_WITH -> "Booked with courier";
        };
    }
}
