package com.bot.bot.analysis;

import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SummaryGeneratorTest {

    @InjectMocks
    private SummaryGenerator summaryGenerator;

    @Test
    void testGenerateSummaryWithAILikelihood() {
        // Setup test data
        PullRequestContext prContext = new PullRequestContext();
        prContext.setTitle("Fix login bug");
        prContext.setDescription("This fixes a bug in the login process.");
        prContext.setFilesChanged(Collections.singletonList("src/login.js"));

        // Create findings with AI-likelihood
        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("ai-low")
                .filePath("PR_DESCRIPTION")
                .lineNumber(0)
                .severity("INFO")
                .category("AI_LIKELIHOOD")
                .message("AI-LIKELIHOOD: LOW - Clearly human-written, no signs of AI assistance.")
                .source("LLM")
                .confidence(0.85)
                .precedenceScore(500)
                .build());

        // Generate summary
        String summary = summaryGenerator.generateSummary(prContext, findings);

        // Verify summary contains expected information
        assertTrue(summary.contains("Title: Fix login bug"));
        assertTrue(summary.contains("Purpose: This fixes a bug in the login process."));
        assertTrue(summary.contains("AI-likelihood: LOW"));
        assertTrue(summary.contains("Clearly human-written, no signs of AI assistance."));
    }

    @Test
    void testGenerateSummaryWithHighRisk() {
        // Setup test data
        PullRequestContext prContext = new PullRequestContext();
        prContext.setTitle("Update dependencies");
        prContext.setDescription("Update all dependencies to latest versions.");
        prContext.setFilesChanged(Collections.singletonList("package.json"));

        // Create findings with high risk
        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("risk-critical")
                .filePath("package.json")
                .lineNumber(1)
                .severity("CRITICAL")
                .category("CODE_REVIEW")
                .message("Critical security vulnerability detected.")
                .source("LLM")
                .confidence(0.9)
                .precedenceScore(700)
                .build());

        // Generate summary
        String summary = summaryGenerator.generateSummary(prContext, findings);

        // Verify summary contains expected information
        assertTrue(summary.contains("Title: Update dependencies"));
        assertTrue(summary.contains("Risk: CRITICAL"));
        assertTrue(summary.contains("Needs human review"));
    }

    @Test
    void testGenerateSummaryWithMediumAILikelihood() {
        // Setup test data
        PullRequestContext prContext = new PullRequestContext();
        prContext.setTitle("Add new feature");
        prContext.setDescription("This adds a new feature to the application.");
        prContext.setFilesChanged(Collections.singletonList("src/features"));

        // Create findings with medium AI-likelihood
        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder()
                .id("ai-medium")
                .filePath("PR_DESCRIPTION")
                .lineNumber(0)
                .severity("WARNING")
                .category("AI_LIKELIHOOD")
                .message("AI-LIKELIHOOD: MEDIUM - Possible AI assistance, but not definitive.")
                .source("LLM")
                .confidence(0.8)
                .precedenceScore(550)
                .build());

        // Generate summary
        String summary = summaryGenerator.generateSummary(prContext, findings);

        // Verify summary contains expected information
        assertTrue(summary.contains("Title: Add new feature"));
        assertTrue(summary.contains("AI-likelihood: MEDIUM"));
        assertTrue(summary.contains("Possible AI assistance, but not definitive"));
        assertTrue(summary.contains("Needs human review"));
    }

    @Test
    void testSubjectAdditionNarrative_PlacementPreparation_ComputerNetworks() {
        com.bot.bot.domain.ChangeChunk chunk = com.bot.bot.domain.ChangeChunk.builder()
                .filePath("subjects/computer-networks.md")
                .changeType("ADDED")
                .addedLines(List.of(
                        "# Computer Networks",
                        "Comprehensive overview of Computer Networking concepts for campus placement preparation.",
                        "## 7 layer OSI model",
                        "The Open Systems Interconnection model defines 7 distinct networking abstraction layers:",
                        "### Physical Layer",
                        "### Data Link Layer"
                ))
                .build();

        PullRequestContext prContext = PullRequestContext.builder()
                .owner("PranavSuryawanshi11")
                .repo("Placement-Preparation-Hub")
                .prNumber(10)
                .title("Add Computer Networks subject with OSI model")
                .build();

        String narrative = summaryGenerator.extractSemanticFeatureNarrative(List.of(chunk), prContext);
        assertEquals("Added one more subject 'Computer Networks' in subjects/ and in Computer Networks added '7 layer OSI model'", narrative);

        String whatChanged = summaryGenerator.extractWhatChanged(List.of(chunk), prContext);
        assertTrue(whatChanged.startsWith("Added one more subject 'Computer Networks' in subjects/ and in Computer Networks added '7 layer OSI model'"));
        assertTrue(whatChanged.contains("6 line(s) of new implementation across 1 file(s)"));

        prContext.setChangeSummaryAfter(whatChanged);
        String fullSummary = summaryGenerator.generateSummary(prContext, Collections.emptyList());
        assertTrue(fullSummary.contains("Feature Summary: Added one more subject 'Computer Networks' in subjects/ and in Computer Networks added '7 layer OSI model'"));
        assertTrue(fullSummary.contains("What Changed: " + whatChanged));
    }

    @Test
    void testSubjectUpdateNarrative_ExistingSubjectWithNewTopic() {
        com.bot.bot.domain.ChangeChunk chunk = com.bot.bot.domain.ChangeChunk.builder()
                .filePath("subjects/computer-networks.md")
                .changeType("MODIFIED")
                .addedLines(List.of(
                        "## 7 layer OSI model",
                        "Added detailed explanation of 7 layer OSI model."
                ))
                .build();

        PullRequestContext prContext = PullRequestContext.builder()
                .owner("PranavSuryawanshi11")
                .repo("Placement-Preparation-Hub")
                .prNumber(11)
                .title("Add 7 layer OSI model notes")
                .build();

        String narrative = summaryGenerator.extractSemanticFeatureNarrative(List.of(chunk), prContext);
        assertEquals("In subject 'Computer Networks' (subjects/), added '7 layer OSI model'", narrative);
    }

    @Test
    void testCodeFeatureAdditionNarrative_AuthService() {
        com.bot.bot.domain.ChangeChunk chunk = com.bot.bot.domain.ChangeChunk.builder()
                .filePath("src/main/java/com/service/AuthService.java")
                .changeType("ADDED")
                .addedLines(List.of(
                        "package com.service;",
                        "public class AuthService {",
                        "    public boolean verifyUser(String token, User user) {",
                        "        return token != null && token.equals(\"VALID\");",
                        "    }",
                        "}"
                ))
                .build();

        PullRequestContext prContext = PullRequestContext.builder()
                .owner("owner")
                .repo("repo")
                .prNumber(1)
                .title("Add AuthService")
                .build();

        String narrative = summaryGenerator.extractSemanticFeatureNarrative(List.of(chunk), prContext);
        assertEquals("Created new file 'AuthService.java' implementing class 'AuthService' with method(s) 'verifyUser()'", narrative);

        String whatChanged = summaryGenerator.extractWhatChanged(List.of(chunk), prContext);
        assertTrue(whatChanged.contains("Created new file 'AuthService.java' implementing class 'AuthService' with method(s) 'verifyUser()'"));
        assertTrue(whatChanged.contains("AuthService.java"));
    }
}