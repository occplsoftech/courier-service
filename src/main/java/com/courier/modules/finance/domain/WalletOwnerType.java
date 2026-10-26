package com.courier.modules.finance.domain;

/**
 * Who a wallet belongs to. Exactly one owner, fixed for the wallet's life.
 *
 * <p>The balance, hold and ledger rules are the same for both; what differs is which
 * business rules may touch the wallet. Branch-only rules (commission, DRS charge, COD and
 * to-pay settlement at a branch) never apply to a {@link #CUSTOMER} wallet.
 */
public enum WalletOwnerType {

    /** A courier-company branch (or hub, partner). The original and default owner. */
    BRANCH,

    /** A business customer of the company — an e-commerce portal client. */
    CUSTOMER
}
