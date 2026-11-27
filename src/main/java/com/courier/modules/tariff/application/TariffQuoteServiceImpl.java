package com.courier.modules.tariff.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.PincodeLocation;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.modules.master.domain.MasterStatus;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.ServiceNetwork;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.pricing.application.PricingProperties;
import com.courier.modules.pricing.domain.VolumetricCalculator;
import com.courier.modules.tariff.domain.TariffChargeRule;
import com.courier.modules.tariff.domain.TariffChargeRuleRepository;
import com.courier.modules.tariff.domain.TariffPriceCalculator;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffRateCardRepository;
import com.courier.modules.tariff.domain.TariffRateSlab;
import com.courier.modules.tariff.domain.TariffRateSlabRepository;
import com.courier.modules.tariff.domain.TariffStatus;
import com.courier.modules.tariff.domain.ZoneUsage;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Zone tariff quotes. Read-only: nothing is saved, so the booking screen can call it on every change. */
@Service
@RequiredArgsConstructor
public class TariffQuoteServiceImpl implements TariffQuoteService {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final TariffZoneService zoneService;
    private final TariffRateCardRepository cards;
    private final TariffRateSlabRepository slabs;
    private final TariffChargeRuleRepository charges;
    private final ServiceTypeService serviceTypeService;
    private final PackageTypeService packageTypeService;
    private final PincodeService pincodeService;
    private final PricingProperties pricingProperties;
    private CustomerPortalLookup customerLookup;

    @Autowired(required = false)
    void setCustomerLookup(CustomerPortalLookup customerLookup) {
        this.customerLookup = customerLookup;
    }

    /**
     * Where shipments start on the date and the cards that price them: the business customer's own card ({@code
     * customerCard}, may be null) first, lane by lane, then the company's ({@code card}, may be null). At least one is set.
     */
    private record Origin(UUID companyId, TariffZoneService.Resolution from, TariffRateCard card, TariffRateCard customerCard) {
    }

