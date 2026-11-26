package com.courier.modules.shipment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryAssignmentTest {

    private static DeliveryAssignment outFor(int daysAgo) {
        return DeliveryAssignment.builder().shipmentId(UUID.randomUUID()).deliveryBranchId(UUID.randomUUID())
                .deliveryUserId(UUID.randomUUID()).assignedAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS)).build();
    }

    @Test
    @DisplayName("Delivery Updation records the relation, the proof and a past delivered date")
    void recordsUpdationDetails() {
        DeliveryAssignment a = outFor(3);
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        a.markDelivered(" R. Sharma ", "Left at reception", null, " Security ", DeliveryProof.STAMP_AND_SIGN, yesterday);

        assertThat(a.getStatus()).isEqualTo(DeliveryAssignmentStatus.DELIVERED);
        assertThat(a.getReceiverName()).isEqualTo("R. Sharma");
        assertThat(a.getReceiverRelation()).isEqualTo("Security");
        assertThat(a.getProofType()).isEqualTo(DeliveryProof.STAMP_AND_SIGN);
        assertThat(a.getDeliveredAt()).isEqualTo(yesterday);
    }

    @Test
    @DisplayName("the delivered date cannot be in the future or before the shipment went out")
    void dateLimits() {
        assertThatThrownBy(() -> outFor(1).markDelivered("A", null, null, null, DeliveryProof.DROP,
                Instant.now().plus(1, ChronoUnit.DAYS))).hasMessageContaining("future");
        assertThatThrownBy(() -> outFor(1).markDelivered("A", null, null, null, DeliveryProof.DROP,
                Instant.now().minus(5, ChronoUnit.DAYS))).hasMessageContaining("before the shipment went out");
    }

    @Test
    @DisplayName("the old three-argument form still delivers now, with no relation or proof")
    void oldForm() {
        DeliveryAssignment a = outFor(0);
        a.markDelivered("A", null, null);
        assertThat(a.getDeliveredAt()).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(5, ChronoUnit.SECONDS));
        assertThat(a.getProofType()).isNull();
    }
}
