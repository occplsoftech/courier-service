package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateQuoteOptionRepository;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.command.CustomerRtoChargeCommand;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The return-to-sender charge of a portal booking (Phase 12.1): the forward freight the customer was quoted (the courier's freight
 * plus OCCPL's service charge on it, without GST or other charges), debited from the customer's wallet once. OCCPL Network bookings are
 * charged when the branch starts the return; outside-courier bookings when the courier's tracking first says the parcel is returning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalRtoChargeService {

    private final PortalBookingRepository bookings;
    private final RateQuoteOptionRepository options;
    private final WalletService walletService;
    private final TransactionTemplate tx;

    /** Charges the booking an OCCPL Network shipment was issued for (none for a counter shipment). */
    public void chargeForShipment(UUID companyId, UUID shipmentId) {
        List<PortalBooking> found = CompanyContext.runAs(companyId, () -> bookings.findByShipment(companyId, shipmentId));
        found.forEach(b -> charge(companyId, b.getId()));
    }

    /** Charges one booking. Safe to repeat; a failure is logged for operations rather than thrown. */
    public void charge(UUID companyId, UUID bookingId) {
        try {
            CompanyContext.runAs(companyId, () -> tx.executeWithoutResult(s -> {
                PortalBooking b = bookings.findById(bookingId).filter(x -> companyId.equals(x.getCompanyId())).orElse(null);
                if (b == null || b.getRtoChargedAt() != null || b.getStatus() != PortalBooking.Status.BOOKED) {
                    return;
                }
                BigDecimal amount = options.findAllWithin(List.of(b.getQuoteOptionId()), companyId).stream().findFirst()
                        .map(PortalRtoChargeService::forwardFreight).orElse(BigDecimal.ZERO);
                if (amount.signum() > 0) {
                    walletService.debitRtoForCustomer(new CustomerRtoChargeCommand(b.getCustomerId(), amount, b.getId(),
                            Optional.ofNullable(b.getAwbNumber()).orElse(b.getId().toString())));
                }
                b.setRtoCharge(amount);
                b.setRtoChargedAt(Instant.now());
                bookings.save(b);
            }));
        } catch (RuntimeException e) {
            log.error("The return of portal booking {} could not be charged to the customer's wallet; charge it manually", bookingId, e);
        }
    }

    /**
     * What the customer paid for freight going out: the freight line plus its share of OCCPL's service charge (the default markup is
     * on freight only; when the markup base does not include the freight, the freight alone).
     */
    static BigDecimal forwardFreight(RateQuoteOption o) {
        BigDecimal freight = o.getBaseFreight() == null ? BigDecimal.ZERO : o.getBaseFreight();
        BigDecimal base = o.getMarkupBase();
        BigDecimal markup = o.getMarkupAmount();
        if (freight.signum() > 0 && base != null && base.signum() > 0 && markup != null && markup.signum() > 0
                && base.compareTo(freight) >= 0) {
            freight = freight.add(markup.multiply(freight).divide(base, 2, RoundingMode.HALF_UP));
        }
        return freight.setScale(2, RoundingMode.HALF_UP);
    }
}