    /** Where the shipment goes, once a card applies. */
    private record Destination(TariffZoneService.Resolution to, PincodeLocation location) {
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Quote quote(QuoteInput in) {
        requireTypes(in);
        return priceLane(requireOrigin(in), destination(in), in);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Optional<Quote> quoteIfApplies(QuoteInput in) {
        requireTypes(in);
        return origin(in).map(origin -> priceLane(origin, destination(in), in));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<Quote> quoteOptions(QuoteInput in, boolean documents) {
        return origin(in).map(origin -> priceAllLanes(origin, destination(in), in, documents).quotes()).orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Options quoteAll(QuoteInput in) {
        if (in.actualWeightKg() == null || in.actualWeightKg().signum() <= 0) {
            throw new BusinessRuleException("Enter the weight in kg, above 0.");
        }
        return priceAllLanes(requireOrigin(in), destination(in), in, null);
    }

    /**
     * Every lane to the destination's zone, the customer's card first: a service and package type the customer's card has
     * is priced from it, the rest from the company's card. {@code documents} null takes documents and parcels alike.
     */
    private Options priceAllLanes(Origin origin, Destination destination, QuoteInput in, Boolean documents) {
        Map<String, List<TariffRateSlab>> lanes = new LinkedHashMap<>();
        Map<String, TariffRateCard> laneCard = new LinkedHashMap<>();
        for (TariffRateCard card : cardsInOrder(origin)) {
            Map<String, List<TariffRateSlab>> ofCard = new LinkedHashMap<>();
            slabs.findByCard(origin.companyId(), card.getId()).stream()
                    .filter(s -> s.getToZoneId().equals(destination.to().zone().getId()))
                    .forEach(s -> ofCard.computeIfAbsent(s.getServiceTypeId() + ":" + s.getPackageTypeId(), k -> new ArrayList<>()).add(s));
            ofCard.forEach((k, lane) -> {
                if (lanes.putIfAbsent(k, lane) == null) {
                    laneCard.put(k, card);
                }
            });
        }
        Map<UUID, List<TariffChargeRule>> rulesByCard = new HashMap<>();
        List<Quote> quotes = new ArrayList<>();
        List<Unavailable> unavailable = new ArrayList<>();
        for (Map.Entry<String, List<TariffRateSlab>> entry : lanes.entrySet()) {
            List<TariffRateSlab> lane = entry.getValue();
            TariffRateCard card = laneCard.get(entry.getKey());
            List<TariffChargeRule> rules = rulesByCard.computeIfAbsent(card.getId(), id -> charges.findByCard(origin.companyId(), id));
            ServiceType serviceType = tryGet(lane.get(0).getServiceTypeId(), serviceTypeService::getById);
            PackageType packageType = tryGet(lane.get(0).getPackageTypeId(), packageTypeService::getById);
            if (serviceType == null || packageType == null
                    || (documents != null && packageType.isDocumentType() != documents)) {
                continue;
            }
            try {
                quotes.add(price(origin, card, destination, in, serviceType, packageType, lane, rules));
            } catch (BusinessRuleException tooHeavy) {
                // This lane has no slab for the weight; the others may.
                unavailable.add(new Unavailable(serviceType.getCode(), serviceType.getName(), packageType.getCode(), tooHeavy.getMessage()));
            }
        }
        quotes.sort(Comparator.comparing(q -> q.price().total()));
        return new Options(quotes, unavailable);
    }

    private Optional<Origin> origin(QuoteInput in) {
        UUID companyId = requireCompany();
        if (in.originPincode() == null || in.originPincode().isBlank()) {
            return Optional.empty();
        }
        return zoneService.resolve(in.originPincode(), ZoneUsage.FROM).flatMap(from -> originAt(companyId, from, in));
    }

    /** Like {@link #origin} but says why there is no card. */
    private Origin requireOrigin(QuoteInput in) {
        UUID companyId = requireCompany();
        TariffZoneService.Resolution from = zoneService.resolve(in.originPincode(), ZoneUsage.FROM)
                .orElseThrow(() -> new BusinessRuleException("No from zone covers pickup pincode " + in.originPincode().trim()
                        + ". Add it (or its city, district or state) to a from zone."));
        return originAt(companyId, from, in).orElseThrow(() -> new BusinessRuleException("No active rate card for from zone %s on %s."
                .formatted(from.zone().getCode(), dateOf(in))));
    }

    private Optional<Origin> originAt(UUID companyId, TariffZoneService.Resolution from, QuoteInput in) {
        LocalDate date = dateOf(in);
        TariffRateCard company = activeCard(companyId, from, date).orElse(null);
        TariffRateCard customer = customerCard(companyId, in.customerId(), from, date).orElse(null);
        return company == null && customer == null ? Optional.empty() : Optional.of(new Origin(companyId, from, company, customer));
    }

    private static List<TariffRateCard> cardsInOrder(Origin origin) {
        List<TariffRateCard> order = new ArrayList<>(2);
        if (origin.customerCard() != null) {
            order.add(origin.customerCard());
        }
        if (origin.card() != null) {
            order.add(origin.card());
        }
        return order;
    }

    private Destination destination(QuoteInput in) {
        TariffZoneService.Resolution to = zoneService.resolve(in.destinationPincode(), ZoneUsage.TO)
                .orElseThrow(() -> new BusinessRuleException("No to zone covers delivery pincode " + in.destinationPincode().trim()
                        + ". Add it (or its city, district or state) to a to zone."));
        PincodeLocation location = pincodeService.location(in.destinationPincode().trim()).orElseThrow();
        if (!location.serviceable()) {
            throw new BusinessRuleException("Delivery pincode " + in.destinationPincode().trim() + " is not serviceable.");
        }
        if (in.cod() && !location.codAvailable()) {
            throw new BusinessRuleException("Cash on delivery is not available at pincode " + in.destinationPincode().trim() + ".");
        }
        return new Destination(to, location);
    }

    private Quote priceLane(Origin origin, Destination destination, QuoteInput in) {
        ServiceType serviceType = lookup(in.serviceTypeId(), serviceTypeService::getById, "service type");
        PackageType packageType = lookup(in.packageTypeId(), packageTypeService::getById, "package type");
        for (TariffRateCard card : cardsInOrder(origin)) {
            List<TariffRateSlab> lane = slabs.findLane(origin.companyId(), card.getId(), destination.to().zone().getId(),
                    serviceType.getId(), packageType.getId());
            if (!lane.isEmpty()) {
                return price(origin, card, destination, in, serviceType, packageType, lane,
                        charges.findByCard(origin.companyId(), card.getId()));
            }
        }
        TariffRateCard shown = origin.card() != null ? origin.card() : origin.customerCard();
        throw new BusinessRuleException("Rate card %s has no %s %s rate to zone %s."
                .formatted(shown.getCode(), serviceType.getCode(), packageType.getCode(), destination.to().zone().getCode()));
    }

    private Quote price(Origin origin, TariffRateCard card, Destination destination, QuoteInput in, ServiceType serviceType,
                        PackageType packageType, List<TariffRateSlab> lane, List<TariffChargeRule> rules) {
        UUID fromState = origin.from().place().stateId();
        UUID toState = destination.to().place().stateId();
        // The pincode master records coverage per network (V171): documents, surface parcels, air parcels.
        ServiceNetwork network = ServiceNetwork.of(packageType.isDocumentType(), serviceType.getNetwork());
        PincodeLocation where = destination.location();
        if (!where.serviceableBy(network)) {
            throw new BusinessRuleException("%s service does not deliver to pincode %s."
                    .formatted(network.label(), in.destinationPincode().trim()));
        }
        boolean oda = where.odaBy(network);
        Integer tatHours = where.coverage(network).tatHours();
        Integer deliveryDays = tatHours != null ? Integer.valueOf((tatHours + 23) / 24) : serviceType.getDeliveryDays();
        TariffPriceCalculator.Shipment shipment = new TariffPriceCalculator.Shipment(in.actualWeightKg(), in.lengthCm(),
                in.widthCm(), in.heightCm(), in.pieces() == null ? 1 : in.pieces(), in.declaredValue(), in.cod(),
                oda, packageType.isDocumentType(), destination.to().zone().getId(), serviceType.getId(),
                packageType.getId(), fromState != null && Objects.equals(fromState, toState),
                serviceDivisor(serviceType)); // used only when the card has no divisor of its own
        TariffPriceCalculator.Price price = TariffPriceCalculator.price(card, lane, rules, shipment);
        return new Quote(card, origin.from(), destination.to(), serviceType.getId(), serviceType.getCode(),
                serviceType.getName(), deliveryDays, packageType.getId(), packageType.getCode(), oda, price, tatHours);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<ServiceDivisor> volumetricDivisors() {
        UUID companyId = requireCompany();
        LocalDate today = LocalDate.now(INDIA);
        // A card's own divisor overrides the service type's for the services it prices (newest card first).
        Map<UUID, Integer> cardDivisors = new HashMap<>();
        cards.search(companyId, null, TariffStatus.ACTIVE, null).stream()
                .filter(c -> c.covers(today) && c.getVolumetricDivisor() != null)
                .sorted(Comparator.comparing(TariffRateCard::getEffectiveFrom).reversed())
                .forEach(card -> slabs.findByCard(companyId, card.getId())
                        .forEach(slab -> cardDivisors.putIfAbsent(slab.getServiceTypeId(), card.getVolumetricDivisor())));
        return serviceTypeService.search(new MasterDataCriteria(null, Set.of(MasterStatus.ACTIVE), null, null, Map.of()),
                        PageRequest.of(0, 500)).getContent().stream()
                .sorted(Comparator.comparing((ServiceType t) -> t.getPriority() == null ? 0 : t.getPriority())
                        .thenComparing(ServiceType::getCode))
                .map(t -> new ServiceDivisor(t.getCode(), t.getName(),
                        cardDivisors.getOrDefault(t.getId(), serviceDivisor(t))))
                .toList();
    }

    /** As bookings weigh it: the service type's divisor, else the deployment default. */
    private int serviceDivisor(ServiceType serviceType) {
        return VolumetricCalculator.divisor(serviceType.getVolumetricDivisor(),
                pricingProperties.toConfiguration().volumetricDivisor()).intValue();
    }

    private Optional<TariffRateCard> activeCard(UUID companyId, TariffZoneService.Resolution from, LocalDate date) {
        return cards.findByFromZone(companyId, from.zone().getId(), TariffStatus.ACTIVE).stream()
                .filter(c -> c.covers(date))
                .max(Comparator.comparing(TariffRateCard::getEffectiveFrom));
    }

    /** The customer's own active card for the from zone on the date, only while they use business rates. */
    private Optional<TariffRateCard> customerCard(UUID companyId, UUID customerId, TariffZoneService.Resolution from, LocalDate date) {
        if (customerId == null || customerLookup == null || !customerLookup.usesBusinessRates(companyId, customerId)) {
            return Optional.empty();
        }
        return cards.findByCustomer(companyId, customerId, from.zone().getId(), TariffStatus.ACTIVE).stream()
                .filter(c -> c.covers(date))
                .max(Comparator.comparing(TariffRateCard::getEffectiveFrom));
    }

    private static LocalDate dateOf(QuoteInput in) {
        return in.bookingDate() == null ? LocalDate.now(INDIA) : in.bookingDate();
    }

    private static void requireTypes(QuoteInput in) {
        if (in.serviceTypeId() == null || in.packageTypeId() == null) {
            throw new BusinessRuleException("Choose the service type and the package type.");
        }
    }

    private static UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Rate cards belong to a company."));
    }

    private static <T> T tryGet(UUID id, Function<UUID, T> fn) {
        try {
            return fn.apply(id);
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    private static <T> T lookup(UUID id, Function<UUID, T> fn, String label) {
        try {
            return fn.apply(id);
        } catch (ResourceNotFoundException e) {
            throw new BusinessRuleException("No such " + label + ": " + id);
        }
    }
}
