package com.courier.modules.manifest.api;

import com.courier.modules.manifest.application.CoLoaderService;
import com.courier.modules.manifest.domain.CoLoader;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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

@RestController
@RequestMapping("/api/v1/co-loaders")
@RequiredArgsConstructor
@Tag(name = "Co-Loaders", description = "Outside transporters / agents used at outscan and local delivery")
@SecurityRequirement(name = "bearerAuth")
public class CoLoaderController {

    private final CoLoaderService service;

    public record CoLoaderRequest(@Size(max = 150) String name, @Size(max = 150) String contactPerson,
                                  @Size(max = 20) String phone, @Size(max = 500) String notes, Boolean active) {
    }

    public record CoLoaderResponse(UUID id, String name, String contactPerson, String phone, String notes, boolean active) {
        static CoLoaderResponse of(CoLoader c) {
            return new CoLoaderResponse(c.getId(), c.getName(), c.getContactPerson(), c.getPhone(), c.getNotes(), c.isActive());
        }
    }

    @GetMapping
    @Operation(summary = "Co-loaders, by name; all=true includes switched-off ones")
    public ApiResponse<List<CoLoaderResponse>> list(@RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.success(service.list(all).stream().map(CoLoaderResponse::of).toList());
    }

    @PostMapping
    @Operation(summary = "Add a co-loader (company admin / branch manager)")
    public ApiResponse<CoLoaderResponse> create(@Valid @RequestBody CoLoaderRequest r) {
        return ApiResponse.success(CoLoaderResponse.of(service.create(r.name(), r.contactPerson(), r.phone(), r.notes())), "Co-loader added");
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change a co-loader, or switch it on / off")
    public ApiResponse<CoLoaderResponse> update(@PathVariable UUID id, @Valid @RequestBody CoLoaderRequest r) {
        return ApiResponse.success(CoLoaderResponse.of(service.update(id, r.name(), r.contactPerson(), r.phone(), r.notes(), r.active())), "Co-loader saved");
    }
}
