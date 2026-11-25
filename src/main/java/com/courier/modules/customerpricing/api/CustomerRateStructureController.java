package com.courier.modules.customerpricing.api;

import com.courier.modules.customerpricing.application.CustomerRateStructureService;
import com.courier.modules.customerpricing.application.CustomerRateStructureService.ImportResult;
import com.courier.modules.customerpricing.application.CustomerRateStructureService.Parsed;
import com.courier.modules.customerpricing.application.CustomerRateStructureService.Services;
import com.courier.modules.customerpricing.application.CustomerRateStructureService.StructureView;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * A business customer's rate structure, imported from the printed rate card's layout: air courier per destination
 * group, cargo per origin region and destination group by surface and by air, and a minimum chargeable weight.
 */
@RestController
@RequestMapping("/api/v1/customer-rates/structure")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer Rates", description = "Per-zone rate cards of business customers")
public class CustomerRateStructureController {

    private final CustomerRateStructureService service;

    @GetMapping("/template")
    @Operation(summary = "The rate structure Excel template (the rate card layout with standard rates)")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("rate-structure-template.xlsx").build().toString())
                .body(service.template());
    }

    @GetMapping
    @Operation(summary = "A customer's stored rate structure")
    public ApiResponse<StructureView> view(@RequestParam UUID customerId) {
        return ApiResponse.success(service.view(customerId));
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Read and check a rate structure sheet (saves nothing)")
    public ApiResponse<Parsed> preview(@RequestParam UUID customerId,
                                       @RequestParam(required = false) UUID airCourierServiceTypeId,
                                       @RequestParam(required = false) UUID cargoSurfaceServiceTypeId,
                                       @RequestParam(required = false) UUID cargoAirServiceTypeId,
                                       @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(service.preview(customerId,
                new Services(airCourierServiceTypeId, cargoSurfaceServiceTypeId, cargoAirServiceTypeId), file.getInputStream()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import a rate structure sheet",
            description = "All or nothing. Replaces the customer's structure for the chosen services and switches the customer "
                    + "to their business rates.")
    public ApiResponse<ImportResult> apply(@RequestParam UUID customerId,
                                           @RequestParam(required = false) UUID airCourierServiceTypeId,
                                           @RequestParam(required = false) UUID cargoSurfaceServiceTypeId,
                                           @RequestParam(required = false) UUID cargoAirServiceTypeId,
                                           @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(service.apply(customerId,
                new Services(airCourierServiceTypeId, cargoSurfaceServiceTypeId, cargoAirServiceTypeId), file.getInputStream()),
                "Rate structure imported");
    }
}
