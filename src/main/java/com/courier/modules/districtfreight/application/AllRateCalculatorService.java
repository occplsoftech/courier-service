package com.courier.modules.districtfreight.application;

import com.courier.modules.company.application.CompanySettingsService;
import com.courier.modules.customerpricing.application.CustomerRateStructureService;
import com.courier.modules.customerpricing.application.PricingZoneService;
import com.courier.modules.customerpricing.application.PricingZoneView;
import com.courier.modules.customerpricing.application.ZoneRateService;
import com.courier.modules.districtfreight.domain.NetworkCoveragePort;
import com.courier.modules.districtfreight.domain.PincodeCoverageLookupPort;
import com.courier.modules.districtfreight.domain.ZoneTariffPort;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.modules.master.domain.MasterDataSpecifications;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.PackageTypeRepository;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The all to all rate calculator: any from pincode, any to pincode, a weight and optionally a business customer, priced on
 * every active service type at once, from the same sources a booking uses and in the same order:
 * the customer's rate structure, then the customer's pricing zone, then the company's Zone Tariff.
 * District Level Freight is not tried: it needs a booking branch, not a pincode.
 *
 * <p>Not transactional itself: each source runs in its own read-only transaction, so one service's refusal (a
 * {@link BusinessRuleException}) is shown on that service's row and does not spoil the others.
 */
@Service
@RequiredArgsConstructor
public class AllRateCalculatorService {

    private static final int MONEY = 2;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final ServiceTypeRepository serviceTypes;
    private final PackageTypeRepository packageTypes;
    private final PincodeCoverageLookupPort coverageLookup;
    private final NetworkCoveragePort networkCoverage;
    private final ZoneTariffPort zoneTariff;
    private final PricingZoneService zoneService;
    private final ZoneRateService zoneRateService;
    private final CustomerRateStructureService rateStructure;
    private final CompanySettingsService companySettings;

    public record Input(String fromPincode, String toPincode, BigDecimal weightKg, UUID packageTypeId, UUID customerId,
                        BigDecimal declaredValue) {
    }

    /**
     * One service's price. {@code error} set (and the amounts null) when the service cannot carry it or has no rate.
     * {@code total} = freight + other charges + GST at the company's GST % (Settings → Finance), as a booking works it out.
     */
    public record Row(UUID serviceTypeId, String serviceTypeCode, String serviceTypeName, String source, String basis,
                      BigDecimal chargeableWeight, BigDecimal freight, BigDecimal otherCharges, BigDecimal gst,
                      BigDecimal total, Integer deliveryDays, Integer tatHours, String error) {
    }

    public record Result(String fromPincode, String toPincode, BigDecimal weightKg, UUID packageTypeId, String packageTypeName,
                         BigDecimal gstPercent, List<Row> rows) {
    }

    @PreAuthorize("isAuthenticated()")
    public Result calculate(Input in) {
        UUID companyId = CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Rates belong to a company."));
        String from = pin(in.fromPincode(), "from");
        String to = pin(in.toPincode(), "to");
        if (in.weightKg() == null || in.weightKg().signum() <= 0) {
            throw new BusinessRuleException("Enter the weight in kg, above 0.");
        }
        PackageType packageType = packageType(companyId, in.packageTypeId());
        PincodeCoverageLookupPort.CoverageRef dest = coverageLookup.findByPincode(to)
                .orElseThrow(() -> new BusinessRuleException("Pincode " + to + " is not in the pincode master."));
        if (!dest.serviceable()) {
            throw new BusinessRuleException("Pincode " + to + " is not serviceable.");
        }
        BigDecimal gstPercent = Optional.ofNullable(companySettings.get().getGstPercentage()).orElse(BigDecimal.ZERO);

        List<ServiceType> services = serviceTypes.findAll(MasterDataSpecifications.matching(
                        MasterDataCriteria.none().withCompanyId(companyId))).stream()
                .filter(ServiceType::isActive)
                .sorted(Comparator.comparing((ServiceType s) -> s.getDisplayOrder() == null ? 0 : s.getDisplayOrder())
                        .thenComparing(ServiceType::getName))
                .toList();
        List<Row> rows = new ArrayList<>();
        for (ServiceType service : services) {
            rows.add(row(service, packageType, from, to, dest, in, gstPercent));
        }
        return new Result(from, to, in.weightKg(), packageType.getId(), packageType.getName(), gstPercent, rows);
    }

