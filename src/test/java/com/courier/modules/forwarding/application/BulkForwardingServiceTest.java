package com.courier.modules.forwarding.application;

import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BulkForwardingServiceTest {

    private final ForwardingService single = mock(ForwardingService.class);
    private final BulkForwardingService bulk = new BulkForwardingService(single);

    @Test
    void oneFailureNeverStopsTheRest() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        when(single.forward(a, "DELHIVERY", "S")).thenReturn(new ForwardingService.ForwardResult(a, "FORWARDED", "Forwarded", "DELHIVERY", "Surface",
                "AWB1", "https://l/1", new BigDecimal("80")));
        when(single.forward(b, "SHIPROCKET", "X")).thenThrow(new BusinessRuleException("Shiprocket did not accept it: pincode not serviceable"));
        when(single.forward(c, "DELHIVERY", "S")).thenThrow(new IllegalStateException("boom"));
        when(single.forward(d, "DELHIVERY", "S")).thenReturn(new ForwardingService.ForwardResult(d, "UNCONFIRMED", "Check", "DELHIVERY", "Surface",
                null, null, new BigDecimal("80")));

        List<BulkForwardingService.ResultRow> rows = bulk.forward(List.of(new BulkForwardingService.Item(a, "DELHIVERY", "S"),
                new BulkForwardingService.Item(b, "SHIPROCKET", "X"), new BulkForwardingService.Item(c, "DELHIVERY", "S"),
                new BulkForwardingService.Item(d, "DELHIVERY", "S"), new BulkForwardingService.Item(a, "DELHIVERY", "S")));

        assertThat(rows).extracting(BulkForwardingService.ResultRow::outcome)
                .containsExactly("FORWARDED", "REFUSED", "ERROR", "UNCONFIRMED", "REFUSED");
        assertThat(rows.get(1).message()).contains("pincode not serviceable");
        assertThat(rows.get(4).message()).contains("Listed twice");
        verify(single, times(1)).forward(a, "DELHIVERY", "S");
    }

    @Test
    void optionsRowPerShipmentWithErrors() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        when(single.options(a)).thenReturn(new ForwardingService.ForwardOptions(a, "SH-1", null, "Hub, 411001", List.of(), List.of()));
        when(single.options(b)).thenThrow(new org.springframework.security.access.AccessDeniedException("no"));
        List<BulkForwardingService.OptionsRow> rows = bulk.options(List.of(a, b, a));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).options().shipmentNumber()).isEqualTo("SH-1");
        assertThat(rows.get(1).error()).contains("may not");
    }

    @Test
    void limits() {
        assertThatThrownBy(() -> bulk.forward(List.of())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> bulk.options(Collections.nCopies(21, null).stream().map(x -> UUID.randomUUID()).toList()))
                .hasMessageContaining("At most 20");
        verify(single, times(0)).options(any());
    }
}
