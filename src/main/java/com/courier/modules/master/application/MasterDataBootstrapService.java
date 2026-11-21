package com.courier.modules.master.application;

import com.courier.modules.master.application.command.PackageTypeCommand;
import com.courier.modules.master.application.command.PaymentModeCommand;
import com.courier.modules.master.application.command.ServiceTypeCommand;
import com.courier.modules.master.application.command.VehicleTypeCommand;
import com.courier.modules.master.application.command.WeightSlabCommand;
import com.courier.modules.master.domain.MasterDataEntity;
import com.courier.modules.master.domain.PackageTypeRepository;
import com.courier.modules.master.domain.PaymentModeRepository;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.modules.master.domain.VehicleTypeRepository;
import com.courier.modules.master.domain.WeightSlabRepository;
import com.courier.modules.master.domain.WeightUnit;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.security.Roles;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fills the four flat catalogues and the weight slabs with the industry-standard set, on
 * demand, for the calling company.
 *
 * <p><b>Why an endpoint and not automatic seeding.</b> Company provisioning already seeds
 * roles and settings, and the obvious move would have been to add master data there. That
 * would mean {@code modules/company} calling {@code modules/master}, which points the
 * dependency arrow the wrong way for a module company knows nothing about — and it would
 * leave every company created before this release with empty lists anyway, so a fill-in
 * path was needed regardless. One explicit, idempotent action serves both.
 *
 * <p><b>Idempotent by code.</b> A row whose code already exists — even soft deleted — is
 * skipped, not overwritten. Running this twice changes nothing, and it never resurrects a
 * catalogue entry an administrator deliberately removed.
 *
 * <p>The geography hierarchy is deliberately <i>not</i> seeded. There is no standard set of
 * countries and pincodes that is right for an arbitrary courier, and inventing one would
 * put thousands of rows nobody asked for in front of every new company.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterDataBootstrapService {

    private static final String WRITE = "hasRole('" + Roles.COMPANY_ADMIN + "')";

    private final VehicleTypeService vehicleTypes;
    private final PackageTypeService packageTypes;
    private final ServiceTypeService serviceTypes;
    private final PaymentModeService paymentModes;
    private final WeightSlabService weightSlabs;

    private final VehicleTypeRepository vehicleTypeRepository;
    private final PackageTypeRepository packageTypeRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final PaymentModeRepository paymentModeRepository;
    private final WeightSlabRepository weightSlabRepository;

    private final AuditService auditService;

    /**
     * @param created how many rows were inserted, per list
     * @param skipped how many were already present, per list
     */
    public record BootstrapResult(Map<String, Integer> created, Map<String, Integer> skipped) {
    }

    @Transactional
    @PreAuthorize(WRITE)
    public BootstrapResult seedDefaults() {
        Map<String, Integer> created = new LinkedHashMap<>();
        Map<String, Integer> skipped = new LinkedHashMap<>();

        seed("vehicleTypes", defaultVehicleTypes(), VehicleTypeCommand::code,
                code -> exists(vehicleTypeRepository, code), vehicleTypes::create, created, skipped);
        seed("packageTypes", defaultPackageTypes(), PackageTypeCommand::code,
                code -> exists(packageTypeRepository, code), packageTypes::create, created, skipped);
        seed("serviceTypes", defaultServiceTypes(), ServiceTypeCommand::code,
                code -> exists(serviceTypeRepository, code), serviceTypes::create, created, skipped);
        seed("paymentModes", defaultPaymentModes(), PaymentModeCommand::code,
                code -> exists(paymentModeRepository, code), paymentModes::create, created, skipped);
        seed("weightSlabs", defaultWeightSlabs(), WeightSlabCommand::code,
                code -> exists(weightSlabRepository, code), weightSlabs::create, created, skipped);

        int total = created.values().stream().mapToInt(Integer::intValue).sum();
        log.info("Master data bootstrap for company {}: {} row(s) created, {} skipped",
                CompanyContext.getCompanyId().orElse(null), total,
                skipped.values().stream().mapToInt(Integer::intValue).sum());
        auditService.record(AuditAction.MASTER_DATA_SEEDED, "MasterData", null,
                Map.of("created", created, "skipped", skipped));

        return new BootstrapResult(created, skipped);
    }

    private <C> void seed(String list,
                          List<C> definitions,
                          Function<C, String> codeOf,
                          java.util.function.Predicate<String> alreadyPresent,
                          Consumer<C> create,
                          Map<String, Integer> created,
                          Map<String, Integer> skipped) {
        int inserted = 0;
        int existing = 0;
        for (C definition : definitions) {
            String code = MasterDataEntity.normaliseCode(codeOf.apply(definition));
            if (alreadyPresent.test(code)) {
                existing++;
                continue;
            }
            create.accept(definition);
            inserted++;
        }
        created.put(list, inserted);
        skipped.put(list, existing);
    }

    private boolean exists(com.courier.modules.master.domain.MasterDataRepository<?> repository,
                           String code) {
        // Live rows only. A soft-deleted default that an administrator removed on purpose
        // is still refused by the uniqueness check inside create(), which surfaces as a
        // 409 rather than being silently resurrected here.
        return repository.findByCodeWithinCompany(code, CompanyContext.requireCompanyId()).isPresent();
    }

    // ------------------------------------------------------------------- the standard set

    private List<VehicleTypeCommand> defaultVehicleTypes() {
        return List.of(
                new VehicleTypeCommand("BIKE", "Two Wheeler", "Documents and small parcels",
                        10, new BigDecimal("20.000"), new BigDecimal("2.000"), 2, false, null),
                new VehicleTypeCommand("AUTO", "Three Wheeler", "Local pickup and delivery",
                        20, new BigDecimal("500.000"), new BigDecimal("40.000"), 3, false, null),
                new VehicleTypeCommand("PICKUP", "Pickup Van", "Intra-city line feed",
                        30, new BigDecimal("1500.000"), new BigDecimal("150.000"), 4, false, null),
                new VehicleTypeCommand("TRUCK", "Truck", "Inter-city line haul",
                        40, new BigDecimal("9000.000"), new BigDecimal("600.000"), 6, true, null),
                new VehicleTypeCommand("CONTAINER", "Container", "Long-haul containerised load",
                        50, new BigDecimal("25000.000"), new BigDecimal("2000.000"), 10, true, null));
    }

    private List<PackageTypeCommand> defaultPackageTypes() {
        return List.of(
                new PackageTypeCommand("DOCUMENT", "Document", "Papers and envelopes",
                        10, true, false, new BigDecimal("0.500"), null, null, null, null),
                new PackageTypeCommand("PARCEL", "Parcel", "General non-document consignment",
                        20, false, false, null, null, null, null, null),
                new PackageTypeCommand("BOX", "Box", "Cartons and cases",
                        30, false, false, null, new BigDecimal("30.00"), new BigDecimal("30.00"),
                        new BigDecimal("30.00"), null),
                new PackageTypeCommand("BAG", "Bag", "Sacks and soft packaging",
                        40, false, false, null, null, null, null, null),
                new PackageTypeCommand("PALLET", "Pallet", "Palletised freight",
                        50, false, false, null, new BigDecimal("120.00"), new BigDecimal("100.00"),
                        new BigDecimal("150.00"), null));
    }

    private List<ServiceTypeCommand> defaultServiceTypes() {
        return List.of(
                new ServiceTypeCommand("SAME_DAY", "Same Day", "Booked and delivered today",
                        10, 0, true, LocalTime.of(11, 0), 40, null, null),
                new ServiceTypeCommand("EXPRESS", "Express", "Next working day where serviced",
                        20, 1, true, LocalTime.of(18, 0), 30, null, null),
                new ServiceTypeCommand("STANDARD", "Standard", "The default service level",
                        30, 3, false, LocalTime.of(18, 0), 20, null, null),
                new ServiceTypeCommand("ECONOMY", "Economy", "Lowest cost, longest transit",
                        40, 6, false, LocalTime.of(18, 0), 10, null, null));
    }

    private List<PaymentModeCommand> defaultPaymentModes() {
        return List.of(
                new PaymentModeCommand("PAID", "Paid", "Freight collected at booking",
                        10, true, false, false, false, null),
                new PaymentModeCommand("TO_PAY", "To Pay", "Freight collected at delivery",
                        20, false, true, false, false, null),
                new PaymentModeCommand("TBB", "To Be Billed", "Billed to a credit account",
                        30, false, false, true, false, null),
                new PaymentModeCommand("COD", "Cash on Delivery",
                        "Consignee's amount collected on behalf of the shipper",
                        40, false, true, false, true, null));
    }

    private List<WeightSlabCommand> defaultWeightSlabs() {
        return List.of(
                slab("SLAB_0_500G", "Up to 500 g", 10, "0.000", "0.500"),
                slab("SLAB_500G_1KG", "500 g to 1 kg", 20, "0.500", "1.000"),
                slab("SLAB_1_5KG", "1 to 5 kg", 30, "1.000", "5.000"),
                slab("SLAB_5_10KG", "5 to 10 kg", 40, "5.000", "10.000"),
                slab("SLAB_10_25KG", "10 to 25 kg", 50, "10.000", "25.000"));
    }

    private WeightSlabCommand slab(String code, String name, int order, String min, String max) {
        return new WeightSlabCommand(code, name, null, order,
                new BigDecimal(min), new BigDecimal(max), WeightUnit.KG, null);
    }
}
