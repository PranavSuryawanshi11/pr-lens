package com.bot.bot.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceEntitiesTest {

    @Test
    @DisplayName("UserProfile entity initializes default values")
    void userProfileDefaults() {
        UserProfile profile = new UserProfile();
        profile.setGithubUsername("octocat");
        profile.setNotificationEmail("octocat@github.com");
        profile.setMonitoredRepos("octocat/repo1, octocat/repo2");

        assertNull(profile.getId());
        assertEquals("octocat", profile.getGithubUsername());
        assertEquals("octocat@github.com", profile.getNotificationEmail());
        assertTrue(profile.getAutoTriageEnabled());
        assertNotNull(profile.getCreatedAt());
        assertNotNull(profile.getUpdatedAt());
        assertNull(profile.getLastSyncAt());

        Instant now = Instant.now();
        profile.setId(10L);
        profile.setLastSyncAt(now);
        assertEquals(10L, profile.getId());
        assertEquals(now, profile.getLastSyncAt());
    }

    @Test
    @DisplayName("Meta entity stores key-value pairs")
    void metaEntity() {
        Meta meta = new Meta();
        meta.setKey("used-token:12345");
        meta.setValue("2026-09-30T10:00:00Z");

        assertEquals("used-token:12345", meta.getKey());
        assertEquals("2026-09-30T10:00:00Z", meta.getValue());
    }

    @Test
    @DisplayName("MaintainerConfig entity getters and setters")
    void maintainerConfigGettersAndSetters() {
        MaintainerConfig config = new MaintainerConfig();
        config.setInstallationId("inst-999");
        config.setDigestCron("0 0 12 * * *");
        config.setThresholdTier("YELLOW");
        config.setLabelsEnabled(true);
        config.setEmailEnabled(true);
        config.setActionsEnabled(false);
        config.setMaintainerEmails(List.of("admin@org.com", "lead@org.com"));

        assertEquals("inst-999", config.getInstallationId());
        assertEquals("0 0 12 * * *", config.getDigestCron());
        assertEquals("YELLOW", config.getThresholdTier());
        assertTrue(config.getLabelsEnabled());
        assertTrue(config.getEmailEnabled());
        assertFalse(config.getActionsEnabled());
        assertEquals(2, config.getMaintainerEmails().size());
    }

    @Test
    @DisplayName("MaintainerConfig StringListConverter converts list to JSON and handles null/empty")
    void stringListConverterToDatabaseColumn() {
        MaintainerConfig.StringListConverter converter = new MaintainerConfig.StringListConverter();

        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToDatabaseColumn(new ArrayList<>()));

        String json = converter.convertToDatabaseColumn(List.of("a@b.com", "c@d.com"));
        assertNotNull(json);
        assertTrue(json.contains("a@b.com"));
        assertTrue(json.contains("c@d.com"));
    }

    @Test
    @DisplayName("MaintainerConfig StringListConverter converts JSON to entity attribute and handles null/blank")
    void stringListConverterToEntityAttribute() {
        MaintainerConfig.StringListConverter converter = new MaintainerConfig.StringListConverter();

        assertTrue(converter.convertToEntityAttribute(null).isEmpty());
        assertTrue(converter.convertToEntityAttribute("").isEmpty());
        assertTrue(converter.convertToEntityAttribute("   ").isEmpty());

        List<String> result = converter.convertToEntityAttribute("[\"test1@org.com\",\"test2@org.com\"]");
        assertEquals(2, result.size());
        assertEquals("test1@org.com", result.get(0));
        assertEquals("test2@org.com", result.get(1));
    }

    @Test
    @DisplayName("PrAnalysis entity tracks complete lifecycle and reputation fields")
    void prAnalysisLifecycle() {
        PrAnalysis analysis = new PrAnalysis();
        analysis.setOwner("owner");
        analysis.setRepo("repo");
        analysis.setPrNumber(123);
        analysis.setCommitSha("sha-abc");
        analysis.setTier("RED");
        analysis.setSecurityFlag(true);
        analysis.setSummary("High risk PR");
        analysis.setFindingsJson("[{\"id\":\"sec-1\"}]");
        analysis.setTitle("Security hotfix");
        analysis.setAuthor("contributor");
        analysis.setFilesChangedCount(3);
        analysis.setFilesChangedJson("[\"A.java\",\"B.java\",\"C.java\"]");
        analysis.setStatus("COMPLETED");
        analysis.setInstallationId("12345");
        analysis.setTargetUser("owner");
        analysis.setAuthorReputation("RETURNING_CONTRIBUTOR");
        analysis.setAuthorReputationDetail("3 prior merged PRs");
        analysis.setRepoContext("owner/repo");
        analysis.setChangeSummaryBefore("legacy auth");
        analysis.setChangeSummaryAfter("jwt auth");
        analysis.setActionTaken(false);

        assertEquals("owner", analysis.getOwner());
        assertEquals("repo", analysis.getRepo());
        assertEquals(123, analysis.getPrNumber());
        assertEquals("sha-abc", analysis.getCommitSha());
        assertEquals("RED", analysis.getTier());
        assertTrue(analysis.getSecurityFlag());
        assertEquals("RETURNING_CONTRIBUTOR", analysis.getAuthorReputation());
        assertFalse(analysis.getActionTaken());
        assertEquals(3, analysis.getFilesChangedCount());
    }
}
