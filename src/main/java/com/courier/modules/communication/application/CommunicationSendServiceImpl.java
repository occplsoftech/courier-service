package com.courier.modules.communication.application;

import com.courier.modules.communication.application.provider.EmailProvider;
import com.courier.modules.communication.application.provider.ProviderSendException;
import com.courier.modules.communication.application.provider.ProviderSendResult;
import com.courier.modules.communication.application.provider.SmsProvider;
import com.courier.modules.communication.application.provider.WhatsAppProvider;
import com.courier.modules.communication.domain.CommunicationLog;
import com.courier.modules.communication.domain.CommunicationLogRepository;
import com.courier.modules.communication.domain.CommunicationSetting;
import com.courier.modules.communication.domain.CommunicationStatus;
import com.courier.modules.communication.domain.CommunicationTemplate;
import com.courier.modules.communication.domain.CommunicationTemplateRepository;
import com.courier.modules.communication.domain.ShipmentDirectoryPort;
import com.courier.modules.communication.domain.ShipmentSnapshot;
import com.courier.shared.company.CompanyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * The second half of the brief's own flow — load template -&gt; build message -&gt; send
 * -&gt; store delivery result — for one already-queued {@link CommunicationLog} row. Called
 * only from {@code CommunicationDispatchJob}, on a scheduler thread with no authenticated
 * caller, so nothing here carries {@code @PreAuthorize}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunicationSendServiceImpl implements CommunicationSendService {

    private final CommunicationLogRepository logRepository;
    private final CommunicationTemplateRepository templateRepository;
    private final CommunicationSettingService settingService;
    private final ShipmentDirectoryPort shipmentDirectoryPort;
    private final com.courier.modules.communication.domain.PortalBookingDirectoryPort portalBookings;
    private final TemplateRenderer templateRenderer;
    private final CommunicationRetryProperties retryProperties;
    private final ObjectMapper objectMapper;
    private final WhatsAppProvider whatsAppProvider;
    private final SmsProvider smsProvider;
    private final EmailProvider emailProvider;

    @Override
    @Transactional
    public void processOne(UUID logId, UUID companyId) {
        CompanyContext.runAs(companyId, () -> processInternal(logId, companyId));
    }

    private void processInternal(UUID logId, UUID companyId) {
        CommunicationLog logRow = logRepository.findById(logId).orElse(null);
        if (logRow == null || logRow.isTerminal()) {
            return;
        }

        var snapshotOpt = shipmentDirectoryPort.findSnapshot(companyId, logRow.getShipmentId())
                .or(() -> portalBookings.findSnapshot(companyId, logRow.getShipmentId()));
        if (snapshotOpt.isEmpty()) {
            cancel(logRow, "Shipment no longer found.");
            return;
        }
        ShipmentSnapshot shipment = snapshotOpt.get();

        CommunicationSetting setting = settingService.findEnabled(companyId, logRow.getChannel()).orElse(null);
        if (setting == null) {
            cancel(logRow, logRow.getChannel() + " is disabled for this company.");
            return;
        }

        CommunicationTemplate template = logRow.getTemplateId() == null ? null
                : templateRepository.findById(logRow.getTemplateId()).orElse(null);
        if (template == null || !template.isActive()) {
            cancel(logRow, "No active template for " + logRow.getEventType() + " on " + logRow.getChannel() + ".");
            return;
        }

        ShipmentSnapshot.Party recipientParty = logRow.getEventType().notifiesSender()
                ? shipment.sender() : shipment.receiver();
        String recipientName = recipientParty == null ? "" : recipientParty.name();

        try {
            ProviderSendResult result = send(logRow, template, shipment, recipientName, setting);
            logRow.setStatus(CommunicationStatus.SENT);
            logRow.setProviderMessageId(result.providerMessageId());
            logRow.setErrorMessage(null);
            logRow.setSentAt(Instant.now());
        } catch (ProviderSendException e) {
            logRow.setStatus(CommunicationStatus.FAILED);
            logRow.setErrorMessage(truncate(e.getMessage()));
            logRow.setNextRetryAt(Instant.now().plus(retryProperties.getBackoffMinutes(), ChronoUnit.MINUTES));
            log.warn("Communication send failed: shipment={} event={} channel={} attempt={} — {}",
                    logRow.getShipmentId(), logRow.getEventType(), logRow.getChannel(),
                    logRow.getAttemptCount() + 1, e.getMessage());
        } catch (RuntimeException e) {
            logRow.setStatus(CommunicationStatus.FAILED);
            logRow.setErrorMessage(truncate("Unexpected error: " + e.getMessage()));
            logRow.setNextRetryAt(Instant.now().plus(retryProperties.getBackoffMinutes(), ChronoUnit.MINUTES));
            log.error("Unexpected error sending communication for shipment {}", logRow.getShipmentId(), e);
        }

        logRow.setAttemptCount(logRow.getAttemptCount() + 1);
        logRow.setLastAttemptAt(Instant.now());
        logRepository.save(logRow);
    }

    private ProviderSendResult send(CommunicationLog logRow, CommunicationTemplate template,
                                     ShipmentSnapshot shipment, String recipientName, CommunicationSetting setting) {
        String content = templateRenderer.render(template.getContent(), shipment, logRow.getEventType(),
                recipientName);
        Map<String, String> config = CommunicationConfigJson.read(objectMapper, setting.getConfigJson());

        return switch (logRow.getChannel()) {
            case WHATSAPP -> {
                if (!template.isApprovedOrUnknownAtMeta()) {
                    throw new ProviderSendException("WhatsApp template \"" + template.getTemplateName()
                            + "\" is " + template.getMetaStatus() + " at Meta, not approved — "
                            + "check its status in Communication > Templates.");
                }
                Map<String, String> vars = templateRenderer.variables(shipment, recipientName);
                WhatsAppProvider.WhatsAppCredentials credentials = new WhatsAppProvider.WhatsAppCredentials(
                        config.get("phoneNumberId"), config.get("businessAccountId"), setting.getSecret());
                yield whatsAppProvider.send(WhatsAppPayloadBuilder.build(logRow.getRecipient(), template,
                        templateRenderer, vars, objectMapper), credentials);
            }
            case SMS -> {
                SmsProvider.SmsCredentials credentials = new SmsProvider.SmsCredentials(
                        setting.getProvider(), config.get("apiUrl"), setting.getSecret(),
                        config.get("senderId"));
                yield smsProvider.send(new SmsProvider.SmsMessage(logRow.getRecipient(), content), credentials);
            }
            case EMAIL -> {
                String subject = template.getSubject() == null ? ""
                        : templateRenderer.render(template.getSubject(), shipment, logRow.getEventType(),
                        recipientName);
                EmailProvider.EmailIdentity identity = new EmailProvider.EmailIdentity(
                        config.get("fromName"), config.get("fromEmail"));
                yield emailProvider.send(new EmailProvider.EmailMessage(logRow.getRecipient(), subject, content),
                        identity);
            }
        };
    }

    private void cancel(CommunicationLog logRow, String reason) {
        logRow.setStatus(CommunicationStatus.CANCELLED);
        logRow.setErrorMessage(reason);
        logRow.setLastAttemptAt(Instant.now());
        logRepository.save(logRow);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 990 ? message.substring(0, 990) : message;
    }
}
