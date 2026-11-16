package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.application.PortalOrderService.Buyer;
import com.courier.modules.customerportal.rates.application.PortalOrderService.Item;
import com.courier.modules.customerportal.rates.application.PortalOrderService.OrderInput;
import com.courier.modules.customerportal.rates.application.PortalOrderService.Parcel;
import com.courier.modules.customerportal.rates.application.PortalOrderService.Payment;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.PortalOrder;
import com.courier.modules.customerportal.rates.domain.PortalOrderRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalOrderServiceTest {

    private static final ActingCustomer ACTING = new ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final CustomerApiKeyService.KeyOwner OWNER = new CustomerApiKeyService.KeyOwner(ACTING.companyId(), ACTING.customerId(), UUID.randomUUID());

    private final PortalOrderRepository orders = mock(PortalOrderRepository.class);
    private final CurrentCustomerResolver resolver = mock(CurrentCustomerResolver.class);
    private final RateComparisonService comparison = mock(RateComparisonService.class);
    private final PortalBulkBookingService bulk = mock(PortalBulkBookingService.class);
    private PortalOrderService service;

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(ACTING);
        when(orders.saveAndFlush(any(PortalOrder.class))).thenAnswer(i -> i.getArgument(0));
        when(orders.save(any(PortalOrder.class))).thenAnswer(i -> i.getArgument(0));
        service = new PortalOrderService(orders, mock(PortalBookingRepository.class), resolver, comparison, bulk, mock(AuditService.class));
    }

    private static OrderInput input(String ref, String phone, String pin, Parcel parcel, Payment pay) {
        return new OrderInput(ref, new Buyer("Ravi Kumar", phone, "12 MG Road, Camp, Pune", pin, null, null), parcel, pay,
                List.of(new Item("T-shirt", 2, new BigDecimal("499"))), null);
    }

    private static Parcel value(String v) {
        return new Parcel(null, null, null, null, null, null, new BigDecimal(v));
    }

    @Test
    @DisplayName("an order from the API is checked and saved with defaults; the same order number again returns it, not a second one")
    void receive() {
        when(orders.findByRef(ACTING.companyId(), ACTING.customerId(), PortalOrder.Source.API, "WEB-1001")).thenReturn(Optional.empty());
        var r = service.receive(OWNER, input("WEB-1001", "+91 98765 43210", "411001", value("998"), null));
        assertThat(r.created()).isTrue();
        assertThat(r.status()).isEqualTo("NEW");
        org.mockito.ArgumentCaptor<PortalOrder> saved = org.mockito.ArgumentCaptor.forClass(PortalOrder.class);
        verify(orders).saveAndFlush(saved.capture());
        PortalOrder o = saved.getValue();
        assertThat(o.getDeliveryPhone()).isEqualTo("9876543210");
        assertThat(o.getWeightKg()).isEqualByComparingTo("0.5");
        assertThat(o.getLengthCm()).isEqualByComparingTo("10");
        assertThat(o.getPackageCount()).isEqualTo(1);
        assertThat(o.getContents()).isEqualTo("General goods");
        assertThat(o.getPaymentMode()).isEqualTo("PREPAID");
        assertThat(o.getCodAmount()).isNull();
        assertThat(o.getItems()).hasSize(1);

        when(orders.findByRef(ACTING.companyId(), ACTING.customerId(), PortalOrder.Source.API, "WEB-1001")).thenReturn(Optional.of(o));
        var again = service.receive(OWNER, input("WEB-1001", "9876543210", "411001", value("998"), null));
        assertThat(again.created()).isFalse();
        assertThat(again.orderId()).isEqualTo(o.getId());
    }

    @Test
    @DisplayName("COD takes the declared value unless an amount is given; bad data is refused field by field")
    void validation() {
        PortalOrder o = new PortalOrder();
        PortalOrderService.apply(o, input("A", "9876543210", "411001", value("1500"), new Payment("cod", null)));
        assertThat(o.getPaymentMode()).isEqualTo("COD");
        assertThat(o.getCodAmount()).isEqualByComparingTo("1500");

        assertThatThrownBy(() -> PortalOrderService.apply(new PortalOrder(), input("A", "12345", "411001", value("1"), null)))
                .hasMessageContaining("buyer.phone");
        assertThatThrownBy(() -> PortalOrderService.apply(new PortalOrder(), input("A", "9876543210", "0110", value("1"), null)))
                .hasMessageContaining("buyer.pincode");
        assertThatThrownBy(() -> PortalOrderService.apply(new PortalOrder(), input("A", "9876543210", "411001",
                new Parcel(null, null, null, null, null, null, null), null))).hasMessageContaining("declaredValue");
        assertThatThrownBy(() -> PortalOrderService.apply(new PortalOrder(), input("A", "9876543210", "411001", value("1"),
                new Payment("TOPAY", null)))).hasMessageContaining("PREPAID or COD");
        assertThatThrownBy(() -> PortalOrderService.ref("bad ref with spaces")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("an order is marked booked once, and only from New")
    void markBooked() {
        PortalOrder o = PortalOrder.builder().customerId(ACTING.customerId()).externalRef("WEB-1").source(PortalOrder.Source.API).build();
        o.setCompanyId(ACTING.companyId());
        when(orders.findById(o.getId())).thenReturn(Optional.of(o));
        UUID booking = UUID.randomUUID();
        service.markBooked(ACTING.companyId(), o.getId(), booking);
        assertThat(o.getStatus()).isEqualTo(PortalOrder.Status.BOOKED);
        assertThat(o.getBookingId()).isEqualTo(booking);

        org.mockito.Mockito.clearInvocations(orders);
        service.markBooked(ACTING.companyId(), o.getId(), UUID.randomUUID());
        verify(orders, never()).save(any());
        assertThat(o.getBookingId()).isEqualTo(booking);
    }

    @Test
    @DisplayName("pricing several orders: each quote carries its order; a booked or archived order is listed with the reason")
    void quote() {
        PortalOrder open = PortalOrder.builder().customerId(ACTING.customerId()).externalRef("WEB-2").source(PortalOrder.Source.API).build();
        PortalOrderService.apply(open, input("WEB-2", "9876543210", "400001", value("750"), null));
        PortalOrder archived = PortalOrder.builder().customerId(ACTING.customerId()).externalRef("WEB-3").source(PortalOrder.Source.API)
                .status(PortalOrder.Status.ARCHIVED).build();
        when(orders.findOwned(open.getId(), ACTING.companyId(), ACTING.customerId())).thenReturn(Optional.of(open));
        when(orders.findOwned(archived.getId(), ACTING.companyId(), ACTING.customerId())).thenReturn(Optional.of(archived));
        when(bulk.contact()).thenReturn(new PortalBulkBookingService.Contact("Test Shop", "9876500000", "Model Colony, Pune", "411016"));
        when(bulk.city("400001")).thenReturn("Mumbai");
        when(comparison.compareForBulk(any())).thenThrow(new BusinessRuleException("No courier serves 400001."));

        var rows = service.quote(List.of(open.getId(), archived.getId()));

        org.mockito.ArgumentCaptor<RateComparisonService.CompareCommand> cmd = org.mockito.ArgumentCaptor.forClass(RateComparisonService.CompareCommand.class);
        verify(comparison).compareForBulk(cmd.capture());
        assertThat(cmd.getValue().orderId()).isEqualTo(open.getId());
        assertThat(cmd.getValue().delivery().city()).isEqualTo("Mumbai");
        assertThat(cmd.getValue().pickup().pincode()).isEqualTo("411016");
        assertThat(rows).extracting(PortalOrderService.QuoteRow::ok).containsExactly(false, false);
        assertThat(rows.get(0).error()).contains("No courier");
        assertThat(rows.get(1).error()).contains("already archived");
        assertThatThrownBy(() -> service.quote(List.of())).hasMessageContaining("Choose");
    }
}
