package com.courier.modules.shipment.api;

import com.courier.modules.shipment.api.dto.ShipmentPaymentResponse;
import com.courier.modules.shipment.application.ShipmentPaymentService;
import com.courier.modules.shipment.domain.ShipmentPayment;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Shipment payments", description = "How the customer pays a Paid booking: Razorpay QR, cash or UPI QR")
@SecurityRequirement(name = "bearerAuth")
public class ShipmentPaymentController {

    private final ShipmentPaymentService service;

    @GetMapping("/shipments/{id}/payment")
    @Operation(summary = "The shipment's payment; a pending Razorpay payment is checked with Razorpay first")
    public ResponseEntity<ApiResponse<ShipmentPaymentResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.status(id).map(ShipmentPaymentResponse::of).orElse(null)));
    }

    @PostMapping("/shipments/{id}/payment/qr")
    @Operation(summary = "Razorpay QR for the shipment's final amount (the open one, or a new one once it expired)")
    public ResponseEntity<ApiResponse<ShipmentPaymentResponse>> qr(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ShipmentPaymentResponse.of(service.openQr(id))));
    }

    @GetMapping("/shipment-payments")
    @Operation(summary = "Payments of Paid bookings, newest first; status=PENDING lists Razorpay payments still due")
    public ResponseEntity<ApiResponse<PageResponse<ShipmentPaymentResponse>>> list(
            @RequestParam(required = false) ShipmentPayment.Status status,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = service.search(status, branchId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)))
                .map(ShipmentPaymentResponse::of);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }
}
