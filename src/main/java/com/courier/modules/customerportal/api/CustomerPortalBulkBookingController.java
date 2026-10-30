package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.PortalBulkBookingService;
import com.courier.modules.customerportal.rates.application.PortalBulkBookingService.BookItem;
import com.courier.modules.customerportal.rates.application.PortalBulkBookingService.BookResult;
import com.courier.modules.customerportal.rates.application.PortalBulkBookingService.Preview;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Booking many shipments from one Excel sheet: template, check-and-price, then book what the customer confirmed. */
@RestController
@RequestMapping("/api/v1/customer-portal/bulk-bookings")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal")
public class CustomerPortalBulkBookingController {

    private final PortalBulkBookingService service;

    public record BookRequest(List<BookItem> items) {
    }

    @GetMapping("/template")
    @Operation(summary = "The Excel template to fill in")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("bulk-booking-template.xlsx").build().toString())
                .body(service.template());
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Check and price every row of an uploaded sheet (books nothing)",
            description = "Up to 100 rows. Each row is priced at the customer's business rate if there is one, otherwise the cheapest courier the "
                    + "customer may use. Rows with `ok` false carry the reason in `error`.")
    public ApiResponse<Preview> preview(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(service.preview(file.getInputStream()));
    }

    public record RowRequest(Integer row, java.util.Map<String, String> fields) {
    }

    @PostMapping("/row")
    @Operation(summary = "Re-check one edited row",
            description = "Send the row's `fields` (keyed by the template headings, as returned by the preview). Same checks and pricing as the upload.")
    public ApiResponse<PortalBulkBookingService.PreviewRow> row(@RequestBody RowRequest request) {
        return ApiResponse.success(service.checkRow(request.row() == null ? 0 : request.row(), request.fields()));
    }

    @PostMapping("/book")
    @Operation(summary = "Book the confirmed rows",
            description = "Send the `quoteId` and `optionId` of each row to book from the preview. Rows book independently; each result says booked or why not.")
    public ApiResponse<List<BookResult>> book(@RequestBody BookRequest request) {
        return ApiResponse.success(service.book(request.items()));
    }
}
