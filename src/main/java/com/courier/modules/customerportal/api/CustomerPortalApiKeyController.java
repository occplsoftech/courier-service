package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.CustomerApiKeyService;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The signed-in customer's API keys for the public order API (13.3). */
@RestController
@RequestMapping("/api/v1/customer-portal/api-keys")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal")
public class CustomerPortalApiKeyController {

    private final CustomerApiKeyService service;

    @GetMapping
    @Operation(summary = "The customer's API keys (never the key itself)")
    public ApiResponse<List<CustomerApiKeyService.KeyView>> list() {
        return ApiResponse.success(service.list());
    }

    public record CreateRequest(String name) {
    }

    @PostMapping
    @Operation(summary = "Create an API key", description = "The full key is in the answer once; store it in your system. Up to 5 active keys.")
    public ApiResponse<CustomerApiKeyService.CreatedKey> create(@RequestBody(required = false) CreateRequest request) {
        return ApiResponse.success(service.create(request == null ? null : request.name()), "API key created");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke an API key (it stops working at once)")
    public ApiResponse<CustomerApiKeyService.KeyView> revoke(@PathVariable UUID id) {
        return ApiResponse.success(service.revoke(id), "API key revoked");
    }
}
