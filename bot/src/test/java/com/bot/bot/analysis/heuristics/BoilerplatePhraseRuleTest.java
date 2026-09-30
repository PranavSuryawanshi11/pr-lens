package com.bot.bot.analysis.heuristics;

import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoilerplatePhraseRuleTest {

    private BoilerplatePhraseRule rule;

    @BeforeEach
    void setUp() {
        rule = new BoilerplatePhraseRule();
    }

    @Test
    @DisplayName("Returns empty findings when chunks list is empty")
    void returnsEmptyFindingsWhenChunksEmpty() {
        List<Finding> findings = rule.analyze(Collections.emptyList());
        assertNotNull(findings);
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Returns empty findings when code contains no boilerplate phrases")
    void returnsEmptyWhenNoBoilerplatePresent() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("src/main/User.java")
                .startLine(10)
                .addedLines(List.of("public String getName() {", "    return this.name;", "}"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Detects 'This ensures optimal' in added line")
    void detectsThisEnsuresOptimal() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("src/service/Optimizer.java")
                .startLine(42)
                .addedLines(List.of("// This ensures optimal memory allocation during execution"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertEquals("boilerplate-phrase", f.getId());
        assertEquals("src/service/Optimizer.java", f.getFilePath());
        assertEquals(42, f.getLineNumber());
        assertEquals("WARNING", f.getSeverity());
        assertEquals("AI_LIKELIHOOD", f.getCategory());
        assertEquals(0.75, f.getConfidence());
        assertEquals(550, f.getPrecedenceScore());
    }

    @Test
    @DisplayName("Detects 'Here's an improved version' in added line")
    void detectsHeresAnImprovedVersion() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("src/util/MathUtil.java")
                .startLine(15)
                .addedLines(List.of("/* Here's an improved version of the sort function */"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
        assertEquals("boilerplate-phrase", findings.get(0).getId());
    }

    @Test
    @DisplayName("Detects case-insensitive variants like 'THIS FIXES' and 'this resolves'")
    void detectsCaseInsensitivePhrases() {
        ChangeChunk chunk1 = ChangeChunk.builder()
                .filePath("File1.java")
                .startLine(1)
                .addedLines(List.of("// THIS FIXES bug #123"))
                .build();

        ChangeChunk chunk2 = ChangeChunk.builder()
                .filePath("File2.java")
                .startLine(1)
                .addedLines(List.of("// this resolves race condition"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk1, chunk2));
        assertEquals(2, findings.size());
    }

    @Test
    @DisplayName("Detects 'This PR introduces' and 'This PR refactors'")
    void detectsPrBoilerplatePhrases() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("App.java")
                .startLine(5)
                .addedLines(List.of("// This PR introduces a clean architecture"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
    }

    @Test
    @DisplayName("Ignores boilerplate phrases in removed lines")
    void ignoresRemovedLinesWithBoilerplate() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("OldFile.java")
                .startLine(1)
                .addedLines(List.of("// standard refactored method"))
                .removedLines(List.of("// This fixes issue in legacy code"))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Reports at most one finding per chunk even if multiple lines match")
    void reportsOnlyOncePerChunk() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("Double.java")
                .startLine(10)
                .addedLines(List.of(
                        "// This fixes the connection timeout",
                        "// This resolves the memory leak as well",
                        "// This ensures optimal performance"
                ))
                .build();

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertEquals(1, findings.size());
    }

    @Test
    @DisplayName("Returns correct rule name")
    void returnsCorrectRuleName() {
        assertEquals("BoilerplatePhraseRule", rule.getName());
    }
}
