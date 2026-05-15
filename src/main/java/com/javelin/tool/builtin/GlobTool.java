package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 按通配符模式搜索文件名，类似 glob 命令。
 *
 * 支持 ** 递归匹配，自动跳过常见忽略目录。
 */
public class GlobTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 500;

    @Override
    public String name() { return "glob"; }

    @Override
    public String description() {
        return "按通配符模式查找文件，如 **/*.java 匹配所有 Java 文件。"
                + "支持 **（递归）、*（单层文件名）、?（单字符）。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "文件匹配模式，如 **/*.java 或 src/**/*Test*.java");
        ObjectNode dirPath = props.putObject("path");
        dirPath.put("type", "string");
        dirPath.put("description", "搜索根目录，不填则为当前工作目录");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String patternStr = input.get("pattern").asText();
        String searchPath = input.has("path") ? input.get("path").asText() : ".";

        try {
            Path root = Path.of(searchPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                return "error: 目录不存在: " + searchPath;
            }

            // 将 glob 转为 PathMatcher 可用的 "glob:**/*.java" 格式
            String matcherPattern = "glob:" + patternStr;
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(matcherPattern);

            List<String> results = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules")
                            || name.equals(".idea") || name.equals("__pycache__")
                            || name.equals("maven-repository")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    Path rel = root.relativize(file);
                    // PathMatcher 的 matches 对 glob:**/*.java 格式只匹配文件名，需要用完整相对路径
                    // 所以手动检查：先看文件名是否匹配，再处理 ** 的情况
                    if (matchesGlob(rel, patternStr)) {
                        results.add(rel.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) return "未找到匹配项";
            results.sort(String::compareTo);
            return String.join("\n", results);
        } catch (IOException e) {
            return "error: 搜索失败: " + e.getMessage();
        }
    }

    /** 简单 glob 匹配：将 ** 处理为任意路径段，* 为任意文件名字符 */
    private static boolean matchesGlob(Path rel, String glob) {
        return matchSegments(rel.toString().replace('\\', '/'), glob.replace('\\', '/'));
    }

    private static boolean matchSegments(String path, String pattern) {
        // 将 pattern 转为正则
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                            i++; // **/
                            regex.append("(.*/)?");
                        } else {
                            regex.append(".*"); // ** at end
                        }
                    } else {
                        regex.append("[^/]*"); // * = not slash
                    }
                    break;
                case '?': regex.append("[^/]"); break;
                case '.': regex.append("\\."); break;
                default: regex.append(c);
            }
        }
        regex.append("$");
        return path.matches(regex.toString());
    }
}
