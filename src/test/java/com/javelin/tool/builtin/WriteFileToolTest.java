package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WriteFileTool 单元测试。
 *
 * 覆盖：正常写入、父目录自动创建、裸文件名（无目录）写入。
 */
class WriteFileToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WriteFileTool tool = new WriteFileTool();

    @TempDir
    Path tempDir;

    @Test
    void writeNewFile() throws Exception {
        Path file = tempDir.resolve("hello.txt");
        String result = tool.execute(args(file.toString(), "hello world"));

        assertTrue(result.startsWith("已写入:"));
        assertEquals("hello world", Files.readString(file));
    }

    @Test
    void overwriteExistingFile() throws Exception {
        Path file = tempDir.resolve("exists.txt");
        Files.writeString(file, "old");

        String result = tool.execute(args(file.toString(), "new"));

        assertTrue(result.startsWith("已写入:"));
        assertEquals("new", Files.readString(file));
    }

    @Test
    void createParentDirectoriesAutomatically() throws Exception {
        Path file = tempDir.resolve("a/b/c/deep.txt");
        assertFalse(Files.exists(file.getParent()));

        String result = tool.execute(args(file.toString(), "deep content"));

        assertTrue(result.startsWith("已写入:"));
        assertTrue(Files.isRegularFile(file));
        assertEquals("deep content", Files.readString(file));
    }

    @Test
    void bareFilenameNoParentDoesNotThrow() throws Exception {
        // 裸文件名（无父目录）不应触发 NPE；文件写入当前工作目录
        String result = tool.execute(args("bare_no_parent.txt", "bare content"));

        assertTrue(result.startsWith("已写入:"));
        // 清理，避免污染项目目录
        Files.deleteIfExists(Path.of("bare_no_parent.txt"));
    }

    private JsonNode args(String filePath, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("file_path", filePath);
        node.put("content", content);
        return node;
    }
}
