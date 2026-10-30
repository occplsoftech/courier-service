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

    @Test
    void editedRowIsRecheckedAndKeepsItsValues() {
        RateComparisonService comparison = mock(RateComparisonService.class);
        PortalBulkBookingService service = new PortalBulkBookingService(comparison, mock(PortalBookingService.class));
        java.util.Map<String, String> f = new java.util.HashMap<>();
        f.put("Pickup Name", "A"); f.put("Pickup Phone", "9800000001"); f.put("Pickup Address", "x"); f.put("Pickup Pincode", "411001");
        f.put("Delivery Name", "B"); f.put("Delivery Phone", "12345"); f.put("Delivery Address", "y"); f.put("Delivery Pincode", "400001");
        f.put("Weight (kg)", "2"); f.put("Declared Value (Rs)", "100");
        var bad = service.checkRow(7, f);
        assertThat(bad.ok()).isFalse();
        assertThat(bad.row()).isEqualTo(7);
        assertThat(bad.error()).contains("Delivery phone");
        assertThat(bad.fields()).containsEntry("Delivery Phone", "12345");
    }
}
