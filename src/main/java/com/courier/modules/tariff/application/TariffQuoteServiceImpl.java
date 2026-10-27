package com.courier.modules.tariff.application;

import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.PincodeLocation;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.ServiceType;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Quote quote(QuoteInput in) {
        UUID companyId = CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Rate cards belong to a company."));
        if (in.serviceTypeId() == null || in.packageTypeId() == null) {
            throw new BusinessRuleException("Choose the service type and the package type.");
        }
        ServiceType serviceType = lookup(in.serviceTypeId(), serviceTypeService::getById, "service type");
        PackageType packageType = lookup(in.packageTypeId(), packageTypeService::getById, "package type");

        TariffZoneService.Resolution from = zoneService.resolve(in.originPincode(), ZoneUsage.FROM)
                .orElseThrow(() -> new BusinessRuleException("No from zone covers pickup pincode " + in.originPincode().trim()
                        + ". Add it (or its city, district or state) to a from zone."));
        TariffZoneService.Resolution to = zoneService.resolve(in.destinationPincode(), ZoneUsage.TO)
                .orElseThrow(() -> new BusinessRuleException("No to zone covers delivery pincode " + in.destinationPincode().trim()
                        + ". Add it (or its city, district or state) to a to zone."));
        PincodeLocation destination = pincodeService.location(in.destinationPincode().trim()).orElseThrow();
        if (!destination.serviceable()) {
            throw new BusinessRuleException("Delivery pincode " + in.destinationPincode().trim() + " is not serviceable.");
        }
        if (in.cod() && !destination.codAvailable()) {
            throw new BusinessRuleException("Cash on delivery is not available at pincode " + in.destinationPincode().trim() + ".");
        }

        LocalDate date = in.bookingDate() == null ? LocalDate.now(INDIA) : in.bookingDate();
        TariffRateCard card = cards.findByFromZone(companyId, from.zone().getId(), TariffStatus.ACTIVE).stream()
                .filter(c -> c.covers(date))
                .max(Comparator.comparing(TariffRateCard::getEffectiveFrom))
                .orElseThrow(() -> new BusinessRuleException("No active rate card for from zone %s on %s."
                        .formatted(from.zone().getCode(), date)));
        List<TariffRateSlab> lane = slabs.findLane(companyId, card.getId(), to.zone().getId(), serviceType.getId(), packageType.getId());
        if (lane.isEmpty()) {
            throw new BusinessRuleException("Rate card %s has no %s %s rate to zone %s."
                    .formatted(card.getCode(), serviceType.getCode(), packageType.getCode(), to.zone().getCode()));
        }

        UUID fromState = from.place().stateId();
        UUID toState = to.place().stateId();
        TariffPriceCalculator.Shipment shipment = new TariffPriceCalculator.Shipment(in.actualWeightKg(), in.lengthCm(),
                in.widthCm(), in.heightCm(), in.pieces() == null ? 1 : in.pieces(), in.declaredValue(), in.cod(),
                destination.odaApplicable(), packageType.isDocumentType(), to.zone().getId(), serviceType.getId(),
                packageType.getId(), fromState != null && Objects.equals(fromState, toState));
        TariffPriceCalculator.Price price = TariffPriceCalculator.price(card, lane, charges.findByCard(companyId, card.getId()), shipment);
        return new Quote(card, from, to, serviceType.getCode(), packageType.getCode(), destination.odaApplicable(), price);
    }

    private static <T> T lookup(UUID id, Function<UUID, T> fn, String label) {
        try {
            return fn.apply(id);
        } catch (ResourceNotFoundException e) {
            throw new BusinessRuleException("No such " + label + ": " + id);
        }
    }
}
