package com.bot.bot.analysis;

import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import com.bot.bot.llm.LLMClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LLMReviewEngine {
    private final LLMClient llmClient;

    /**
     * Analyze code chunks using LLM for contextual review.
     * Processes chunks in parallel and aggregates results.
     */
    public Mono<List<Finding>> analyzeWithLLM(PullRequestContext prContext, List<ChangeChunk> chunks) {
        log.debug("Starting LLM analysis on {} chunks", chunks.size());

        if (chunks.isEmpty()) {
            return Mono.just(new ArrayList<>());
        }

        // Attempt holistic PR review to extract executive summary, functional changes,
        // what to add/edit, maintainer decision, and findings
        String holisticPrompt = buildHolisticPrompt(prContext, chunks);

        return llmClient.generateCodeReview(holisticPrompt)
                .map(response -> parseHolisticReviewResponse(response, prContext, chunks))
                .filter(findings -> !findings.isEmpty() || (prContext != null && prContext.getExecutiveSummary() != null))
                .switchIfEmpty(
                        Flux.fromIterable(chunks)
                                .flatMap(chunk -> generateReviewForChunk(chunk, prContext))
                                .flatMap(Flux::fromIterable)
                                .collectList()
                )
                .onErrorResume(e -> {
                    log.warn("Holistic LLM analysis unavailable ({}), running per-chunk review", e.getMessage());
                    return Flux.fromIterable(chunks)
                            .flatMap(chunk -> generateReviewForChunk(chunk, prContext))
                            .flatMap(Flux::fromIterable)
                            .collectList()
                            .onErrorResume(err -> {
                                log.warn("Fallback per-chunk review failed", err);
                                return Mono.just(new ArrayList<>());
                            });
                })
                .doOnSuccess(findings -> log.debug("LLM analysis completed with {} findings", findings.size()));
    }

    /**
     * Generate review for a single chunk and return list of findings.
     */
    private Mono<List<Finding>> generateReviewForChunk(ChangeChunk chunk, PullRequestContext prContext) {
        String prompt = buildPrompt(chunk, prContext);

        return llmClient.generateCodeReview(prompt)
                .map(response -> parseReviewResponse(response, chunk))
                .onErrorResume(e -> {
                    log.warn("Error generating review for chunk {}", chunk.getFilePath(), e);
                    return Mono.just(new ArrayList<>());
                });
    }

    /**
     * Build a comprehensive prompt for the LLM with context, including bug detection and AI-likelihood assessment.
     */
    private String buildPrompt(ChangeChunk chunk, PullRequestContext prContext) {
        String added = chunk.getAddedLines().isEmpty()
                ? "(none)" : String.join("\n", chunk.getAddedLines());
        String removed = chunk.getRemovedLines().isEmpty()
                ? "(none)" : String.join("\n", chunk.getRemovedLines());

        return String.format(
                """
                You are an expert code reviewer. Analyze the following code change for software bugs, logic flaws, and overall quality, and provide specific, actionable feedback.
                
                File: %s
                Change Type: %s
                
                Added Lines (new code):
                %s
                
                Removed Lines (deleted code):
                %s
                
                Context:
                %s
                
                PR Title: %s
                PR Description: %s
                
                Provide your review in a structured format:
                1. Code Bugs & Logic Flaws (if any):
                   Identify any runtime crashes, NullPointer/undefined dereferencing, off-by-one errors, resource leaks, unhandled edge cases, or broken logic.
                   Format each bug clearly as:
                   - BUG [CRITICAL|HIGH|MEDIUM]: <bug description> - SUGGESTION: <how to fix it>
                2. Issues Found (if any): List each issue with severity (CRITICAL, HIGH, MEDIUM, LOW)
                3. Suggestions for Improvement
                4. Positive Observations (if any)
                5. AI-Likelihood Assessment: Classify the likelihood of AI assistance in this change as LOW, MEDIUM, or HIGH, and provide reasoning.

                Be concise and focus on substantive issues.

                For AI-Likelihood Assessment:
                - LOW: Clearly human-written, no signs of AI assistance
                - MEDIUM: Possible AI assistance, but not definitive
                - HIGH: Likely AI-generated or heavily assisted

                Include reasoning for your classification.
                """,
                chunk.getFilePath(),
                chunk.getChangeType(),
                added,
                removed,
                chunk.getContext(),
                prContext.getTitle(),
                prContext.getDescription() != null ? prContext.getDescription() : "No description"
        );
    }

    // Pattern for explicit bug lines: e.g. - BUG [CRITICAL]: ... or BUG: ...
    private static final Pattern BUG_PATTERN = Pattern.compile(
            "^\\s*[-*]?\\s*BUG\\s*(\\[(CRITICAL|HIGH|MEDIUM|LOW)\\]|:\\s*(CRITICAL|HIGH|MEDIUM|LOW)|(CRITICAL|HIGH|MEDIUM|LOW))?\\s*[:\\-]?\\s*(.+)",
            Pattern.CASE_INSENSITIVE
    );

    // Patterns for severity detection with word boundaries to reduce false positives
    private static final Pattern SEVERITY_CRITICAL = Pattern.compile(
            "\\b(CRITICAL|DANGER|CRITICAL ISSUE)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEVERITY_HIGH = Pattern.compile(
            "\\b(SEVERITY:\\s*HIGH|\\[HIGH\\]|BUG|VULNERABILITY)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEVERITY_MEDIUM = Pattern.compile(
            "\\b(SEVERITY:\\s*MEDIUM|\\[MEDIUM\\]|WARNING|MEDIUM RISK)\\b.*", Pattern.CASE_INSENSITIVE);

    // Patterns for AI-likelihood detection
    private static final Pattern AI_LIKELIHOOD_LOW = Pattern.compile(
            "\\b(AI-LIKELIHOOD:\\s*LOW|\\[LOW\\]|HUMAN-WRITTEN|LOW AI-LIKELIHOOD)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern AI_LIKELIHOOD_MEDIUM = Pattern.compile(
            "\\b(AI-LIKELIHOOD:\\s*MEDIUM|\\[MEDIUM\\]|POSSIBLE AI|MEDIUM AI-LIKELIHOOD)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern AI_LIKELIHOOD_HIGH = Pattern.compile(
            "\\b(AI-LIKELIHOOD:\\s*HIGH|\\[HIGH\\]|AI-GENERATED|HIGH AI-LIKELIHOOD)\\b.*", Pattern.CASE_INSENSITIVE);

    /**
     * Parse LLM response and extract findings, including code bugs and AI-likelihood classification.
     */
    private List<Finding> parseReviewResponse(String response, ChangeChunk chunk) {
        List<Finding> findings = new ArrayList<>();

        if (response == null || response.isEmpty()) {
            return findings;
        }

        // Split response into lines and check for severity-labeled findings
        String[] lines = response.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Detect explicit bug findings
            Matcher bugMatcher = BUG_PATTERN.matcher(trimmed);
            if (bugMatcher.find()) {
                String severity = "HIGH";
                String upper = trimmed.toUpperCase();
                if (upper.contains("CRITICAL") || upper.contains("DANGER")) {
                    severity = "CRITICAL";
                } else if (upper.contains("MEDIUM")) {
                    severity = "MEDIUM";
                } else if (upper.contains("LOW")) {
                    severity = "LOW";
                }

                String bugMsg = trimmed;
                String suggestion = null;
                if (trimmed.contains(" - SUGGESTION:") || trimmed.contains(" - FIX:")) {
                    String[] parts = trimmed.split(" - (SUGGESTION|FIX):", 2);
                    bugMsg = parts[0].trim();
                    suggestion = parts.length > 1 ? parts[1].trim() : null;
                }

                findings.add(Finding.builder()
                        .id(UUID.randomUUID().toString())
                        .filePath(chunk.getFilePath())
                        .lineNumber(chunk.getStartLine())
                        .severity(severity)
                        .category("BUG_DETECTION")
                        .message(bugMsg)
                        .suggestion(suggestion)
                        .source("LLM")
                        .confidence(0.85)
                        .precedenceScore(800)
                        .build());
            }
            // Detect critical issues with word-boundary matching
            else if (SEVERITY_CRITICAL.matcher(trimmed).find()) {
                findings.add(Finding.builder()
                        .id(UUID.randomUUID().toString())
                        .filePath(chunk.getFilePath())
                        .lineNumber(chunk.getStartLine())
                        .severity("CRITICAL")
                        .category("CODE_REVIEW")
                        .message(trimmed)
                        .source("LLM")
                        .confidence(0.85)
                        .precedenceScore(700)
                        .build());
            }
            // Detect high severity issues
            else if (SEVERITY_HIGH.matcher(trimmed).find()) {
                findings.add(Finding.builder()
                        .id(UUID.randomUUID().toString())
                        .filePath(chunk.getFilePath())
                        .lineNumber(chunk.getStartLine())
                        .severity("HIGH")
                        .category("CODE_REVIEW")
                        .message(trimmed)
                        .source("LLM")
                        .confidence(0.80)
                        .precedenceScore(650)
                        .build());
            }
            // Detect medium severity issues
            else if (SEVERITY_MEDIUM.matcher(trimmed).find()) {
                findings.add(Finding.builder()
                        .id(UUID.randomUUID().toString())
                        .filePath(chunk.getFilePath())
                        .lineNumber(chunk.getStartLine())
                        .severity("MEDIUM")
                        .category("CODE_REVIEW")
                        .message(trimmed)
                        .source("LLM")
                        .confidence(0.75)
                        .precedenceScore(600)
                        .build());
            }
            // Detect AI-likelihood: Low
            else if (AI_LIKELIHOOD_LOW.matcher(trimmed).find()) {
                findings.add(Finding.builder()
                        .id(UUID.randomUUID().toString())
                        .filePath(chunk.getFilePath())
                        .lineNumber(chunk.getStartLine())
                        .severity("INFO")
                        .category("AI_LIKELIHOOD")
                        .message(trimmed)
                        .source("LLM")
                        .confidence(0.85)
                        .precedenceScore(500)
                        .build());
            }
            // Detect AI-likelihood: Medium
            else if (AI_LIKELIHOOD_MEDIUM.matcher(trimmed).find()) {
                findings.add(Finding.builder()
                        .id(UUID.randomUUID().toString())
                        .filePath(chunk.getFilePath())
                        .lineNumber(chunk.getStartLine())
                        .severity("WARNING")
                        .category("AI_LIKELIHOOD")
                        .message(trimmed)
                        .source("LLM")
                        .confidence(0.80)
                        .precedenceScore(550)
                        .build());
            }
            // Detect AI-likelihood: High
            else if (AI_LIKELIHOOD_HIGH.matcher(trimmed).find()) {
                findings.add(Finding.builder()
                        .id(UUID.randomUUID().toString())
                        .filePath(chunk.getFilePath())
                        .lineNumber(chunk.getStartLine())
                        .severity("INFO")
                        .category("AI_LIKELIHOOD")
                        .message(trimmed)
                        .source("LLM")
                        .confidence(0.85)
                        .precedenceScore(550)
                        .build());
            }
        }

        return findings;
    }

    private String buildHolisticPrompt(PullRequestContext prContext, List<ChangeChunk> chunks) {
        StringBuilder diffBuilder = new StringBuilder();
        int charBudget = 32_000;
        int currentLength = 0;

        for (ChangeChunk chunk : chunks) {
            String filePath = chunk.getFilePath() != null ? chunk.getFilePath() : "unknown";
            String changeType = chunk.getChangeType() != null ? chunk.getChangeType() : "MODIFIED";
            String added = chunk.getAddedLines().isEmpty() ? "(none)" : String.join("\n", chunk.getAddedLines());
            String removed = chunk.getRemovedLines().isEmpty() ? "(none)" : String.join("\n", chunk.getRemovedLines());

            String chunkText = String.format("""
                    --- File: %s (%s) ---
                    [Added Lines]:
                    %s
                    [Removed Lines]:
                    %s
                    """, filePath, changeType, added, removed);

            if (currentLength + chunkText.length() > charBudget) {
                diffBuilder.append("\n... [Additional changes truncated for length] ...\n");
                break;
            }
            diffBuilder.append(chunkText).append("\n");
            currentLength += chunkText.length();
        }

        String title = prContext != null && prContext.getTitle() != null ? prContext.getTitle() : "Pull Request";
        String desc = prContext != null && prContext.getDescription() != null ? prContext.getDescription() : "No description provided.";
        String author = prContext != null && prContext.getAuthorLogin() != null ? prContext.getAuthorLogin() : "unknown";
        String repo = prContext != null ? prContext.getOwner() + "/" + prContext.getRepo() : "repository";

        return String.format(
                """
                You are a Staff Principal Engineer performing a comprehensive code review and executive triage of this GitHub Pull Request for the repository owner.
                
                The repository owner needs to make an immediate decision (merge, request specific edits, or reject) purely from your email review without having to open the code diff or decipher raw code snippets.
                
                Repository: %s
                PR Title: %s
                Author: @%s
                PR Description: %s
                
                === CODE CHANGES ===
                %s
                
                Provide your analysis using these EXACT section headers:
                
                ===EXECUTIVE_SUMMARY===
                A clear, professional 2-3 sentence explanation of the PR's core purpose, motivation, and what problem it solves.
                
                ===FUNCTIONAL_CHANGES===
                A comprehensive point-by-point breakdown:
                - What new capabilities, endpoints, classes, methods, or configurations were added and what they do.
                - What existing logic or behavior was modified, and how it behaves now vs before.
                - System Behavioral Diff: Before PR vs After PR.
                
                ===WHAT_TO_ADD_OR_EDIT===
                Actionable gap analysis and specific instructions for the PR contributor:
                - If incomplete or issues exist: List the exact functionality, validations, edge-case checks, error handling, or unit tests that the author MUST add or edit before this PR can be merged.
                - If completely ready and clean: State "No additional edits required — implementation is complete, well-tested, and ready for production."
                
                ===MAINTAINER_DECISION===
                - Recommendation: [APPROVE & MERGE | REQUEST SPECIFIC CHANGES | REJECT / CLOSE]
                - Rationale: 2-3 clear bullet points explaining why the repo owner should take this action.
                
                ===FINDINGS===
                List each substantive bug, logic flaw, security issue, or quality note:
                - BUG [CRITICAL|HIGH|MEDIUM|LOW]: <bug description> - SUGGESTION: <how to fix it>
                - ISSUE [CRITICAL|HIGH|MEDIUM|LOW]: <issue description>
                - AI-LIKELIHOOD: [LOW|MEDIUM|HIGH] - <reasoning>
                - POSITIVE: <positive observation, e.g. includes tests, clean architecture>
                """,
                repo, title, author, desc, diffBuilder.toString()
        );
    }

    private List<Finding> parseHolisticReviewResponse(String response, PullRequestContext prContext, List<ChangeChunk> chunks) {
        List<Finding> findings = new ArrayList<>();
        if (response == null || response.isBlank()) {
            return findings;
        }

        String execSummary = extractSection(response, "EXECUTIVE_SUMMARY", "Executive Summary");
        if (execSummary != null && !execSummary.isBlank() && prContext != null) {
            prContext.setExecutiveSummary(execSummary.trim());
        }

        String funcChanges = extractSection(response, "FUNCTIONAL_CHANGES", "Functional Changes");
        if (funcChanges != null && !funcChanges.isBlank() && prContext != null) {
            List<String> changeList = Arrays.stream(funcChanges.split("\n"))
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("===") && !l.startsWith("###"))
                    .map(l -> l.replaceFirst("^[-*•\\d.)\\s]+", "").trim())
                    .filter(l -> !l.isEmpty())
                    .limit(8)
                    .collect(Collectors.toList());
            if (!changeList.isEmpty()) {
                prContext.setFunctionalChanges(changeList);
            }
        }

        String whatToEdit = extractSection(response, "WHAT_TO_ADD_OR_EDIT", "What to Add or Edit");
        if (whatToEdit == null || whatToEdit.isBlank()) {
            whatToEdit = extractSection(response, "WHAT_NEEDS_TO_BE_ADDED", "What Needs to Be Added");
        }
        if (whatToEdit != null && !whatToEdit.isBlank() && prContext != null) {
            List<String> editList = Arrays.stream(whatToEdit.split("\n"))
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("===") && !l.startsWith("###"))
                    .map(l -> l.replaceFirst("^[-*•\\d.)\\s]+", "").trim())
                    .filter(l -> !l.isEmpty())
                    .limit(6)
                    .collect(Collectors.toList());
            if (!editList.isEmpty()) {
                prContext.setWhatToEditOrAdd(editList);
            }
        }

        String decision = extractSection(response, "MAINTAINER_DECISION", "Maintainer Decision");
        if (decision != null && !decision.isBlank() && prContext != null) {
            String[] lines = decision.split("\n");
            StringBuilder rationale = new StringBuilder();
            for (String l : lines) {
                String trimmed = l.trim();
                if (trimmed.toLowerCase().startsWith("- recommendation:") || trimmed.toLowerCase().startsWith("recommendation:")) {
                    String rec = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                    prContext.setDecisionRecommendation(rec);
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("===") && !trimmed.startsWith("###")) {
                    if (rationale.length() > 0) rationale.append(" ");
                    rationale.append(trimmed.replaceFirst("^[-*•\\d.)\\s]+", "").trim());
                }
            }
            if (rationale.length() > 0) {
                prContext.setDecisionRationale(rationale.toString());
            }
        }

        // Parse findings from whole text or findings section
        ChangeChunk defaultChunk = !chunks.isEmpty() ? chunks.get(0) : ChangeChunk.builder().filePath("Repository").startLine(1).build();
        findings.addAll(parseReviewResponse(response, defaultChunk));

        return findings;
    }

    private String extractSection(String text, String tag1, String tag2) {
        Pattern pattern = Pattern.compile(
                "(?:===|###|##)\\s*(?:" + Pattern.quote(tag1) + "|" + Pattern.quote(tag2) + ")\\s*(?:===)?\\s*\\n(.*?)(?=\\n(?:===|###|##|\\Z))",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}