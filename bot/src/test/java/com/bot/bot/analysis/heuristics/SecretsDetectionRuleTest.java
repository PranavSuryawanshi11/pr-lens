package com.bot.bot.analysis.heuristics;

import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecretsDetectionRuleTest {

    private SecretsDetectionRule rule;

    @BeforeEach
    void setUp() {
        rule = new SecretsDetectionRule();
    }

    @Test
    @DisplayName("Returns empty findings when chunks list is empty")
    void returnsEmptyWhenChunksEmpty() {
        List<Finding> findings = rule.analyze(Collections.emptyList());
        assertNotNull(findings);
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Returns empty findings when code has no secrets")
    void returnsEmptyWhenCodeIsClean() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("src/main/Config.java")
                .startLine(1)
                .addedLines(List.of("public final int MAX_RETRIES = 5;", "public final String APP_NAME = \"PR-Triage\";"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Detects AWS Access Key ID starting with AKIA")
    void detectsAwsAccessKeyId() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("aws_config.py")
                .startLine(12)
                .addedLines(List.of("AWS_KEY = 'AKIAIOSFODNN7EXAMPLE'"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertEquals("aws_config.py", f.getFilePath());
        assertEquals(12, f.getLineNumber());
        assertEquals("CRITICAL", f.getSeverity());
        assertEquals("SECURITY", f.getCategory());
        assertTrue(f.getMessage().contains("AWS_KEY"));
        assertEquals(0.95, f.getConfidence());
        assertEquals(1000, f.getPrecedenceScore());
    }

    @Test
    @DisplayName("Detects RSA Private Key PEM header")
    void detectsRsaPrivateKeyPem() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("keys/server.key")
                .startLine(1)
                .addedLines(List.of("-----BEGIN RSA PRIVATE KEY-----", "MIIEowIBAAKCAQEA0..."))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertTrue(f.getMessage().contains("PRIVATE_KEY"));
        assertEquals("CRITICAL", f.getSeverity());
    }

    @Test
    @DisplayName("Detects generic PKCS#8 Private Key PEM header")
    void detectsPkcs8PrivateKeyPem() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("keys/id_rsa")
                .startLine(1)
                .addedLines(List.of("-----BEGIN PRIVATE KEY-----"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).getMessage().contains("PRIVATE_KEY"));
    }

    @Test
    @DisplayName("Detects hardcoded password assignment")
    void detectsHardcodedPassword() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("settings.py")
                .startLine(25)
                .addedLines(List.of("db_password = \"superSecretP@ssw0rd\""))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).getMessage().contains("PASSWORD"));
    }

    @Test
    @DisplayName("Detects hardcoded passwd with colon syntax")
    void detectsPasswdWithColon() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("config.yaml")
                .startLine(4)
                .addedLines(List.of("passwd: 'admin12345'"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).getMessage().contains("PASSWORD"));
    }

    @Test
    @DisplayName("Detects API key assignment")
    void detectsApiKey() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("client.ts")
                .startLine(8)
                .addedLines(List.of("const apiKey = 'sk-1234567890abcdef12345';"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).getMessage().contains("API_KEY"));
    }

    @Test
    @DisplayName("Detects GitHub personal access token (ghp_)")
    void detectsGitHubToken() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("deploy.sh")
                .startLine(3)
                .addedLines(List.of("export GITHUB_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz1234567890"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).getMessage().contains("GITHUB_TOKEN"));
    }

    @Test
    @DisplayName("Detects Slack Bot/User token (xoxb-)")
    void detectsSlackToken() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("slack.js")
                .startLine(10)
                .addedLines(List.of("const token = 'xoxb-123456789012-123456789012-abcdef123456';"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).getMessage().contains("SLACK_TOKEN"));
    }

    @Test
    @DisplayName("Ignores secrets in removed lines (only checks addedLines)")
    void ignoresRemovedSecrets() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("legacy.py")
                .startLine(1)
                .addedLines(List.of("password = os.getenv('DB_PASSWORD')"))
                .removedLines(List.of("password = 'plaintext_old_password'"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Returns correct rule name")
    void returnsCorrectRuleName() {
        assertEquals("SecretsDetectionRule", rule.getName());
    }
}
