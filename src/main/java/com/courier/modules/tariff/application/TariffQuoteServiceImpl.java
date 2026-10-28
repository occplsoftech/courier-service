package com.courier.modules.tariff.application;

import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.PincodeLocation;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.ServiceType;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /** The card that prices shipments from the pickup pincode on the date, with where they start. */
    private record Origin(UUID companyId, TariffZoneService.Resolution from, TariffRateCard card) {
    }

    /** Where the shipment goes, once a card applies. */
    private record Destination(TariffZoneService.Resolution to, PincodeLocation location) {
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Quote quote(QuoteInput in) {
        UUID companyId = requireCompany();
        requireTypes(in);
        TariffZoneService.Resolution from = zoneService.resolve(in.originPincode(), ZoneUsage.FROM)
                .orElseThrow(() -> new BusinessRuleException("No from zone covers pickup pincode " + in.originPincode().trim()
                        + ". Add it (or its city, district or state) to a from zone."));
        LocalDate date = dateOf(in);
        TariffRateCard card = activeCard(companyId, from, date)
                .orElseThrow(() -> new BusinessRuleException("No active rate card for from zone %s on %s."
                        .formatted(from.zone().getCode(), date)));
        return priceLane(new Origin(companyId, from, card), destination(in), in);
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
        UUID companyId = requireCompany();
        if (in.actualWeightKg() == null || in.actualWeightKg().signum() <= 0) {
            throw new BusinessRuleException("Enter the weight in kg, above 0.");
        }
        TariffZoneService.Resolution from = zoneService.resolve(in.originPincode(), ZoneUsage.FROM)
                .orElseThrow(() -> new BusinessRuleException("No from zone covers pickup pincode " + in.originPincode().trim()
                        + ". Add it (or its city, district or state) to a from zone."));
        LocalDate date = dateOf(in);
        TariffRateCard card = activeCard(companyId, from, date)
                .orElseThrow(() -> new BusinessRuleException("No active rate card for from zone %s on %s."
                        .formatted(from.zone().getCode(), date)));
        return priceAllLanes(new Origin(companyId, from, card), destination(in), in, null);
    }

    /** Every lane of the card to the destination's zone; {@code documents} null takes documents and parcels alike. */
    private Options priceAllLanes(Origin origin, Destination destination, QuoteInput in, Boolean documents) {
        Map<String, List<TariffRateSlab>> lanes = new LinkedHashMap<>();
        slabs.findByCard(origin.companyId(), origin.card().getId()).stream()
                .filter(s -> s.getToZoneId().equals(destination.to().zone().getId()))
                .forEach(s -> lanes.computeIfAbsent(s.getServiceTypeId() + ":" + s.getPackageTypeId(), k -> new ArrayList<>()).add(s));
        List<TariffChargeRule> rules = charges.findByCard(origin.companyId(), origin.card().getId());
        List<Quote> quotes = new ArrayList<>();
        List<Unavailable> unavailable = new ArrayList<>();
        for (List<TariffRateSlab> lane : lanes.values()) {
            ServiceType serviceType = tryGet(lane.get(0).getServiceTypeId(), serviceTypeService::getById);
            PackageType packageType = tryGet(lane.get(0).getPackageTypeId(), packageTypeService::getById);
            if (serviceType == null || packageType == null
                    || (documents != null && packageType.isDocumentType() != documents)) {
                continue;
            }
            try {
                quotes.add(price(origin, destination, in, serviceType, packageType, lane, rules));
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
        Optional<TariffZoneService.Resolution> from = zoneService.resolve(in.originPincode(), ZoneUsage.FROM);
        if (from.isEmpty()) {
            return Optional.empty();
        }
        return activeCard(companyId, from.get(), dateOf(in)).map(card -> new Origin(companyId, from.get(), card));
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
        TariffRateCard card = origin.card();
        List<TariffRateSlab> lane = slabs.findLane(origin.companyId(), card.getId(), destination.to().zone().getId(),
                serviceType.getId(), packageType.getId());
        if (lane.isEmpty()) {
            throw new BusinessRuleException("Rate card %s has no %s %s rate to zone %s."
                    .formatted(card.getCode(), serviceType.getCode(), packageType.getCode(), destination.to().zone().getCode()));
        }
        return price(origin, destination, in, serviceType, packageType, lane, charges.findByCard(origin.companyId(), card.getId()));
    }

    private Quote price(Origin origin, Destination destination, QuoteInput in, ServiceType serviceType,
                        PackageType packageType, List<TariffRateSlab> lane, List<TariffChargeRule> rules) {
        UUID fromState = origin.from().place().stateId();
        UUID toState = destination.to().place().stateId();
        boolean oda = destination.location().odaApplicable();
        TariffPriceCalculator.Shipment shipment = new TariffPriceCalculator.Shipment(in.actualWeightKg(), in.lengthCm(),
                in.widthCm(), in.heightCm(), in.pieces() == null ? 1 : in.pieces(), in.declaredValue(), in.cod(),
                oda, packageType.isDocumentType(), destination.to().zone().getId(), serviceType.getId(),
                packageType.getId(), fromState != null && Objects.equals(fromState, toState));
        TariffPriceCalculator.Price price = TariffPriceCalculator.price(origin.card(), lane, rules, shipment);
        return new Quote(origin.card(), origin.from(), destination.to(), serviceType.getId(), serviceType.getCode(),
                serviceType.getName(), serviceType.getDeliveryDays(), packageType.getId(), packageType.getCode(), oda, price);
    }

    private Optional<TariffRateCard> activeCard(UUID companyId, TariffZoneService.Resolution from, LocalDate date) {
        return cards.findByFromZone(companyId, from.zone().getId(), TariffStatus.ACTIVE).stream()
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
