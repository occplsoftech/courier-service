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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The brief's own flow, as code: Business Event -&gt; find enabled channels -&gt; load
 * template -&gt; build message -&gt; send -&gt; store delivery result. This class owns only
 * the first half — "find enabled channels" through "queue one log row per channel" — never
 * a network call itself. The actual send happens later, off {@code CommunicationLog}, in
 * {@code CommunicationDispatchJob}/{@code CommunicationSendService}: queueing here stays a
 * fast, transactional DB insert, safe to run inside an {@code AFTER_COMMIT} listener without
 * risking a slow provider call on that thread.
 *
 * <p>Called only from {@code ShipmentCommunicationListener} today, but takes plain
 * scalars (companyId/shipmentId/eventType) rather than a {@code ShipmentEvent}, so a future
 * event source (a Kafka consumer, say) can call it the same way with no change here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommunicationOrchestrator {

    private final ShipmentDirectoryPort shipmentDirectoryPort;
    private final com.courier.modules.communication.domain.PortalBookingDirectoryPort portalBookings;
    private final CommunicationSettingService settingService;
    private final CommunicationTemplateService templateService;
    private final CommunicationLogRepository logRepository;

    public void handle(UUID companyId, UUID shipmentId, CommunicationEventType eventType) {
        // Cheapest possible check first: a company that has never enabled any channel
        // (the default for every company today — nothing opts in automatically) gets
        // zero queries beyond this one, instead of a per-channel lookup+insert pass that
        // only ever produces CANCELLED rows nobody will read. Found necessary running a
        // 50-VU local load test 2026-09-02: every booking paying for this loop regardless
        // of Communication Center ever being configured pushed the request's REQUIRES_NEW
        // transaction long enough to exhaust HikariCP under concurrent booking traffic.
        if (!settingService.hasAnyEnabled(companyId)) {
            return;
        }
        var snapshot = shipmentDirectoryPort.findSnapshot(companyId, shipmentId)
                .or(() -> portalBookings.findSnapshot(companyId, shipmentId));
        if (snapshot.isEmpty()) {
            log.warn("Communication event {} for shipment {} in company {} — shipment not found, skipping",
                    eventType, shipmentId, companyId);
            return;
        }
        ShipmentSnapshot shipment = snapshot.get();
        ShipmentSnapshot.Party recipient = eventType.notifiesSender() ? shipment.sender() : shipment.receiver();

        for (CommunicationChannel channel : CommunicationChannel.values()) {
            queueOne(companyId, shipment, recipient, eventType, channel);
        }
    }

    private void queueOne(UUID companyId, ShipmentSnapshot shipment, ShipmentSnapshot.Party recipient,
                           CommunicationEventType eventType, CommunicationChannel channel) {
        // Duplicate protection: never touch an already-attempted (shipment, event, channel)
        // row from this natural path — a retry is the only way to re-attempt one, via
        // CommunicationLogService.retry, which acts on the existing row directly.
        if (logRepository.findByShipmentIdAndEventTypeAndChannel(shipment.shipmentId(), eventType, channel)
                .isPresent()) {
            return;
        }

        String recipientAddress = recipientAddress(recipient, channel);
        String cancelReason = cancelReason(companyId, recipient, eventType, channel, recipientAddress);

        CommunicationTemplate template = cancelReason == null
                ? templateService.findActive(companyId, eventType, channel).orElse(null) : null;
        if (cancelReason == null && template == null) {
            cancelReason = "No active template for " + eventType + " on " + channel + ".";
        }

        CommunicationLog logRow = CommunicationLog.builder()
                .shipmentId(shipment.shipmentId())
                .customerId(recipient == null ? null : recipient.customerId())
                .eventType(eventType)
                .channel(channel)
                .recipient(recipientAddress == null ? "" : recipientAddress)
                .templateId(template == null ? null : template.getId())
                .status(cancelReason == null ? CommunicationStatus.PENDING : CommunicationStatus.CANCELLED)
                .errorMessage(cancelReason)
                .build();
        logRow.setCompanyId(companyId);
        logRepository.save(logRow);
    }

    /** Null means "go ahead and queue it"; non-null is the reason it was cancelled instead. */
    private String cancelReason(UUID companyId, ShipmentSnapshot.Party recipient, CommunicationEventType eventType,
                                 CommunicationChannel channel, String recipientAddress) {
        if (recipient == null || recipientAddress == null || recipientAddress.isBlank()) {
            return "No " + channel + " address on file for the recipient.";
        }
        CommunicationSetting setting = settingService.findEnabled(companyId, channel).orElse(null);
        if (setting == null) {
            return channel + " is disabled for this company.";
        }
        if (!customerOptedIn(recipient, channel)) {
            return "Customer has opted out of " + channel + ".";
        }
        return null;
    }

    private static boolean customerOptedIn(ShipmentSnapshot.Party recipient, CommunicationChannel channel) {
        return switch (channel) {
            case WHATSAPP -> recipient.whatsappEnabled();
            case SMS -> recipient.smsEnabled();
            case EMAIL -> recipient.emailEnabled();
        };
    }

    private static String recipientAddress(ShipmentSnapshot.Party recipient, CommunicationChannel channel) {
        if (recipient == null) {
            return null;
        }
        return channel == CommunicationChannel.EMAIL ? recipient.email() : recipient.contact();
    }
}
