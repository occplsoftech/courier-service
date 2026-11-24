package com.courier.modules.shipment.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * How the customer pays a Paid booking at the counter. Razorpay: booked PENDING, a single-use QR for the
 * final amount is shown, PAID once Razorpay reports the payment (the branch wallet is not debited). Cash and
 * the company's UPI QR: written PAID at booking, wallet debited as before. One per shipment.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "shipment_payments")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class ShipmentPayment extends CompanyOwnedEntity {

    public enum Method { RAZORPAY, CASH, UPI_QR }

    public enum Status { PENDING, PAID }

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "shipment_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID shipmentId;
    @Column(name = "shipment_number", nullable = false, length = 40) private String shipmentNumber;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "booking_branch_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID bookingBranchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20) private Method method;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20) private Status status;
    @Column(name = "amount", nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @Column(name = "reference", length = 100) private String reference;

    @Column(name = "gateway_qr_id", length = 64) private String gatewayQrId;
    @Column(name = "qr_image_url", length = 500) private String qrImageUrl;
    @Column(name = "qr_close_by") private Instant qrCloseBy;
    @Column(name = "gateway_payment_id", length = 64) private String gatewayPaymentId;
    @Column(name = "paid_amount", precision = 19, scale = 4) private BigDecimal paidAmount;
    @Column(name = "paid_at") private Instant paidAt;

    public boolean isPaid() {
        return status == Status.PAID;
    }
}
