package com.courier.modules.customerportal.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerportal.rates.application.PortalShipmentService;
import com.courier.modules.customerportal.rates.application.PortalShipmentService.BookingSummary;
import com.courier.modules.finance.application.CustomerWalletService;
import com.courier.modules.finance.domain.SubTransactionType;
import com.courier.modules.finance.domain.TransactionType;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.shared.exception.BusinessRuleException;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortalExportServiceTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private final ActingCustomer me = new ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final CurrentCustomerResolver resolver = mock(CurrentCustomerResolver.class);
    private final CustomerWalletService wallets = mock(CustomerWalletService.class);
    private final CustomerPortalLookup customers = mock(CustomerPortalLookup.class);
    private final PortalShipmentService shipments = mock(PortalShipmentService.class);
    private final PortalExportService service = new PortalExportService(resolver, wallets, customers, shipments);
    private final Wallet wallet = mock(Wallet.class);

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(me);
        when(wallet.getId()).thenReturn(UUID.randomUUID());
        when(wallet.getWalletNumber()).thenReturn("WLT1");
        when(wallets.provisionForCustomer(me.companyId(), me.customerId())).thenReturn(wallet);
        when(customers.findActiveBusinessCustomer(any(), any())).thenReturn(java.util.Optional.empty());
    }

    private static WalletTransaction tx(String no, TransactionType type, String amount, String before, String after, LocalDate day) {
        WalletTransaction t = mock(WalletTransaction.class);
        when(t.getTransactionNo()).thenReturn(no);
        when(t.getTransactionType()).thenReturn(type);
        when(t.getSubTransactionType()).thenReturn(type == TransactionType.CR ? SubTransactionType.values()[0] : null);
        when(t.getAmount()).thenReturn(new BigDecimal(amount));
        when(t.getBalanceBefore()).thenReturn(new BigDecimal(before));
        when(t.getBalanceAfter()).thenReturn(new BigDecimal(after));
        when(t.getRemarks()).thenReturn(no.equals("T3") ? "=cmd()" : "ok");
        when(t.getCreatedAt()).thenReturn(day.atTime(12, 0).atZone(INDIA).toInstant());
        return t;
    }

    private static Sheet read(byte[] xlsx) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheetAt(0);
    }

    @Test
    @DisplayName("statement: only the period's transactions, oldest first, with opening and closing balance and totals")
    void statement() throws Exception {
        LocalDate d = LocalDate.of(2026, 11, 3);
        // newest first, as the ledger returns them: one after the period, two inside, one before
        var after = tx("T4", TransactionType.DR, "10", "120", "110", d.plusDays(5));
        var in2 = tx("T3", TransactionType.DR, "80", "200", "120", d.plusDays(1));
        var in1 = tx("T2", TransactionType.CR, "100", "100", "200", d);
        var before = tx("T1", TransactionType.CR, "100", "0", "100", d.minusDays(3));
        when(wallets.ledgerOfWallet(eq(me.companyId()), any(), eq(0), anyInt()))
                .thenReturn(new PageImpl<>(List.of(after, in2, in1, before), PageRequest.of(0, 100), 4));

        Sheet s = read(service.walletStatement(d, d.plusDays(2)));

        assertThat(s.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(100.0);  // opening
        assertThat(s.getRow(5).getCell(1).getNumericCellValue()).isEqualTo(100.0);  // credits
        assertThat(s.getRow(6).getCell(1).getNumericCellValue()).isEqualTo(80.0);   // debits
        assertThat(s.getRow(7).getCell(1).getNumericCellValue()).isEqualTo(120.0);  // closing
        assertThat(s.getRow(10).getCell(1).getStringCellValue()).isEqualTo("T2");   // oldest first
        assertThat(s.getRow(11).getCell(1).getStringCellValue()).isEqualTo("T3");
        assertThat(s.getRow(11).getCell(7).getStringCellValue()).startsWith(" =");  // formula neutralised
        assertThat(s.getRow(12)).isNull();                                          // T4 (after) and T1 (before) left out
    }

    @Test
    @DisplayName("statement: an empty period keeps the earlier balance; bad or too long periods are refused")
    void statementEdges() throws Exception {
        LocalDate d = LocalDate.of(2026, 11, 3);
        var before = tx("T1", TransactionType.CR, "100", "0", "100", d.minusDays(3));
        when(wallets.ledgerOfWallet(any(), any(), eq(0), anyInt())).thenReturn(new PageImpl<>(List.of(before), PageRequest.of(0, 100), 1));

        Sheet s = read(service.walletStatement(d, d));
        assertThat(s.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(100.0);
        assertThat(s.getRow(7).getCell(1).getNumericCellValue()).isEqualTo(100.0);
        assertThat(s.getRow(10).getCell(0).getStringCellValue()).contains("No transactions");

        assertThatThrownBy(() -> service.walletStatement(d, d.minusDays(1))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.walletStatement(d, d.plusDays(400))).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("366");
    }

    private static BookingSummary row(String awb, String status) {
        return new BookingSummary(UUID.randomUUID(), status, "OCCPL_NETWORK", "OCCPL Network", "OCCPL SURFACE", "SURFACE", "PU-1", awb,
                "Pune", "411016", "Ravi", "Mumbai", "400001", 1, new BigDecimal("82.60"), "INR", Instant.now(), Instant.now(),
                new PortalShipmentService.Delivery("AWAITING_PICKUP", "Booked", null, Instant.now(), Instant.now()));
    }

    @Test
    @DisplayName("shipments: every page of the filtered list is written, with readable statuses")
    void shipmentsExport() throws Exception {
        when(shipments.list(eq("BOOKED"), any(), any(), any(), any(), eq(0), anyInt()))
                .thenReturn(new PageImpl<>(List.of(row("A1", "BOOKED")), PageRequest.of(0, 1), 2));
        when(shipments.list(eq("BOOKED"), any(), any(), any(), any(), eq(1), anyInt()))
                .thenReturn(new PageImpl<>(List.of(row("A2", "CANCELLED")), PageRequest.of(1, 1), 2));

        Sheet s = read(service.shipments("BOOKED", null, null, null, null));

        assertThat(s.getRow(1).getCell(1).getStringCellValue()).isEqualTo("A1");
        assertThat(s.getRow(2).getCell(1).getStringCellValue()).isEqualTo("A2");
        assertThat(s.getRow(2).getCell(5).getStringCellValue()).isEqualTo("Cancelled");
        assertThat(s.getRow(1).getCell(6).getStringCellValue()).isEqualTo("Booked");   // a booked shipment keeps its delivery status
        assertThat(s.getRow(2).getCell(6).getStringCellValue()).isEmpty();              // a cancelled booking does not show its last check
        assertThat(s.getRow(1).getCell(12).getNumericCellValue()).isEqualTo(82.6);
    }

    @Test
    @DisplayName("shipments: more than 5,000 matches is refused with advice to narrow the filters")
    void shipmentsLimit() {
        when(shipments.list(any(), any(), any(), any(), any(), eq(0), anyInt()))
                .thenReturn(new PageImpl<>(List.of(row("A1", "BOOKED")), PageRequest.of(0, 50), 6000));
        assertThatThrownBy(() -> service.shipments(null, null, null, null, null)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Narrow");
    }
}
