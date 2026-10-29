package com.courier.modules.master.application;

import com.courier.modules.master.application.port.PincodePostalLookupProvider;
import com.courier.modules.master.application.command.PincodeCommand;
import com.courier.modules.master.domain.Area;
import com.courier.modules.master.domain.AreaRepository;
import com.courier.modules.master.domain.City;
import com.courier.modules.master.domain.CityRepository;
import com.courier.modules.master.domain.District;
import com.courier.modules.master.domain.DistrictRepository;
import com.courier.modules.master.domain.GlobalMasters;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.master.domain.PincodeRepository;
import com.courier.modules.master.domain.State;
import com.courier.modules.master.domain.StateRepository;
import com.courier.modules.master.infrastructure.MasterTable;
import com.courier.modules.master.infrastructure.MasterUniquenessChecker;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pincodes, the leaf of the hierarchy.
 *
 * <p>"One Pincode belongs to one Area" is enforced twice over: the {@code areaId} column
 * is single and non-null, and {@code (company_id, code)} is unique, so the same pincode
 * cannot be filed under two areas of one company.
 *
 * <p>Unlike its ancestors this list has no name uniqueness rule — two post offices in one
 * area really can share a locality name, and the pincode itself is already the key.
 */
@Slf4j
@Service
public class PincodeServiceImpl extends AbstractMasterDataService<Pincode> implements PincodeService {

    /**
     * Global list, but pincodes are the one geography level a company admin may also
     * write: onboarding a company routinely needs a serviceable pincode the platform
     * catalogue does not have yet, and waiting on a platform operator for that is the
     * wrong shape. Country/State/District/City/Area stay super-admin-only — a company
     * admin editing {@code PUNE} would be editing it for everyone, and there is no
     * equivalent day-to-day need to.
     */
    private static final String WRITE =
            "hasAnyRole('" + Roles.SUPER_ADMIN + "', '" + Roles.COMPANY_ADMIN + "')";

    /** Anyone signed in reads it — a booking clerk needs the map to book anything. */
    private static final String READ = "isAuthenticated()";

    private final AreaRepository areas;
    private final CityRepository cities;
    private final DistrictRepository districts;
    private final StateRepository states;
    private final PincodePostalLookupProvider postalLookup;
    private final GeographyAutoResolver geographyResolver;

    private final PincodeAreaService pincodeAreaService;

    public PincodeServiceImpl(PincodeRepository pincodes,
                              AreaRepository areas,
                              CityRepository cities,
                              DistrictRepository districts,
                              StateRepository states,
                              MasterUniquenessChecker uniqueness,
                              AuditService auditService,
                              PincodePostalLookupProvider postalLookup,
                              GeographyAutoResolver geographyResolver,
                              PincodeAreaService pincodeAreaService) {
        super(pincodes, uniqueness, auditService, "Pincode", MasterTable.PINCODES);
        this.areas = areas;
        this.cities = cities;
        this.districts = districts;
        this.states = states;
        this.postalLookup = postalLookup;
        this.geographyResolver = geographyResolver;
        this.pincodeAreaService = pincodeAreaService;
    }

    @Override
    protected boolean global() {
        return true;
    }

