package com.courier.modules.tariff.api;

import com.courier.modules.tariff.application.CustomerTariffImportService;
import com.courier.modules.tariff.application.CustomerTariffImportService.CustomerCard;
import com.courier.modules.tariff.application.CustomerTariffImportService.UploadOptions;
import com.courier.modules.tariff.application.CustomerTariffImportService.UploadResult;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A business customer's own zone-tariff rate card, uploaded from Excel. Uploads are {@code COMPANY_ADMIN} (on the service). */
@RestController
@RequestMapping("/api/v1/customer-tariff/{customerId}")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer Tariff", description = "A business customer's own rate card from their old-software rate sheet")
public class CustomerTariffController {

    private final CustomerTariffImportService service;

    @GetMapping("/cards")
    @Operation(summary = "The customer's own rate cards", description = "One per from zone; open one with /tariff-rate-cards/{id}.")
    public ApiResponse<List<CustomerCard>> cards(@PathVariable UUID customerId) {
        return ApiResponse.success(service.cardsOf(customerId));
    }

    @PostMapping(value = "/upload/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Check a customer's rate sheet (saves nothing)",
            description = "Columns From zone, To zone, State, City, Mode, Product, Applicable From, Weight From, Weight To, Rate, "
                    + "Rate Type. additionStepKg: what one \"Addition 500GM\" rate buys, 0.5 (default) or 1. fromZoneId: "
                    + "default the sheet's. Lanes whose Fixed / Per Kg look swapped are errors.")
    public ApiResponse<UploadResult> preview(@PathVariable UUID customerId, @RequestParam("file") MultipartFile file,
                                             @RequestParam(required = false) UUID fromZoneId,
                                             @RequestParam(required = false) BigDecimal additionStepKg) {
        return ApiResponse.success(service.preview(customerId, new UploadOptions(fromZoneId, additionStepKg), file), "Sheet checked");
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a customer's rate sheet",
            description = "All or nothing. Creates the customer's card for the from zone on the first upload (charges copied from "
                    + "the company's card), replaces the lanes in the sheet, activates the card and switches the customer to "
                    + "business rates.")
    public ApiResponse<UploadResult> upload(@PathVariable UUID customerId, @RequestParam("file") MultipartFile file,
                                            @RequestParam(required = false) UUID fromZoneId,
                                            @RequestParam(required = false) BigDecimal additionStepKg) {
        UploadResult r = service.importFile(customerId, new UploadOptions(fromZoneId, additionStepKg), file);
        return ApiResponse.success(r, "Uploaded %d lane(s) to %s".formatted(r.sheet().lanes().size(), r.sheet().rateCardCode()));
    }
}
