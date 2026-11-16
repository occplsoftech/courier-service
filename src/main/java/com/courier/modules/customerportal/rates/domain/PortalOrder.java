package com.courier.modules.customerportal.rates.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An order that arrived for a business customer (their system's API key now; Shopify / WooCommerce next) and waits to be booked (13.3). */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "portal_orders")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class PortalOrder extends CompanyOwnedEntity {

    public enum Source { API, SHOPIFY, WOOCOMMERCE }

    /** NEW: waiting in the inbox; BOOKED: a booking was made from it; ARCHIVED: put aside by the customer. */
    public enum Status { NEW, BOOKED, ARCHIVED }

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private Source source;

    /** The order number in the customer's own system. */
    @Column(name = "external_ref", nullable = false, length = 80, updatable = false)
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.NEW;

    @Column(name = "delivery_name", nullable = false, length = 100) private String deliveryName;
    @Column(name = "delivery_phone", nullable = false, length = 20) private String deliveryPhone;
    @Column(name = "delivery_address", nullable = false, length = 300) private String deliveryAddress;
    @Column(name = "delivery_pincode", nullable = false, length = 6) private String deliveryPincode;
    @Column(name = "delivery_city", length = 80) private String deliveryCity;
    @Column(name = "delivery_state", length = 80) private String deliveryState;

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 3) private BigDecimal weightKg;
    @Column(name = "length_cm", nullable = false, precision = 10, scale = 2) private BigDecimal lengthCm;
    @Column(name = "breadth_cm", nullable = false, precision = 10, scale = 2) private BigDecimal breadthCm;
    @Column(name = "height_cm", nullable = false, precision = 10, scale = 2) private BigDecimal heightCm;
    @Column(name = "package_count", nullable = false) private int packageCount;
    @Column(name = "contents", nullable = false, length = 200) private String contents;
    @Column(name = "declared_value", nullable = false, precision = 14, scale = 2) private BigDecimal declaredValue;
    /** PREPAID or COD. */
    @Column(name = "payment_mode", nullable = false, length = 10) private String paymentMode;
    @Column(name = "cod_amount", precision = 14, scale = 2) private BigDecimal codAmount;

    /** What the buyer ordered: name, quantity, price per line (shown to the customer; not used for pricing). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", columnDefinition = "JSON")
    private List<Map<String, Object>> items;

    @Column(name = "notes", length = 500) private String notes;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "booking_id", columnDefinition = "BINARY(16)")
    private UUID bookingId;

    @Column(name = "booked_at") private Instant bookedAt;
}
