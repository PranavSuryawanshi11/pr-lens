package com.bot.bot.github;

import com.bot.bot.config.GitHubProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class GitHubJwtGeneratorTest {

    private GitHubProperties gitHubProperties;
    private GitHubJwtGenerator jwtGenerator;

    @BeforeEach
    void setUp() {
        gitHubProperties = new GitHubProperties();
        jwtGenerator = new GitHubJwtGenerator(gitHubProperties);
    }

    @Test
    @DisplayName("Throws IllegalStateException when appId is missing")
    void throwsWhenAppIdMissing(@TempDir Path tempDir) throws IOException {
        Path keyFile = tempDir.resolve("key.pem");
        Files.writeString(keyFile, "dummy");

        gitHubProperties.setAppId("");
        gitHubProperties.setPrivateKeyPath(keyFile.toString());

        assertThrows(IllegalStateException.class, () -> jwtGenerator.generateAppToken());
    }

    @Test
    @DisplayName("Throws IllegalStateException when private key file does not exist")
    void throwsWhenKeyFileNotFound() {
        gitHubProperties.setAppId("12345");
        gitHubProperties.setPrivateKeyPath("nonexistent/path/to/key.pem");

        assertThrows(IllegalStateException.class, () -> jwtGenerator.generateAppToken());
    }

    @Test
    @DisplayName("Generates valid RS256 JWT using PKCS#8 private key")
    void generatesValidJwtWithPkcs8Key(@TempDir Path tempDir) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        String b64 = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----";

        Path keyFile = tempDir.resolve("pkcs8.pem");
        Files.writeString(keyFile, pem);

        gitHubProperties.setAppId("98765");
        gitHubProperties.setPrivateKeyPath(keyFile.toString());

        String token = jwtGenerator.generateAppToken();
        assertNotNull(token);
        assertFalse(token.isBlank());

        // Parse claims using public key
        Claims claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals("98765", claims.getIssuer());
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
    }

    @Test
    @DisplayName("Caches generated token on consecutive calls within expiry window")
    void cachesGeneratedToken(@TempDir Path tempDir) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        String b64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----";

        Path keyFile = tempDir.resolve("cached_key.pem");
        Files.writeString(keyFile, pem);

        gitHubProperties.setAppId("11111");
        gitHubProperties.setPrivateKeyPath(keyFile.toString());

        String token1 = jwtGenerator.generateAppToken();
        String token2 = jwtGenerator.generateAppToken();

        assertSame(token1, token2, "Token should be cached and return same reference");
    }

    @Test
    @DisplayName("Throws RuntimeException when PEM header is unsupported")
    void throwsOnUnsupportedPemHeader(@TempDir Path tempDir) throws IOException {
        Path keyFile = tempDir.resolve("invalid.pem");
        Files.writeString(keyFile, "-----BEGIN UNKNOWN KEY-----\nabcdef\n-----END UNKNOWN KEY-----");

        gitHubProperties.setAppId("12345");
        gitHubProperties.setPrivateKeyPath(keyFile.toString());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> jwtGenerator.generateAppToken());
        assertTrue(ex.getMessage().contains("Failed to generate JWT token"));
    }

    @Test
    @DisplayName("Tests wrapPkcs1InPkcs8 helper method with small DER bytes")
    void testsWrapPkcs1InPkcs8() throws Exception {
        byte[] dummyPkcs1 = new byte[]{1, 2, 3, 4, 5};
        byte[] pkcs8 = GitHubJwtGenerator.wrapPkcs1InPkcs8(dummyPkcs1);

        assertNotNull(pkcs8);
        assertTrue(pkcs8.length > dummyPkcs1.length);
        assertEquals(0x30, pkcs8[0], "PKCS#8 outer tag should be SEQUENCE (0x30)");
    }
}
