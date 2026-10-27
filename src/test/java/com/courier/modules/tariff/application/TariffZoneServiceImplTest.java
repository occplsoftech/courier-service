package com.courier.modules.tariff.application;

import com.courier.modules.master.application.CityService;
import com.courier.modules.master.application.DistrictService;
import com.courier.modules.master.application.PincodeLocation;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.application.StateService;
import com.courier.modules.master.domain.City;
import com.courier.modules.master.domain.District;
import com.courier.modules.master.domain.State;
import com.courier.modules.tariff.application.TariffZoneService.LocationInput;
import com.courier.modules.tariff.application.TariffZoneService.ZoneInput;
import com.courier.modules.tariff.domain.LocationScope;
import com.courier.modules.tariff.domain.TariffChargeRuleRepository;
import com.courier.modules.tariff.domain.TariffRateCardRepository;
import com.courier.modules.tariff.domain.TariffRateSlabRepository;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.domain.TariffZoneLocation;
import com.courier.modules.tariff.domain.TariffZoneLocationRepository;
import com.courier.modules.tariff.domain.TariffZoneRepository;
import com.courier.modules.tariff.domain.TariffZoneStatus;
import com.courier.modules.tariff.domain.ZoneUsage;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.DuplicateResourceException;
import com.courier.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tariff zones: creating them, adding places, the "one zone per place per direction" rule, and finding the zone of a
 * pincode with the most specific location winning. Repositories and the geography masters are mocked; an in-memory
 * list stands in for saved locations so the clash checks see what earlier calls saved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TariffZoneServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID MAHARASHTRA = UUID.randomUUID();
    private static final UUID KERALA = UUID.randomUUID();
    private static final UUID PUNE_DISTRICT = UUID.randomUUID();
    private static final UUID MUMBAI_DISTRICT = UUID.randomUUID();
    private static final UUID ERNAKULAM = UUID.randomUUID();
    private static final UUID PUNE_CITY = UUID.randomUUID();
    private static final UUID MUMBAI_CITY = UUID.randomUUID();
    private static final UUID KOCHI = UUID.randomUUID();

    @Mock private TariffZoneRepository zones;
    @Mock private TariffZoneLocationRepository locations;
    @Mock private StateService stateService;
    @Mock private DistrictService districtService;
    @Mock private CityService cityService;
    @Mock private PincodeService pincodeService;
    @Mock private AuditService auditService;
    @Mock private TariffRateCardRepository rateCards;
    @Mock private TariffRateSlabRepository rateSlabs;
    @Mock private TariffChargeRuleRepository chargeRules;

    private TariffZoneServiceImpl service;
    private final List<TariffZone> savedZones = new ArrayList<>();
    private final List<TariffZoneLocation> savedLocations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new TariffZoneServiceImpl(zones, locations, rateCards, rateSlabs, chargeRules, stateService, districtService, cityService, pincodeService, auditService);
        CompanyContext.setCompanyId(COMPANY);

        state(MAHARASHTRA, "Maharashtra");
        state(KERALA, "Kerala");
        district(PUNE_DISTRICT, "Pune", MAHARASHTRA);
        district(MUMBAI_DISTRICT, "Mumbai", MAHARASHTRA);
        district(ERNAKULAM, "Ernakulam", KERALA);
        city(PUNE_CITY, "Pune", PUNE_DISTRICT);
        city(MUMBAI_CITY, "Mumbai", MUMBAI_DISTRICT);
        city(KOCHI, "Kochi", ERNAKULAM);
        pincode("411030", PUNE_CITY, PUNE_DISTRICT);
        pincode("412308", null, PUNE_DISTRICT);
        pincode("400001", MUMBAI_CITY, MUMBAI_DISTRICT);
        pincode("682001", KOCHI, ERNAKULAM);

        when(zones.save(any(TariffZone.class))).thenAnswer(i -> {
            TariffZone z = i.getArgument(0);
            if (savedZones.stream().noneMatch(x -> x == z)) {
                z.setCompanyId(COMPANY);
                savedZones.add(z);
            }
            return z;
        });
        when(zones.findByIdWithinCompany(any(), eq(COMPANY))).thenAnswer(i ->
                savedZones.stream().filter(z -> z.getId().equals(i.getArgument(0)) && !z.isDeleted()).findFirst());
        when(zones.findAllWithinCompany(any(), eq(COMPANY))).thenAnswer(i -> savedZones.stream()
                .filter(z -> ((java.util.Collection<?>) i.getArgument(0)).contains(z.getId())).toList());
        when(zones.existsByCode(eq(COMPANY), anyString(), any())).thenAnswer(i -> savedZones.stream()
                .anyMatch(z -> !z.isDeleted() && z.getCode().equals(i.getArgument(1)) && !z.getId().equals(i.getArgument(2))));

        when(locations.save(any(TariffZoneLocation.class))).thenAnswer(i -> {
            TariffZoneLocation l = i.getArgument(0);
            if (savedLocations.stream().noneMatch(x -> x == l)) {
                l.setCompanyId(COMPANY);
                savedLocations.add(l);
            }
            return l;
        });
        when(locations.findByIdWithinCompany(any(), eq(COMPANY))).thenAnswer(i -> live().stream()
                .filter(l -> l.getId().equals(i.getArgument(0))).findFirst());
        when(locations.findByZone(eq(COMPANY), any())).thenAnswer(i -> live().stream()
                .filter(l -> l.getZoneId().equals(i.getArgument(1))).toList());
        when(locations.findSameState(eq(COMPANY), any(), any())).thenAnswer(i -> same(LocationScope.STATE, i.getArgument(2), null));
        when(locations.findSameDistrict(eq(COMPANY), any(), any())).thenAnswer(i -> same(LocationScope.DISTRICT, i.getArgument(2), null));
        when(locations.findSameCity(eq(COMPANY), any(), any())).thenAnswer(i -> same(LocationScope.CITY, i.getArgument(2), null));
        when(locations.findSamePincode(eq(COMPANY), any(), any())).thenAnswer(i -> same(LocationScope.PINCODE, null, i.getArgument(2)));
        when(locations.findCandidates(eq(COMPANY), any(), any(), any(), any())).thenAnswer(i -> live().stream().filter(l -> switch (l.getScope()) {
            case PINCODE -> l.getPincode().equals(i.getArgument(1));
            case CITY -> l.getCityId().equals(i.getArgument(2));
            case DISTRICT -> l.getDistrictId().equals(i.getArgument(3));
            case STATE -> l.getStateId().equals(i.getArgument(4));
        }).toList());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    // ----------------------------------------------------------- fixtures

    private List<TariffZoneLocation> live() {
        return savedLocations.stream().filter(l -> !l.isDeleted()).toList();
    }

    private List<TariffZoneLocation> same(LocationScope scope, UUID id, String pincode) {
        return live().stream().filter(l -> l.getScope() == scope && (scope == LocationScope.PINCODE
                ? l.getPincode().equals(pincode) : l.placeKey().equals(String.valueOf(id)))).toList();
    }

    private void state(UUID id, String name) {
        State s = new State();
        s.setId(id);
        s.setName(name);
        when(stateService.getById(id)).thenReturn(s);
    }

    private void district(UUID id, String name, UUID stateId) {
        District d = new District();
        d.setId(id);
        d.setName(name);
        d.setStateId(stateId);
        when(districtService.getById(id)).thenReturn(d);
    }

    private void city(UUID id, String name, UUID districtId) {
        City c = new City();
        c.setId(id);
        c.setName(name);
        c.setDistrictId(districtId);
        when(cityService.getById(id)).thenReturn(c);
    }

    private void pincode(String code, UUID cityId, UUID districtId) {
        when(pincodeService.location(code)).thenReturn(Optional.of(
                new PincodeLocation(UUID.randomUUID(), UUID.randomUUID(), cityId, districtId, true, true, false)));
    }

    private static ZoneInput zone(String code, ZoneUsage usage) {
        return new ZoneInput(code, code.charAt(0) + code.substring(1).toLowerCase(), usage, null, null);
    }

    private static LocationInput stateLoc(UUID id) {
        return new LocationInput(LocationScope.STATE, id, null, null, null);
    }

    private static LocationInput districtLoc(UUID id) {
        return new LocationInput(LocationScope.DISTRICT, null, id, null, null);
    }

    private static LocationInput cityLoc(UUID id) {
        return new LocationInput(LocationScope.CITY, null, null, id, null);
    }

    private static LocationInput pinLoc(String pin) {
        return new LocationInput(LocationScope.PINCODE, null, null, null, pin);
    }

    // ---------------------------------------------------------- creating

    @Test
    @DisplayName("a zone is created with its code tidied and its locations filled in up to the state")
    void createsZoneWithLocations() {
        TariffZoneView v = service.create(new ZoneInput(" north east ", "North East", ZoneUsage.TO, "", 7),
                List.of(cityLoc(MUMBAI_CITY), stateLoc(KERALA)));

        assertThat(v.zone().getCode()).isEqualTo("NORTH_EAST");
        assertThat(v.zone().getDisplayOrder()).isEqualTo(7);
        assertThat(v.zone().getDescription()).isNull();
        assertThat(v.locations()).hasSize(2);
        TariffZoneLocation mumbai = savedLocations.stream().filter(l -> l.getScope() == LocationScope.CITY).findFirst().orElseThrow();
        assertThat(mumbai.getDistrictId()).isEqualTo(MUMBAI_DISTRICT);
        assertThat(mumbai.getStateId()).isEqualTo(MAHARASHTRA);
        assertThat(mumbai.getZoneId()).isEqualTo(v.zone().getId());
        assertThat(v.locations()).extracting(TariffZoneView.LocationView::label)
                .contains("Mumbai, Mumbai district, Maharashtra", "Kerala");
    }

    @Test
    @DisplayName("a zone code may be used once per company")
    void codeIsUnique() {
        service.create(zone("METRO", ZoneUsage.TO), List.of());

        assertThatThrownBy(() -> service.create(zone("metro", ZoneUsage.TO), List.of()))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a bad code, a missing name or a missing usage is refused")
    void invalidZone() {
        assertThatThrownBy(() -> service.create(new ZoneInput("X", "x", ZoneUsage.TO, null, null), List.of()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("2 to 30");
        assertThatThrownBy(() -> service.create(new ZoneInput("PUNE", " ", ZoneUsage.TO, null, null), List.of()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> service.create(new ZoneInput("PUNE", "Pune", null, null, null), List.of()))
                .isInstanceOf(BusinessRuleException.class);
    }

    // --------------------------------------------------------- locations

    @Test
    @DisplayName("a place already in another TO zone cannot join a second TO zone")
    void placeInOneZonePerDirection() {
        service.create(zone("MAHARASHTRA", ZoneUsage.TO), List.of(stateLoc(MAHARASHTRA)));

        assertThatThrownBy(() -> service.create(zone("WEST", ZoneUsage.TO), List.of(stateLoc(MAHARASHTRA))))
                .isInstanceOf(DuplicateResourceException.class).hasMessageContaining("MAHARASHTRA");
    }

    @Test
    @DisplayName("a FROM-only zone and a TO-only zone may share a place; BOTH competes with either")
    void usagesThatDoNotCompete() {
        service.create(zone("PUNE_ORIGIN", ZoneUsage.FROM), List.of(cityLoc(PUNE_CITY)));
        service.create(zone("PUNE", ZoneUsage.TO), List.of(cityLoc(PUNE_CITY)));

        assertThatThrownBy(() -> service.create(zone("PUNE_ALL", ZoneUsage.BOTH), List.of(cityLoc(PUNE_CITY))))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a city and its state can be in different zones: specificity, not a clash")
    void differentScopesDoNotClash() {
        service.create(zone("MAHARASHTRA", ZoneUsage.TO), List.of(stateLoc(MAHARASHTRA)));
        TariffZoneView metro = service.create(zone("METRO", ZoneUsage.TO), List.of(cityLoc(MUMBAI_CITY)));

        assertThat(metro.locations()).hasSize(1);
    }

    @Test
    @DisplayName("the same place twice in one request is refused before anything is saved")
    void duplicateInRequest() {
        assertThatThrownBy(() -> service.create(zone("METRO", ZoneUsage.TO), List.of(cityLoc(MUMBAI_CITY), cityLoc(MUMBAI_CITY))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("listed twice");

        assertThat(savedZones).isEmpty();
        assertThat(savedLocations).isEmpty();
    }

    @Test
    @DisplayName("adding locations is all or nothing: one clash saves none of them")
    void addLocationsAllOrNothing() {
        service.create(zone("MAHARASHTRA", ZoneUsage.TO), List.of(stateLoc(MAHARASHTRA)));
        TariffZoneView south = service.create(zone("SOUTH", ZoneUsage.TO), List.of());
        int before = savedLocations.size();

        assertThatThrownBy(() -> service.addLocations(south.zone().getId(), List.of(stateLoc(KERALA), stateLoc(MAHARASHTRA))))
                .isInstanceOf(DuplicateResourceException.class);

        assertThat(savedLocations).hasSize(before);
    }

    @Test
    @DisplayName("adding a place this zone already has says so")
    void alreadyInThisZone() {
        TariffZoneView kerala = service.create(zone("KERALA", ZoneUsage.TO), List.of(stateLoc(KERALA)));

        assertThatThrownBy(() -> service.addLocations(kerala.zone().getId(), List.of(stateLoc(KERALA))))
                .isInstanceOf(DuplicateResourceException.class).hasMessageContaining("already in this zone");
    }

    @Test
    @DisplayName("a city must be in the district chosen with it")
    void cityMustBeInDistrict() {
        assertThatThrownBy(() -> service.create(zone("METRO", ZoneUsage.TO),
                List.of(new LocationInput(LocationScope.CITY, null, PUNE_DISTRICT, MUMBAI_CITY, null))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not in the district");
    }

    @Test
    @DisplayName("pincodes are checked against the pincode master and must be 6 digits")
    void pincodeChecks() {
        assertThatThrownBy(() -> service.create(zone("METRO", ZoneUsage.TO), List.of(pinLoc("01234"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("6 digits");
        when(pincodeService.location("999999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(zone("METRO", ZoneUsage.TO), List.of(pinLoc("999999"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not in the pincode master");

        TariffZoneView v = service.create(zone("METRO", ZoneUsage.TO), List.of(pinLoc("400001")));
        TariffZoneLocation pin = savedLocations.get(0);
        assertThat(pin.getCityId()).isEqualTo(MUMBAI_CITY);
        assertThat(pin.getStateId()).isEqualTo(MAHARASHTRA);
        assertThat(v.locations().get(0).label()).isEqualTo("400001 (Mumbai)");
    }

    @Test
    @DisplayName("an unknown state, district or city is refused with a readable message")
    void unknownGeography() {
        UUID ghost = UUID.randomUUID();
        when(stateService.getById(ghost)).thenThrow(new ResourceNotFoundException("State", ghost));

        assertThatThrownBy(() -> service.create(zone("X1", ZoneUsage.TO), List.of(stateLoc(ghost))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("No such state");
    }

    @Test
    @DisplayName("removing a location frees the place for another zone")
    void removeLocation() {
        TariffZoneView west = service.create(zone("WEST", ZoneUsage.TO), List.of(stateLoc(MAHARASHTRA)));
        UUID locationId = west.locations().get(0).id();

        service.removeLocation(west.zone().getId(), locationId);
        TariffZoneView maharashtra = service.create(zone("MAHARASHTRA", ZoneUsage.TO), List.of(stateLoc(MAHARASHTRA)));

        assertThat(maharashtra.locations()).hasSize(1);
    }

    @Test
    @DisplayName("widening a zone to BOTH is refused when a FROM zone already holds one of its places")
    void wideningUsageRechecks() {
        service.create(zone("PUNE_ORIGIN", ZoneUsage.FROM), List.of(cityLoc(PUNE_CITY)));
        TariffZoneView pune = service.create(zone("PUNE", ZoneUsage.TO), List.of(cityLoc(PUNE_CITY)));

        assertThatThrownBy(() -> service.update(pune.zone().getId(), zone("PUNE", ZoneUsage.BOTH), null))
                .isInstanceOf(DuplicateResourceException.class).hasMessageContaining("PUNE_ORIGIN");
    }

    @Test
    @DisplayName("deleting a zone deletes its locations too")
    void deleteZone() {
        TariffZoneView kerala = service.create(zone("KERALA", ZoneUsage.TO), List.of(stateLoc(KERALA)));

        service.delete(kerala.zone().getId());

        assertThat(live()).isEmpty();
        assertThat(kerala.zone().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("a zone a rate card uses cannot be deleted, nor narrowed out of the direction the card uses it in")
    void zoneInUse() {
        TariffZoneView pune = service.create(zone("PUNE", ZoneUsage.BOTH), List.of(cityLoc(PUNE_CITY)));
        TariffZoneView kerala = service.create(zone("KERALA", ZoneUsage.TO), List.of(stateLoc(KERALA)));
        when(rateCards.existsByFromZone(COMPANY, pune.zone().getId())).thenReturn(true);
        when(rateSlabs.existsByToZone(COMPANY, kerala.zone().getId())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(pune.zone().getId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("used by a rate card");
        assertThatThrownBy(() -> service.delete(kerala.zone().getId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("used by a rate card");
        assertThatThrownBy(() -> service.update(pune.zone().getId(), zone("PUNE", ZoneUsage.TO), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("from zone of a rate card");
        assertThatThrownBy(() -> service.update(kerala.zone().getId(), zone("KERALA", ZoneUsage.FROM), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("must stay TO or BOTH");
        assertThat(pune.zone().isDeleted()).isFalse();
    }

    // ------------------------------------------------------------ resolve

    private void sampleZones() {
        service.create(zone("PUNE", ZoneUsage.BOTH), List.of(cityLoc(PUNE_CITY)));
        service.create(zone("EXPUNE", ZoneUsage.TO), List.of(districtLoc(PUNE_DISTRICT)));
        service.create(zone("MAHARASHTRA", ZoneUsage.TO), List.of(stateLoc(MAHARASHTRA)));
        service.create(zone("METRO", ZoneUsage.TO), List.of(cityLoc(MUMBAI_CITY)));
        service.create(zone("KERALA", ZoneUsage.TO), List.of(stateLoc(KERALA)));
    }

    @Test
    @DisplayName("the most specific location decides the zone of a pincode")
    void mostSpecificWins() {
        sampleZones();

        assertThat(service.resolve("411030", ZoneUsage.TO).orElseThrow().zone().getCode()).isEqualTo("PUNE");
        assertThat(service.resolve("412308", ZoneUsage.TO).orElseThrow().zone().getCode()).isEqualTo("EXPUNE");
        TariffZoneService.Resolution mumbai = service.resolve("400001", ZoneUsage.TO).orElseThrow();
        assertThat(mumbai.zone().getCode()).isEqualTo("METRO");
        assertThat(mumbai.matchedBy().scope()).isEqualTo(LocationScope.CITY);
        assertThat(mumbai.place().cityName()).isEqualTo("Mumbai");
        assertThat(mumbai.place().stateName()).isEqualTo("Maharashtra");
        assertThat(service.resolve("682001", ZoneUsage.TO).orElseThrow().zone().getCode()).isEqualTo("KERALA");
    }

    @Test
    @DisplayName("a pincode rule beats every other rule")
    void pincodeRuleWins() {
        sampleZones();
        service.create(zone("SPECIAL", ZoneUsage.TO), List.of(pinLoc("400001")));

        assertThat(service.resolve("400001", ZoneUsage.TO).orElseThrow().zone().getCode()).isEqualTo("SPECIAL");
    }

    @Test
    @DisplayName("only zones of the right direction answer: Pune (BOTH) is a from zone, Metro (TO) is not")
    void directionMatters() {
        sampleZones();

        assertThat(service.resolve("411030", ZoneUsage.FROM).orElseThrow().zone().getCode()).isEqualTo("PUNE");
        assertThat(service.resolve("400001", ZoneUsage.FROM)).isEmpty();
    }

    @Test
    @DisplayName("an inactive zone is skipped and the next most specific one answers")
    void inactiveZoneSkipped() {
        sampleZones();
        TariffZone metro = savedZones.stream().filter(z -> z.getCode().equals("METRO")).findFirst().orElseThrow();

        service.deactivate(metro.getId());

        assertThat(metro.getStatus()).isEqualTo(TariffZoneStatus.INACTIVE);
        assertThat(service.resolve("400001", ZoneUsage.TO).orElseThrow().zone().getCode()).isEqualTo("MAHARASHTRA");
    }

    @Test
    @DisplayName("a pincode no zone covers resolves to nothing; a bad or unknown pincode is refused")
    void unresolved() {
        service.create(zone("KERALA", ZoneUsage.TO), List.of(stateLoc(KERALA)));

        assertThat(service.resolve("400001", ZoneUsage.TO)).isEmpty();
        assertThatThrownBy(() -> service.resolve("4000", ZoneUsage.TO)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.resolve("400001", ZoneUsage.BOTH)).isInstanceOf(BusinessRuleException.class);
        when(pincodeService.location("999999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve("999999", ZoneUsage.TO))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not in the pincode master");
    }

    @Test
    @DisplayName("every change is audited")
    void audited() {
        TariffZoneView v = service.create(zone("KERALA", ZoneUsage.TO), List.of(stateLoc(KERALA)));
        service.addLocations(v.zone().getId(), List.of(cityLoc(KOCHI)));

        ArgumentCaptor<com.courier.shared.audit.domain.AuditAction> action =
                ArgumentCaptor.forClass(com.courier.shared.audit.domain.AuditAction.class);
        verify(auditService, org.mockito.Mockito.atLeast(2)).record(action.capture(), eq("TariffZone"), any(), any());
        assertThat(action.getAllValues()).contains(com.courier.shared.audit.domain.AuditAction.TARIFF_ZONE_CREATED,
                com.courier.shared.audit.domain.AuditAction.TARIFF_ZONE_LOCATION_ADDED);
        verify(locations, never()).findSameState(eq(COMPANY), any(), isNull());
    }
}
