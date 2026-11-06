package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.company.domain.CompanyRepository;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortalBookingDirectoryAdapterTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private final PortalBookingRepository bookings = mock(PortalBookingRepository.class);
    private final RateQuoteRepository quotes = mock(RateQuoteRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final PortalBookingDirectoryAdapter adapter = new PortalBookingDirectoryAdapter(bookings, quotes, companies, customers);

    private PortalBooking booking(String provider) {
        PortalBooking b = PortalBooking.builder().customerId(UUID.randomUUID()).quoteId(UUID.randomUUID()).providerCode(provider)
                .status(PortalBooking.Status.BOOKED).awbNumber("SR123").providerOrderRef("ORD-9").totalAmount(new BigDecimal("150"))
                .currency("INR").build();
        b.setId(UUID.randomUUID());
        b.setCompanyId(COMPANY);
        when(bookings.findById(b.getId())).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    @DisplayName("an outside courier's booking: the customer's preferences for the sender, the entered receiver, the AWB and price")
    void buildsTheSnapshot() {
        PortalBooking b = booking("SHIPROCKET");
        RateQuote q = mock(RateQuote.class);
        when(q.getRequest()).thenReturn(Map.of(
                "pickup", Map.of("name", "Acme", "phone", "9800000001", "city", "Pune"),
                "delivery", Map.of("name", "Ravi", "phone", "9800000002", "city", "Mumbai")));
        when(quotes.findOwned(b.getQuoteId(), COMPANY, b.getCustomerId())).thenReturn(Optional.of(q));
        Customer owner = mock(Customer.class);
        when(owner.getCompanyId()).thenReturn(COMPANY);
        when(owner.getId()).thenReturn(b.getCustomerId());
        when(owner.getMobile()).thenReturn("9800000001");
        when(owner.getEmail()).thenReturn("acme@test");
        when(owner.isWhatsappEnabled()).thenReturn(true);
        when(owner.isSmsEnabled()).thenReturn(false);
        when(owner.isEmailEnabled()).thenReturn(true);
        when(customers.findById(b.getCustomerId())).thenReturn(Optional.of(owner));
        when(customers.findByCompanyIdAndMobile(any(), any())).thenReturn(Optional.empty());

        var s = adapter.findSnapshot(COMPANY, b.getId()).orElseThrow();

        assertThat(s.shipmentId()).isEqualTo(b.getId());
        assertThat(s.trackingNumber()).isEqualTo("SR123");
        assertThat(s.shipmentNumber()).isEqualTo("ORD-9");
        assertThat(s.amount()).isEqualByComparingTo("150");
        assertThat(s.pickupLocation()).isEqualTo("Pune");
        assertThat(s.deliveryLocation()).isEqualTo("Mumbai");
        assertThat(s.sender().email()).isEqualTo("acme@test");
        assertThat(s.sender().smsEnabled()).isFalse();
        assertThat(s.receiver().name()).isEqualTo("Ravi");
        assertThat(s.receiver().contact()).isEqualTo("9800000002");
        assertThat(s.receiver().whatsappEnabled()).isTrue();
    }

    @Test
    @DisplayName("an OCCPL Network booking, or another company's booking, gives nothing (its shipment speaks for it)")
    void skipsOwnNetworkAndOtherCompanies() {
        PortalBooking own = booking("OCCPL_NETWORK");
        assertThat(adapter.findSnapshot(COMPANY, own.getId())).isEmpty();

        PortalBooking other = booking("DELHIVERY");
        assertThat(adapter.findSnapshot(UUID.randomUUID(), other.getId())).isEmpty();
    }
}
