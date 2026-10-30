package com.courier.modules.customerpricing.api;

import com.courier.modules.customerpricing.application.CustomerRateImportService;
import com.courier.modules.customerpricing.application.CustomerRateImportService.ImportPreview;
import com.courier.modules.customerpricing.application.CustomerRateImportService.ImportResult;
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

/** Set up a business customer's zones and rate cards from one Excel sheet (COMPANY_ADMIN, enforced on the service). */
@RestController
@RequestMapping("/api/v1/customer-rates/import")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer Rates", description = "Per-zone rate cards of business customers")
public class CustomerRateImportController {

    private final CustomerRateImportService service;

    @GetMapping("/template")
    @Operation(summary = "The Excel template for importing a customer's rates",
            description = "One row per zone and service type, up to five weight slabs per row; lists the company's service type codes.")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("customer-rates-template.xlsx").build().toString())
                .body(service.template());
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Check an uploaded rate sheet row by row (saves nothing)")
    public ApiResponse<ImportPreview> preview(@RequestParam UUID customerId, @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(service.preview(customerId, file.getInputStream()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import the sheet: create the zones and save every rate card",
            description = "All or nothing: refused, with nothing saved, if any row has an error. A zone the customer already has is reused and a "
                    + "rate card that already exists is replaced. The customer is then switched to their business rates.")
    public ApiResponse<ImportResult> apply(@RequestParam UUID customerId, @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(service.apply(customerId, file.getInputStream()), "Rates imported");
    }
}
