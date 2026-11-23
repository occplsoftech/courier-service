package com.courier.modules.pricing.application;

import com.courier.modules.company.application.CompanySettingsService;
import com.courier.modules.company.domain.CompanySettings;
import com.courier.modules.freight.application.FreightCalculationResult;
import com.courier.modules.freight.application.FreightFactorService;
import com.courier.modules.freight.application.command.FreightCalculationCommand;
import com.courier.modules.master.domain.Route;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.modules.pricing.application.calculator.ApplicableChargesCalculator;
import com.courier.modules.pricing.application.command.PricingCommand;
import com.courier.modules.pricing.application.factory.PricingFactory;
import com.courier.modules.pricing.application.strategy.PricingStrategy;
import com.courier.modules.pricing.application.validation.BookingValidation;
import com.courier.modules.pricing.application.validation.RateValidation;
import com.courier.modules.pricing.application.validation.RouteValidation;
import com.courier.modules.pricing.application.validation.WeightValidation;
import com.courier.modules.pricing.domain.ChargeableWeightCalculator;
import com.courier.modules.pricing.domain.PricingConfiguration;
import com.courier.modules.pricing.domain.RoundingRule;
import com.courier.modules.pricing.domain.VolumetricCalculator;
import com.courier.modules.pricing.domain.WeightCalculator;
import com.courier.modules.rate.domain.Rate;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.RouteRateUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Orchestrates the module's flow: Validate Route -> Validate Serviceability -> Validate
 * Rate -> Calculate Volumetric Weight -> Calculate Chargeable Weight -> Execute Charge
 * Calculators -> Return Charge Breakup. Every step is delegated: this class sequences,
 * it does not itself decide a route is inactive or compute a surcharge.
 *
 * <p>{@code isAuthenticated()} — the same read tier {@code RateServiceImpl.calculate} uses.
 * A caller within a request thread (the REST endpoint, or a future Shipment/Quotation
 * module calling this bean in-process) already carries the {@code SecurityContext} and
 * {@code CompanyContext} that {@link RouteValidation}, {@link RateValidation} and
 * {@link BookingValidation} need from the modules they call into — this class binds
 * neither itself, it is stateless.
 */
@Service
@RequiredArgsConstructor
public class PricingEngineImpl implements PricingEngine {

    private final RouteValidation routeValidation;
    private final BookingValidation bookingValidation;
    private final RateValidation rateValidation;
    private final WeightValidation weightValidation;
    private final PricingFactory pricingFactory;
    private final PricingProperties properties;
    private final FreightFactorService freightFactorService;
    private final CompanySettingsService companySettingsService;
    private final ApplicableChargesCalculator applicableChargesCalculator;
    private final ServiceTypeRepository serviceTypeRepository;

    @Override
    @PreAuthorize("isAuthenticated()")
    public PricingResult calculate(PricingCommand command) {
        weightValidation.validate(command);

        PricingConfiguration configuration = properties.toConfiguration();
        BigDecimal actualWeight = WeightCalculator.normalise(command.actualWeight());
        BigDecimal volumetricWeight = VolumetricCalculator.calculate(
                command.length(), command.width(), command.height(),
                VolumetricCalculator.divisor(serviceTypeDivisor(command.serviceTypeId()),
                        configuration.volumetricDivisor()));
        try {
            Route route = routeValidation.validate(command);
            LocalDate bookingDate = bookingValidation.validate(command);
            List<Rate> candidates = rateValidation.validate(route.getId(), command, bookingDate);

            // CompanySettings (round-off override) is resolved only on the priced path.
            // Chargeable weight is the higher of actual and volumetric: no company minimum.
            CompanySettings settings = companySettingsService.get();
            BigDecimal chargeableWeight = ChargeableWeightCalculator.calculate(actualWeight, volumetricWeight);
            PricingContext context = new PricingContext(
                    command, configuration.withRoundingRule(resolveRoundingRule(settings)));
            context.matchedRoute(route);
            context.candidates(candidates);
            context.bookingDate(bookingDate);
            context.actualWeight(actualWeight);
            context.volumetricWeight(volumetricWeight);
            context.chargeableWeight(chargeableWeight);
            context.gstPercentage(settings.getGstPercentage());

            PricingStrategy strategy = pricingFactory.resolve(context);
            return strategy.price(context);
        } catch (RouteRateUnavailableException noRouteRate) {
            return priceByDistanceAndWeight(command, actualWeight, volumetricWeight);
        }
    }

