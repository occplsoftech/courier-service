package com.courier.modules.customerportal.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerbilling.domain.ApprovalStatus;
import com.courier.modules.customerbilling.domain.CustomerInvoice;
import com.courier.modules.customerbilling.domain.CustomerInvoiceLineRepository;
import com.courier.modules.customerbilling.domain.CustomerInvoicePayment;
import com.courier.modules.customerbilling.domain.CustomerInvoicePaymentRepository;
import com.courier.modules.customerbilling.domain.CustomerInvoiceRepository;
import com.courier.modules.customerbilling.domain.InvoiceStatus;
import com.courier.modules.customerbilling.domain.PaymentMethod;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortalInvoiceServiceTest {

    private final ActingCustomer me = new ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final CurrentCustomerResolver resolver = mock(CurrentCustomerResolver.class);
    private final CustomerInvoiceRepository invoices = mock(CustomerInvoiceRepository.class);
    private final CustomerInvoicePaymentRepository payments = mock(CustomerInvoicePaymentRepository.class);
    private final CustomerPortalLookup customers = mock(CustomerPortalLookup.class);
    private final PortalInvoiceService service = new PortalInvoiceService(resolver, invoices, mock(CustomerInvoiceLineRepository.class),
            payments, customers);
    private CustomerInvoice jan, feb, cancelled;

    private CustomerInvoice invoice(String no, LocalDate date, String total, String prepaid, String paid, InvoiceStatus status) {
        CustomerInvoice i = CustomerInvoice.builder().customerId(me.customerId()).invoiceNumber(no).invoiceDate(date)
                .periodFrom(date.minusDays(30)).periodTo(date).status(status).approvalStatus(ApprovalStatus.APPROVED)
                .shipmentsCount(3).totalAmount(new BigDecimal(total)).prepaidAmount(new BigDecimal(prepaid)).amountPaid(new BigDecimal(paid))
                .gstAmount(BigDecimal.ZERO).build();
        i.setId(UUID.randomUUID());
        i.setCompanyId(me.companyId());
        return i;
    }

    private CustomerInvoicePayment payment(CustomerInvoice i, LocalDate on, String amount) {
        CustomerInvoicePayment p = CustomerInvoicePayment.builder().invoiceId(i.getId()).paidOn(on).amount(new BigDecimal(amount))
                .method(PaymentMethod.UPI).reference("UTR1").build();
        p.setCompanyId(me.companyId());
        return p;
    }

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(me);
        when(customers.findActiveBusinessCustomer(any(), any())).thenReturn(Optional.empty());
        jan = invoice("INV-1", LocalDate.of(2026, 1, 31), "1000", "100", "900", InvoiceStatus.PAID);       // due 900, paid
        feb = invoice("INV-2", LocalDate.of(2026, 2, 28), "500", "0", "200", InvoiceStatus.PARTLY_PAID);   // due 500, 300 owed
        cancelled = invoice("INV-X", LocalDate.of(2026, 2, 10), "700", "0", "0", InvoiceStatus.CANCELLED);
        when(invoices.findApprovedForCustomer(me.companyId(), me.customerId())).thenReturn(List.of(feb, cancelled, jan));
        when(payments.findByInvoice(me.companyId(), jan.getId())).thenReturn(List.of(payment(jan, LocalDate.of(2026, 2, 5), "900")));
        when(payments.findByInvoice(me.companyId(), feb.getId())).thenReturn(List.of(payment(feb, LocalDate.of(2026, 3, 3), "200")));
        when(payments.findByInvoice(me.companyId(), cancelled.getId())).thenReturn(List.of());
    }

    @Test
    @DisplayName("summary: outstanding is what is still owed (total minus prepaid minus paid), cancelled invoices owe nothing")
    void summary() {
        var s = service.summary();

        assertThat(s.outstanding()).isEqualByComparingTo("300");
        assertThat(s.unpaidInvoices()).isEqualTo(1);
        assertThat(s.lastPaymentOn()).isEqualTo(LocalDate.of(2026, 3, 3));
        assertThat(s.invoices()).extracting(PortalInvoiceService.InvoiceRow::invoiceNumber).containsExactly("INV-2", "INV-X", "INV-1");
        assertThat(s.invoices().get(2).due()).isEqualByComparingTo("900");
        assertThat(s.invoices().get(1).balance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("detail: only the customer's own approved invoice opens")
    void detailIsOwnOnly() {
        assertThat(service.detail(feb.getId()).payments()).hasSize(1);
        assertThatThrownBy(() -> service.detail(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("statement: opening balance before the period, each invoice and payment with a running balance, cancelled left out")
    void statement() {
        var s = service.statement(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31));

        assertThat(s.opening()).isEqualByComparingTo("900");          // January invoice, not yet paid on 1 Feb
        assertThat(s.invoiced()).isEqualByComparingTo("500");
        assertThat(s.paid()).isEqualByComparingTo("1100");
        assertThat(s.closing()).isEqualByComparingTo("300");
        assertThat(s.lines()).extracting(PortalInvoiceService.StatementLine::reference)
                .containsExactly("INV-1 · UTR1", "INV-2", "INV-2 · UTR1");
        assertThat(s.lines().get(0).balance()).isEqualByComparingTo("0");
        assertThat(s.lines().get(2).balance()).isEqualByComparingTo("300");

        assertThatThrownBy(() -> service.statement(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 1))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.statement(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 3, 1))).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("statement export: the same figures in an Excel sheet")
    void statementSheet() throws Exception {
        byte[] xlsx = service.statementSheet(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31));
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(900.0);
            assertThat(sheet.getRow(5).getCell(1).getNumericCellValue()).isEqualTo(300.0);
            assertThat(sheet.getRow(8).getCell(2).getStringCellValue()).isEqualTo("INV-1 · UTR1");
        }
    }
}
