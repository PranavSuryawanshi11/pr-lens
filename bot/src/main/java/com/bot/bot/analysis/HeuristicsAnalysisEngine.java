package com.bot.bot.analysis;

import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import com.bot.bot.analysis.heuristics.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class HeuristicsAnalysisEngine {
    private final SecretsDetectionRule secretsDetectionRule;
    private final CommitMessageStyleRule commitMessageStyleRule;
    private final DiffShapeRule diffShapeRule;
    private final AccountAgeRule accountAgeRule;
    private final CommentCodeRatioRule commentCodeRatioRule;
    private final BoilerplatePhraseRule boilerplatePhraseRule;
    private final BugDetectionRule bugDetectionRule;
    private final ExecutorService executor;

    @Autowired
    public HeuristicsAnalysisEngine(SecretsDetectionRule secretsDetectionRule,
                                    CommitMessageStyleRule commitMessageStyleRule,
                                    DiffShapeRule diffShapeRule,
                                    AccountAgeRule accountAgeRule,
                                    CommentCodeRatioRule commentCodeRatioRule,
                                    BoilerplatePhraseRule boilerplatePhraseRule,
                                    BugDetectionRule bugDetectionRule,
                                    ExecutorService executor) {
        this.secretsDetectionRule = secretsDetectionRule;
        this.commitMessageStyleRule = commitMessageStyleRule;
        this.diffShapeRule = diffShapeRule;
        this.accountAgeRule = accountAgeRule;
        this.commentCodeRatioRule = commentCodeRatioRule;
        this.boilerplatePhraseRule = boilerplatePhraseRule;
        this.bugDetectionRule = bugDetectionRule != null ? bugDetectionRule : new BugDetectionRule();
        this.executor = executor;
    }

    public HeuristicsAnalysisEngine(SecretsDetectionRule secretsDetectionRule,
                                    CommitMessageStyleRule commitMessageStyleRule,
                                    DiffShapeRule diffShapeRule,
                                    AccountAgeRule accountAgeRule,
                                    CommentCodeRatioRule commentCodeRatioRule,
                                    BoilerplatePhraseRule boilerplatePhraseRule,
                                    ExecutorService executor) {
        this(secretsDetectionRule, commitMessageStyleRule, diffShapeRule,
             accountAgeRule, commentCodeRatioRule, boilerplatePhraseRule,
             new BugDetectionRule(), executor);
    }

    public List<Finding> analyze(List<ChangeChunk> chunks, PullRequestContext prContext) {
        List<Finding> findings = new ArrayList<>();

        PullRequestContext.setCurrent(prContext);
        try {
            // Run all rules in parallel on the shared executor (no per-call pool).
            List<CompletableFuture<List<Finding>>> futures = new ArrayList<>();
            futures.add(CompletableFuture.supplyAsync(() -> {
                PullRequestContext.setCurrent(prContext);
                try {
                    return secretsDetectionRule.analyze(chunks);
                } finally {
                    PullRequestContext.clear();
                }
            }, executor));
            futures.add(CompletableFuture.supplyAsync(() -> {
                PullRequestContext.setCurrent(prContext);
                try {
                    return commitMessageStyleRule.analyze(chunks);
                } finally {
                    PullRequestContext.clear();
                }
            }, executor));
            futures.add(CompletableFuture.supplyAsync(() -> {
                PullRequestContext.setCurrent(prContext);
                try {
                    return diffShapeRule.analyze(chunks);
                } finally {
                    PullRequestContext.clear();
                }
            }, executor));
            futures.add(CompletableFuture.supplyAsync(() -> {
                PullRequestContext.setCurrent(prContext);
                try {
                    return accountAgeRule.analyze(chunks);
                } finally {
                    PullRequestContext.clear();
                }
            }, executor));
            futures.add(CompletableFuture.supplyAsync(() -> {
                PullRequestContext.setCurrent(prContext);
                try {
                    return commentCodeRatioRule.analyze(chunks);
                } finally {
                    PullRequestContext.clear();
                }
            }, executor));
            futures.add(CompletableFuture.supplyAsync(() -> {
                PullRequestContext.setCurrent(prContext);
                try {
                    return boilerplatePhraseRule.analyze(chunks);
                } finally {
                    PullRequestContext.clear();
                }
            }, executor));
            futures.add(CompletableFuture.supplyAsync(() -> {
                PullRequestContext.setCurrent(prContext);
                try {
                    return bugDetectionRule != null ? bugDetectionRule.analyze(chunks, prContext) : Collections.emptyList();
                } finally {
                    PullRequestContext.clear();
                }
            }, executor));

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (CompletableFuture<List<Finding>> future : futures) {
                findings.addAll(future.join());
            }

            return findings;
        } finally {
            PullRequestContext.clear();
        }
    }
}
