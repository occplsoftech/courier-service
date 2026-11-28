package com.courier.modules.finance.application;

import com.courier.modules.finance.application.event.CashHandoverEvent;
import com.courier.modules.finance.domain.CashHandover;
import com.courier.modules.finance.domain.CashHandoverRepository;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class CashHandoverServiceImpl implements CashHandoverService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Longest window one list or report call may cover. */
    static final int MAX_DAYS = 93;
    private static final String ENTITY = "CashHandover";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");
    private static final Set<String> PROOF_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "heic");
    private static final int MAX_PROOF_BYTES = 10 * 1024 * 1024;

    private final CashHandoverRepository repository;
    private final FinanceBranchScope scope;
    private final AuditService auditService;
    private final FileStoragePort fileStorage;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public CashHandoverServiceImpl(CashHandoverRepository repository, FinanceBranchScope scope, AuditService auditService,
                                   FileStoragePort fileStorage, ApplicationEventPublisher events) {
        this(repository, scope, auditService, fileStorage, events, Clock.systemUTC());
    }

    CashHandoverServiceImpl(CashHandoverRepository repository, FinanceBranchScope scope, AuditService auditService,
                            FileStoragePort fileStorage, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.scope = scope;
        this.auditService = auditService;
        this.fileStorage = fileStorage;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------ the holder

    @Override
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public String uploadProof(UploadProofCommand command) {
        UUID companyId = scope.requireCompany();
        if (command.content() == null || command.content().length == 0) {
            throw new BusinessRuleException("Choose an image of the proof.");
        }
        if (command.content().length > MAX_PROOF_BYTES) {
            throw new BusinessRuleException("The image is too large; keep it under 10 MB.");
        }
        String extension = extensionOf(command.filename());
        if (!PROOF_EXTENSIONS.contains(extension)) {
            throw new BusinessRuleException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Only JPEG / PNG / WEBP / HEIC images are accepted as handover proof.");
        }
        String key = "%s/cash-handover-%s.%s".formatted(companyId, UUID.randomUUID(), extension);
        return fileStorage.upload(new FileStoragePort.UploadRequest(command.content(), key, command.contentType(),
                "cash-handover-proof")).url();
    }

    @Override
    @Transactional
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public CashHandover submit(SubmitCommand command) {
        UUID companyId = scope.requireCompany();
        AuthenticatedUser caller = scope.caller();
        UUID branchId = scope.resolveBranch(command.branchId(), companyId, caller);

        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("The amount must be more than zero.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessRuleException("Enter a valid amount in rupees and paise.");
        }
        if (command.mode() == null) {
            throw new BusinessRuleException("Choose how the cash is handed over.");
        }
        String proof = trimToNull(command.proofImageUrl(), 500);
        if (proof == null || !(proof.startsWith("https://") || proof.startsWith("http://"))) {
            throw new BusinessRuleException("Upload a proof image (deposit slip, UPI screenshot or photo of the cash).");
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate day = command.handoverDate() == null ? today : command.handoverDate();
        if (day.isAfter(today)) {
            throw new BusinessRuleException("The handover date cannot be in the future.");
        }

        CashHandover saved = repository.save(CashHandover.builder()
                .branchId(branchId)
                .userId(caller.userId())
                .amount(amount.setScale(Wallet.MONEY_SCALE))
                .mode(command.mode())
                .reference(trimToNull(command.reference(), 100))
                .remarks(trimToNull(command.remarks(), 500))
                .handoverDate(day)
                .proofImageUrl(proof)
                .submittedAt(now())
                .build());

        log.info("Cash handover {} submitted: {} by user {} at branch {}", saved.getId(), amount.toPlainString(),
                caller.userId(), branchId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("branchId", branchId.toString());
        details.put("amount", amount.toPlainString());
        details.put("mode", command.mode().name());
        details.put("handoverDate", day.toString());
        auditService.record(AuditAction.CASH_HANDOVER_SUBMITTED, ENTITY, saved.getId(), details);
        publish(saved, caller.userId(), null);
        return saved;
    }

    // ------------------------------------------------------------------------------------------------ the receiver

    @Override
    @Transactional
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public CashHandover approve(UUID handoverId, String note) {
        AuthenticatedUser caller = scope.caller();
        CashHandover handover = decidable(handoverId, caller);
        handover.approve(caller.userId(), now(), trimToNull(note, 500));
        CashHandover saved = repository.save(handover);
        audit(AuditAction.CASH_HANDOVER_APPROVED, saved, saved.getDecisionRemarks());
        publish(saved, caller.userId(), saved.getDecisionRemarks());
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public CashHandover reject(UUID handoverId, String reason) {
        AuthenticatedUser caller = scope.caller();
        CashHandover handover = decidable(handoverId, caller);
        handover.reject(caller.userId(), now(), trimToNull(reason, 500));
        CashHandover saved = repository.save(handover);
        audit(AuditAction.CASH_HANDOVER_REJECTED, saved, saved.getDecisionRemarks());
        publish(saved, caller.userId(), saved.getDecisionRemarks());
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public CashHandover voidHandover(UUID handoverId, String reason) {
        UUID companyId = scope.requireCompany();
        AuthenticatedUser caller = scope.caller();
        CashHandover handover = inScope(handoverId, companyId, caller);
        if (!handover.isApproved()) {
            throw new BusinessRuleException("Only an approved handover can be voided; a pending one is rejected instead.");
        }
        boolean admin = caller.hasRole(Roles.COMPANY_ADMIN);
        if (!admin && !caller.userId().equals(handover.getDecidedBy())) {
            throw new AccessDeniedException("Only whoever approved this handover, or a company admin, can void it.");
        }
        handover.voidWith(caller.userId(), now(), trimToNull(reason, 500));
        CashHandover saved = repository.save(handover);
        audit(AuditAction.CASH_HANDOVER_VOIDED, saved, saved.getVoidReason());
        publish(saved, caller.userId(), saved.getVoidReason());
        return saved;
    }

    // ------------------------------------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public List<CashHandover> list(UUID branchId, LocalDate from, LocalDate to, UUID userId) {
        UUID companyId = scope.requireCompany();
        AuthenticatedUser caller = scope.caller();
        UUID branch = scope.resolveBranch(branchId, companyId, caller);
        LocalDate[] range = range(from, to, clock);
        UUID onlyUser = scope.seesTeam(caller) ? userId : caller.userId();
        return repository.findForBranchBetween(companyId, branch, range[0], range[1]).stream()
                .filter(h -> onlyUser == null || onlyUser.equals(h.getUserId()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public List<CashHandover> pending(UUID branchId) {
        UUID companyId = scope.requireCompany();
        AuthenticatedUser caller = scope.caller();
        UUID branch = scope.resolveBranch(branchId, companyId, caller);
        boolean team = scope.seesTeam(caller);
        return repository.findPendingForBranch(companyId, branch).stream()
                .filter(h -> team || caller.userId().equals(h.getUserId()))
                .toList();
    }

    // ------------------------------------------------------------------------------------------------ internals

    /** A pending handover the caller may approve or reject: a receiver role, in their branch, and not their own. */
    private CashHandover decidable(UUID handoverId, AuthenticatedUser caller) {
        UUID companyId = scope.requireCompany();
        if (!scope.canApproveHandover(caller)) {
            throw new AccessDeniedException("Only a branch manager, the accounts desk or a company admin approves cash handovers.");
        }
        CashHandover handover = inScope(handoverId, companyId, caller);
        if (handover.getUserId().equals(caller.userId())) {
            throw new BusinessRuleException("You cannot approve or reject your own handover; someone else must receive your cash.");
        }
        return handover;
    }

    /** The handover, if the caller's branch scope reaches it (404 otherwise, like every other read). */
    private CashHandover inScope(UUID handoverId, UUID companyId, AuthenticatedUser caller) {
        CashHandover handover = repository.findByIdWithinCompany(handoverId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, handoverId));
        scope.resolveBranch(handover.getBranchId(), companyId, caller);
        return handover;
    }

    /** Notifications go out after commit — see {@link CashHandoverEvent}. */
    private void publish(CashHandover h, UUID actor, String note) {
        events.publishEvent(new CashHandoverEvent(scope.requireCompany(), h.getId(), h.getBranchId(), h.getUserId(), actor,
                h.getStatus(), h.getAmount(), h.getMode(), h.getHandoverDate(), note));
    }

    private void audit(AuditAction action, CashHandover h, String note) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", h.getUserId().toString());
        details.put("amount", h.getAmount().toPlainString());
        details.put("status", h.getStatus().name());
        if (note != null) details.put("note", note);
        auditService.record(action, ENTITY, h.getId(), details);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** Validates a report window: both ends default to today (IST), {@code to} not before {@code from}, at most
     *  {@link #MAX_DAYS} days. */
    static LocalDate[] range(LocalDate from, LocalDate to, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate f = from == null ? (to == null ? today : to) : from;
        LocalDate t = to == null ? (from == null ? today : f) : to;
        if (t.isBefore(f)) {
            throw new BusinessRuleException("The end date cannot be before the start date.");
        }
        if (ChronoUnit.DAYS.between(f, t) >= MAX_DAYS) {
            throw new BusinessRuleException("Choose at most " + MAX_DAYS + " days at a time.");
        }
        return new LocalDate[]{f, t};
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private static String trimToNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw new BusinessRuleException("Keep it to " + max + " characters.");
        }
        return v;
    }
}
