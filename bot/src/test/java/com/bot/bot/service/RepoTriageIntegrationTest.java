package com.bot.bot.service;

import com.bot.bot.actions.TokenService;
import com.bot.bot.analysis.HeuristicsAnalysisEngine;
import com.bot.bot.analysis.LLMReviewEngine;
import com.bot.bot.analysis.SummaryGenerator;
import com.bot.bot.analysis.heuristics.SecretsDetectionRule;
import com.bot.bot.config.AppProperties;
import com.bot.bot.diff.UnifiedDiffParser;
import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import com.bot.bot.domain.TriageResult;
import com.bot.bot.email.ThresholdAlertService;
import com.bot.bot.engine.FindingMerger;
import com.bot.bot.engine.ReviewPublisher;
import com.bot.bot.github.GitHubApiClient;
import com.bot.bot.persistence.MetaRepository;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.bot.bot.web.TriageController;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test suite verifying PR triage behavior,
 * diff parsing, author reputation resolution, heuristics, review orchestration,
 * idempotency, secrets scanning, one-click action tokens, and on-demand triage controller.
 */
class RepoTriageIntegrationTest {

    private static final String OWNER = "PranavSuryawanshi11";
    private static final String REPO_HUB = "Placement-Preparation-Hub";
    private static final String REPO_LENS = "pr-lens";

    private static final String HUB_DIFF =
            "diff --git a/test_note.md b/test_note.md\n" +
            "new file mode 100644\n" +
            "index 0000000..a6f9d30\n" +
            "--- /dev/null\n" +
            "+++ b/test_note.md\n" +
            "@@ -0,0 +1,2 @@\n" +
            "+# Placement Preparation Hub - Verification Note\n" +
            "+This file was added to verify automated PR triage analysis.\n";

    private static final String LENS_DIFF =
            "diff --git a/bot/src/test/java/com/sprint/sprint/domain/TriageResultTest.java b/bot/src/test/java/com/sprint/sprint/domain/TriageResultTest.java\n" +
            "new file mode 100644\n" +
            "index 0000000..80a45e7\n" +
            "--- /dev/null\n" +
            "+++ b/bot/src/test/java/com/sprint/sprint/domain/TriageResultTest.java\n" +
            "@@ -0,0 +1,20 @@\n" +
            "+package com.sprint.sprint.domain;\n" +
            "+\n" +
            "+import org.junit.jupiter.api.Test;\n" +
            "+import static org.junit.jupiter.api.Assertions.*;\n" +
            "+\n" +
            "+class TriageResultTest {\n" +
            "+    @Test\n" +
            "+    void testRecord() {\n" +
            "+        assertNotNull(new Object());\n" +
            "+    }\n" +
            "+}\n";

    private GitHubApiClient gitHubApiClient;
    private UnifiedDiffParser diffParser;
    private HeuristicsAnalysisEngine heuristicsAnalysisEngine;
    private LLMReviewEngine llmReviewEngine;
    private FindingMerger findingMerger;
    private ReviewPublisher reviewPublisher;
    private SummaryGenerator summaryGenerator;
    private AppProperties appProperties;
    private PrAnalysisRepository prAnalysisRepository;
    private ThresholdAlertService thresholdAlertService;
    private TokenService tokenService;
    private ReviewOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        gitHubApiClient = mock(GitHubApiClient.class);
        diffParser = new UnifiedDiffParser();
        heuristicsAnalysisEngine = mock(HeuristicsAnalysisEngine.class);
        llmReviewEngine = mock(LLMReviewEngine.class);
        findingMerger = new FindingMerger();
        reviewPublisher = mock(ReviewPublisher.class);
        summaryGenerator = mock(SummaryGenerator.class);
        appProperties = new AppProperties();
        appProperties.setHeuristicsEnabled(true);
        appProperties.setLlmEnabled(false);
        appProperties.setActionSecret("12345678901234567890123456789012");
        appProperties.setBaseUrl("http://localhost:8080");
        prAnalysisRepository = mock(PrAnalysisRepository.class);
        thresholdAlertService = mock(ThresholdAlertService.class);
        MetaRepository metaRepository = mock(MetaRepository.class);
        tokenService = new TokenService(appProperties, metaRepository);

