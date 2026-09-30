package com.bot.bot.config;

import com.bot.bot.actions.TokenService;
import com.bot.bot.email.EmailTemplate;
import com.bot.bot.email.MailService;
import com.bot.bot.email.ThresholdAlertService;
import com.bot.bot.persistence.MetaRepository;
import com.bot.bot.persistence.PrAnalysisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ConfigPropertiesTest {

    @Test
    @DisplayName("AppProperties default values and setters")
    void appPropertiesDefaults() {
        AppProperties props = new AppProperties();
        assertTrue(props.isHeuristicsEnabled());
        assertTrue(props.isLlmEnabled());
        assertFalse(props.isAutoApprove());
        assertTrue(props.isInlineComments());
        assertTrue(props.isReviewSummaryEnabled());
        assertNull(props.getActionSecret());
        assertNull(props.getBaseUrl());

        props.setAutoApprove(true);
        props.setActionSecret("secret");
        props.setBaseUrl("http://localhost:8080");

        assertTrue(props.isAutoApprove());
        assertEquals("secret", props.getActionSecret());
        assertEquals("http://localhost:8080", props.getBaseUrl());
    }

    @Test
    @DisplayName("GitHubProperties hasToken returns true only when token is non-blank")
    void gitHubPropertiesHasToken() {
        GitHubProperties props = new GitHubProperties();
        assertFalse(props.hasToken());

        props.setToken("");
        assertFalse(props.hasToken());

        props.setToken("   ");
        assertFalse(props.hasToken());

        props.setToken("ghp_12345");
        assertTrue(props.hasToken());
    }

    @Test
    @DisplayName("GitHubProperties hasAppCredentials checks appId and file existence")
    void gitHubPropertiesHasAppCredentials(@TempDir Path tempDir) throws IOException {
        GitHubProperties props = new GitHubProperties();
        assertFalse(props.hasAppCredentials());

        props.setAppId("12345");
        props.setPrivateKeyPath("nonexistent.pem");
        assertFalse(props.hasAppCredentials());

        Path keyFile = tempDir.resolve("key.pem");
        Files.writeString(keyFile, "pem content");
        props.setPrivateKeyPath(keyFile.toString());
        assertTrue(props.hasAppCredentials());

        props.setAppId("");
        assertFalse(props.hasAppCredentials());
    }

    @Test
    @DisplayName("LLMProperties default values and providers list")
    void llmPropertiesDefaults() {
        LLMProperties props = new LLMProperties();
        assertTrue(props.isEnabled());
        assertEquals(60, props.getTimeoutSeconds());
        assertNotNull(props.getProviders());
        assertTrue(props.getProviders().isEmpty());

        ProviderConfig p = new ProviderConfig();
        p.setName("nim");
        props.setProviders(List.of(p));
        assertEquals(1, props.getProviders().size());
        assertEquals("nim", props.getProviders().get(0).getName());
    }

    @Test
    @DisplayName("ProviderConfig default values and setters")
    void providerConfigDefaults() {
        ProviderConfig p = new ProviderConfig();
        assertEquals("openai-compatible", p.getProviderType());
        assertEquals("", p.getName());
        assertEquals("http://localhost:8000", p.getBaseUrl());
        assertEquals("gpt-4o-mini", p.getModel());
        assertEquals("", p.getApiKey());

        p.setProviderType("ollama");
        p.setName("local-llama");
        p.setBaseUrl("http://localhost:11434");
        p.setModel("llama3.2");
        p.setApiKey("none");

        assertEquals("ollama", p.getProviderType());
        assertEquals("local-llama", p.getName());
        assertEquals("http://localhost:11434", p.getBaseUrl());
        assertEquals("llama3.2", p.getModel());
    }

    @Test
    @DisplayName("MailProperties default values and setters")
    void mailPropertiesDefaults() {
        MailProperties props = new MailProperties();
        assertFalse(props.isEnabled());
        assertEquals(25, props.getPort());
        assertEquals("PR-Triage", props.getSenderName());
        assertEquals("0 0 18 * * *", props.getDigestCron());
        assertNotNull(props.getMaintainerEmails());

        props.setEnabled(true);
        props.setHost("smtp.gmail.com");
        props.setPort(587);
        props.setUsername("bot@gmail.com");
        props.setPassword("pass");
        props.setFrom("bot@gmail.com");

        assertTrue(props.isEnabled());
        assertEquals("smtp.gmail.com", props.getHost());
        assertEquals(587, props.getPort());
    }

    @Test
    @DisplayName("ActionsConfig bean factory methods and validateActionConfig")
    void actionsConfigBeans() {
        AppProperties appProps = new AppProperties();
        MailProperties mailProps = new MailProperties();
        ConfigService configService = mock(ConfigService.class);

        ActionsConfig actionsConfig = new ActionsConfig(appProps, mailProps, configService);

        // When mail is disabled, validation does not warn
        mailProps.setEnabled(false);
        assertDoesNotThrow(actionsConfig::validateActionConfig);

        // When mail is enabled but secret/baseUrl missing, validation logs warning without error
        mailProps.setEnabled(true);
        assertDoesNotThrow(actionsConfig::validateActionConfig);

        MetaRepository metaRepo = mock(MetaRepository.class);
        TokenService tokenService = actionsConfig.tokenService(metaRepo);
        assertNotNull(tokenService);

        EmailTemplate emailTemplate = actionsConfig.emailTemplate(tokenService);
        assertNotNull(emailTemplate);

        PrAnalysisRepository prRepo = mock(PrAnalysisRepository.class);
        MailService mailService = mock(MailService.class);
        ThresholdAlertService alertService = actionsConfig.thresholdAlertService(
                prRepo, mailService, configService, emailTemplate, null, null
        );
        assertNotNull(alertService);
    }
}
