package com.courier.modules.tariff.api;

import com.courier.modules.tariff.application.PublicRateService;
import com.courier.modules.tariff.application.TariffQuoteService;
import com.courier.modules.tariff.application.TariffQuoteService.QuoteInput;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * The public website's rate calculator — no bearer token. Its own controller and a smaller response than the staff
 * calculator: no ids, rate card or zone codes, and no reasons that describe the tariff's setup. Nothing is saved.
 */
@RestController
@RequestMapping("/api/v1/public/rates")
@RequiredArgsConstructor
@Tag(name = "Public Rates", description = "Price a shipment on the website — no login required")
public class PublicRateController {

    private static final String PINCODE = "[1-9][0-9]{5}";

    private final PublicRateService service;
    private final PublicRateLimiter limiter;

    public record RateRequest(
            @NotNull @Pattern(regexp = PINCODE, message = "Enter a 6-digit pincode") String originPincode,
            @NotNull @Pattern(regexp = PINCODE, message = "Enter a 6-digit pincode") String destinationPincode,
            @NotNull @Positive @Digits(integer = 5, fraction = 3) BigDecimal actualWeightKg,
            @Positive @Digits(integer = 4, fraction = 1) BigDecimal lengthCm,
            @Positive @Digits(integer = 4, fraction = 1) BigDecimal widthCm,
            @Positive @Digits(integer = 4, fraction = 1) BigDecimal heightCm,
            @Positive @Max(999) Integer pieces,
            @NotNull @PositiveOrZero @DecimalMax("100000000") @Digits(integer = 9, fraction = 2) BigDecimal declaredValue,
            boolean cod) {
    }

    public record Charge(String name, BigDecimal amount) {
    }

    public record Tax(String code, BigDecimal percent, BigDecimal amount) {
    }

    public record Rate(String service, String packageType, Integer deliveryDays, boolean remoteArea,
                       BigDecimal chargeableWeightKg, BigDecimal freight, List<Charge> charges, List<Tax> taxes,
                       BigDecimal roundOff, BigDecimal total) {
    }

    public record RatesResponse(List<Rate> rates) {
    }

    /** {@code service} as in {@link Rate}: the service type's name, else its code. */
    public record Divisor(String service, int divisor) {
    }

    public record DivisorsResponse(List<Divisor> divisors) {
    }

    @GetMapping
    @Operation(summary = "Volumetric divisors (public)", description = "The L × W × H (cm) divisor each parcel service is "
            + "priced with, for the website's volumetric weight calculator. Rate-limited per caller address.")
    public ApiResponse<DivisorsResponse> divisors(HttpServletRequest http) {
        limiter.check(clientIp(http));
        return ApiResponse.success(new DivisorsResponse(service.divisors().stream()
                .map(d -> new Divisor(d.serviceTypeName() == null ? d.serviceTypeCode() : d.serviceTypeName(), d.divisor()))
                .toList()));
    }

    @PostMapping
    @Operation(summary = "Price a shipment (public)", description = "Every service the company's zone tariff has between two "
            + "pincodes, cheapest first, for today. Rate-limited per caller address.")
    public ApiResponse<RatesResponse> rates(@Valid @RequestBody RateRequest r, HttpServletRequest http) {
        limiter.check(clientIp(http));
        return ApiResponse.success(new RatesResponse(service.rates(new QuoteInput(r.originPincode(), r.destinationPincode(),
                null, null, r.actualWeightKg(), r.lengthCm(), r.widthCm(), r.heightCm(), r.pieces(), r.declaredValue(),
                r.cod(), null)).quotes().stream().map(PublicRateController::rate).toList()));
    }

    private static Rate rate(TariffQuoteService.Quote q) {
        var p = q.price();
        return new Rate(q.serviceTypeName() == null ? q.serviceTypeCode() : q.serviceTypeName(), q.packageTypeCode(),
                q.deliveryDays(), q.oda(), p.chargeableWeightKg(), p.freight().amount(),
                p.charges().stream().map(c -> new Charge(c.name(), c.amount())).toList(),
                p.taxes().stream().map(t -> new Tax(t.code(), t.percent(), t.amount())).toList(), p.roundOff(), p.total());
    }

    /** As in {@code PublicLeadController}: a hint for limiting only, never an authentication signal. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return ip != null && ip.length() > 45 ? ip.substring(0, 45) : ip;
    }
}