    /**
     * The area check must run inside the platform's own binding, not the caller's: the
     * Hibernate company filter scopes every read to whatever is currently bound, and for a
     * {@code COMPANY_ADMIN} caller that is their own company, not the platform's — so a
     * lookup of the platform's own area would see nothing. {@link #createEntity} enters
     * that binding too, but only around the save; wrapping the whole method is what lets
     * this check see the row it is actually validating against.
     */
    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public Pincode create(PincodeCommand command) {
        return withOwner(() -> {
            UUID companyId = requireCompany();
            requireArea(command.areaId(), companyId, true);

            Pincode pincode = new Pincode();
            applyCommonFields(pincode, command.code(), command.name(), command.description(),
                    command.displayOrder());
            pincode.setAreaId(command.areaId());
            applyFlags(pincode, command);
            Pincode saved = createEntity(pincode);
            pincodeAreaService.syncAreas(saved);
            return saved;
        });
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public Pincode update(UUID id, PincodeCommand command) {
        UUID companyId = requireCompany();
        Pincode saved = updateEntity(id, command.expectedVersion(), pincode -> {
            boolean reparented = !Objects.equals(pincode.getAreaId(), command.areaId());
            requireArea(command.areaId(), companyId, reparented);

            applyCommonFields(pincode, null, command.name(), command.description(),
                    command.displayOrder());
            pincode.setAreaId(command.areaId());
            applyFlags(pincode, command);
        });
        pincodeAreaService.syncAreas(saved);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Pincode getById(UUID id) {
        return doGetById(id);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Page<Pincode> search(MasterDataCriteria criteria, Pageable pageable) {
        return doSearch(criteria, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Pincode findByCode(String code) {
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID,
                () -> repository.findByCodeWithinCompany(code, GlobalMasters.PLATFORM_COMPANY_ID)
                        .orElseThrow(() -> new ResourceNotFoundException("Pincode", code)));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Optional<String> codeOf(UUID pincodeId) {
        if (pincodeId == null) {
            return Optional.empty();
        }
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> repository.findById(pincodeId)
                .filter(p -> GlobalMasters.PLATFORM_COMPANY_ID.equals(p.getCompanyId()))
                .map(Pincode::getCode));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Optional<PincodeLocation> location(String code) {
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> repository
                .findByCodeWithinCompany(code, GlobalMasters.PLATFORM_COMPANY_ID)
                .map(pincode -> {
                    Area area = areas.findById(pincode.getAreaId()).orElse(null);
                    City city = area != null ? cities.findById(area.getCityId()).orElse(null) : null;
                    return new PincodeLocation(pincode.getId(), pincode.getAreaId(),
                            city != null ? city.getId() : null, city != null ? city.getDistrictId() : null,
                            pincode.isServiceable(), pincode.isCodAvailable(), pincode.isOdaApplicable());
                }));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Optional<PincodeGeography> geography(String code) {
        return geography(code, null);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Optional<PincodeGeography> geography(String code, UUID areaId) {
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> repository
                .findByCodeWithinCompany(code, GlobalMasters.PLATFORM_COMPANY_ID)
                .map(pincode -> {
                    Area area = areas.findById(areaId != null ? areaId : pincode.getAreaId()).orElse(null);
                    City city = area != null ? cities.findById(area.getCityId()).orElse(null) : null;
                    District district = city != null ? districts.findById(city.getDistrictId()).orElse(null) : null;
                    State state = district != null ? states.findById(district.getStateId()).orElse(null) : null;
                    return new PincodeGeography(pincode.getName(),
                            area != null ? area.getName() : null,
                            city != null ? city.getName() : null,
                            district != null ? district.getName() : null,
                            state != null ? state.getName() : null);
                }));
    }

    /**
     * Same write audience as {@link #create}: a match can create State/District/City/Area
     * rows, which is exactly the "onboarding needs a row the platform catalogue does not
     * have yet" case {@link #create}'s own doc explains for Pincode itself — extended one
     * step further up the chain by {@link GeographyAutoResolver}.
     */
    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public Optional<PincodeAreaLookupResult> lookupPostalArea(String pincode) {
        if (pincode == null || !pincode.matches("^[0-9]{4,10}$")) {
            throw new BusinessRuleException("Enter a valid pincode (4 to 10 digits) before looking up its area.");
        }
        List<PincodePostalLookupProvider.PostOffice> matches = postalLookup.lookup(pincode);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        // Every match resolved up front — the create form's own preview of the full area
        // list needs all of them, not just the primary; this is the same resolution
        // syncAreas would do once the pincode is actually saved, just run one save earlier.
        List<GeographyAutoResolver.GeographyMatch> resolvedAll = matches.stream()
                .map(geographyResolver::resolveArea)
                .toList();
        GeographyAutoResolver.GeographyMatch primary = resolvedAll.get(0);
        return Optional.of(new PincodeAreaLookupResult(primary.area(), primary.city(),
                primary.district(), primary.state(), primary.country(),
                matches.get(0).name(), matches.size(), resolvedAll));
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public void delete(UUID id) {
        doDelete(id);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public Pincode activate(UUID id) {
        return doActivate(id);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public Pincode deactivate(UUID id) {
        return doDeactivate(id);
    }

    // ---------------------------------------------------------------------------- rules

    @Override
    protected void requireActivatable(Pincode pincode, UUID companyId) {
        requireArea(pincode.getAreaId(), companyId, true);
    }

    @Override
    protected Map<String, Object> snapshot(Pincode pincode) {
        Map<String, Object> values = super.snapshot(pincode);
        values.put("areaId", String.valueOf(pincode.getAreaId()));
        values.put("serviceable", pincode.isServiceable());
        values.put("codAvailable", pincode.isCodAvailable());
        values.put("prepaidAvailable", pincode.isPrepaidAvailable());
        values.put("pickupAvailable", pincode.isPickupAvailable());
        values.put("zone", pincode.getZone());
        values.put("odaApplicable", pincode.isOdaApplicable());
        return values;
    }

    /**
     * Absent flags default to <i>enabled</i>, matching the "a pincode we bothered to add
     * is one we serve" reading, except that {@code serviceable=false} folds the rest down
     * in {@link Pincode#applySpecificInvariants()}. ODA is the opposite default — most
     * pincodes are not out-of-delivery-area, so absent means false, not true.
     */
    private void applyFlags(Pincode pincode, PincodeCommand command) {
        pincode.setServiceable(orTrue(command.serviceable()));
        pincode.setCodAvailable(orTrue(command.codAvailable()));
        pincode.setPrepaidAvailable(orTrue(command.prepaidAvailable()));
        pincode.setPickupAvailable(orTrue(command.pickupAvailable()));
        pincode.setZone(command.zone());
        pincode.setOdaApplicable(Boolean.TRUE.equals(command.odaApplicable()));
    }

    private static boolean orTrue(Boolean value) {
        return value == null || value;
    }

    private void requireArea(UUID areaId, UUID companyId, boolean mustBeActive) {
        if (areaId == null) {
            throw new BusinessRuleException("A pincode must belong to an area.");
        }
        Area area = areas.findByIdWithinCompany(areaId, companyId)
                .orElseThrow(() -> new BusinessRuleException(
                        "No area of this company has id %s.".formatted(areaId)));
        if (mustBeActive && !area.isActive()) {
            throw new BusinessRuleException(
                    "Area %s is inactive, so nothing new may be filed under it."
                            .formatted(area.getCode()));
        }
    }
}
