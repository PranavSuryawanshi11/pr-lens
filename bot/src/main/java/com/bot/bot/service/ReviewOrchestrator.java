package com.bot.bot.service;


import com.google.gson.JsonObject;
import com.bot.bot.analysis.HeuristicsAnalysisEngine;
import com.bot.bot.analysis.LLMReviewEngine;
import com.bot.bot.analysis.SummaryGenerator;
import com.bot.bot.config.AppProperties;
import com.bot.bot.diff.UnifiedDiffParser;
import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import com.bot.bot.domain.TriageResult;
import com.bot.bot.engine.FindingMerger;
import com.bot.bot.engine.ReviewPublisher;
import com.bot.bot.github.GitHubApiClient;
import com.bot.bot.email.ThresholdAlertService;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewOrchestrator {
    private final GitHubApiClient gitHubApiClient;
    private final UnifiedDiffParser diffParser;
    private final HeuristicsAnalysisEngine heuristicsAnalysisEngine;
    private final LLMReviewEngine llmReviewEngine;
    private final FindingMerger findingMerger;
    private final ReviewPublisher reviewPublisher;
    private final SummaryGenerator summaryGenerator;
    private final AppProperties appProperties;
    private final PrAnalysisRepository prAnalysisRepository;
    private final Gson gson;
    private final ThresholdAlertService thresholdAlertService;

    /**
     * Process pull request asynchronously.
     * Fetches PR data, analyzes changes, and publishes review.
     */
    @Async("reviewTaskExecutor")
    public void processPullRequest(JsonObject webhookData) {
        log.info("Starting PR review process");

        try {
            PullRequestContext prContext = gitHubApiClient.fetchPullRequestContext(webhookData);
            processPullRequestContext(prContext).block();
        } catch (Exception e) {
            log.error("Error processing PR review", e);
        }
    }

    /**
     * Triage any PR on demand by owner, repository name, and PR number.
     * Works on any GitHub account via GitHub API without pre-configuring a GitHub App.
     */
    public Mono<PrAnalysis> triagePullRequest(String owner, String repo, int prNumber) {
        log.info("Starting on-demand PR triage for {}/{}/PR#{}", owner, repo, prNumber);
        return gitHubApiClient.fetchPullRequestDetails(owner, repo, prNumber)
                .flatMap(prContext -> processPullRequestContext(prContext)
                        .then(Mono.fromCallable(() ->
                                prAnalysisRepository.findLatest(owner, repo, prNumber).orElse(null))));
    }

    /**
     * Triage PR directly with supplied diff (e.g. from browser extension session on private repos).
     * Works without needing any Personal Access Tokens or GitHub Developer Settings!
     */
    public Mono<PrAnalysis> triagePullRequestWithDiff(String owner, String repo, int prNumber,
                                                     String title, String author, String diff) {
        long priorPrs = (author != null && !author.isBlank())
                ? prAnalysisRepository.countPriorPrsByAuthor(owner, repo, author, prNumber)
                : 0;
        String assoc = priorPrs > 0 ? "CONTRIBUTOR" : "NONE";
        var rep = GitHubApiClient.resolveAuthorReputation(owner, author, assoc);
        if (priorPrs > 0 && !"TRUSTED_MAINTAINER".equalsIgnoreCase(rep.reputation())) {
            rep = new GitHubApiClient.AuthorReputationInfo("RETURNING_CONTRIBUTOR",
                    "Returning Contributor (" + (priorPrs + 1) + " contributions to this repository)", "CONTRIBUTOR");
        }
        PullRequestContext prContext = PullRequestContext.builder()
                .owner(owner)
                .repo(repo)
                .prNumber(prNumber)
                .title(title != null && !title.isBlank() ? title : ("PR #" + prNumber))
                .description("")
                .authorLogin(author != null && !author.isBlank() ? author : "unknown")
                .baseRef("main")
                .headRef("feature")
                .commitSha("direct-" + System.currentTimeMillis())
                .installationId(0)
                .targetUser(owner)
                .authorAssociation(rep.association())
                .authorReputation(rep.reputation())
                .authorReputationDetail(rep.detail())
                .repoContext(owner + "/" + repo)
                .build();

        List<ChangeChunk> chunks = diffParser.parse(diff != null ? diff : "");
        prContext.setFilesChanged(chunks.stream()
                .map(ChangeChunk::getFilePath)
                .distinct()
                .collect(Collectors.toList()));

        return analyzeDiff(prContext, chunks)
                .then(Mono.fromCallable(() ->
                        prAnalysisRepository.findLatest(owner, repo, prNumber).orElse(null)));
    }

    /**
     * Process PR context: fetch diff, analyze, and publish review.
     */
    public Mono<Void> processPullRequestContext(PullRequestContext prContext) {
        long installationId = prContext.getInstallationId();
        String commitSha = prContext.getCommitSha();

        if (prContext.getAuthorLogin() != null && !prContext.getAuthorLogin().isBlank()) {
            long priorPrs = prAnalysisRepository.countPriorPrsByAuthor(
                    prContext.getOwner(), prContext.getRepo(), prContext.getAuthorLogin(), prContext.getPrNumber());
            if (priorPrs > 0 && !"TRUSTED_MAINTAINER".equalsIgnoreCase(prContext.getAuthorReputation())) {
                prContext.setAuthorReputation("RETURNING_CONTRIBUTOR");
                prContext.setAuthorReputationDetail("Returning Contributor (" + (priorPrs + 1) + " contributions to this repository)");
                prContext.setAuthorAssociation("CONTRIBUTOR");
            }
        }

        // T7: Skip analysis if this commit SHA was already analyzed
        if (prAnalysisRepository.existsByOwnerRepoPrSha(
                prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber(), commitSha)) {
            log.info("No changes since {} for {}/{}/PR#{} — skipping analysis",
                    commitSha, prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber());
            return Mono.empty();
        }

        log.info("Processing PR {}/{}/#{} (installation {})",
                prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber(), installationId);

        return gitHubApiClient.fetchDiff(prContext.getOwner(), prContext.getRepo(),
                        prContext.getPrNumber(), installationId)
                .map(diff -> {
                    List<ChangeChunk> chunks = diffParser.parse(diff);
                    prContext.setFilesChanged(chunks.stream()
                            .map(ChangeChunk::getFilePath)
                            .distinct()
                            .collect(Collectors.toList()));
                    log.info("Parsed {} change chunks, {} unique files",
                            chunks.size(), prContext.getFilesChanged().size());
                    return chunks;
                })
                .flatMap(chunks -> analyzeDiff(prContext, chunks))
                .doOnError(e -> log.error("Error in PR processing", e));
    }

    /**
     * Analyze changes: run heuristics and LLM analysis, merge findings, and publish.
     */
    private Mono<Void> analyzeDiff(PullRequestContext prContext, List<ChangeChunk> chunks) {
        log.debug("Starting diff analysis");

        List<Finding> findings = new ArrayList<>();

        // 1. Run heuristics analysis (synchronous)
        if (appProperties.isHeuristicsEnabled()) {
            log.debug("Running heuristics analysis");
            List<Finding> heuristicFindings = heuristicsAnalysisEngine.analyze(chunks, prContext);
            findings.addAll(heuristicFindings);
            log.info("Heuristics found {} findings", heuristicFindings.size());
        }

        // 2. Run LLM analysis (asynchronous)
        Mono<List<Finding>> llmResult = appProperties.isLlmEnabled()
                ? llmReviewEngine.analyzeWithLLM(prContext, chunks)
                    .onErrorResume(e -> {
                        log.error("LLM analysis failed, continuing with heuristics only", e);
                        return Mono.just(new ArrayList<>());
                    })
                : Mono.just(new ArrayList<>());

        // Extract before/after code change summaries
        if (prContext.getChangeSummaryBefore() == null || prContext.getChangeSummaryBefore().isBlank()) {
            prContext.setChangeSummaryBefore(summaryGenerator.extractWhatWasBefore(chunks, prContext));
        }
        if (prContext.getChangeSummaryAfter() == null || prContext.getChangeSummaryAfter().isBlank()) {
            prContext.setChangeSummaryAfter(summaryGenerator.extractWhatChanged(chunks, prContext));
        }

        return llmResult.flatMap(llmFindings -> {
            if (llmFindings != null) {
                findings.addAll(llmFindings);
                log.info("LLM found {} findings", llmFindings.size());
            }

            // Enrich context with functional analysis before generating summary
            summaryGenerator.enrichContextWithFunctionalAnalysis(prContext, chunks, findings);

            // Generate structured summary
            String summary = summaryGenerator.generateSummary(prContext, findings);
            log.info("Generated structured summary for PR {}/{}/#{}:", prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber());
            log.debug(summary);

            return publishReviewWithFindings(prContext, findings, chunks);
        });
    }

    /**
     * Merge, rank, and publish review findings.
     */
    private Mono<Void> publishReviewWithFindings(PullRequestContext prContext, List<Finding> findings, List<ChangeChunk> chunks) {
        log.debug("Merging and ranking {} findings", findings.size());

        List<Finding> rankedFindings = findingMerger.mergeAndRank(findings);
        log.info("Final {} findings after deduplication and ranking", rankedFindings.size());

        // Compute triage tier and attach to context
        TriageResult triageResult = summaryGenerator.computeTier(rankedFindings, prContext);
        prContext.setTriageResult(triageResult);
        log.info("Triage tier: {} (security={}, action={})",
                triageResult.tier(), triageResult.securityFlag(), triageResult.suggestedAction());

        // Re-enrich context with final ranked findings and triage tier
        summaryGenerator.enrichContextWithFunctionalAnalysis(prContext, chunks, rankedFindings);

        // Generate structured summary (once)
        String summary = summaryGenerator.generateSummary(prContext, rankedFindings);
        log.info("Generated structured summary for PR {}/{}/#{}:", prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber());
        log.debug(summary);

        boolean autoApprove = appProperties.isAutoApprove();
        boolean inlineComments = appProperties.isInlineComments();

        log.debug("Publishing review to {}/{}/PR#{} (autoApprove={}, inline={})",
                prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber(),
                autoApprove, inlineComments);

        // Persist BEFORE publishing: a transient GitHub API failure must not lose
        // the analysis (otherwise no dedupe row exists and no retry ever fires).
        PrAnalysis saved = persistAnalysis(prContext, rankedFindings, triageResult, summary);

        return reviewPublisher.publishReview(
                        prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber(),
                        rankedFindings, autoApprove, inlineComments, prContext.getInstallationId(), prContext)
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .doOnSuccess(v -> {
                    log.info("Review published successfully for {}/{}/PR#{}",
                            prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber());
                    log.info("Summary:");
                    log.debug(summary);
                })
                .doOnError(e -> log.error("Error publishing review for {}/{}/PR#{}",
                        prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber(), e))
                .then(Mono.defer(() -> {
                    if (saved != null) {
                        try {
                            thresholdAlertService.maybeAlert(saved);
                        } catch (Exception e) {
                            log.error("Failed to send threshold alert for {}/{}/PR#{}",
                                    prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber(), e);
                        }
                    }
                    return Mono.empty();
                }));
    }

    /**
     * Persist the completed analysis (findings + tier) for later reporting and dedupe.
     * Returns the saved entity, or null if persistence failed.
     */
    private PrAnalysis persistAnalysis(PullRequestContext prContext, List<Finding> findings,
                                        TriageResult triageResult, String summary) {
        try {
            PrAnalysis entity = new PrAnalysis();
            entity.setOwner(prContext.getOwner());
            entity.setRepo(prContext.getRepo());
            entity.setPrNumber(prContext.getPrNumber());
            entity.setCommitSha(prContext.getCommitSha());
            entity.setTier(triageResult != null && triageResult.tier() != null
                    ? triageResult.tier().name() : "UNKNOWN");
            entity.setSecurityFlag(triageResult != null && triageResult.securityFlag());
            entity.setSummary(summary);
            entity.setFindingsJson(gson.toJson(findings));
            entity.setTitle(prContext.getTitle());
            entity.setAuthor(prContext.getAuthorLogin());
            List<String> files = prContext.getFilesChanged();
            entity.setFilesChangedCount(files != null ? files.size() : 0);
            entity.setFilesChangedJson(files != null ? gson.toJson(files) : "[]");
            entity.setStatus("COMPLETED");
            entity.setInstallationId(String.valueOf(prContext.getInstallationId()));
            entity.setTargetUser(prContext.getTargetUser() != null && !prContext.getTargetUser().isBlank()
                    ? prContext.getTargetUser() : prContext.getOwner());
            entity.setAuthorReputation(prContext.getAuthorReputation() != null
                    ? prContext.getAuthorReputation() : "FIRST_TIME_CONTRIBUTOR");
            entity.setAuthorReputationDetail(prContext.getAuthorReputationDetail());
            entity.setChangeSummaryBefore(prContext.getChangeSummaryBefore());
            entity.setChangeSummaryAfter(prContext.getChangeSummaryAfter());
            entity.setRepoContext(prContext.getRepoContext());
            entity.setCreatedAt(java.time.Instant.now());

            prAnalysisRepository.save(entity);
            log.info("Persisted analysis for {}/{}/PR#{} (sha {})",
                    prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber(),
                    prContext.getCommitSha());
            return entity;
        } catch (Exception e) {
            log.error("Failed to persist analysis for {}/{}/PR#{}",
                    prContext.getOwner(), prContext.getRepo(), prContext.getPrNumber(), e);
            return null;
        }
    }
}
