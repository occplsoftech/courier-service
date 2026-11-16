package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.PortalOrderService;
import com.courier.modules.customerportal.rates.application.PortalOrderService.OrderInput;
import com.courier.modules.customerportal.rates.application.PortalOrderService.OrderView;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The signed-in customer's orders inbox (13.3): orders that arrived from their system, to check and book. */
@RestController
@RequestMapping("/api/v1/customer-portal/orders")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal")
public class CustomerPortalOrderController {

    private final PortalOrderService service;

    @GetMapping
    @Operation(summary = "Orders, newest first", description = "`status`: NEW (default), BOOKED, ARCHIVED or ALL.")
    public ApiResponse<com.courier.shared.api.PageResponse<OrderView>> list(@RequestParam(required = false) String status,
                                                                            @RequestParam(defaultValue = "0") int page,
                                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(com.courier.shared.api.PageResponse.from(service.list(status, page, size)));
    }

    @GetMapping("/counts")
    @Operation(summary = "How many orders are new, booked and archived")
    public ApiResponse<PortalOrderService.Counts> counts() {
        return ApiResponse.success(service.counts());
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderView> get(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Correct an order before booking it", description = "Same fields and checks as the public API; the order number stays.")
    public ApiResponse<OrderView> update(@PathVariable UUID id, @RequestBody OrderInput body) {
        return ApiResponse.success(service.update(id, body), "Order saved");
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Put an order aside (it will not be booked)")
    public ApiResponse<OrderView> archive(@PathVariable UUID id) {
        return ApiResponse.success(service.archive(id), "Order archived");
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Bring an archived order back to the inbox")
    public ApiResponse<OrderView> restore(@PathVariable UUID id) {
        return ApiResponse.success(service.restore(id), "Order restored");
    }

    public record QuoteRequest(List<UUID> orderIds) {
    }

    @PostMapping("/quote")
    @Operation(summary = "Price orders for booking at once",
            description = "Like bulk booking: the customer's own rate when OCCPL Network has one, else the cheapest allowed courier; pickup is "
                    + "the default pickup address. Book the ok rows with POST /customer-portal/bulk-bookings/book; each order is then booked.")
    public ApiResponse<List<PortalOrderService.QuoteRow>> quote(@RequestBody QuoteRequest request) {
        return ApiResponse.success(service.quote(request == null ? null : request.orderIds()));
    }
}
