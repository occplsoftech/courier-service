package com.courier.modules.communication.infrastructure;

import com.courier.modules.communication.application.provider.EmailProvider;
import com.courier.modules.communication.application.provider.ProviderSendException;
import com.courier.modules.communication.application.provider.ProviderSendResult;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Platform-level SMTP, {@code spring.mail.*} env-configured — one mail server for the whole
 * deployment, matching the brief's own "Use existing email infrastructure if available"
 * (there was none; this is the infrastructure). A company only supplies its own {@code
 * fromName}/{@code fromEmail} identity via {@code CommunicationSetting.configJson} — not a
 * per-company SMTP credential, since most real deployments send transactional mail through
 * one shared, authenticated relay regardless of which company triggered it.
 */
@Slf4j
public class SmtpEmailProvider implements EmailProvider {

    private final JavaMailSender mailSender;

    public SmtpEmailProvider(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public ProviderSendResult send(EmailMessage message, EmailIdentity identity) {
        if (message.toEmail() == null || message.toEmail().isBlank()) {
            throw new ProviderSendException("No recipient email address on file.");
        }
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            boolean inline = !message.inlineImages().isEmpty();
            MimeMessageHelper helper = new MimeMessageHelper(mime, inline, "UTF-8");
            helper.setTo(message.toEmail());
            helper.setSubject(message.subject() == null ? "" : message.subject());
            helper.setText(message.htmlBody() == null ? "" : message.htmlBody(), true);
            // Inline parts go after the body (JavaMail requires the text first).
            for (EmailProvider.InlineImage img : message.inlineImages()) {
                helper.addInline(img.contentId(), new org.springframework.core.io.ByteArrayResource(img.bytes()), img.contentType());
            }
            String fromEmail = identity.fromEmail() == null || identity.fromEmail().isBlank()
                    ? null : identity.fromEmail();
            if (fromEmail != null) {
                helper.setFrom(fromEmail, identity.fromName() == null ? fromEmail : identity.fromName());
            }
            mailSender.send(mime);
            log.info("Email sent to {} via SMTP, subject '{}'", message.toEmail(), message.subject());
            // JavaMailSender's synchronous send() gives no vendor-assigned message id —
            // the SMTP transaction succeeding is the only confirmation available here.
            return new ProviderSendResult(null);
        } catch (ProviderSendException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderSendException("SMTP send failed: " + e.getMessage(), e);
        }
    }
}
