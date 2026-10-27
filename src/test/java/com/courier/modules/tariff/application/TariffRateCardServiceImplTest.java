package com.courier.modules.tariff.application;

import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.PincodeLocation;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.tariff.application.TariffRateCardService.CardInput;
import com.courier.modules.tariff.application.TariffRateCardService.ChargeInput;
import com.courier.modules.tariff.application.TariffRateCardService.LaneInput;
import com.courier.modules.tariff.application.TariffRateCardService.RateCardDetail;
import com.courier.modules.tariff.application.TariffRateCardService.SlabInput;
import com.courier.modules.tariff.domain.ChargeCalcMethod;
import com.courier.modules.tariff.domain.SlabRateType;
import com.courier.modules.tariff.domain.TariffChargeRule;
import com.courier.modules.tariff.domain.TariffChargeRuleRepository;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffRateCardRepository;
import com.courier.modules.tariff.domain.TariffRateSlab;
import com.courier.modules.tariff.domain.TariffRateSlabRepository;
import com.courier.modules.tariff.domain.TariffStatus;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.domain.TariffZoneRepository;
import com.courier.modules.tariff.domain.TotalRounding;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Rate cards end to end over in-memory "saved" lists: creating a card, saving lanes, charges, activation rules, and a
 * quote from pickup and delivery pincodes through the real calculator.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TariffRateCardServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID MAHARASHTRA = UUID.randomUUID();
    private static final UUID MP = UUID.randomUUID();

    @Mock private TariffRateCardRepository cards;
    @Mock private TariffRateSlabRepository slabs;
    @Mock private TariffChargeRuleRepository charges;
    @Mock private TariffZoneRepository zones;
    @Mock private TariffZoneService zoneService;
    @Mock private ServiceTypeService serviceTypeService;
    @Mock private PackageTypeService packageTypeService;
    @Mock private PincodeService pincodeService;
    @Mock private AuditService auditService;

    private TariffRateCardServiceImpl service;
    private TariffQuoteServiceImpl quotes;
    private final List<TariffRateCard> savedCards = new ArrayList<>();
    private final List<TariffRateSlab> savedSlabs = new ArrayList<>();
    private final List<TariffChargeRule> savedCharges = new ArrayList<>();
    private final Map<UUID, TariffZone> zoneById = new HashMap<>();

    private TariffZone pune;
    private TariffZone central;
    private TariffZone kerala;
    private ServiceType air;
    private PackageType doc;
    private PackageType nondoc;

    @BeforeEach
    void setUp() {
        service = new TariffRateCardServiceImpl(cards, slabs, charges, zones, serviceTypeService, packageTypeService, auditService);
        quotes = new TariffQuoteServiceImpl(zoneService, cards, slabs, charges, serviceTypeService, packageTypeService, pincodeService);
        CompanyContext.setCompanyId(COMPANY);

        pune = zone("PUNE", ZoneUsage.BOTH);
        central = zone("CENTRAL", ZoneUsage.TO);
        kerala = zone("KERALA", ZoneUsage.TO);
        air = new ServiceType();
        air.setCode("AIR");
        doc = new PackageType();
        doc.setCode("DOC");
        doc.setDocumentType(true);
        nondoc = new PackageType();
        nondoc.setCode("NONDOC");
        when(serviceTypeService.getById(any())).thenAnswer(i -> {
            if (air.getId().equals(i.getArgument(0))) return air;
            throw new ResourceNotFoundException("ServiceType", i.getArgument(0));
        });
        when(packageTypeService.getById(any())).thenAnswer(i -> {
            UUID id = i.getArgument(0);
            if (doc.getId().equals(id)) return doc;
            if (nondoc.getId().equals(id)) return nondoc;
            throw new ResourceNotFoundException("PackageType", id);
        });

        when(zones.findByIdWithinCompany(any(), eq(COMPANY))).thenAnswer(i -> Optional.ofNullable(zoneById.get(i.<UUID>getArgument(0))));
        when(zones.findAllWithinCompany(any(), eq(COMPANY))).thenAnswer(i -> i.<Collection<UUID>>getArgument(0).stream()
                .map(zoneById::get).filter(java.util.Objects::nonNull).toList());

        when(cards.save(any(TariffRateCard.class))).thenAnswer(i -> keep(savedCards, i.getArgument(0)));
        when(cards.findByIdWithinCompany(any(), eq(COMPANY))).thenAnswer(i -> live(savedCards).stream()
                .filter(c -> c.getId().equals(i.getArgument(0))).findFirst());
        when(cards.existsByCode(eq(COMPANY), any(), any())).thenAnswer(i -> live(savedCards).stream()
                .anyMatch(c -> c.getCode().equals(i.getArgument(1)) && !c.getId().equals(i.getArgument(2))));
        when(cards.findByFromZone(eq(COMPANY), any(), any())).thenAnswer(i -> live(savedCards).stream()
                .filter(c -> c.getFromZoneId().equals(i.getArgument(1)) && c.getStatus() == i.getArgument(2)).toList());

        when(slabs.save(any(TariffRateSlab.class))).thenAnswer(i -> keep(savedSlabs, i.getArgument(0)));
        when(slabs.findByCard(eq(COMPANY), any())).thenAnswer(i -> live(savedSlabs).stream()
                .filter(s -> s.getRateCardId().equals(i.getArgument(1))).toList());
        when(slabs.findLane(eq(COMPANY), any(), any(), any(), any())).thenAnswer(i -> live(savedSlabs).stream()
                .filter(s -> s.getRateCardId().equals(i.getArgument(1)) && s.getToZoneId().equals(i.getArgument(2))
                        && s.getServiceTypeId().equals(i.getArgument(3)) && s.getPackageTypeId().equals(i.getArgument(4)))
                .sorted(java.util.Comparator.comparing(TariffRateSlab::getWeightFromKg)).toList());

        when(charges.save(any(TariffChargeRule.class))).thenAnswer(i -> keep(savedCharges, i.getArgument(0)));
        when(charges.findByCard(eq(COMPANY), any())).thenAnswer(i -> live(savedCharges).stream()
                .filter(r -> r.getRateCardId().equals(i.getArgument(1))).toList());
        when(charges.findByIdWithinCompany(any(), eq(COMPANY))).thenAnswer(i -> live(savedCharges).stream()
                .filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    private TariffZone zone(String code, ZoneUsage usage) {
        TariffZone z = TariffZone.builder().code(code).name(code).zoneUsage(usage).build();
        z.setCompanyId(COMPANY);
        zoneById.put(z.getId(), z);
        return z;
    }

    private static <T extends com.courier.shared.domain.CompanyOwnedEntity> T keep(List<T> list, T entity) {
        if (list.stream().noneMatch(x -> x == entity)) {
            entity.setCompanyId(COMPANY);
            list.add(entity);
        }
        return entity;
    }

    private static <T extends com.courier.shared.domain.CompanyOwnedEntity> List<T> live(List<T> list) {
        return list.stream().filter(x -> !x.isDeleted()).toList();
    }

    private CardInput cardInput(String code, TariffZone from, LocalDate start, LocalDate end) {
        return new CardInput(code, "Pune cash", from.getId(), start, end, null, null, null, null);
    }

    private LaneInput airDocToCentral(String perKg) {
        return new LaneInput(central.getId(), air.getId(), doc.getId(), List.of(
                new SlabInput(new BigDecimal("0.001"), new BigDecimal("0.5"), SlabRateType.FIXED, new BigDecimal("200"), null),
                new SlabInput(new BigDecimal("0.501"), BigDecimal.ONE, SlabRateType.FIXED, new BigDecimal("200"), null),
                new SlabInput(new BigDecimal("1.001"), new BigDecimal("99"), SlabRateType.PER_EXTRA_KG, new BigDecimal(perKg), null)));
    }

    private ChargeInput fuel(String percent, UUID serviceTypeId) {
        return new ChargeInput("fuel", "Fuel surcharge", null, serviceTypeId, null, null, ChargeCalcMethod.PERCENT_OF_FREIGHT,
                new BigDecimal(percent), null, null, null, null, null);
    }

    private RateCardDetail activeCard() {
        RateCardDetail card = service.create(cardInput("PUNE_CASH", pune, LocalDate.of(2025, 4, 1), null));
        service.saveLane(card.card().getId(), airDocToCentral("200"));
        return service.activate(card.card().getId());
    }

    @Test
    @DisplayName("a new card starts inactive with GST 18% and rupee rounding")
    void create() {
        RateCardDetail d = service.create(cardInput("pune cash", pune, LocalDate.of(2025, 4, 1), null));

        assertThat(d.card().getCode()).isEqualTo("PUNE_CASH");
        assertThat(d.card().getStatus()).isEqualTo(TariffStatus.INACTIVE);
        assertThat(d.card().getGstPercent()).isEqualByComparingTo("18");
        assertThat(d.card().getTotalRounding()).isEqualTo(TotalRounding.NEAREST_RUPEE);
        assertThat(d.labels()).containsEntry(pune.getId(), "PUNE");
    }

    @Test
    @DisplayName("a card's from zone must be a FROM or BOTH zone, and codes are unique")
    void createRefused() {
        assertThatThrownBy(() -> service.create(cardInput("X1", central, LocalDate.of(2025, 4, 1), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("to zone only");
        service.create(cardInput("PUNE_CASH", pune, LocalDate.of(2025, 4, 1), null));
        assertThatThrownBy(() -> service.create(cardInput("PUNE_CASH", pune, LocalDate.of(2027, 4, 1), null)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("saving a lane replaces all its slabs")
    void saveLaneReplaces() {
        RateCardDetail card = service.create(cardInput("PUNE_CASH", pune, LocalDate.of(2025, 4, 1), null));
        service.saveLane(card.card().getId(), airDocToCentral("200"));
        RateCardDetail d = service.saveLane(card.card().getId(), airDocToCentral("180"));

        assertThat(d.slabs()).hasSize(3);
        assertThat(d.slabs().get(2).getRate()).isEqualByComparingTo("180");
        assertThat(d.labels()).containsEntry(central.getId(), "CENTRAL").containsEntry(air.getId(), "AIR").containsEntry(doc.getId(), "DOC");
    }

    @Test
    @DisplayName("a lane to a FROM-only zone, or for an unknown service type, is refused")
    void laneRefused() {
        TariffZone origin = zone("PUNE_ORIGIN", ZoneUsage.FROM);
        RateCardDetail card = service.create(cardInput("PUNE_CASH", pune, LocalDate.of(2025, 4, 1), null));
        LaneInput lane = airDocToCentral("200");

        assertThatThrownBy(() -> service.saveLane(card.card().getId(),
                new LaneInput(origin.getId(), lane.serviceTypeId(), lane.packageTypeId(), lane.slabs())))
                .hasMessageContaining("from zone only");
        assertThatThrownBy(() -> service.saveLane(card.card().getId(),
                new LaneInput(central.getId(), UUID.randomUUID(), lane.packageTypeId(), lane.slabs())))
                .hasMessageContaining("No such service type");
        assertThat(savedSlabs).isEmpty();
    }

    @Test
    @DisplayName("activation needs rates and no other active card of the same from zone on the same dates")
    void activation() {
        RateCardDetail empty = service.create(cardInput("EMPTY", pune, LocalDate.of(2025, 4, 1), null));
        assertThatThrownBy(() -> service.activate(empty.card().getId())).hasMessageContaining("Add at least one rate");

        activeCard();
        RateCardDetail next = service.create(cardInput("PUNE_2026", pune, LocalDate.of(2026, 4, 1), null));
        service.saveLane(next.card().getId(), airDocToCentral("220"));
        assertThatThrownBy(() -> service.activate(next.card().getId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("PUNE_CASH is already active");
    }

    @Test
    @DisplayName("two charges with the same code and filters are refused; a narrower one is fine")
    void chargeDuplicates() {
        RateCardDetail card = service.create(cardInput("PUNE_CASH", pune, LocalDate.of(2025, 4, 1), null));
        service.addCharge(card.card().getId(), fuel("10", null));

        assertThatThrownBy(() -> service.addCharge(card.card().getId(), fuel("12", null)))
                .isInstanceOf(DuplicateResourceException.class);
        RateCardDetail d = service.addCharge(card.card().getId(), fuel("15", air.getId()));
        assertThat(d.charges()).extracting(TariffChargeRule::getChargeCode).containsExactly("FUEL", "FUEL");

        UUID first = d.charges().get(0).getId();
        assertThat(service.updateCharge(card.card().getId(), first, fuel("11", null)).charges())
                .extracting(r -> r.getChargeValue().stripTrailingZeros().toPlainString()).contains("11");
        assertThat(service.deleteCharge(card.card().getId(), first).charges()).hasSize(1);
    }

    @Test
    @DisplayName("deleting a card deletes its slabs and charges")
    void deleteCard() {
        RateCardDetail card = activeCard();
        service.addCharge(card.card().getId(), fuel("10", null));

        service.delete(card.card().getId());

        assertThat(live(savedCards)).isEmpty();
        assertThat(live(savedSlabs)).isEmpty();
        assertThat(live(savedCharges)).isEmpty();
    }

    // ------------------------------------------------------------ quote

    private void pincodes() {
        when(zoneService.resolve(eq("411030"), eq(ZoneUsage.FROM))).thenReturn(Optional.of(resolution(pune, MAHARASHTRA)));
        when(zoneService.resolve(eq("462001"), eq(ZoneUsage.TO))).thenReturn(Optional.of(resolution(central, MP)));
        when(zoneService.resolve(eq("682001"), eq(ZoneUsage.TO))).thenReturn(Optional.of(resolution(kerala, UUID.randomUUID())));
        when(pincodeService.location("462001")).thenReturn(Optional.of(new PincodeLocation(UUID.randomUUID(), null, null, null, true, true, false)));
        when(pincodeService.location("682001")).thenReturn(Optional.of(new PincodeLocation(UUID.randomUUID(), null, null, null, true, false, false)));
    }

    private static TariffZoneService.Resolution resolution(TariffZone zone, UUID stateId) {
        TariffZoneView.LocationView place = new TariffZoneView.LocationView(null, com.courier.modules.tariff.domain.LocationScope.PINCODE,
                stateId, "State", null, null, null, null, "000000");
        return new TariffZoneService.Resolution(zone, place, place);
    }

    private TariffQuoteService.QuoteInput quoteInput(String to, String kg, boolean cod) {
        return new TariffQuoteService.QuoteInput("411030", to, air.getId(), doc.getId(), new BigDecimal(kg),
                null, null, null, null, null, cod, LocalDate.of(2025, 6, 1));
    }

    @Test
    @DisplayName("quote: pickup pincode picks the card, delivery pincode the lane; freight + fuel + IGST across states")
    void quote() {
        RateCardDetail card = activeCard();
        service.addCharge(card.card().getId(), fuel("10", null));
        pincodes();

        TariffQuoteService.Quote q = quotes.quote(quoteInput("462001", "2.3", false));

        assertThat(q.card().getCode()).isEqualTo("PUNE_CASH");
        assertThat(q.to().zone().getCode()).isEqualTo("CENTRAL");
        assertThat(q.price().freight().amount()).isEqualByComparingTo("600");
        assertThat(q.price().charges()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("60"));
        assertThat(q.price().taxes()).singleElement().satisfies(t -> assertThat(t.code()).isEqualTo("IGST"));
        assertThat(q.price().total()).isEqualByComparingTo("779");
    }

    @Test
    @DisplayName("quote refusals: no rate for the lane, COD where it is not available, no card on that date")
    void quoteRefused() {
        activeCard();
        pincodes();

        assertThatThrownBy(() -> quotes.quote(quoteInput("682001", "1", false)))
                .hasMessageContaining("has no AIR DOC rate to zone KERALA");
        assertThatThrownBy(() -> quotes.quote(quoteInput("682001", "1", true)))
                .hasMessageContaining("Cash on delivery is not available");
        assertThatThrownBy(() -> quotes.quote(new TariffQuoteService.QuoteInput("411030", "462001", air.getId(), doc.getId(),
                BigDecimal.ONE, null, null, null, null, null, false, LocalDate.of(2024, 1, 1))))
                .hasMessageContaining("No active rate card for from zone PUNE on 2024-01-01");
    }
}
