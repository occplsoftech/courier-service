package com.courier.modules.forwarding.api;

import com.courier.modules.forwarding.application.BulkForwardingService;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Forward several shipments from a hub at once (14.1); each shipment follows the single-forward rules. */
@RestController
@RequestMapping("/api/v1/forwarding/bulk")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Forwarding")
public class BulkForwardingController {

    private final BulkForwardingService service;

    public record OptionsRequest(List<UUID> shipmentIds) {
    }

    @PostMapping("/options")
    @Operation(summary = "The couriers' prices for several shipments (up to 20 per call)",
            description = "One row per shipment: its options (with `blocker` when it cannot be forwarded) or `error`.")
    public ApiResponse<List<BulkForwardingService.OptionsRow>> options(@RequestBody OptionsRequest r) {
        return ApiResponse.success(service.options(r.shipmentIds()));
    }

    public record ForwardRequest(List<BulkForwardingService.Item> items) {
    }

    @PostMapping
    @Operation(summary = "Forward several shipments (up to 20 per call), one after the other",
            description = "Each is priced afresh and booked as a single forward. One row per shipment: FORWARDED (courier AWB, label), "
                    + "UNCONFIRMED (check with the courier; never retried), REFUSED (reason) or ERROR. One failure never stops the rest.")
    public ApiResponse<List<BulkForwardingService.ResultRow>> forward(@RequestBody ForwardRequest r) {
        return ApiResponse.success(service.forward(r.items()));
    }
}
