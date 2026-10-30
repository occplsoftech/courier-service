package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.CustomerCourierAccessService;
import com.courier.modules.customerportal.rates.application.CustomerCourierAccessService.AccessView;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Which couriers each business customer sees in the customer portal (COMPANY_ADMIN only). */
@RestController
@RequestMapping("/api/v1/courier-integrations/customer-access")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Courier integrations")
public class CustomerCourierAccessController {

    private final CustomerCourierAccessService service;

    public record SaveRequest(List<String> providerCodes) {
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Every courier, and whether this customer can see it",
            description = "`customised` is false while the customer has none assigned and so sees OCCPL Network only.")
    public ApiResponse<AccessView> get(@PathVariable UUID customerId) {
        return ApiResponse.success(service.view(customerId));
    }

    @PutMapping("/{customerId}")
    @Operation(summary = "Set the couriers this customer sees (replaces the list)",
            description = "Only these couriers' rates appear in the customer's portal. An empty list returns the customer to the default: OCCPL Network only.")
    public ApiResponse<AccessView> save(@PathVariable UUID customerId, @RequestBody SaveRequest request) {
        return ApiResponse.success(service.save(customerId, request.providerCodes()), "Courier access saved");
    }
}
