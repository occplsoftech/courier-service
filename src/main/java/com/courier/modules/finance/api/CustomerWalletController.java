package com.courier.modules.finance.api;

import com.courier.modules.finance.application.command.CustomerCreditCommand;
import com.courier.modules.finance.application.command.CustomerDebitCommand;
import com.courier.modules.finance.api.dto.CustomerWalletDebitRequest;
import com.courier.modules.finance.api.dto.CustomerWalletCreditRequest;
import com.courier.modules.finance.api.dto.CustomerWalletResponse;
import com.courier.modules.finance.api.dto.WalletTransactionResponse;
import com.courier.modules.finance.application.CustomerWalletService;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.domain.ReferenceType;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Staff view of a customer's wallet. The customer portal has its own endpoints. */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/wallet")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer wallet", description = "A business customer's prepaid wallet (staff)")
public class CustomerWalletController {

    private final CustomerWalletService service;
    private final WalletService walletService;
    private final WalletMapper mapper;

    @GetMapping
    @Operation(summary = "A customer's wallet",
            description = "`COMPANY_ADMIN`/`FINANCE_USER` only. Returns the customer's wallet, "
                    + "opening it first if the customer does not have one yet. 404 for a customer "
                    + "of another company.")
    public ApiResponse<CustomerWalletResponse> wallet(@PathVariable UUID customerId) {
        return ApiResponse.success(CustomerWalletResponse.of(service.getForCustomer(customerId)));
    }

    @PostMapping("/credit")
    @Operation(summary = "Credit a customer's wallet",
            description = "`COMPANY_ADMIN` only. Writes a CR entry and raises the balance in one "
                    + "transaction (for example a bank transfer received). The reason defaults to "
                    + "MCR and must be creditable. 404 for a customer of another company.")
    public ApiResponse<WalletTransactionResponse> credit(@PathVariable UUID customerId,
                                                         @Valid @RequestBody CustomerWalletCreditRequest request) {
        return ApiResponse.success(mapper.toResponse(walletService.creditForCustomer(
                new CustomerCreditCommand(customerId, request.amount(), request.subTransactionType(),
                        ReferenceType.MANUAL, request.referenceId(), request.remarks()))), "Wallet credited");
    }

    @PostMapping("/debit")
    @Operation(summary = "Debit a customer's wallet",
            description = "`COMPANY_ADMIN` only. Writes a DR entry and lowers the balance in one "
                    + "transaction (a correction, or a charge raised outside the portal). The reason "
                    + "defaults to MDB and must be debitable. Refused when the balance is short "
                    + "(no overdraft). 404 for a customer of another company.")
    public ApiResponse<WalletTransactionResponse> debit(@PathVariable UUID customerId,
                                                        @Valid @RequestBody CustomerWalletDebitRequest request) {
        return ApiResponse.success(mapper.toResponse(walletService.debitForCustomer(
                new CustomerDebitCommand(customerId, request.amount(), request.subTransactionType(),
                        ReferenceType.MANUAL, request.referenceId(), request.remarks()))), "Wallet debited");
    }

    @GetMapping("/transactions")
    @Operation(summary = "A customer's wallet ledger, newest first",
            description = "Same access as the wallet itself. `size` is capped at 100.")
    public ApiResponse<PageResponse<WalletTransactionResponse>> transactions(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(PageResponse.from(
                service.ledgerForCustomer(customerId, page, size), mapper::toResponse));
    }
}
