package com.bot.bot.analysis.heuristics;

import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountAgeRuleTest {

    private AccountAgeRule rule;

    @BeforeEach
    void setUp() {
        rule = new AccountAgeRule();
        PullRequestContext.clear();
    }

    @AfterEach
    void tearDown() {
        PullRequestContext.clear();
    }

    @Test
    @DisplayName("Returns empty findings when PullRequestContext is null")
    void returnsEmptyFindingsWhenContextIsNull() {
        List<Finding> findings = rule.analyze(Collections.emptyList(), null);
        assertNotNull(findings);
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Returns empty findings when no context is set in ThreadLocal")
    void returnsEmptyFindingsWhenThreadLocalIsNull() {
        List<Finding> findings = rule.analyze(Collections.emptyList());
        assertNotNull(findings);
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Identifies TRUSTED_MAINTAINER with fast-track observation")
    void identifiesTrustedMaintainer() {
        PullRequestContext ctx = PullRequestContext.builder()
                .authorLogin("alice")
                .authorReputation("TRUSTED_MAINTAINER")
                .authorReputationDetail("Repository owner")
                .build();

        List<Finding> findings = rule.analyze(Collections.emptyList(), ctx);
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertEquals("author-trusted-maintainer", f.getId());
        assertEquals("INFO", f.getSeverity());
        assertEquals("POSITIVE_OBSERVATION", f.getCategory());
        assertTrue(f.getMessage().contains("@alice"));
        assertTrue(f.getMessage().contains("Repository owner"));
        assertTrue(f.getMessage().contains("Fast-track"));
        assertEquals(0.95, f.getConfidence());
        assertEquals(350, f.getPrecedenceScore());
    }

    @Test
    @DisplayName("Handles case-insensitivity for TRUSTED_MAINTAINER")
    void handlesCaseInsensitiveTrustedMaintainer() {
        PullRequestContext ctx = PullRequestContext.builder()
                .authorLogin("bob")
                .authorReputation("trusted_maintainer")
                .build();

        List<Finding> findings = rule.analyze(Collections.emptyList(), ctx);
        assertEquals(1, findings.size());
        assertEquals("author-trusted-maintainer", findings.get(0).getId());
    }

    @Test
    @DisplayName("Identifies COLLABORATOR with positive observation")
    void identifiesCollaborator() {
        PullRequestContext ctx = PullRequestContext.builder()
                .authorLogin("charlie")
                .authorReputation("COLLABORATOR")
                .authorReputationDetail("Triage team member")
                .build();

        List<Finding> findings = rule.analyze(Collections.emptyList(), ctx);
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertEquals("author-collaborator", f.getId());
        assertEquals("INFO", f.getSeverity());
        assertEquals("POSITIVE_OBSERVATION", f.getCategory());
        assertTrue(f.getMessage().contains("@charlie"));
        assertTrue(f.getMessage().contains("Triage team member"));
        assertEquals(0.90, f.getConfidence());
        assertEquals(380, f.getPrecedenceScore());
    }

    @Test
    @DisplayName("Identifies RETURNING_CONTRIBUTOR with contributor context")
    void identifiesReturningContributor() {
        PullRequestContext ctx = PullRequestContext.builder()
                .authorLogin("dave")
                .authorReputation("RETURNING_CONTRIBUTOR")
                .authorReputationDetail("5 prior merged PRs")
                .build();

        List<Finding> findings = rule.analyze(Collections.emptyList(), ctx);
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertEquals("author-returning-contributor", f.getId());
        assertEquals("INFO", f.getSeverity());
        assertEquals("CONTRIBUTOR_CONTEXT", f.getCategory());
        assertTrue(f.getMessage().contains("@dave"));
        assertTrue(f.getMessage().contains("5 prior merged PRs"));
        assertEquals(0.85, f.getConfidence());
        assertEquals(420, f.getPrecedenceScore());
    }

    @Test
    @DisplayName("Identifies FIRST_TIME_CONTRIBUTOR with default detail when detail is blank")
    void identifiesFirstTimeContributorWithBlankDetail() {
        PullRequestContext ctx = PullRequestContext.builder()
                .authorLogin("eve")
                .authorReputation("FIRST_TIME_CONTRIBUTOR")
                .authorReputationDetail("")
                .build();

        List<Finding> findings = rule.analyze(Collections.emptyList(), ctx);
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertEquals("author-first-time-contributor", f.getId());
        assertEquals("INFO", f.getSeverity());
        assertEquals("CONTRIBUTOR_CONTEXT", f.getCategory());
        assertTrue(f.getMessage().contains("@eve"));
        assertTrue(f.getMessage().contains("no prior merged PRs found"));
        assertNotNull(f.getSuggestion());
        assertEquals(0.90, f.getConfidence());
        assertEquals(480, f.getPrecedenceScore());
    }

    @Test
    @DisplayName("Identifies FIRST_TIME_CONTRIBUTOR with custom detail message")
    void identifiesFirstTimeContributorWithCustomDetail() {
        PullRequestContext ctx = PullRequestContext.builder()
                .authorLogin("frank")
                .authorReputation("FIRST_TIME_CONTRIBUTOR")
                .authorReputationDetail("Account created yesterday")
                .build();

        List<Finding> findings = rule.analyze(Collections.emptyList(), ctx);
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertTrue(f.getMessage().contains("Account created yesterday"));
    }

    @Test
    @DisplayName("Falls back to unknown author and FIRST_TIME_CONTRIBUTOR when fields are null")
    void handlesNullAuthorAndNullReputation() {
        PullRequestContext ctx = PullRequestContext.builder()
                .authorLogin(null)
                .authorReputation(null)
                .build();

        List<Finding> findings = rule.analyze(Collections.emptyList(), ctx);
        assertEquals(1, findings.size());

        Finding f = findings.get(0);
        assertEquals("author-first-time-contributor", f.getId());
        assertTrue(f.getMessage().contains("@unknown"));
    }

    @Test
    @DisplayName("Works via ThreadLocal PullRequestContext")
    void worksViaThreadLocalContext() {
        PullRequestContext ctx = PullRequestContext.builder()
                .authorLogin("grace")
                .authorReputation("TRUSTED_MAINTAINER")
                .build();
        PullRequestContext.setCurrent(ctx);

        List<Finding> findings = rule.analyze(Collections.emptyList());
        assertEquals(1, findings.size());
        assertEquals("author-trusted-maintainer", findings.get(0).getId());
    }

    @Test
    @DisplayName("Returns correct rule name")
    void returnsCorrectRuleName() {
        assertEquals("AccountAgeRule", rule.getName());
    }
}
