package com.bot.bot.analysis.heuristics;

import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommentCodeRatioRuleTest {

    private CommentCodeRatioRule rule;

    @BeforeEach
    void setUp() {
        rule = new CommentCodeRatioRule();
    }

    @Test
    @DisplayName("Returns empty findings when chunks list is empty")
    void returnsEmptyWhenChunksEmpty() {
        List<Finding> findings = rule.analyze(Collections.emptyList());
        assertNotNull(findings);
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Returns empty findings when chunk has no added lines")
    void returnsEmptyWhenNoAddedLines() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("Empty.java")
                .addedLines(Collections.emptyList())
                .removedLines(List.of("int x = 1;"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Returns empty findings when comment ratio is exactly 50% or below")
    void returnsEmptyWhenRatioUnderOrEqualTo50Percent() {
        // 5 comment lines, 5 code lines -> ratio 0.5 (not > 0.5)
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("Balanced.java")
                .addedLines(List.of(
                        "// comment 1",
                        "// comment 2",
                        "// comment 3",
                        "// comment 4",
                        "// comment 5",
                        "int a = 1;",
                        "int b = 2;",
                        "int c = 3;",
                        "int d = 4;",
                        "int e = 5;"
                ))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Flags high comment-to-code ratio when comments exceed 50%")
    void flagsWhenRatioExceeds50Percent() {
        // 6 comment lines, 4 code lines -> ratio 0.6 > 0.5
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("HighComments.java")
                .addedLines(List.of(
                        "// comment 1",
                        "// comment 2",
                        "// comment 3",
                        "// comment 4",
                        "// comment 5",
                        "// comment 6",
                        "int a = 1;",
                        "int b = 2;",
                        "int c = 3;",
                        "int d = 4;"
                ))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertEquals("comment-code-ratio-high", f.getId());
        assertEquals("CODE_CHANGES", f.getFilePath());
        assertEquals(0, f.getLineNumber());
        assertEquals("WARNING", f.getSeverity());
        assertEquals("AI_LIKELIHOOD", f.getCategory());
        assertEquals(0.75, f.getConfidence());
        assertEquals(550, f.getPrecedenceScore());
    }

    @Test
    @DisplayName("Counts block comments starting with '*' with leading whitespace")
    void countsBlockCommentAsterisks() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("Javadoc.java")
                .addedLines(List.of(
                        "  * Detailed method description line 1",
                        "  * Detailed method description line 2",
                        "  * Detailed method description line 3",
                        "  * Detailed method description line 4",
                        "void run() {}"
                ))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
    }

    @Test
    @DisplayName("Counts double-slash comments with leading whitespace")
    void countsDoubleSlashCommentsWithWhitespace() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("Indented.java")
                .addedLines(List.of(
                        "    // indented comment 1",
                        "    // indented comment 2",
                        "    // indented comment 3",
                        "    int x = 1;"
                ))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
    }

    @Test
    @DisplayName("Aggregates ratio across multiple chunks")
    void aggregatesAcrossMultipleChunks() {
        // Chunk 1: 100% comments (4 lines)
        ChangeChunk chunk1 = ChangeChunk.builder()
                .filePath("File1.java")
                .addedLines(List.of("// 1", "// 2", "// 3", "// 4"))
                .build();

        // Chunk 2: 0% comments (16 code lines)
        // Total: 4 comments / 20 lines = 20% -> should NOT flag
        ChangeChunk chunk2 = ChangeChunk.builder()
                .filePath("File2.java")
                .addedLines(List.of(
                        "l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8",
                        "l9", "l10", "l11", "l12", "l13", "l14", "l15", "l16"
                ))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk1, chunk2));
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Returns correct rule name")
    void returnsCorrectRuleName() {
        assertEquals("CommentCodeRatioRule", rule.getName());
    }
}
