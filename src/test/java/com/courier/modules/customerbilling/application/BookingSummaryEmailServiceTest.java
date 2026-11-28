package com.courier.modules.customerbilling.application;

import com.courier.modules.communication.application.provider.EmailProvider;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.modules.customerbilling.domain.BookingSummaryEmailLog;
import com.courier.modules.customerbilling.domain.BookingSummaryEmailLogRepository;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingSummaryEmailServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 28);
    /** 27 Nov 23:30 IST → 28 Nov 23:30 IST. */
    private static final Instant FROM = Instant.parse("2026-11-27T18:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-28T18:00:00Z");

    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final ServiceTypeRepository serviceTypes = mock(ServiceTypeRepository.class);
    private final BookingSummaryEmailLogRepository logs = mock(BookingSummaryEmailLogRepository.class);
    private final EmailProvider email = mock(EmailProvider.class);
    private final InvoiceEmailSender identity = mock(InvoiceEmailSender.class);
    private final BookingSummaryEmailService service =
            new BookingSummaryEmailService(shipments, customers, serviceTypes, logs, email, identity);

    private final UUID surface = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ServiceType st = new ServiceType();
        st.setId(surface);
        st.setName("Surface");
        when(serviceTypes.findAll(any(Specification.class))).thenReturn(List.of(st));
        when(identity.identity(COMPANY)).thenReturn(new EmailProvider.EmailIdentity("OCCPL COURIER", "support@occpl.test"));
        when(logs.findByCompanyIdAndCustomerIdAndReportDate(any(), any(), any())).thenReturn(Optional.empty());
        when(logs.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("one e-mail per business customer listing their bookings — AWB, consignee, destination, pieces, service, status; no rate, no weight")
    void listsBookingsWithoutRateOrWeight() {
        Customer acme = customer("ACME Traders", "orders@acme.test", true);
        Shipment s = shipment(acme.getId(), "26110000029", "E2E Test Receiver", "Pune City", "411030");
        when(shipments.findBusinessBookingsCreatedBetween(COMPANY, FROM, TO, ShipmentStatus.CANCELLED)).thenReturn(List.of(s));

        int attempted = service.processCompany(COMPANY, DAY, FROM, TO);

        assertThat(attempted).isEqualTo(1);
        ArgumentCaptor<EmailProvider.EmailMessage> msg = ArgumentCaptor.forClass(EmailProvider.EmailMessage.class);
        verify(email).send(msg.capture(), eq(new EmailProvider.EmailIdentity("OCCPL COURIER", "support@occpl.test")));
        assertThat(msg.getValue().toEmail()).isEqualTo("orders@acme.test");
        assertThat(msg.getValue().subject()).isEqualTo("Your booking details dated: 28 Nov 2026 - 1 shipment");
        String html = msg.getValue().htmlBody();
        assertThat(html).contains("Dear ACME Traders", "26110000029", "E2E Test Receiver", "Pune City", "Surface",
                "27 Nov 2026, 11:30 PM", "28 Nov 2026, 11:30 PM", "Thanks,<br>Team OCCPL COURIER");
        // the shipment carries a weight and a price; neither may appear in what the reader sees
        String text = html.replaceAll("<[^>]*>", " ");
        // owner: no booking time, no pincode, no status column
        assertThat(text).doesNotContain("Booked on").doesNotContain("411030").doesNotContain("Status").doesNotContain("10:22 AM");
        assertThat(text).doesNotContainIgnoringCase("weight").doesNotContainIgnoringCase("rate")
                .doesNotContain("kg").doesNotContain("₹").doesNotContain("2.75").doesNotContain("90.00");
        ArgumentCaptor<BookingSummaryEmailLog> row = ArgumentCaptor.forClass(BookingSummaryEmailLog.class);
        verify(logs).save(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(BookingSummaryEmailLog.SENT);
        assertThat(row.getValue().getBookingCount()).isEqualTo(1);
        assertThat(row.getValue().getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("no e-mail address, or e-mail notifications off: nothing is sent")
    void skipsWithoutEmailOrWhenOff() {
        Customer off = customer("Off Ltd", "off@x.test", false);
        Customer none = customer("No Mail Ltd", " ", true);
        when(shipments.findBusinessBookingsCreatedBetween(COMPANY, FROM, TO, ShipmentStatus.CANCELLED)).thenReturn(List.of(
                shipment(off.getId(), "A1", "R", "Pune", "411001"), shipment(none.getId(), "A2", "R", "Pune", "411001")));

        assertThat(service.processCompany(COMPANY, DAY, FROM, TO)).isZero();
        verify(email, never()).send(any(), any());
    }

    @Test
    @DisplayName("already sent today: the retry tick sends nothing")
    void alreadySentNotRepeated() {
        Customer acme = customer("ACME", "orders@acme.test", true);
        when(shipments.findBusinessBookingsCreatedBetween(COMPANY, FROM, TO, ShipmentStatus.CANCELLED))
                .thenReturn(List.of(shipment(acme.getId(), "A1", "R", "Pune", "411001")));
        BookingSummaryEmailLog sent = new BookingSummaryEmailLog();
        sent.setStatus(BookingSummaryEmailLog.SENT);
        when(logs.findByCompanyIdAndCustomerIdAndReportDate(COMPANY, acme.getId(), DAY)).thenReturn(Optional.of(sent));

        assertThat(service.processCompany(COMPANY, DAY, FROM, TO)).isZero();
        verify(email, never()).send(any(), any());
    }

    @Test
    @DisplayName("a failed send is recorded FAILED with the reason, for the next tick to retry")
    void failureRecorded() {
        Customer acme = customer("ACME", "orders@acme.test", true);
        when(shipments.findBusinessBookingsCreatedBetween(COMPANY, FROM, TO, ShipmentStatus.CANCELLED))
                .thenReturn(List.of(shipment(acme.getId(), "A1", "R", "Pune", "411001")));
        when(email.send(any(), any())).thenThrow(new RuntimeException("SMTP down"));

        assertThat(service.processCompany(COMPANY, DAY, FROM, TO)).isEqualTo(1);
        ArgumentCaptor<BookingSummaryEmailLog> row = ArgumentCaptor.forClass(BookingSummaryEmailLog.class);
        verify(logs).save(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(BookingSummaryEmailLog.FAILED);
        assertThat(row.getValue().getErrorMessage()).isEqualTo("SMTP down");
        assertThat(row.getValue().getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("subject: 'Your booking details dated: 28 Nov 2026 - 4 shipments'")
    void subjectFormat() {
        assertThat(BookingSummaryEmailService.subject(DAY, 4)).isEqualTo("Your booking details dated: 28 Nov 2026 - 4 shipments");
        assertThat(BookingSummaryEmailService.subject(DAY, 1)).isEqualTo("Your booking details dated: 28 Nov 2026 - 1 shipment");
    }

    @Test
    @DisplayName("an uploaded logo is sent inside the e-mail and shown on top; a logo link is used as is; none → no image")
    void logo() {
        var uploaded = BookingSummaryEmailService.logoFrom("data:image/png;base64,iVBORw0KGgo=", "https://x.test/logo.png");
        assertThat(uploaded.src()).isEqualTo("cid:company-logo");
        assertThat(uploaded.inline().contentType()).isEqualTo("image/png");
        assertThat(uploaded.inline().bytes()).hasSize(8);
        assertThat(BookingSummaryEmailService.logoFrom(null, "https://x.test/logo.png").src()).isEqualTo("https://x.test/logo.png");
        assertThat(BookingSummaryEmailService.logoFrom(null, null)).isNull();

        String html = service.html("OCCPL Logistics", "cid:company-logo", "ACME", List.of(), java.util.Map.of(), FROM, TO);
        assertThat(html.indexOf("<img src=\"cid:company-logo\"")).isLessThan(html.indexOf("Dear ACME"));
        assertThat(html).contains("Thanks,<br>Team OCCPL Logistics");
    }

    private Customer customer(String name, String mail, boolean enabled) {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setCustomerType(com.courier.modules.customer.domain.CustomerType.BUSINESS);
        c.setCompanyName(name);
        c.setEmail(mail);
        c.setEmailEnabled(enabled);
        when(customers.findByIdWithinCompany(c.getId(), COMPANY)).thenReturn(Optional.of(c));
        return c;
    }

    private Shipment shipment(UUID customerId, String awb, String receiver, String city, String pin) {
        Shipment s = new Shipment();
        s.setId(UUID.randomUUID());
        s.setBusinessCustomerId(customerId);
        s.setTrackingNumber(awb);
        s.setShipmentNumber("TEST_BR-000014");
        s.setReceiverName(receiver);
        s.setToCity(city);
        s.setDeliveryPincode(pin);
        s.setNumberOfPackages(1);
        s.setServiceTypeId(surface);
        s.setStatus(ShipmentStatus.BOOKED);
        s.setCreatedAt(Instant.parse("2026-11-28T04:52:52Z"));
        s.setActualWeight(new BigDecimal("2.75"));
        return s;
    }
}
