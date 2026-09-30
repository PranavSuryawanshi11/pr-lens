package com.bot.bot.analysis;

import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import com.bot.bot.domain.TriageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryGeneratorExtendedTest {

    private SummaryGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SummaryGenerator();
    }

    @Test
    @DisplayName("generateSummary generates structured summary with all 12 sections")
    void generatesCompleteStructuredSummary() {
        PullRequestContext ctx = PullRequestContext.builder()
                .owner("facebook")
                .repo("react")
                .prNumber(123)
                .title("feat: Add Concurrent Mode Scheduler")
                .description("Improves responsiveness")
                .authorLogin("dan_abramov")
                .authorReputation("TRUSTED_MAINTAINER")
                .authorReputationDetail("Core maintainer")
                .repoContext("facebook/react")
                .changeSummaryBefore("Legacy sync rendering")
                .changeSummaryAfter("Added concurrent scheduler")
                .filesChanged(List.of("Scheduler.js"))
                .build();

        Finding finding = Finding.builder()
                .id("f1")
                .severity("INFO")
                .category("QUALITY")
                .message("Well structured code")
                .confidence(0.9)
                .build();

        String summary = generator.generateSummary(ctx, List.of(finding));

        assertTrue(summary.contains("Title: feat: Add Concurrent Mode Scheduler"));
        assertTrue(summary.contains("Feature Summary:"));
        assertTrue(summary.contains("What Was Before: Legacy sync rendering"));
        assertTrue(summary.contains("What Changed: Added concurrent scheduler"));
        assertTrue(summary.contains("Contributor Context: @dan_abramov"));
        assertTrue(summary.contains("Repository Context: facebook/react"));
        assertTrue(summary.contains("Scope:"));
        assertTrue(summary.contains("Risk:"));
        assertTrue(summary.contains("AI-likelihood:"));
        assertTrue(summary.contains("Quality signal:"));
        assertTrue(summary.contains("Recommendation:"));
    }

    @Test
    @DisplayName("computeTier assigns RED and CONSIDER_CLOSING when CRITICAL BUG_DETECTION finding exists")
    void computeTierRedForCriticalBugFinding() {
        Finding bugFinding = Finding.builder()
                .id("bug-1")
                .severity("CRITICAL")
                .category("BUG_DETECTION")
                .message("Resource leak causing crash")
                .confidence(0.95)
                .build();

        PullRequestContext ctx = PullRequestContext.builder().owner("o").repo("r").prNumber(1).build();
        TriageResult result = generator.computeTier(List.of(bugFinding), ctx);

        assertEquals(TriageResult.Tier.RED, result.tier());
        assertEquals(TriageResult.SuggestedAction.CONSIDER_CLOSING, result.suggestedAction());
    }

    @Test
    @DisplayName("computeTier flags securityFlag=true when SECURITY finding exists")
    void computeTierSetsSecurityFlag() {
        Finding secFinding = Finding.builder()
                .id("sec-1")
                .severity("HIGH")
                .category("SECURITY")
                .message("AWS Key leaked")
                .confidence(0.95)
                .build();

        PullRequestContext ctx = PullRequestContext.builder().owner("o").repo("r").prNumber(1).build();
        TriageResult result = generator.computeTier(List.of(secFinding), ctx);

        assertTrue(result.securityFlag());
    }

    @Test
    @DisplayName("computeTier assigns GREEN and REVIEW_AND_MERGE when coherent, tests present, and no bugs")
    void computeTierGreenForCleanPrWithTests() {
        Finding info = Finding.builder()
                .id("info-1")
                .severity("INFO")
                .category("POSITIVE_OBSERVATION")
                .message("Unit tests added with 100% coverage")
                .confidence(0.8)
                .build();

        PullRequestContext ctx = PullRequestContext.builder()
                .owner("o")
                .repo("r")
                .prNumber(1)
                .description("Clean refactor with full unit tests")
                .filesChanged(List.of("src/Main.java", "src/test/MainTest.java"))
                .build();

        TriageResult result = generator.computeTier(List.of(info), ctx);

        assertEquals(TriageResult.Tier.GREEN, result.tier());
        assertFalse(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.REVIEW_AND_MERGE, result.suggestedAction());
    }

    @Test
    @DisplayName("extractWhatChanged identifies added classes, methods, and Spring endpoints")
    void extractWhatChangedIdentifiesComponents() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("src/UserController.java")
                .addedLines(List.of(
                        "public class UserController {",
                        "    @GetMapping(\"/api/users\")",
                        "    public List<User> getUsers() {",
                        "        return userService.findAll();",
                        "    }",
                        "}"
                ))
                .build();

        PullRequestContext ctx = PullRequestContext.builder().build();
        String whatChanged = generator.extractWhatChanged(List.of(chunk), ctx);

        assertNotNull(whatChanged);
        assertFalse(whatChanged.isBlank());
        assertTrue(whatChanged.contains("UserController") || whatChanged.contains("/api/users") || whatChanged.contains("getUsers"));
    }

    @Test
    @DisplayName("extractWhatWasBefore summarizes removed code and baseline state")
    void extractWhatWasBeforeSummarizesRemovedLines() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("Legacy.java")
                .removedLines(List.of(
                        "public void oldMethod() {",
                        "    deprecatedCall();",
                        "}"
                ))
                .build();

        PullRequestContext ctx = PullRequestContext.builder().build();
        String whatWasBefore = generator.extractWhatWasBefore(List.of(chunk), ctx);

        assertNotNull(whatWasBefore);
        assertFalse(whatWasBefore.isBlank());
    }

    @Test
    @DisplayName("formatReputationLabel returns human-friendly badges")
    void formatReputationLabelReturnsFriendlyNames() {
        assertEquals("Trusted Maintainer", generator.formatReputationLabel("TRUSTED_MAINTAINER"));
        assertEquals("Collaborator", generator.formatReputationLabel("COLLABORATOR"));
        assertEquals("Returning Contributor", generator.formatReputationLabel("RETURNING_CONTRIBUTOR"));
        assertEquals("First-time Contributor", generator.formatReputationLabel("FIRST_TIME_CONTRIBUTOR"));
        assertEquals("First-time Contributor", generator.formatReputationLabel("UNKNOWN"));
    }
}
