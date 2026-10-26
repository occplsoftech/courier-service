package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.finance.api.WalletMapper;
import com.courier.modules.finance.api.dto.CustomerWalletResponse;
import com.courier.modules.finance.api.dto.WalletTransactionResponse;
import com.courier.modules.finance.application.CustomerWalletService;
import com.courier.modules.finance.domain.Wallet;
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
}