    /**
     * Fallback when there's no route, an inactive route, no active rate, or no weight
     * slab for this lane at all: the company-level Freight Factor grid ({@code freight =
     * matchedFactor * weight}, resolved via the booking/delivery branch pair's road
     * distance) — every caller of this engine (Shipment Booking, the frontend's own live
     * pricing preview, any future Quotation/mobile consumer) gets it for free, not just
     * whichever one remembers to catch {@link RouteRateUnavailableException} itself. No
     * fuel/handling/ODA/insurance/discount — those are Rate-card-driven percentages with
     * no other source once there is no matched {@link Rate} — Freight Factor deliberately
     * carries none of those. Round-off and Applicable Charges are the two exceptions.
     * Round-off is a company-level preference ({@code CompanySettings.roundOffRule}), not
     * Rate-card-driven, so it still applies here the same way GST does (see {@code
     * resolveRoundingRule}). {@link ApplicableChargesCalculator} is keyed on the {@code
     * charge} module's own
     * weight/distance-slab configuration, not a matched Route/Rate at all (see its own
     * class doc), so it still runs here — a company's configured Hamali/fuel-surcharge
     * charges must not silently vanish just because this lane has no Rate Master entry,
     * which is the common case once Shipment Booking stopped requiring an explicit
     * Delivery Branch pick. GST is still statutory here too: applied on top of freight +
     * Applicable Charges at the company's own {@code CompanySettings.gstPercentage}, since
     * there is no matched {@link Rate} to read a per-lane percentage off of. A gap in the
     * Freight Factor grid itself, or an unresolvable branch-pair distance, still fails the
     * call with its own exception — there is no third fallback tier.
     *
     * <p>{@code command.freightFactorOverride()} lets a caller raise the matched cell's own
     * factor before freight is computed — never lower it. A smaller override is refused
     * outright (a desk can charge more than the grid says, never less); a null override
     * simply prices at the matched factor, unchanged from before this existed.
     *
     * <p>A gap in the grid — no cell covers this lane's distance/weight — no longer fails
     * the call: it prices at freight zero (still GSTed, still net-zero on top) instead of
     * throwing, so a caller with its own way to price the lane (District Level Freight, a
     * manual Other Charges entry) is not blocked by this grid alone being incomplete. An
     * unresolvable branch-pair distance (a branch with no geocoded lat/long) gets the same
     * treatment — freight zero, not a thrown error — on direct request: this grid is a
     * fallback for booking, not the authoritative freight source (District Level Freight
     * is), so a stale geocode on either branch must never block a real booking. See
     * {@code FreightFactorServiceImpl.tryCalculate}.
     */
    /**
     * The final net amount's round-off is company-configurable ({@code
     * CompanySettings.roundOffRule}), overriding the deployment-wide {@code
     * PricingProperties.roundingRule} default when the company has set one. Stored as a
     * plain string on {@code CompanySettings} (so that module stays free of a dependency
     * on this one) — resolved to the real enum here, falling back to the deployment
     * default on a blank or since-renamed value rather than failing the whole booking.
     */
    private RoundingRule resolveRoundingRule(CompanySettings settings) {
        String stored = settings.getRoundOffRule();
        if (stored == null || stored.isBlank()) {
            return properties.getRoundingRule();
        }
        try {
            return RoundingRule.valueOf(stored);
        } catch (IllegalArgumentException invalid) {
            return properties.getRoundingRule();
        }
    }

    private PricingResult priceByDistanceAndWeight(PricingCommand command, BigDecimal actualWeight,
                                                    BigDecimal volumetricWeight) {
        // Fetched once, up front, for GST/round-off further down.
        var settings = companySettingsService.get();
        BigDecimal chargeableWeight = ChargeableWeightCalculator.calculate(actualWeight, volumetricWeight);
        var matchOutcome = freightFactorService.tryCalculate(new FreightCalculationCommand(
                command.bookingBranchId(), command.deliveryBranchId(), chargeableWeight));
        BigDecimal matchedFactor = matchOutcome.map(r -> r.matchedFactor().getFactor()).orElse(null);
        BigDecimal effectiveFactor = matchedFactor;
        BigDecimal chargesSubtotal = matchOutcome.map(FreightCalculationResult::freight).orElse(BigDecimal.ZERO);
        if (command.freightFactorOverride() != null) {
            if (matchedFactor != null && command.freightFactorOverride().compareTo(matchedFactor) < 0) {
                throw new BusinessRuleException(
                        "Freight factor can only be increased — matched %s, requested %s."
                                .formatted(matchedFactor.toPlainString(),
                                        command.freightFactorOverride().toPlainString()));
            }
            effectiveFactor = command.freightFactorOverride();
            chargesSubtotal = effectiveFactor.multiply(chargeableWeight).setScale(2, RoundingMode.HALF_UP);
        }
        List<ApplicableChargesCalculator.Line> applicableChargeLines =
                applicableChargeLines(command, chargeableWeight, chargesSubtotal);
        BigDecimal applicableCharges = applicableChargeLines.stream()
                .map(ApplicableChargesCalculator.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxableSubtotal = chargesSubtotal.add(applicableCharges);
        BigDecimal gstAmount = taxableSubtotal.multiply(settings.getGstPercentage())
                .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
        BigDecimal preRoundNetAmount = taxableSubtotal.add(gstAmount);
        BigDecimal netAmount = resolveRoundingRule(settings).apply(preRoundNetAmount);
        BigDecimal roundOff = netAmount.subtract(preRoundNetAmount);
        return new PricingResult(null, null, actualWeight, volumetricWeight, chargeableWeight,
                chargesSubtotal, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                applicableCharges, applicableChargeLines, gstAmount, BigDecimal.ZERO, roundOff,
                netAmount, effectiveFactor);
    }

    /** {@link ApplicableChargesCalculator} needs only service type, booking/delivery
     *  branch, chargeable weight and freight (for a PERCENTAGE-type charge) — none of
     *  which require a matched Route/Rate, so it runs unchanged from the standard chain
     *  even on this fallback path. See the class doc above. */
    private List<ApplicableChargesCalculator.Line> applicableChargeLines(PricingCommand command,
                                                                         BigDecimal chargeableWeight,
                                                                         BigDecimal freight) {
        return applicableChargesCalculator.resolve(command.serviceTypeId(), chargeableWeight,
                command.totalActualWeight(), command.numberOfPackages(),
                command.bookingBranchId(), command.deliveryBranchId(), freight);
    }

    /** The service type's own volumetric divisor, or null to use the deployment default. */
    private Integer serviceTypeDivisor(java.util.UUID serviceTypeId) {
        if (serviceTypeId == null) {
            return null;
        }
        return CompanyContext.getCompanyId()
                .flatMap(companyId -> serviceTypeRepository.findByIdWithinCompany(serviceTypeId, companyId))
                .map(ServiceType::getVolumetricDivisor).orElse(null);
    }
}
