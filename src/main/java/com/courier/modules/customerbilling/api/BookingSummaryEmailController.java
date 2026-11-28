package com.courier.modules.customerbilling.api;

import com.courier.modules.customerbilling.application.BookingSummaryEmailService;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** The nightly booking summary e-mail, sent on demand for one business customer (company admin). */
@RestController
@RequestMapping("/api/v1/booking-summary-email")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Booking Summary E-mail", description = "Send a business customer's daily booking e-mail now")
public class BookingSummaryEmailController {

    private final BookingSummaryEmailService service;

    /** {@code to}: where to send (blank = the customer's address); {@code day}: whose bookings (blank = today, IST). */
    public record SendNowRequest(@Size(max = 255) String to, LocalDate day) {
    }

    @PostMapping("/{customerId}/send-now")
    @Operation(summary = "Send a business customer's daily booking e-mail now",
            description = "Same e-mail as the 23:30 run, for the day's window (23:30 the day before → 23:30, or now). Not "
                    + "recorded as the nightly send, so tonight's e-mail still goes out. Company admin.")
    public ApiResponse<BookingSummaryEmailService.SendNowResult> sendNow(@PathVariable UUID customerId,
                                                                         @RequestBody(required = false) SendNowRequest request) {
        BookingSummaryEmailService.SendNowResult r = service.sendNow(customerId,
                request == null ? null : request.to(), request == null ? null : request.day());
        return ApiResponse.success(r, "Sent to %s (%d shipment%s)".formatted(r.to(), r.bookings(), r.bookings() == 1 ? "" : "s"));
    }
}
