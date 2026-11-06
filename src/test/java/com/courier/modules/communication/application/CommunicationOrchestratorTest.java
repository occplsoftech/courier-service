package com.courier.modules.communication.application;

import com.courier.modules.communication.domain.CommunicationChannel;
import com.courier.modules.communication.domain.CommunicationEventType;
import com.courier.modules.communication.domain.CommunicationLog;
import com.courier.modules.communication.domain.CommunicationLogRepository;
import com.courier.modules.communication.domain.CommunicationSetting;
import com.courier.modules.communication.domain.CommunicationStatus;
import com.courier.modules.communication.domain.CommunicationTemplate;
import com.courier.modules.communication.domain.ShipmentDirectoryPort;
import com.courier.modules.communication.domain.ShipmentSnapshot;
import com.courier.modules.communication.domain.TemplateStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the brief's own "Success / Disabled Channel / Customer Preference / Duplicate
 *  Event" test list for the queueing half of the flow. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommunicationOrchestratorTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID SHIPMENT_ID = UUID.randomUUID();

    @Mock private ShipmentDirectoryPort shipmentDirectoryPort;

    @Mock private com.courier.modules.communication.domain.PortalBookingDirectoryPort portalBookings;
    @Mock private CommunicationSettingService settingService;
    @Mock private CommunicationTemplateService templateService;
    @Mock private CommunicationLogRepository logRepository;

    private CommunicationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new CommunicationOrchestrator(shipmentDirectoryPort, portalBookings, settingService, templateService,
                logRepository);
        when(settingService.hasAnyEnabled(any())).thenReturn(true);
        when(shipmentDirectoryPort.findSnapshot(COMPANY, SHIPMENT_ID)).thenReturn(Optional.of(snapshot(true, true, true)));
        when(logRepository.findByShipmentIdAndEventTypeAndChannel(any(), any(), any())).thenReturn(Optional.empty());
        when(logRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        for (CommunicationChannel channel : CommunicationChannel.values()) {
            when(settingService.findEnabled(COMPANY, channel))
                    .thenReturn(Optional.of(CommunicationSetting.builder().channel(channel).enabled(true).build()));
            when(templateService.findActive(COMPANY, CommunicationEventType.SHIPMENT_BOOKED, channel))
                    .thenReturn(Optional.of(activeTemplate(channel)));
        }
    }

    private ShipmentSnapshot snapshot(boolean waEnabled, boolean smsEnabled, boolean emailEnabled) {
        ShipmentSnapshot.Party sender = new ShipmentSnapshot.Party(
                "Sender Name", "9876500000", UUID.randomUUID(), "sender@test.com",
                waEnabled, smsEnabled, emailEnabled);
        ShipmentSnapshot.Party receiver = new ShipmentSnapshot.Party(
                "Receiver Name", "9876500001", UUID.randomUUID(), "receiver@test.com", true, true, true);
        return new ShipmentSnapshot(SHIPMENT_ID, "SHP-001", "AWB123", "Acme", sender, receiver,
                "Pune", "Mumbai", new BigDecimal("100.00"), LocalDate.now(), null);
    }

    private CommunicationTemplate activeTemplate(CommunicationChannel channel) {
        CommunicationTemplate t = CommunicationTemplate.builder()
                .eventType(CommunicationEventType.SHIPMENT_BOOKED).channel(channel)
                .templateName("t").content("Hi {{customerName}}").status(TemplateStatus.ACTIVE).build();
        t.setCompanyId(COMPANY);
        return t;
    }

    @Test
    void success_queuesOnePendingRowPerChannel() {
        orchestrator.handle(COMPANY, SHIPMENT_ID, CommunicationEventType.SHIPMENT_BOOKED);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(CommunicationStatus.PENDING);
            assertThat(row.getShipmentId()).isEqualTo(SHIPMENT_ID);
            assertThat(row.getEventType()).isEqualTo(CommunicationEventType.SHIPMENT_BOOKED);
        });
    }

    @Test
    void companyIsolation_queuedRowIsStampedWithTheCallersCompanyIdOnly() {
        UUID otherCompany = UUID.randomUUID();
        when(shipmentDirectoryPort.findSnapshot(otherCompany, SHIPMENT_ID)).thenReturn(Optional.empty());

        orchestrator.handle(COMPANY, SHIPMENT_ID, CommunicationEventType.SHIPMENT_BOOKED);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row -> assertThat(row.getCompanyId()).isEqualTo(COMPANY));
        // A call scoped to a different company finds nothing — no cross-tenant bleed of
        // this shipment's snapshot into another company's queueing pass.
        orchestrator.handle(otherCompany, SHIPMENT_ID, CommunicationEventType.SHIPMENT_BOOKED);
        verify(logRepository, times(3)).save(any()); // still 3 — the second call queued nothing
    }

    @Test
    void disabledChannel_cancelsWithReason() {
        when(settingService.findEnabled(COMPANY, CommunicationChannel.SMS)).thenReturn(Optional.empty());

        orchestrator.handle(COMPANY, SHIPMENT_ID, CommunicationEventType.SHIPMENT_BOOKED);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository, times(3)).save(captor.capture());
        CommunicationLog smsRow = captor.getAllValues().stream()
                .filter(r -> r.getChannel() == CommunicationChannel.SMS).findFirst().orElseThrow();
        assertThat(smsRow.getStatus()).isEqualTo(CommunicationStatus.CANCELLED);
        assertThat(smsRow.getErrorMessage()).contains("disabled");
    }

    @Test
    void customerOptedOut_cancelsWithReason() {
        when(shipmentDirectoryPort.findSnapshot(COMPANY, SHIPMENT_ID))
                .thenReturn(Optional.of(snapshot(false, true, true)));

        orchestrator.handle(COMPANY, SHIPMENT_ID, CommunicationEventType.SHIPMENT_BOOKED);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository, times(3)).save(captor.capture());
        CommunicationLog waRow = captor.getAllValues().stream()
                .filter(r -> r.getChannel() == CommunicationChannel.WHATSAPP).findFirst().orElseThrow();
        assertThat(waRow.getStatus()).isEqualTo(CommunicationStatus.CANCELLED);
        assertThat(waRow.getErrorMessage()).contains("opted out");
    }

    @Test
    void noActiveTemplate_cancelsWithReason() {
        when(templateService.findActive(COMPANY, CommunicationEventType.SHIPMENT_BOOKED, CommunicationChannel.EMAIL))
                .thenReturn(Optional.empty());

        orchestrator.handle(COMPANY, SHIPMENT_ID, CommunicationEventType.SHIPMENT_BOOKED);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository, times(3)).save(captor.capture());
        CommunicationLog emailRow = captor.getAllValues().stream()
                .filter(r -> r.getChannel() == CommunicationChannel.EMAIL).findFirst().orElseThrow();
        assertThat(emailRow.getStatus()).isEqualTo(CommunicationStatus.CANCELLED);
        assertThat(emailRow.getErrorMessage()).contains("No active template");
        assertThat(emailRow.getTemplateId()).isNull();
    }

    @Test
    void duplicateEvent_doesNotQueueASecondRow() {
        when(logRepository.findByShipmentIdAndEventTypeAndChannel(SHIPMENT_ID,
                CommunicationEventType.SHIPMENT_BOOKED, CommunicationChannel.WHATSAPP))
                .thenReturn(Optional.of(CommunicationLog.builder().build()));

        orchestrator.handle(COMPANY, SHIPMENT_ID, CommunicationEventType.SHIPMENT_BOOKED);

        // Only SMS + EMAIL saved — WhatsApp already had a row and was skipped entirely.
        verify(logRepository, times(2)).save(any());
    }

    @Test
    void shipmentNotFound_queuesNothing() {
        when(shipmentDirectoryPort.findSnapshot(COMPANY, SHIPMENT_ID)).thenReturn(Optional.empty());

        orchestrator.handle(COMPANY, SHIPMENT_ID, CommunicationEventType.SHIPMENT_BOOKED);

        verify(logRepository, never()).save(any());
    }

    @Test
    void noChannelEverEnabled_skipsEntirelyWithoutTouchingShipmentOrLogRepository() {
        when(settingService.hasAnyEnabled(COMPANY)).thenReturn(false);

        orchestrator.handle(COMPANY, SHIPMENT_ID, CommunicationEventType.SHIPMENT_BOOKED);

        verify(shipmentDirectoryPort, never()).findSnapshot(any(), any());
        verify(logRepository, never()).save(any());
    }

    @Test
    void outForDelivery_notifiesReceiverNotSender() {
        for (CommunicationChannel channel : CommunicationChannel.values()) {
            when(templateService.findActive(COMPANY, CommunicationEventType.OUT_FOR_DELIVERY, channel))
                    .thenReturn(Optional.of(CommunicationTemplate.builder()
                            .eventType(CommunicationEventType.OUT_FOR_DELIVERY).channel(channel)
                            .templateName("t").content("hi").status(TemplateStatus.ACTIVE).build()));
        }

        orchestrator.handle(COMPANY, SHIPMENT_ID, CommunicationEventType.OUT_FOR_DELIVERY);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository, times(3)).save(captor.capture());
        CommunicationLog waRow = captor.getAllValues().stream()
                .filter(r -> r.getChannel() == CommunicationChannel.WHATSAPP).findFirst().orElseThrow();
        assertThat(waRow.getRecipient()).isEqualTo("9876500001"); // receiver's contact, not sender's
    }

    @Test
    void listsExactlyTheSpecEventsPlusTheDailySummary() {
        assertThat(List.of(CommunicationEventType.values())).containsExactlyInAnyOrder(
                CommunicationEventType.SHIPMENT_BOOKED, CommunicationEventType.SHIPMENT_PICKED_UP, CommunicationEventType.SHIPMENT_DISPATCHED,
                CommunicationEventType.SHIPMENT_RECEIVED, CommunicationEventType.OUT_FOR_DELIVERY,
                CommunicationEventType.SHIPMENT_DELIVERED, CommunicationEventType.SHIPMENT_CANCELLED,
                CommunicationEventType.RTO_INITIATED, CommunicationEventType.RTO_DELIVERED,
                CommunicationEventType.DAILY_BUSINESS_SUMMARY);
    }
}
