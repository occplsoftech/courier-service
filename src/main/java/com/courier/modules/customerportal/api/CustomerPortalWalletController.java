package com.courier.modules.customerportal.api;

import com.courier.modules.finance.application.command.CustomerRechargeOrderCommand;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.finance.api.WalletMapper;
import com.courier.modules.finance.api.TopupRequestMapper;
import com.courier.modules.finance.api.dto.CustomerRechargeConfirmRequest;
import com.courier.modules.finance.api.dto.CustomerRechargeRequest;
import com.courier.modules.finance.api.dto.CustomerTopupRequestRequest;
import com.courier.modules.finance.api.dto.CustomerWalletResponse;
import com.courier.modules.finance.api.dto.ProofImageUploadResponse;
import com.courier.modules.finance.api.dto.RechargeOrderResponse;
import com.courier.modules.finance.api.dto.TopupRequestResponse;
import com.courier.modules.finance.api.dto.WalletTransactionResponse;
import com.courier.modules.finance.application.CustomerWalletService;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.WalletTopupRequestService;
import com.courier.modules.finance.application.payment.PaymentGatewayPort;
import com.courier.modules.finance.domain.Wallet;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's own wallet. The customer comes from the signed-in account, never
 * from the request, so one customer can never ask for another's wallet.
 */
@RestController
@RequestMapping("/api/v1/customer-portal/wallet")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal", description = "Business customers: profile, rates, bookings, tracking, wallet")
public class CustomerPortalWalletController {

    private final CurrentCustomerResolver currentCustomer;
    private final CustomerWalletService wallets;
    private final WalletService walletService;
    private final WalletTopupRequestService topupRequests;
    private final TopupRequestMapper topupMapper;
    private final WalletMapper mapper;

    @GetMapping
    @Operation(summary = "The signed-in customer's wallet",
            description = "Balance, hold and status. Opened on first use if the customer has none yet.")
    public ApiResponse<CustomerWalletResponse> wallet() {
        return ApiResponse.success(CustomerWalletResponse.of(ownWallet(currentCustomer.require())));
    }

    @GetMapping("/transactions")
    @Operation(summary = "The signed-in customer's wallet ledger, newest first", description = "`size` is capped at 100.")
    public ApiResponse<PageResponse<WalletTransactionResponse>> transactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ActingCustomer me = currentCustomer.require();
        Wallet wallet = ownWallet(me);
        return ApiResponse.success(PageResponse.from(
                wallets.ledgerOfWallet(me.companyId(), wallet.getId(), page, size), mapper::toResponse));
    }

    private Wallet ownWallet(ActingCustomer me) {
        return wallets.provisionForCustomer(me.companyId(), me.customerId());
    }

    // ------------------------------------------------------------------ money in

    @PostMapping("/recharge/order")
    @Operation(summary = "Open an online recharge",
            description = "Step one: the server fixes the amount at the payment gateway and returns "
                    + "the order for checkout. Nothing is credited yet. 422 if the company has no "
                    + "payment gateway configured; use a top-up request instead.")
    public ApiResponse<RechargeOrderResponse> openRecharge(@Valid @RequestBody CustomerRechargeRequest request) {
        ActingCustomer me = currentCustomer.require();
        PaymentGatewayPort.GatewayOrder order = walletService.openRechargeForCustomer(
                new CustomerRechargeOrderCommand(me.customerId(), request.amount()));
        Wallet wallet = ownWallet(me);
        return ApiResponse.success(
                mapper.toResponse(order, wallet.getId(), wallet.getWalletNumber(), null), "Recharge order created");
    }

    @PostMapping("/recharge")
    @Operation(summary = "Complete an online recharge",
            description = "Step two: settles the payment checkout produced. The signature is verified and "
                    + "the gateway is asked what was actually paid; that amount is credited, not the one in "
                    + "the request. Idempotent on `paymentReference`.")
    public ApiResponse<WalletTransactionResponse> recharge(@Valid @RequestBody CustomerRechargeConfirmRequest request) {
        ActingCustomer me = currentCustomer.require();
        return ApiResponse.success(mapper.toResponse(walletService.completeRechargeForCustomer(
                me.customerId(), request.gatewayOrderId(), request.paymentReference(), request.signature())),
                "Wallet recharged");
    }

    @PostMapping(value = "/topup-requests/upload-proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload proof of payment for a top-up request",
            description = "A JPEG/PNG/WEBP/HEIC image (bank transfer screenshot, cash receipt). Returns the "
                    + "URL to pass as `proofImageUrl` when raising the request.")
    public ApiResponse<ProofImageUploadResponse> uploadProof(@RequestParam("file") MultipartFile file) {
        currentCustomer.require();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("The uploaded file could not be read. Please retry.");
        }
        String url = topupRequests.uploadProofImageForCustomer(new WalletTopupRequestService.UploadProofImageCommand(
                content, file.getOriginalFilename(), file.getContentType()));
        return ApiResponse.success(new ProofImageUploadResponse(url), "File uploaded");
    }

    @PostMapping("/topup-requests")
    @Operation(summary = "Ask the company to credit your wallet",
            description = "For a bank transfer or cash payment. Nothing is credited until a company admin "
                    + "approves the request. Proof of payment is required.")
    public ApiResponse<TopupRequestResponse> requestTopup(@Valid @RequestBody CustomerTopupRequestRequest request) {
        ActingCustomer me = currentCustomer.require();
        return ApiResponse.success(topupMapper.toResponse(topupRequests.createForCustomer(
                me.customerId(), request.amount(), request.remarks(), request.proofImageUrl())),
                "Top-up request raised");
    }

    @GetMapping("/topup-requests")
    @Operation(summary = "Your top-up requests, newest first", description = "`size` is capped at 100.")
    public ApiResponse<PageResponse<TopupRequestResponse>> topupRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ActingCustomer me = currentCustomer.require();
        return ApiResponse.success(PageResponse.from(
                topupRequests.searchForCustomer(me.customerId(), page, size), topupMapper::toResponse));
    }
}
