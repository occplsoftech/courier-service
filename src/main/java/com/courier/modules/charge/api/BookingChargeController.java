package com.courier.modules.charge.api;

import com.courier.modules.charge.application.BookingChargeService;
import com.courier.modules.charge.application.BookingChargeService.CustomerRate;
import com.courier.modules.charge.application.BookingChargeService.Line;
import com.courier.modules.charge.application.BookingChargeService.QuoteLine;
import com.courier.modules.charge.application.BookingChargeService.Rate;
import com.courier.modules.charge.application.BookingChargeService.RateInput;
import com.courier.modules.charge.domain.BookingChargeCode;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Charges Master (V192): booking charges the clerk can add, per company and per business customer. */
@RestController
@RequestMapping("/api/v1/booking-charges")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Charges Master", description = "Handling, Pick up, Delivery, AWB, Green Tax, Reverse Pick Up, COD and Other charges")
public class BookingChargeController {

    private final BookingChargeService service;

    public record QuoteRequest(UUID customerId, BigDecimal freight, BigDecimal chargeableWeight, Integer pieces,
                               BigDecimal codAmount) {
    }

    @GetMapping
    @Operation(summary = "The company's charges", description = "All eight, in order; a charge never set is inactive "
            + "(COD defaults to 2% of the COD amount, minimum 100).")
    public ApiResponse<List<Rate>> company() {
        return ApiResponse.success(service.companyRates());
    }

    @PutMapping("/{code}")
    @Operation(summary = "Set a company charge", description = "COMPANY_ADMIN. `calc`: FIXED, PER_KG, PER_PIECE, "
            + "PERCENT_OF_FREIGHT (PERCENT_OF_COD for COD only); `minAmount` optional.")
    public ApiResponse<Rate> saveCompany(@PathVariable BookingChargeCode code, @RequestBody RateInput input) {
        return ApiResponse.success(service.saveCompanyRate(code, input), "Charge saved");
    }

    @GetMapping("/customers/{customerId}")
    @Operation(summary = "A business customer's charges", description = "Each charge with the company's value and the "
            + "customer's override (null when it uses the company's).")
    public ApiResponse<List<CustomerRate>> customer(@PathVariable UUID customerId) {
        return ApiResponse.success(service.customerRates(customerId));
    }

    @PutMapping("/customers/{customerId}/{code}")
    @Operation(summary = "Override a charge for a business customer", description = "COMPANY_ADMIN.")
    public ApiResponse<Rate> saveCustomer(@PathVariable UUID customerId, @PathVariable BookingChargeCode code,
                                          @RequestBody RateInput input) {
        return ApiResponse.success(service.saveCustomerRate(customerId, code, input), "Customer charge saved");
    }

    @DeleteMapping("/customers/{customerId}/{code}")
    @Operation(summary = "Reset a customer's override", description = "COMPANY_ADMIN. The customer pays the company's value again.")
    public ApiResponse<Void> resetCustomer(@PathVariable UUID customerId, @PathVariable BookingChargeCode code) {
        service.resetCustomerRate(customerId, code);
        return ApiResponse.success(null, "Customer charge reset");
    }

    @PostMapping("/quote")
    @Operation(summary = "Charges for a booking", description = "Every active charge with its amount for this freight, "
            + "chargeable weight, pieces and COD amount (the customer's value where overridden), for the clerk to tick.")
    public ApiResponse<List<QuoteLine>> quote(@RequestBody QuoteRequest r) {
        return ApiResponse.success(service.quote(r.customerId(), r.freight(), r.chargeableWeight(),
                r.pieces() == null ? 1 : r.pieces(), r.codAmount()));
    }

    @GetMapping("/shipments/{shipmentId}")
    @Operation(summary = "A booking's picked charges")
    public ApiResponse<List<Line>> shipment(@PathVariable UUID shipmentId) {
        return ApiResponse.success(service.linesFor(shipmentId));
    }
}