        orchestrator = new ReviewOrchestrator(
                gitHubApiClient,
                diffParser,
                heuristicsAnalysisEngine,
                llmReviewEngine,
                findingMerger,
                reviewPublisher,
                summaryGenerator,
                appProperties,
                prAnalysisRepository,
                new Gson(),
                thresholdAlertService
        );

        when(summaryGenerator.generateSummary(any(), anyList())).thenReturn("Summary for PR");
        when(summaryGenerator.computeTier(anyList(), any())).thenReturn(
                new TriageResult(TriageResult.Tier.YELLOW, false, TriageResult.SuggestedAction.MANUAL_CHECK));
        when(reviewPublisher.publishReview(any(), any(), anyInt(), anyList(), anyBoolean(), anyBoolean(), anyLong(), any()))
                .thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Diff parsing correctly extracts changes from markdown notes PR")
    void testDiffParsingForMarkdownNotes() {
        List<ChangeChunk> chunks = diffParser.parse(HUB_DIFF);
        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("test_note.md", chunks.get(0).getFilePath());
        assertEquals(2, chunks.get(0).getAddedLines().size());
        assertTrue(chunks.get(0).getAddedLines().stream().anyMatch(l -> l.contains("Placement Preparation Hub")));
    }

    @Test
    @DisplayName("Diff parsing correctly extracts test classes from Java test PR")
    void testDiffParsingForJavaTest() {
        List<ChangeChunk> chunks = diffParser.parse(LENS_DIFF);
        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getFilePath().contains("TriageResultTest.java"));
        assertTrue(chunks.get(0).getAddedLines().stream().anyMatch(l -> l.contains("class TriageResultTest")));
    }

    @Test
    @DisplayName("Author reputation correctly resolves repo owner as TRUSTED_MAINTAINER on own repo")
    void testAuthorReputationForOwner() {
        GitHubApiClient.AuthorReputationInfo rep1 =
                GitHubApiClient.resolveAuthorReputation("PranavSuryawanshi11", "PranavSuryawanshi11", "OWNER");
        assertEquals("TRUSTED_MAINTAINER", rep1.reputation());
        assertEquals("OWNER", rep1.association());

        GitHubApiClient.AuthorReputationInfo rep2 =
                GitHubApiClient.resolveAuthorReputation("pranavsuryawanshi11", "PRANAVSURYAWANSHI11", "NONE");
        assertEquals("TRUSTED_MAINTAINER", rep2.reputation());
    }

    @Test
    @DisplayName("Returning contributor reputation is detected when prior PRs exist in repo")
    void testReturningContributorReputation() {
        PullRequestContext ctx = PullRequestContext.builder()
                .owner(OWNER)
                .repo(REPO_HUB)
                .prNumber(5)
                .authorLogin("external-contributor")
                .authorReputation("FIRST_TIME_CONTRIBUTOR")
                .authorAssociation("NONE")
                .commitSha("sha-12345")
                .installationId(0)
                .build();

        when(prAnalysisRepository.countPriorPrsByAuthor(OWNER, REPO_HUB, "external-contributor", 5)).thenReturn(3L);
        when(prAnalysisRepository.existsByOwnerRepoPrSha(any(), any(), anyInt(), any())).thenReturn(false);
        when(gitHubApiClient.fetchDiff(any(), any(), anyInt(), anyLong())).thenReturn(Mono.just(HUB_DIFF));
        when(heuristicsAnalysisEngine.analyze(any(), any())).thenReturn(List.of());

        orchestrator.processPullRequestContext(ctx).block();

        assertEquals("RETURNING_CONTRIBUTOR", ctx.getAuthorReputation());
        assertEquals("CONTRIBUTOR", ctx.getAuthorAssociation());
    }

    @Test
    @DisplayName("Full triage workflow for repository PR 1 persists and alerts")
    void testTriageRepositoryPr1() {
        PullRequestContext ctx = PullRequestContext.builder()
                .owner(OWNER)
                .repo(REPO_HUB)
                .prNumber(1)
                .title("feat: automated PR triage pipeline verification")
                .authorLogin(OWNER)
                .commitSha("d22c06a4e9c2a8b1e5a8824dd117715be75ec959")
                .installationId(0)
                .build();

        when(prAnalysisRepository.existsByOwnerRepoPrSha(OWNER, REPO_HUB, 1, "d22c06a4e9c2a8b1e5a8824dd117715be75ec959"))
                .thenReturn(false);
        when(gitHubApiClient.fetchPullRequestDetails(OWNER, REPO_HUB, 1)).thenReturn(Mono.just(ctx));
        when(gitHubApiClient.fetchDiff(OWNER, REPO_HUB, 1, 0)).thenReturn(Mono.just(HUB_DIFF));
        when(heuristicsAnalysisEngine.analyze(any(), any())).thenReturn(List.of());

        PrAnalysis savedAnalysis = new PrAnalysis();
        savedAnalysis.setOwner(OWNER);
        savedAnalysis.setRepo(REPO_HUB);
        savedAnalysis.setPrNumber(1);
        savedAnalysis.setTier("YELLOW");
        savedAnalysis.setSecurityFlag(false);
        savedAnalysis.setSummary("Summary for PR");

        when(prAnalysisRepository.findLatest(OWNER, REPO_HUB, 1)).thenReturn(Optional.of(savedAnalysis));

        PrAnalysis result = orchestrator.triagePullRequest(OWNER, REPO_HUB, 1).block();

        assertNotNull(result);
        assertEquals(OWNER, result.getOwner());
        assertEquals(REPO_HUB, result.getRepo());
        assertEquals(1, result.getPrNumber());
        assertEquals("YELLOW", result.getTier());

        verify(prAnalysisRepository).save(any(PrAnalysis.class));
        verify(thresholdAlertService).maybeAlert(any());
    }

    @Test
    @DisplayName("Full triage workflow for repository PR 4 parses test file and succeeds")
    void testTriageRepositoryPr4() {
        PullRequestContext ctx = PullRequestContext.builder()
                .owner(OWNER)
                .repo(REPO_LENS)
                .prNumber(4)
                .title("test: add TriageResult record tests")
                .authorLogin(OWNER)
                .commitSha("469c85221de3af2080a46b138a1c95c96ea9250c")
                .installationId(0)
                .build();

        when(prAnalysisRepository.existsByOwnerRepoPrSha(OWNER, REPO_LENS, 4, "469c85221de3af2080a46b138a1c95c96ea9250c"))
                .thenReturn(false);
        when(gitHubApiClient.fetchPullRequestDetails(OWNER, REPO_LENS, 4)).thenReturn(Mono.just(ctx));
        when(gitHubApiClient.fetchDiff(OWNER, REPO_LENS, 4, 0)).thenReturn(Mono.just(LENS_DIFF));
        when(heuristicsAnalysisEngine.analyze(any(), any())).thenReturn(List.of());

        PrAnalysis savedAnalysis = new PrAnalysis();
        savedAnalysis.setOwner(OWNER);
        savedAnalysis.setRepo(REPO_LENS);
        savedAnalysis.setPrNumber(4);
        savedAnalysis.setTier("YELLOW");

        when(prAnalysisRepository.findLatest(OWNER, REPO_LENS, 4)).thenReturn(Optional.of(savedAnalysis));

        PrAnalysis result = orchestrator.triagePullRequest(OWNER, REPO_LENS, 4).block();

        assertNotNull(result);
        assertEquals(REPO_LENS, result.getRepo());
        assertEquals(4, result.getPrNumber());
        verify(prAnalysisRepository).save(any(PrAnalysis.class));
    }

    @Test
    @DisplayName("Idempotency: Re-analyzing unchanged commit SHA skips duplicate processing")
    void testIdempotencyOnUnchangedSha() {
        PullRequestContext ctx = PullRequestContext.builder()
                .owner(OWNER)
                .repo(REPO_HUB)
                .prNumber(1)
                .commitSha("same-commit-sha")
                .installationId(0)
                .build();

        when(prAnalysisRepository.existsByOwnerRepoPrSha(OWNER, REPO_HUB, 1, "same-commit-sha")).thenReturn(true);

        orchestrator.processPullRequestContext(ctx).block();

        verify(gitHubApiClient, never()).fetchDiff(any(), any(), anyInt(), anyLong());
        verify(prAnalysisRepository, never()).save(any());
    }

    @Test
    @DisplayName("Secrets scan over repo diffs confirms zero leaked credentials")
    void testSecretsScanCleanOnRepoDiffs() {
        SecretsDetectionRule rule = new SecretsDetectionRule();
        List<ChangeChunk> chunks1 = diffParser.parse(HUB_DIFF);
        List<ChangeChunk> chunks2 = diffParser.parse(LENS_DIFF);

        List<Finding> findings1 = rule.analyze(chunks1);
        List<Finding> findings2 = rule.analyze(chunks2);

        assertTrue(findings1.isEmpty(), "test_note.md should contain no secrets");
        assertTrue(findings2.isEmpty(), "TriageResultTest.java should contain no secrets");
    }

    @Test
    @DisplayName("One-click action tokens sign and verify with correct owner/repo")
    void testActionTokensForRepos() {
        String approveUrl = tokenService.buildActionUrl(OWNER, REPO_HUB, 1, "approve");
        assertNotNull(approveUrl);
        assertTrue(approveUrl.contains("http://localhost:8080/action?token="));
        assertTrue(approveUrl.contains("&do=approve"));

        String token = approveUrl.substring(approveUrl.indexOf("token=") + 6, approveUrl.indexOf("&do="));
        TokenService.TokenPayload payload = tokenService.verify(token);

        assertEquals(OWNER, payload.owner());
        assertEquals(REPO_HUB, payload.repo());
        assertEquals(1, payload.prNumber());
        assertEquals("approve", payload.action());
    }

    @Test
    @DisplayName("TriageController REST API produces success response for on-demand PR triage")
    void testTriageControllerForOnDemandPr() {
        PrAnalysis analysis = new PrAnalysis();
        analysis.setOwner(OWNER);
        analysis.setRepo(REPO_HUB);
        analysis.setPrNumber(1);
        analysis.setTier("YELLOW");
        analysis.setSecurityFlag(false);
        analysis.setSummary("Verified PR 1");
        analysis.setActionTaken(false);

        when(gitHubApiClient.fetchPullRequestDetails(OWNER, REPO_HUB, 1)).thenReturn(Mono.just(
                PullRequestContext.builder().owner(OWNER).repo(REPO_HUB).prNumber(1).commitSha("c1").build()));
        when(prAnalysisRepository.findLatest(OWNER, REPO_HUB, 1)).thenReturn(Optional.of(analysis));
        when(prAnalysisRepository.existsByOwnerRepoPrSha(any(), any(), anyInt(), any())).thenReturn(false);
        when(gitHubApiClient.fetchDiff(any(), any(), anyInt(), anyLong())).thenReturn(Mono.just(HUB_DIFF));
        when(heuristicsAnalysisEngine.analyze(any(), any())).thenReturn(List.of());

        TriageController controller = new TriageController(orchestrator, thresholdAlertService, tokenService);
        ResponseEntity<?> response = controller.triagePrGet(
                "https://github.com/PranavSuryawanshi11/Placement-Preparation-Hub/pull/1", null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof TriageController.TriageResponse);
        TriageController.TriageResponse body = (TriageController.TriageResponse) response.getBody();
        assertEquals("SUCCESS", body.status());
        assertEquals(OWNER, body.owner());
        assertEquals(REPO_HUB, body.repo());
        assertEquals(1, body.prNumber());
        assertEquals("YELLOW", body.tier());
        assertFalse(body.securityFlag());
    }
}
