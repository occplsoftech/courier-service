package com.courier.modules.tariff.application;

import com.courier.modules.company.domain.CompanyRepository;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Zone tariff prices for the public website's rate calculator: the staff calculator's options
 * ({@link TariffQuoteService#quoteAll}) of one configured company ({@code app.public-rates.company-code}), never one the
 * caller chooses. Read-only. The quote runs as an actor without any role, because the staff services ask for a signed-in user.
 */
@Service
@RequiredArgsConstructor
public class PublicRateService {

    static final String NO_RATE = "We do not have an online rate for this shipment. Check the pincodes and weight, or contact us for a rate.";
    private static final UUID PUBLIC_ACTOR = new UUID(0L, 0L);

    private final TariffQuoteService quotes;
    private final CompanyRepository companies;

    @Value("${app.public-rates.company-code:}")
    private String companyCode;

    public TariffQuoteService.Options rates(TariffQuoteService.QuoteInput input) {
        UUID companyId = companies.findByCompanyCode(companyCode == null ? "" : companyCode.trim())
                .filter(c -> c.isActive()).map(c -> c.getCompanyId())
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.SERVICE_UNAVAILABLE, "Online rates are not available right now."));
        TariffQuoteService.Options options;
        try {
            options = CompanyContext.runAs(companyId, () -> asPublic(companyId, () -> quotes.quoteAll(input)));
        } catch (BusinessRuleException internal) {
            // The staff message names zones, cards and how to set them up; the public gets the outcome only.
            throw new BusinessRuleException(NO_RATE);
        }
        if (options.quotes().isEmpty()) {
            throw new BusinessRuleException(NO_RATE);
        }
        return options;
    }

    private static <T> T asPublic(UUID companyId, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext scoped = SecurityContextHolder.createEmptyContext();
        AuthenticatedUser actor = new AuthenticatedUser(PUBLIC_ACTOR, companyId, "public@website", Set.of(), "public-rates");
        scoped.setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, actor.authorities()));
        SecurityContextHolder.setContext(scoped);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
