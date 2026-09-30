package com.bot.bot.webhook;

import com.bot.bot.config.GitHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureVerifierExtendedTest {

    private GitHubProperties properties;
    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new GitHubProperties();
        properties.setWebhookSecret("my-webhook-secret-xyz");
        verifier = new WebhookSignatureVerifier(properties);
    }

    private String calculateSignature(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(raw);
    }

    @Test
    @DisplayName("Returns true for perfectly matching signature and payload")
    void verifiesValidSignature() throws Exception {
        String payload = "{\"action\": \"opened\", \"number\": 10}";
        String sig = calculateSignature("my-webhook-secret-xyz", payload);

        assertTrue(verifier.verifySignature(payload, sig));
    }

    @Test
    @DisplayName("Returns false when signature is calculated with different secret")
    void rejectsWrongSecretSignature() throws Exception {
        String payload = "{\"action\": \"opened\"}";
        String wrongSig = calculateSignature("wrong-secret", payload);

        assertFalse(verifier.verifySignature(payload, wrongSig));
    }

    @Test
    @DisplayName("Returns false when payload has been tampered with")
    void rejectsTamperedPayload() throws Exception {
        String originalPayload = "{\"action\": \"opened\"}";
        String sig = calculateSignature("my-webhook-secret-xyz", originalPayload);

        String tamperedPayload = "{\"action\": \"closed\"}";
        assertFalse(verifier.verifySignature(tamperedPayload, sig));
    }

    @Test
    @DisplayName("Returns false when webhookSecret in properties is null or blank")
    void rejectsWhenSecretNotConfigured() {
        properties.setWebhookSecret(null);
        assertFalse(verifier.verifySignature("{}", "sha256=test"));

        properties.setWebhookSecret("");
        assertFalse(verifier.verifySignature("{}", "sha256=test"));

        properties.setWebhookSecret("   ");
        assertFalse(verifier.verifySignature("{}", "sha256=test"));
    }

    @Test
    @DisplayName("Returns false when signature parameter is null, blank, or missing prefix")
    void rejectsNullOrBlankSignature() {
        assertFalse(verifier.verifySignature("{}", null));
        assertFalse(verifier.verifySignature("{}", ""));
        assertFalse(verifier.verifySignature("{}", "   "));
        assertFalse(verifier.verifySignature("{}", "rawhexwithoutprefix"));
    }

    @Test
    @DisplayName("Handles UTF-8 Unicode characters in payload correctly")
    void handlesUnicodePayload() throws Exception {
        String payload = "{\"message\": \"你好世界 🚀 Pull Request\"}";
        String sig = calculateSignature("my-webhook-secret-xyz", payload);

        assertTrue(verifier.verifySignature(payload, sig));
    }

    @Test
    @DisplayName("Handles empty JSON payload correctly")
    void handlesEmptyPayload() throws Exception {
        String payload = "";
        String sig = calculateSignature("my-webhook-secret-xyz", payload);

        assertTrue(verifier.verifySignature(payload, sig));
    }
}
