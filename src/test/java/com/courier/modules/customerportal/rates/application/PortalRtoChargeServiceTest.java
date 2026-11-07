package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateQuoteOptionRepository;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.command.CustomerRtoChargeCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortalRtoChargeServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    @Mock private PortalBookingRepository bookings;
    @Mock private RateQuoteOptionRepository options;
    @Mock private WalletService wallet;
    @Mock private TransactionTemplate tx;

    private PortalRtoChargeService service;

    @BeforeEach
    void setUp() {
        service = new PortalRtoChargeService(bookings, options, wallet, tx);
    }

    private static RateQuoteOption option(String freight, String markupBase, String markup) {
        RateQuoteOption o = RateQuoteOption.builder().baseFreight(new BigDecimal(freight))
                .markupBase(markupBase == null ? null : new BigDecimal(markupBase))
                .markupAmount(markup == null ? null : new BigDecimal(markup)).build();
        o.setId(UUID.randomUUID());
        return o;
    }

    @Test
    @DisplayName("forward freight = freight + its share of OCCPL's service charge; freight alone when the markup is not on freight")
    void forwardFreight() {
        assertThat(PortalRtoChargeService.forwardFreight(option("100.00", "100.00", "10.00"))).isEqualByComparingTo("110.00");
        assertThat(PortalRtoChargeService.forwardFreight(option("100.00", "125.00", "12.50"))).isEqualByComparingTo("110.00");
        assertThat(PortalRtoChargeService.forwardFreight(option("100.00", "20.00", "2.00"))).isEqualByComparingTo("100.00");
        assertThat(PortalRtoChargeService.forwardFreight(option("80.00", null, null))).isEqualByComparingTo("80.00");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("charges a booked booking once from the wallet and records it; an already charged booking is left alone")
    void chargesOnce() {
        doAnswer(i -> { ((Consumer<org.springframework.transaction.TransactionStatus>) i.getArgument(0)).accept(null); return null; })
                .when(tx).executeWithoutResult(any());
        RateQuoteOption o = option("200.00", "200.00", "20.00");
        PortalBooking b = PortalBooking.builder().customerId(CUSTOMER).quoteOptionId(o.getId()).status(PortalBooking.Status.BOOKED)
                .awbNumber("AWB9").build();
        b.setId(UUID.randomUUID());
        b.setCompanyId(COMPANY);
        when(bookings.findById(b.getId())).thenReturn(Optional.of(b));
        when(options.findAllWithin(List.of(o.getId()), COMPANY)).thenReturn(List.of(o));

        service.charge(COMPANY, b.getId());

        ArgumentCaptor<CustomerRtoChargeCommand> cmd = ArgumentCaptor.forClass(CustomerRtoChargeCommand.class);
        verify(wallet).debitRtoForCustomer(cmd.capture());
        assertThat(cmd.getValue().amount()).isEqualByComparingTo("220.00");
        assertThat(cmd.getValue().customerId()).isEqualTo(CUSTOMER);
        assertThat(b.getRtoCharge()).isEqualByComparingTo("220.00");
        assertThat(b.getRtoChargedAt()).isNotNull();

        org.mockito.Mockito.clearInvocations(wallet);
        service.charge(COMPANY, b.getId());
        verify(wallet, never()).debitRtoForCustomer(any());
    }
}
