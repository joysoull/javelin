package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 在文件内容中搜索正则表达式，类似 ripgrep。
 *
 * 自动跳过二进制文件和常见忽略目录（target、.git、node_modules 等）。
 */
public class GrepTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 200;
    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1 MB

    @Override
    public String name() { return "grep"; }

    @Override
    public String description() {
        return "在目录中递归搜索文件内容，支持正则表达式。自动跳过二进制文件和 target/.git/node_modules 目录。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "正则表达式搜索模式");
        ObjectNode dirPath = props.putObject("path");
        dirPath.put("type", "string");
        dirPath.put("description", "搜索目录路径，不填则为当前工作目录");
        ObjectNode glob = props.putObject("glob");
        glob.put("type", "string");
        glob.put("description", "文件名过滤，如 *.java。不填则搜索所有文本文件");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String patternStr = input.get("pattern").asText();
        String searchPath = input.has("path") ? input.get("path").asText() : ".";
        String globFilter = input.has("glob") ? input.get("glob").asText() : null;

        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return "error: 正则表达式语法错误: " + e.getMessage();
        }

        try {
            List<String> results = new ArrayList<>();
            Path root = Path.of(searchPath);
            if (!Files.isDirectory(root)) {
                return "error: 目录不存在: " + searchPath;
            }

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules")
                            || name.equals(".idea") || name.equals(".vscode") || name.equals("__pycache__")
                            || name.equals("maven-repository")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (results.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    if (attrs.size() > MAX_FILE_SIZE) return FileVisitResult.CONTINUE;
                    if (globFilter != null && !file.getFileName().toString().matches(globToRegex(globFilter))) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        String content = Files.readString(file);
                        String[] lines = content.split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            if (results.size() >= MAX_RESULTS) break;
                            if (regex.matcher(lines[i]).find()) {
                                String relPath = root.relativize(file).toString();
                                results.add(String.format("%s:%d: %s", relPath, i + 1, lines[i].strip()));
                            }
                        }
                    } catch (IOException ignored) {
                        // 二进制文件或编码问题，跳过
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) return "未找到匹配项";
            return String.join("\n", results);
        } catch (IOException e) {
            return "error: 搜索失败: " + e.getMessage();
        }
    }

    /** 将简单 glob 转为正则：* → [^/]*，? → [^/] */
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*': sb.append("[^/]*"); break;
                case '?': sb.append("[^/]"); break;
                case '.': sb.append("\\."); break;
                default:  sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }
}
