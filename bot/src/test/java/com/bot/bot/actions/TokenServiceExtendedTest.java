package com.bot.bot.actions;

import com.bot.bot.config.AppProperties;
import com.bot.bot.persistence.Meta;
import com.bot.bot.persistence.MetaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TokenServiceExtendedTest {

    private AppProperties appProperties;
    private MetaRepository metaRepository;
    private Map<String, Meta> metaStore;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setActionSecret("super-secret-key-12345");
        appProperties.setBaseUrl("https://triage.example.com");

        metaRepository = mock(MetaRepository.class);
        metaStore = new HashMap<>();

        when(metaRepository.save(any(Meta.class))).thenAnswer(inv -> {
            Meta m = inv.getArgument(0);
            metaStore.put(m.getKey(), m);
            return m;
        });

        when(metaRepository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(metaStore.get(inv.getArgument(0)))
        );

        tokenService = new TokenService(appProperties, metaRepository);
    }

    @Test
    @DisplayName("buildActionUrl returns empty string when actionSecret is null or blank")
    void buildActionUrlEmptyWhenActionSecretBlank() {
        AppProperties noSecretProps = new AppProperties();
        noSecretProps.setActionSecret(null);
        TokenService noSecretService = new TokenService(noSecretProps, metaRepository);
        assertEquals("", noSecretService.buildActionUrl("o", "r", 1, "approve"));

        AppProperties emptySecretProps = new AppProperties();
        emptySecretProps.setActionSecret("");
        TokenService emptySecretService = new TokenService(emptySecretProps, metaRepository);
        assertEquals("", emptySecretService.buildActionUrl("o", "r", 1, "approve"));
    }

    @Test
    @DisplayName("buildActionUrl defaults to http://localhost:8080 when baseUrl is not configured")
    void buildActionUrlDefaultsLocalhost() {
        AppProperties defaultBaseProps = new AppProperties();
        defaultBaseProps.setActionSecret("secret");
        defaultBaseProps.setBaseUrl(null);
        TokenService localService = new TokenService(defaultBaseProps, metaRepository);
        String url = localService.buildActionUrl("o", "r", 1, "approve");

        assertTrue(url.startsWith("http://localhost:8080/action?token="));
        assertTrue(url.contains("&do=approve"));
    }

    @Test
    @DisplayName("buildActionUrl correctly formats URL with configured baseUrl")
    void buildActionUrlFormatsCorrectly() {
        String url = tokenService.buildActionUrl("owner", "repo", 55, "close");

        assertTrue(url.startsWith("https://triage.example.com/action?token="));
        assertTrue(url.contains("&do=close"));
    }

    @Test
    @DisplayName("buildActionUrl strips trailing slash from baseUrl")
    void buildActionUrlStripsTrailingSlash() {
        AppProperties trailingSlashProps = new AppProperties();
        trailingSlashProps.setActionSecret("secret");
        trailingSlashProps.setBaseUrl("https://my-ngrok.ngrok-free.dev/");
        TokenService service = new TokenService(trailingSlashProps, metaRepository);
        String url = service.buildActionUrl("owner", "repo", 55, "approve");

        assertTrue(url.startsWith("https://my-ngrok.ngrok-free.dev/action?token="));
        org.junit.jupiter.api.Assertions.assertFalse(url.contains("//action"));
    }

    @Test
    @DisplayName("Verifies and parses all token actions correctly")
    void verifiesAllSupportedActions() {
        String approveToken = tokenService.generate("o", "r", 10, "approve");
        TokenService.TokenPayload p1 = tokenService.verify(approveToken);
        assertEquals("approve", p1.action());

        String changesToken = tokenService.generate("o", "r", 20, "request-changes");
        TokenService.TokenPayload p2 = tokenService.verify(changesToken);
        assertEquals("request-changes", p2.action());

        String closeToken = tokenService.generate("o", "r", 30, "close");
        TokenService.TokenPayload p3 = tokenService.verify(closeToken);
        assertEquals("close", p3.action());
    }

    @Test
    @DisplayName("Throws TokenException when signature is invalid (bad signature)")
    void throwsWhenSignatureInvalid() {
        String token = tokenService.generate("o", "r", 1, "approve");
        String[] parts = token.split("\\.");
        String invalidSig = parts[0] + ".WRONGSIGNATURE";

        TokenException ex = assertThrows(TokenException.class, () -> tokenService.verify(invalidSig));
        assertTrue(ex.getMessage().contains("bad signature"));
    }

    @Test
    @DisplayName("Throws TokenException when token is verified twice (replay attack prevention)")
    void throwsWhenTokenReused() {
        String token = tokenService.generate("org", "repo", 99, "approve");
        tokenService.verify(token);

        TokenException ex = assertThrows(TokenException.class, () -> tokenService.verify(token));
        assertTrue(ex.getMessage().contains("already used"));
    }

    @Test
    @DisplayName("Throws TokenException on malformed payload or missing dots")
    void throwsOnMalformedPayload() {
        assertThrows(TokenException.class, () -> tokenService.verify("notoken"));
    }

    @Test
    @DisplayName("Purges expired used tokens safely")
    void purgesExpiredUsedTokens() {
        Meta expired = new Meta();
        expired.setKey("used:expired");
        expired.setValue("1000"); // very old timestamp

        Meta active = new Meta();
        active.setKey("used:active");
        active.setValue(String.valueOf(System.currentTimeMillis() / 1000 + 3600));

        when(metaRepository.findByKeyStartingWith("used:")).thenReturn(List.of(expired, active));

        assertDoesNotThrow(() -> tokenService.purgeExpiredUsedTokens());
        verify(metaRepository).delete(expired);
        verify(metaRepository, never()).delete(active);
    }
}
