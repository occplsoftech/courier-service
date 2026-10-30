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

    private static PortalBulkBookingService svc(RateComparisonService comparison, PortalBookingService booking) {
        var resolver = mock(com.courier.modules.customerportal.application.CurrentCustomerResolver.class);
        var customers = mock(com.courier.modules.customer.application.CustomerPortalLookup.class);
        var acting = new com.courier.modules.customerportal.application.ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(resolver.require()).thenReturn(acting);
        var customer = com.courier.modules.customer.domain.Customer.builder().companyName("Acme").contactPerson("Meera Shah").mobile("9800000001").build();
        when(customers.findActiveBusinessCustomer(any(), any())).thenReturn(java.util.Optional.of(customer));
        var book = mock(com.courier.modules.customer.application.CustomerPortalAddressBook.class);
        when(book.list(any(), any())).thenReturn(List.of(new com.courier.modules.customer.application.CustomerPortalAddressBook.PortalAddress(
                UUID.randomUUID(), "OFFICE", "BOTH", "12 MG Road", "Pune", null, "411001", true, false, 0L)));
        return new PortalBulkBookingService(comparison, booking, resolver, customers, book);
    }

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
        PortalBulkBookingService service = svc(comparison, booking);
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
        var cap = org.mockito.ArgumentCaptor.forClass(RateComparisonService.CompareCommand.class);
        org.mockito.Mockito.verify(comparison).compareForBulk(cap.capture());
        assertThat(cap.getValue().pickup().name()).isEqualTo("Meera Shah");
        assertThat(cap.getValue().pickup().phone()).isEqualTo("9800000001");
        assertThat(cap.getValue().pickup().address()).isEqualTo("12 MG Road, Pune");
        assertThat(cap.getValue().pickup().pincode()).isEqualTo("411001");
    }

    @Test
    void junkFileIsRefusedWithAMessage() {
        PortalBulkBookingService service = svc(mock(RateComparisonService.class), mock(PortalBookingService.class));
        assertThatThrownBy(() -> service.preview(new ByteArrayInputStream("not excel".getBytes())))
                .hasMessageContaining("could not be read");
    }

    @Test
    void sheetWithoutDimensionColumnsIsAccepted() throws Exception {
        RateComparisonService comparison = mock(RateComparisonService.class);
        PortalBulkBookingService service = svc(comparison, mock(PortalBookingService.class));
        UUID quote = UUID.randomUUID(), opt = UUID.randomUUID();
        OptionView o = new OptionView(opt, "OCCPL_NETWORK", "OCCPL Network", "OCCPL Standard", "SURFACE", null, null,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, null, null, BigDecimal.ONE, new BigDecimal("59"), "INR", List.of(), Instant.now());
        QuoteView view = new QuoteView(quote, "OPEN", Instant.now(), "411001", "411004", BigDecimal.ONE, BigDecimal.ONE, 1,
                BigDecimal.TEN, "PREPAID", List.of(o), List.of());
        org.mockito.ArgumentCaptor<RateComparisonService.CompareCommand> cap = org.mockito.ArgumentCaptor.forClass(RateComparisonService.CompareCommand.class);
        when(comparison.compareForBulk(cap.capture())).thenReturn(new BulkQuote(view, opt, "CHEAPEST"));

        byte[] withDims = service.template();
        try (var in = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(withDims));
             var out = new java.io.ByteArrayOutputStream()) {
            var sheet = in.getSheetAt(0);
            for (int r = 0; r <= 1; r++) {
                var row = sheet.getRow(r);
                for (int c = 7; c <= 9; c++) {
                    row.removeCell(row.getCell(c));
                }
            }
            in.write(out);
            var preview = service.preview(new ByteArrayInputStream(out.toByteArray()));
            assertThat(preview.rows().get(0).ok()).isTrue();
        }
        assertThat(cap.getValue().pkg().length()).isEqualByComparingTo("10");
    }
}
