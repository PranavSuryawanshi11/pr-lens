package com.bot.bot.diff;

import com.bot.bot.domain.ChangeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedDiffParserExtendedTest {

    private UnifiedDiffParser parser;

    @BeforeEach
    void setUp() {
        parser = new UnifiedDiffParser();
    }

    @Test
    @DisplayName("Returns empty list when diff is null")
    void handlesNullDiff() {
        List<ChangeChunk> chunks = parser.parse(null);
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty());
    }

    @Test
    @DisplayName("Returns empty list when diff is empty string")
    void handlesEmptyDiff() {
        List<ChangeChunk> chunks = parser.parse("");
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty());
    }

    @Test
    @DisplayName("Returns empty list when diff has no git diff headers or changes")
    void handlesPlainNonDiffText() {
        List<ChangeChunk> chunks = parser.parse("Just some random log output\nline 2\nline 3");
        assertTrue(chunks.isEmpty());
    }

    @Test
    @DisplayName("Correctly detects newly added file (--- /dev/null)")
    void detectsAddedFile() {
        String diff = """
                diff --git a/src/NewService.java b/src/NewService.java
                new file mode 100644
                index 0000000..abcdef1
                --- /dev/null
                +++ b/src/NewService.java
                @@ -0,0 +1,5 @@
                +package src;
                +
                +public class NewService {
                +    public void execute() {}
                +}
                """;

        List<ChangeChunk> chunks = parser.parse(diff);
        assertEquals(1, chunks.size());

        ChangeChunk chunk = chunks.get(0);
        assertEquals("src/NewService.java", chunk.getFilePath());
        assertEquals("java", chunk.getFileType());
        assertEquals("ADDED", chunk.getChangeType());
        assertEquals(1, chunk.getStartLine());
        assertEquals(5, chunk.getAddedLines().size());
        assertTrue(chunk.getRemovedLines().isEmpty());
    }

    @Test
    @DisplayName("Correctly detects deleted file (+++ /dev/null)")
    void detectsDeletedFile() {
        String diff = """
                diff --git a/src/OldService.java b/src/OldService.java
                deleted file mode 100644
                index abcdef1..0000000
                --- a/src/OldService.java
                +++ /dev/null
                @@ -1,4 +0,0 @@
                -package src;
                -
                -public class OldService {}
                """;

        List<ChangeChunk> chunks = parser.parse(diff);
        assertEquals(1, chunks.size());

        ChangeChunk chunk = chunks.get(0);
        assertEquals("src/OldService.java", chunk.getFilePath());
        assertEquals("DELETED", chunk.getChangeType());
        assertTrue(chunk.getAddedLines().isEmpty());
        assertEquals(3, chunk.getRemovedLines().size());
    }

    @Test
    @DisplayName("Parses multiple hunks within the same file")
    void parsesMultipleHunksInSingleFile() {
        String diff = """
                diff --git a/src/Main.java b/src/Main.java
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -10,3 +10,4 @@
                 int a = 1;
                -int b = 2;
                +int b = 20;
                +int b2 = 25;
                 int c = 3;
                @@ -50,3 +51,3 @@
                 void test() {
                -    print("old");
                +    print("new");
                 }
                """;

        List<ChangeChunk> chunks = parser.parse(diff);
        assertEquals(2, chunks.size());

        ChangeChunk h1 = chunks.get(0);
        assertEquals(10, h1.getStartLine());
        assertEquals(2, h1.getAddedLines().size());
        assertEquals(1, h1.getRemovedLines().size());

        ChangeChunk h2 = chunks.get(1);
        assertEquals(51, h2.getStartLine());
        assertEquals(1, h2.getAddedLines().size());
        assertEquals(1, h2.getRemovedLines().size());
    }

    @Test
    @DisplayName("Parses multi-file diff accurately")
    void parsesMultiFileDiff() {
        String diff = """
                diff --git a/file1.py b/file1.py
                --- a/file1.py
                +++ b/file1.py
                @@ -1,2 +1,2 @@
                -def old(): pass
                +def new(): pass
                diff --git a/file2.js b/file2.js
                --- a/file2.js
                +++ b/file2.js
                @@ -5,2 +5,3 @@
                 const x = 1;
                +const y = 2;
                """;

        List<ChangeChunk> chunks = parser.parse(diff);
        assertEquals(2, chunks.size());

        assertEquals("file1.py", chunks.get(0).getFilePath());
        assertEquals("py", chunks.get(0).getFileType());

        assertEquals("file2.js", chunks.get(1).getFilePath());
        assertEquals("js", chunks.get(1).getFileType());
    }

    @Test
    @DisplayName("Extracts various file extensions and handles no-extension edge cases")
    void testsFileTypeExtraction() {
        String diff = """
                diff --git a/Dockerfile b/Dockerfile
                --- a/Dockerfile
                +++ b/Dockerfile
                @@ -1,1 +1,2 @@
                 FROM alpine
                +RUN apk update
                diff --git a/.gitignore b/.gitignore
                --- a/.gitignore
                +++ b/.gitignore
                @@ -1,1 +1,2 @@
                 *.class
                +*.log
                diff --git a/nested/sub/data.config.json b/nested/sub/data.config.json
                --- a/nested/sub/data.config.json
                +++ b/nested/sub/data.config.json
                @@ -1,1 +1,2 @@
                 {}
                +{"key": "val"}
                """;

        List<ChangeChunk> chunks = parser.parse(diff);
        assertEquals(3, chunks.size());
        assertEquals("unknown", chunks.get(0).getFileType()); // Dockerfile
        assertEquals("unknown", chunks.get(1).getFileType()); // .gitignore
        assertEquals("json", chunks.get(2).getFileType());    // data.config.json
    }

    @Test
    @DisplayName("Handles hunk headers without comma format (e.g. @@ -1 +1 @@)")
    void handlesHunkHeaderWithoutComma() {
        String diff = """
                diff --git a/Single.java b/Single.java
                --- a/Single.java
                +++ b/Single.java
                @@ -1 +1 @@
                -oldLine
                +newLine
                """;

        List<ChangeChunk> chunks = parser.parse(diff);
        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).getStartLine());
        assertEquals(List.of("newLine"), chunks.get(0).getAddedLines());
        assertEquals(List.of("oldLine"), chunks.get(0).getRemovedLines());
    }

    @Test
    @DisplayName("EndLine calculation is startLine + addedLines.size()")
    void calculatesEndLineCorrectly() {
        String diff = """
                diff --git a/Calc.java b/Calc.java
                --- a/Calc.java
                +++ b/Calc.java
                @@ -100,2 +100,4 @@
                +line1
                +line2
                +line3
                +line4
                """;

        List<ChangeChunk> chunks = parser.parse(diff);
        assertEquals(1, chunks.size());
        assertEquals(100, chunks.get(0).getStartLine());
        assertEquals(104, chunks.get(0).getEndLine());
    }
}
