package com.courier.modules.charge.application;

import com.courier.modules.charge.domain.BookingChargeCalc;
import com.courier.modules.charge.domain.BookingChargeCode;
import com.courier.modules.charge.domain.BookingChargeRate;
import com.courier.modules.charge.domain.BookingChargeRateRepository;
import com.courier.modules.charge.domain.ShipmentBookingCharge;
import com.courier.modules.charge.domain.ShipmentBookingChargeRepository;
import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingChargeServiceImpl implements BookingChargeService {

    private static final String ENTITY = "BookingCharge";
    private static final String ADMIN = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String READ = "isAuthenticated()";
    /** A typed amount above this is a typo, not a charge. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000");

    private final BookingChargeRateRepository rates;
    private final ShipmentBookingChargeRepository lines;
    private final CustomerPortalLookup customers;
    private final AuditService auditService;

    /** A company with no Charges Master rows yet (created after V192): everything off, COD as before (2%, min ₹100). */
    static Rate defaultRate(BookingChargeCode code) {
        return code == BookingChargeCode.COD
                ? new Rate(code, code.label(), BookingChargeCalc.PERCENT_OF_COD, new BigDecimal("2"), new BigDecimal("100"), true)
                : new Rate(code, code.label(), BookingChargeCalc.FIXED, BigDecimal.ZERO, null, false);
    }

    private static Rate toRate(BookingChargeRate r) {
        return new Rate(r.getCode(), r.getCode().label(), r.getCalcMethod(), r.getValue(), r.getMinAmount(), r.isActive());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<Rate> companyRates() {
        Map<BookingChargeCode, Rate> found = companyMap(requireCompany());
        return Arrays.stream(BookingChargeCode.values()).map(found::get).toList();
    }

    private Map<BookingChargeCode, Rate> companyMap(UUID companyId) {
        Map<BookingChargeCode, Rate> map = new EnumMap<>(BookingChargeCode.class);
        for (BookingChargeCode c : BookingChargeCode.values()) {
            map.put(c, defaultRate(c));
        }
        rates.findCompanyRates(companyId).forEach(r -> map.put(r.getCode(), toRate(r)));
        return map;
    }

    @Override
    @Transactional
    @PreAuthorize(ADMIN)
    public Rate saveCompanyRate(BookingChargeCode code, RateInput input) {
        UUID companyId = requireCompany();
        BookingChargeRate row = rates.findCompanyRates(companyId).stream().filter(r -> r.getCode() == code).findFirst()
                .orElseGet(() -> BookingChargeRate.of(null, code));
        apply(row, input);
        BookingChargeRate saved = rates.save(row);
        auditService.record(AuditAction.BOOKING_CHARGE_SAVED, ENTITY, saved.getId(), details(code, null, saved));
        return toRate(saved);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<CustomerRate> customerRates(UUID customerId) {
        UUID companyId = requireCompany();
        requireCustomer(companyId, customerId);
        Map<BookingChargeCode, Rate> company = companyMap(companyId);
        Map<BookingChargeCode, Rate> overrides = new EnumMap<>(BookingChargeCode.class);
        rates.findCustomerRates(companyId, customerId).forEach(r -> overrides.put(r.getCode(), toRate(r)));
        return Arrays.stream(BookingChargeCode.values())
                .map(c -> new CustomerRate(c, c.label(), company.get(c), overrides.get(c))).toList();
    }

    @Override
    @Transactional
    @PreAuthorize(ADMIN)
    public Rate saveCustomerRate(UUID customerId, BookingChargeCode code, RateInput input) {
        UUID companyId = requireCompany();
        requireCustomer(companyId, customerId);
        BookingChargeRate row = rates.findCustomerRates(companyId, customerId).stream().filter(r -> r.getCode() == code)
                .findFirst().orElseGet(() -> BookingChargeRate.of(customerId, code));
        apply(row, input);
        BookingChargeRate saved = rates.save(row);
        auditService.record(AuditAction.BOOKING_CHARGE_OVERRIDE_SAVED, ENTITY, saved.getId(), details(code, customerId, saved));
        return toRate(saved);
    }

    @Override
    @Transactional
    @PreAuthorize(ADMIN)
    public void resetCustomerRate(UUID customerId, BookingChargeCode code) {
        UUID companyId = requireCompany();
        rates.findCustomerRates(companyId, customerId).stream().filter(r -> r.getCode() == code).forEach(r -> {
            r.softDelete(SecurityUtils.getCurrentUserId().orElse(null));
            rates.save(r);
            auditService.record(AuditAction.BOOKING_CHARGE_OVERRIDE_RESET, ENTITY, r.getId(),
                    Map.of("code", code.name(), "customerId", customerId.toString()));
        });
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<QuoteLine> quote(UUID customerId, BigDecimal freight, BigDecimal chargeableKg, int pieces, BigDecimal codAmount) {
        UUID companyId = requireCompany();
        Map<BookingChargeCode, Rate> company = companyMap(companyId);
        Map<BookingChargeCode, Rate> overrides = overrides(companyId, customerId);
        List<QuoteLine> out = new ArrayList<>();
        for (BookingChargeCode c : BookingChargeCode.values()) {
            Rate r = overrides.getOrDefault(c, company.get(c));
            if (!r.active()) {
                continue;
            }
            out.add(new QuoteLine(c, c.label(), r.calc(), r.value(), r.minAmount(),
                    BookingChargeRate.amount(r.calc(), r.value(), r.minAmount(), freight, chargeableKg, pieces, codAmount),
                    overrides.containsKey(c)));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Resolved resolve(UUID customerId, List<Picked> picked, BigDecimal freight, BigDecimal chargeableKg, int pieces,
                            BigDecimal codAmount) {
        UUID companyId = requireCompany();
        Map<BookingChargeCode, Rate> company = companyMap(companyId);
        Map<BookingChargeCode, Rate> overrides = overrides(companyId, customerId);
        Rate cod = overrides.getOrDefault(BookingChargeCode.COD, company.get(BookingChargeCode.COD));
        if (picked == null) {
            BigDecimal codCharge = cod.active()
                    ? BookingChargeRate.amount(cod.calc(), cod.value(), cod.minAmount(), freight, chargeableKg, pieces, codAmount)
                    : BigDecimal.ZERO;
            return new Resolved(codCharge, BigDecimal.ZERO, List.of());
        }
        Set<BookingChargeCode> seen = new HashSet<>();
        Map<BookingChargeCode, Line> out = new LinkedHashMap<>();
        BigDecimal codCharge = BigDecimal.ZERO;
        BigDecimal others = BigDecimal.ZERO;
        for (Picked p : picked) {
            if (p == null || p.code() == null) {
                continue;
            }
            if (!seen.add(p.code())) {
                throw new BusinessRuleException("%s is added twice.".formatted(p.code().label()));
            }
            Rate r = overrides.getOrDefault(p.code(), company.get(p.code()));
            if (!r.active()) {
                throw new BusinessRuleException("%s is not active in the Charges Master.".formatted(p.code().label()));
            }
            BigDecimal calculated = BookingChargeRate.amount(r.calc(), r.value(), r.minAmount(), freight, chargeableKg, pieces,
                    codAmount);
            if (p.code() == BookingChargeCode.COD) {
                if (codAmount == null || codAmount.signum() <= 0) {
                    throw new BusinessRuleException("The COD charge needs a Cash on Delivery booking with its COD amount.");
                }
                codCharge = calculated;
                out.put(p.code(), new Line(p.code(), p.code().label(), calculated));
                continue;
            }
            BigDecimal amount = p.amount() == null ? calculated : p.amount().setScale(2, java.math.RoundingMode.HALF_UP);
            if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
                throw new BusinessRuleException("%s must be between 0 and %s.".formatted(p.code().label(), MAX_AMOUNT.toPlainString()));
            }
            if (amount.signum() == 0) {
                continue;
            }
            others = others.add(amount);
            out.put(p.code(), new Line(p.code(), p.code().label(), amount));
        }
        return new Resolved(codCharge, others, List.copyOf(out.values()));
    }

    @Override
    @Transactional
    public void record(UUID shipmentId, List<Line> picked) {
        if (picked == null || picked.isEmpty()) {
            return;
        }
        picked.forEach(l -> lines.save(ShipmentBookingCharge.of(shipmentId, l.code(), l.amount())));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<Line> linesFor(UUID shipmentId) {
        return lines.findForShipment(requireCompany(), shipmentId).stream()
                .map(l -> new Line(l.getCode(), l.getName(), l.getAmount())).toList();
    }

    private Map<BookingChargeCode, Rate> overrides(UUID companyId, UUID customerId) {
        Map<BookingChargeCode, Rate> map = new EnumMap<>(BookingChargeCode.class);
        if (customerId != null) {
            rates.findCustomerRates(companyId, customerId).forEach(r -> map.put(r.getCode(), toRate(r)));
        }
        return map;
    }

    private static void apply(BookingChargeRate row, RateInput input) {
        if (input == null) {
            throw new BusinessRuleException("Nothing to save.");
        }
        BookingChargeCalc calc = row.getCode() == BookingChargeCode.COD && input.calc() == null
                ? BookingChargeCalc.PERCENT_OF_COD : input.calc();
        row.set(calc, input.value(), input.minAmount(), input.active() == null || input.active());
    }

    private static Map<String, Object> details(BookingChargeCode code, UUID customerId, BookingChargeRate r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", code.name());
        if (customerId != null) {
            d.put("customerId", customerId.toString());
        }
        d.put("calc", r.getCalcMethod().name());
        d.put("value", r.getValue().toPlainString());
        d.put("minAmount", r.getMinAmount() == null ? "" : r.getMinAmount().toPlainString());
        d.put("active", r.isActive());
        return d;
    }

    private void requireCustomer(UUID companyId, UUID customerId) {
        if (customerId == null || customers.findActiveBusinessCustomer(companyId, customerId).isEmpty()) {
            throw new BusinessRuleException("Pick an active business customer.");
        }
    }

    private static UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Charges belong to a company."));
    }
}
