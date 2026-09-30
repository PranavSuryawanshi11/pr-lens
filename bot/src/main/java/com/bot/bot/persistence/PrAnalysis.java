package com.bot.bot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "pr_analysis")
@Data
public class PrAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repo;

    @Column(name = "pr_number", nullable = false)
    private Integer prNumber;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(name = "title")
    private String title;

    @Column(name = "author")
    private String author;

    @Column(name = "files_changed_count")
    private Integer filesChangedCount = 0;

    @Column(name = "files_changed_json", columnDefinition = "TEXT")
    private String filesChangedJson;

    @Column(nullable = false)
    private String tier;

    @Column(name = "security_flag", nullable = false)
    private Boolean securityFlag;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "findings_json", columnDefinition = "TEXT")
    private String findingsJson;

    @Column(nullable = false)
    private String status;

    @Column(name = "installation_id")
    private String installationId;

    @Column(name = "alerted", nullable = false)
    private Boolean alerted = false;

    @Column(name = "action_taken", nullable = false)
    private Boolean actionTaken = false;

    @Column(name = "closed")
    private Boolean closed = false;

    @Column(name = "action_type")
    private String actionType;

    @Column(name = "target_user")
    private String targetUser;

    @Column(name = "author_reputation")
    private String authorReputation;

    @Column(name = "author_reputation_detail")
    private String authorReputationDetail;

    @Column(name = "change_summary_before", columnDefinition = "TEXT")
    private String changeSummaryBefore;

    @Column(name = "change_summary_after", columnDefinition = "TEXT")
    private String changeSummaryAfter;

    @Column(name = "repo_context", columnDefinition = "TEXT")
    private String repoContext;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
