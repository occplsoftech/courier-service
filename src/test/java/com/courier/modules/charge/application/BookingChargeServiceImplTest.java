package com.courier.modules.charge.application;

import com.courier.modules.charge.application.BookingChargeService.Picked;
import com.courier.modules.charge.application.BookingChargeService.RateInput;
import com.courier.modules.charge.application.BookingChargeService.Resolved;
import com.courier.modules.charge.domain.BookingChargeCalc;
import com.courier.modules.charge.domain.BookingChargeCode;
import com.courier.modules.charge.domain.BookingChargeRate;
import com.courier.modules.charge.domain.BookingChargeRateRepository;
import com.courier.modules.charge.domain.ShipmentBookingChargeRepository;
import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingChargeServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    private final BookingChargeRateRepository rates = mock(BookingChargeRateRepository.class);
    private final List<BookingChargeRate> company = new ArrayList<>();
    private final List<BookingChargeRate> customer = new ArrayList<>();
    private BookingChargeServiceImpl service;

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static BookingChargeRate rate(UUID customerId, BookingChargeCode code, BookingChargeCalc calc, String value,
                                          String min, boolean active) {
        BookingChargeRate r = BookingChargeRate.of(customerId, code);
        r.set(calc, d(value), min == null ? null : d(min), active);
        return r;
    }

    @BeforeEach
    void setUp() {
        CompanyContext.setCompanyId(COMPANY);
        when(rates.findCompanyRates(COMPANY)).thenReturn(company);
        when(rates.findCustomerRates(eq(COMPANY), eq(CUSTOMER))).thenReturn(customer);
        when(rates.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new BookingChargeServiceImpl(rates, mock(ShipmentBookingChargeRepository.class),
                mock(CustomerPortalLookup.class), mock(AuditService.class));
        company.add(rate(null, BookingChargeCode.HANDLING, BookingChargeCalc.PER_KG, "5", "50", true));
        company.add(rate(null, BookingChargeCode.PICKUP, BookingChargeCalc.FIXED, "100", null, true));
        company.add(rate(null, BookingChargeCode.AWB, BookingChargeCalc.FIXED, "50", null, true));
        company.add(rate(null, BookingChargeCode.REVERSE_PICKUP, BookingChargeCalc.PERCENT_OF_FREIGHT, "10", "100", true));
        company.add(rate(null, BookingChargeCode.GREEN_TAX, BookingChargeCalc.FIXED, "20", null, false));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    @Test
    @DisplayName("each calculation, then the minimum: per kg rounds the weight up; % of freight; COD % or minimum")
    void amounts() {
        assertThat(BookingChargeRate.amount(BookingChargeCalc.PER_KG, d("5"), d("50"), d("250"), d("12.2"), 1, null)).isEqualByComparingTo("65");
        assertThat(BookingChargeRate.amount(BookingChargeCalc.PER_KG, d("5"), d("50"), d("250"), d("4"), 1, null)).isEqualByComparingTo("50");
        assertThat(BookingChargeRate.amount(BookingChargeCalc.PER_PIECE, d("15"), null, null, null, 3, null)).isEqualByComparingTo("45");
        assertThat(BookingChargeRate.amount(BookingChargeCalc.PERCENT_OF_FREIGHT, d("10"), d("100"), d("2500"), null, 1, null)).isEqualByComparingTo("250");
        assertThat(BookingChargeRate.amount(BookingChargeCalc.PERCENT_OF_COD, d("2"), d("100"), null, null, 1, d("8000"))).isEqualByComparingTo("160");
        assertThat(BookingChargeRate.amount(BookingChargeCalc.PERCENT_OF_COD, d("2"), d("100"), null, null, 1, d("1000"))).isEqualByComparingTo("100");
        assertThat(BookingChargeRate.amount(BookingChargeCalc.PERCENT_OF_COD, d("2"), d("100"), null, null, 1, null)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a company without a COD row keeps today's COD: 2% of the COD amount, at least 100")
    void codDefault() {
        Resolved r = service.resolve(null, null, d("250"), d("4"), 1, d("8000"));
        assertThat(r.codCharge()).isEqualByComparingTo("160");
        assertThat(r.othersTotal()).isEqualByComparingTo("0");
        assertThat(r.lines()).isEmpty();
    }

    @Test
    @DisplayName("the quote lists active charges only, the customer's override first")
    void quote() {
        customer.add(rate(CUSTOMER, BookingChargeCode.AWB, BookingChargeCalc.FIXED, "0", null, true));
        var lines = service.quote(CUSTOMER, d("250"), d("4"), 1, null);
        assertThat(lines).extracting(BookingChargeService.QuoteLine::code).containsExactly(BookingChargeCode.HANDLING,
                BookingChargeCode.PICKUP, BookingChargeCode.AWB, BookingChargeCode.REVERSE_PICKUP, BookingChargeCode.COD);
        assertThat(lines.get(2).amount()).isEqualByComparingTo("0");
        assertThat(lines.get(2).customerRate()).isTrue();
        assertThat(lines.get(0).amount()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("picked charges: amounts as calculated or as typed; COD only when picked and only on COD")
    void picked() {
        Resolved r = service.resolve(null, List.of(new Picked(BookingChargeCode.HANDLING, null),
                new Picked(BookingChargeCode.PICKUP, d("80")), new Picked(BookingChargeCode.COD, d("1"))), d("250"), d("4"), 1, d("8000"));
        assertThat(r.othersTotal()).isEqualByComparingTo("130");
        assertThat(r.codCharge()).isEqualByComparingTo("160");
        assertThat(r.lines()).extracting(BookingChargeService.Line::amount).map(BigDecimal::toPlainString)
                .containsExactly("50.00", "80.00", "160.00");

        Resolved unticked = service.resolve(null, List.of(new Picked(BookingChargeCode.PICKUP, null)), d("250"), d("4"), 1, d("8000"));
        assertThat(unticked.codCharge()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an inactive, repeated or COD-without-COD pick is refused")
    void refused() {
        assertThatThrownBy(() -> service.resolve(null, List.of(new Picked(BookingChargeCode.GREEN_TAX, null)), d("1"), d("1"), 1, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not active");
        assertThatThrownBy(() -> service.resolve(null, List.of(new Picked(BookingChargeCode.AWB, null),
                new Picked(BookingChargeCode.AWB, null)), d("1"), d("1"), 1, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> service.resolve(null, List.of(new Picked(BookingChargeCode.COD, null)), d("1"), d("1"), 1, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Cash on Delivery");
    }

    @Test
    @DisplayName("COD must be a % of the COD amount, and nothing else may be")
    void codCalcRule() {
        assertThatThrownBy(() -> service.saveCompanyRate(BookingChargeCode.COD, new RateInput(BookingChargeCalc.FIXED, d("50"), null, true)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.saveCompanyRate(BookingChargeCode.AWB, new RateInput(BookingChargeCalc.PERCENT_OF_COD, d("2"), null, true)))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(service.saveCompanyRate(BookingChargeCode.COD, new RateInput(null, d("2.5"), d("120"), true)).calc())
                .isEqualTo(BookingChargeCalc.PERCENT_OF_COD);
    }
}
