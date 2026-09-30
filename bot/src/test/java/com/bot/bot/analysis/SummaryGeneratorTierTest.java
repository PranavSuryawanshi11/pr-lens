package com.bot.bot.analysis;

import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import com.bot.bot.domain.TriageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SummaryGeneratorTierTest {

    @InjectMocks
    private SummaryGenerator summaryGenerator;

    @Test
    void green_whenCoherentLowAiHasTestsAndNoSecurity() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Fix login validation");
        ctx.setDescription("Adds input validation to the login form to prevent XSS.");
        ctx.setFilesChanged(List.of(
                "src/LoginForm.java",
                "src/Validator.java",
                "src/test/LoginFormTest.java",
                "src/UserSession.java",
                "src/utils/Sanitizer.java"
        ));

        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("ai-low")
                .category("AI_LIKELIHOOD")
                .severity("INFO")
                .message("AI-LIKELIHOOD: LOW - Clearly human-written.")
                .source("LLM")
                .confidence(0.85)
                .precedenceScore(500)
                .build());
        findings.add(Finding.builder()
                .id("test-obs")
                .category("POSITIVE_OBSERVATION")
                .severity("INFO")
                .message("PR includes test coverage for the change.")
                .source("LLM")
                .confidence(0.90)
                .precedenceScore(300)
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        assertEquals(TriageResult.Tier.GREEN, result.tier());
        assertFalse(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.REVIEW_AND_MERGE, result.suggestedAction());
    }

    @Test
    void yellow_whenMixedOrOffTopicSignals() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Update various files");
        ctx.setDescription("Mixed changes across the codebase.");
        ctx.setFilesChanged(List.of(
                "src/Controller.java",
                "src/Model.java"
        ));

        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("ai-mid")
                .category("AI_LIKELIHOOD")
                .severity("WARNING")
                .message("AI-LIKELIHOOD: MEDIUM - Possible AI assistance.")
                .source("LLM")
                .confidence(0.80)
                .precedenceScore(550)
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        assertEquals(TriageResult.Tier.YELLOW, result.tier());
        assertFalse(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.MANUAL_CHECK, result.suggestedAction());
    }

    @Test
    void red_whenTemplatedSweepingAndNoClearIntent() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Update files");
        ctx.setDescription(null);

        List<String> manyFiles = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            manyFiles.add("src/module" + i + "/File.java");
        }
        ctx.setFilesChanged(manyFiles);

        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("boilerplate-1")
                .category("AI_LIKELIHOOD")
                .severity("INFO")
                .message("boilerplate phrase detected: 'as an ai language model'")
                .source("HEURISTIC")
                .confidence(0.95)
                .precedenceScore(600)
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        assertEquals(TriageResult.Tier.RED, result.tier());
        assertFalse(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.CONSIDER_CLOSING, result.suggestedAction());
    }

    @Test
    void securityFlag_independentOfTier() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Fix login validation");
        ctx.setDescription("Adds input validation to the login form.");
        ctx.setFilesChanged(List.of(
                "src/LoginForm.java",
                "src/test/LoginFormTest.java"
        ));

        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("ai-low")
                .category("AI_LIKELIHOOD")
                .severity("INFO")
                .message("AI-LIKELIHOOD: LOW - Clearly human-written.")
                .source("LLM")
                .confidence(0.85)
                .precedenceScore(500)
                .build());
        findings.add(Finding.builder()
                .id("sec-1")
                .category("SECURITY")
                .severity("CRITICAL")
                .message("Hardcoded API key detected")
                .source("HEURISTIC")
                .confidence(0.99)
                .precedenceScore(900)
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        assertEquals(TriageResult.Tier.GREEN, result.tier());
        assertTrue(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.REVIEW_AND_MERGE, result.suggestedAction());
    }

    @Test
    void red_withSecurityFlag() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle(null);
        ctx.setDescription("");

        List<String> files = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            files.add("src/file" + i + ".txt");
        }
        ctx.setFilesChanged(files);

        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("boilerplate-1")
                .category("AI_LIKELIHOOD")
                .severity("INFO")
                .message("Contains boilerplate template")
                .source("HEURISTIC")
                .confidence(0.9)
                .precedenceScore(600)
                .build());
        findings.add(Finding.builder()
                .id("sec-1")
                .category("SECURITY")
                .severity("HIGH")
                .message("Insecure dependency")
                .source("HEURISTIC")
                .confidence(0.85)
                .precedenceScore(800)
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        assertEquals(TriageResult.Tier.RED, result.tier());
        assertTrue(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.CONSIDER_CLOSING, result.suggestedAction());
    }

    @Test
    void nullFindings_doesNotThrow() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Clean PR");
        ctx.setDescription("A good description.");
        ctx.setFilesChanged(List.of("src/Main.java", "src/test/MainTest.java"));

        TriageResult result = summaryGenerator.computeTier(null, ctx);

        assertNotNull(result);
        assertEquals(TriageResult.Tier.YELLOW, result.tier());
        assertFalse(result.securityFlag());
    }

    @Test
    void nullContext_doesNotThrow() {
        List<Finding> findings = List.of(
                Finding.builder()
                        .id("f1")
                        .category("AI_LIKELIHOOD")
                        .severity("INFO")
                        .message("AI-LIKELIHOOD: LOW - Human-written.")
                        .build()
        );

        TriageResult result = summaryGenerator.computeTier(findings, null);

        assertNotNull(result);
        assertEquals(TriageResult.Tier.YELLOW, result.tier());
        assertFalse(result.securityFlag());
    }

    @Test
    void nullFilesChanged_handledSafely() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("PR with null files");
        ctx.setDescription("Some description");
        ctx.setFilesChanged(null);

        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("ai-low")
                .category("AI_LIKELIHOOD")
                .severity("INFO")
                .message("AI-LIKELIHOOD: LOW - Clearly human-written.")
                .source("LLM")
                .confidence(0.85)
                .precedenceScore(500)
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        assertEquals(TriageResult.Tier.YELLOW, result.tier());
        assertFalse(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.MANUAL_CHECK, result.suggestedAction());
    }

    @Test
    void yellow_whenFindingsEmpty() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Some PR");
        ctx.setDescription("Some description.");
        ctx.setFilesChanged(List.of("src/File.java"));

        TriageResult result = summaryGenerator.computeTier(List.of(), ctx);

        assertEquals(TriageResult.Tier.YELLOW, result.tier());
        assertFalse(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.MANUAL_CHECK, result.suggestedAction());
    }

    @Test
    void red_whenCriticalBugDetected() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Fix calculation");
        ctx.setDescription("Fixes calculating totals");
        ctx.setFilesChanged(List.of("src/Calculator.java", "src/test/CalculatorTest.java"));

        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("ai-low")
                .category("AI_LIKELIHOOD")
                .severity("INFO")
                .message("AI-LIKELIHOOD: LOW - Clearly human-written.")
                .build());
        findings.add(Finding.builder()
                .id("bug-1")
                .category("BUG_DETECTION")
                .severity("CRITICAL")
                .message("Infinite loop detected: inverted decrement step")
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        // A critical bug MUST escalate tier to RED!
        assertEquals(TriageResult.Tier.RED, result.tier());
        assertEquals(TriageResult.SuggestedAction.CONSIDER_CLOSING, result.suggestedAction());
    }

    @Test
    void yellow_whenHighBugDetected() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Fix auth flow");
        ctx.setDescription("Updates user authentication check");
        ctx.setFilesChanged(List.of("src/AuthService.java", "src/test/AuthTest.java"));

        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("ai-low")
                .category("AI_LIKELIHOOD")
                .severity("INFO")
                .message("AI-LIKELIHOOD: LOW - Clearly human-written.")
                .build());
        findings.add(Finding.builder()
                .id("bug-1")
                .category("BUG_DETECTION")
                .severity("HIGH")
                .message("Dangerous String comparison using '==' instead of '.equals()'")
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        // A high severity bug downgrades PR from GREEN to YELLOW (requires manual check)
        assertEquals(TriageResult.Tier.YELLOW, result.tier());
        assertEquals(TriageResult.SuggestedAction.MANUAL_CHECK, result.suggestedAction());
    }

    @Test
    void cleanSmallPr_givesGreen() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Add MathUtils helper");
        ctx.setDescription("Adds standard MathUtils with test coverage");
        ctx.setFilesChanged(List.of("src/MathUtils.java", "src/test/MathUtilsTest.java"));

        // Clean signals: findings list provided, zero bugs, zero security flags, LLM unavailable/fallback (no AI finding)
        List<Finding> cleanFindings = new ArrayList<>();
        cleanFindings.add(Finding.builder()
                .id("test-coverage")
                .category("POSITIVE_OBSERVATION")
                .severity("INFO")
                .message("Includes comprehensive test coverage")
                .build());

        TriageResult result = summaryGenerator.computeTier(cleanFindings, ctx);

        assertEquals(TriageResult.Tier.GREEN, result.tier());
        assertFalse(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.REVIEW_AND_MERGE, result.suggestedAction());
    }

    @Test
    void prWithSecurityFlag_givesRed() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle(null);
        ctx.setDescription("");
        List<String> files = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            files.add("src/service" + i + ".java");
        }
        ctx.setFilesChanged(files);

        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("sec-crit")
                .category("SECURITY")
                .severity("CRITICAL")
                .message("Exposed AWS secret access key")
                .build());
        findings.add(Finding.builder()
                .id("ai-boilerplate")
                .category("AI_LIKELIHOOD")
                .severity("INFO")
                .message("templated boilerplate detected")
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        assertEquals(TriageResult.Tier.RED, result.tier());
        assertTrue(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.CONSIDER_CLOSING, result.suggestedAction());
    }

    @Test
    void prWithSomeRuleViolations_givesYellow() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Update user profile logic");
        ctx.setDescription("Refactors user profile handling");
        ctx.setFilesChanged(List.of("src/UserProfile.java", "src/test/UserProfileTest.java"));

        List<Finding> findings = new ArrayList<>();
        // Rule violation: medium severity bug/rule issue
        findings.add(Finding.builder()
                .id("rule-viol-1")
                .category("BUG_DETECTION")
                .severity("MEDIUM")
                .message("Potential unhandled NullPointerException on optional field")
                .build());

        TriageResult result = summaryGenerator.computeTier(findings, ctx);

        assertEquals(TriageResult.Tier.YELLOW, result.tier());
        assertFalse(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.MANUAL_CHECK, result.suggestedAction());
    }

    @Test
    void llmUnavailable_doesNotCrash_andDoesNotIncorrectlyGiveGreenWhenRulesFail() {
        PullRequestContext ctx = new PullRequestContext();
        ctx.setTitle("Refactor database repo");
        ctx.setDescription("Refactors SQL queries");
        // Rules fail because no test files are included
        ctx.setFilesChanged(List.of("src/UserRepo.java", "src/OrderRepo.java"));

        // LLM is completely unavailable (empty findings list / no AI likelihood finding)
        List<Finding> findings = new ArrayList<>();
        // Also contains a code bug finding
        findings.add(Finding.builder()
                .id("bug-err")
                .category("BUG_DETECTION")
                .severity("HIGH")
                .message("Missing transaction boundary on write operation")
                .build());

        assertDoesNotThrow(() -> {
            TriageResult result = summaryGenerator.computeTier(findings, ctx);
            assertNotEquals(TriageResult.Tier.GREEN, result.tier());
            assertEquals(TriageResult.Tier.YELLOW, result.tier());
            assertEquals(TriageResult.SuggestedAction.MANUAL_CHECK, result.suggestedAction());
        });
    }
}
