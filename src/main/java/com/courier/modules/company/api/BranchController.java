package com.courier.modules.company.api;

import com.courier.modules.company.api.dto.AddBranchPincodesRequest;
import com.courier.modules.company.api.dto.AddBranchPincodesResponse;
import com.courier.modules.company.api.dto.AssignHubRequest;
import com.courier.modules.company.api.dto.AssignManagerRequest;
import com.courier.modules.company.api.dto.AssignUsersRequest;
import com.courier.modules.company.api.dto.AssignUsersResponse;
import com.courier.modules.company.api.dto.BranchPincodeResponse;
import com.courier.modules.company.api.dto.BranchResponse;
import com.courier.modules.company.api.dto.BranchSearchRequest;
import com.courier.modules.company.api.dto.BranchSummaryResponse;
import com.courier.modules.company.api.dto.CreateBranchRequest;
import com.courier.modules.company.api.dto.UpdateBranchRequest;
import com.courier.modules.company.application.BranchPincodeMappingService;
import com.courier.modules.company.application.BranchService;
import com.courier.modules.company.domain.Branch;
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
import org.springframework.data.domain.PageImpl;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Company branches.
 *
 * <p>Enforced on {@code BranchService}: {@code COMPANY_ADMIN} manages every branch;
 * {@code BRANCH_MANAGER} updates and staffs only the branch they manage; other company
 * users read only the branch they are assigned to; {@code SUPER_ADMIN} reads across
 * companies. The company is always the caller's, from the JWT.
 *
 * <p>There is no soft-delete-via-DELETE that removes the row physically — the delete is a
 * soft delete. Hub, Customer and Shipment are separate modules, not reachable here.
 */
@RestController
@RequestMapping("/api/v1/branches")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Branches", description = "Company branch administration")
public class BranchController {

    private static final Map<String, String> SORTABLE = Map.ofEntries(
            Map.entry("branchCode", "branchCode"),
            Map.entry("branchName", "branchName"),
            Map.entry("branchType", "branchType"),
            Map.entry("status", "status"),
            Map.entry("city", "city"),
            Map.entry("state", "state"),
            Map.entry("postalCode", "postalCode"),
            Map.entry("createdDate", "createdAt"),
            Map.entry("createdAt", "createdAt"),
            Map.entry("updatedDate", "updatedAt"));

    private static final int MAX_PAGE_SIZE = 100;

    private final BranchService service;
    private final BranchMapper mapper;
    private final BranchPincodeMappingService pincodeMappingService;

