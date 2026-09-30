package com.bot.bot.analysis.heuristics;

import com.bot.bot.analysis.Rule;
import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import com.bot.bot.domain.PullRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic Code Bug Detection Rule.
 * Analyzes code diff chunks for common, high-confidence programming bugs,
 * logic flaws, null dereferences, resource leaks, and off-by-one errors across languages.
 */
@Slf4j
@Component
public class BugDetectionRule implements Rule {

    private static final Pattern STRING_EQUALS_JAVA = Pattern.compile(
            "(\"[^\"]*\"\\s*(==|!=)|(==|!=)\\s*\"[^\"]*\")"
    );

    private static final Pattern ACCIDENTAL_ASSIGNMENT = Pattern.compile(
            "\\bif\\s*\\(\\s*([a-zA-Z0-9_$.]+)\\s*=\\s*(true|false|\\d+|\"[^\"]*\")\\s*\\)"
    );

    private static final Pattern SWALLOWED_EXCEPTION_BLOCK = Pattern.compile(
            "catch\\s*\\([^)]*\\)\\s*\\{\\s*(//[^\n]*)?\\}"
    );

    private static final Pattern PYTHON_PASS_EXCEPT = Pattern.compile(
            "^\\s*except(\\s+[^:]*)?:\\s*pass\\s*$"
    );

    private static final Pattern OFF_BY_ONE_LOOP = Pattern.compile(
            "for\\s*\\([^;]+;\\s*([a-zA-Z0-9_]+)\\s*<=\\s*([a-zA-Z0-9_$.]+)\\.(length|size\\(\\))\\s*;"
    );

    private static final Pattern INFINITE_LOOP_STEP_INC = Pattern.compile(
            "for\\s*\\([^;]*;\\s*([a-zA-Z0-9_]+)\\s*<\\s*[^;]+;\\s*\\1--\\s*\\)"
    );

    private static final Pattern INFINITE_LOOP_STEP_DEC = Pattern.compile(
            "for\\s*\\([^;]*;\\s*([a-zA-Z0-9_]+)\\s*>\\s*[^;]+;\\s*\\1\\+\\+\\s*\\)"
    );

    private static final Pattern NULL_DEREF_IMMEDIATE = Pattern.compile(
            "if\\s*\\(\\s*([a-zA-Z0-9_]+)\\s*==\\s*null\\s*\\)\\s*\\{?\\s*\\1\\."
    );

    private static final Pattern STRAY_SEMICOLON = Pattern.compile(
            "\\b(if|while|for)\\s*\\([^)]+\\)\\s*;(?![a-zA-Z0-9_])"
    );

    private static final Pattern UNCLOSED_RESOURCE_JAVA = Pattern.compile(
            "\\b(FileInputStream|FileOutputStream|FileReader|FileWriter|BufferedReader|BufferedWriter|Socket|ServerSocket)\\s+([a-zA-Z0-9_]+)\\s*=\\s*new\\s+"
    );

    private static final Pattern SELF_ASSIGNMENT = Pattern.compile(
            "\\b([a-zA-Z0-9_]+)\\s*=\\s*\\1\\s*;|this\\.([a-zA-Z0-9_]+)\\s*=\\s*this\\.\\2\\s*;"
    );

    @Override
    public String getName() {
        return "BugDetectionRule";
    }

    @Override
    public List<Finding> analyze(List<ChangeChunk> chunks) {
        return analyze(chunks, null);
    }

    @Override
    public List<Finding> analyze(List<ChangeChunk> chunks, PullRequestContext prContext) {
        List<Finding> findings = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) {
            return findings;
        }

