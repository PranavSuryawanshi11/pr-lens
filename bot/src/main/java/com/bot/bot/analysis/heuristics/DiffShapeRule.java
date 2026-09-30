package com.bot.bot.analysis.heuristics;

import com.bot.bot.analysis.Rule;
import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DiffShapeRule implements Rule {

    private static final Pattern CMD_EXEC_PATTERN = Pattern.compile(
            "\\b(Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder|system\\(|popen\\()\\b");
    private static final Pattern RAW_SQL_PATTERN = Pattern.compile(
            "(?i)\"(SELECT|INSERT|UPDATE|DELETE)\\s+.*\"\\s*\\+\\s*[a-zA-Z_]");
    private static final Pattern SECURITY_DISABLE_PATTERN = Pattern.compile(
            "(?i)\\b(csrf\\(\\)\\.disable|permitAll\\(\\)|TrustAllCerts|ALLOW_ALL_HOSTNAME_VERIFIER|InsecureTrustManager)\\b");
    private static final Pattern EMPTY_CATCH_PATTERN = Pattern.compile(
            "catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}");

    @Override
    public List<Finding> analyze(List<ChangeChunk> chunks) {
        return analyze(chunks, PullRequestContext.getCurrent());
    }

    @Override
    public List<Finding> analyze(List<ChangeChunk> chunks, PullRequestContext prContext) {
        List<Finding> findings = new ArrayList<>();

        if (chunks == null || chunks.isEmpty()) {
            return findings;
        }

        // Track unique files changed
        Set<String> uniqueFiles = new HashSet<>();
        int totalLinesAdded = 0;
        int totalLinesRemoved = 0;
        int nonTestLinesAdded = 0;
        boolean hasTestFileChanges = false;
        boolean hasTestDeletions = false;

        boolean isFirstTime = prContext != null && "FIRST_TIME_CONTRIBUTOR".equalsIgnoreCase(prContext.getAuthorReputation());

        for (ChangeChunk chunk : chunks) {
            String path = chunk.getFilePath() != null ? chunk.getFilePath() : "";
            String lowerPath = path.toLowerCase();
            uniqueFiles.add(path);

            int addedCount = chunk.getAddedLines() != null ? chunk.getAddedLines().size() : 0;
            int removedCount = chunk.getRemovedLines() != null ? chunk.getRemovedLines().size() : 0;
            totalLinesAdded += addedCount;
            totalLinesRemoved += removedCount;

            boolean isTestFile = lowerPath.contains("test") || lowerPath.contains("spec");
            if (isTestFile) {
                hasTestFileChanges = true;
                if (removedCount > addedCount || "DELETED".equalsIgnoreCase(chunk.getChangeType())) {
                    hasTestDeletions = true;
                }
            } else {
                nonTestLinesAdded += addedCount;
            }

            // 1. Critical & Sensitive files inspection (Repo Context)
            if (lowerPath.contains(".github/workflows") || lowerPath.contains(".github/actions")) {
                findings.add(Finding.builder()
                        .id("risk-workflow-modification")
                        .filePath(path)
                        .lineNumber(chunk.getStartLine())
                        .severity(isFirstTime ? "CRITICAL" : "HIGH")
                        .category("SECURITY")
                        .message("CI/CD workflow configuration modified: " + path + ". CI changes execute automated scripts with repository secrets.")
                        .suggestion("Audit pipeline steps, secret access, and external action hashes carefully.")
                        .source("HEURISTIC")
                        .confidence(0.95)
                        .precedenceScore(900)
                        .build());
            } else if (lowerPath.endsWith(".env") || lowerPath.contains("secret") || lowerPath.contains("credentials")) {
                findings.add(Finding.builder()
                        .id("risk-env-sensitive-file")
                        .filePath(path)
                        .lineNumber(chunk.getStartLine())
                        .severity("CRITICAL")
                        .category("SECURITY")
                        .message("Sensitive environment or credential file touched: " + path)
                        .suggestion("Ensure sensitive keys and tokens are not committed to source control.")
                        .source("HEURISTIC")
                        .confidence(0.98)
                        .precedenceScore(950)
                        .build());
            } else if (lowerPath.endsWith("pom.xml") || lowerPath.endsWith("package.json") || lowerPath.endsWith("build.gradle")) {
                findings.add(Finding.builder()
                        .id("risk-dependency-management")
                        .filePath(path)
                        .lineNumber(chunk.getStartLine())
                        .severity(isFirstTime ? "HIGH" : "INFO")
                        .category("SECURITY")
                        .message("Build and dependency manifest modified: " + path + (isFirstTime ? " (external contributor - verify dependency sources for supply-chain risk)" : ""))
                        .suggestion("Verify new dependency versions against CVE databases.")
                        .source("HEURISTIC")
                        .confidence(0.85)
                        .precedenceScore(700)
                        .build());
            }

            // 2. Dangerous code patterns in added lines
            if (chunk.getAddedLines() != null) {
                for (int i = 0; i < chunk.getAddedLines().size(); i++) {
                    String addedLine = chunk.getAddedLines().get(i);
                    int lineNo = chunk.getStartLine() + i;

                    if (CMD_EXEC_PATTERN.matcher(addedLine).find()) {
                        findings.add(Finding.builder()
                                .id("risk-cmd-exec")
                                .filePath(path)
                                .lineNumber(lineNo)
                                .severity("CRITICAL")
                                .category("SECURITY")
                                .message("Potential OS command execution or ProcessBuilder invocation detected: " + addedLine.trim())
                                .suggestion("Avoid executing shell processes; use safe language APIs instead.")
                                .source("HEURISTIC")
                                .confidence(0.92)
                                .precedenceScore(920)
                                .build());
                    }

                    if (RAW_SQL_PATTERN.matcher(addedLine).find()) {
                        findings.add(Finding.builder()
                                .id("risk-sql-concatenation")
                                .filePath(path)
                                .lineNumber(lineNo)
                                .severity("HIGH")
                                .category("SECURITY")
                                .message("Potential SQL injection hazard detected via dynamic string concatenation in query.")
                                .suggestion("Use parameterized queries, PreparedStatements, or JPA criteria.")
                                .source("HEURISTIC")
                                .confidence(0.88)
                                .precedenceScore(820)
                                .build());
                    }

                    if (SECURITY_DISABLE_PATTERN.matcher(addedLine).find()) {
                        findings.add(Finding.builder()
                                .id("risk-security-bypass")
                                .filePath(path)
                                .lineNumber(lineNo)
                                .severity("HIGH")
                                .category("SECURITY")
                                .message("Security controls or certificate verification may have been disabled: " + addedLine.trim())
                                .suggestion("Verify that security bypasses are not deployed to production.")
                                .source("HEURISTIC")
                                .confidence(0.90)
                                .precedenceScore(850)
                                .build());
                    }

                    if (EMPTY_CATCH_PATTERN.matcher(addedLine).find()) {
                        findings.add(Finding.builder()
                                .id("risk-swallowed-exception")
                                .filePath(path)
                                .lineNumber(lineNo)
                                .severity("LOW")
                                .category("CODE_SMELL")
                                .message("Empty catch block detected - exception is being silently swallowed.")
                                .suggestion("Log exception or handle failure state properly.")
                                .source("HEURISTIC")
                                .confidence(0.80)
                                .precedenceScore(300)
                                .build());
                    }
                }
            }
        }

        // 3. Test coverage signals
        if (prContext != null && nonTestLinesAdded > 35 && !hasTestFileChanges) {
            findings.add(Finding.builder()
                    .id("risk-missing-tests")
                    .filePath("PROJECT_SCOPE")
                    .lineNumber(0)
                    .severity("MEDIUM")
                    .category("QUALITY")
                    .message(String.format("Added %d lines of functional code without accompanying test coverage updates.", nonTestLinesAdded))
                    .suggestion("Request unit or integration tests verifying the new behavior.")
                    .source("HEURISTIC")
                    .confidence(0.80)
                    .precedenceScore(450)
                    .build());
        }

        if (hasTestDeletions) {
            findings.add(Finding.builder()
                    .id("risk-test-deletion")
                    .filePath("TEST_SUITE")
                    .lineNumber(0)
                    .severity("WARNING")
                    .category("QUALITY")
                    .message("Existing test cases or assertions appear to have been removed or reduced.")
                    .suggestion("Confirm that regression coverage was not compromised.")
                    .source("HEURISTIC")
                    .confidence(0.85)
                    .precedenceScore(500)
                    .build());
        }

        // 4. Standard Diff Shape Checks (Sweeping & High Volume)
        if (uniqueFiles.size() > 5) {
            findings.add(Finding.builder()
                    .id("diff-sweeping")
                    .filePath("MULTIPLE_FILES")
                    .lineNumber(0)
                    .severity("WARNING")
                    .category("AI_LIKELIHOOD")
                    .message("Sweeping changes across many unrelated files detected. This is a common pattern in AI-generated PRs.")
                    .source("HEURISTIC")
                    .confidence(0.75)
                    .precedenceScore(550)
                    .build());
        }

        int totalLinesChanged = totalLinesAdded + totalLinesRemoved;
        if (totalLinesChanged > 100) {
            findings.add(Finding.builder()
                    .id("diff-high-volume")
                    .filePath("MULTIPLE_FILES")
                    .lineNumber(0)
                    .severity("WARNING")
                    .category("AI_LIKELIHOOD")
                    .message("High volume of changes detected. This is a common pattern in AI-generated PRs.")
                    .source("HEURISTIC")
                    .confidence(0.75)
                    .precedenceScore(550)
                    .build());
        }

        return findings;
    }

    @Override
    public String getName() {
        return "DiffShapeRule";
    }
}