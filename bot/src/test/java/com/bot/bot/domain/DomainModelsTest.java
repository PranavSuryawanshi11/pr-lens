package com.bot.bot.domain;

import com.bot.bot.actions.TokenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DomainModelsTest {

    @AfterEach
    void tearDown() {
        PullRequestContext.clear();
    }

    @Test
    @DisplayName("Finding model builder, getters, setters, and properties")
    void findingModel() {
        Finding finding = Finding.builder()
                .id("f-1")
                .filePath("src/App.java")
                .lineNumber(15)
                .endLine(20)
                .severity("HIGH")
                .category("SECURITY")
                .message("Insecure deserialization")
                .suggestion("Use safe parser")
                .source("HEURISTIC")
                .confidence(0.92)
                .precedenceScore(800)
                .build();

        assertEquals("f-1", finding.getId());
        assertEquals("src/App.java", finding.getFilePath());
        assertEquals(15, finding.getLineNumber());
        assertEquals(20, finding.getEndLine());
        assertEquals("HIGH", finding.getSeverity());
        assertEquals("SECURITY", finding.getCategory());
        assertEquals("Insecure deserialization", finding.getMessage());
        assertEquals("Use safe parser", finding.getSuggestion());
        assertEquals("HEURISTIC", finding.getSource());
        assertEquals(0.92, finding.getConfidence());
        assertEquals(800, finding.getPrecedenceScore());

        // Test no-args constructor
        Finding empty = new Finding();
        empty.setId("f-2");
        assertEquals("f-2", empty.getId());
    }

    @Test
    @DisplayName("ChangeChunk model builder and default lists")
    void changeChunkModel() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("Main.java")
                .fileType("java")
                .startLine(1)
                .endLine(10)
                .changeType("MODIFIED")
                .context("@@ -1,5 +1,5 @@")
                .build();

        assertEquals("Main.java", chunk.getFilePath());
        assertEquals("java", chunk.getFileType());
        assertEquals(1, chunk.getStartLine());
        assertEquals(10, chunk.getEndLine());
        assertEquals("MODIFIED", chunk.getChangeType());
        assertNotNull(chunk.getAddedLines());
        assertNotNull(chunk.getRemovedLines());
        assertTrue(chunk.getAddedLines().isEmpty());
        assertTrue(chunk.getRemovedLines().isEmpty());

        chunk.getAddedLines().add("System.out.println(1);");
        assertEquals(1, chunk.getAddedLines().size());
    }

    @Test
    @DisplayName("ReviewComment model builder and default side")
    void reviewCommentModel() {
        ReviewComment comment = ReviewComment.builder()
                .path("src/Util.java")
                .line(42)
                .startLine(40)
                .body("Consider extracting helper method")
                .build();

        assertEquals("src/Util.java", comment.getPath());
        assertEquals(42, comment.getLine());
        assertEquals(40, comment.getStartLine());
        assertEquals("RIGHT", comment.getSide());
        assertEquals("Consider extracting helper method", comment.getBody());

        comment.setSide("LEFT");
        assertEquals("LEFT", comment.getSide());
    }

    @Test
    @DisplayName("TriageResult record and Tier / SuggestedAction enums")
    void triageResultRecordAndEnums() {
        TriageResult result = new TriageResult(
                TriageResult.Tier.RED,
                true,
                TriageResult.SuggestedAction.CONSIDER_CLOSING
        );

        assertEquals(TriageResult.Tier.RED, result.tier());
        assertTrue(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.CONSIDER_CLOSING, result.suggestedAction());

        assertEquals(3, TriageResult.Tier.values().length);
        assertEquals(TriageResult.Tier.GREEN, TriageResult.Tier.valueOf("GREEN"));
        assertEquals(TriageResult.Tier.YELLOW, TriageResult.Tier.valueOf("YELLOW"));
        assertEquals(TriageResult.Tier.RED, TriageResult.Tier.valueOf("RED"));

        assertEquals(3, TriageResult.SuggestedAction.values().length);
        assertEquals(TriageResult.SuggestedAction.REVIEW_AND_MERGE, TriageResult.SuggestedAction.valueOf("REVIEW_AND_MERGE"));
        assertEquals(TriageResult.SuggestedAction.MANUAL_CHECK, TriageResult.SuggestedAction.valueOf("MANUAL_CHECK"));
        assertEquals(TriageResult.SuggestedAction.CONSIDER_CLOSING, TriageResult.SuggestedAction.valueOf("CONSIDER_CLOSING"));
    }

    @Test
    @DisplayName("PullRequestContext thread-local lifecycle and field mapping")
    void pullRequestContextLifecycle() {
        assertNull(PullRequestContext.getCurrent());

        PullRequestContext ctx = PullRequestContext.builder()
                .owner("facebook")
                .repo("react")
                .prNumber(100)
                .title("Support async components")
                .description("New feature")
                .authorLogin("gaearon")
                .baseRef("main")
                .headRef("async-comp")
                .commitSha("abc1234567")
                .installationId(9999L)
                .filesChanged(List.of("React.js", "ReactFiber.js"))
                .targetUser("facebook")
                .authorAssociation("MEMBER")
                .authorReputation("TRUSTED_MAINTAINER")
                .authorReputationDetail("Core team")
                .repoContext("facebook/react")
                .changeSummaryBefore("Sync rendering only")
                .changeSummaryAfter("Added fiber scheduler")
                .build();

        PullRequestContext.setCurrent(ctx);
        assertSame(ctx, PullRequestContext.getCurrent());

        // Setting null removes from thread local
        PullRequestContext.setCurrent(null);
        assertNull(PullRequestContext.getCurrent());

        PullRequestContext.setCurrent(ctx);
        PullRequestContext.clear();
        assertNull(PullRequestContext.getCurrent());
    }

    @Test
    @DisplayName("TokenException retains error message")
    void tokenException() {
        TokenException ex = new TokenException("Invalid token signature");
        assertEquals("Invalid token signature", ex.getMessage());
        assertTrue(ex instanceof RuntimeException);
    }
}
