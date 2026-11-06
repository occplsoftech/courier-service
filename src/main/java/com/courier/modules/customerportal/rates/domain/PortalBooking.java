package com.courier.modules.customerportal.rates.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** One booking attempt from the customer portal (V128), unique per (company, customer, idempotency key). */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "portal_bookings")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
// Only changed columns are written, so a booking saved for another reason (label link, tracking number) never writes back
// a stale delivery status that the tracking sync updated in the meantime.
@org.hibernate.annotations.DynamicUpdate
public class PortalBooking extends CompanyOwnedEntity {

    public enum Status {
        PENDING, SUBMITTING, BOOKED, FAILED, OUTCOME_UNKNOWN,
        /** The customer cancelled it before pickup (OCCPL Network). */
        CANCELLED;

        /** Statuses that block another booking of the same quote. */
        public static final Set<Status> ACTIVE = EnumSet.of(PENDING, SUBMITTING, BOOKED, OUTCOME_UNKNOWN);
    }

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID customerId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "quote_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID quoteId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "quote_option_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID quoteOptionId;

    @Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "provider_code", nullable = false, length = 40, updatable = false)
    private String providerCode;

    @Column(name = "status", nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "shipment_id", columnDefinition = "BINARY(16)")
    private UUID shipmentId;

    @Column(name = "provider_order_ref", length = 120) private String providerOrderRef;
    @Column(name = "awb_number", length = 60) private String awbNumber;
    @Column(name = "label_url", length = 1024) private String labelUrl;
    @Column(name = "failure_code", length = 60) private String failureCode;
    @Column(name = "failure_message", length = 500) private String failureMessage;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "booked_at") private Instant bookedAt;

    /** The courier's latest delivery status (V135), kept by {@code TrackingSyncService}. */
    @Column(name = "delivery_status", length = 30)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private DeliveryStage deliveryStatus;
    @Column(name = "delivery_status_text", length = 150) private String deliveryStatusText;
    @Column(name = "delivery_location", length = 150) private String deliveryLocation;
    @Column(name = "delivery_status_at") private Instant deliveryStatusAt;
    @Column(name = "tracking_synced_at") private Instant trackingSyncedAt;
    /** Due for the next sync; null once the shipment is finished (or was never booked). */
    @Column(name = "next_tracking_sync_at") private Instant nextTrackingSyncAt;
}
