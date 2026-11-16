package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.ShopConnectionService;
import com.courier.modules.customerportal.rates.domain.ShopConnection;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * The signed-in customer's online stores (13.3b Shopify, 13.3c WooCommerce): connect with credentials made in the store's own admin,
 * import orders, send tracking back. {@code {platform}} is {@code shopify} or {@code woocommerce}.
 */
@RestController
@RequestMapping("/api/v1/customer-portal/connections/{platform}")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal")
public class CustomerPortalShopController {

    private final ShopConnectionService service;

    @GetMapping
    @Operation(summary = "The connected store of this kind (null when none)")
    public ApiResponse<ShopConnectionService.ConnectionView> get(@PathVariable String platform) {
        return ApiResponse.success(service.view(platform(platform)).orElse(null));
    }

    /** Shopify: shopDomain + accessToken. WooCommerce: storeUrl + consumerKey + consumerSecret. */
    public record ConnectRequest(String shopDomain, String accessToken, String storeUrl, String consumerKey, String consumerSecret,
                                 Boolean fulfilBack) {
    }

    @PostMapping
    @Operation(summary = "Connect a store", description = "Checks the credentials with the store, saves them encrypted and imports open orders.")
    public ApiResponse<ShopConnectionService.ConnectionView> connect(@PathVariable String platform, @RequestBody ConnectRequest r) {
        ShopConnection.Platform p = platform(platform);
        Map<String, String> pasted = new HashMap<>();
        put(pasted, "accessToken", r.accessToken());
        put(pasted, "consumerKey", r.consumerKey());
        put(pasted, "consumerSecret", r.consumerSecret());
        String address = p == ShopConnection.Platform.SHOPIFY ? r.shopDomain() : (r.storeUrl() != null ? r.storeUrl() : r.shopDomain());
        return ApiResponse.success(service.connect(p, address, pasted, r.fulfilBack()), "Store connected");
    }

    public record SettingsRequest(Boolean paused, Boolean fulfilBack) {
    }

    @PatchMapping
    @Operation(summary = "Pause or resume importing; turn sending tracking back on or off")
    public ApiResponse<ShopConnectionService.ConnectionView> settings(@PathVariable String platform, @RequestBody SettingsRequest request) {
        return ApiResponse.success(service.settings(platform(platform), request.paused(), request.fulfilBack()));
    }

    @PostMapping("/import")
    @Operation(summary = "Import open orders now")
    public ApiResponse<ShopConnectionService.ImportResult> importNow(@PathVariable String platform) {
        return ApiResponse.success(service.importNow(platform(platform)));
    }

    @DeleteMapping
    @Operation(summary = "Disconnect the store (orders already imported stay)")
    public ApiResponse<Void> disconnect(@PathVariable String platform) {
        service.disconnect(platform(platform));
        return ApiResponse.success(null, "Store disconnected");
    }

    private static ShopConnection.Platform platform(String raw) {
        return switch (raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT)) {
            case "shopify" -> ShopConnection.Platform.SHOPIFY;
            case "woocommerce" -> ShopConnection.Platform.WOOCOMMERCE;
            default -> throw new ResourceNotFoundException("Store type", raw);
        };
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
