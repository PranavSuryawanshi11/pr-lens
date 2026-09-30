package com.bot.bot.email;

import com.bot.bot.config.MailProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailServiceTest {

    private MailProperties props;
    private JavaMailSender sender;

    @BeforeEach
    void setUp() {
        props = new MailProperties();
        sender = mock(JavaMailSender.class);
    }

    @Test
    @DisplayName("isEnabled returns false when props.enabled is false")
    void isEnabledReturnsFalseWhenPropsDisabled() {
        props.setEnabled(false);
        MailService service = new MailService(props, sender);
        assertFalse(service.isEnabled());
    }

    @Test
    @DisplayName("isEnabled returns false when sender is null")
    void isEnabledReturnsFalseWhenSenderIsNull() {
        props.setEnabled(true);
        MailService service = new MailService(props, null);
        assertFalse(service.isEnabled());
    }

    @Test
    @DisplayName("isEnabled returns true when props.enabled is true and sender is present")
    void isEnabledReturnsTrueWhenConfigured() {
        props.setEnabled(true);
        MailService service = new MailService(props, sender);
        assertTrue(service.isEnabled());
    }

    @Test
    @DisplayName("sendEmailWithStatus returns failure result when mail is disabled")
    void returnsFailureResultWhenDisabled() {
        props.setEnabled(false);
        MailService service = new MailService(props, sender);

        MailService.SendResult result = service.sendEmailWithStatus(
                List.of("dev@example.com"), "Test Subject", "<p>Body</p>"
        );

        assertFalse(result.success());
        assertTrue(result.message().contains("SMTP is disabled"));
        assertEquals(List.of("dev@example.com"), result.recipients());
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendEmailWithStatus returns failure when recipient list is empty")
    void returnsFailureWhenRecipientsEmpty() {
        props.setEnabled(true);
        MailService service = new MailService(props, sender);

        MailService.SendResult result = service.sendEmailWithStatus(
                Collections.emptyList(), "Subject", "<p>Body</p>"
        );

        assertFalse(result.success());
        assertTrue(result.message().contains("No recipient"));
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendEmailWithStatus returns failure when recipient list is null")
    void returnsFailureWhenRecipientsNull() {
        props.setEnabled(true);
        MailService service = new MailService(props, sender);

        MailService.SendResult result = service.sendEmailWithStatus(
                null, "Subject", "<p>Body</p>"
        );

        assertFalse(result.success());
        assertTrue(result.message().contains("No recipient"));
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendEmailWithStatus successfully sends email when enabled and valid")
    void sendsEmailSuccessfully() {
        props.setEnabled(true);
        props.setFrom("bot@example.com");
        props.setSenderName("Glint Bot");

        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mimeMessage);

        MailService service = new MailService(props, sender);

        MailService.SendResult result = service.sendEmailWithStatus(
                List.of("lead@example.com"), "PR #1 Triaged", "<b>Details</b>"
        );

        assertTrue(result.success());
        assertTrue(result.message().contains("Sent email successfully"));
        verify(sender).send(mimeMessage);
    }

    @Test
    @DisplayName("sendEmail delegates to sendEmailWithStatus without error")
    void sendEmailDelegatesCleanly() {
        props.setEnabled(false);
        MailService service = new MailService(props, sender);

        assertDoesNotThrow(() ->
                service.sendEmail(List.of("user@example.com"), "Subject", "Body")
        );
    }

    @Test
    @DisplayName("Handles exception during sender.send and returns error result")
    void handlesSmtpExceptionGracefully() {
        props.setEnabled(true);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("SMTP Authentication Failed")).when(sender).send(any(MimeMessage.class));

        MailService service = new MailService(props, sender);

        MailService.SendResult result = service.sendEmailWithStatus(
                List.of("user@example.com"), "Subject", "Body"
        );

        assertFalse(result.success());
        assertTrue(result.message().contains("SMTP Error: SMTP Authentication Failed"));
    }

    @Test
    @DisplayName("Falls back to username or default email when from address is blank")
    void fallsBackToUsernameWhenFromBlank() {
        props.setEnabled(true);
        props.setFrom("");
        props.setUsername("mybot@gmail.com");

        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mimeMessage);

        MailService service = new MailService(props, sender);
        MailService.SendResult result = service.sendEmailWithStatus(
                List.of("user@example.com"), "Subject", "Body"
        );

        assertTrue(result.success());
    }
}
