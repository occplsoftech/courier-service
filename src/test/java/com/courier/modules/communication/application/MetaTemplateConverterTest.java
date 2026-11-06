package com.courier.modules.communication.application;

import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaTemplateConverterTest {

    private static MetaTemplateConverter.Converted convert(String content) {
        return MetaTemplateConverter.convert(content, TemplateSampleValues.all());
    }

    @Test
    void numbersPlaceholdersByFirstAppearanceAndReusesTheNumberForRepeats() {
        var c = convert("Hi {{customerName}}, {{trackingNumber}} for {{customerName}} via {{companyName}}.");

        assertThat(c.bodyText()).isEqualTo("Hi {{1}}, {{2}} for {{1}} via {{3}}.");
        assertThat(c.variables()).containsExactly("customerName", "trackingNumber", "companyName");
        assertThat(c.examples()).containsExactly("Rahul Sharma", "AWB123456789", "Amazing Logistics");
    }

    @Test
    void theNumberingMatchesWhatTheSenderPositionsAreSentIn() {
        String content = "A {{trackingNumber}} B {{customerName}} C {{trackingNumber}} D";
        var converted = convert(content);
        var sent = new TemplateRenderer("http://x/{trackingNumber}").positionalParameters(content,
                java.util.Map.of("trackingNumber", "T1", "customerName", "N1"));

        assertThat(converted.variables()).containsExactly("trackingNumber", "customerName");
        assertThat(sent).containsExactly("T1", "N1");
    }

    @Test
    void aTemplateWithNoVariablesHasNoExamples() {
        var c = convert("Welcome to our service.");
        assertThat(c.bodyText()).isEqualTo("Welcome to our service.");
        assertThat(c.examples()).isEmpty();
    }

    @Test
    void rejectsAnUnknownVariable() {
        assertThatThrownBy(() -> convert("Hi {{nope}} thanks"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("{{nope}}");
    }

    @Test
    void rejectsABodyThatStartsOrEndsWithAVariable() {
        assertThatThrownBy(() -> convert("{{customerName}} hello there"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("starts or ends");
        assertThatThrownBy(() -> convert("hello there {{customerName}}"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("starts or ends");
    }

    @Test
    void rejectsBlankAndOverlongContent() {
        assertThatThrownBy(() -> convert("  ")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> convert("x".repeat(MetaTemplateConverter.MAX_BODY + 1)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("1024");
    }

    @Test
    void nameMustBeLowercaseUnderscoreDigits() {
        MetaTemplateConverter.requireValidName("shipment_booked_v2");
        assertThatThrownBy(() -> MetaTemplateConverter.requireValidName("Shipment Booked - WhatsApp"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("lowercase");
        assertThatThrownBy(() -> MetaTemplateConverter.requireValidName(null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void theDefaultDailyTemplateIsSubmittable() {
        var seed = com.courier.modules.communication.domain.DefaultCommunicationTemplates.DAILY_SUMMARY;
        MetaTemplateConverter.requireValidName(seed.templateName());
        var c = convert(seed.content());
        assertThat(c.variables()).hasSize(11);
        assertThat(c.bodyText()).startsWith("Daily business summary for {{1}} - {{2}}").endsWith("Have a great day!");
    }

    @Test
    void everyStockWhatsAppTemplateCanBeSubmittedAsSeeded() {
        var stock = com.courier.modules.communication.domain.DefaultCommunicationTemplates.all().stream()
                .filter(sd -> sd.channel() == com.courier.modules.communication.domain.CommunicationChannel.WHATSAPP)
                .toList();

        assertThat(stock).hasSize(6);
        for (var seed : stock) {
            MetaTemplateConverter.requireValidName(seed.templateName());
            assertThat(convert(seed.content()).bodyText()).as(seed.templateName()).isNotBlank();
        }
    }

    @Test
    void headerVariableBecomesNumberOneWithItsSample() {
        var h = MetaTemplateConverter.convertHeader("Shipment {{shipmentNumber}}", TemplateSampleValues.all());
        assertThat(h.text()).isEqualTo("Shipment {{1}}");
        assertThat(h.example()).isEqualTo("SHP-000123");
        assertThat(MetaTemplateConverter.convertHeader("Plain header", TemplateSampleValues.all()).example()).isNull();
        assertThat(MetaTemplateConverter.convertHeader("  ", TemplateSampleValues.all())).isNull();
        assertThatThrownBy(() -> MetaTemplateConverter.convertHeader("{{nope}}", TemplateSampleValues.all()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void urlButtonWithATrailingVariableGetsMetasPlaceholderAndAFullExampleUrl() {
        var buttons = MetaTemplateConverter.convertButtons(java.util.List.of(
                new com.courier.modules.communication.domain.TemplateButton(
                        com.courier.modules.communication.domain.TemplateButton.Type.URL, "Track",
                        "https://courier.skra.in/track/{{trackingNumber}}", null),
                new com.courier.modules.communication.domain.TemplateButton(
                        com.courier.modules.communication.domain.TemplateButton.Type.PHONE_NUMBER, "Call", null, "+919123456780"),
                new com.courier.modules.communication.domain.TemplateButton(
                        com.courier.modules.communication.domain.TemplateButton.Type.URL, "Help", "https://x.in/help", null)),
                TemplateSampleValues.all());

        assertThat(buttons.get(0).url()).isEqualTo("https://courier.skra.in/track/{{1}}");
        assertThat(buttons.get(0).urlExample()).isEqualTo("https://courier.skra.in/track/AWB123456789");
        assertThat(buttons.get(1).phoneNumber()).isEqualTo("+919123456780");
        assertThat(buttons.get(2).url()).isEqualTo("https://x.in/help");
        assertThat(buttons.get(2).urlExample()).isNull();
    }

    @Test
    void aTemplatesOwnSamplesOverrideTheBuiltInOnes() {
        var merged = TemplateSampleValues.merged(java.util.Map.of("customerName", "Asha Patil", "amount", " "));
        assertThat(merged).containsEntry("customerName", "Asha Patil").containsEntry("amount", "450.00");
        var c = MetaTemplateConverter.convert("Hi {{customerName}}, thanks!", merged);
        assertThat(c.examples()).containsExactly("Asha Patil");
        assertThat(TemplateSampleValues.merged(null)).isSameAs(TemplateSampleValues.all());
    }
}
