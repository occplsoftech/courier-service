package com.courier.modules.communication.infrastructure;

import com.courier.modules.communication.application.provider.EmailProvider;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpEmailProviderInlineTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final SmtpEmailProvider provider = new SmtpEmailProvider(sender);
    private final EmailProvider.EmailIdentity from = new EmailProvider.EmailIdentity("OCCPL Logistics", "support@occpl.test");

    private MimeMessage sent(EmailProvider.EmailMessage message) throws Exception {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        provider.send(message, from);
        ArgumentCaptor<MimeMessage> m = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(m.capture());
        m.getValue().saveChanges();
        return m.getValue();
    }

    private static void parts(Part p, List<Part> out) throws Exception {
        out.add(p);
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) parts(mp.getBodyPart(i), out);
        }
    }

    @Test
    @DisplayName("an inline logo goes inside the e-mail as an image part with its Content-ID")
    void inlineLogo() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        MimeMessage m = sent(new EmailProvider.EmailMessage("a@b.test", "Your booking details",
                "<img src=\"cid:company-logo\">", List.of(new EmailProvider.InlineImage("company-logo", png, "image/png"))));
        List<Part> all = new ArrayList<>();
        parts(m, all);
        Part logo = all.stream().filter(p -> {
            try { return p.isMimeType("image/png"); } catch (Exception e) { return false; }
        }).findFirst().orElseThrow();
        assertThat(((jakarta.mail.internet.MimePart) logo).getContentID()).isEqualTo("<company-logo>");
        assertThat(logo.getDisposition()).isEqualTo(Part.INLINE);
    }

    @Test
    @DisplayName("a message without inline images is still a single HTML body")
    void plainStaysPlain() throws Exception {
        MimeMessage m = sent(new EmailProvider.EmailMessage("a@b.test", "Hi", "<p>Hi</p>"));
        assertThat(m.isMimeType("text/html")).isTrue();
    }
}
