package com.bot.bot.analysis;

import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import com.bot.bot.domain.TriageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryGenerator {

    private static final Pattern MD_H1_PATTERN = Pattern.compile("^#\\s+([^#\\n]+)");
    private static final Pattern MD_H2_PATTERN = Pattern.compile("^##\\s+([^#\\n]+)");
    private static final Pattern MD_H3_PATTERN = Pattern.compile("^###\\s+([^#\\n]+)");
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?:public\\s+|protected\\s+|private\\s+)?(?:abstract\\s+|final\\s+)?(?:class|interface|record|enum)\\s+([A-Za-z0-9_]+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("(?:public|protected|private|static|async|def|function|fun)\\s+(?:[\\w<>\\[\\],?]+\\s+)?([A-Za-z0-9_]+)\\s*\\(");
    private static final Pattern ENDPOINT_PATTERN = Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern TEST_METHOD_PATTERN = Pattern.compile("(?:void|def)\\s+(test[A-Za-z0-9_]*|[a-z0-9_]+Test|[a-z0-9_]+_test)\\s*\\(");

    private static final Set<String> IGNORED_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "return", "new", "super", "this",
            "class", "interface", "record", "enum", "try", "throw", "throws", "else", "finally"
    );

    private static final List<String> DOC_SUBJECT_FOLDERS = List.of(
            "subjects", "subject", "topics", "topic", "notes", "note", "docs", "doc",
            "courses", "course", "chapters", "chapter", "modules", "module", "tutorials",
            "tutorial", "content", "questions", "problems", "guides", "guide", "dsa",
            "cs-fundamentals", "placement-preparation"
    );

    /**
     * Generates a structured summary for a PR based on findings and context.
     *
     * @param prContext The PR context containing metadata
     * @param findings List of findings from analysis engines
     * @return Structured summary as a formatted string
     */
    public String generateSummary(PullRequestContext prContext, List<Finding> findings) {
        // Extract AI-likelihood from findings
        String aiLikelihood = extractAILikelihood(findings);

        // Extract risk level from findings
        String riskLevel = extractRiskLevel(findings);

        // Extract quality signal from findings
        String qualitySignal = extractQualitySignal(findings);

        // Use computeTier for recommendation
        TriageResult triage = computeTier(findings, prContext);
        String recommendation = tierToRecommendation(triage);

        String before = (prContext != null && prContext.getChangeSummaryBefore() != null && !prContext.getChangeSummaryBefore().isBlank())
                ? prContext.getChangeSummaryBefore()
                : "No previous code state documented.";
        String after = (prContext != null && prContext.getChangeSummaryAfter() != null && !prContext.getChangeSummaryAfter().isBlank())
                ? prContext.getChangeSummaryAfter()
                : "Code modifications introduced.";

        String featureSummary = extractSemanticFeatureSummary(prContext, after);

        String authorReputation = (prContext != null && prContext.getAuthorReputation() != null)
                ? prContext.getAuthorReputation()
                : "FIRST_TIME_CONTRIBUTOR";
        String authorDetail = (prContext != null && prContext.getAuthorReputationDetail() != null && !prContext.getAuthorReputationDetail().isBlank())
                ? " (" + prContext.getAuthorReputationDetail() + ")"
                : "";
        String authorLogin = prContext != null && prContext.getAuthorLogin() != null ? prContext.getAuthorLogin() : "unknown";
        String contributorContext = "@" + authorLogin + " [" + formatReputationLabel(authorReputation) + "]" + authorDetail;

        String repoContext = (prContext != null && prContext.getRepoContext() != null && !prContext.getRepoContext().isBlank())
                ? prContext.getRepoContext()
                : ((prContext != null ? prContext.getOwner() + "/" + prContext.getRepo() : "Repository"));

        String execSummary = (prContext != null && prContext.getExecutiveSummary() != null && !prContext.getExecutiveSummary().isBlank())
                ? prContext.getExecutiveSummary()
                : extractPurpose(findings, prContext);

        String functionalChangesStr;
        if (prContext != null && prContext.getFunctionalChanges() != null && !prContext.getFunctionalChanges().isEmpty()) {
            functionalChangesStr = prContext.getFunctionalChanges().stream().map(c -> "- " + c).collect(Collectors.joining("\n"));
        } else {
            functionalChangesStr = "- " + after;
        }

        String whatToEditOrAddStr;
        if (prContext != null && prContext.getWhatToEditOrAdd() != null && !prContext.getWhatToEditOrAdd().isEmpty()) {
            whatToEditOrAddStr = prContext.getWhatToEditOrAdd().stream().map(e -> "- " + e).collect(Collectors.joining("\n"));
        } else {
            whatToEditOrAddStr = "- No additional edits required — change is complete.";
        }

        String decisionRationaleStr = (prContext != null && prContext.getDecisionRationale() != null && !prContext.getDecisionRationale().isBlank())
                ? prContext.getDecisionRationale()
                : recommendation;

        // Build the summary
        return String.format(
                """
Title: %s
Purpose: %s
Executive Summary: %s
Feature Summary: %s
What Was Before: %s
What Changed: %s
Functional Changes:
%s
What to Add or Edit:
%s
Contributor Context: %s
Repository Context: %s
Scope: %s
Risk: %s
AI-likelihood: %s - %s
Quality signal: %s
Recommendation: %s
Decision Rationale: %s
""",
                prContext != null ? prContext.getTitle() : "Pull Request",
                extractPurpose(findings, prContext),
                execSummary,
                featureSummary,
                before,
                after,
                functionalChangesStr,
                whatToEditOrAddStr,
                contributorContext,
                repoContext,
                extractScope(findings, prContext),
                riskLevel,
                aiLikelihood,
                extractAILikelihoodReason(findings),
                qualitySignal,
                recommendation,
                decisionRationaleStr
        );
    }

    public String extractWhatWasBefore(List<ChangeChunk> chunks, PullRequestContext prContext) {
        if (chunks == null || chunks.isEmpty()) {
            return "No previous code modifications detected (clean addition or metadata update).";
        }

        List<String> removedSnippets = new ArrayList<>();
        int totalRemoved = 0;
        int filesWithDeletions = 0;

        for (ChangeChunk chunk : chunks) {
            if (chunk.getRemovedLines() != null && !chunk.getRemovedLines().isEmpty()) {
                filesWithDeletions++;
                totalRemoved += chunk.getRemovedLines().size();
                for (String line : chunk.getRemovedLines()) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")) {
                        if (removedSnippets.size() < 3) {
                            removedSnippets.add(trimmed.length() > 60 ? trimmed.substring(0, 57) + "..." : trimmed);
                        }
                    }
                }
            }
        }

        if (totalRemoved == 0) {
            return "Clean addition: new functionality introduced without replacing existing code.";
        }

        String snippetText = removedSnippets.isEmpty() ? "" : " (prior code included: `" + String.join("`, `", removedSnippets) + "`)";
        return String.format("Previously had %d line(s) across %d file(s) that were modified or removed%s.",
                totalRemoved, filesWithDeletions, snippetText);
    }

    public String extractWhatChanged(List<ChangeChunk> chunks, PullRequestContext prContext) {
        if (chunks == null || chunks.isEmpty()) {
            return prContext != null && prContext.getTitle() != null ? prContext.getTitle() : "Code updates submitted.";
        }

        String narrative = extractSemanticFeatureNarrative(chunks, prContext);

        List<String> addedSnippets = new ArrayList<>();
        int totalAdded = 0;
        int filesWithAdditions = 0;

        for (ChangeChunk chunk : chunks) {
            if (chunk.getAddedLines() != null && !chunk.getAddedLines().isEmpty()) {
                filesWithAdditions++;
                totalAdded += chunk.getAddedLines().size();
                for (String line : chunk.getAddedLines()) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*") && !trimmed.startsWith("#")) {
                        if (addedSnippets.size() < 3) {
                            addedSnippets.add(trimmed.length() > 60 ? trimmed.substring(0, 57) + "..." : trimmed);
                        }
                    }
                }
            }
        }

        int fileCount = filesWithAdditions > 0 ? filesWithAdditions : (prContext != null && prContext.getFilesChanged() != null ? prContext.getFilesChanged().size() : 1);
        String snippetText = addedSnippets.isEmpty() ? "" : " (introduced: `" + String.join("`, `", addedSnippets) + "`)";
        String statsText = String.format("Added %d line(s) of new implementation across %d file(s)%s.",
                totalAdded,
                fileCount,
                snippetText);

        if (narrative != null && !narrative.isBlank()) {
            return narrative + " - " + statsText;
        }

        return statsText;
    }

    /**
     * Synthesizes a natural human-readable narrative of what feature, file, subject or logic
     * was introduced by the contributor.
     */
    public String extractSemanticFeatureNarrative(List<ChangeChunk> chunks, PullRequestContext prContext) {
        if (chunks == null || chunks.isEmpty()) {
            if (prContext != null && prContext.getTitle() != null && !prContext.getTitle().isBlank()) {
                return "Submitted update: " + prContext.getTitle();
            }
            return "Submitted code modifications.";
        }

        List<String> narratives = new ArrayList<>();

        for (ChangeChunk chunk : chunks) {
            String filePath = chunk.getFilePath();
            if (filePath == null || filePath.isBlank()) continue;

            boolean isAdded = "ADDED".equalsIgnoreCase(chunk.getChangeType());
            List<String> addedLines = chunk.getAddedLines() != null ? chunk.getAddedLines() : List.of();

            // 1. Check for Subject / Tutorial / Educational / Documentation folders
            String subjectFolderMatch = findSubjectOrDocFolder(filePath);
            if (subjectFolderMatch != null) {
                String subjectNarrative = extractSubjectNarrative(filePath, subjectFolderMatch, isAdded, addedLines);
                if (subjectNarrative != null && !subjectNarrative.isBlank()) {
                    narratives.add(subjectNarrative);
                    continue;
                }
            }

            // 2. Check for Code additions (Java, Python, JS/TS, Go, C++, etc.)
            String codeNarrative = extractCodeNarrative(filePath, isAdded, addedLines);
            if (codeNarrative != null && !codeNarrative.isBlank()) {
                narratives.add(codeNarrative);
                continue;
            }

            // 3. Check for Test files
            if (isTestFile(filePath)) {
                String testNarrative = extractTestNarrative(filePath, isAdded, addedLines);
                if (testNarrative != null && !testNarrative.isBlank()) {
                    narratives.add(testNarrative);
                    continue;
                }
            }

            // 4. Check for Config, CI/CD, Documentation
            String configNarrative = extractGeneralFileNarrative(filePath, isAdded, addedLines);
            if (configNarrative != null && !configNarrative.isBlank()) {
                narratives.add(configNarrative);
            }
        }

        if (narratives.isEmpty()) {
            if (prContext != null && prContext.getTitle() != null && !prContext.getTitle().isBlank()) {
                return "Implemented updates for: " + prContext.getTitle();
            }
            return "Submitted code updates.";
        }

        if (narratives.size() == 1) {
            return narratives.get(0);
        } else if (narratives.size() == 2) {
            return narratives.get(0) + " and " + lowercaseFirst(narratives.get(1));
        } else {
            return narratives.get(0) + ", " + lowercaseFirst(narratives.get(1)) + " and " + (narratives.size() - 2) + " other update(s)";
        }
    }

    private String findSubjectOrDocFolder(String filePath) {
        if (filePath == null) return null;
        String normalized = filePath.replace('\\', '/').toLowerCase();
        String[] parts = normalized.split("/");
        for (String part : parts) {
            for (String folder : DOC_SUBJECT_FOLDERS) {
                if (part.equals(folder)) {
                    return folder;
                }
            }
        }
        return null;
    }

    private String extractSubjectNarrative(String filePath, String folderKey, boolean isAdded, List<String> addedLines) {
        String normalized = filePath.replace('\\', '/');
        String[] parts = normalized.split("/");

        int folderIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase(folderKey)) {
                folderIdx = i;
                break;
            }
        }

        String rawSubject = "";
        String subPathTopic = "";
        if (folderIdx >= 0 && folderIdx + 1 < parts.length) {
            rawSubject = parts[folderIdx + 1];
            if (folderIdx + 2 < parts.length) {
                subPathTopic = parts[folderIdx + 2];
            }
        } else {
            rawSubject = parts[parts.length - 1];
        }

        String formattedSubject = formatSubjectOrFileName(rawSubject);

        // Scan added lines for headings
        String detectedH1 = null;
        List<String> h2Topics = new ArrayList<>();
        List<String> h3Topics = new ArrayList<>();

        for (String line : addedLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher m1 = MD_H1_PATTERN.matcher(trimmed);
            if (m1.find() && detectedH1 == null) {
                String h1 = m1.group(1).trim().replaceAll("\\*\\*", "").replaceAll("`", "");
                if (!h1.equalsIgnoreCase("Notes") && !h1.equalsIgnoreCase("README") && !h1.equalsIgnoreCase("Index")) {
                    detectedH1 = h1;
                }
            }

            Matcher m2 = MD_H2_PATTERN.matcher(trimmed);
            if (m2.find()) {
                String h2 = m2.group(1).trim().replaceAll("\\*\\*", "").replaceAll("`", "");
                h2 = h2.replaceFirst("^\\d+(?:\\.\\d+)*\\s*[-.:)]\\s+", "").trim();
                if (!h2.isBlank() && !h2Topics.contains(h2) && h2Topics.size() < 3) {
                    h2Topics.add(h2);
                }
            }

            Matcher m3 = MD_H3_PATTERN.matcher(trimmed);
            if (m3.find()) {
                String h3 = m3.group(1).trim().replaceAll("\\*\\*", "").replaceAll("`", "");
                h3 = h3.replaceFirst("^\\d+(?:\\.\\d+)*\\s*[-.:)]\\s+", "").trim();
                if (!h3.isBlank() && !h3Topics.contains(h3) && h3Topics.size() < 3) {
                    h3Topics.add(h3);
                }
            }
        }

        // Prefer primary H2 section headings; fall back to H3 only if no H2 exists
        List<String> subtopics = !h2Topics.isEmpty() ? h2Topics : h3Topics;

        // If subPathTopic exists and subtopics is empty, use subPathTopic as a topic
        if (subtopics.isEmpty() && !subPathTopic.isBlank()) {
            String formattedSub = formatSubjectOrFileName(subPathTopic);
            if (!formattedSub.isBlank() && !formattedSub.equalsIgnoreCase("Readme")) {
                subtopics.add(formattedSub);
            }
        }

        String subjectName = (detectedH1 != null && !detectedH1.isBlank()) ? detectedH1 : formattedSubject;
        if (subjectName.isBlank()) {
            subjectName = "New Content";
        }

        String folderDisplay = folderKey.toLowerCase();
        String categoryLabel = switch (folderDisplay) {
            case "subjects", "subject" -> "subject";
            case "topics", "topic" -> "topic";
            case "courses", "course" -> "course";
            case "chapters", "chapter" -> "chapter";
            case "modules", "module" -> "module";
            case "tutorials", "tutorial" -> "tutorial";
            case "notes", "note" -> "notes for";
            case "questions", "problems" -> "question/problem";
            case "docs", "doc", "guides", "guide" -> "guide/documentation";
            default -> "section";
        };

        if (isAdded) {
            if (!subtopics.isEmpty()) {
                String topicList = formatListWithAnd(subtopics);
                return String.format("Added one more %s '%s' in %s/ and in %s added '%s'",
                        categoryLabel, subjectName, folderDisplay, subjectName, topicList);
            } else {
                return String.format("Added one more %s '%s' in %s/", categoryLabel, subjectName, folderDisplay);
            }
        } else {
            if (!subtopics.isEmpty()) {
                String topicList = formatListWithAnd(subtopics);
                return String.format("In %s '%s' (%s/), added '%s'",
                        categoryLabel, subjectName, folderDisplay, topicList);
            } else {
                return String.format("Updated %s '%s' in %s/", categoryLabel, subjectName, folderDisplay);
            }
        }
    }

    private String extractCodeNarrative(String filePath, boolean isAdded, List<String> addedLines) {
        String fileName = getSimpleFileName(filePath);
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".java") && !lower.endsWith(".py") && !lower.endsWith(".js")
                && !lower.endsWith(".ts") && !lower.endsWith(".jsx") && !lower.endsWith(".tsx")
                && !lower.endsWith(".go") && !lower.endsWith(".cpp") && !lower.endsWith(".c")
                && !lower.endsWith(".cs") && !lower.endsWith(".rs") && !lower.endsWith(".kt")) {
            return null;
        }

        String detectedClass = null;
        String detectedEndpoint = null;
        List<String> detectedMethods = new ArrayList<>();

        for (String line : addedLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue;

            if (detectedClass == null) {
                Matcher mClass = CLASS_PATTERN.matcher(trimmed);
                if (mClass.find()) {
                    detectedClass = mClass.group(1);
                }
            }

            if (detectedEndpoint == null) {
                Matcher mEnd = ENDPOINT_PATTERN.matcher(trimmed);
                if (mEnd.find()) {
                    detectedEndpoint = mEnd.group(1);
                }
            }

            Matcher mMethod = METHOD_PATTERN.matcher(trimmed);
            if (mMethod.find()) {
                String mName = mMethod.group(1);
                if (!IGNORED_KEYWORDS.contains(mName) && !detectedMethods.contains(mName) && detectedMethods.size() < 2) {
                    detectedMethods.add(mName);
                }
            }
        }

        if (isAdded) {
            if (detectedClass != null && !detectedMethods.isEmpty()) {
                return String.format("Created new file '%s' implementing class '%s' with method(s) '%s()'",
                        fileName, detectedClass, String.join("()', '", detectedMethods));
            } else if (detectedClass != null) {
                return String.format("Created new file '%s' implementing class '%s'", fileName, detectedClass);
            } else if (!detectedMethods.isEmpty()) {
                return String.format("Created new file '%s' adding function(s) '%s()'",
                        fileName, String.join("()', '", detectedMethods));
            } else if (detectedEndpoint != null) {
                return String.format("Created new endpoint '%s' in '%s'", detectedEndpoint, fileName);
            } else {
                return String.format("Created new file '%s'", fileName);
            }
        } else {
            if (detectedEndpoint != null) {
                return String.format("Added endpoint '%s' in '%s'", detectedEndpoint, fileName);
            } else if (!detectedMethods.isEmpty()) {
                return String.format("Added method '%s()' in '%s'", String.join("()', '", detectedMethods), fileName);
            } else if (detectedClass != null) {
                return String.format("Updated class '%s' in '%s'", detectedClass, fileName);
            } else {
                return String.format("Updated logic in '%s'", fileName);
            }
        }
    }

    private boolean isTestFile(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.contains("test") || lower.contains("spec");
    }

    private String extractTestNarrative(String filePath, boolean isAdded, List<String> addedLines) {
        String fileName = getSimpleFileName(filePath);
        List<String> tests = new ArrayList<>();
        for (String line : addedLines) {
            String trimmed = line.trim();
            Matcher m = TEST_METHOD_PATTERN.matcher(trimmed);
            if (m.find()) {
                String tName = m.group(1);
                if (!tests.contains(tName) && tests.size() < 2) {
                    tests.add(tName);
                }
            }
        }
        if (isAdded) {
            if (!tests.isEmpty()) {
                return String.format("Added test suite '%s' covering '%s'", fileName, String.join("', '", tests));
            }
            return String.format("Added test suite '%s'", fileName);
        } else {
            if (!tests.isEmpty()) {
                return String.format("Added test case(s) '%s' in '%s'", String.join("', '", tests), fileName);
            }
            return String.format("Updated tests in '%s'", fileName);
        }
    }

    private String extractGeneralFileNarrative(String filePath, boolean isAdded, List<String> addedLines) {
        String fileName = getSimpleFileName(filePath);
        String lower = fileName.toLowerCase();
        if (lower.contains("dockerfile") || lower.contains("docker-compose")) {
            return (isAdded ? "Added Docker configuration in '" : "Updated Docker configuration in '") + fileName + "'";
        }
        if (filePath.contains(".github/workflows")) {
            return (isAdded ? "Added GitHub Actions CI workflow in '" : "Updated GitHub Actions workflow in '") + fileName + "'";
        }
        if (lower.equals("readme.md")) {
            return "Updated README documentation";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties") || lower.endsWith(".json")) {
            return (isAdded ? "Added configuration file '" : "Updated configuration in '") + fileName + "'";
        }
        if (isAdded) {
            return "Created new file '" + fileName + "'";
        }
        return "Updated '" + fileName + "'";
    }

    private String extractSemanticFeatureSummary(PullRequestContext prContext, String after) {
        if (after != null && !after.isBlank()) {
            int dashIdx = after.indexOf(" - Added ");
            if (dashIdx < 0) {
                dashIdx = after.indexOf(" \u2014 Added ");
            }
            if (dashIdx > 0) {
                return after.substring(0, dashIdx).trim();
            }
            return after;
        }
        if (prContext != null && prContext.getTitle() != null && !prContext.getTitle().isBlank()) {
            return prContext.getTitle();
        }
        return "New feature implementation and code enhancements.";
    }

    public String formatSubjectOrFileName(String raw) {
        if (raw == null || raw.isBlank()) return "";
        int dot = raw.lastIndexOf('.');
        if (dot > 0) {
            raw = raw.substring(0, dot);
        }
        String[] words = raw.split("[-_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isBlank()) continue;
            String upper = w.toUpperCase();
            if (upper.equals("OSI") || upper.equals("TCP") || upper.equals("UDP") || upper.equals("IP")
                    || upper.equals("HTTP") || upper.equals("API") || upper.equals("SQL") || upper.equals("AI")
                    || upper.equals("ML") || upper.equals("DSA") || upper.equals("OOP") || upper.equals("DBMS")
                    || upper.equals("OS") || upper.equals("CN") || upper.equals("PR") || upper.equals("URL")
                    || upper.equals("REST") || upper.equals("JWT")) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(upper);
            } else {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) {
                    sb.append(w.substring(1).toLowerCase());
                }
            }
        }
        return sb.toString();
    }

    private String getSimpleFileName(String filePath) {
        if (filePath == null) return "";
        int idx = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return idx >= 0 ? filePath.substring(idx + 1) : filePath;
    }

    private String formatListWithAnd(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + "' and '" + items.get(1);
        if (items.size() == 3) return items.get(0) + "', '" + items.get(1) + "' and '" + items.get(2);
        return items.get(0) + "', '" + items.get(1) + "' and " + (items.size() - 2) + " other topic(s)";
    }

    private String lowercaseFirst(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    public String formatReputationLabel(String reputation) {
        if (reputation == null) return "First-time Contributor";
        return switch (reputation.toUpperCase()) {
            case "TRUSTED_MAINTAINER" -> "Trusted Maintainer";
            case "COLLABORATOR" -> "Collaborator";
            case "RETURNING_CONTRIBUTOR" -> "Returning Contributor";
            default -> "First-time Contributor";
        };
    }

    /**
     * Extracts the AI-likelihood classification from findings.
     *
     * @param findings List of findings
     * @return AI-likelihood classification (LOW, MEDIUM, HIGH)
     */
    private String extractAILikelihood(List<Finding> findings) {
        if (findings == null) return "UNKNOWN";
        return findings.stream()
                .filter(f -> "AI_LIKELIHOOD".equals(f.getCategory()))
                .findFirst()
                .map(Finding::getMessage)
                .map(message -> {
                    if (message.contains("LOW") || message.contains("HUMAN-WRITTEN")) {
                        return "LOW";
                    } else if (message.contains("MEDIUM") || message.contains("POSSIBLE AI")) {
                        return "MEDIUM";
                    } else if (message.contains("HIGH") || message.contains("AI-GENERATED")) {
                        return "HIGH";
                    }
                    return "UNKNOWN";
                }).orElse("UNKNOWN");
    }

    /**
     * Extracts the reasoning for AI-likelihood classification.
     *
     * @param findings List of findings
     * @return Reasoning for AI-likelihood classification
     */
    private String extractAILikelihoodReason(List<Finding> findings) {
        if (findings == null) return "No AI-likelihood assessment found.";
        return findings.stream()
                .filter(f -> "AI_LIKELIHOOD".equals(f.getCategory()))
                .findFirst()
                .map(Finding::getMessage)
                .map(message -> {
                    // Extract reasoning after the classification
                    String[] parts = message.split("-", 2);
                    if (parts.length > 1) {
                        return parts[1].trim();
                    }
                    return "No reasoning provided.";
                }).orElse("No AI-likelihood assessment found.");
    }

    /**
     * Extracts the risk level from findings.
     *
     * @param findings List of findings
     * @return Risk level (LOW, MEDIUM, HIGH, CRITICAL)
     */
    private String extractRiskLevel(List<Finding> findings) {
        if (findings == null) return "LOW";
        return findings.stream()
                .filter(f -> "CRITICAL".equals(f.getSeverity()))
                .findFirst()
                .map(f -> "CRITICAL")
                .orElseGet(() -> {
                    return findings.stream()
                            .filter(f -> "HIGH".equals(f.getSeverity()))
                            .findFirst()
                            .map(f -> "HIGH")
                            .orElseGet(() -> {
                                return findings.stream()
                                        .filter(f -> "MEDIUM".equals(f.getSeverity()))
                                        .findFirst()
                                        .map(f -> "MEDIUM")
                                        .orElse("LOW");
                            });
                });
    }

    /**
     * Extracts the quality signal from findings.
     *
     * @param findings List of findings
     * @return Quality signal (e.g., "Includes tests", "Follows repo conventions")
     */
    private String extractQualitySignal(List<Finding> findings) {
        if (findings == null) return "No specific quality signal found.";

        boolean hasCriticalBug = findings.stream()
                .anyMatch(f -> "BUG_DETECTION".equalsIgnoreCase(f.getCategory()) && "CRITICAL".equalsIgnoreCase(f.getSeverity()));
        boolean hasBugs = findings.stream()
                .anyMatch(f -> "BUG_DETECTION".equalsIgnoreCase(f.getCategory()));

        if (hasCriticalBug) {
            long count = findings.stream().filter(f -> "BUG_DETECTION".equalsIgnoreCase(f.getCategory())).count();
            return count + " code bug(s) detected, including CRITICAL issues. Fixes required.";
        } else if (hasBugs) {
            long count = findings.stream().filter(f -> "BUG_DETECTION".equalsIgnoreCase(f.getCategory())).count();
            return count + " potential code bug(s) detected. Review recommended.";
        }

        // Check for positive observations
        List<String> positiveObservations = findings.stream()
                .filter(f -> "POSITIVE_OBSERVATION".equals(f.getCategory()))
                .map(Finding::getMessage)
                .collect(Collectors.toList());

        if (!positiveObservations.isEmpty()) {
            return "Includes tests, follows repo conventions, and other positive observations.";
        }

        // Default quality signal
        return "No specific quality signal found.";
    }

    /**
     * Extracts the purpose of the PR from context or findings.
     *
     * @param findings List of findings
     * @param prContext The PR context
     * @return Purpose of the PR
     */
    private String extractPurpose(List<Finding> findings, PullRequestContext prContext) {
        if (prContext != null && prContext.getDescription() != null && !prContext.getDescription().isEmpty()) {
            return prContext.getDescription();
        }

        // If no description, try to infer from findings
        if (findings != null && !findings.isEmpty()) {
            return "Change inferred from code analysis.";
        }

        return "No description available.";
    }

    /**
     * Extracts the scope of the PR.
     *
     * @param findings List of findings
     * @param prContext The PR context
     * @return Scope of the PR
     */
    private String extractScope(List<Finding> findings, PullRequestContext prContext) {
        if (prContext == null || prContext.getFilesChanged() == null) {
            return "small files: none";
        }
        // Count unique files changed
        int uniqueFiles = prContext.getFilesChanged().size();

        // Determine scope size
        String scopeSize = "small";
        if (uniqueFiles > 5) {
            scopeSize = "medium";
        }
        if (uniqueFiles > 10) {
            scopeSize = "large";
        }

        // Get list of files
        String filesList = String.join(", ", prContext.getFilesChanged());

        return String.format("%s files: %s", scopeSize, filesList);
    }

    /**
     * Computes the triage tier for a PR based on SRS §5 criteria.
     *
     * @param findings  list of analysis findings (nullable)
     * @param ctx       pull request context with filesChanged and metadata
     * @return TriageResult with tier, securityFlag, and suggestedAction
     */
    public TriageResult computeTier(List<Finding> findings, PullRequestContext ctx) {
        boolean findingsProvided = findings != null;
        if (findings == null) findings = List.of();
        List<String> filesChanged = (ctx != null && ctx.getFilesChanged() != null) ? ctx.getFilesChanged() : List.of();

        // Signal extraction
        String aiLikelihood = extractAILikelihood(findings);

        boolean hasSecurityFinding = findings.stream()
                .anyMatch(f -> "SECURITY".equals(f.getCategory()));

        boolean hasTemplatedSignal = findings.stream()
                .anyMatch(f -> "AI_LIKELIHOOD".equals(f.getCategory())
                        && f.getMessage() != null
                        && f.getMessage().toLowerCase().contains("boilerplate"));

        boolean sweepingUnrelated = filesChanged.size() > 10;

        boolean noClearIntent = (ctx == null || ctx.getDescription() == null || ctx.getDescription().isBlank());

        boolean hasTests = findings.stream()
                .anyMatch(f -> "POSITIVE_OBSERVATION".equals(f.getCategory())
                        && f.getMessage() != null
                        && f.getMessage().toLowerCase().contains("test"))
                || filesChanged.stream().anyMatch(f -> {
                    String lower = f.toLowerCase();
                    return lower.contains("test") || lower.contains("spec");
                });

        boolean isCoherent = !noClearIntent && !sweepingUnrelated;

        boolean hasCriticalBug = findings.stream()
                .anyMatch(f -> "BUG_DETECTION".equalsIgnoreCase(f.getCategory())
                        && "CRITICAL".equalsIgnoreCase(f.getSeverity()));

        boolean hasBug = findings.stream()
                .anyMatch(f -> "BUG_DETECTION".equalsIgnoreCase(f.getCategory()));

        boolean hasRuleViolations = findings.stream().anyMatch(f -> {
            String cat = f.getCategory() != null ? f.getCategory().toUpperCase() : "";
            if ("POSITIVE_OBSERVATION".equals(cat)) return false;
            if ("AI_LIKELIHOOD".equals(cat)) {
                return !"LOW".equals(extractAILikelihood(List.of(f)));
            }
            String sev = f.getSeverity() != null ? f.getSeverity().toUpperCase() : "INFO";
            return "CRITICAL".equals(sev) || "HIGH".equals(sev) || "MEDIUM".equals(sev)
                    || "BUG_DETECTION".equals(cat) || "SECURITY".equals(cat);
        });

        // RED: templated + sweeping-unrelated changes + no clear intent, OR critical code bug detected
        if ((hasTemplatedSignal && sweepingUnrelated && noClearIntent) || hasCriticalBug) {
            return new TriageResult(TriageResult.Tier.RED, hasSecurityFinding,
                    TriageResult.SuggestedAction.CONSIDER_CLOSING);
        }

        /*
         * Why GREEN was previously nearly unreachable:
         * Previously, reaching GREEN required `"LOW".equals(aiLikelihood)`.
         * However, `extractAILikelihood` only evaluates to "LOW" when an external LLM
         * review engine is enabled, succeeds, and explicitly generates an AI_LIKELIHOOD finding
         * containing "LOW" or "HUMAN-WRITTEN".
         * When the LLM is unconfigured, disabled, times out, or runs in fallback/offline mode,
         * `extractAILikelihood` returns "UNKNOWN". Because "UNKNOWN" never matched "LOW",
         * GREEN was completely unreachable for any clean PR in fallback or offline environments,
         * erroneously forcing them to fall through to YELLOW.
         *
         * Minimal fix: Allow GREEN when signals are clean:
         * Either the LLM explicitly confirms LOW AI-likelihood, OR the LLM is in fallback/unavailable ("UNKNOWN")
         * with clean signals: findings were provided, diff is small & coherent (isCoherent), tests exist (hasTests),
         * no bugs (!hasBug), no security flags (!hasSecurityFinding), and no negative rule violations (!hasRuleViolations).
         */
        boolean isAiCleanOrFallback = "LOW".equals(aiLikelihood)
                || ("UNKNOWN".equals(aiLikelihood) && findingsProvided && !hasSecurityFinding && !hasRuleViolations);

        // GREEN: coherent + low AI-likelihood (or clean fallback) + has tests + no code bugs
        if (isCoherent && isAiCleanOrFallback && hasTests && !hasBug) {
            return new TriageResult(TriageResult.Tier.GREEN, hasSecurityFinding,
                    TriageResult.SuggestedAction.REVIEW_AND_MERGE);
        }

        // YELLOW: everything else (mixed/complex/off-topic/moderate AI or non-critical bugs)
        return new TriageResult(TriageResult.Tier.YELLOW, hasSecurityFinding,
                TriageResult.SuggestedAction.MANUAL_CHECK);
    }

    private String tierToRecommendation(TriageResult triage) {
        if (triage == null || triage.tier() == null) {
            return "Needs human review.";
        }
        return switch (triage.tier()) {
            case GREEN -> "Merge-worthy.";
            case YELLOW -> "Needs human review.";
            case RED -> "Requires immediate review.";
        };
    }

    /**
     * Enriches the PullRequestContext with deep semantic and functional analysis of the changes:
     * - Executive Summary & Motivation
     * - Functional Breakdown (What functionality was added or edited)
     * - What to Add or Edit (Actionable contributor items & gap analysis)
     * - Maintainer Decision Recommendation & Rationale
     */
    public void enrichContextWithFunctionalAnalysis(PullRequestContext prContext, List<ChangeChunk> chunks, List<Finding> findings) {
        if (prContext == null) return;
        if (chunks == null) chunks = List.of();
        if (findings == null) findings = List.of();

        // 1. Ensure Before / After descriptions
        if (prContext.getChangeSummaryBefore() == null || prContext.getChangeSummaryBefore().isBlank()) {
            prContext.setChangeSummaryBefore(extractWhatWasBefore(chunks, prContext));
        }
        if (prContext.getChangeSummaryAfter() == null || prContext.getChangeSummaryAfter().isBlank()) {
            prContext.setChangeSummaryAfter(extractWhatChanged(chunks, prContext));
        }

        // 2. Derive Executive Summary if not already set by LLM
        if (prContext.getExecutiveSummary() == null || prContext.getExecutiveSummary().isBlank()) {
            prContext.setExecutiveSummary(deriveExecutiveSummary(chunks, prContext));
        }

        // 3. Derive Functional Changes if not already set by LLM
        if (prContext.getFunctionalChanges() == null || prContext.getFunctionalChanges().isEmpty()) {
            prContext.setFunctionalChanges(deriveFunctionalChanges(chunks, prContext));
        }

        // 4. Derive What to Add or Edit (Gap Analysis) if not already set by LLM
        TriageResult triage = prContext.getTriageResult() != null ? prContext.getTriageResult() : computeTier(findings, prContext);
        if (prContext.getWhatToEditOrAdd() == null || prContext.getWhatToEditOrAdd().isEmpty()) {
            prContext.setWhatToEditOrAdd(deriveWhatToEditOrAdd(chunks, prContext, findings, triage));
        }

        // 5. Derive Decision Recommendation & Rationale if not already set by LLM
        if (prContext.getDecisionRecommendation() == null || prContext.getDecisionRecommendation().isBlank()) {
            DecisionInfo decisionInfo = deriveDecisionRecommendation(triage, prContext.getWhatToEditOrAdd(), findings);
            prContext.setDecisionRecommendation(decisionInfo.recommendation());
            prContext.setDecisionRationale(decisionInfo.rationale());
        }
    }

    public record DecisionInfo(String recommendation, String rationale) {}

    public String deriveExecutiveSummary(List<ChangeChunk> chunks, PullRequestContext prContext) {
        String title = prContext != null && prContext.getTitle() != null ? prContext.getTitle().trim() : "";
        String desc = prContext != null && prContext.getDescription() != null ? prContext.getDescription().trim() : "";

        // Check if test PR
        boolean onlyTests = chunks != null && !chunks.isEmpty() && chunks.stream().allMatch(c -> isTestFile(c.getFilePath()));
        if (chunks != null && onlyTests) {
            List<String> testNames = chunks.stream().map(c -> getSimpleFileName(c.getFilePath())).distinct().toList();
            return String.format("This pull request introduces automated test coverage for %s, validating core domain behaviors and edge cases without affecting production runtime logic.",
                    String.join(", ", testNames));
        }

        // Check for educational / docs content
        String docNarrative = extractSemanticFeatureNarrative(chunks, prContext);
        if (docNarrative != null && (docNarrative.contains("subject") || docNarrative.contains("course") || docNarrative.contains("topic") || docNarrative.contains("notes for"))) {
            return String.format("This pull request updates repository documentation and educational resources: %s.", docNarrative);
        }

        // Check if description has good explanation
        if (desc.length() > 20 && !desc.equalsIgnoreCase("No description") && !desc.equalsIgnoreCase("No description available.")) {
            return desc;
        }

        if (!title.isBlank()) {
            return String.format("This pull request implements '%s'. %s", title,
                    (docNarrative != null && !docNarrative.isBlank() ? docNarrative + "." : "Enhances code implementation and system capabilities."));
        }

        return "This pull request submits code modifications to enhance functionality and maintainability across the repository.";
    }

    public List<String> deriveFunctionalChanges(List<ChangeChunk> chunks, PullRequestContext prContext) {
        List<String> changes = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) {
            if (prContext != null && prContext.getTitle() != null) {
                changes.add("Implemented updates: " + prContext.getTitle());
            } else {
                changes.add("Code enhancements introduced.");
            }
            return changes;
        }

        // Group files by type / role
        for (ChangeChunk chunk : chunks) {
            String filePath = chunk.getFilePath();
            if (filePath == null) continue;
            String fileName = getSimpleFileName(filePath);
            boolean isAdded = "ADDED".equalsIgnoreCase(chunk.getChangeType());
            List<String> addedLines = chunk.getAddedLines() != null ? chunk.getAddedLines() : List.of();

            // Detect endpoints
            List<String> endpoints = new ArrayList<>();
            for (String l : addedLines) {
                Matcher m = ENDPOINT_PATTERN.matcher(l);
                if (m.find()) {
                    endpoints.add(m.group(1));
                }
            }

            // Detect methods
            List<String> methods = new ArrayList<>();
            for (String l : addedLines) {
                Matcher m = METHOD_PATTERN.matcher(l);
                if (m.find()) {
                    String name = m.group(1);
                    if (!IGNORED_KEYWORDS.contains(name) && !methods.contains(name) && methods.size() < 4) {
                        methods.add(name);
                    }
                }
            }

            // Detect classes / records / interfaces
            String detectedClass = null;
            for (String l : addedLines) {
                Matcher m = CLASS_PATTERN.matcher(l);
                if (m.find()) {
                    detectedClass = m.group(1);
                    break;
                }
            }

            if (isTestFile(filePath)) {
                List<String> testCases = new ArrayList<>();
                for (String l : addedLines) {
                    Matcher m = TEST_METHOD_PATTERN.matcher(l);
                    if (m.find()) {
                        testCases.add(m.group(1));
                    }
                }
                if (isAdded) {
                    changes.add("Added test suite '" + fileName + "'" + (testCases.isEmpty() ? "" : " with test cases: " + String.join(", ", testCases)));
                } else {
                    changes.add("Updated test suite '" + fileName + "'" + (testCases.isEmpty() ? "" : " added test case(s): " + String.join(", ", testCases)));
                }
            } else if (!endpoints.isEmpty()) {
                changes.add((isAdded ? "Created new API controller '" : "Updated API controller '") + fileName + "' exposing endpoint(s): " + String.join(", ", endpoints));
            } else if (detectedClass != null) {
                String methodList = methods.isEmpty() ? "" : " with method(s): " + String.join("(), ", methods) + "()";
                changes.add((isAdded ? "Implemented new component '" : "Updated component '") + detectedClass + "' in '" + fileName + "'" + methodList);
            } else if (!methods.isEmpty()) {
                changes.add((isAdded ? "Created '" : "Updated '") + fileName + "' introducing function(s): " + String.join("(), ", methods) + "()");
            } else {
                String general = extractGeneralFileNarrative(filePath, isAdded, addedLines);
                if (general != null && !general.isBlank()) {
                    changes.add(general);
                } else {
                    changes.add((isAdded ? "Added new file '" : "Modified '") + fileName + "'");
                }
            }
        }

        // Deduplicate and cap to top 6 items
        return changes.stream().distinct().limit(6).collect(Collectors.toList());
    }

    public List<String> deriveWhatToEditOrAdd(List<ChangeChunk> chunks, PullRequestContext prContext,
                                             List<Finding> findings, TriageResult triage) {
        List<String> items = new ArrayList<>();

        // 1. Critical or High bugs detected
        if (findings != null) {
            for (Finding f : findings) {
                if ("BUG_DETECTION".equalsIgnoreCase(f.getCategory()) || "SECURITY".equalsIgnoreCase(f.getCategory())) {
                    String sev = f.getSeverity() != null ? f.getSeverity().toUpperCase() : "HIGH";
                    if ("CRITICAL".equals(sev) || "HIGH".equals(sev)) {
                        String msg = f.getMessage() != null ? f.getMessage() : "Issue detected";
                        String sugg = f.getSuggestion() != null && !f.getSuggestion().isBlank() ? " — Fix: " + f.getSuggestion() : "";
                        String file = f.getFilePath() != null ? f.getFilePath() : "codebase";
                        items.add("Fix " + sev + " issue in '" + file + "': " + msg + sugg);
                    }
                }
            }
        }

        // 2. Check for missing test coverage when new production code is introduced
        if (chunks != null && !chunks.isEmpty()) {
            boolean hasProdChanges = chunks.stream().anyMatch(c -> !isTestFile(c.getFilePath()) && isCodeFile(c.getFilePath()));
            boolean hasTestChanges = chunks.stream().anyMatch(c -> isTestFile(c.getFilePath()));

            if (hasProdChanges && !hasTestChanges) {
                List<String> prodFiles = chunks.stream()
                        .filter(c -> !isTestFile(c.getFilePath()) && isCodeFile(c.getFilePath()))
                        .map(c -> getSimpleFileName(c.getFilePath()))
                        .limit(3)
                        .toList();
                items.add("Add unit test coverage: Newly introduced production logic in '" + String.join("', '", prodFiles)
                        + "' lacks unit tests. Request the contributor to add unit tests covering positive and edge-case execution paths.");
            }
        }

        // 3. Check for input validation on new public endpoints or methods
        if (chunks != null) {
            boolean hasPublicMethods = chunks.stream().anyMatch(c -> {
                if (isTestFile(c.getFilePath())) return false;
                List<String> added = c.getAddedLines() != null ? c.getAddedLines() : List.of();
                return added.stream().anyMatch(l -> l.contains("public ") && l.contains("(") && !l.contains("class ") && !l.contains("interface "));
            });
            boolean hasValidation = chunks.stream().anyMatch(c -> {
                List<String> added = c.getAddedLines() != null ? c.getAddedLines() : List.of();
                return added.stream().anyMatch(l -> l.contains("@Valid") || l.contains("requireNonNull") || l.contains("IllegalArgumentException") || (l.contains("if (") && l.contains("== null")));
            });
            if (hasPublicMethods && !hasValidation && items.size() < 3) {
                items.add("Verify input validation: Ensure defensive null-checks and parameter validation constraints are present for public entry points.");
            }
        }

        // 4. If nothing needed, confirm complete
        if (items.isEmpty()) {
            items.add("No additional edits required — the implementation is complete, well-tested, and ready to merge.");
        }

        return items;
    }

    private boolean isCodeFile(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js")
                || lower.endsWith(".ts") || lower.endsWith(".go") || lower.endsWith(".cpp")
                || lower.endsWith(".rs") || lower.endsWith(".kt") || lower.endsWith(".cs");
    }

    public DecisionInfo deriveDecisionRecommendation(TriageResult triage, List<String> whatToEditOrAdd, List<Finding> findings) {
        if (triage != null && (triage.tier() == TriageResult.Tier.RED || triage.securityFlag())) {
            return new DecisionInfo(
                    "REJECT OR REQUIRE BLOCKING FIXES",
                    "High risk or critical security concerns were detected. Do not merge until all blocking issues and vulnerabilities are remediated."
            );
        }

        boolean hasBlockingEdits = whatToEditOrAdd != null && whatToEditOrAdd.stream().anyMatch(item ->
                item.toLowerCase().contains("fix ") || item.toLowerCase().contains("missing unit test") || item.toLowerCase().contains("lacks unit tests")
        );

        if (hasBlockingEdits || (triage != null && triage.tier() == TriageResult.Tier.YELLOW)) {
            return new DecisionInfo(
                    "REQUEST SPECIFIC CHANGES",
                    "The contribution provides good functional value, but required edits (unit tests and input validations detailed above) should be requested from the author prior to merging."
            );
        }

        return new DecisionInfo(
                "APPROVE & MERGE",
                "All automated checks passed cleanly. Changes are well-structured, accompanied by test verification, and introduce zero regressions. Safe to merge."
        );
    }
}