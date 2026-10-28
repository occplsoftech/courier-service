package com.courier.modules.tariff.api;

import com.courier.modules.tariff.api.TariffRateCardDtos.CardRequest;
import com.courier.modules.tariff.api.TariffRateCardDtos.CardResponse;
import com.courier.modules.tariff.api.TariffRateCardDtos.ChargeLineResponse;
import com.courier.modules.tariff.api.TariffRateCardDtos.ChargeRequest;
import com.courier.modules.tariff.api.TariffRateCardDtos.ChargeResponse;
import com.courier.modules.tariff.api.TariffRateCardDtos.LaneRequest;
import com.courier.modules.tariff.api.TariffRateCardDtos.LaneResponse;
import com.courier.modules.tariff.api.TariffRateCardDtos.OptionsRequest;
import com.courier.modules.tariff.api.TariffRateCardDtos.OptionsResponse;
import com.courier.modules.tariff.api.TariffRateCardDtos.QuoteRequest;
import com.courier.modules.tariff.api.TariffRateCardDtos.QuoteResponse;
import com.courier.modules.tariff.api.TariffRateCardDtos.SlabResponse;
import com.courier.modules.tariff.api.TariffRateCardDtos.TaxLineResponse;
import com.courier.modules.tariff.api.TariffRateCardDtos.UnavailableResponse;
import com.courier.modules.tariff.api.TariffRateCardDtos.ZoneRef;
import com.courier.modules.tariff.application.TariffQuoteService;
import com.courier.modules.tariff.application.TariffQuoteService.QuoteInput;
import com.courier.modules.tariff.application.TariffRateCardService;
import com.courier.modules.tariff.application.TariffRateCardService.CardInput;
import com.courier.modules.tariff.application.TariffRateCardService.ChargeInput;
import com.courier.modules.tariff.application.TariffRateCardService.LaneInput;
import com.courier.modules.tariff.application.TariffRateCardService.RateCardDetail;
import com.courier.modules.tariff.application.TariffRateCardService.SlabInput;
import com.courier.modules.tariff.application.TariffZoneService;
import com.courier.modules.tariff.domain.TariffChargeRule;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffRateSlab;
import com.courier.modules.tariff.domain.TariffStatus;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Zone tariff rate cards, their lanes and charges, and quotes. Writes are {@code COMPANY_ADMIN} (on the service). */
@RestController
@RequestMapping("/api/v1/tariff-rate-cards")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Tariff Rate Cards", description = "Rates from a from zone by to zone, service type, package type and weight; "
        + "fuel, hamali and other charges; GST; quotes")
public class TariffRateCardController {

    private final TariffRateCardService service;
    private final TariffQuoteService quoteService;

