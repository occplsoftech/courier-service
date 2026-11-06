package com.courier.modules.communication.application;

import com.courier.modules.communication.application.provider.EmailProvider;
import com.courier.modules.communication.application.provider.ProviderSendException;
import com.courier.modules.communication.application.provider.ProviderSendResult;
import com.courier.modules.communication.application.provider.SmsProvider;
import com.courier.modules.communication.application.provider.WhatsAppProvider;
import com.courier.modules.communication.domain.CommunicationChannel;
import com.courier.modules.communication.domain.CommunicationEventType;
import com.courier.modules.communication.domain.CommunicationLog;
import com.courier.modules.communication.domain.CommunicationLogRepository;
import com.courier.modules.communication.domain.CommunicationSetting;
import com.courier.modules.communication.domain.CommunicationStatus;
import com.courier.modules.communication.domain.CommunicationTemplate;
import com.courier.modules.communication.domain.CommunicationTemplateRepository;
import com.courier.modules.communication.domain.ShipmentDirectoryPort;
import com.courier.modules.communication.domain.ShipmentSnapshot;
import com.courier.modules.communication.domain.TemplateStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the brief's own "Success / Failure / Retry / Disabled Channel" test list for the
 *  send half of the flow. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommunicationSendServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID SHIPMENT_ID = UUID.randomUUID();
    private static final UUID TEMPLATE_ID = UUID.randomUUID();
    private static final UUID LOG_ID = UUID.randomUUID();

    @Mock private CommunicationLogRepository logRepository;
    @Mock private CommunicationTemplateRepository templateRepository;
    @Mock private CommunicationSettingService settingService;
    @Mock private ShipmentDirectoryPort shipmentDirectoryPort;
    @Mock private com.courier.modules.communication.domain.PortalBookingDirectoryPort portalBookings;
    @Mock private WhatsAppProvider whatsAppProvider;
    @Mock private SmsProvider smsProvider;
    @Mock private EmailProvider emailProvider;

    private CommunicationSendServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CommunicationSendServiceImpl(logRepository, templateRepository, settingService,
                shipmentDirectoryPort, portalBookings, new TemplateRenderer("http://localhost:4200/track/{trackingNumber}"),
                retryProperties(), new ObjectMapper(), whatsAppProvider, smsProvider, emailProvider);

        when(shipmentDirectoryPort.findSnapshot(COMPANY, SHIPMENT_ID)).thenReturn(Optional.of(snapshot()));
        when(settingService.findEnabled(COMPANY, CommunicationChannel.WHATSAPP))
                .thenReturn(Optional.of(CommunicationSetting.builder()
                        .channel(CommunicationChannel.WHATSAPP).enabled(true).build()));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(activeTemplate()));
        when(logRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        // nothing thread-local held by this service — kept for symmetry with other tests
    }

    private CommunicationRetryProperties retryProperties() {
        CommunicationRetryProperties p = new CommunicationRetryProperties();
        p.setMaxAttempts(3);
        p.setBackoffMinutes(15);
        return p;
    }

    private ShipmentSnapshot snapshot() {
        ShipmentSnapshot.Party sender = new ShipmentSnapshot.Party(
                "Sender", "9876500000", null, "sender@test.com", true, true, true);
        ShipmentSnapshot.Party receiver = new ShipmentSnapshot.Party(
                "Receiver", "9876500001", null, "receiver@test.com", true, true, true);
        return new ShipmentSnapshot(SHIPMENT_ID, "SHP-001", "AWB123", "Acme", sender, receiver,
                "Pune", "Mumbai", new BigDecimal("100.00"), LocalDate.now(), null);
    }

    private CommunicationTemplate activeTemplate() {
        CommunicationTemplate t = CommunicationTemplate.builder()
                .eventType(CommunicationEventType.SHIPMENT_BOOKED).channel(CommunicationChannel.WHATSAPP)
                .templateName("Approved Template").content("Hi {{customerName}}")
                .status(TemplateStatus.ACTIVE).build();
        t.setCompanyId(COMPANY);
        return t;
    }

    private CommunicationLog pendingLog() {
        CommunicationLog row = CommunicationLog.builder()
                .shipmentId(SHIPMENT_ID).eventType(CommunicationEventType.SHIPMENT_BOOKED)
                .channel(CommunicationChannel.WHATSAPP).recipient("9876500000").templateId(TEMPLATE_ID)
                .status(CommunicationStatus.PENDING).attemptCount(0).build();
        row.setCompanyId(COMPANY);
        return row;
    }

    @Test
    void success_marksSentWithProviderMessageId() {
        when(logRepository.findById(LOG_ID)).thenReturn(Optional.of(pendingLog()));
        when(whatsAppProvider.send(any(), any())).thenReturn(new ProviderSendResult("wamid.123"));

        service.processOne(LOG_ID, COMPANY);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository).save(captor.capture());
        CommunicationLog saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(CommunicationStatus.SENT);
        assertThat(saved.getProviderMessageId()).isEqualTo("wamid.123");
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getSentAt()).isNotNull();
    }

    @Test
    void failure_marksFailedAndSchedulesRetry() {
        when(logRepository.findById(LOG_ID)).thenReturn(Optional.of(pendingLog()));
        when(whatsAppProvider.send(any(), any())).thenThrow(new ProviderSendException("Meta API down"));

        service.processOne(LOG_ID, COMPANY);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository).save(captor.capture());
        CommunicationLog saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(CommunicationStatus.FAILED);
        assertThat(saved.getErrorMessage()).contains("Meta API down");
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getNextRetryAt()).isNotNull();
    }

    @Test
    void retry_stopsAfterConfiguredMaxAttempts() {
        CommunicationLog exhausted = pendingLog();
        exhausted.setStatus(CommunicationStatus.FAILED);
        exhausted.setAttemptCount(2); // one more failure reaches maxAttempts=3
        when(logRepository.findById(LOG_ID)).thenReturn(Optional.of(exhausted));
        when(whatsAppProvider.send(any(), any())).thenThrow(new ProviderSendException("still down"));

        service.processOne(LOG_ID, COMPANY);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository).save(captor.capture());
        assertThat(captor.getValue().getAttemptCount()).isEqualTo(3);
        // The dispatch job's own query (attemptCount < maxAttempts) is what actually stops
        // further sweeps from picking this row up again — asserted in the repository test.
    }

    @Test
    void disabledChannelAtSendTime_cancelsInsteadOfSending() {
        when(logRepository.findById(LOG_ID)).thenReturn(Optional.of(pendingLog()));
        when(settingService.findEnabled(COMPANY, CommunicationChannel.WHATSAPP)).thenReturn(Optional.empty());

        service.processOne(LOG_ID, COMPANY);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CommunicationStatus.CANCELLED);
        verify(whatsAppProvider, never()).send(any(), any());
    }

    @Test
    void templateDeactivatedBeforeSend_cancelsInsteadOfSending() {
        when(logRepository.findById(LOG_ID)).thenReturn(Optional.of(pendingLog()));
        CommunicationTemplate inactive = activeTemplate();
        inactive.setStatus(TemplateStatus.INACTIVE);
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(inactive));

        service.processOne(LOG_ID, COMPANY);

        ArgumentCaptor<CommunicationLog> captor = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(logRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CommunicationStatus.CANCELLED);
        verify(whatsAppProvider, never()).send(any(), any());
    }

    @Test
    void alreadyTerminalRow_isNeverReprocessed() {
        CommunicationLog sent = pendingLog();
        sent.setStatus(CommunicationStatus.SENT);
        when(logRepository.findById(LOG_ID)).thenReturn(Optional.of(sent));

        service.processOne(LOG_ID, COMPANY);

        verify(logRepository, never()).save(any());
        verify(whatsAppProvider, never()).send(any(), any());
    }
}
