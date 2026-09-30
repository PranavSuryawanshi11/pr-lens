package com.bot.bot.analysis.heuristics;

import com.bot.bot.analysis.Rule;
import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class AccountAgeRule implements Rule {

    @Override
    public List<Finding> analyze(List<ChangeChunk> chunks) {
        return analyze(chunks, PullRequestContext.getCurrent());
    }

    @Override
    public List<Finding> analyze(List<ChangeChunk> chunks, PullRequestContext prContext) {
        List<Finding> findings = new ArrayList<>();
        if (prContext == null) {
            return findings;
        }

        String author = prContext.getAuthorLogin() != null ? prContext.getAuthorLogin() : "unknown";
        String reputation = prContext.getAuthorReputation() != null ? prContext.getAuthorReputation() : "FIRST_TIME_CONTRIBUTOR";
        String detail = prContext.getAuthorReputationDetail() != null ? prContext.getAuthorReputationDetail() : "";

        if ("TRUSTED_MAINTAINER".equalsIgnoreCase(reputation)) {
            findings.add(Finding.builder()
                    .id("author-trusted-maintainer")
                    .filePath("REPOSITORY")
                    .lineNumber(0)
                    .severity("INFO")
                    .category("POSITIVE_OBSERVATION")
                    .message(String.format("Author @%s is a trusted maintainer/owner (%s). Fast-track review eligible.", author, detail))
                    .source("HEURISTIC")
                    .confidence(0.95)
                    .precedenceScore(350)
                    .build());
        } else if ("COLLABORATOR".equalsIgnoreCase(reputation)) {
            findings.add(Finding.builder()
                    .id("author-collaborator")
                    .filePath("REPOSITORY")
                    .lineNumber(0)
                    .severity("INFO")
                    .category("POSITIVE_OBSERVATION")
                    .message(String.format("Author @%s is an active repository collaborator (%s).", author, detail))
                    .source("HEURISTIC")
                    .confidence(0.90)
                    .precedenceScore(380)
                    .build());
        } else if ("RETURNING_CONTRIBUTOR".equalsIgnoreCase(reputation)) {
            findings.add(Finding.builder()
                    .id("author-returning-contributor")
                    .filePath("REPOSITORY")
                    .lineNumber(0)
                    .severity("INFO")
                    .category("CONTRIBUTOR_CONTEXT")
                    .message(String.format("Author @%s is a returning contributor (%s).", author, detail))
                    .source("HEURISTIC")
                    .confidence(0.85)
                    .precedenceScore(420)
                    .build());
        } else {
            // First time contributor / external
            findings.add(Finding.builder()
                    .id("author-first-time-contributor")
                    .filePath("REPOSITORY")
                    .lineNumber(0)
                    .severity("INFO")
                    .category("CONTRIBUTOR_CONTEXT")
                    .message(String.format("Author @%s is a first-time contributor (%s). Detailed code review and intent verification recommended.", author, detail.isBlank() ? "no prior merged PRs found" : detail))
                    .suggestion("Review changes thoroughly and ensure new tests accompany any functional logic.")
                    .source("HEURISTIC")
                    .confidence(0.90)
                    .precedenceScore(480)
                    .build());
        }

        return findings;
    }

    @Override
    public String getName() {
        return "AccountAgeRule";
    }
}