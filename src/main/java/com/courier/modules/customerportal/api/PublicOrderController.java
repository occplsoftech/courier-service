package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.CustomerApiKeyService;
import com.courier.modules.customerportal.rates.application.PortalOrderService;
import com.courier.modules.customerportal.rates.application.PortalOrderService.OrderInput;
import com.courier.modules.customerportal.rates.application.PortalOrderService.Received;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import com.courier.shared.exception.UnauthorizedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The public order API (13.3): a business customer's own system sends its orders with an API key created in the portal (header
 * {@code X-Api-Key}). Orders land in the portal's "Orders to ship" inbox; the customer books them there. No login token: the key is the
 * credential. At most {@value #PER_MINUTE} requests a minute per key.
 */
@RestController
@RequestMapping("/api/v1/public/orders")
@RequiredArgsConstructor
@Tag(name = "Public order API", description = "Send orders from your own system into the customer portal with an API key")
public class PublicOrderController {

    static final int PER_MINUTE = 120;

    private final CustomerApiKeyService keys;
    private final PortalOrderService orders;
    private final Map<UUID, long[]> usage = new ConcurrentHashMap<>();

    @PostMapping
    @Operation(summary = "Send an order",
            description = "Required: orderNumber, buyer {name, phone, address, pincode}, parcel.declaredValue. Optional: buyer.city/state, "
                    + "parcel {weightKg (0.5), lengthCm/breadthCm/heightCm (10), count (1), contents}, payment {mode PREPAID|COD, codAmount "
                    + "(the declared value)}, items [{name, quantity, price}], notes. The same orderNumber again returns the order already "
                    + "received (200, created=false); a new one 201.")
    public ResponseEntity<ApiResponse<Received>> send(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                      @RequestBody(required = false) OrderInput body) {
        CustomerApiKeyService.KeyOwner owner = owner(apiKey);
        Received r = orders.receive(owner, body);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(r, r.created() ? "Order received" : "Order already received"));
    }

    @GetMapping("/{orderNumber}")
    @Operation(summary = "An order's status",
            description = "NEW (waiting in the inbox), BOOKED (with the courier and its tracking number) or ARCHIVED.")
    public ApiResponse<PortalOrderService.Status> status(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                         @PathVariable String orderNumber) {
        return ApiResponse.success(orders.status(owner(apiKey), orderNumber));
    }

    private CustomerApiKeyService.KeyOwner owner(String apiKey) {
        CustomerApiKeyService.KeyOwner owner = keys.authenticate(apiKey)
                .orElseThrow(() -> new UnauthorizedException("A valid X-Api-Key header is required. Create a key in the portal under Developer API."));
        long minute = Instant.now().getEpochSecond() / 60;
        long[] slot = usage.compute(owner.keyId(), (k, v) -> v == null || v[0] != minute ? new long[]{minute, 1} : new long[]{minute, v[1] + 1});
        if (slot[1] > PER_MINUTE) {
            throw new BusinessRuleException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many requests: at most " + PER_MINUTE + " a minute per key.");
        }
        return owner;
    }
}
