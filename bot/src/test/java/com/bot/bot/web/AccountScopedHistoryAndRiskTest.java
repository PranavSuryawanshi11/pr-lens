package com.bot.bot.web;

import com.bot.bot.actions.TokenService;
import com.bot.bot.analysis.SummaryGenerator;
import com.bot.bot.analysis.heuristics.AccountAgeRule;
import com.bot.bot.analysis.heuristics.DiffShapeRule;
import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import com.bot.bot.email.EmailTemplate;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.bot.bot.persistence.UserProfile;
import com.bot.bot.persistence.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.Pageable;
import org.springframework.validation.support.BindingAwareModelMap;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountScopedHistoryAndRiskTest {

    private PrAnalysisRepository repo;
    private TokenService tokenService;
    private UserProfileRepository profileRepo;
    private DashboardController controller;
    private SummaryGenerator summaryGenerator;
    private DiffShapeRule diffShapeRule;
    private AccountAgeRule accountAgeRule;
    private EmailTemplate emailTemplate;

    @BeforeEach
    void setup() {
        repo = mock(PrAnalysisRepository.class);
        tokenService = mock(TokenService.class);
        profileRepo = mock(UserProfileRepository.class);
        controller = new DashboardController(repo, tokenService, profileRepo, null, null);
        summaryGenerator = new SummaryGenerator();
        diffShapeRule = new DiffShapeRule();
        accountAgeRule = new AccountAgeRule();
        emailTemplate = new EmailTemplate(tokenService);

        when(tokenService.buildActionUrl(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn("http://localhost:8080/action?token=test");
    }

    private PrAnalysis createPr(String user, String owner, String repoName, int prNum, String author, String reputation, String before, String after) {
        PrAnalysis a = new PrAnalysis();
        a.setTargetUser(user);
        a.setOwner(owner);
        a.setRepo(repoName);
        a.setPrNumber(prNum);
        a.setTitle("Test PR #" + prNum);
        a.setAuthor(author);
        a.setTier("GREEN");
        a.setAuthorReputation(reputation);
        a.setChangeSummaryBefore(before);
        a.setChangeSummaryAfter(after);
        a.setRepoContext(owner + "/" + repoName);
        a.setSummary("PR summary");
        a.setStatus("NEW");
        return a;
    }

    @Test
    void accountIsolation_SwitchingFromPranavHidesHistoryAndReenteringRestoresIt() {
        PrAnalysis pranavPr = createPr("pranavsuryawanshi11", "pranavsuryawanshi11", "repo-a", 101, "contributor1",
                "FIRST_TIME_CONTRIBUTOR", "old auth check", "new OAuth2 flow");

        when(repo.findByUser(eq("pranavsuryawanshi11"), any(Pageable.class)))
                .thenReturn(List.of(pranavPr));
        when(repo.findAllByUser(eq("pranavsuryawanshi11")))
                .thenReturn(List.of(pranavPr));

        when(repo.findByUser(eq("newuser"), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(repo.findAllByUser(eq("newuser")))
                .thenReturn(Collections.emptyList());

        // 1. Load pranavsuryawanshi11 history
        BindingAwareModelMap modelPranav = new BindingAwareModelMap();
        controller.dashboard(modelPranav, "pranavsuryawanshi11");
        assertFalse((Boolean) modelPranav.get("empty"));
        assertEquals(1, modelPranav.get("totalCount"));
        List<DashboardController.Row> pranavRows = (List<DashboardController.Row>) modelPranav.get("rows");
        assertEquals(1, pranavRows.size());
        assertEquals("old auth check", pranavRows.get(0).changeSummaryBefore());
        assertEquals("new OAuth2 flow", pranavRows.get(0).changeSummaryAfter());
        assertEquals("FIRST_TIME_CONTRIBUTOR", pranavRows.get(0).authorReputation());

        // 2. Switch account to 'newuser' -> pranav's PRs MUST NOT be shown
        BindingAwareModelMap modelNewUser = new BindingAwareModelMap();
        controller.dashboard(modelNewUser, "newuser");
        assertTrue((Boolean) modelNewUser.get("empty"));
        assertEquals(0, modelNewUser.get("totalCount"));
        List<DashboardController.Row> newUserRows = (List<DashboardController.Row>) modelNewUser.get("rows");
        assertTrue(newUserRows.isEmpty());

        // 3. Switch back to 'pranavsuryawanshi11' -> previous history is displayed again
        BindingAwareModelMap modelRestored = new BindingAwareModelMap();
        controller.dashboard(modelRestored, "pranavsuryawanshi11");
        assertFalse((Boolean) modelRestored.get("empty"));
        assertEquals(1, modelRestored.get("totalCount"));
        List<DashboardController.Row> restoredRows = (List<DashboardController.Row>) modelRestored.get("rows");
        assertEquals(1, restoredRows.size());
        assertEquals("pranavsuryawanshi11/repo-a #101", restoredRows.get(0).pr());
    }

    @Test
    void codeAwareSummary_ExtractsWhatWasBeforeAndWhatChanged() {
        ChangeChunk chunk = ChangeChunk.builder()
                .filePath("src/main/AuthService.java")
                .startLine(45)
                .removedLines(List.of(
                        "if (!user.hasRole(\"ADMIN\")) return false;",
                        "return checkLegacySession(token);"
                ))
                .addedLines(List.of(
                        "SecurityContext context = SecurityContextHolder.getContext();",
                        "return jwtValidator.verify(context.getToken());"
                ))
                .build();

        PullRequestContext prContext = PullRequestContext.builder()
                .owner("owner").repo("repo").prNumber(42).title("Update auth")
                .authorLogin("alice").authorAssociation("CONTRIBUTOR")
                .authorReputation("RETURNING_CONTRIBUTOR")
                .authorReputationDetail("Returning contributor with verified PR history")
                .repoContext("owner/repo")
                .build();

        String before = summaryGenerator.extractWhatWasBefore(List.of(chunk), prContext);
        String after = summaryGenerator.extractWhatChanged(List.of(chunk), prContext);

        prContext.setChangeSummaryBefore(before);
        prContext.setChangeSummaryAfter(after);

        assertNotNull(before);
        assertNotNull(after);
        assertTrue(before.contains("AuthService.java") || before.contains("user.hasRole") || before.contains("legacy"),
                "Before summary should reflect removed/previous logic: " + before);
        assertTrue(after.contains("AuthService.java") || after.contains("SecurityContext") || after.contains("jwtValidator"),
                "After summary should reflect added logic: " + after);

        String fullSummary = summaryGenerator.generateSummary(prContext, Collections.emptyList());
        assertTrue(fullSummary.contains("What Was Before"));
        assertTrue(fullSummary.contains("What Changed"));
        assertTrue(fullSummary.contains("RETURNING_CONTRIBUTOR") || fullSummary.contains("Returning contributor"));
    }

    @Test
    void contributorReputation_RulesAndBadges() {
        PullRequestContext maintainerCtx = PullRequestContext.builder()
                .owner("acme").repo("core").prNumber(1).title("Fix")
                .authorLogin("lead-dev")
                .authorReputation("TRUSTED_MAINTAINER")
                .authorReputationDetail("Repository owner/admin")
                .build();

        List<Finding> maintainerFindings = accountAgeRule.analyze(Collections.emptyList(), maintainerCtx);
        assertFalse(maintainerFindings.isEmpty());
        assertEquals("POSITIVE_OBSERVATION", maintainerFindings.get(0).getCategory());
        assertTrue(maintainerFindings.get(0).getMessage().contains("trusted maintainer"));

        PullRequestContext firstTimeCtx = PullRequestContext.builder()
                .owner("acme").repo("core").prNumber(2).title("Add feature")
                .authorLogin("newbie")
                .authorReputation("FIRST_TIME_CONTRIBUTOR")
                .authorReputationDetail("First time opening a pull request in this repository")
                .build();

        List<Finding> firstTimeFindings = accountAgeRule.analyze(Collections.emptyList(), firstTimeCtx);
        assertFalse(firstTimeFindings.isEmpty());
        assertEquals("INFO", firstTimeFindings.get(0).getSeverity());
        assertTrue(firstTimeFindings.get(0).getMessage().toLowerCase().contains("first-time contributor"));
    }

    @Test
    void deepRiskAnalysis_DetectsCriticalPatterns() {
        ChangeChunk cmdExecChunk = ChangeChunk.builder()
                .filePath("src/main/Executor.java")
                .startLine(10)
                .addedLines(List.of("Runtime.getRuntime().exec(cmd);"))
                .build();

        ChangeChunk sqlChunk = ChangeChunk.builder()
                .filePath("src/main/UserDao.java")
                .startLine(25)
                .addedLines(List.of("String query = \"SELECT * FROM users WHERE name = '\" + userInput + \"'\";"))
                .build();

        ChangeChunk disableSecurityChunk = ChangeChunk.builder()
                .filePath("src/main/SecurityConfig.java")
                .startLine(50)
                .addedLines(List.of("http.csrf().disable();"))
                .build();

        List<Finding> cmdFindings = diffShapeRule.analyze(List.of(cmdExecChunk));
        assertTrue(cmdFindings.stream().anyMatch(f -> "CRITICAL".equals(f.getSeverity()) && f.getMessage().contains("command execution")));

        List<Finding> sqlFindings = diffShapeRule.analyze(List.of(sqlChunk));
        assertTrue(sqlFindings.stream().anyMatch(f -> "HIGH".equals(f.getSeverity()) && f.getMessage().contains("SQL injection")));

        List<Finding> secFindings = diffShapeRule.analyze(List.of(disableSecurityChunk));
        assertTrue(secFindings.stream().anyMatch(f -> "HIGH".equals(f.getSeverity()) && f.getMessage().contains("Security controls")));
    }

    @Test
    void emailTemplate_RendersCodeChangeAndAuthorReputation() {
        PrAnalysis analysis = createPr("pranavsuryawanshi11", "acme", "api", 55, "bob",
                "COLLABORATOR", "Basic password auth", "MFA with WebAuthn");
        analysis.setSummary("Migrate to MFA authentication");

        String emailHtml = emailTemplate.renderAlert(analysis);
        assertNotNull(emailHtml);
        assertTrue(emailHtml.contains("What Was Before"), "Email should contain 'What Was Before' section");
        assertTrue(emailHtml.contains("Basic password auth"), "Email should show prior code behavior");
        assertTrue(emailHtml.contains("What Changed in PR"), "Email should contain 'What Changed in PR' section");
        assertTrue(emailHtml.contains("MFA with WebAuthn"), "Email should show new code change");
        assertTrue(emailHtml.contains("COLLABORATOR") || emailHtml.contains("Collaborator"), "Email should display author reputation badge");
        assertTrue(emailHtml.contains("Approve PR"), "Email should provide Approve PR button");
        assertTrue(emailHtml.contains("Reject PR"), "Email should provide Reject PR button");
    }

    @Test
    void returningContributor_WhenAuthorHasPriorPrs_ReputationBecomesReturningContributor() {
        PrAnalysis pr1 = createPr("yash1648", "yash1648", "repo-lens-ai", 202, "external-contributor",
                "FIRST_TIME_CONTRIBUTOR", "old db", "new db");
        when(repo.findByUser(eq("yash1648"), any(Pageable.class)))
                .thenReturn(List.of(pr1));
        when(repo.findAllByUser(eq("yash1648")))
                .thenReturn(List.of(pr1));
        // Author has 1 prior PR to this repository
        when(repo.countPriorPrsByAuthor(eq("yash1648"), eq("repo-lens-ai"), eq("external-contributor"), eq(202)))
                .thenReturn(1L);

        BindingAwareModelMap model = new BindingAwareModelMap();
        controller.dashboard(model, "yash1648");
        List<DashboardController.Row> rows = (List<DashboardController.Row>) model.get("rows");
        assertEquals(1, rows.size());
        assertEquals("RETURNING_CONTRIBUTOR", rows.get(0).authorReputation());
        assertTrue(rows.get(0).authorReputationDetail().contains("Returning Contributor"));
    }
}
