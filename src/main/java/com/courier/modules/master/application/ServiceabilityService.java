package com.courier.modules.master.application;

import com.courier.modules.master.domain.Area;
import com.courier.modules.master.domain.AreaRepository;
import com.courier.modules.master.domain.City;
import com.courier.modules.master.domain.CityRepository;
import com.courier.modules.master.domain.GlobalMasters;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.master.domain.PincodeArea;
import com.courier.modules.master.domain.PincodeAreaRepository;
import com.courier.modules.master.domain.PincodeRepository;
import com.courier.modules.master.domain.UnserviceableRequest;
import com.courier.modules.master.domain.UnserviceableRequest.Reason;
import com.courier.modules.master.domain.UnserviceableRequest.Source;
import com.courier.modules.master.domain.UnserviceableRequestRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Can OCCPL deliver to this pincode and area (the pincode switched on, and the area of that pincode switched on), and how often
 * bookings asked for one it cannot: each "service unavailable" answer is counted per company, pincode and area, from staff booking and
 * from the customer portal. The report lists them most-asked first; a company admin can switch the area (and its pincode) on from it.
 */
@Slf4j
@Service
public class ServiceabilityService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String ADMIN = "hasAnyRole('" + Roles.SUPER_ADMIN + "', '" + Roles.COMPANY_ADMIN + "')";

    private final PincodeRepository pincodes;
    private final PincodeAreaRepository pincodeAreas;
    private final AreaRepository areas;
    private final CityRepository cities;
    private final UnserviceableRequestRepository requests;
    private final AuditService auditService;
    private final TransactionTemplate newTx;

    public ServiceabilityService(PincodeRepository pincodes, PincodeAreaRepository pincodeAreas, AreaRepository areas, CityRepository cities,
                                 UnserviceableRequestRepository requests, AuditService auditService, PlatformTransactionManager txManager) {
        this.pincodes = pincodes;
        this.pincodeAreas = pincodeAreas;
        this.areas = areas;
        this.cities = cities;
        this.requests = requests;
        this.auditService = auditService;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** {@code reason} null when serviceable; {@code message} is what the booking screen shows. */
    public record Check(String pincode, UUID areaId, String areaName, String cityName, boolean serviceable, Reason reason, String message) {
    }

    public record ReportRow(UUID id, String pincode, UUID areaId, String areaName, String cityName, Reason reason, int staffCount,
                            int portalCount, int total, Instant firstRequestedAt, Instant lastRequestedAt, UUID lastBranchId,
                            Instant turnedOnAt, boolean serviceableNow, boolean canTurnOn) {
    }

    // ------------------------------------------------------------------ the check

    /** Whether a destination can be booked; an unserviceable one is counted (never fails the caller). */
    public Check check(String pincodeCode, UUID areaId, UUID branchId, Source source) {
        Check c = evaluate(pincodeCode, areaId);
        if (!c.serviceable()) {
            record(c, branchId, source);
        }
        return c;
    }

    Check evaluate(String pincodeCode, UUID areaId) {
        String code = pincodeCode == null ? "" : pincodeCode.trim();
        if (!code.matches("^[1-9][0-9]{5}$")) {
            throw new BusinessRuleException("Enter a 6-digit pincode.");
        }
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> {
            UUID platform = GlobalMasters.PLATFORM_COMPANY_ID;
            Pincode p = pincodes.findByCodeWithinCompany(code, platform).orElse(null);
            String[] names = names(areaId);
            String place = names[0] == null ? code : names[0] + (names[1] == null ? "" : ", " + names[1]) + " (" + code + ")";
            if (p == null) {
                return new Check(code, areaId, names[0], names[1], false, Reason.NOT_ON_FILE,
                        "Service unavailable: pincode " + code + " is not on OCCPL's network.");
            }
            if (!p.isServiceable()) {
                return new Check(code, areaId, names[0], names[1], false, Reason.PINCODE_OFF, "Service unavailable for " + place + ".");
            }
            if (areaId == null) {
                return new Check(code, null, null, null, true, null, null);
            }
            PincodeArea link = pincodeAreas.findByCompanyIdAndPincodeIdAndAreaId(platform, p.getId(), areaId).orElse(null);
            if (link == null) {
                return new Check(code, areaId, names[0], names[1], false, Reason.NOT_ON_FILE,
                        "Service unavailable: this area is not listed under pincode " + code + ".");
            }
            if (!link.isServiceable()) {
                return new Check(code, areaId, names[0], names[1], false, Reason.AREA_OFF, "Service unavailable for " + place + ".");
            }
            return new Check(code, areaId, names[0], names[1], true, null, null);
        });
    }

    /** {areaName, cityName} of an area of the global masters, or nulls. */
    private String[] names(UUID areaId) {
        if (areaId == null) {
            return new String[] {null, null};
        }
        Area a = areas.findByIdWithinCompany(areaId, GlobalMasters.PLATFORM_COMPANY_ID).orElse(null);
        if (a == null) {
            return new String[] {null, null};
        }
        City c = a.getCityId() == null ? null : cities.findByIdWithinCompany(a.getCityId(), GlobalMasters.PLATFORM_COMPANY_ID).orElse(null);
        return new String[] {a.getName(), c == null ? null : c.getName()};
    }

    /** Counts one unserviceable request for the caller's company, in its own transaction; a failure is only logged. */
    void record(Check c, UUID branchId, Source source) {
        UUID companyId = CompanyContext.getCompanyId().orElse(null);
        if (companyId == null || GlobalMasters.PLATFORM_COMPANY_ID.equals(companyId)) {
            return;
        }
        UUID userId = SecurityUtils.getCurrentUser().map(AuthenticatedUser::userId).orElse(null);
        String key = c.areaId() == null ? "" : c.areaId().toString();
        Instant now = Instant.now();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                newTx.executeWithoutResult(s -> CompanyContext.runAs(companyId, () -> {
                    UnserviceableRequest r = requests.findKey(companyId, c.pincode(), key).orElseGet(() -> {
                        UnserviceableRequest n = new UnserviceableRequest();
                        n.setPincodeCode(c.pincode());
                        n.setAreaKey(key);
                        n.setAreaId(c.areaId());
                        n.setFirstRequestedAt(now);
                        return n;
                    });
                    if (r.getAreaName() == null) {
                        r.setAreaName(trim(c.areaName()));
                        r.setCityName(trim(c.cityName()));
                    }
                    r.setReason(c.reason());
                    if (source == Source.PORTAL) {
                        r.setPortalCount(r.getPortalCount() + 1);
                    } else {
                        r.setStaffCount(r.getStaffCount() + 1);
                    }
                    r.setLastRequestedAt(now);
                    if (branchId != null) {
                        r.setLastBranchId(branchId);
                    }
                    r.setLastUserId(userId);
                    // asked again after being switched on: it is unserviceable once more
                    r.setTurnedOnAt(null);
                    r.setTurnedOnBy(null);
                    requests.saveAndFlush(r);
                }));
                return;
            } catch (DataIntegrityViolationException raced) {
                // another booking created the row at the same moment: count onto it
            } catch (RuntimeException e) {
                log.warn("Could not count an unserviceable request for {} / {}", c.pincode(), key, e);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ the report

    @PreAuthorize("isAuthenticated()")
    public Page<ReportRow> report(Boolean open, String search, LocalDate since, int page, int size) {
        UUID companyId = CompanyContext.requireCompanyId();
        String q = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(Locale.ROOT).replace("%", "") + "%";
        Instant from = since == null ? null : since.atStartOfDay(IST).toInstant();
        boolean admin = mayTurnOn();
        return newTxRead(() -> requests.search(companyId, open, q, from, PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))))
                .map(r -> row(r, admin)));
    }

    @PreAuthorize(ADMIN)
    public ReportRow turnOn(UUID id) {
        UUID companyId = CompanyContext.requireCompanyId();
        UnserviceableRequest r = requests.findOwned(id, companyId).orElseThrow(() -> new ResourceNotFoundException("Unserviceable request", id));
        UUID userId = SecurityUtils.requireCurrentUser().userId();
        CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> newTx.executeWithoutResult(s -> {
            UUID platform = GlobalMasters.PLATFORM_COMPANY_ID;
            Pincode p = pincodes.findByCodeWithinCompany(r.getPincodeCode(), platform).orElseThrow(() -> new BusinessRuleException(
                    "Pincode " + r.getPincodeCode() + " is not in Masters yet. Add it under Masters → Pincode first."));
            if (!p.isServiceable()) {
                p.setServiceable(true);
                p.setPrepaidAvailable(true);
                p.applyInvariants();
                pincodes.saveAndFlush(p);
            }
            if (r.getAreaId() != null) {
                PincodeArea link = pincodeAreas.findByCompanyIdAndPincodeIdAndAreaId(platform, p.getId(), r.getAreaId())
                        .orElseThrow(() -> new BusinessRuleException("This area is not listed under pincode " + r.getPincodeCode()
                                + ". Add it under Masters → Pincode first."));
                if (!link.isServiceable()) {
                    link.setServiceable(true);
                    pincodeAreas.saveAndFlush(link);
                }
            }
        }));
        UnserviceableRequest saved = newTx.execute(s -> CompanyContext.runAs(companyId, () -> {
            UnserviceableRequest x = requests.findOwned(id, companyId).orElseThrow();
            x.setTurnedOnAt(Instant.now());
            x.setTurnedOnBy(userId);
            return requests.saveAndFlush(x);
        }));
        auditService.record(AuditAction.AREA_SERVICE_TURNED_ON, "UnserviceableRequest", id,
                Map.of("pincode", r.getPincodeCode(), "area", r.getAreaName() == null ? "" : r.getAreaName()));
        return row(saved, true);
    }

    @PreAuthorize("isAuthenticated()")
    public byte[] export(Boolean open, String search, LocalDate since) {
        Page<ReportRow> rows = report(open, search, since, 0, 200);
        java.util.List<ReportRow> all = new java.util.ArrayList<>(rows.getContent());
        for (int p = 1; p < rows.getTotalPages() && p < 50; p++) {
            all.addAll(report(open, search, since, p, 200).getContent());
        }
        String[] head = {"Pincode", "Area", "City", "Times asked", "Staff booking", "Customer portal", "First asked", "Last asked", "Why",
                "Status"};
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Unserviceable requests");
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            Row h = sheet.createRow(0);
            for (int i = 0; i < head.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(head[i]);
                c.setCellStyle(bold);
                sheet.setColumnWidth(i, 18 * 256);
            }
            int n = 1;
            for (ReportRow r : all) {
                Row x = sheet.createRow(n++);
                x.createCell(0).setCellValue(r.pincode());
                x.createCell(1).setCellValue(r.areaName() == null ? "" : r.areaName());
                x.createCell(2).setCellValue(r.cityName() == null ? "" : r.cityName());
                x.createCell(3).setCellValue(r.total());
                x.createCell(4).setCellValue(r.staffCount());
                x.createCell(5).setCellValue(r.portalCount());
                x.createCell(6).setCellValue(day(r.firstRequestedAt()));
                x.createCell(7).setCellValue(day(r.lastRequestedAt()));
                x.createCell(8).setCellValue(reasonWords(r.reason()));
                x.createCell(9).setCellValue(r.serviceableNow() ? "Serviceable now" : "Still unavailable");
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("The report could not be written", e);
        }
    }

    private <T> T newTxRead(java.util.function.Supplier<T> s) {
        return s.get();
    }

    private ReportRow row(UnserviceableRequest r, boolean admin) {
        boolean now;
        try {
            now = evaluate(r.getPincodeCode(), r.getAreaId()).serviceable();
        } catch (RuntimeException e) {
            now = false;
        }
        return new ReportRow(r.getId(), r.getPincodeCode(), r.getAreaId(), r.getAreaName(), r.getCityName(), r.getReason(), r.getStaffCount(),
                r.getPortalCount(), r.total(), r.getFirstRequestedAt(), r.getLastRequestedAt(), r.getLastBranchId(), r.getTurnedOnAt(), now,
                admin && !now && r.getReason() != Reason.NOT_ON_FILE);
    }

    private static boolean mayTurnOn() {
        return SecurityUtils.getCurrentUser().map(u -> u.isSuperAdmin() || u.hasRole(Roles.COMPANY_ADMIN)).orElse(false);
    }

    static String reasonWords(Reason r) {
        return r == null ? "" : switch (r) {
            case PINCODE_OFF -> "Pincode switched off";
            case AREA_OFF -> "Area switched off";
            case NOT_ON_FILE -> "Not in Masters";
        };
    }

    private static String day(Instant i) {
        return i == null ? "" : i.atZone(IST).toLocalDate().toString();
    }

    private static String trim(String s) {
        return s == null ? null : (s.length() > 150 ? s.substring(0, 150) : s);
    }
}
