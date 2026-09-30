package com.bot.bot.engine;

import com.bot.bot.domain.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FindingMergerExtendedTest {

    private FindingMerger merger;

    @BeforeEach
    void setUp() {
        merger = new FindingMerger();
    }

    @Test
    @DisplayName("Returns empty list when input is null")
    void handlesNullList() {
        List<Finding> result = merger.mergeAndRank(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Returns empty list when input is empty list")
    void handlesEmptyList() {
        List<Finding> result = merger.mergeAndRank(Collections.emptyList());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Filters out null elements in the list safely")
    void filtersOutNullElements() {
        Finding f = Finding.builder()
                .id("f1")
                .filePath("A.java")
                .lineNumber(1)
                .severity("INFO")
                .confidence(0.5)
                .precedenceScore(100)
                .build();

        List<Finding> list = new ArrayList<>();
        list.add(null);
        list.add(f);
        list.add(null);

        List<Finding> result = merger.mergeAndRank(list);
        assertEquals(1, result.size());
        assertEquals("f1", result.get(0).getId());
    }

    @Test
    @DisplayName("Keeps existing finding when duplicate has equal or lower confidence")
    void keepsFirstWhenEqualConfidence() {
        Finding f1 = Finding.builder()
                .id("first")
                .filePath("A.java")
                .lineNumber(10)
                .category("BUG")
                .confidence(0.8)
                .precedenceScore(100)
                .build();

        Finding f2 = Finding.builder()
                .id("second")
                .filePath("A.java")
                .lineNumber(10)
                .category("BUG")
                .confidence(0.8)
                .precedenceScore(100)
                .build();

        List<Finding> result = merger.mergeAndRank(List.of(f1, f2));
        assertEquals(1, result.size());
        assertEquals("first", result.get(0).getId());
    }

    @Test
    @DisplayName("Replaces existing finding when duplicate has strictly higher confidence")
    void replacesWhenHigherConfidence() {
        Finding f1 = Finding.builder()
                .id("first")
                .filePath("A.java")
                .lineNumber(10)
                .category("BUG")
                .confidence(0.7)
                .precedenceScore(100)
                .build();

        Finding f2 = Finding.builder()
                .id("second")
                .filePath("A.java")
                .lineNumber(10)
                .category("BUG")
                .confidence(0.95)
                .precedenceScore(100)
                .build();

        List<Finding> result = merger.mergeAndRank(List.of(f1, f2));
        assertEquals(1, result.size());
        assertEquals("second", result.get(0).getId());
    }

    @Test
    @DisplayName("Differentiates findings on different lines of the same file")
    void differentiatesByLineNumber() {
        Finding f1 = Finding.builder()
                .id("line10")
                .filePath("A.java")
                .lineNumber(10)
                .category("BUG")
                .confidence(0.8)
                .build();

        Finding f2 = Finding.builder()
                .id("line20")
                .filePath("A.java")
                .lineNumber(20)
                .category("BUG")
                .confidence(0.8)
                .build();

        List<Finding> result = merger.mergeAndRank(List.of(f1, f2));
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Differentiates findings on the same line with different categories")
    void differentiatesByCategory() {
        Finding f1 = Finding.builder()
                .id("bug")
                .filePath("A.java")
                .lineNumber(10)
                .category("BUG")
                .confidence(0.8)
                .build();

        Finding f2 = Finding.builder()
                .id("security")
                .filePath("A.java")
                .lineNumber(10)
                .category("SECURITY")
                .confidence(0.8)
                .build();

        List<Finding> result = merger.mergeAndRank(List.of(f1, f2));
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Handles null filePath and null category without throwing NPE")
    void handlesNullFieldsInKeyGeneration() {
        Finding f = Finding.builder()
                .id("null-fields")
                .filePath(null)
                .lineNumber(0)
                .category(null)
                .confidence(0.5)
                .build();

        assertDoesNotThrow(() -> {
            List<Finding> result = merger.mergeAndRank(List.of(f));
            assertEquals(1, result.size());
        });
    }

    @Test
    @DisplayName("Correctly orders by precedenceScore descending")
    void ordersByPrecedenceScoreDescending() {
        Finding low = Finding.builder().id("low").filePath("A.java").lineNumber(1).precedenceScore(100).confidence(0.5).build();
        Finding high = Finding.builder().id("high").filePath("B.java").lineNumber(2).precedenceScore(500).confidence(0.5).build();
        Finding mid = Finding.builder().id("mid").filePath("C.java").lineNumber(3).precedenceScore(300).confidence(0.5).build();

        List<Finding> result = merger.mergeAndRank(List.of(low, high, mid));
        assertEquals("high", result.get(0).getId());
        assertEquals("mid", result.get(1).getId());
        assertEquals("low", result.get(2).getId());
    }

    @Test
    @DisplayName("Breaks precedence tie by severity descending: CRITICAL > HIGH > MEDIUM > LOW > INFO")
    void breaksTieBySeverity() {
        Finding info = Finding.builder().id("info").filePath("1.java").precedenceScore(100).severity("INFO").confidence(0.5).build();
        Finding crit = Finding.builder().id("crit").filePath("2.java").precedenceScore(100).severity("CRITICAL").confidence(0.5).build();
        Finding med = Finding.builder().id("med").filePath("3.java").precedenceScore(100).severity("MEDIUM").confidence(0.5).build();
        Finding high = Finding.builder().id("high").filePath("4.java").precedenceScore(100).severity("HIGH").confidence(0.5).build();
        Finding low = Finding.builder().id("low").filePath("5.java").precedenceScore(100).severity("LOW").confidence(0.5).build();

        List<Finding> result = merger.mergeAndRank(List.of(info, low, med, crit, high));
        assertEquals("crit", result.get(0).getId());
        assertEquals("high", result.get(1).getId());
        assertEquals("med", result.get(2).getId());
        assertEquals("low", result.get(3).getId());
        assertEquals("info", result.get(4).getId());
    }

    @Test
    @DisplayName("Breaks tie by confidence descending when precedence and severity are equal")
    void breaksTieByConfidence() {
        Finding c50 = Finding.builder().id("c50").precedenceScore(100).severity("HIGH").filePath("A.java").lineNumber(1).confidence(0.50).build();
        Finding c95 = Finding.builder().id("c95").precedenceScore(100).severity("HIGH").filePath("B.java").lineNumber(1).confidence(0.95).build();
        Finding c75 = Finding.builder().id("c75").precedenceScore(100).severity("HIGH").filePath("C.java").lineNumber(1).confidence(0.75).build();

        List<Finding> result = merger.mergeAndRank(List.of(c50, c95, c75));
        assertEquals("c95", result.get(0).getId());
        assertEquals("c75", result.get(1).getId());
        assertEquals("c50", result.get(2).getId());
    }
}
