package com.courier.modules.customerpricing.api;

import com.courier.modules.customerpricing.api.CustomerRateDtos.CalculateRequest;
import com.courier.modules.customerpricing.api.CustomerRateDtos.CalculateResponse;
import com.courier.modules.customerpricing.api.CustomerRateDtos.RateResponse;
import com.courier.modules.customerpricing.api.CustomerRateDtos.SaveRequest;
import com.courier.modules.customerpricing.api.CustomerRateDtos.SlabResponse;
import com.courier.modules.customerpricing.application.CustomerRateSourceService;
import com.courier.modules.customerpricing.application.ZoneRateService;
import com.courier.modules.customerpricing.application.ZoneRateService.Quote;
import com.courier.modules.customerpricing.application.ZoneRateService.QuoteCommand;
import com.courier.modules.customerpricing.application.ZoneRateService.SaveCommand;
import com.courier.modules.customerpricing.application.ZoneRateService.SlabInput;
import com.courier.modules.customerpricing.application.ZoneRateView;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.UUID;

/** Per-zone customer rate cards. Writes are {@code COMPANY_ADMIN} (enforced on the service); reads and calculate are any authenticated user. */
@RestController
@RequestMapping("/api/v1/customer-rates")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer Rates", description = "Per-zone rate cards of business customers")
public class CustomerRateController {

    private final ZoneRateService service;
    private final CustomerRateSourceService rateSource;

    public record RateSourceRequest(boolean useBusinessRates) {
    }

    @GetMapping("/customer/{customerId}/source")
    @Operation(summary = "Which rates price this customer", description = "`useBusinessRates` true: the customer's own rate card; false: company rates.")
    public ApiResponse<CustomerRateSourceService.SourceView> source(@PathVariable UUID customerId) {
        return ApiResponse.success(rateSource.get(customerId));
    }

    @PutMapping("/customer/{customerId}/source")
    @Operation(summary = "Choose business rates or company rates for this customer",
            description = "Business rates: the customer's rate card applies where it covers; otherwise (or when off) the company rates apply.")
    public ApiResponse<CustomerRateSourceService.SourceView> setSource(@PathVariable UUID customerId, @RequestBody RateSourceRequest r) {
        return ApiResponse.success(rateSource.set(customerId, r.useBusinessRates()), "Rate source saved");
    }

    @GetMapping
    @Operation(summary = "List rate cards", description = "Every zone rate card, optionally for one customer.")
    public ApiResponse<List<RateResponse>> list(@RequestParam(required = false) UUID customerId) {
        return ApiResponse.success(service.search(customerId).stream().map(this::toResponse).toList());
    }

    @GetMapping("/zone/{zoneId}")
    @Operation(summary = "Fetch a zone's rate card for one service type", description = "`serviceTypeId` omitted is the card for "
            + "every service. `data` is null if the zone has none yet.")
    public ApiResponse<RateResponse> get(@PathVariable UUID zoneId, @RequestParam(required = false) UUID serviceTypeId) {
        return ApiResponse.success(service.getByZone(zoneId, serviceTypeId).map(this::toResponse).orElse(null));
    }

    @GetMapping("/zone/{zoneId}/cards")
    @Operation(summary = "Every rate card of a zone", description = "The \"all services\" card and one per service type (AIR, SURFACE...).")
    public ApiResponse<List<RateResponse>> cards(@PathVariable UUID zoneId) {
        return ApiResponse.success(service.cardsOfZone(zoneId).stream().map(this::toResponse).toList());
    }

    @PutMapping("/zone/{zoneId}")
    @Operation(summary = "Create or replace a zone's rate card", description = "Header charges plus per-kg weight "
            + "slabs, half-open [min,max), non-overlapping; only the last slab may have no maximum. `version` "
            + "is required only when a rate card already exists.")
    public ApiResponse<RateResponse> save(@PathVariable UUID zoneId, @RequestParam(required = false) UUID serviceTypeId, @Valid @RequestBody SaveRequest r) {
        List<SlabInput> slabs = r.slabs().stream()
                .map(s -> new SlabInput(s.minWeight(), s.maxWeight(), s.ratePerKg())).toList();
        SaveCommand command = new SaveCommand(r.minChargeableWeight(), r.docketCharge(), r.mathadiCharge(),
                r.packCharge(), r.odaCharge(), r.fuelSurchargePercent(), r.fovPercent(),
                r.effectiveFrom(), r.effectiveTo(), slabs, r.version());
        return ApiResponse.success(toResponse(service.save(zoneId, serviceTypeId, command)), "Rate saved");
    }

    @PatchMapping("/zone/{zoneId}/activate")
    @Operation(summary = "Activate a zone's rate card")
    public ApiResponse<RateResponse> activate(@PathVariable UUID zoneId, @RequestParam(required = false) UUID serviceTypeId) {
        return ApiResponse.success(toResponse(service.activate(zoneId, serviceTypeId)), "Rate activated");
    }

    @PatchMapping("/zone/{zoneId}/deactivate")
    @Operation(summary = "Deactivate a zone's rate card")
    public ApiResponse<RateResponse> deactivate(@PathVariable UUID zoneId, @RequestParam(required = false) UUID serviceTypeId) {
        return ApiResponse.success(toResponse(service.deactivate(zoneId, serviceTypeId)), "Rate deactivated");
    }

    @DeleteMapping("/zone/{zoneId}")
    @Operation(summary = "Delete a zone's rate card")
    public ApiResponse<Void> delete(@PathVariable UUID zoneId, @RequestParam(required = false) UUID serviceTypeId) {
        service.delete(zoneId, serviceTypeId);
        return ApiResponse.success("Rate deleted");
    }

    @PostMapping("/calculate")
    @Operation(summary = "Price a shipment from a customer's zone rate", description = "Resolves the customer's "
            + "active zone for the destination, then prices from its rate card. Read-only.")
    public ApiResponse<CalculateResponse> calculate(@Valid @RequestBody CalculateRequest r) {
        Quote q = service.calculate(new QuoteCommand(r.customerId(), r.districtId(), r.cityId(),
                r.actualWeight(), r.declaredValue(), r.odaApplicable(), r.bookingDate(), r.serviceTypeId()));
        return ApiResponse.success(new CalculateResponse(q.zoneId(), q.zoneName(), q.actualWeight(),
                q.chargeableWeight(), q.ratePerKg(), q.freight(), q.docketCharge(), q.mathadiCharge(),
                q.packCharge(), q.odaCharge(), q.fuelSurcharge(), q.fovCharge(), q.total()), "Rate calculated");
    }

    private RateResponse toResponse(ZoneRateView v) {
        var r = v.rate();
        return new RateResponse(r.getId(), v.zone().getId(), v.zone().getZoneName(), r.getCustomerId(),
                v.customerName(), r.getMinChargeableWeight(), r.getDocketCharge(), r.getMathadiCharge(),
                r.getPackCharge(), r.getOdaCharge(), r.getFuelSurchargePercent(), r.getFovPercent(),
                r.getEffectiveFrom(), r.getEffectiveTo(), r.getStatus(),
                r.getServiceTypeId(), v.serviceTypeCode(), v.serviceTypeName(),
                v.slabs().stream().map(s -> new SlabResponse(s.getId(), s.getMinWeight(), s.getMaxWeight(),
                        s.getRatePerKg())).toList(),
                r.getCreatedAt(), r.getUpdatedAt(), r.getVersion());
    }
}