        for (ChangeChunk chunk : chunks) {
            String path = chunk.getFilePath();
            if (path == null) continue;

            String lowerPath = path.toLowerCase();
            boolean isJava = lowerPath.endsWith(".java");
            boolean isJsTs = lowerPath.endsWith(".js") || lowerPath.endsWith(".ts") || lowerPath.endsWith(".jsx") || lowerPath.endsWith(".tsx");
            boolean isPython = lowerPath.endsWith(".py");
            boolean isCodeFile = isJava || isJsTs || isPython || lowerPath.endsWith(".cs") || lowerPath.endsWith(".go") || lowerPath.endsWith(".cpp") || lowerPath.endsWith(".c");

            if (!isCodeFile) continue;

            List<String> addedLines = chunk.getAddedLines();
            if (addedLines == null || addedLines.isEmpty()) continue;

            int startLine = chunk.getStartLine() > 0 ? chunk.getStartLine() : 1;

            for (int i = 0; i < addedLines.size(); i++) {
                String line = addedLines.get(i);
                if (line == null) continue;
                String trimmed = line.trim();

                // Skip pure comments
                if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }

                int currentLineNum = startLine + i;

                // 1. Accidental assignment in if condition (All languages)
                Matcher assignMatcher = ACCIDENTAL_ASSIGNMENT.matcher(trimmed);
                if (assignMatcher.find()) {
                    findings.add(createFinding(path, currentLineNum, "CRITICAL",
                            "Accidental assignment in conditional statement ('=' instead of '==' or '==='). The condition will always evaluate to the assigned value.",
                            "Replace '=' with '==' or '===' to test equality.", 750));
                }

                // 2. Off-by-one loop boundary error (<= length/size)
                Matcher offByOneMatcher = OFF_BY_ONE_LOOP.matcher(trimmed);
                if (offByOneMatcher.find()) {
                    findings.add(createFinding(path, currentLineNum, "CRITICAL",
                            "Off-by-one error: Loop condition '<=' with length/size will cause ArrayIndexOutOfBoundsException or IndexOutOfBoundsException on the final iteration.",
                            "Change '<=' to '<' in the loop termination condition.", 750));
                }

                // 3. Infinite loop step errors (Inverted increment/decrement)
                if (INFINITE_LOOP_STEP_INC.matcher(trimmed).find() || INFINITE_LOOP_STEP_DEC.matcher(trimmed).find()) {
                    findings.add(createFinding(path, currentLineNum, "CRITICAL",
                            "Infinite loop detected: loop termination condition will never be reached because counter increment/decrement direction is inverted.",
                            "Fix the loop step expression (e.g. use 'i++' when counting up to a limit).", 780));
                }

                // 4. Null pointer dereference right after null check
                if (NULL_DEREF_IMMEDIATE.matcher(trimmed).find()) {
                    findings.add(createFinding(path, currentLineNum, "CRITICAL",
                            "Guaranteed NullPointerException: variable is dereferenced inside an 'if (var == null)' condition or block.",
                            "Verify if the condition was meant to be '!= null' or add proper null handling.", 780));
                }

                // 5. Dangerous String comparison with == or != in Java
                if (isJava) {
                    Matcher strEqualsMatcher = STRING_EQUALS_JAVA.matcher(trimmed);
                    if (strEqualsMatcher.find()) {
                        findings.add(createFinding(path, currentLineNum, "HIGH",
                                "Dangerous String comparison using '==' or '!='. In Java, this checks reference identity rather than string content.",
                                "Use '.equals()' or 'Objects.equals(a, b)' for string content comparison.", 720));
                    }
                }

                // 6. Swallowed exceptions
                if (SWALLOWED_EXCEPTION_BLOCK.matcher(trimmed).find() || (isPython && PYTHON_PASS_EXCEPT.matcher(trimmed).find())) {
                    findings.add(createFinding(path, currentLineNum, "HIGH",
                            "Empty catch block silently swallows exceptions without logging or recovery, hiding unexpected runtime failures.",
                            "Log the exception with a logger or rethrow it.", 700));
                }

                // 7. Stray semicolon directly after if/for/while
                Matcher straySemi = STRAY_SEMICOLON.matcher(trimmed);
                if (straySemi.find() && !trimmed.toLowerCase().startsWith("do ")) {
                    findings.add(createFinding(path, currentLineNum, "HIGH",
                            "Stray semicolon directly after conditional/loop statement creates an empty statement. The subsequent block will execute unconditionally.",
                            "Remove the stray semicolon after the condition parenthesis.", 730));
                }

                // 8. Resource leak without try-with-resources (Java)
                if (isJava && UNCLOSED_RESOURCE_JAVA.matcher(trimmed).find() && !trimmed.contains("try (") && !trimmed.contains("try(")) {
                    findings.add(createFinding(path, currentLineNum, "HIGH",
                            "Potential resource leak: I/O stream or socket opened without try-with-resources.",
                            "Use try-with-resources: 'try (var stream = new ...) { ... }' to ensure deterministic cleanup.", 700));
                }

                // 9. Self-assignment (no-op)
                Matcher selfAssign = SELF_ASSIGNMENT.matcher(trimmed);
                if (selfAssign.find() && !trimmed.contains("==") && !trimmed.contains("!=")) {
                    findings.add(createFinding(path, currentLineNum, "MEDIUM",
                            "Self-assignment: variable is assigned to itself with no operational effect.",
                            "Assign the intended parameter or expression.", 650));
                }
            }
        }

        return findings;
    }

    private Finding createFinding(String filePath, int line, String severity, String message, String suggestion, int precedence) {
        return Finding.builder()
                .id(UUID.randomUUID().toString())
                .filePath(filePath)
                .lineNumber(line)
                .endLine(0)
                .severity(severity)
                .category("BUG_DETECTION")
                .message(message)
                .suggestion(suggestion)
                .source("HEURISTIC")
                .confidence(0.92)
                .precedenceScore(precedence)
                .build();
    }
}