    @PostMapping
    @Operation(summary = "Create a rate card", description = "Starts INACTIVE: add its rates, then activate it.")
    public ResponseEntity<ApiResponse<CardResponse>> create(@Valid @RequestBody CardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toResponse(service.create(input(request))), "Rate card created"));
    }

    @GetMapping
    @Operation(summary = "List rate cards", description = "Optionally by from zone, status or a code/name search. "
            + "Lanes and charges are counted, not listed.")
    public ApiResponse<List<CardResponse>> list(@RequestParam(required = false) UUID fromZoneId,
                                                @RequestParam(required = false) TariffStatus status,
                                                @RequestParam(required = false) String search) {
        return ApiResponse.success(service.search(fromZoneId, status, search).stream()
                .map(s -> card(s.card(), s.fromZoneCode(), s.laneCount(), s.chargeCount(), List.of(), List.of()))
                .toList());
    }

    @PostMapping("/quote")
    @Operation(summary = "Price a shipment", description = "The pickup pincode's from zone picks the active rate card "
            + "for the booking date (today if empty); the delivery pincode's to zone picks the rate. Nothing is saved.")
    public ApiResponse<QuoteResponse> quote(@Valid @RequestBody QuoteRequest r) {
        return ApiResponse.success(quoteResponse(quoteService.quote(new QuoteInput(r.originPincode(), r.destinationPincode(),
                r.serviceTypeId(), r.packageTypeId(), r.actualWeightKg(), r.lengthCm(), r.widthCm(), r.heightCm(),
                r.pieces(), r.declaredValue(), r.cod(), r.bookingDate()))));
    }

    @PostMapping("/quote/options")
    @Operation(summary = "Price a shipment on every option", description = "The calculator's view: every service type and "
            + "package type the active card has to the delivery pincode's zone, cheapest first. Options that cannot carry "
            + "the weight are listed under `unavailable`. Nothing is saved.")
    public ApiResponse<OptionsResponse> quoteOptions(@Valid @RequestBody OptionsRequest r) {
        TariffQuoteService.Options o = quoteService.quoteAll(new QuoteInput(r.originPincode(), r.destinationPincode(),
                null, null, r.actualWeightKg(), r.lengthCm(), r.widthCm(), r.heightCm(), r.pieces(), r.declaredValue(),
                r.cod(), r.bookingDate()));
        List<QuoteResponse> options = o.quotes().stream().map(TariffRateCardController::quoteResponse).toList();
        QuoteResponse first = options.isEmpty() ? null : options.get(0);
        return ApiResponse.success(new OptionsResponse(first == null ? null : first.rateCardId(),
                first == null ? null : first.rateCardCode(), first == null ? null : first.fromZone(),
                first == null ? null : first.toZone(), first != null && first.oda(), options,
                o.unavailable().stream().map(u -> new UnavailableResponse(u.serviceTypeCode(), u.serviceTypeName(),
                        u.packageTypeCode(), u.reason())).toList()));
    }

    private static QuoteResponse quoteResponse(TariffQuoteService.Quote q) {
        var p = q.price();
        return new QuoteResponse(q.card().getId(), q.card().getCode(), zoneRef(q.from()), zoneRef(q.to()),
                q.serviceTypeId(), q.serviceTypeCode(), q.serviceTypeName(), q.deliveryDays(), q.packageTypeId(),
                q.packageTypeCode(), q.oda(), p.actualWeightKg(), p.volumetricWeightKg(),
                p.chargeableWeightKg(), p.freight().amount(), p.freight().basis(),
                p.charges().stream().map(c -> new ChargeLineResponse(c.code(), c.name(), c.amount(), c.taxable(), c.basis())).toList(),
                p.taxableAmount(), p.taxes().stream().map(t -> new TaxLineResponse(t.code(), t.percent(), t.amount())).toList(),
                p.taxTotal(), p.nonTaxableAmount(), p.roundOff(), p.total());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a rate card with its lanes and charges")
    public ApiResponse<CardResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.getById(id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a rate card", description = "`version` optional; stale returns 409.")
    public ApiResponse<CardResponse> update(@PathVariable UUID id, @Valid @RequestBody CardRequest request) {
        return ApiResponse.success(toResponse(service.update(id, input(request), request.version())), "Rate card updated");
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate a rate card", description = "Refused while another active card of the same from zone "
            + "covers any of its dates, or while it has no rates.")
    public ApiResponse<CardResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.activate(id)), "Rate card activated");
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a rate card")
    public ApiResponse<CardResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.deactivate(id)), "Rate card deactivated");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a rate card", description = "Soft-deletes the card with its rates and charges.")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.success("Rate card deleted");
    }

    @PutMapping("/{id}/lanes")
    @Operation(summary = "Save the rates of one lane", description = "Replaces every weight slab of the to zone × service "
            + "type × package type. The lightest slab is FIXED; PER_EXTRA_KG adds `rate` per `stepKg` (default 1) above "
            + "the fixed slab before it. Slabs may not overlap or leave a gap above 1 g.")
    public ApiResponse<CardResponse> saveLane(@PathVariable UUID id, @Valid @RequestBody LaneRequest r) {
        return ApiResponse.success(toResponse(service.saveLane(id, new LaneInput(r.toZoneId(), r.serviceTypeId(), r.packageTypeId(),
                r.slabs().stream().map(s -> new SlabInput(s.weightFromKg(), s.weightToKg(), s.rateType(), s.rate(), s.stepKg())).toList()))),
                "Rates saved");
    }

    @DeleteMapping("/{id}/lanes")
    @Operation(summary = "Remove the rates of one lane")
    public ApiResponse<CardResponse> deleteLane(@PathVariable UUID id, @RequestParam UUID toZoneId,
                                                @RequestParam UUID serviceTypeId, @RequestParam UUID packageTypeId) {
        return ApiResponse.success(toResponse(service.deleteLane(id, toZoneId, serviceTypeId, packageTypeId)), "Rates removed");
    }

    @PostMapping("/{id}/charges")
    @Operation(summary = "Add a charge", description = "Fuel, hamali, docket, COD, FOV, ODA… To zone, service type and "
            + "package type are optional filters; per charge code the most specific matching rule is used.")
    public ApiResponse<CardResponse> addCharge(@PathVariable UUID id, @Valid @RequestBody ChargeRequest r) {
        return ApiResponse.success(toResponse(service.addCharge(id, chargeInput(r))), "Charge added");
    }

    @PutMapping("/{id}/charges/{chargeId}")
    @Operation(summary = "Edit a charge")
    public ApiResponse<CardResponse> updateCharge(@PathVariable UUID id, @PathVariable UUID chargeId,
                                                  @Valid @RequestBody ChargeRequest r) {
        return ApiResponse.success(toResponse(service.updateCharge(id, chargeId, chargeInput(r))), "Charge updated");
    }

    @DeleteMapping("/{id}/charges/{chargeId}")
    @Operation(summary = "Remove a charge")
    public ApiResponse<CardResponse> deleteCharge(@PathVariable UUID id, @PathVariable UUID chargeId) {
        return ApiResponse.success(toResponse(service.deleteCharge(id, chargeId)), "Charge removed");
    }

    private static CardInput input(CardRequest r) {
        return new CardInput(r.code(), r.name(), r.fromZoneId(), r.effectiveFrom(), r.effectiveTo(), r.gstPercent(),
                r.volumetricDivisor(), r.totalRounding(), r.description());
    }

    private static ChargeInput chargeInput(ChargeRequest r) {
        return new ChargeInput(r.code(), r.name(), r.toZoneId(), r.serviceTypeId(), r.packageTypeId(), r.appliesWhen(),
                r.calcMethod(), r.value(), r.minAmount(), r.maxAmount(), r.taxable(), r.displayOrder(), r.status());
    }

    private static ZoneRef zoneRef(TariffZoneService.Resolution r) {
        return new ZoneRef(r.zone().getId(), r.zone().getCode(), r.zone().getName(), r.matchedBy().label());
    }

    private static CardResponse toResponse(RateCardDetail d) {
        Map<UUID, String> labels = d.labels();
        Map<String, List<TariffRateSlab>> byLane = new LinkedHashMap<>();
        d.slabs().forEach(s -> byLane.computeIfAbsent(s.getToZoneId() + ":" + s.getServiceTypeId() + ":" + s.getPackageTypeId(),
                k -> new ArrayList<>()).add(s));
        List<LaneResponse> lanes = byLane.values().stream().map(slabs -> {
            TariffRateSlab f = slabs.get(0);
            return new LaneResponse(f.getToZoneId(), labels.get(f.getToZoneId()), f.getServiceTypeId(),
                    labels.get(f.getServiceTypeId()), f.getPackageTypeId(), labels.get(f.getPackageTypeId()),
                    slabs.stream().map(s -> new SlabResponse(s.getId(), s.getWeightFromKg(), s.getWeightToKg(),
                            s.getRateType(), s.getRate(), s.getStepKg())).toList());
        }).toList();
        List<ChargeResponse> charges = d.charges().stream().map(c -> charge(c, labels)).toList();
        return card(d.card(), labels.get(d.card().getFromZoneId()), lanes.size(), charges.size(), lanes, charges);
    }

    private static ChargeResponse charge(TariffChargeRule c, Map<UUID, String> labels) {
        return new ChargeResponse(c.getId(), c.getChargeCode(), c.getChargeName(), c.getToZoneId(), label(labels, c.getToZoneId()),
                c.getServiceTypeId(), label(labels, c.getServiceTypeId()), c.getPackageTypeId(), label(labels, c.getPackageTypeId()),
                c.getAppliesWhen(), c.getCalcMethod(), c.getChargeValue(), c.getMinAmount(), c.getMaxAmount(), c.isTaxable(),
                c.getDisplayOrder(), c.getStatus());
    }

    private static String label(Map<UUID, String> labels, UUID id) {
        return id == null ? null : labels.get(id);
    }

    private static CardResponse card(TariffRateCard c, String fromZoneCode, int laneCount, int chargeCount,
                                     List<LaneResponse> lanes, List<ChargeResponse> charges) {
        return new CardResponse(c.getId(), c.getCode(), c.getName(), c.getFromZoneId(), fromZoneCode, c.getEffectiveFrom(),
                c.getEffectiveTo(), c.getGstPercent(), c.getVolumetricDivisor(), c.getTotalRounding(), c.getDescription(),
                c.getStatus(), laneCount, chargeCount, lanes, charges, c.getCreatedAt(), c.getUpdatedAt(), c.getVersion());
    }
}
