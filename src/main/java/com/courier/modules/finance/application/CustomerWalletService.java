package com.courier.modules.finance.application;

import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletTransaction;
import org.springframework.data.domain.Page;

import java.util.UUID;

/**
 * Customer wallets: the prepaid wallet of a business customer (an e-commerce portal client).
 *
 * <p>Same wallet, same balance / hold / ledger rules as a branch wallet — only the owner
 * differs ({@link com.courier.modules.finance.domain.WalletOwnerType}). This first part of the
 * feature opens the wallet and reads it; recharge, top-up requests and paying for bookings
 * build on it in the next parts and reuse {@link WalletServiceImpl}'s money paths.
 */
public interface CustomerWalletService {

    /**
     * The customer's wallet, opened if it does not exist yet. Idempotent.
     *
     * <p>No {@code @PreAuthorize}, on purpose: it is called from the event listener that opens
     * a new business customer's wallet (whoever created the customer, whatever their role) and
     * from the customer portal after it has resolved the acting customer from the signed-in
     * account. Never expose it through a controller that takes a customer id from the caller —
     * that is {@link #getForCustomer}.
     *
     * @param companyId  the company the customer belongs to
     * @param customerId the customer; must exist in that company
     */
    Wallet provisionForCustomer(UUID companyId, UUID customerId);

    /**
     * Staff read: a customer's wallet (opening it if the customer predates wallets).
     * Company admins and finance users only; the customer must belong to the caller's company.
     */
    Wallet getForCustomer(UUID customerId);

    /** Staff read: the customer's ledger, newest first. Same access as {@link #getForCustomer}. */
    Page<WalletTransaction> ledgerForCustomer(UUID customerId, int page, int size);

    /**
     * The ledger of a wallet already resolved by the caller (the customer portal passes the
     * wallet of the customer it authenticated). Newest first; {@code size} is capped.
     */
    Page<WalletTransaction> ledgerOfWallet(UUID companyId, UUID walletId, int page, int size);
}
