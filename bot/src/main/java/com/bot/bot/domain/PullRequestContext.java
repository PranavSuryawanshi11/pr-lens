package com.bot.bot.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullRequestContext {
    private String owner;
    private String repo;
    private int prNumber;
    private String title;
    private String description;
    private String authorLogin;
    private String baseRef;
    private String headRef;
    private String commitSha;
    private long installationId;
    private List<String> filesChanged;
    private TriageResult triageResult;
    private String targetUser;
    private String authorAssociation;
    private String authorReputation;
    private String authorReputationDetail;
    private String repoContext;
    private String changeSummaryBefore;
    private String changeSummaryAfter;
    private String executiveSummary;
    private List<String> functionalChanges;
    private List<String> whatToEditOrAdd;
    private String decisionRecommendation;
    private String decisionRationale;

    private static final ThreadLocal<PullRequestContext> CURRENT = new ThreadLocal<>();

    public static void setCurrent(PullRequestContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    public static PullRequestContext getCurrent() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
