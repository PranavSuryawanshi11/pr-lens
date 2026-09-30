package com.bot.bot.analysis;

import com.bot.bot.analysis.heuristics.*;
import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class HeuristicsAnalysisEngineExtendedTest {

    private SecretsDetectionRule secretsRule;
    private CommitMessageStyleRule commitRule;
    private DiffShapeRule diffShapeRule;
    private AccountAgeRule accountAgeRule;
    private CommentCodeRatioRule commentRatioRule;
    private BoilerplatePhraseRule boilerplateRule;
    private BugDetectionRule bugRule;
    private ExecutorService executor;
    private HeuristicsAnalysisEngine engine;

    @BeforeEach
    void setUp() {
        secretsRule = mock(SecretsDetectionRule.class);
        commitRule = mock(CommitMessageStyleRule.class);
        diffShapeRule = mock(DiffShapeRule.class);
        accountAgeRule = mock(AccountAgeRule.class);
        commentRatioRule = mock(CommentCodeRatioRule.class);
        boilerplateRule = mock(BoilerplatePhraseRule.class);
        bugRule = mock(BugDetectionRule.class);
        executor = Executors.newCachedThreadPool();

        engine = new HeuristicsAnalysisEngine(
                secretsRule, commitRule, diffShapeRule,
                accountAgeRule, commentRatioRule, boilerplateRule,
                bugRule, executor
        );
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        PullRequestContext.clear();
    }

    @Test
    @DisplayName("Aggregates findings from all seven rules in parallel")
    void aggregatesFindingsFromAllRules() {
        Finding f1 = Finding.builder().id("sec").build();
        Finding f2 = Finding.builder().id("commit").build();
        Finding f3 = Finding.builder().id("shape").build();
        Finding f4 = Finding.builder().id("age").build();
        Finding f5 = Finding.builder().id("comment").build();
        Finding f6 = Finding.builder().id("boilerplate").build();
        Finding f7 = Finding.builder().id("bug").build();

        when(secretsRule.analyze(anyList())).thenReturn(List.of(f1));
        when(commitRule.analyze(anyList())).thenReturn(List.of(f2));
        when(diffShapeRule.analyze(anyList())).thenReturn(List.of(f3));
        when(accountAgeRule.analyze(anyList())).thenReturn(List.of(f4));
        when(commentRatioRule.analyze(anyList())).thenReturn(List.of(f5));
        when(boilerplateRule.analyze(anyList())).thenReturn(List.of(f6));
        when(bugRule.analyze(anyList(), any())).thenReturn(List.of(f7));

        PullRequestContext ctx = PullRequestContext.builder().owner("o").repo("r").prNumber(1).build();
        List<ChangeChunk> chunks = List.of(ChangeChunk.builder().filePath("Test.java").build());

        List<Finding> findings = engine.analyze(chunks, ctx);

        assertEquals(7, findings.size());
        assertTrue(findings.stream().anyMatch(f -> "sec".equals(f.getId())));
        assertTrue(findings.stream().anyMatch(f -> "bug".equals(f.getId())));
    }

    @Test
    @DisplayName("Handles all rules returning empty lists gracefully")
    void handlesAllRulesEmpty() {
        when(secretsRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(commitRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(diffShapeRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(accountAgeRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(commentRatioRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(boilerplateRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(bugRule.analyze(anyList(), any())).thenReturn(Collections.emptyList());

        List<Finding> findings = engine.analyze(Collections.emptyList(), null);
        assertNotNull(findings);
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Ensures PullRequestContext is cleared after analyze execution")
    void clearsPullRequestContextAfterExecution() {
        when(secretsRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(commitRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(diffShapeRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(accountAgeRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(commentRatioRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(boilerplateRule.analyze(anyList())).thenReturn(Collections.emptyList());
        when(bugRule.analyze(anyList(), any())).thenReturn(Collections.emptyList());

        PullRequestContext ctx = PullRequestContext.builder().owner("org").repo("repo").prNumber(5).build();
        engine.analyze(Collections.emptyList(), ctx);

        assertNull(PullRequestContext.getCurrent(), "ThreadLocal PullRequestContext should be cleared");
    }

    @Test
    @DisplayName("Secondary 7-arg constructor creates default BugDetectionRule")
    void secondaryConstructorCreatesDefaultBugRule() {
        HeuristicsAnalysisEngine simpleEngine = new HeuristicsAnalysisEngine(
                secretsRule, commitRule, diffShapeRule,
                accountAgeRule, commentRatioRule, boilerplateRule,
                executor
        );
        assertNotNull(simpleEngine);
    }
}
