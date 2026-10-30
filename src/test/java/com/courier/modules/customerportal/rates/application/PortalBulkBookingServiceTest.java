package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.application.RateComparisonService.BulkQuote;
import com.courier.modules.customerportal.rates.application.RateComparisonService.OptionView;
import com.courier.modules.customerportal.rates.application.RateComparisonService.QuoteView;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortalBulkBookingServiceTest {

    private static RateQuoteOption option(String provider, String service, String total) {
        return RateQuoteOption.builder().providerCode(provider).serviceCode(service).availability("AVAILABLE")
                .totalAmount(new BigDecimal(total)).build();
    }

    @Test
    void businessRateWinsEvenWhenAnotherCourierIsCheaper() {
        RateQuoteOption business = option("OCCPL_NETWORK", "OCCPL_BUSINESS_RATE", "300");
        RateQuoteOption cheaper = option("DELHIVERY", "SURFACE", "200");
        assertThat(RateComparisonService.chooseForBulk(List.of(cheaper, business))).isSameAs(business);
    }

    @Test
    void withoutBusinessRateTheCheapestIsChosen() {
        RateQuoteOption tariff = option("OCCPL_NETWORK", "OCCPL_EXPRESS", "300");
        RateQuoteOption cheaper = option("DELHIVERY", "SURFACE", "200");
        assertThat(RateComparisonService.chooseForBulk(List.of(tariff, cheaper))).isSameAs(cheaper);
        assertThat(RateComparisonService.chooseForBulk(List.of())).isNull();
    }

    @Test
    void templateRoundTripsAndBadRowsAreReportedNotThrown() throws Exception {
        RateComparisonService comparison = mock(RateComparisonService.class);
        PortalBookingService booking = mock(PortalBookingService.class);
        PortalBulkBookingService service = new PortalBulkBookingService(comparison, booking);
        UUID quote = UUID.randomUUID(), opt = UUID.randomUUID();
        OptionView o = new OptionView(opt, "OCCPL_NETWORK", "OCCPL Network", "OCCPL Standard", "SURFACE", null, null,
                new BigDecimal("2.5"), BigDecimal.TEN, BigDecimal.ONE, null, null, BigDecimal.ONE, new BigDecimal("295"), "INR", List.of(), Instant.now());
        QuoteView view = new QuoteView(quote, "OPEN", Instant.now(), "411001", "400001", new BigDecimal("2.5"), BigDecimal.ONE, 1,
                BigDecimal.TEN, "PREPAID", List.of(o), List.of());
        when(comparison.compareForBulk(any())).thenReturn(new BulkQuote(view, opt, "BUSINESS_RATE"));

        var preview = service.preview(new ByteArrayInputStream(service.template()));
        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).ok()).isTrue();
        assertThat(preview.rows().get(0).row()).isEqualTo(2);
        assertThat(preview.rows().get(0).basis()).isEqualTo("BUSINESS_RATE");
        assertThat(preview.okTotal()).isEqualByComparingTo("295");
    }

    @Test
    void junkFileIsRefusedWithAMessage() {
        PortalBulkBookingService service = new PortalBulkBookingService(mock(RateComparisonService.class), mock(PortalBookingService.class));
        assertThatThrownBy(() -> service.preview(new ByteArrayInputStream("not excel".getBytes())))
                .hasMessageContaining("could not be read");
    }
}
