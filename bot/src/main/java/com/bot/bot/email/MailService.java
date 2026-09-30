package com.bot.bot.email;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bot.bot.config.MailProperties;

import jakarta.mail.internet.MimeMessage;

import java.util.List;

/**
 * Thin wrapper over JavaMailSender that no-ops (logs) when mail is disabled
 * or no sender bean is present, so callers never need null checks.
 */
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final MailProperties props;
    private final JavaMailSender sender;

    public MailService(MailProperties props, JavaMailSender sender) {
        this.props = props;
        this.sender = sender;
    }

    public boolean isEnabled() {
        return props.isEnabled() && sender != null;
    }

    public record SendResult(boolean success, String message, List<String> recipients) {}

    public SendResult sendEmailWithStatus(List<String> to, String subject, String htmlBody) {
        saveHtmlPreview(htmlBody);
        if (!isEnabled()) {
            String msg = "SMTP is disabled or not configured. To enable live delivery, ensure MAIL_ENABLED=true and Gmail credentials in bot/.env";
            log.info("═══════════════════════════════════════════════════════════════════");
            log.info("📧 [PR TRIAGE EMAIL NOTIFICATION] (Local Dev / SMTP Disabled)");
            log.info("   To:      {}", (to != null && !to.isEmpty()) ? to : "[Repository Owner / Maintainers]");
            log.info("   Subject: {}", subject);
            extractAndLogActionLinks(htmlBody);
            log.info("   Full HTML Preview saved: data/latest_email.html");
            log.info("═══════════════════════════════════════════════════════════════════");
            return new SendResult(false, msg, to);
        }
        if (to == null || to.isEmpty()) {
            String msg = "No recipient email address specified or resolved.";
            log.warn("Mail enabled but no recipients for subject='{}' - skipping", subject);
            return new SendResult(false, msg, to);
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            String from = (props.getFrom() != null && !props.getFrom().isBlank()) ? props.getFrom() : props.getUsername();
            if (from == null || from.isBlank()) {
                from = "workwithpranav07@gmail.com";
            }
            String senderName = (props.getSenderName() != null && !props.getSenderName().isBlank())
                    ? props.getSenderName().trim()
                    : "PR-Triage";
            helper.setFrom(from, senderName);
            helper.setTo(to.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            sender.send(message);
            log.info("✅ Sent email '{}' to {} recipient(s): {}", subject, to.size(), to);
            return new SendResult(true, "Sent email successfully to " + String.join(", ", to), to);
        } catch (Exception e) {
            log.error("❌ Failed to send email '{}': {}", subject, e.getMessage(), e);
            return new SendResult(false, "SMTP Error: " + e.getMessage(), to);
        }
    }

    public void sendEmail(List<String> to, String subject, String htmlBody) {
        sendEmailWithStatus(to, subject, htmlBody);
    }

    private void saveHtmlPreview(String htmlBody) {
        if (htmlBody == null) return;
        try {
            java.nio.file.Path dataDir = java.nio.file.Paths.get("data");
            if (!java.nio.file.Files.exists(dataDir)) {
                java.nio.file.Files.createDirectories(dataDir);
            }
            java.nio.file.Files.writeString(dataDir.resolve("latest_email.html"), htmlBody, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Could not write latest_email.html: {}", e.getMessage());
        }
    }

    private void extractAndLogActionLinks(String html) {
        if (html == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("href=\"([^\"]+/action\\?[^\"]+)\"").matcher(html);
        while (m.find()) {
            String url = m.group(1);
            if (url.contains("do=approve")) {
                log.info("   👉 Quick Approve Link: {}", url);
            } else if (url.contains("do=reject")) {
                log.info("   👉 Quick Reject Link:  {}", url);
            } else {
                log.info("   👉 Action Link:        {}", url);
            }
        }
    }
}
