package com.courier.modules.finance.application;

import com.courier.modules.finance.domain.CashHandover;
import com.courier.modules.finance.domain.CashHandoverRepository;
import com.courier.modules.finance.domain.Wallet;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import lombok.extern.slf4j.Slf4j;
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
import java.util.UUID;

@Slf4j
@Service
public class CashHandoverServiceImpl implements CashHandoverService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Longest window one list or report call may cover. */
    static final int MAX_DAYS = 93;
    private static final String ENTITY = "CashHandover";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    private final CashHandoverRepository repository;
    private final FinanceBranchScope scope;
    private final AuditService auditService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CashHandoverServiceImpl(CashHandoverRepository repository, FinanceBranchScope scope, AuditService auditService) {
        this(repository, scope, auditService, Clock.systemUTC());
    }

    CashHandoverServiceImpl(CashHandoverRepository repository, FinanceBranchScope scope, AuditService auditService,
                            Clock clock) {
        this.repository = repository;
        this.scope = scope;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    @Transactional
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public CashHandover record(RecordCommand command) {
        UUID companyId = scope.requireCompany();
        AuthenticatedUser caller = scope.caller();
        if (!scope.canRecordHandover(caller)) {
            throw new AccessDeniedException("Only a branch manager, the accounts desk or a company admin records a cash handover.");
        }
        UUID branchId = scope.resolveBranch(command.branchId(), companyId, caller);
        if (command.userId() == null) {
            throw new BusinessRuleException("Choose the user who handed over the cash.");
        }
        if (command.userId().equals(caller.userId()) && !caller.hasRole(Roles.COMPANY_ADMIN)) {
            throw new BusinessRuleException("You cannot record a handover from yourself; someone else must receive your cash.");
        }
        if (!scope.userExists(command.userId(), companyId)) {
            throw new ResourceNotFoundException("User", command.userId());
        }
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("The amount must be more than zero.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessRuleException("Enter a valid amount in rupees and paise.");
        }
        if (command.mode() == null) {
            throw new BusinessRuleException("Choose how the cash was handed over.");
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate day = command.handoverDate() == null ? today : command.handoverDate();
        if (day.isAfter(today)) {
            throw new BusinessRuleException("The handover date cannot be in the future.");
        }

        CashHandover handover = CashHandover.builder()
                .branchId(branchId)
                .userId(command.userId())
                .amount(amount.setScale(Wallet.MONEY_SCALE))
                .mode(command.mode())
                .reference(trimToNull(command.reference(), 100))
                .remarks(trimToNull(command.remarks(), 500))
                .handoverDate(day)
                .receivedBy(caller.userId())
                .receivedAt(Instant.now(clock).truncatedTo(ChronoUnit.MICROS))
                .build();
        CashHandover saved = repository.save(handover);

        log.info("Cash handover {} recorded: {} from user {} at branch {} by {}", saved.getId(),
                amount.toPlainString(), command.userId(), branchId, caller.userId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("branchId", branchId.toString());
        details.put("userId", command.userId().toString());
        details.put("amount", amount.toPlainString());
        details.put("mode", command.mode().name());
        details.put("handoverDate", day.toString());
        auditService.record(AuditAction.CASH_HANDOVER_RECORDED, ENTITY, saved.getId(), details);
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public CashHandover voidHandover(UUID handoverId, String reason) {
        UUID companyId = scope.requireCompany();
        AuthenticatedUser caller = scope.caller();
        CashHandover handover = repository.findByIdWithinCompany(handoverId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, handoverId));
        // Branch-bound callers only reach their own branch's handovers (404 otherwise, like every other read).
        scope.resolveBranch(handover.getBranchId(), companyId, caller);
        boolean admin = caller.hasRole(Roles.COMPANY_ADMIN);
        if (!admin && !handover.getReceivedBy().equals(caller.userId())) {
            throw new AccessDeniedException("Only whoever recorded this handover, or a company admin, can void it.");
        }
        handover.voidWith(caller.userId(), Instant.now(clock).truncatedTo(ChronoUnit.MICROS),
                trimToNull(reason, 500));
        CashHandover saved = repository.save(handover);

        auditService.record(AuditAction.CASH_HANDOVER_VOIDED, ENTITY, saved.getId(), Map.of(
                "userId", saved.getUserId().toString(),
                "amount", saved.getAmount().toPlainString(),
                "reason", saved.getVoidReason()));
        return saved;
    }

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
