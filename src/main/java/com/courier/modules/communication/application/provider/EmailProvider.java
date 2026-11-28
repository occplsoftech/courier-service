package com.courier.modules.communication.application.provider;

/**
 * "Use existing email infrastructure if available" — none does (auth's own {@code
 * NotificationPort}/{@code LogOnlyNotificationSender} confirms no SMTP is wired anywhere in
 * this codebase yet). {@code SmtpEmailProvider} uses a platform-level {@code JavaMailSender}
 * (env-configured {@code spring.mail.*}, the same "no default, fails only when actually
 * asked to send" posture {@code SecretsEncryptionProperties} already takes) — a company only
 * sets its own {@code fromName}/{@code fromEmail} identity, not SMTP credentials, since SMTP
 * itself is platform infrastructure here, not a per-company secret. {@code
 * LogOnlyEmailProvider} is the default when {@code spring.mail.host} isn't set.
 */
public interface EmailProvider {

    ProviderSendResult send(EmailMessage message, EmailIdentity identity);

    record EmailMessage(
            String toEmail,
            String subject,
            /** HTML body — templates may contain markup, per the brief's own "Support HTML
             *  templates". */
            String htmlBody,
            /** Images the body shows as {@code <img src="cid:contentId">}, sent inside the e-mail (a logo): mail apps
             *  such as Gmail do not show {@code data:} images. Empty for most messages. */
            java.util.List<InlineImage> inlineImages
    ) {
        public EmailMessage {
            inlineImages = inlineImages == null ? java.util.List.of() : java.util.List.copyOf(inlineImages);
        }

        /** A plain HTML message with no inline images. */
        public EmailMessage(String toEmail, String subject, String htmlBody) {
            this(toEmail, subject, htmlBody, java.util.List.of());
        }
    }

    /** One image sent inside an e-mail, shown where the body says {@code cid:contentId}. */
    record InlineImage(String contentId, byte[] bytes, String contentType) {
    }

    record EmailIdentity(String fromName, String fromEmail) {
    }
}
