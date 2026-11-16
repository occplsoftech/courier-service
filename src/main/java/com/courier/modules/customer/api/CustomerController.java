package com.courier.modules.customer.api;

import com.courier.modules.customer.api.dto.CreateCustomerAddressRequest;
import com.courier.modules.customer.api.dto.CreateCustomerRequest;
import com.courier.modules.customer.api.dto.CustomerAddressResponse;
import com.courier.modules.customer.api.dto.CustomerResponse;
import com.courier.modules.customer.api.dto.CustomerSearchRequest;
import com.courier.modules.customer.api.dto.CustomerSummaryResponse;
import com.courier.modules.customer.api.dto.UpdateCustomerAddressRequest;
import com.courier.modules.customer.api.dto.UpdateCustomerRequest;
import com.courier.modules.customer.application.CustomerAddressService;
import com.courier.modules.customer.application.CustomerLogoService;
import com.courier.modules.customer.application.CustomerService;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerAddress;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Customers: reusable master data, independent of Shipment Order. Enforced on
 * {@link CustomerService} / {@link CustomerAddressService}: {@code COMPANY_ADMIN} full
 * control; {@code BRANCH_MANAGER} / {@code OPERATOR} create and update while booking, and
 * may add or edit an address but not delete one; every authenticated user of the company
 * reads. The company is always the caller's, from the JWT.
 */
@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customers", description = "Customer master data and their addresses")
public class CustomerController {

    private static final Map<String, String> SORTABLE = Map.ofEntries(
            Map.entry("customerCode", "customerCode"),
            Map.entry("firstName", "firstName"),
            Map.entry("lastName", "lastName"),
            Map.entry("mobile", "mobile"),
            Map.entry("customerType", "customerType"),
            Map.entry("status", "status"),
            Map.entry("createdDate", "createdAt"),
            Map.entry("createdAt", "createdAt"),
            Map.entry("updatedDate", "updatedAt"));

    private static final int MAX_PAGE_SIZE = 100;

    private final CustomerService customerService;
    private final CustomerLogoService logoService;
    private final CustomerAddressService addressService;
    private final CustomerMapper mapper;

