package com.courier.modules.communication.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommunicationEventTypeTest {

    @Test
    void senderFacingEvents() {
        assertThat(CommunicationEventType.SHIPMENT_BOOKED.notifiesSender()).isTrue();
        assertThat(CommunicationEventType.SHIPMENT_PICKED_UP.notifiesSender()).isTrue();
        assertThat(CommunicationEventType.DELIVERY_FAILED.notifiesSender()).isTrue();
        assertThat(CommunicationEventType.SHIPMENT_DISPATCHED.notifiesSender()).isTrue();
        assertThat(CommunicationEventType.SHIPMENT_CANCELLED.notifiesSender()).isTrue();
    }

    @Test
    void receiverFacingEvents() {
        assertThat(CommunicationEventType.SHIPMENT_RECEIVED.notifiesSender()).isFalse();
        assertThat(CommunicationEventType.OUT_FOR_DELIVERY.notifiesSender()).isFalse();
        assertThat(CommunicationEventType.SHIPMENT_DELIVERED.notifiesSender()).isFalse();
    }

    @Test
    void defaultEnabledIsExactlyTheFourBriefedEvents() {
        assertThat(CommunicationEventType.DEFAULT_ENABLED).containsExactlyInAnyOrder(
                CommunicationEventType.SHIPMENT_BOOKED, CommunicationEventType.SHIPMENT_PICKED_UP, CommunicationEventType.SHIPMENT_DISPATCHED,
                CommunicationEventType.OUT_FOR_DELIVERY, CommunicationEventType.SHIPMENT_DELIVERED,
                CommunicationEventType.DELIVERY_FAILED);
    }
}
