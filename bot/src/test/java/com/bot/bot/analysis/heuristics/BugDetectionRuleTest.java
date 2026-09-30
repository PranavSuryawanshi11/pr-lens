package com.bot.bot.analysis.heuristics;

import com.bot.bot.domain.ChangeChunk;
import com.bot.bot.domain.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BugDetectionRuleTest {

    private BugDetectionRule rule;

    @BeforeEach
    void setUp() {
        rule = new BugDetectionRule();
    }

    private ChangeChunk makeChunk(String filePath, int startLine, List<String> addedLines) {
        return ChangeChunk.builder()
                .filePath(filePath)
                .startLine(startLine)
                .addedLines(addedLines)
                .build();
    }

    @Test
    void detectsDangerousStringEqualityInJava() {
        ChangeChunk chunk = makeChunk("src/main/AuthService.java", 20, List.of(
                "String role = getRole();",
                "if (role == \"ADMIN\") {",
                "    allowAccess();",
                "}"
        ));

        List<Finding> findings = rule.analyze(List.of(chunk));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("BUG_DETECTION", f.getCategory());
        assertEquals("HIGH", f.getSeverity());
        assertEquals(21, f.getLineNumber());
        assertTrue(f.getMessage().contains("Dangerous String comparison"));
        assertTrue(f.getSuggestion().contains(".equals()"));
    }

    @Test
    void detectsAccidentalAssignmentInIf() {
        ChangeChunk chunk = makeChunk("src/controller/UserController.js", 45, List.of(
                "function validate(user) {",
                "    if (user.isAdmin = true) {",
                "        return true;",
                "    }",
                "}"
        ));

        List<Finding> findings = rule.analyze(List.of(chunk));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("BUG_DETECTION", f.getCategory());
        assertEquals("CRITICAL", f.getSeverity());
        assertEquals(46, f.getLineNumber());
        assertTrue(f.getMessage().contains("Accidental assignment"));
    }

    @Test
    void detectsSwallowedException() {
        ChangeChunk chunk = makeChunk("src/service/PaymentService.java", 80, List.of(
                "try {",
                "    processTransaction();",
                "} catch (Exception e) {}",
                "return true;"
        ));

        List<Finding> findings = rule.analyze(List.of(chunk));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("BUG_DETECTION", f.getCategory());
        assertEquals("HIGH", f.getSeverity());
        assertEquals(82, f.getLineNumber());
        assertTrue(f.getMessage().contains("Empty catch block"));
    }

    @Test
    void detectsOffByOneLoopCondition() {
        ChangeChunk chunk = makeChunk("src/util/ArrayHelper.java", 15, List.of(
                "for (int i = 0; i <= items.length; i++) {",
                "    process(items[i]);",
                "}"
        ));

        List<Finding> findings = rule.analyze(List.of(chunk));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("BUG_DETECTION", f.getCategory());
        assertEquals("CRITICAL", f.getSeverity());
        assertEquals(15, f.getLineNumber());
        assertTrue(f.getMessage().contains("Off-by-one"));
    }

    @Test
    void detectsInfiniteLoopStep() {
        ChangeChunk chunk = makeChunk("src/algorithm/Sort.java", 30, List.of(
                "for (int i = 0; i < len; i--) {",
                "    doWork(i);",
                "}"
        ));

        List<Finding> findings = rule.analyze(List.of(chunk));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("BUG_DETECTION", f.getCategory());
        assertEquals("CRITICAL", f.getSeverity());
        assertTrue(f.getMessage().contains("Infinite loop"));
    }

    @Test
    void detectsImmediateNullPointerDereference() {
        ChangeChunk chunk = makeChunk("src/service/OrderService.java", 55, List.of(
                "if (order == null) order.cancel();"
        ));

        List<Finding> findings = rule.analyze(List.of(chunk));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("BUG_DETECTION", f.getCategory());
        assertEquals("CRITICAL", f.getSeverity());
        assertTrue(f.getMessage().contains("NullPointerException"));
    }

    @Test
    void detectsStraySemicolonOnIf() {
        ChangeChunk chunk = makeChunk("src/main/App.java", 10, List.of(
                "if (count > 0);",
                "System.out.println(count);"
        ));

        List<Finding> findings = rule.analyze(List.of(chunk));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("BUG_DETECTION", f.getCategory());
        assertEquals("HIGH", f.getSeverity());
        assertTrue(f.getMessage().contains("Stray semicolon"));
    }

    @Test
    void detectsResourceLeakWithoutTryWithResources() {
        ChangeChunk chunk = makeChunk("src/io/FileReaderUtil.java", 5, List.of(
                "FileInputStream fis = new FileInputStream(\"file.txt\");",
                "fis.read();"
        ));

        List<Finding> findings = rule.analyze(List.of(chunk));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("BUG_DETECTION", f.getCategory());
        assertEquals("HIGH", f.getSeverity());
        assertTrue(f.getMessage().contains("resource leak"));
    }

    @Test
    void skipsCleanCodeWithoutFalsePositives() {
        ChangeChunk chunk = makeChunk("src/clean/CleanCode.java", 1, List.of(
                "// if (x == \"test\") commented out",
                "if (role.equals(\"ADMIN\")) {",
                "    try (FileInputStream fis = new FileInputStream(\"file.txt\")) {",
                "        fis.read();",
                "    } catch (IOException e) {",
                "        log.error(\"Read error\", e);",
                "    }",
                "}",
                "for (int i = 0; i < items.length; i++) {",
                "    process(items[i]);",
                "}"
        ));

        List<Finding> findings = rule.analyze(List.of(chunk));
        assertTrue(findings.isEmpty(), "Clean code should not produce false positive bug findings");
    }
}
