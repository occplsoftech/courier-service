package com.courier.modules.charge.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/** A Charges Master charge the clerk added to one booking (V192), with its amount at booking time. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "shipment_booking_charges")
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class ShipmentBookingCharge extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "shipment_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "charge_code", nullable = false, updatable = false, length = 20)
    private BookingChargeCode code;

    @Column(name = "charge_name", nullable = false, updatable = false, length = 60)
    private String name;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    public static ShipmentBookingCharge of(UUID shipmentId, BookingChargeCode code, BigDecimal amount) {
        ShipmentBookingCharge c = new ShipmentBookingCharge();
        c.shipmentId = shipmentId;
        c.code = code;
        c.name = code.label();
        c.amount = amount;
        return c;
    }
}
