package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.application.PortalInvoiceService;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** The signed-in customer's invoices, payments and statement of account (read-only; staff create invoices and record payments). */
@RestController
@RequestMapping("/api/v1/customer-portal/invoices")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal")
public class CustomerPortalInvoiceController {

    private final PortalInvoiceService service;

    @GetMapping
    @Operation(summary = "Outstanding amount and the customer's approved invoices, newest first",
            description = "`due` is the total minus the part paid at booking (prepaid); `balance` is what is still owed on the invoice.")
    public ApiResponse<PortalInvoiceService.Summary> summary() {
        return ApiResponse.success(service.summary());
    }

    @GetMapping("/statement")
    @Operation(summary = "Statement of account",
            description = "`from` / `to`: yyyy-MM-dd (default: this year to today), at most 366 days. Opening balance, each invoice and "
                    + "payment with the running balance, closing balance.")
    public ApiResponse<PortalInvoiceService.Statement> statement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(service.statement(from, to));
    }

    @GetMapping("/statement/export")
    @Operation(summary = "The statement of account as Excel")
    public ResponseEntity<byte[]> statementExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("statement.xlsx").build().toString())
                .body(service.statementSheet(from, to));
    }

    @GetMapping("/{invoiceId}")
    @Operation(summary = "One of the customer's approved invoices: its shipment lines and payments (404 for anyone else's)")
    public ApiResponse<PortalInvoiceService.InvoiceDetail> detail(@PathVariable UUID invoiceId) {
        return ApiResponse.success(service.detail(invoiceId));
    }
}
