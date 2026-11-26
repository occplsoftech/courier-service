package com.courier.modules.shipment.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
import java.util.UUID;

/**
 * The delivery desk's current assignment for one shipment, and — once closed — its proof
 * of delivery. One row per shipment (re-assigning before delivery updates it in place);
 * the append-only record of what happened is {@code shipment_status_history}, the same
 * "current state vs. ledger" split {@code finance.domain.Wallet}/{@code WalletTransaction}
 * already draws.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "delivery_assignment",
        uniqueConstraints = @UniqueConstraint(name = "uk_delivery_assignment_company_shipment",
                columnNames = {"company_id", "shipment_id"}),
        indexes = {
                @Index(name = "idx_delivery_assignment_branch",
                        columnList = "company_id, delivery_branch_id, status"),
                @Index(name = "idx_delivery_assignment_user",
                        columnList = "company_id, delivery_user_id, status")
        })
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class DeliveryAssignment extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "shipment_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID shipmentId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "delivery_branch_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID deliveryBranchId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "delivery_user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID deliveryUserId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    /** {@code "DRS" + 6-digit serial}, e.g. {@code DRS000001} — one number per bulk
     *  {@code assignOutForDelivery} call, stamped on every row it touches. Null on rows
     *  created before this column existed. */
    @Column(name = "drs_number", length = 20)
    private String drsNumber;

    /** Optional — the delivery boy's own vehicle for this run, not validated against
     *  {@code manifest.domain.Vehicle} here (the manifest module is the only one allowed
     *  to depend the other way; see {@code ManifestServiceImpl}'s class doc). The
     *  controller validates it active before this is ever set. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "vehicle_id", columnDefinition = "BINARY(16)")
    private UUID vehicleId;

    /** Optional trip expenses, stamped on every row one bulk {@code assignOutForDelivery}
     *  ("Generate DRS") call touches — same convention {@link #drsNumber} already uses. */
    @Column(name = "fuel_cost", precision = 12, scale = 2)
    private BigDecimal fuelCost;

    @Column(name = "delivery_charge", precision = 12, scale = 2)
    private BigDecimal deliveryCharge;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DeliveryAssignmentStatus status = DeliveryAssignmentStatus.ASSIGNED;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "receiver_name", length = 150)
    private String receiverName;

    @Column(name = "delivery_remarks", length = 500)
    private String deliveryRemarks;

    /** The receiver's relation to the consignee (V178), e.g. Self, Security, Neighbour. */
    @Column(name = "receiver_relation", length = 50)
    private String receiverRelation;

    /** How the delivery was proved (V178). */
    @Enumerated(EnumType.STRING)
    @Column(name = "proof_type", length = 30)
    private DeliveryProof proofType;

    @Column(name = "otp", length = 10)
    private String otp;

    public void reassign(UUID deliveryUserId, UUID deliveryBranchId) {
        if (status == DeliveryAssignmentStatus.DELIVERED) {
            throw new BusinessRuleException("This shipment has already been delivered.");
        }
        this.deliveryUserId = deliveryUserId;
        this.deliveryBranchId = deliveryBranchId;
        this.assignedAt = Instant.now();
    }

    /** Signature/photo capture urls are no longer stored here — see {@code ShipmentAsset}
     *  (V33); {@code ShipmentServiceImpl.deliver} records them as {@code POD} assets in the
     *  same transaction as this call. */
    public void markDelivered(String receiverName, String remarks, String otp) {
        markDelivered(receiverName, remarks, otp, null, null, null);
    }

    /**
     * As {@link #markDelivered(String, String, String)}, with the receiver's relation, the proof and the moment of
     * delivery ({@code null} = now). The moment cannot be in the future or before the shipment went out.
     */
    public void markDelivered(String receiverName, String remarks, String otp, String relation, DeliveryProof proof,
                              Instant at) {
        Instant when = at == null ? Instant.now() : at;
        if (when.isAfter(Instant.now().plusSeconds(60))) {
            throw new BusinessRuleException("The delivered date cannot be in the future.");
        }
        if (assignedAt != null && when.isBefore(assignedAt.truncatedTo(java.time.temporal.ChronoUnit.DAYS))) {
            throw new BusinessRuleException("The delivered date cannot be before the shipment went out for delivery.");
        }
        if (status == DeliveryAssignmentStatus.DELIVERED) {
            throw new BusinessRuleException("This shipment has already been delivered.");
        }
        if (receiverName == null || receiverName.isBlank()) {
            throw new BusinessRuleException("A receiver name is required to close a delivery.");
        }
        this.status = DeliveryAssignmentStatus.DELIVERED;
        this.deliveredAt = when;
        this.receiverRelation = relation == null || relation.isBlank() ? null : relation.trim();
        this.proofType = proof;
        this.receiverName = receiverName.trim();
        this.deliveryRemarks = remarks == null || remarks.isBlank() ? null : remarks.trim();
        this.otp = otp == null || otp.isBlank() ? null : otp.trim();
    }
}