    private Row row(ServiceType service, PackageType packageType, String from, String to,
                    PincodeCoverageLookupPort.CoverageRef dest, Input in, BigDecimal gstPercent) {
        Integer tat = null;
        try {
            NetworkCoveragePort.NetworkCoverageRef network = networkCoverage
                    .coverage(to, service.getId(), packageType.getId()).orElse(null);
            if (network != null) {
                if (!network.serviceable()) {
                    return failed(service, "%s service does not deliver to pincode %s.".formatted(network.networkLabel(), to));
                }
                tat = network.tatHours();
            }
            Integer days = tat != null ? Integer.valueOf((tat + 23) / 24) : service.getDeliveryDays();
            BigDecimal weight = in.weightKg();

            if (in.customerId() != null) {
                Optional<CustomerRateStructureService.Quote> structured =
                        rateStructure.quote(in.customerId(), service.getId(), from, to, weight);
                if (structured.isPresent()) {
                    var q = structured.get();
                    return priced(service, "Customer rate structure", q.label(), q.chargeableWeight(), q.freight(),
                            BigDecimal.ZERO, gstPercent, days, tat);
                }
                PricingZoneView zone = zoneService.resolve(in.customerId(), dest.districtId(), dest.cityId()).orElse(null);
                if (zone != null) {
                    ZoneRateService.Quote q = zoneRateService.calculate(new ZoneRateService.QuoteCommand(in.customerId(),
                            dest.districtId(), dest.cityId(), weight, in.declaredValue(), dest.odaApplicable(), null, service.getId()));
                    BigDecimal charges = q.odaCharge().add(q.docketCharge()).add(q.mathadiCharge()).add(q.packCharge())
                            .add(q.fuelSurcharge()).add(q.fovCharge());
                    return priced(service, "Customer pricing zone", "Zone " + q.zoneName(), q.chargeableWeight(), q.freight(),
                            charges, gstPercent, days, tat);
                }
            }
            Optional<ZoneTariffPort.TariffPrice> tariff = zoneTariff.price(from, to, service.getId(), packageType.getId(),
                    weight, 1, in.declaredValue(), LocalDate.now(INDIA), in.customerId());
            if (tariff.isPresent()) {
                var t = tariff.get();
                return priced(service, "Zone Tariff", t.label() + (t.basis() == null ? "" : " · " + t.basis()), weight,
                        t.freight(), t.extras(), gstPercent, days, tat);
            }
            return failed(service, "No rate for this route on this service.");
        } catch (BusinessRuleException e) {
            return failed(service, e.getMessage());
        }
    }

    private static Row priced(ServiceType s, String source, String basis, BigDecimal chargeable, BigDecimal freight,
                              BigDecimal charges, BigDecimal gstPercent, Integer days, Integer tat) {
        BigDecimal f = freight.setScale(MONEY, RoundingMode.HALF_UP);
        BigDecimal c = (charges == null ? BigDecimal.ZERO : charges).setScale(MONEY, RoundingMode.HALF_UP);
        BigDecimal gst = f.add(c).multiply(gstPercent).divide(BigDecimal.valueOf(100), MONEY, RoundingMode.HALF_UP);
        return new Row(s.getId(), s.getCode(), s.getName(), source, basis, chargeable, f, c, gst, f.add(c).add(gst),
                days, tat, null);
    }

    private static Row failed(ServiceType s, String error) {
        return new Row(s.getId(), s.getCode(), s.getName(), null, null, null, null, null, null, null, null, null, error);
    }

    private PackageType packageType(UUID companyId, UUID id) {
        if (id != null) {
            return packageTypes.findByIdWithinCompany(id, companyId)
                    .orElseThrow(() -> new BusinessRuleException("No such package type: " + id));
        }
        // Default: the first active non-document package type (parcels), else any active one.
        List<PackageType> all = packageTypes.findAll(MasterDataSpecifications.matching(
                MasterDataCriteria.none().withCompanyId(companyId))).stream().filter(PackageType::isActive).toList();
        return all.stream().filter(p -> !p.isDocumentType()).findFirst()
                .or(() -> all.stream().findFirst())
                .orElseThrow(() -> new BusinessRuleException("Add a package type first (Masters → Package Type)."));
    }

    private static String pin(String value, String which) {
        String v = value == null ? "" : value.trim();
        if (!v.matches("^[1-9][0-9]{5}$")) {
            throw new BusinessRuleException("Enter a 6-digit " + which + " pincode.");
        }
        return v;
    }
}
