package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EditFileTool 单元测试。
 *
 * 覆盖 SEARCH/REPLACE 的核心场景：正常替换、找不到、多处匹配、空 old_string、
 * 多行替换、文件开头/结尾替换。
 */
class EditFileToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EditFileTool tool = new EditFileTool();

    @TempDir
    Path tempDir;

    private Path file;

    @BeforeEach
    void setUp() {
        file = tempDir.resolve("test.txt");
    }

    @Test
    void replaceSingleLineSuccess() throws Exception {
        Files.writeString(file, "hello world\nfoo bar\n");

        String result = tool.execute(args("hello world", "hi there"));

        assertTrue(result.startsWith("已修改:"));
        assertEquals("hi there\nfoo bar\n", Files.readString(file));
    }

    @Test
    void replaceMultiLineSuccess() throws Exception {
        Files.writeString(file, "line1\nline2\nline3\nline4\n");

        String result = tool.execute(args("line2\nline3", "middle2\nmiddle3"));

        assertTrue(result.startsWith("已修改:"));
        assertEquals("line1\nmiddle2\nmiddle3\nline4\n", Files.readString(file));
    }

    @Test
    void replaceAtFileStart() throws Exception {
        Files.writeString(file, "first\nsecond\n");

        tool.execute(args("first", "head"));

        assertEquals("head\nsecond\n", Files.readString(file));
    }

    @Test
    void replaceAtFileEnd() throws Exception {
        Files.writeString(file, "alpha\nbeta\n");

        tool.execute(args("beta", "omega"));

        assertEquals("alpha\nomega\n", Files.readString(file));
    }

    @Test
    void noMatchReturnsError() throws Exception {
        Files.writeString(file, "abc def\n");

        String result = tool.execute(args("xyz", "zzz"));

        assertTrue(result.startsWith("error:"));
        assertTrue(result.contains("未找到匹配内容"));
        assertEquals("abc def\n", Files.readString(file)); // 文件未变
    }

    @Test
    void multipleMatchesReturnsError() throws Exception {
        Files.writeString(file, "repeat\nrepeat\nrepeat\n");

        String result = tool.execute(args("repeat", "once"));

        assertTrue(result.startsWith("error:"));
        assertTrue(result.contains("多处匹配"));
        assertEquals("repeat\nrepeat\nrepeat\n", Files.readString(file)); // 文件未变
    }

    @Test
    void emptyOldStringReturnsError() throws Exception {
        Files.writeString(file, "content\n");

        String result = tool.execute(args("", "new"));

        assertTrue(result.startsWith("error:"));
        assertTrue(result.contains("不能为空"));
    }

    @Test
    void missingFileReturnsError() {
        Path noSuch = tempDir.resolve("no_such_file.txt");

        String result = tool.execute(args("a", "b", noSuch.toString()));

        assertTrue(result.startsWith("error:"));
        assertTrue(result.contains("文件不存在"));
    }

    @Test
    void exactMatchIncludingWhitespace() throws Exception {
        Files.writeString(file, "    indented line\n");

        String result = tool.execute(args("    indented line", "    changed"));

        assertTrue(result.startsWith("已修改:"));
        assertEquals("    changed\n", Files.readString(file));
    }

    // ── helpers ──

    private JsonNode args(String oldStr, String newStr) {
        return args(oldStr, newStr, file.toString());
    }

    private JsonNode args(String oldStr, String newStr, String path) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("file_path", path);
        node.put("old_string", oldStr);
        node.put("new_string", newStr);
        return node;
    }
}