    @PostMapping
    @Operation(summary = "Create a customer",
            description = "`COMPANY_ADMIN`, `BRANCH_MANAGER` or an operator booking at the "
                    + "counter. `customerCode` is optional — blank generates one. `mobile` "
                    + "is unique within the company. GST is mandatory when `customerType` "
                    + "is `BUSINESS`.")
    public ResponseEntity<ApiResponse<CustomerResponse>> create(
            @Valid @RequestBody CreateCustomerRequest request) {
        Customer created = customerService.create(mapper.toCommand(request));
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/customers/{id}")
                        .buildAndExpand(created.getId()).toUri())
                .body(ApiResponse.success(mapper.toResponse(created, List.of()), "Customer created"));
    }

    @GetMapping("/{id}/logo")
    @Operation(summary = "The customer's brand logo as a data URL",
            description = "`data` is null when no logo is uploaded.")
    public ApiResponse<Map<String, String>> logo(@PathVariable UUID id) {
        Map<String, String> body = new java.util.HashMap<>();
        body.put("dataUrl", logoService.get(id).orElse(null));
        return ApiResponse.success(body);
    }

    @PutMapping(value = "/{id}/logo", consumes = "multipart/form-data")
    @Operation(summary = "Upload the customer's brand logo",
            description = "PNG, JPEG or WebP, at most 150 KB; the type is read from the file. Does not change the customer's version.")
    public ApiResponse<Void> uploadLogo(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        logoService.upload(id, file.getBytes());
        return ApiResponse.success("Logo saved");
    }

    @DeleteMapping("/{id}/logo")
    @Operation(summary = "Remove the customer's brand logo")
    public ApiResponse<Void> removeLogo(@PathVariable UUID id) {
        logoService.remove(id);
        return ApiResponse.success("Logo removed");
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a customer",
            description = "Full replacement of the editable fields. `version` required; a "
                    + "stale value returns 409. `customerCode` is immutable; status has its "
                    + "own endpoints.")
    public ApiResponse<CustomerResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateCustomerRequest request) {
        Customer updated = customerService.update(id, mapper.toCommand(request));
        return ApiResponse.success(
                mapper.toResponse(updated, addressService.listByCustomer(id)), "Customer updated");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a customer", description = "Includes every address on file for it.")
    public ApiResponse<CustomerResponse> get(@PathVariable UUID id) {
        Customer customer = customerService.getById(id);
        return ApiResponse.success(
                mapper.toResponse(customer, addressService.listByCustomer(id)));
    }

    @GetMapping
    @Operation(summary = "List customers",
            description = """
                    Paged, sorted, filtered, searchable. Sort: `customerCode`, `firstName`,
                    `lastName`, `mobile`, `customerType`, `status`, `createdDate`,
                    `updatedDate`. `size` capped at 100.
                    """)
    public ApiResponse<PageResponse<CustomerSummaryResponse>> list(
            @Valid @ParameterObject CustomerSearchRequest search,
            @ParameterObject @PageableDefault(size = 20, sort = "customerCode") Pageable pageable) {
        Page<Customer> page = customerService.search(mapper.toCriteria(search), sanitise(pageable));
        return ApiResponse.success(PageResponse.from(page, mapper::toSummary));
    }

    public record HomeBranchRequest(UUID branchId) {
    }

    @PatchMapping("/{id}/home-branch")
    @Operation(summary = "Set a business customer's home branch",
            description = "Its couriers are the customer's default in the portal (13.2). `branchId` null fills it again from the branch "
                    + "serving the default pickup pincode. `COMPANY_ADMIN` or `BRANCH_MANAGER`.")
    public ApiResponse<CustomerResponse> setHomeBranch(@PathVariable UUID id,
                                                       @org.springframework.web.bind.annotation.RequestBody HomeBranchRequest request) {
        Customer saved = customerService.setHomeBranch(id, request == null ? null : request.branchId());
        return ApiResponse.success(mapper.toResponse(saved, addressService.listByCustomer(id)), "Home branch saved");
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate a customer", description = "`COMPANY_ADMIN` or `BRANCH_MANAGER`. Idempotent.")
    public ApiResponse<CustomerResponse> activate(@PathVariable UUID id) {
        Customer activated = customerService.activate(id);
        return ApiResponse.success(
                mapper.toResponse(activated, addressService.listByCustomer(id)), "Customer activated");
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a customer",
            description = "`COMPANY_ADMIN` or `BRANCH_MANAGER`. Withdraws it from the booking "
                    + "pickers; past shipments referencing it are unaffected. Idempotent.")
    public ApiResponse<CustomerResponse> deactivate(@PathVariable UUID id) {
        Customer deactivated = customerService.deactivate(id);
        return ApiResponse.success(
                mapper.toResponse(deactivated, addressService.listByCustomer(id)), "Customer deactivated");
    }

    // -------------------------------------------------------------------- addresses

    @PostMapping("/{id}/addresses")
    @Operation(summary = "Add an address to a customer",
            description = "Each geography id, if given, must exist in the shared global "
                    + "masters. Setting `isDefaultPickup`/`isDefaultDelivery` clears that "
                    + "flag on the customer's other addresses. A duplicate of an existing "
                    + "address (same lines and pincode) is refused.")
    public ResponseEntity<ApiResponse<CustomerAddressResponse>> addAddress(
            @PathVariable UUID id, @Valid @RequestBody CreateCustomerAddressRequest request) {
        CustomerAddress created = addressService.create(id, mapper.toCommand(request));
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/customers/{id}/addresses/{addressId}")
                        .buildAndExpand(id, created.getId()).toUri())
                .body(ApiResponse.success(mapper.toResponse(created), "Address added"));
    }

    @PutMapping("/{id}/addresses/{addressId}")
    @Operation(summary = "Update a customer address",
            description = "Full replacement. `version` required; a stale value returns 409.")
    public ApiResponse<CustomerAddressResponse> updateAddress(
            @PathVariable UUID id, @PathVariable UUID addressId,
            @Valid @RequestBody UpdateCustomerAddressRequest request) {
        CustomerAddress updated = addressService.update(id, addressId, mapper.toCommand(request));
        return ApiResponse.success(mapper.toResponse(updated), "Address updated");
    }

    @DeleteMapping("/{id}/addresses/{addressId}")
    @Operation(summary = "Delete a customer address",
            description = "Soft delete. `COMPANY_ADMIN` only.")
    public ResponseEntity<ApiResponse<Void>> deleteAddress(
            @PathVariable UUID id, @PathVariable UUID addressId) {
        addressService.delete(id, addressId);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success("Address deleted"));
    }

    // -------------------------------------------------------------------- helpers

    private Pageable sanitise(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        List<Sort.Order> orders = pageable.getSort().stream()
                .map(order -> {
                    String property = SORTABLE.get(order.getProperty());
                    if (property == null) {
                        throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                                "Cannot sort by '%s'. Allowed: %s"
                                        .formatted(order.getProperty(),
                                                String.join(", ", new TreeSet<>(SORTABLE.keySet()))));
                    }
                    return new Sort.Order(order.getDirection(), property);
                })
                .toList();
        Sort sort = orders.isEmpty() ? Sort.by(Sort.Order.asc("customerCode")) : Sort.by(orders);
        return PageRequest.of(pageable.getPageNumber(), size, sort);
    }
}
