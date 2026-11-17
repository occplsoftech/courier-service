package com.courier.modules.platformbookings.api;

import com.courier.modules.platformbookings.application.PlatformBookingsService;
import com.courier.modules.platformbookings.application.PlatformBookingsService.CarriedBy;
import com.courier.modules.platformbookings.application.PlatformBookingsService.Filter;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** The super admin's list of every company's bookings and who carries each one. */
@RestController
@RequestMapping("/api/v1/platform/bookings")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Platform")
public class PlatformBookingsController {

    private final PlatformBookingsService service;

    @GetMapping
    @Operation(summary = "Every booking of every company, newest first (super admin)",
            description = "carriedBy OCCPL / FORWARDED / BOOKED_WITH; providerCode (e.g. SHIPYAARI) and courier (a courier named in the "
                    + "service, e.g. Delhivery) narrow to one carrier as the totals list them. Dates are booking dates (IST), inclusive.")
    public ApiResponse<PlatformBookingsService.Page> list(@RequestParam(required = false) UUID companyId,
                                                         @RequestParam(required = false) CarriedBy carriedBy,
                                                         @RequestParam(required = false) String providerCode,
                                                         @RequestParam(required = false) String courier,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                         @RequestParam(required = false) String search,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(service.list(filter(companyId, carriedBy, providerCode, courier, from, to, search), page, size));
    }

    @GetMapping("/export")
    @Operation(summary = "The same list as an Excel file (up to 20,000 rows)")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) UUID companyId,
                                         @RequestParam(required = false) CarriedBy carriedBy,
                                         @RequestParam(required = false) String providerCode,
                                         @RequestParam(required = false) String courier,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String search) {
        byte[] file = service.export(filter(companyId, carriedBy, providerCode, courier, from, to, search));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"all-bookings-" + LocalDate.now() + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    private static Filter filter(UUID companyId, CarriedBy carriedBy, String providerCode, String courier, LocalDate from, LocalDate to,
                                 String search) {
        return new Filter(companyId, carriedBy, blank(providerCode), blank(courier), from, to, blank(search));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
