package com.courier.modules.finance.application;

import com.courier.modules.finance.application.command.CustomerRechargeOrderCommand;
import com.courier.modules.finance.application.command.CustomerCreditCommand;
import com.courier.modules.finance.application.command.BookingDebitCommand;
import com.courier.modules.finance.application.command.CodDeliveryDebitCommand;
import com.courier.modules.finance.application.command.CommissionCreditCommand;
import com.courier.modules.finance.application.command.CreditCommand;
import com.courier.modules.finance.application.command.DebitCommand;
import com.courier.modules.finance.application.command.DeliveryWeightCommissionCreditCommand;
import com.courier.modules.finance.application.command.DrsChargeCreditCommand;
import com.courier.modules.finance.application.command.RechargeCommand;
import com.courier.modules.finance.application.command.ShipmentReversalCommand;
import com.courier.modules.finance.application.event.WalletEvent;
import com.courier.modules.finance.application.payment.PaymentGatewayPort;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.PaymentStatus;
import com.courier.modules.finance.domain.RechargeMode;
import com.courier.modules.finance.domain.RechargeOrder;
import com.courier.modules.finance.domain.RechargeOrderRepository;
import com.courier.modules.finance.domain.PendingCommissionPort;
import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.SubTransactionType;
import com.courier.modules.finance.domain.TransactionType;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletNumberGenerator;
import com.courier.modules.finance.domain.WalletRepository;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.finance.domain.WalletTransactionCriteria;
import com.courier.modules.finance.domain.WalletTransactionRepository;
import com.courier.modules.finance.domain.WalletTransactionSpecifications;
import com.courier.modules.finance.infrastructure.CompanyPaymentGatewayResolver;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ForbiddenException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Branch wallet use cases.
 *
 * <p>Three things hold this module together, and none of them is negotiable.
 *
 * <ol>
 *   <li><b>Balance and ledger move together or not at all.</b> Every mutation goes through
 *       {@link #post}, which writes the {@link WalletTransaction} and applies the balance
 *       inside one transaction. There is no other path, and no method here sets a balance.</li>
 *   <li><b>The wallet row is locked, not versioned.</b> Money paths load through
 *       {@code lockByBranchIdWithinCompany} ({@code SELECT ... FOR UPDATE}). Two concurrent
 *       bookings against one branch serialise; with optimistic locking one would lose and a
 *       customer would see a failed payment for a race they had no part in.</li>
 *   <li><b>The amount credited comes from the gateway, never from the client.</b> See
 *       {@link #completeRecharge}.</li>
 * </ol>
 *
 * <p>Per-method {@code @PreAuthorize} gates the role tier; the finer "your branch" is
 * enforced in code, because it depends on the caller's own placement. Reads out of scope
 * answer 404 (don't reveal another branch exists); writes out of scope answer 403 (the
 * caller reached a branch of their own company they simply may not act on) — the same split
 * {@code BranchServiceImpl} makes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private static final String ENTITY = "Wallet";
    private static final String LEDGER_ENTITY = "WalletTransaction";

    private static final String COMPANY_ADMIN_ONLY = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String AUTHENTICATED = "isAuthenticated()";

    /** A customer acting on their own wallet, through the customer portal. The portal passes the
     *  customer it authenticated; this gate is the second lock, so a staff token can never reach
     *  these entry points even if one were wired to a controller by mistake. */
    private static final String CUSTOMER_ONLY = "hasRole('" + Roles.CUSTOMER + "')";

    /**
     * Whose wallet a money path is acting on: a branch's or a customer's, never both. The branch
     * paths build {@link #branch}; the customer paths build {@link #customer}. Everything after
     * the wallet is located — the row lock, {@link #post}, the audit and the events — is shared.
     */
    private record Owner(UUID branchId, UUID customerId) {

        static Owner branch(UUID branchId) {
            return new Owner(branchId, null);
        }

        static Owner customer(UUID customerId) {
            return new Owner(null, customerId);
        }

        static Owner of(RechargeOrder order) {
            return order.getCustomerId() != null ? customer(order.getCustomerId()) : branch(order.getBranchId());
        }

        boolean isCustomer() {
            return customerId != null;
        }

        UUID id() {
            return isCustomer() ? customerId : branchId;
        }
    }

    /** Company-wide financial reach — restricted to the two roles the Finance Report's
     *  nav entry itself is gated on, not every company user with wallet read access. */
    private static final String FINANCE_READERS = "hasAnyRole('" + Roles.COMPANY_ADMIN
            + "', '" + com.courier.modules.company.domain.DefaultRoleCatalog.FINANCE_USER + "')";

    /**
     * Moving a company's money is a company's own act.
     *
     * <p>A platform operator is excluded explicitly rather than by accident. They have no
     * branch, so {@code resolveBranchForWrite} would refuse them anyway — but "it happens
     * not to work" is not the same as "it is not allowed", and the day someone gives a
     * super admin a branch for testing, the accident stops protecting anything. A
     * recharge that a platform operator made is indistinguishable in the ledger from one
     * the branch made, which is precisely what a ledger exists to prevent.
     */
    private static final String COMPANY_USERS_ONLY =
            "isAuthenticated() and !hasRole('" + Roles.SUPER_ADMIN + "') "
                    + "and !hasRole('" + Roles.PLATFORM_ADMIN + "')";

    /** Wallet numbers are random; a collision is improbable, not impossible. */
    private static final int NUMBER_ATTEMPTS = 5;

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final BranchDirectoryPort branchDirectory;
    private final PendingCommissionPort pendingCommission;
    private final CompanyPaymentGatewayResolver paymentGatewayResolver;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final RechargeOrderTracker rechargeOrders;
    private final RechargeOrderRepository rechargeOrderRepository;
    private final CustomerWalletService customerWallets;

    // ------------------------------------------------------------- provisioning

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public Wallet getOrCreateForBranch(UUID branchId) {
        UUID companyId = requireCompany();
        requireBranchExists(branchId, companyId);

        Optional<Wallet> existing = walletRepository.findByBranchIdWithinCompany(branchId, companyId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = Wallet.builder()
                .walletNumber(nextWalletNumber())
                .branchId(branchId)
                .status(com.courier.modules.finance.domain.WalletStatus.ACTIVE)
                .availableBalance(Wallet.ZERO)
                .holdBalance(Wallet.ZERO)
                .currency(Wallet.DEFAULT_CURRENCY)
                .build();

        // A concurrent first access could race us here. uk_wallets_company_branch is the
        // arbiter: the loser gets a DataIntegrityViolationException, which the global
        // handler renders as a 409, and the retry finds the wallet the winner created.
        // Rare by construction — this happens at most once in a branch's lifetime.
        Wallet saved = walletRepository.save(wallet);

        log.info("Wallet {} created for branch {} in company {}",
                saved.getWalletNumber(), branchId, companyId);
        auditService.record(AuditAction.WALLET_CREATED, ENTITY, saved.getId(),
                Map.of("walletNumber", saved.getWalletNumber(), "branchId", branchId.toString()));
        eventPublisher.publishEvent(new WalletEvent.WalletCreated(
                saved.getId(), companyId, branchId, saved.getWalletNumber(), Instant.now()));

        return saved;
    }

    // -------------------------------------------------------------------- reads

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public Wallet getForBranch(UUID branchId) {
        UUID companyId = requireCompany();
        UUID resolved = resolveBranchForRead(branchId, companyId);
        // Not read-only: a branch created before this module exists without a wallet, and
        // "your wallet does not exist" is not an answer a branch user can act on.
        return getOrCreateForBranch(resolved);
    }

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public WalletSummary summarise(UUID branchId) {
        UUID companyId = requireCompany();
        UUID resolved = resolveBranchForRead(branchId, companyId);
        return summariseResolved(resolved, companyId);
    }

    @Override
    @Transactional
    @PreAuthorize(FINANCE_READERS)
    public List<WalletSummary> companySummary() {
        UUID companyId = requireCompany();
        return branchDirectory.listBranches(companyId).stream()
                .map(b -> summariseResolved(b.branchId(), companyId))
                .toList();
    }

    /** Shared by {@link #summarise} and {@link #companySummary} — one branch's wallet
     *  summary, given an already-resolved/authorised branch id. */
    private WalletSummary summariseResolved(UUID resolved, UUID companyId) {
        Wallet wallet = getOrCreateForBranch(resolved);

        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant startOfMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        BranchDirectoryPort.BranchRef branch =
                branchDirectory.findBranch(resolved, companyId).orElse(null);

        WalletTransaction lastTransaction = firstOf(
                transactionRepository.findRecent(wallet.getId(), companyId, PageRequest.of(0, 1)));
        WalletTransaction lastRecharge = firstOf(
                transactionRepository.findLatestSettledOfType(wallet.getId(), companyId,
                        SubTransactionType.WRC, PageRequest.of(0, 1)));

        PendingCommissionPort.PendingCommission pending =
                pendingCommission.pendingCommissionFor(resolved, companyId);

        return new WalletSummary(
                wallet,
                branch == null ? null : branch.branchCode(),
                branch == null ? null : branch.branchName(),
                sum(wallet, companyId, TransactionType.CR, startOfDay),
                sum(wallet, companyId, TransactionType.DR, startOfDay),
                sum(wallet, companyId, TransactionType.CR, startOfMonth),
                sum(wallet, companyId, TransactionType.DR, startOfMonth),
                sum(wallet, companyId, TransactionType.CR, Instant.EPOCH),
                sum(wallet, companyId, TransactionType.DR, Instant.EPOCH),
                sumCommission(wallet, companyId, startOfDay),
                pending.bookingPending(),
                pending.deliveryPending(),
                transactionRepository.countByWallet(wallet.getId(), companyId),
                lastTransaction == null ? null : lastTransaction.getCreatedAt(),
                lastRecharge == null ? null : lastRecharge.getAmount(),
                lastRecharge == null ? null : lastRecharge.getCreatedAt());
    }

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public Page<WalletTransaction> searchTransactions(UUID branchId,
                                                      WalletTransactionCriteria criteria,
                                                      Pageable pageable) {
        UUID companyId = requireCompany();
        UUID resolved = resolveBranchForRead(branchId, companyId);
        Wallet wallet = getOrCreateForBranch(resolved);

        // The wallet and company are pinned here, never taken from the request: a criteria
        // object that arrived with someone else's wallet id would be someone else's statement.
        WalletTransactionCriteria safe =
                (criteria == null ? WalletTransactionCriteria.none() : criteria)
                        .scopedTo(wallet.getId(), companyId);

        return transactionRepository.findAll(WalletTransactionSpecifications.matching(safe), pageable);
    }

    private static boolean isNotEmpty(java.util.Set<?> values) {
        return values != null && !values.isEmpty();
    }

    // ------------------------------------------------------- recharge attempts

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(COMPANY_USERS_ONLY)
    public Page<RechargeOrder> searchRechargeAttempts(UUID branchId, PaymentStatus status, RechargeMode mode,
                                                      java.time.LocalDate fromDate, java.time.LocalDate toDate,
                                                      String search, Pageable pageable) {
        UUID companyId = requireCompany();
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        boolean companyWide = isAdmin(caller) || caller.hasRole(
                com.courier.modules.company.domain.DefaultRoleCatalog.FINANCE_USER);
        UUID scopedBranch = companyWide ? branchId
                : ownBranch(caller, companyId).orElseThrow(() -> new BusinessRuleException(
                        "You are not assigned to a branch, so there is no wallet to report on."));
        if (companyWide && scopedBranch != null) {
            requireBranchExists(scopedBranch, companyId);
        }
        Instant from = fromDate == null ? null : fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = toDate == null ? null : toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String like = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase() + "%";

        return rechargeOrderRepository.findAll((root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> p = new java.util.ArrayList<>();
            p.add(cb.equal(root.get("companyId"), companyId));
            if (scopedBranch != null) p.add(cb.equal(root.get("branchId"), scopedBranch));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (mode != null) p.add(cb.equal(root.get("mode"), mode));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) p.add(cb.lessThan(root.get("createdAt"), to));
            if (like != null) {
                p.add(cb.or(cb.like(cb.lower(root.get("transactionNo")), like),
                        cb.like(cb.lower(root.get("gatewayOrderId")), like),
                        cb.like(cb.lower(root.get("gatewayPaymentId")), like)));
            }
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, pageable);
    }

    // ----------------------------------------------------------------- recharge

    @Override
    @Transactional
    @PreAuthorize(COMPANY_USERS_ONLY)
    public PaymentGatewayPort.GatewayOrder openRecharge(RechargeCommand command) {
        UUID companyId = requireCompany();
        PaymentGatewayPort paymentGateway = paymentGatewayResolver.resolve(companyId);
        UUID branchId = resolveBranchForWrite(command.branchId(), companyId);
        Wallet wallet = getOrCreateForBranch(branchId);
        requireOperational(wallet);

        return startRecharge(companyId, paymentGateway, wallet, Owner.branch(branchId), command.amount());
    }

    @Override
    @Transactional
    @PreAuthorize(CUSTOMER_ONLY)
    public PaymentGatewayPort.GatewayOrder openRechargeForCustomer(CustomerRechargeOrderCommand command) {
        UUID companyId = requireCompany();
        UUID customerId = command.customerId();
        PaymentGatewayPort paymentGateway = paymentGatewayResolver.resolve(companyId);
        Wallet wallet = customerWallets.provisionForCustomer(companyId, customerId);
        requireOperational(wallet);

        return startRecharge(companyId, paymentGateway, wallet, Owner.customer(customerId), command.amount());
    }

    /** Steps shared by a branch's and a customer's recharge once the wallet is known. */
    private PaymentGatewayPort.GatewayOrder startRecharge(UUID companyId, PaymentGatewayPort paymentGateway,
                                                          Wallet wallet, Owner owner, BigDecimal requested) {
        BigDecimal amount = requirePositiveAmount(requested);

        // Our own record first, with our own transaction number — committed before the
        // gateway is called, so an attempt is never lost to a gateway timeout or a crash,
        // and the number is the gateway receipt. It is a recharge-order row, deliberately not
        // a ledger entry: a ledger entry is a record of money that moved (see V102).
        String transactionNo = WalletNumberGenerator.transactionNumber();
        RechargeOrder attempt = owner.isCustomer()
                ? rechargeOrders.beginForCustomer(companyId, wallet.getId(), owner.customerId(), amount,
                        wallet.getCurrency(), paymentGateway.name(), transactionNo)
                : rechargeOrders.begin(companyId, wallet.getId(), owner.branchId(), amount,
                        wallet.getCurrency(), paymentGateway.name(), transactionNo);

        // The gateway echoes these notes back on the webhook; the webhook finds the wallet from them.
        Map<String, String> notes = new HashMap<>();
        notes.put("walletNumber", wallet.getWalletNumber());
        notes.put(owner.isCustomer() ? "customerId" : "branchId", owner.id().toString());
        notes.put("companyId", companyId.toString());
        notes.put("transactionNo", transactionNo);

        PaymentGatewayPort.GatewayOrder order;
        try {
            order = paymentGateway.createOrder(amount, wallet.getCurrency(), transactionNo, notes);
        } catch (RuntimeException e) {
            rechargeOrders.markFailed(attempt.getId(), "Gateway order was not created: " + e.getMessage());
            throw e;
        }
        rechargeOrders.attachGatewayOrder(attempt.getId(), order.orderId());

        log.info("Recharge {} (order {}) opened for wallet {} ({} {}) by {}",
                transactionNo, order.orderId(), wallet.getWalletNumber(), wallet.getCurrency(),
                amount.toPlainString(), currentActor());
        auditService.record(AuditAction.WALLET_RECHARGE_INITIATED, ENTITY, wallet.getId(),
                Map.of("walletNumber", wallet.getWalletNumber(),
                        "amount", amount.toPlainString(),
                        "gateway", order.gateway(),
                        "transactionNo", transactionNo,
                        "gatewayOrderId", order.orderId()));

        return order;
    }

    @Override
    @Transactional
    @PreAuthorize(COMPANY_USERS_ONLY)
    public WalletTransaction completeRecharge(RechargeCommand command) {
        UUID companyId = requireCompany();
        PaymentGatewayPort paymentGateway = paymentGatewayResolver.resolve(companyId);
        UUID branchId = resolveBranchForWrite(command.branchId(), companyId);

        return completeFor(companyId, paymentGateway, Owner.branch(branchId), command.paymentReference(),
                command.gatewayOrderId(), command.signature(), command.remarks());
    }

    @Override
    @Transactional
    @PreAuthorize(CUSTOMER_ONLY)
    public WalletTransaction completeRechargeForCustomer(UUID customerId, String gatewayOrderId,
                                                         String paymentReference, String signature) {
        UUID companyId = requireCompany();
        PaymentGatewayPort paymentGateway = paymentGatewayResolver.resolve(companyId);
        return completeFor(companyId, paymentGateway, Owner.customer(customerId), paymentReference,
                gatewayOrderId, signature, null);
    }

    /** Step two of a recharge, once the owner is known. The credited amount comes from the gateway. */
    private WalletTransaction completeFor(UUID companyId, PaymentGatewayPort paymentGateway, Owner owner,
                                          String paymentReference, String gatewayOrderId, String signatureValue,
                                          String remarks) {
        String paymentId = required(paymentReference, "paymentReference");
        String orderId = required(gatewayOrderId, "gatewayOrderId");
        String signature = required(signatureValue, "signature");

        // Idempotency, before anything else. A client that retries, a double-submitted
        // form and a webhook that fires twice must all credit the wallet exactly once.
        Optional<WalletTransaction> already =
                transactionRepository.findByPaymentReferenceWithinCompany(paymentId, companyId);
        if (already.isPresent()) {
            log.info("Recharge {} already recorded as {}; returning it unchanged",
                    paymentId, already.get().getTransactionNo());
            rechargeOrders.markSuccess(companyId, orderId, paymentId, already.get().getId());
            return already.get();
        }
        // Not ours, but recorded by someone. Refuse flatly rather than saying whose it is.
        if (transactionRepository.isPaymentReferenceRecorded(paymentId)) {
            log.warn("Payment {} was presented by company {} but is already recorded elsewhere",
                    paymentId, companyId);
            throw new BusinessRuleException("This payment has already been recorded.");
        }

        // 1. The confirmation really came from the gateway.
        paymentGateway.verifyPayment(
                new PaymentGatewayPort.PaymentConfirmation(orderId, paymentId, signature));

        // 2. What was actually paid, according to the gateway — not according to the
        //    client — and post it. Shared with the webhook path below, whose own
        //    authenticity check (the whole-payload webhook signature) happens before this
        //    is ever called, not via a per-payment HMAC.
        return settlePayment(companyId, owner, paymentGateway, orderId, paymentId,
                remarks, currentActor());
    }

    /**
     * Settles a recharge from a Razorpay webhook — the case {@link #completeRecharge} exists
     * to cover but cannot reach on its own: a browser tab closed after the gateway captured
     * the payment but before the confirmation call landed. The money is real and sitting at
     * the gateway either way; without this it is never credited until someone notices and
     * credits it by hand.
     *
     * <p>No {@code @PreAuthorize} — a gateway webhook carries no authenticated user, so the
     * ordinary per-method role gate has nothing to check. Safety instead comes from the
     * caller: {@code RazorpayWebhookController} verifies the whole-payload webhook signature
     * (a secret this method never sees) before calling this, and binds {@code companyId}
     * via {@link com.courier.shared.company.CompanyContext#runAs} from the order's own
     * {@code notes} — the same notes {@link #openRecharge} wrote, not anything the webhook
     * body claims about itself. This method must never be reachable from a normal
     * authenticated request.
     *
     * <p>Idempotent exactly like {@link #completeRecharge}: if the browser already settled
     * this payment, {@code settlePayment}'s lookup returns that entry unchanged.
     */
    @Override
    @Transactional
    public WalletTransaction settleFromWebhook(UUID companyId, UUID branchId,
                                               String gatewayOrderId, String paymentId) {
        PaymentGatewayPort paymentGateway = paymentGatewayResolver.resolve(companyId);
        return settlePayment(companyId, Owner.branch(branchId), paymentGateway, gatewayOrderId, paymentId,
                "Settled via gateway webhook — browser did not confirm", "webhook");
    }

    /** {@link #settleFromWebhook} for a customer's recharge. Same rules, same caller restrictions. */
    @Override
    @Transactional
    public WalletTransaction settleFromWebhookForCustomer(UUID companyId, UUID customerId,
                                                          String gatewayOrderId, String paymentId) {
        PaymentGatewayPort paymentGateway = paymentGatewayResolver.resolve(companyId);
        return settlePayment(companyId, Owner.customer(customerId), paymentGateway, gatewayOrderId, paymentId,
                "Settled via gateway webhook — browser did not confirm", "webhook");
    }

    /**
     * What was actually paid, according to the gateway — not according to any client — then
     * posted to the ledger. Idempotent on {@code paymentId}: a retry, a double submit and a
     * webhook that fires after (or before) the browser confirmation all credit the wallet
     * exactly once, whichever caller gets there first.
     */
    private WalletTransaction settlePayment(UUID companyId, Owner owner,
                                            PaymentGatewayPort paymentGateway,
                                            String orderId, String paymentId,
                                            String remarks, String actor) {
        Optional<WalletTransaction> already =
                transactionRepository.findByPaymentReferenceWithinCompany(paymentId, companyId);
        if (already.isPresent()) {
            log.info("Recharge {} already recorded as {}; returning it unchanged",
                    paymentId, already.get().getTransactionNo());
            rechargeOrders.markSuccess(companyId, orderId, paymentId, already.get().getId());
            return already.get();
        }
        // Not ours, but recorded by someone. Refuse flatly rather than saying whose it is.
        if (transactionRepository.isPaymentReferenceRecorded(paymentId)) {
            log.warn("Payment {} was presented by company {} but is already recorded elsewhere",
                    paymentId, companyId);
            throw new BusinessRuleException("This payment has already been recorded.");
        }

        PaymentGatewayPort.GatewayPayment payment = paymentGateway.fetchPayment(paymentId);
        if (!payment.captured()) {
            throw new BusinessRuleException(
                    "The payment has not been captured by the gateway and cannot be credited.");
        }
        if (!orderId.equals(payment.orderId())) {
            log.warn("Payment {} belongs to order {}, not the presented {}",
                    paymentId, payment.orderId(), orderId);
            throw new BusinessRuleException("The payment does not belong to that order.");
        }

        Wallet wallet = lockOwned(owner, companyId);
        requireOperational(wallet);
        if (payment.currency() != null && !payment.currency().equalsIgnoreCase(wallet.getCurrency())) {
            throw new BusinessRuleException(
                    "The payment was made in %s but the wallet holds %s."
                            .formatted(payment.currency(), wallet.getCurrency()));
        }

        WalletTransaction entry = post(wallet, companyId, TransactionType.CR,
                SubTransactionType.WRC, payment.amount(),
                ReferenceType.PAYMENT, orderId, remarks,
                payment.gateway(), paymentId);
        rechargeOrders.markSuccess(companyId, orderId, paymentId, entry.getId());

        log.info("Wallet {} recharged {} {} via {} ({}) by {}",
                wallet.getWalletNumber(), wallet.getCurrency(),
                payment.amount().toPlainString(), payment.gateway(), paymentId, actor);
        auditService.record(AuditAction.WALLET_RECHARGED, ENTITY, wallet.getId(),
                Map.of("walletNumber", wallet.getWalletNumber(),
                        "transactionNo", entry.getTransactionNo(),
                        "amount", payment.amount().toPlainString(),
                        "gateway", payment.gateway(),
                        "paymentReference", paymentId,
                        "balanceAfter", entry.getBalanceAfter().toPlainString()));
        eventPublisher.publishEvent(new WalletEvent.WalletRecharged(
                wallet.getId(), companyId, owner.branchId(), entry.getId(), payment.gateway(), paymentId,
                payment.amount(), entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    @Override
    @Transactional
    @PreAuthorize(COMPANY_USERS_ONLY)
    public RechargeOrder checkRechargeAttempt(UUID rechargeOrderId) {
        UUID companyId = requireCompany();
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        RechargeOrder order = rechargeOrderRepository.findById(rechargeOrderId)
                .filter(o -> companyId.equals(o.getCompanyId()))
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, rechargeOrderId));
        boolean companyWide = isAdmin(caller) || caller.hasRole(
                com.courier.modules.company.domain.DefaultRoleCatalog.FINANCE_USER);
        // A customer's recharge has no branch: only company-wide staff may check it.
        if (!companyWide && (order.getBranchId() == null
                || !ownBranch(caller, companyId).filter(order.getBranchId()::equals).isPresent())) {
            throw new ResourceNotFoundException(ENTITY, rechargeOrderId);
        }
        reconcileRecharge(order.getId());
        return rechargeOrderRepository.findById(order.getId()).orElse(order);
    }

    @Override
    @Transactional
    public void markRechargeFailed(UUID companyId, String gatewayOrderId, String reason) {
        rechargeOrders.markFailedByGatewayOrder(companyId, gatewayOrderId, reason);
    }

    /** A PENDING recharge older than this with no captured payment is given up on (a late
     *  capture is still settled by the webhook). */
    private static final java.time.Duration RECHARGE_PAYMENT_WINDOW = java.time.Duration.ofMinutes(10);
    /** An attempt that never got a gateway order id (crash between the two steps). */
    private static final java.time.Duration RECHARGE_ORDER_CREATE_WINDOW = java.time.Duration.ofMinutes(10);

    @Override
    @Transactional
    public void reconcileRecharge(UUID rechargeOrderId) {
        RechargeOrder order = rechargeOrderRepository.findById(rechargeOrderId).orElse(null);
        if (order == null || !order.isPending()) {
            return;
        }
        UUID companyId = order.getCompanyId();
        Instant now = Instant.now();
        java.time.Duration age = java.time.Duration.between(order.getCreatedAt(), now);

        if (order.getGatewayOrderId() == null) {
            if (age.compareTo(RECHARGE_ORDER_CREATE_WINDOW) > 0) {
                rechargeOrders.markFailed(order.getId(), "The gateway order was never created.");
            }
            return;
        }

        PaymentGatewayPort gateway = paymentGatewayResolver.resolve(companyId);
        List<PaymentGatewayPort.OrderPayment> payments;
        try {
            payments = gateway.fetchOrderPayments(order.getGatewayOrderId());
        } catch (BusinessRuleException unreachable) {
            // Transient (gateway down) or misconfigured: leave PENDING, try again next run.
            log.warn("Recharge {} could not be checked at the gateway: {}",
                    order.getTransactionNo(), unreachable.getMessage());
            rechargeOrders.touch(order.getId());
            return;
        }

        Optional<PaymentGatewayPort.OrderPayment> captured =
                payments.stream().filter(PaymentGatewayPort.OrderPayment::captured).findFirst();
        if (captured.isPresent()) {
            settlePayment(companyId, Owner.of(order), gateway, order.getGatewayOrderId(),
                    captured.get().paymentId(), "Settled by reconciliation — no confirmation reached us",
                    "scheduler");
            return;
        }
        if (!payments.isEmpty() && payments.stream().allMatch(p -> "failed".equalsIgnoreCase(p.status()))) {
            String reason = payments.get(payments.size() - 1).error();
            rechargeOrders.markFailed(order.getId(), reason == null ? "The payment failed at the gateway." : reason);
            return;
        }
        if (age.compareTo(RECHARGE_PAYMENT_WINDOW) > 0) {
            rechargeOrders.markFailed(order.getId(), "No payment was captured within 10 minutes.");
            return;
        }
        rechargeOrders.touch(order.getId());
    }

    // ------------------------------------------------------------ manual credit

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public WalletTransaction credit(CreditCommand command) {
        UUID companyId = requireCompany();
        UUID branchId = resolveBranchForWrite(command.branchId(), companyId);

        return creditOwned(companyId, Owner.branch(branchId), command.amount(), command.subTransactionType(),
                command.referenceType(), command.referenceId(), command.remarks());
    }

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public WalletTransaction creditForCustomer(CustomerCreditCommand command) {
        UUID companyId = requireCompany();
        UUID customerId = command.customerId();
        // 404 for a customer of another company, and opens the wallet of one that predates wallets.
        customerWallets.getForCustomer(customerId);

        return creditOwned(companyId, Owner.customer(customerId), command.amount(), command.subTransactionType(),
                command.referenceType(), command.referenceId(), command.remarks());
    }

    /** A manual credit once the owner is known. Shared by a branch's and a customer's wallet. */
    private WalletTransaction creditOwned(UUID companyId, Owner owner, BigDecimal requested,
                                          SubTransactionType requestedReason, ReferenceType referenceType,
                                          String referenceId, String remarks) {
        BigDecimal amount = requirePositiveAmount(requested);
        SubTransactionType reason = requestedReason == null ? SubTransactionType.MCR : requestedReason;
        reason.requireSupports(TransactionType.CR);

        Wallet wallet = lockOwned(owner, companyId);
        requireOperational(wallet);

        // A manual recharge is recorded in the recharge table first (born SUCCESS), then the
        // ledger entry is written — same order as an online one. Other manual credit reasons
        // (commission, adjustment...) are not recharges and touch only the ledger.
        RechargeOrder manualRecharge = null;
        if (reason == SubTransactionType.MCR) {
            manualRecharge = owner.isCustomer()
                    ? rechargeOrders.recordManualForCustomer(companyId, wallet.getId(), owner.customerId(), amount,
                            wallet.getCurrency(), nextTransactionNumber(), remarks)
                    : rechargeOrders.recordManual(companyId, wallet.getId(), owner.branchId(), amount,
                            wallet.getCurrency(), nextTransactionNumber(), remarks);
        }

        WalletTransaction entry = post(wallet, companyId, TransactionType.CR, reason, amount,
                referenceType == null ? ReferenceType.MANUAL : referenceType,
                referenceId, remarks, null, null);
        if (manualRecharge != null) {
            rechargeOrders.linkLedger(manualRecharge.getId(), entry.getId());
        }

        log.info("Wallet {} credited {} {} ({}) by {}", wallet.getWalletNumber(),
                wallet.getCurrency(), amount.toPlainString(), reason, currentActor());
        auditService.record(AuditAction.WALLET_CREDITED, ENTITY, wallet.getId(),
                ledgerDetails(wallet, entry));
        eventPublisher.publishEvent(new WalletEvent.WalletCredited(
                wallet.getId(), companyId, owner.branchId(), entry.getId(), reason, amount,
                entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    // ------------------------------------------------------------- manual debit

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public WalletTransaction debit(DebitCommand command) {
        UUID companyId = requireCompany();
        UUID branchId = resolveBranchForWrite(command.branchId(), companyId);

        BigDecimal amount = requirePositiveAmount(command.amount());
        SubTransactionType reason = command.subTransactionType() == null
                ? SubTransactionType.MDB : command.subTransactionType();
        reason.requireSupports(TransactionType.DR);

        Wallet wallet = lockOrThrow(branchId, companyId);
        requireOperational(wallet);

        // Wallet.applyDebit refuses an overdraft; the ledger entry is only written if it
        // succeeds, so a rejected debit leaves no trace of a balance that never changed.
        WalletTransaction entry = post(wallet, companyId, TransactionType.DR, reason, amount,
                command.referenceType() == null ? ReferenceType.MANUAL : command.referenceType(),
                command.referenceId(), command.remarks(), null, null);

        log.info("Wallet {} debited {} {} ({}) by {}", wallet.getWalletNumber(),
                wallet.getCurrency(), amount.toPlainString(), reason, currentActor());
        auditService.record(AuditAction.WALLET_DEBITED, ENTITY, wallet.getId(),
                ledgerDetails(wallet, entry));
        eventPublisher.publishEvent(new WalletEvent.WalletDebited(
                wallet.getId(), companyId, branchId, entry.getId(), reason, amount,
                entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    // ------------------------------------------------------------- COD delivery debit

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public WalletTransaction debitForCodDelivery(CodDeliveryDebitCommand command) {
        UUID companyId = requireCompany();
        UUID resolvedBranch = resolveBranchForWrite(command.branchId(), companyId);

        BigDecimal validated = requirePositiveAmount(command.amount());
        SubTransactionType reason = SubTransactionType.COD;
        reason.requireSupports(TransactionType.DR);

        Wallet wallet = lockOrThrow(resolvedBranch, companyId);
        requireOperational(wallet);

        WalletTransaction entry = post(wallet, companyId, TransactionType.DR, reason, validated,
                ReferenceType.SHIPMENT, command.shipmentNumber(), command.remarks(), null, null);

        log.info("Wallet {} debited {} {} for COD delivery {} by {}", wallet.getWalletNumber(),
                wallet.getCurrency(), validated.toPlainString(), command.shipmentNumber(),
                currentActor());
        auditService.record(AuditAction.WALLET_DEBITED, ENTITY, wallet.getId(),
                ledgerDetails(wallet, entry));
        eventPublisher.publishEvent(new WalletEvent.WalletDebited(
                wallet.getId(), companyId, resolvedBranch, entry.getId(), reason, validated,
                entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    // ------------------------------------------------------------- TO_PAY receipt debit

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public WalletTransaction debitForToPayReceivedAtBranch(CodDeliveryDebitCommand command) {
        UUID companyId = requireCompany();
        UUID resolvedBranch = resolveBranchForWrite(command.branchId(), companyId);

        BigDecimal validated = requirePositiveAmount(command.amount());
        SubTransactionType reason = SubTransactionType.TPY;
        reason.requireSupports(TransactionType.DR);

        Wallet wallet = lockOrThrow(resolvedBranch, companyId);
        requireOperational(wallet);

        WalletTransaction entry = post(wallet, companyId, TransactionType.DR, reason, validated,
                ReferenceType.SHIPMENT, command.shipmentNumber(), command.remarks(), null, null);

        log.info("Wallet {} debited {} {} for TO_PAY receipt {} by {}", wallet.getWalletNumber(),
                wallet.getCurrency(), validated.toPlainString(), command.shipmentNumber(),
                currentActor());
        auditService.record(AuditAction.WALLET_DEBITED, ENTITY, wallet.getId(),
                ledgerDetails(wallet, entry));
        eventPublisher.publishEvent(new WalletEvent.WalletDebited(
                wallet.getId(), companyId, resolvedBranch, entry.getId(), reason, validated,
                entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    // ------------------------------------------------------------- DRS charge credit

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public WalletTransaction creditForDrsCharge(DrsChargeCreditCommand command) {
        UUID companyId = requireCompany();
        UUID resolvedBranch = resolveBranchForWrite(command.branchId(), companyId);

        BigDecimal validated = requirePositiveAmount(command.amount());
        SubTransactionType reason = SubTransactionType.DRS;
        reason.requireSupports(TransactionType.CR);

        Wallet wallet = lockOrThrow(resolvedBranch, companyId);
        requireOperational(wallet);

        WalletTransaction entry = post(wallet, companyId, TransactionType.CR, reason, validated,
                ReferenceType.SHIPMENT, command.shipmentNumber(), command.remarks(), null, null);

        log.info("Wallet {} credited {} {} DRS commission for shipment {} by {}", wallet.getWalletNumber(),
                wallet.getCurrency(), validated.toPlainString(), command.shipmentNumber(),
                currentActor());
        auditService.record(AuditAction.WALLET_CREDITED, ENTITY, wallet.getId(),
                ledgerDetails(wallet, entry));
        eventPublisher.publishEvent(new WalletEvent.WalletCredited(
                wallet.getId(), companyId, resolvedBranch, entry.getId(), reason, validated,
                entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    // --------------------------------------------------- delivery weight commission credit

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public WalletTransaction creditForDeliveryWeightCommission(DeliveryWeightCommissionCreditCommand command) {
        UUID companyId = requireCompany();
        UUID resolvedBranch = resolveBranchForWrite(command.branchId(), companyId);

        BigDecimal validated = requirePositiveAmount(command.amount());
        SubTransactionType reason = SubTransactionType.DWC;
        reason.requireSupports(TransactionType.CR);

        Wallet wallet = lockOrThrow(resolvedBranch, companyId);
        requireOperational(wallet);

        WalletTransaction entry = post(wallet, companyId, TransactionType.CR, reason, validated,
                ReferenceType.SHIPMENT, command.shipmentNumber(), command.remarks(), null, null);

        log.info("Wallet {} credited {} {} delivery weight commission for shipment {} by {}",
                wallet.getWalletNumber(), wallet.getCurrency(), validated.toPlainString(),
                command.shipmentNumber(), currentActor());
        auditService.record(AuditAction.WALLET_CREDITED, ENTITY, wallet.getId(),
                ledgerDetails(wallet, entry));
        eventPublisher.publishEvent(new WalletEvent.WalletCredited(
                wallet.getId(), companyId, resolvedBranch, entry.getId(), reason, validated,
                entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    // -------------------------------------------------------------- booking debit

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public WalletTransaction debitForBooking(BookingDebitCommand command) {
        UUID companyId = requireCompany();
        UUID resolvedBranch = resolveBranchForWrite(command.branchId(), companyId);

        BigDecimal validated = requirePositiveAmount(command.amount());
        SubTransactionType reason = SubTransactionType.SBK;
        reason.requireSupports(TransactionType.DR);

        Wallet wallet = lockOrThrow(resolvedBranch, companyId);
        requireOperational(wallet);

        WalletTransaction entry = post(wallet, companyId, TransactionType.DR, reason, validated,
                ReferenceType.SHIPMENT, command.shipmentNumber(), command.remarks(), null, null);

        log.info("Wallet {} debited {} {} for booking {} by {}", wallet.getWalletNumber(),
                wallet.getCurrency(), validated.toPlainString(), command.shipmentNumber(),
                currentActor());
        auditService.record(AuditAction.WALLET_DEBITED, ENTITY, wallet.getId(),
                ledgerDetails(wallet, entry));
        eventPublisher.publishEvent(new WalletEvent.WalletDebited(
                wallet.getId(), companyId, resolvedBranch, entry.getId(), reason, validated,
                entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    // ---------------------------------------------------------- commission credit

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public WalletTransaction creditCommission(CommissionCreditCommand command) {
        UUID companyId = requireCompany();
        UUID resolvedBranch = resolveBranchForWrite(command.branchId(), companyId);

        BigDecimal validated = requirePositiveAmount(command.amount());
        SubTransactionType reason = SubTransactionType.COM;
        reason.requireSupports(TransactionType.CR);

        Wallet wallet = lockOrThrow(resolvedBranch, companyId);
        requireOperational(wallet);

        WalletTransaction entry = post(wallet, companyId, TransactionType.CR, reason, validated,
                ReferenceType.SHIPMENT, command.shipmentNumber(), command.remarks(), null, null);

        log.info("Wallet {} credited {} {} commission for booking {} by {}", wallet.getWalletNumber(),
                wallet.getCurrency(), validated.toPlainString(), command.shipmentNumber(),
                currentActor());
        auditService.record(AuditAction.WALLET_CREDITED, ENTITY, wallet.getId(),
                ledgerDetails(wallet, entry));
        eventPublisher.publishEvent(new WalletEvent.WalletCredited(
                wallet.getId(), companyId, resolvedBranch, entry.getId(), reason, validated,
                entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    // ------------------------------------------------------------ shipment cancellation

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public List<WalletTransaction> reverseForShipment(ShipmentReversalCommand command) {
        UUID companyId = requireCompany();

        List<WalletTransaction> originals = new java.util.ArrayList<>(
                transactionRepository.findSettledByReferenceWithinCompany(
                        ReferenceType.SHIPMENT, command.shipmentNumber(), companyId));
        // Deterministic lock order across wallets, not the ledger's own creation order —
        // the same "never lock rows in caller-decided order" discipline the class javadoc's
        // row-locking rule implies, in case two entries here land on different branches.
        originals.sort(java.util.Comparator.comparing(WalletTransaction::getWalletId));

        java.util.Set<UUID> alreadyRefunded = refundedBookingDebitIds(originals);
        List<WalletTransaction> reversals = new java.util.ArrayList<>();
        for (WalletTransaction original : originals) {
            // A booking debit an admin's PAID -> TO_PAY switch already refunded must not be
            // credited back a second time by the cancellation.
            if (alreadyRefunded.contains(original.getId())) {
                continue;
            }
            // A reversal never itself gets reversed — SRF/ADJ entries this same method wrote
            // (or a manual adjustment) are not something a cancellation should undo again.
            if (original.getSubTransactionType() == SubTransactionType.SRF
                    || original.getSubTransactionType() == SubTransactionType.ADJ) {
                continue;
            }

            try {
                reversals.add(postReversal(original, companyId, command.remarks()));
            } catch (RuntimeException e) {
                log.error("Could not reverse {} entry {} ({} {}) for cancelled shipment {}; "
                        + "reconcile manually", original.getSubTransactionType(),
                        original.getTransactionNo(), original.getAmount().toPlainString(),
                        original.getTransactionType(), command.shipmentNumber(), e);
            }
        }

        return reversals;
    }

    @Override
    @Transactional
    @PreAuthorize(AUTHENTICATED)
    public List<WalletTransaction> refundBookingDebit(ShipmentReversalCommand command) {
        UUID companyId = requireCompany();
        List<WalletTransaction> originals = new java.util.ArrayList<>(
                transactionRepository.findSettledByReferenceWithinCompany(
                        ReferenceType.SHIPMENT, command.shipmentNumber(), companyId));
        java.util.Set<UUID> alreadyRefunded = refundedBookingDebitIds(originals);
        List<WalletTransaction> refunds = new java.util.ArrayList<>();
        for (WalletTransaction original : originals) {
            if (original.getSubTransactionType() == SubTransactionType.SBK
                    && !alreadyRefunded.contains(original.getId())) {
                refunds.add(postReversal(original, companyId, command.remarks()));
            }
        }
        return refunds;
    }

    /** Booking debits ({@code SBK}) on this shipment's ledger that a refund credit
     *  ({@code SRF}) of the same amount has already answered, matched oldest-first. */
    private static java.util.Set<UUID> refundedBookingDebitIds(List<WalletTransaction> ledger) {
        List<WalletTransaction> debits = ledger.stream()
                .filter(t -> t.getSubTransactionType() == SubTransactionType.SBK).toList();
        java.util.Set<UUID> matched = new java.util.HashSet<>();
        for (WalletTransaction t : ledger) {
            if (t.getSubTransactionType() != SubTransactionType.SRF) {
                continue;
            }
            debits.stream()
                    .filter(d -> !matched.contains(d.getId()) && d.getAmount().compareTo(t.getAmount()) == 0)
                    .findFirst().ifPresent(d -> matched.add(d.getId()));
        }
        return matched;
    }

    private WalletTransaction postReversal(WalletTransaction original, UUID companyId, String remarks) {
        Wallet source = walletRepository.findByIdWithinCompany(original.getWalletId(), companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, original.getWalletId()));
        Wallet wallet = lockOrThrow(source.getBranchId(), companyId);
        requireOperational(wallet);

        boolean creditBack = original.isDebit();
        TransactionType type = creditBack ? TransactionType.CR : TransactionType.DR;
        SubTransactionType reason = creditBack ? SubTransactionType.SRF : SubTransactionType.ADJ;

        WalletTransaction entry = post(wallet, companyId, type, reason, original.getAmount(),
                ReferenceType.SHIPMENT, original.getReferenceId(),
                remarks + " — reversing " + original.getTransactionNo(), null, null);

        log.info("Wallet {} {} {} {} reversing {} {} for shipment {} by {}",
                wallet.getWalletNumber(), creditBack ? "credited" : "debited", wallet.getCurrency(),
                original.getAmount().toPlainString(), original.getSubTransactionType(),
                original.getTransactionNo(), original.getReferenceId(), currentActor());
        auditService.record(
                creditBack ? AuditAction.WALLET_CREDITED : AuditAction.WALLET_DEBITED,
                ENTITY, wallet.getId(), ledgerDetails(wallet, entry));
        eventPublisher.publishEvent(creditBack
                ? new WalletEvent.WalletCredited(wallet.getId(), companyId, wallet.getBranchId(),
                        entry.getId(), reason, original.getAmount(), entry.getBalanceAfter(), Instant.now())
                : new WalletEvent.WalletDebited(wallet.getId(), companyId, wallet.getBranchId(),
                        entry.getId(), reason, original.getAmount(), entry.getBalanceAfter(), Instant.now()));

        return entry;
    }

    // ------------------------------------------------------- the only money path

    /**
     * Applies the balance change and writes the matching ledger entry, in that order and in
     * one transaction.
     *
     * <p>The order matters: {@code applyCredit} / {@code applyDebit} enforce the domain rules
     * (positive amount, operational wallet, no overdraft) and throw before anything is
     * written, so a refused operation leaves neither a balance change nor an orphan entry.
     */
    private WalletTransaction post(Wallet wallet, UUID companyId,
                                   TransactionType type, SubTransactionType reason,
                                   BigDecimal amount,
                                   ReferenceType referenceType, String referenceId, String remarks,
                                   String gateway, String paymentReference) {

        BigDecimal before = Wallet.normalise(wallet.getAvailableBalance());
        // TPY (TO_PAY received at branch) is a liability the branch owes the company the
        // instant the shipment lands, not a spend against float it must already have —
        // see Wallet.applyDebitAllowingOverdraft's own doc. Every other debit type still
        // enforces no-overdraft.
        BigDecimal after = type.isCredit() ? wallet.applyCredit(amount)
                : reason == SubTransactionType.TPY ? wallet.applyDebitAllowingOverdraft(amount)
                : wallet.applyDebit(amount);

        WalletTransaction entry = WalletTransaction.builder()
                .transactionNo(nextTransactionNumber())
                .walletId(wallet.getId())
                .transactionType(type)
                .subTransactionType(reason)
                .amount(Wallet.normalise(amount))
                .balanceBefore(before)
                .balanceAfter(after)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .remarks(remarks)
                .paymentGateway(gateway)
                .paymentReference(paymentReference)
                .build();

        WalletTransaction saved = transactionRepository.save(entry);
        walletRepository.save(wallet);
        return saved;
    }

    // ------------------------------------------------------------------ scoping

    /**
     * Which branch's wallet the caller is asking to read.
     *
     * <p>An admin may name any branch of their company. Anyone else gets their own — the
     * branch they are placed at, or the one they manage — and naming another branch answers
     * 404, because "that branch exists but is not yours" is information they should not have.
     */
    private UUID resolveBranchForRead(UUID requested, UUID companyId) {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (isAdmin(caller)) {
            UUID branchId = requested != null ? requested : ownBranch(caller, companyId).orElse(null);
            if (branchId == null) {
                throw new BusinessRuleException(
                        "Name a branch: you are not assigned to one, so there is no wallet to default to.");
            }
            requireBranchExists(branchId, companyId);
            return branchId;
        }

        UUID own = ownBranch(caller, companyId).orElseThrow(() -> new BusinessRuleException(
                "You are not assigned to a branch, so you have no wallet to view."));
        if (requested != null && !requested.equals(own)) {
            throw new ResourceNotFoundException(ENTITY, requested);
        }
        return own;
    }

    /**
     * Which branch's wallet the caller is asking to change.
     *
     * <p>403 rather than 404 here, mirroring {@code BranchServiceImpl.requireManageable}: the
     * caller reached a branch of their own company that they may not act on, which is a
     * permission answer, not a hidden resource.
     */
    private UUID resolveBranchForWrite(UUID requested, UUID companyId) {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (isAdmin(caller)) {
            UUID branchId = requested != null ? requested : ownBranch(caller, companyId).orElse(null);
            if (branchId == null) {
                throw new BusinessRuleException(
                        "Name the branch whose wallet this affects.");
            }
            requireBranchExists(branchId, companyId);
            return branchId;
        }

        UUID own = ownBranch(caller, companyId).orElseThrow(() -> new BusinessRuleException(
                "You are not assigned to a branch, so you have no wallet to transact on."));
        if (requested != null && !requested.equals(own)) {
            throw new ForbiddenException("You may only transact on the wallet of your own branch.");
        }
        return own;
    }

    /** The branch a non-admin caller belongs to: their placement, else the one they manage. */
    private Optional<UUID> ownBranch(AuthenticatedUser caller, UUID companyId) {
        Optional<UUID> placement = branchDirectory.branchOfUser(caller.userId(), companyId);
        if (placement.isPresent()) {
            return placement;
        }
        return branchDirectory.branchManagedBy(caller.userId(), companyId);
    }

    private boolean isAdmin(AuthenticatedUser caller) {
        return caller.hasRole(Roles.COMPANY_ADMIN) || caller.isSuperAdmin();
    }

    // ------------------------------------------------------------------ helpers

    /** Locks the wallet of whichever owner this is, creating a customer's if it does not exist yet. */
    private Wallet lockOwned(Owner owner, UUID companyId) {
        if (!owner.isCustomer()) {
            return lockOrThrow(owner.branchId(), companyId);
        }
        // Ensure the wallet exists before taking the row lock, for the same reason as lockOrThrow.
        customerWallets.provisionForCustomer(companyId, owner.customerId());
        return walletRepository.lockByCustomerIdWithinCompany(owner.customerId(), companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, owner.customerId()));
    }

    private Wallet lockOrThrow(UUID branchId, UUID companyId) {
        // Ensure the wallet exists before taking the row lock — FOR UPDATE on no rows
        // locks nothing and would let two callers both proceed to create one.
        getOrCreateForBranch(branchId);
        return walletRepository.lockByBranchIdWithinCompany(branchId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, branchId));
    }

    private void requireBranchExists(UUID branchId, UUID companyId) {
        if (branchId == null) {
            throw new BusinessRuleException("A branch is required.");
        }
        branchDirectory.findBranch(branchId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));
    }

    private void requireOperational(Wallet wallet) {
        if (!wallet.isActive()) {
            throw new BusinessRuleException(
                    "Wallet %s is %s and cannot accept transactions."
                            .formatted(wallet.getWalletNumber(),
                                    wallet.getStatus().name().toLowerCase()));
        }
    }

    private static BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("Amount must be greater than zero.");
        }
        return Wallet.normalise(amount);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException("'%s' is required to settle a recharge.".formatted(field));
        }
        return value.trim();
    }

    /** Null (no matching rows) normalises to zero — a wallet with no history has no total. */
    private BigDecimal sum(Wallet wallet, UUID companyId, TransactionType type, Instant from) {
        return Wallet.normalise(transactionRepository.sumSettledSince(
                wallet.getId(), companyId, type, from));
    }

    private static final List<SubTransactionType> COMMISSION_REASONS =
            List.of(SubTransactionType.COM, SubTransactionType.DRS);

    /** The commission slice of {@link #sum} — same settled-credit rule, narrowed to
     *  {@code COM}/{@code DRS}. */
    private BigDecimal sumCommission(Wallet wallet, UUID companyId, Instant from) {
        return Wallet.normalise(transactionRepository.sumSettledSinceForSubTypes(
                wallet.getId(), companyId, TransactionType.CR, COMMISSION_REASONS, from));
    }

    private static WalletTransaction firstOf(List<WalletTransaction> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private String nextWalletNumber() {
        for (int attempt = 0; attempt < NUMBER_ATTEMPTS; attempt++) {
            String candidate = WalletNumberGenerator.walletNumber();
            if (!walletRepository.existsByWalletNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique wallet number in " + NUMBER_ATTEMPTS + " attempts");
    }

    private String nextTransactionNumber() {
        for (int attempt = 0; attempt < NUMBER_ATTEMPTS; attempt++) {
            String candidate = WalletNumberGenerator.transactionNumber();
            if (!transactionRepository.existsByTransactionNo(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique transaction number in " + NUMBER_ATTEMPTS + " attempts");
    }

    private Map<String, Object> ledgerDetails(Wallet wallet, WalletTransaction entry) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("walletNumber", wallet.getWalletNumber());
        details.put("transactionNo", entry.getTransactionNo());
        details.put("transactionType", entry.getTransactionType().name());
        details.put("subTransactionType", entry.getSubTransactionType().name());
        details.put("amount", entry.getAmount().toPlainString());
        details.put("balanceBefore", entry.getBalanceBefore().toPlainString());
        details.put("balanceAfter", entry.getBalanceAfter().toPlainString());
        details.put("referenceId", entry.getReferenceId());
        return details;
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. A wallet belongs to a branch of a "
                        + "company, so this operation must be performed by a user of that company."));
    }

    private String currentActor() {
        return SecurityUtils.getCurrentUser()
                .map(user -> user.email() == null ? user.userId().toString() : user.email())
                .orElse("system");
    }
}
