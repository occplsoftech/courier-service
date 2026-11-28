package com.courier.modules.shipment.infrastructure;

import com.courier.modules.finance.domain.BranchUserActivityPort.Booking;
import com.courier.modules.finance.domain.BranchUserActivityPort.Delivery;
import com.courier.modules.finance.domain.BranchUserActivityPort.PaymentKind;
import com.courier.modules.master.application.PaymentModeService;
import com.courier.modules.master.domain.PaymentMode;
import com.courier.modules.shipment.domain.DeliveryAssignment;
import com.courier.modules.shipment.domain.DeliveryAssignmentRepository;
import com.courier.modules.shipment.domain.DeliveryAssignmentStatus;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentCharge;
import com.courier.modules.shipment.domain.ShipmentChargeRepository;
import com.courier.modules.shipment.domain.ShipmentPayment;
import com.courier.modules.shipment.domain.ShipmentPaymentRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShipmentUserActivityDirectoryTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 28);

    @Mock private ShipmentRepository shipments;
    @Mock private ShipmentPaymentRepository payments;
    @Mock private ShipmentChargeRepository charges;
    @Mock private DeliveryAssignmentRepository runs;
    @Mock private PaymentModeService paymentModes;

    private ShipmentUserActivityDirectory directory;
    private final PaymentMode paid = mode("Paid", true, false, false, false);
    private final PaymentMode toPay = mode("To Pay", false, true, false, false);
    private final PaymentMode cod = mode("COD", false, true, true, false);
    private final PaymentMode credit = mode("Credit", false, false, false, true);

    @BeforeEach
    void setUp() {
        directory = new ShipmentUserActivityDirectory(shipments, payments, charges, runs, paymentModes);
        for (PaymentMode m : List.of(paid, toPay, cod, credit)) {
            when(paymentModes.getById(m.getId())).thenReturn(m);
        }
    }

    @Test
    @DisplayName("payment kinds follow the payment mode's flags")
    void kinds() {
        assertThat(ShipmentUserActivityDirectory.kindOf(paid)).isEqualTo(PaymentKind.PAID);
        assertThat(ShipmentUserActivityDirectory.kindOf(toPay)).isEqualTo(PaymentKind.TO_PAY);
        assertThat(ShipmentUserActivityDirectory.kindOf(cod)).isEqualTo(PaymentKind.COD);
        assertThat(ShipmentUserActivityDirectory.kindOf(credit)).isEqualTo(PaymentKind.CREDIT);
    }

    @Test
    @DisplayName("a Paid booking takes its counter payment; one booked before counter payments existed counts as cash")
    void counterPayments() {
        Shipment upi = shipment("S1", paid, null);
        Shipment legacy = shipment("S2", paid, null);
        Shipment pending = shipment("S3", paid, null);
        when(shipments.findAllByCompanyIdAndBookingBranchIdAndBookingDateBetween(COMPANY, BRANCH, DAY, DAY))
                .thenReturn(List.of(upi, legacy, pending));
        when(charges.findByShipmentIdIn(anyCollection())).thenReturn(List.of(
                charge(upi, "300"), charge(legacy, "450"), charge(pending, "200")));
        when(payments.findAllByShipmentIdIn(anyCollection())).thenReturn(List.of(
                payment(upi, ShipmentPayment.Method.UPI_QR, ShipmentPayment.Status.PAID, "300"),
                payment(pending, ShipmentPayment.Method.RAZORPAY, ShipmentPayment.Status.PENDING, "200")));

        Map<String, Booking> byNumber = directory.bookings(COMPANY, BRANCH, DAY, DAY).stream()
                .collect(java.util.stream.Collectors.toMap(Booking::shipmentNumber, b -> b));

        assertThat(byNumber.get("S1").counterMethod()).isEqualTo("UPI_QR");
        assertThat(byNumber.get("S1").counterAmount()).isEqualByComparingTo("300");
        assertThat(byNumber.get("S2").counterMethod()).isEqualTo("CASH");
        assertThat(byNumber.get("S2").counterPaid()).isTrue();
        assertThat(byNumber.get("S2").counterAmount()).isEqualByComparingTo("450");
        assertThat(byNumber.get("S3").counterPaid()).isFalse();
        assertThat(byNumber.get("S3").counterAmount()).isEqualByComparingTo("0");
        assertThat(byNumber.get("S1").bookedBy()).isEqualTo(USER);
    }

    @Test
    @DisplayName("on delivery: freight for To-Pay and counter COD, the customer's COD amount for a portal COD")
    void deliveryCollection() {
        Shipment topay = shipment("T1", toPay, null);
        Shipment counterCod = shipment("C1", cod, null);
        Shipment portalCod = shipment("C2", cod, new BigDecimal("1500"));
        Shipment billed = shipment("K1", credit, null);
        List<Shipment> all = List.of(topay, counterCod, portalCod, billed);
        when(runs.findForBranchAssignedOrDeliveredBetween(any(), any(), any(), any()))
                .thenReturn(all.stream().map(s -> run(s, s != billed)).toList());
        when(shipments.findAllByCompanyIdAndIdIn(any(), anyCollection())).thenReturn(all);
        when(charges.findByShipmentIdIn(anyCollection())).thenReturn(List.of(
                charge(topay, "250"), charge(counterCod, "320"), charge(portalCod, "90"), charge(billed, "600")));

        Map<String, Delivery> byNumber = directory.deliveries(COMPANY, BRANCH, Instant.EPOCH, Instant.now()).stream()
                .collect(java.util.stream.Collectors.toMap(Delivery::shipmentNumber, d -> d));

        assertThat(byNumber.get("T1").amountToCollect()).isEqualByComparingTo("250");
        assertThat(byNumber.get("C1").amountToCollect()).isEqualByComparingTo("320");
        assertThat(byNumber.get("C2").amountToCollect()).isEqualByComparingTo("1500");
        assertThat(byNumber.get("K1").amountToCollect()).isEqualByComparingTo("0");
        assertThat(byNumber.get("K1").deliveredAt()).as("still out").isNull();
        assertThat(byNumber.get("T1").deliveredAt()).isNotNull();
        assertThat(byNumber.get("T1").deliveryUserId()).isEqualTo(USER);
    }

    private static PaymentMode mode(String name, boolean atBooking, boolean atDelivery, boolean cod, boolean credit) {
        PaymentMode m = new PaymentMode();
        m.setName(name);
        m.setCollectAtBooking(atBooking);
        m.setCollectAtDelivery(atDelivery);
        m.setCashOnDelivery(cod);
        m.setRequiresCreditAccount(credit);
        return m;
    }

    private static Shipment shipment(String number, PaymentMode mode, BigDecimal codAmount) {
        Shipment s = Shipment.builder().shipmentNumber(number).bookingBranchId(BRANCH).bookingDate(DAY)
                .paymentModeId(mode.getId()).status(ShipmentStatus.BOOKED).codAmount(codAmount).build();
        s.setCreatedBy(USER);
        return s;
    }

    private static ShipmentCharge charge(Shipment s, String net) {
        ShipmentCharge c = ShipmentCharge.builder().shipmentId(s.getId()).build();
        c.setNetAmount(new BigDecimal(net));
        return c;
    }

    private static ShipmentPayment payment(Shipment s, ShipmentPayment.Method method, ShipmentPayment.Status status,
                                           String amount) {
        ShipmentPayment p = new ShipmentPayment();
        p.setShipmentId(s.getId());
        p.setMethod(method);
        p.setStatus(status);
        p.setAmount(new BigDecimal(amount));
        return p;
    }

    private static DeliveryAssignment run(Shipment s, boolean delivered) {
        DeliveryAssignment d = DeliveryAssignment.builder().shipmentId(s.getId()).deliveryBranchId(BRANCH)
                .deliveryUserId(USER).drsNumber("DRS1").assignedAt(Instant.parse("2026-11-28T03:00:00Z")).build();
        d.setStatus(delivered ? DeliveryAssignmentStatus.DELIVERED : DeliveryAssignmentStatus.ASSIGNED);
        d.setDeliveredAt(delivered ? Instant.parse("2026-11-28T08:00:00Z") : null);
        return d;
    }
}
