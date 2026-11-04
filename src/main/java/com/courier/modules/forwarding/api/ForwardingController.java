package com.courier.modules.forwarding.api;

import com.courier.modules.forwarding.application.ForwardingService;
import com.courier.modules.forwarding.application.ForwardingService.ForwardOptions;
import com.courier.modules.forwarding.application.ForwardingService.ForwardResult;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Hand a shipment held at a hub or branch to a connected courier (company admin, or the manager of that hub or branch). */
@RestController
@RequestMapping("/api/v1/shipments/{shipmentId}/forwarding")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Forwarding", description = "Forward a shipment to a courier partner from the hub")
public class ForwardingController {

    private final ForwardingService service;

    public record ForwardRequest(@NotBlank String providerCode, @NotBlank String serviceCode) {
    }

    @GetMapping("/options")
    @Operation(summary = "The connected couriers' prices for this shipment",
            description = "The courier collects from the hub or branch holding the shipment. `blocker` says why it cannot be forwarded "
                    + "(already forwarded, not at the hub, To Pay...); then there are no options. Prices are the couriers' own (our cost).")
    public ApiResponse<ForwardOptions> options(@PathVariable UUID shipmentId) {
        return ApiResponse.success(service.options(shipmentId));
    }

    @PostMapping
    @Operation(summary = "Forward the shipment to the chosen courier service",
            description = "Priced afresh and booked once. outcome FORWARDED (the courier's AWB and label are on the shipment), or UNCONFIRMED when we "
                    + "cannot tell whether the courier accepted (check with the courier; it is not retried by itself). A refusal is a 422 with the reason.")
    public ApiResponse<ForwardResult> forward(@PathVariable UUID shipmentId, @RequestBody ForwardRequest r) {
        return ApiResponse.success(service.forward(shipmentId, r.providerCode(), r.serviceCode()));
    }

    public record ResolveRequest(boolean courierTookIt, String awb, boolean cancelledByHand) {
    }

    @PostMapping("/resolve")
    @Operation(summary = "Settle a forward the courier never confirmed",
            description = "courierTookIt=false cancels the order at the courier (when it can) and clears the flag so the shipment can be forwarded "
                    + "again; cancelledByHand=true clears it without asking the courier (the person cancelled it in the courier's panel); courierTookIt=true records the AWB the courier gave (awb required) and marks the shipment forwarded.")
    public ApiResponse<ForwardingService.ResolveResult> resolve(@PathVariable UUID shipmentId, @RequestBody ResolveRequest r) {
        return ApiResponse.success(service.resolve(shipmentId, r.courierTookIt(), r.awb(), r.cancelledByHand()));
    }
}
