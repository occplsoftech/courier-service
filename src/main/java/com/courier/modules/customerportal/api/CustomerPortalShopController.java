package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.ShopConnectionService;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's Shopify store (13.3b): connect with a custom app's token, import orders, send tracking back. */
@RestController
@RequestMapping("/api/v1/customer-portal/connections/shopify")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal")
public class CustomerPortalShopController {

    private final ShopConnectionService service;

    @GetMapping
    @Operation(summary = "The connected Shopify store (null when none)")
    public ApiResponse<ShopConnectionService.ConnectionView> get() {
        return ApiResponse.success(service.view().orElse(null));
    }

    public record ConnectRequest(String shopDomain, String accessToken, Boolean fulfilBack) {
    }

    @PostMapping
    @Operation(summary = "Connect a Shopify store", description = "Checks the token with the store, saves it encrypted and imports open orders.")
    public ApiResponse<ShopConnectionService.ConnectionView> connect(@RequestBody ConnectRequest request) {
        return ApiResponse.success(service.connect(request.shopDomain(), request.accessToken(), request.fulfilBack()), "Shopify connected");
    }

    public record SettingsRequest(Boolean paused, Boolean fulfilBack) {
    }

    @PatchMapping
    @Operation(summary = "Pause or resume importing; turn sending tracking back on or off")
    public ApiResponse<ShopConnectionService.ConnectionView> settings(@RequestBody SettingsRequest request) {
        return ApiResponse.success(service.settings(request.paused(), request.fulfilBack()));
    }

    @PostMapping("/import")
    @Operation(summary = "Import open orders now")
    public ApiResponse<ShopConnectionService.ImportResult> importNow() {
        return ApiResponse.success(service.importNow());
    }

    @DeleteMapping
    @Operation(summary = "Disconnect the store (orders already imported stay)")
    public ApiResponse<Void> disconnect() {
        service.disconnect();
        return ApiResponse.success(null, "Shopify disconnected");
    }
}
