package com.courier.modules.shipment.infrastructure;

import com.courier.modules.finance.domain.BranchUserActivityPort;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Answers Finance's {@link BranchUserActivityPort} from {@code shipments}, {@code shipment_payments},
 * {@code shipment_charges} and {@code delivery_assignment}. Direct repository reads, no {@code @PreAuthorize}: the
 * User Performance Report's own service already decided which branch the caller may read (same seam as
 * {@link ShipmentPendingCommissionDirectory}).
 *
 * <p>The amount a delivery boy collects mirrors {@code ShipmentServiceImpl.deliver}: a portal COD shipment collects the
 * customer's COD amount, a counter COD or To-Pay shipment collects its freight (net amount).
 */
@Component
@RequiredArgsConstructor
public class ShipmentUserActivityDirectory implements BranchUserActivityPort {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentPaymentRepository paymentRepository;
    private final ShipmentChargeRepository chargeRepository;
    private final DeliveryAssignmentRepository deliveryAssignmentRepository;
    private final PaymentModeService paymentModeService;

    @Override
    @Transactional(readOnly = true)
    public List<Booking> bookings(UUID companyId, UUID branchId, LocalDate from, LocalDate to) {
        List<Shipment> shipments = shipmentRepository
                .findAllByCompanyIdAndBookingBranchIdAndBookingDateBetween(companyId, branchId, from, to);
        if (shipments.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = shipments.stream().map(Shipment::getId).toList();
        Map<UUID, BigDecimal> net = netAmounts(ids);
        Map<UUID, ShipmentPayment> payments = paymentRepository.findAllByShipmentIdIn(ids).stream()
                .collect(Collectors.toMap(ShipmentPayment::getShipmentId, Function.identity(), (a, b) -> a));
        Map<UUID, PaymentMode> modes = new HashMap<>();

        return shipments.stream().map(s -> {
            PaymentMode mode = modes.computeIfAbsent(s.getPaymentModeId(), paymentModeService::getById);
            PaymentKind kind = kindOf(mode);
            BigDecimal amount = net.getOrDefault(s.getId(), BigDecimal.ZERO);
            String method = null;
            boolean paid = false;
            BigDecimal taken = BigDecimal.ZERO;
            if (kind == PaymentKind.PAID) {
                ShipmentPayment p = payments.get(s.getId());
                if (p == null) {
                    // Booked before counter payments were recorded (shipment_payments): those were cash at the counter.
                    method = ShipmentPayment.Method.CASH.name();
                    paid = true;
                    taken = amount;
                } else {
                    method = p.getMethod().name();
                    paid = p.getStatus() == ShipmentPayment.Status.PAID;
                    taken = paid ? (p.getPaidAmount() != null ? p.getPaidAmount() : p.getAmount()) : BigDecimal.ZERO;
                }
            }
            return new Booking(s.getId(), s.getShipmentNumber(), s.getBookingDate(), s.getCreatedAt(), s.getCreatedBy(),
                    s.getStatus().name(), s.getStatus() == ShipmentStatus.CANCELLED, mode.getName(), kind,
                    amount, s.getChargeableWeight() == null ? BigDecimal.ZERO : s.getChargeableWeight(),
                    method, paid, taken);
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Delivery> deliveries(UUID companyId, UUID branchId, Instant from, Instant to) {
        List<DeliveryAssignment> runs = deliveryAssignmentRepository
                .findForBranchAssignedOrDeliveredBetween(companyId, branchId, from, to);
        if (runs.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = runs.stream().map(DeliveryAssignment::getShipmentId).distinct().toList();
        Map<UUID, Shipment> shipments = shipmentRepository.findAllByCompanyIdAndIdIn(companyId, ids).stream()
                .collect(Collectors.toMap(Shipment::getId, Function.identity()));
        Map<UUID, BigDecimal> net = netAmounts(ids);
        Map<UUID, PaymentMode> modes = new HashMap<>();

        return runs.stream()
                .filter(d -> d.getDeliveryUserId() != null && shipments.containsKey(d.getShipmentId()))
                .map(d -> {
                    Shipment s = shipments.get(d.getShipmentId());
                    PaymentMode mode = modes.computeIfAbsent(s.getPaymentModeId(), paymentModeService::getById);
                    PaymentKind kind = kindOf(mode);
                    BigDecimal collect = BigDecimal.ZERO;
                    if (kind == PaymentKind.COD && s.getCodAmount() != null && s.getCodAmount().signum() > 0) {
                        collect = s.getCodAmount();
                    } else if (kind == PaymentKind.COD || kind == PaymentKind.TO_PAY) {
                        collect = net.getOrDefault(s.getId(), BigDecimal.ZERO);
                    }
                    Instant deliveredAt = d.getStatus() == DeliveryAssignmentStatus.DELIVERED ? d.getDeliveredAt() : null;
                    return new Delivery(s.getId(), s.getShipmentNumber(), d.getDeliveryUserId(), d.getDrsNumber(),
                            d.getAssignedAt(), deliveredAt, s.getFailedAttempts(), mode.getName(), kind, collect);
                }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, ShipmentOwners> ownersOf(UUID companyId, Collection<String> shipmentNumbers) {
        if (shipmentNumbers == null || shipmentNumbers.isEmpty()) {
            return Map.of();
        }
        List<Shipment> shipments = shipmentRepository
                .findAllByCompanyIdAndTrackingNumberInOrShipmentNumberIn(companyId, shipmentNumbers).stream()
                .filter(s -> shipmentNumbers.contains(s.getShipmentNumber()))
                .toList();
        Map<UUID, DeliveryAssignment> runs = deliveryAssignmentRepository
                .findByShipmentIdIn(shipments.stream().map(Shipment::getId).toList()).stream()
                .collect(Collectors.toMap(DeliveryAssignment::getShipmentId, Function.identity(), (a, b) -> a));
        Map<String, ShipmentOwners> owners = new HashMap<>();
        for (Shipment s : shipments) {
            DeliveryAssignment run = runs.get(s.getId());
            owners.put(s.getShipmentNumber(), new ShipmentOwners(s.getShipmentNumber(), s.getCreatedBy(),
                    s.getBookingBranchId(), run == null ? null : run.getDeliveryUserId(),
                    run == null ? s.getDeliveryBranchId() : run.getDeliveryBranchId()));
        }
        return owners;
    }

    static PaymentKind kindOf(PaymentMode mode) {
        if (mode.isRequiresCreditAccount()) return PaymentKind.CREDIT;
        if (mode.isCashOnDelivery()) return PaymentKind.COD;
        if (mode.isCollectAtDelivery()) return PaymentKind.TO_PAY;
        if (mode.isCollectAtBooking()) return PaymentKind.PAID;
        return PaymentKind.CREDIT;
    }

    private Map<UUID, BigDecimal> netAmounts(Collection<UUID> shipmentIds) {
        return chargeRepository.findByShipmentIdIn(shipmentIds).stream()
                .collect(Collectors.toMap(ShipmentCharge::getShipmentId,
                        c -> c.getNetAmount() == null ? BigDecimal.ZERO : c.getNetAmount(), (a, b) -> a));
    }
}
