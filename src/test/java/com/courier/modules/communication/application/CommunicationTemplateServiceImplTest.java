package com.courier.modules.communication.application;

import com.courier.modules.communication.application.command.CreateCommunicationTemplateCommand;
import com.courier.modules.communication.application.command.UpdateCommunicationTemplateCommand;
import com.courier.modules.communication.domain.CommunicationChannel;
import com.courier.modules.communication.domain.CommunicationEventType;
import com.courier.modules.communication.domain.CommunicationTemplate;
import com.courier.modules.communication.domain.CommunicationTemplateRepository;
import com.courier.modules.communication.domain.TemplateStatus;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommunicationTemplateServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();

    @Mock private CommunicationTemplateRepository repository;
    @Mock private AuditService auditService;

    private CommunicationTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CommunicationTemplateServiceImpl(repository, new TemplateRenderer(
                "http://localhost:4200/track/{trackingNumber}"), auditService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        CompanyContext.setCompanyId(COMPANY);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    private static List<CommunicationTemplate> existingFor(
            List<com.courier.modules.communication.domain.DefaultCommunicationTemplates.Seed> seeds) {
        return seeds.stream().map(seed -> CommunicationTemplate.builder().eventType(seed.eventType())
                .channel(seed.channel()).templateName(seed.templateName()).content(seed.content()).build()).toList();
    }

    @Test
    void list_seedsEveryDefaultWhenCompanyHasNone() {
        when(repository.findAllByCompanyIdOrderByEventTypeAscChannelAsc(COMPANY)).thenReturn(List.of());

        service.list();

        // 6 default events x 3 channels + the WhatsApp-only daily summary
        verify(repository, times(19)).save(any());
    }

    @Test
    void list_doesNotReseedWhenEveryDefaultAlreadyExists() {
        when(repository.findAllByCompanyIdOrderByEventTypeAscChannelAsc(COMPANY)).thenReturn(
                existingFor(com.courier.modules.communication.domain.DefaultCommunicationTemplates.all()));

        service.list();

        verify(repository, times(0)).save(any());
    }

    @Test
    void list_addsOnlyTheMissingDefaultToACompanySeededBeforeItExisted() {
        var all = com.courier.modules.communication.domain.DefaultCommunicationTemplates.all();
        // seeded before the daily summary and "picked up" existed
        when(repository.findAllByCompanyIdOrderByEventTypeAscChannelAsc(COMPANY)).thenReturn(
                existingFor(all.stream().filter(s -> s.eventType() != CommunicationEventType.DAILY_BUSINESS_SUMMARY
                        && s.eventType() != CommunicationEventType.SHIPMENT_PICKED_UP
                        && s.eventType() != CommunicationEventType.DELIVERY_FAILED).toList()));

        service.list();

        var saved = org.mockito.ArgumentCaptor.forClass(CommunicationTemplate.class);
        verify(repository, times(7)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(CommunicationTemplate::getEventType).containsExactlyInAnyOrder(
                CommunicationEventType.SHIPMENT_PICKED_UP, CommunicationEventType.SHIPMENT_PICKED_UP,
                CommunicationEventType.SHIPMENT_PICKED_UP, CommunicationEventType.DELIVERY_FAILED,
                CommunicationEventType.DELIVERY_FAILED, CommunicationEventType.DELIVERY_FAILED,
                CommunicationEventType.DAILY_BUSINESS_SUMMARY);
    }

    @Test
    void update_changingContentOfASubmittedTemplateClearsItsMetaApproval() {
        CommunicationTemplate t = CommunicationTemplate.builder().eventType(CommunicationEventType.SHIPMENT_BOOKED)
                .channel(CommunicationChannel.WHATSAPP).templateName("shipment_booked").content("Hi {{customerName}}!")
                .status(TemplateStatus.ACTIVE).metaStatus("APPROVED").metaTemplateId("123").build();
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        var updated = service.update(java.util.UUID.randomUUID(), new UpdateCommunicationTemplateCommand(
                "shipment_booked", null, "Hello {{customerName}}!", TemplateStatus.ACTIVE, null));

        assertThat(updated.getMetaStatus()).isNull();
        assertThat(updated.getMetaTemplateId()).isNull();
    }

    @Test
    void update_leavesMetaApprovalAloneWhenOnlyTheSwitchChanges() {
        CommunicationTemplate t = CommunicationTemplate.builder().eventType(CommunicationEventType.SHIPMENT_BOOKED)
                .channel(CommunicationChannel.WHATSAPP).templateName("shipment_booked").content("Hi {{customerName}}!")
                .status(TemplateStatus.ACTIVE).metaStatus("APPROVED").build();
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        var updated = service.update(java.util.UUID.randomUUID(), new UpdateCommunicationTemplateCommand(
                "shipment_booked", null, "Hi {{customerName}}!", TemplateStatus.INACTIVE, null));

        assertThat(updated.getMetaStatus()).isEqualTo("APPROVED");
        assertThat(updated.getStatus()).isEqualTo(TemplateStatus.INACTIVE);
    }

    @Test
    void preview_ofTheDailySummaryFillsItsOwnSampleFigures() {
        var seed = com.courier.modules.communication.domain.DefaultCommunicationTemplates.DAILY_SUMMARY;
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(CommunicationTemplate.builder()
                .eventType(seed.eventType()).channel(seed.channel()).templateName(seed.templateName())
                .content(seed.content()).build()));

        var preview = service.preview(java.util.UUID.randomUUID());

        assertThat(preview.content()).contains("Bookings: 15").contains("13 Oct 2026").doesNotContain("{{");
    }

    @Test
    void create_refusesADuplicateEventChannelCombination() {
        when(repository.findByCompanyIdAndEventTypeAndChannel(COMPANY, CommunicationEventType.SHIPMENT_BOOKED,
                CommunicationChannel.WHATSAPP)).thenReturn(Optional.of(CommunicationTemplate.builder().build()));

        assertThatThrownBy(() -> service.create(new CreateCommunicationTemplateCommand(
                CommunicationEventType.SHIPMENT_BOOKED, CommunicationChannel.WHATSAPP, "t", null, "c")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void create_succeedsForANewCombination() {
        when(repository.findByCompanyIdAndEventTypeAndChannel(any(), any(), any())).thenReturn(Optional.empty());

        CommunicationTemplate created = service.create(new CreateCommunicationTemplateCommand(
                CommunicationEventType.SHIPMENT_CANCELLED, CommunicationChannel.SMS, "Cancelled SMS", null,
                "Sorry, {{shipmentNumber}} was cancelled."));

        assertThat(created.getStatus()).isEqualTo(TemplateStatus.ACTIVE);
        assertThat(created.getCompanyId()).isEqualTo(COMPANY);
    }

    @Test
    void update_refusesAStaleVersion() {
        CommunicationTemplate existing = CommunicationTemplate.builder()
                .eventType(CommunicationEventType.SHIPMENT_BOOKED).channel(CommunicationChannel.SMS)
                .templateName("old").content("old content").status(TemplateStatus.ACTIVE).build();
        existing.setCompanyId(COMPANY);
        UUID id = UUID.randomUUID();
        when(repository.findByIdWithinCompany(id, COMPANY)).thenReturn(Optional.of(existing));
        // version defaults to null on a transient entity — force a mismatch via a non-null expectation
        existing.setVersion(5L);

        assertThatThrownBy(() -> service.update(id, new UpdateCommunicationTemplateCommand(
                "new", null, "new content", TemplateStatus.ACTIVE, 4L)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private CommunicationTemplate whatsApp() {
        return CommunicationTemplate.builder().eventType(CommunicationEventType.SHIPMENT_BOOKED)
                .channel(CommunicationChannel.WHATSAPP).templateName("shipment_booked").content("Hi {{customerName}}!")
                .status(TemplateStatus.ACTIVE).build();
    }

    private UpdateCommunicationTemplateCommand update(CommunicationTemplate t, String category, String language,
            String header, String footer, List<com.courier.modules.communication.domain.TemplateButton> buttons,
            java.util.Map<String, String> samples) {
        return new UpdateCommunicationTemplateCommand(t.getTemplateName(), null, t.getContent(), TemplateStatus.ACTIVE,
                null, category, language, header, footer, buttons, samples);
    }

    @Test
    void update_savesCategoryLanguageHeaderFooterButtonsAndSamples() {
        CommunicationTemplate t = whatsApp();
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        var updated = service.update(java.util.UUID.randomUUID(), update(t, "marketing", "hi", " Shipment {{shipmentNumber}} ",
                "Thank you", List.of(new com.courier.modules.communication.domain.TemplateButton(
                        com.courier.modules.communication.domain.TemplateButton.Type.URL, "Track",
                        "https://x.in/t/{{trackingNumber}}", null)), java.util.Map.of("customerName", "Asha")));

        assertThat(updated.getCategory()).isEqualTo("MARKETING");
        assertThat(updated.getLanguage()).isEqualTo("hi");
        assertThat(updated.getHeaderText()).isEqualTo("Shipment {{shipmentNumber}}");
        assertThat(updated.getFooterText()).isEqualTo("Thank you");
        assertThat(updated.getButtonsJson()).contains("Track").contains("https://x.in/t/{{trackingNumber}}");
        assertThat(updated.getVariableSamplesJson()).contains("Asha");
    }

    @Test
    void update_nullLeavesStoredPartsAloneAndBlankClearsThem() {
        CommunicationTemplate t = whatsApp();
        t.setHeaderText("Old header");
        t.setFooterText("Old footer");
        t.setLanguage("mr");
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        var untouched = service.update(java.util.UUID.randomUUID(), update(t, null, null, null, null, null, null));
        assertThat(untouched.getHeaderText()).isEqualTo("Old header");
        assertThat(untouched.getFooterText()).isEqualTo("Old footer");
        assertThat(untouched.getLanguage()).isEqualTo("mr");

        var cleared = service.update(java.util.UUID.randomUUID(), update(t, null, null, "", "  ", List.of(), java.util.Map.of()));
        assertThat(cleared.getHeaderText()).isNull();
        assertThat(cleared.getFooterText()).isNull();
        assertThat(cleared.getButtonsJson()).isNull();
    }

    @Test
    void update_rejectsBadCategoryLanguageAndWhatsAppPartsOnOtherChannels() {
        CommunicationTemplate t = whatsApp();
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.update(java.util.UUID.randomUUID(), update(t, "PROMO", null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.update(java.util.UUID.randomUUID(), update(t, null, "fr", null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class);

        CommunicationTemplate sms = whatsApp();
        sms.setChannel(CommunicationChannel.SMS);
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(sms));
        assertThatThrownBy(() -> service.update(java.util.UUID.randomUUID(), update(sms, null, null, "Header", null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("WhatsApp-only");
    }

    @Test
    void update_changingAButtonOrLanguageClearsApprovalButChangingSamplesDoesNot() {
        CommunicationTemplate t = whatsApp();
        t.setMetaStatus("APPROVED");
        t.setMetaTemplateId("1");
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        service.update(java.util.UUID.randomUUID(), update(t, null, null, null, null, null, java.util.Map.of("customerName", "Asha")));
        assertThat(t.getMetaStatus()).isEqualTo("APPROVED");

        service.update(java.util.UUID.randomUUID(), update(t, null, "hi", null, null, null, null));
        assertThat(t.getMetaStatus()).isNull();

        t.setMetaStatus("APPROVED");
        service.update(java.util.UUID.randomUUID(), update(t, null, null, null, null, List.of(
                new com.courier.modules.communication.domain.TemplateButton(
                        com.courier.modules.communication.domain.TemplateButton.Type.PHONE_NUMBER, "Call", null, "+919123456780")), null));
        assertThat(t.getMetaStatus()).isNull();
    }

    @Test
    void setStatus_switchesOnlyTheStatusAndKeepsMetaApproval() {
        CommunicationTemplate t = whatsApp();
        t.setMetaStatus("APPROVED");
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        var off = service.setStatus(java.util.UUID.randomUUID(), TemplateStatus.INACTIVE);
        assertThat(off.getStatus()).isEqualTo(TemplateStatus.INACTIVE);
        assertThat(off.getMetaStatus()).isEqualTo("APPROVED");
        assertThat(off.getContent()).isEqualTo("Hi {{customerName}}!");

        var on = service.setStatus(java.util.UUID.randomUUID(), TemplateStatus.ACTIVE);
        assertThat(on.isActive()).isTrue();
    }

    @Test
    void setStatus_isANoOpWhenAlreadyThere() {
        CommunicationTemplate t = whatsApp();
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        service.setStatus(java.util.UUID.randomUUID(), TemplateStatus.ACTIVE);

        verify(repository, times(0)).save(any());
        verify(auditService, times(0)).record(any(), any(), any(), any());
        assertThatThrownBy(() -> service.setStatus(java.util.UUID.randomUUID(), null))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ------------------------------------------------------------------ starter + reset

    @Test
    void starter_returnsTheSeedForASeededEventAndAGeneratedOneOtherwise() {
        var seeded = service.starter(CommunicationEventType.SHIPMENT_BOOKED, CommunicationChannel.SMS);
        assertThat(seeded.seeded()).isTrue();
        assertThat(seeded.templateName()).isEqualTo("Shipment Booked - SMS");

        var generated = service.starter(CommunicationEventType.SHIPMENT_CANCELLED, CommunicationChannel.WHATSAPP);
        assertThat(generated.seeded()).isFalse();
        assertThat(generated.templateName()).isEqualTo("shipment_cancelled");

        assertThatThrownBy(() -> service.starter(null, CommunicationChannel.SMS)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.starter(CommunicationEventType.DAILY_BUSINESS_SUMMARY, CommunicationChannel.SMS))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resetToDefault_restoresEverythingButWhetherItSends() {
        CommunicationTemplate t = whatsApp();
        t.setTemplateName("Shipment Booked - WhatsApp");
        t.setContent("old text ending in {{trackingUrl}}");
        t.setCategory("MARKETING");
        t.setLanguage("hi");
        t.setHeaderText("Header");
        t.setFooterText("Footer");
        t.setButtonsJson("[{\"type\":\"URL\",\"text\":\"x\",\"url\":\"https://x.in\"}]");
        t.setVariableSamplesJson("{\"customerName\":\"Asha\"}");
        t.setMetaStatus("APPROVED");
        t.setMetaTemplateId("55");
        t.setStatus(TemplateStatus.INACTIVE);
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        var reset = service.resetToDefault(java.util.UUID.randomUUID());

        assertThat(reset.getTemplateName()).isEqualTo("shipment_booked");
        assertThat(reset.getContent()).startsWith("Hi {{customerName}}").endsWith("thank you!");
        assertThat(reset.getCategory()).isEqualTo("UTILITY");
        assertThat(reset.getLanguage()).isEqualTo("en_US");
        assertThat(reset.getHeaderText()).isNull();
        assertThat(reset.getFooterText()).isNull();
        assertThat(reset.getButtonsJson()).isNull();
        assertThat(reset.getVariableSamplesJson()).isNull();
        assertThat(reset.getMetaStatus()).isNull();
        assertThat(reset.getMetaTemplateId()).isNull();
        assertThat(reset.getStatus()).isEqualTo(TemplateStatus.INACTIVE); // the on/off switch is the admin's
    }

    @Test
    void resetToDefault_ofATemplateWithoutASeedUsesTheGeneratedStarter() {
        CommunicationTemplate t = whatsApp();
        t.setEventType(CommunicationEventType.SHIPMENT_CANCELLED);
        t.setTemplateName("whatever");
        t.setContent("mine");
        when(repository.findByIdWithinCompany(any(), any())).thenReturn(Optional.of(t));

        var reset = service.resetToDefault(java.util.UUID.randomUUID());

        assertThat(reset.getTemplateName()).isEqualTo("shipment_cancelled");
        assertThat(reset.getContent()).contains("has been cancelled");
    }
}
