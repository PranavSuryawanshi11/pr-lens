package com.bot.bot.service;

import com.bot.bot.analysis.HeuristicsAnalysisEngine;
import com.bot.bot.analysis.LLMReviewEngine;
import com.bot.bot.analysis.SummaryGenerator;
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
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewOrchestratorExtendedTest {

    private GitHubApiClient gitHubApiClient;
    private UnifiedDiffParser diffParser;
    private HeuristicsAnalysisEngine heuristicsAnalysisEngine;
    private LLMReviewEngine llmReviewEngine;
    private FindingMerger findingMerger;
    private ReviewPublisher reviewPublisher;
    private SummaryGenerator summaryGenerator;
    private AppProperties appProperties;
    private PrAnalysisRepository prAnalysisRepository;
    private Gson gson;
    private ThresholdAlertService thresholdAlertService;
    private ReviewOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        gitHubApiClient = mock(GitHubApiClient.class);
        diffParser = mock(UnifiedDiffParser.class);
        heuristicsAnalysisEngine = mock(HeuristicsAnalysisEngine.class);
        llmReviewEngine = mock(LLMReviewEngine.class);
        findingMerger = mock(FindingMerger.class);
        reviewPublisher = mock(ReviewPublisher.class);
        summaryGenerator = mock(SummaryGenerator.class);
        appProperties = new AppProperties();
        prAnalysisRepository = mock(PrAnalysisRepository.class);
        gson = new Gson();
        thresholdAlertService = mock(ThresholdAlertService.class);

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
                gson,
                thresholdAlertService
        );
    }

    @Test
    @DisplayName("Skips analysis when commitSha has already been analyzed")
    void skipsAnalysisWhenShaAlreadyAnalyzed() {
        PullRequestContext ctx = PullRequestContext.builder()
                .owner("owner")
                .repo("repo")
                .prNumber(10)
                .commitSha("existing-sha")
                .build();

        when(prAnalysisRepository.existsByOwnerRepoPrSha("owner", "repo", 10, "existing-sha"))
                .thenReturn(true);

        StepVerifier.create(orchestrator.processPullRequestContext(ctx))
                .verifyComplete();

        verifyNoInteractions(gitHubApiClient);
        verifyNoInteractions(diffParser);
        verifyNoInteractions(reviewPublisher);
    }

    @Test
    @DisplayName("Executes with heuristics disabled when configured")
    void executesWithHeuristicsDisabled() {
        appProperties.setHeuristicsEnabled(false);
        appProperties.setLlmEnabled(true);

        PullRequestContext ctx = PullRequestContext.builder()
                .owner("owner")
                .repo("repo")
                .prNumber(1)
                .commitSha("sha-1")
                .installationId(123L)
                .build();

        when(prAnalysisRepository.existsByOwnerRepoPrSha(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(false);
        when(gitHubApiClient.fetchDiff("owner", "repo", 1, 123L))
                .thenReturn(Mono.just("sample diff"));
        when(diffParser.parse("sample diff"))
                .thenReturn(List.of(ChangeChunk.builder().filePath("A.java").build()));
        when(llmReviewEngine.analyzeWithLLM(any(), anyList()))
                .thenReturn(Mono.just(Collections.emptyList()));
        when(findingMerger.mergeAndRank(anyList()))
                .thenReturn(Collections.emptyList());
        when(summaryGenerator.computeTier(anyList(), any()))
                .thenReturn(new TriageResult(TriageResult.Tier.GREEN, false, TriageResult.SuggestedAction.REVIEW_AND_MERGE));
        when(summaryGenerator.generateSummary(any(), anyList()))
                .thenReturn("Clean PR");
        when(reviewPublisher.publishReview(anyString(), anyString(), anyInt(), anyList(), anyBoolean(), anyBoolean(), anyLong(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.processPullRequestContext(ctx))
                .verifyComplete();

        verifyNoInteractions(heuristicsAnalysisEngine);
        verify(llmReviewEngine).analyzeWithLLM(any(), anyList());
        verify(reviewPublisher).publishReview(eq("owner"), eq("repo"), eq(1), anyList(), anyBoolean(), anyBoolean(), eq(123L), any());
    }

    @Test
    @DisplayName("Gracefully recovers when LLM analysis fails and continues with heuristics")
    void recoversWhenLlmFails() {
        appProperties.setHeuristicsEnabled(true);
        appProperties.setLlmEnabled(true);

        PullRequestContext ctx = PullRequestContext.builder()
                .owner("org")
                .repo("app")
                .prNumber(5)
                .commitSha("sha-5")
                .installationId(999L)
                .build();

        ChangeChunk chunk = ChangeChunk.builder().filePath("Test.java").build();
        Finding hFinding = Finding.builder().id("h1").severity("INFO").build();

        when(prAnalysisRepository.existsByOwnerRepoPrSha(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(false);
        when(gitHubApiClient.fetchDiff("org", "app", 5, 999L)).thenReturn(Mono.just("diff"));
        when(diffParser.parse("diff")).thenReturn(List.of(chunk));
        when(heuristicsAnalysisEngine.analyze(anyList(), any())).thenReturn(List.of(hFinding));

        // LLM throws error
        when(llmReviewEngine.analyzeWithLLM(any(), anyList()))
                .thenReturn(Mono.error(new RuntimeException("LLM API quota exceeded")));

        when(findingMerger.mergeAndRank(anyList())).thenReturn(List.of(hFinding));
        when(summaryGenerator.computeTier(anyList(), any()))
                .thenReturn(new TriageResult(TriageResult.Tier.GREEN, false, TriageResult.SuggestedAction.REVIEW_AND_MERGE));
        when(summaryGenerator.generateSummary(any(), anyList())).thenReturn("Summary");
        when(reviewPublisher.publishReview(anyString(), anyString(), anyInt(), anyList(), anyBoolean(), anyBoolean(), anyLong(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.processPullRequestContext(ctx))
                .verifyComplete();

        verify(heuristicsAnalysisEngine).analyze(anyList(), any());
        verify(reviewPublisher).publishReview(eq("org"), eq("app"), eq(5), eq(List.of(hFinding)), anyBoolean(), anyBoolean(), eq(999L), any());
    }

    @Test
    @DisplayName("triagePullRequestWithDiff processes supplied diff without fetching from GitHub API")
    void triageWithDiffDirectly() {
        ChangeChunk chunk = ChangeChunk.builder().filePath("Service.java").build();
        when(diffParser.parse(anyString())).thenReturn(List.of(chunk));
        when(heuristicsAnalysisEngine.analyze(anyList(), any())).thenReturn(Collections.emptyList());
        when(llmReviewEngine.analyzeWithLLM(any(), anyList())).thenReturn(Mono.just(Collections.emptyList()));
        when(findingMerger.mergeAndRank(anyList())).thenReturn(Collections.emptyList());
        when(summaryGenerator.computeTier(anyList(), any()))
                .thenReturn(new TriageResult(TriageResult.Tier.GREEN, false, TriageResult.SuggestedAction.REVIEW_AND_MERGE));
        when(summaryGenerator.generateSummary(any(), anyList())).thenReturn("Summary");
        when(reviewPublisher.publishReview(anyString(), anyString(), anyInt(), anyList(), anyBoolean(), anyBoolean(), anyLong(), any()))
                .thenReturn(Mono.empty());

        PrAnalysis saved = new PrAnalysis();
        saved.setOwner("owner");
        saved.setRepo("repo");
        saved.setPrNumber(1);
        when(prAnalysisRepository.findLatest("owner", "repo", 1)).thenReturn(Optional.of(saved));

        StepVerifier.create(orchestrator.triagePullRequestWithDiff("owner", "repo", 1, "Direct PR", "author", "diff content"))
                .expectNext(saved)
                .verifyComplete();

        verify(gitHubApiClient, never()).fetchDiff(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("processPullRequest handles webhook data and logs errors gracefully")
    void processPullRequestHandlesWebhookExceptions() {
        JsonObject webhookData = new JsonObject();
        when(gitHubApiClient.fetchPullRequestContext(webhookData))
                .thenThrow(new IllegalArgumentException("Invalid webhook payload"));

        assertDoesNotThrow(() -> orchestrator.processPullRequest(webhookData));
    }
}
