package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.RateComparisonService;
import com.courier.modules.customerportal.rates.application.RateComparisonService.InternationalEstimateCommand;
import com.courier.modules.customerportal.rates.domain.InternationalRateProvider;
import com.courier.modules.customerportal.rates.application.RateComparisonService.CompareCommand;
import com.courier.modules.customerportal.rates.application.RateComparisonService.EstimateCommand;
import com.courier.modules.customerportal.rates.application.RateComparisonService.PackageInput;
import com.courier.modules.customerportal.rates.application.RateComparisonService.PartyInput;
import com.courier.modules.customerportal.rates.application.RateComparisonService.QuoteView;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** Rate comparison for the signed-in customer. Prices are computed and stored on the server only. */
@RestController
@RequestMapping("/api/v1/customer-portal")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal")
public class CustomerPortalRatesController {

    private final RateComparisonService service;

    @PostMapping("/rates/compare")
    @Operation(summary = "Compare courier rates for a shipment",
            description = "Asks OCCPL's network and every connected courier; returns priced services (markup and tax "
                    + "included) sorted by total, plus the providers that could not price it and why. The result is "
                    + "stored as a quote valid for `app.customer-portal.quote-validity` (30 minutes by default).")
    public ApiResponse<QuoteView> compare(@Valid @RequestBody CompareRequest request) {
        return ApiResponse.success(service.compare(new CompareCommand(request.pickup().toInput(),
                request.delivery().toInput(), request.pkg().toInput())));
    }

    @PostMapping("/rates/estimate")
    @Operation(summary = "Rate calculator: price a package between two pincodes, nothing stored",
            description = "Same couriers and pricing as compare, from pincodes and a package only. The option ids are not stored and cannot be booked: "
                    + "book through the booking flow, which asks for the addresses. Without dimensions the chargeable "
                    + "weight is the actual weight.")
    public ApiResponse<QuoteView> estimate(@Valid @RequestBody EstimateRequest request) {
        return ApiResponse.success(service.estimate(new EstimateCommand(request.pickupPincode(), request.deliveryPincode(),
                request.packageCount(), request.actualWeightKg(), request.declaredValue(), request.length(),
                request.breadth(), request.height(), request.dimensionUnit(), request.paymentMode())));
    }

    @PostMapping("/rates/international/estimate")
    @Operation(summary = "International rate calculator (sKart): pickup pincode to a country and postcode, nothing stored",
            description = "`kind`: PARCEL (default), DOCUMENT or COMMERCIAL. Same markup and GST handling as the domestic "
                    + "estimate. Empty options with sKart listed as unavailable when the company has not connected it.")
    public ApiResponse<QuoteView> internationalEstimate(@Valid @RequestBody InternationalEstimateRequest request) {
        return ApiResponse.success(service.estimateInternational(new InternationalEstimateCommand(request.originPincode(),
                request.destinationCountry(), request.destinationPostcode(),
                request.kind() == null ? InternationalRateProvider.Kind.PARCEL : request.kind(), request.weightKg(),
                request.pieces(), request.maxLengthCm())));
    }

    @GetMapping("/international/countries")
    @Operation(summary = "Countries the connected international courier (sKart) ships to, A-Z (empty when none is connected)")
    public ApiResponse<java.util.List<InternationalRateProvider.Country>> internationalCountries() {
        return ApiResponse.success(service.internationalCountries());
    }

    @GetMapping("/quotes/{quoteId}")
    @Operation(summary = "One of the customer's own quotes (404 for anyone else's)")
    public ApiResponse<QuoteView> quote(@PathVariable UUID quoteId) {
        return ApiResponse.success(service.get(quoteId));
    }

    public record Party(@NotNull @Size(max = 100) String name, @NotNull @Size(max = 20) String phone,
                        @NotNull @Size(max = 300) String address, @NotNull @Size(max = 6) String pincode,
                        @NotNull @Size(max = 80) String city, @NotNull @Size(max = 80) String state) {
        PartyInput toInput() {
            return new PartyInput(name, phone, address, pincode, city, state);
        }
    }

    public record Package(@NotNull BigDecimal actualWeightKg, @NotNull BigDecimal length, @NotNull BigDecimal breadth,
                          @NotNull BigDecimal height, @Size(max = 2) String dimensionUnit, @NotNull Integer packageCount,
                          @NotNull @Size(min = 1, max = 200) String contents, @NotNull BigDecimal declaredValue,
                          @Size(max = 10) String paymentMode) {
        PackageInput toInput() {
            return new PackageInput(actualWeightKg, length, breadth, height, dimensionUnit, packageCount, contents,
                    declaredValue, paymentMode);
        }
    }

    public record EstimateRequest(@NotNull @Size(max = 6) String pickupPincode, @NotNull @Size(max = 6) String deliveryPincode,
                                  @NotNull Integer packageCount, @NotNull BigDecimal actualWeightKg,
                                  @NotNull BigDecimal declaredValue, BigDecimal length, BigDecimal breadth, BigDecimal height,
                                  @Size(max = 2) String dimensionUnit, @Size(max = 10) String paymentMode) {
    }

    public record InternationalEstimateRequest(@NotNull @Size(min = 6, max = 6) String originPincode,
                                               @NotNull @Size(min = 2, max = 80) String destinationCountry,
                                               @Size(max = 20) String destinationPostcode, InternationalRateProvider.Kind kind,
                                               @NotNull BigDecimal weightKg, Integer pieces, BigDecimal maxLengthCm) {
    }

    public record CompareRequest(@NotNull @Valid Party pickup, @NotNull @Valid Party delivery,
                                 @NotNull @Valid Package pkg) {
    }
}
