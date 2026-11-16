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

/**
 * Which couriers each business customer sees in the customer portal: their own list, else their home branch's (13.2), else OCCPL Network
 * only. Company admin; a branch manager for their own branch and its customers.
 */
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

    @GetMapping("/branch/{branchId}")
    @Operation(summary = "A branch's couriers: the default of its customers who have no list of their own (13.2)",
            description = "`customised` is false while the branch has none (its customers then see OCCPL Network only). `customers` counts "
                    + "the active business customers whose home branch it is.")
    public ApiResponse<CustomerCourierAccessService.BranchAccessView> getBranch(@PathVariable UUID branchId) {
        return ApiResponse.success(service.viewBranch(branchId));
    }

    @PutMapping("/branch/{branchId}")
    @Operation(summary = "Set a branch's couriers (replaces the list)",
            description = "Applies to every customer of the branch who has no list of their own. An empty list: those customers see OCCPL "
                    + "Network only.")
    public ApiResponse<CustomerCourierAccessService.BranchAccessView> saveBranch(@PathVariable UUID branchId, @RequestBody SaveRequest request) {
        return ApiResponse.success(service.saveBranch(branchId, request.providerCodes()), "Branch courier access saved");
    }

    @PutMapping("/{customerId}")
    @Operation(summary = "Set the couriers this customer sees (replaces the list)",
            description = "Only these couriers' rates appear in the customer's portal. An empty list returns the customer to the default: OCCPL Network only.")
    public ApiResponse<AccessView> save(@PathVariable UUID customerId, @RequestBody SaveRequest request) {
        return ApiResponse.success(service.save(customerId, request.providerCodes()), "Courier access saved");
    }
}