    @PostMapping
    @Operation(summary = "Create a branch, its user and its wallet",
            description = "`COMPANY_ADMIN` only. Code and name are unique within the "
                    + "company, including against soft-deleted branches. A `managerId`, if "
                    + "given, must be a user of the company.\n\n"
                    + "One call creates four things: the branch, a login account for it "
                    + "(`branchUser` on the request; omitted means one is derived from the "
                    + "branch code), the `BRANCH_MANAGER` company role that account is "
                    + "granted (created from the default catalogue if the company has none), "
                    + "and its prepaid wallet with a generated wallet number.\n\n"
                    + "The first three share the branch's transaction. The **wallet does "
                    + "not**: it is provisioned after commit and repaired on first access, "
                    + "so it is not in this response — read it from "
                    + "`GET /api/v1/branch-wallet`.\n\n"
                    + "When no password is supplied the generated one is returned in "
                    + "`branchUser.temporaryPassword` — **this is the only time it is "
                    + "readable**; afterwards it can only be reset.")
    public ResponseEntity<ApiResponse<BranchResponse>> create(
            @Valid @RequestBody CreateBranchRequest request) {
        BranchService.BranchCreation created = service.create(mapper.toCommand(request));
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/branches/{id}")
                        .buildAndExpand(created.branch().getId()).toUri())
                .body(ApiResponse.success(mapper.toResponse(created),
                        "Branch, user and wallet created"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a branch",
            description = "Full replacement. `COMPANY_ADMIN` (any branch) or `BRANCH_MANAGER` "
                    + "(their own). `version` required; a stale value returns 409. Status and "
                    + "manager have their own endpoints.")
    public ApiResponse<BranchResponse> update(@PathVariable UUID id,
                                              @Valid @RequestBody UpdateBranchRequest request) {
        return ApiResponse.success(
                mapper.toResponse(service.update(id, mapper.toCommand(request))), "Branch updated");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a branch",
            description = "A non-admin sees only the branch they manage or are assigned to; "
                    + "any other id returns 404.")
    public ApiResponse<BranchResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.getById(id)));
    }

    @GetMapping
    @Operation(summary = "List branches",
            description = """
                    Paged, sorted, filtered, searchable. `COMPANY_ADMIN`/`SUPER_ADMIN` see
                    the whole company (or all companies); everyone else sees only the
                    branches they may. Sort: `branchCode`, `branchName`, `branchType`,
                    `status`, `city`, `state`, `postalCode`, `createdDate`, `updatedDate`.
                    `size` capped at 100.
                    """)
    public ApiResponse<PageResponse<BranchSummaryResponse>> list(
            @Valid @ParameterObject BranchSearchRequest search,
            @ParameterObject @PageableDefault(size = 20, sort = "branchCode") Pageable pageable) {
        Page<Branch> page = service.search(mapper.toCriteria(search), sanitise(pageable));
        return ApiResponse.success(PageResponse.from(page, mapper::toSummary));
    }

    @GetMapping("/directory")
    @Operation(summary = "All active branches, for pickers",
            description = "Every active branch of the caller's company, regardless of the "
                    + "branch-scoped visibility `GET /branches` applies for a non-admin — a "
                    + "booking clerk or the Rate Calculator needs every destination, not just "
                    + "their own branch. `isAuthenticated()`, unpaged (branch counts are "
                    + "small).")
    public ApiResponse<PageResponse<BranchSummaryResponse>> directory() {
        List<Branch> branches = service.listActiveDirectory();
        Page<Branch> page = new PageImpl<>(branches);
        return ApiResponse.success(PageResponse.from(page, mapper::toSummary));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a branch",
            description = "Soft delete. `COMPANY_ADMIN` only. The row is retained and the "
                    + "code and name stay reserved; shipments and history referencing it "
                    + "survive.")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success("Branch deleted"));
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate a branch", description = "`COMPANY_ADMIN`. Idempotent.")
    public ApiResponse<BranchResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.activate(id)), "Branch activated");
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a branch",
            description = "`COMPANY_ADMIN`. Withdraws it from operation; existing shipments "
                    + "referencing it are unaffected. Idempotent.")
    public ApiResponse<BranchResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.deactivate(id)), "Branch deactivated");
    }

    @PatchMapping("/{id}/assign-manager")
    @Operation(summary = "Assign the branch manager",
            description = "`COMPANY_ADMIN`. Names one user as manager (null clears it). The "
                    + "user must belong to the company; granting them the BRANCH_MANAGER role "
                    + "is a separate User Management action.")
    public ApiResponse<BranchResponse> assignManager(@PathVariable UUID id,
                                                     @RequestBody AssignManagerRequest request) {
        return ApiResponse.success(
                mapper.toResponse(service.assignManager(id, request.managerId())), "Manager assigned");
    }

    @PatchMapping("/{id}/assign-hub")
    @Operation(summary = "Assign the branch's hub",
            description = "`COMPANY_ADMIN`. Names the HUB-type branch this branch's Loading "
                    + "Sheets go to by default (null clears it).")
    public ApiResponse<BranchSummaryResponse> assignHub(@PathVariable UUID id,
                                                        @RequestBody AssignHubRequest request) {
        return ApiResponse.success(
                mapper.toSummary(service.assignHub(id, request.hubId())), "Hub assigned");
    }

    @PatchMapping("/{id}/courier-pickup-location")
    @Operation(summary = "Set the branch's pickup location name at the couriers",
            description = "`COMPANY_ADMIN`. The name exactly as registered in the Delhivery / Shiprocket panel; the courier collects "
                    + "forwarded shipments from there. Blank clears it.")
    public ApiResponse<BranchResponse> setCourierPickupLocation(@PathVariable UUID id,
                                                                @RequestBody @jakarta.validation.Valid
                                                                com.courier.modules.company.api.dto.SetCourierPickupLocationRequest request) {
        return ApiResponse.success(mapper.toResponse(service.setCourierPickupLocation(id, request.name())), "Saved");
    }

    @PatchMapping("/{id}/assign-users")
    @Operation(summary = "Assign users to the branch",
            description = "`COMPANY_ADMIN` (any branch) or `BRANCH_MANAGER` (their own). "
                    + "Places the listed users at the branch. Ids that are not users of the "
                    + "company come back in `rejected`; ones already there in `skipped`.")
    public ApiResponse<AssignUsersResponse> assignUsers(@PathVariable UUID id,
                                                        @Valid @RequestBody AssignUsersRequest request) {
        BranchService.AssignUsersResult result = service.assignUsers(id, request.userIds());
        return ApiResponse.success(
                new AssignUsersResponse(result.assigned(), result.skipped(), result.rejected()),
                "Users assigned");
    }

    // ------------------------------------------------------------ pincode mapping

    @GetMapping("/{id}/pincodes")
    @Operation(summary = "List the pincodes mapped to this branch",
            description = "Every pincode this branch serves. Same read visibility as "
                    + "`GET /branches/{id}`.")
    public ApiResponse<List<BranchPincodeResponse>> listPincodes(@PathVariable UUID id) {
        return ApiResponse.success(pincodeMappingService.list(id));
    }

    @PostMapping("/{id}/pincodes")
    @Operation(summary = "Map pincodes to this branch",
            description = "`COMPANY_ADMIN` only. A pincode already mapped to a *different* "
                    + "branch of the same company is reported in `conflicts`, not moved — "
                    + "remove it from that branch first. One already mapped to this branch is "
                    + "reported in `alreadyMapped`, not re-applied.")
    public ApiResponse<AddBranchPincodesResponse> addPincodes(@PathVariable UUID id,
                                                              @Valid @RequestBody AddBranchPincodesRequest request) {
        return ApiResponse.success(
                pincodeMappingService.addPincodes(id, request.pincodeIds()), "Pincodes mapped");
    }

    @DeleteMapping("/{id}/pincodes/{mappingId}")
    @Operation(summary = "Remove a pincode from this branch",
            description = "`COMPANY_ADMIN` only. Soft delete of the mapping row only — the "
                    + "branch and the pincode itself are untouched.")
    public ResponseEntity<ApiResponse<Void>> removePincode(@PathVariable UUID id,
                                                            @PathVariable UUID mappingId) {
        pincodeMappingService.removePincode(id, mappingId);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success("Pincode unmapped"));
    }

    @GetMapping("/by-pincode/{pincodeId}")
    @Operation(summary = "The branch mapped to a pincode, if any",
            description = "Resolves `branch_pincode_mapping`'s one-branch-per-pincode rule "
                    + "for the caller's company — Shipment Booking's Destination Pincode "
                    + "field uses this to auto-select Delivery Branch. `data` is absent when "
                    + "the pincode isn't mapped to any branch yet. `isAuthenticated()`.")
    public ApiResponse<BranchSummaryResponse> branchForPincode(@PathVariable UUID pincodeId) {
        return ApiResponse.success(
                pincodeMappingService.findBranchForPincode(pincodeId).map(mapper::toSummary).orElse(null));
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
        Sort sort = orders.isEmpty() ? Sort.by(Sort.Order.asc("branchCode")) : Sort.by(orders);
        return PageRequest.of(pageable.getPageNumber(), size, sort);
    }
}
