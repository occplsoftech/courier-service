package com.courier.modules.districtfreight.api;

import com.courier.modules.districtfreight.application.AllRateCalculatorService;
import com.courier.modules.districtfreight.application.AllRateCalculatorService.Input;
import com.courier.modules.districtfreight.application.AllRateCalculatorService.Result;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** All to all rate calculator: one route, every service type, priced the way a booking would be. */
@RestController
@RequestMapping("/api/v1/rate-calculator")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Rate Calculator", description = "Price a route on every service type")
public class AllRateCalculatorController {

    private final AllRateCalculatorService service;

    public record CalculateRequest(@NotBlank String fromPincode, @NotBlank String toPincode,
                                   @NotNull @Positive BigDecimal weightKg, UUID packageTypeId, UUID customerId,
                                   BigDecimal declaredValue) {
    }

    @PostMapping
    @Operation(summary = "Price a route on every active service type",
            description = "Sources in booking order: the business customer's rate structure, their pricing zone, then Zone "
                    + "Tariff. Each row carries its source, freight, other charges, GST at the company's GST % and total, "
                    + "or why that service cannot carry it.")
    public ApiResponse<Result> calculate(@Valid @RequestBody CalculateRequest r) {
        return ApiResponse.success(service.calculate(new Input(r.fromPincode(), r.toPincode(), r.weightKg(),
                r.packageTypeId(), r.customerId(), r.declaredValue())));
    }
}
