package com.courier.modules.master.api;

import com.courier.modules.master.application.ServiceabilityService;
import com.courier.modules.master.domain.UnserviceableRequest;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Can a destination pincode and area be booked, and the report of the ones bookings asked for but OCCPL does not serve. */
@RestController
@RequestMapping("/api/v1/serviceability")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Serviceability")
public class ServiceabilityController {

    private final ServiceabilityService service;

    @GetMapping("/check")
    @Operation(summary = "Is this destination pincode (and area) serviceable?",
            description = "Called by the booking screen when an area is picked. An unserviceable answer is counted for the report "
                    + "(pincode + area, the booking branch and user).")
    public ApiResponse<ServiceabilityService.Check> check(@RequestParam String pincode, @RequestParam(required = false) UUID areaId,
                                                          @RequestParam(required = false) UUID branchId) {
        return ApiResponse.success(service.check(pincode, areaId, branchId, UnserviceableRequest.Source.STAFF));
    }

    @GetMapping("/unserviceable")
    @Operation(summary = "Unserviceable requests, most asked first",
            description = "open=true still unavailable, open=false switched on since; since = last asked on or after (IST).")
    public ApiResponse<PageResponse<ServiceabilityService.ReportRow>> report(@RequestParam(required = false) Boolean open,
                                                                           @RequestParam(required = false) String search,
                                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
                                                                           @RequestParam(defaultValue = "0") int page,
                                                                           @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(PageResponse.from(service.report(open, search, since, page, size)));
    }

    @GetMapping("/unserviceable/export")
    @Operation(summary = "The report as an Excel file")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Boolean open, @RequestParam(required = false) String search,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"unserviceable-requests-" + LocalDate.now() + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.export(open, search, since));
    }

    @PostMapping("/unserviceable/{id}/turn-on")
    @Operation(summary = "Switch this pincode and area on (company admin)",
            description = "Makes the area serviceable, and its pincode too when it was off (prepaid on). A pincode or area not in Masters "
                    + "cannot be switched on here (add it first).")
    public ApiResponse<ServiceabilityService.ReportRow> turnOn(@PathVariable UUID id) {
        return ApiResponse.success(service.turnOn(id), "Switched on");
    }
}
