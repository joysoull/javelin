package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 列出目录中的文件和子目录。
 */
public class ListDirTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() { return "list_dir"; }

    @Override
    public String description() {
        return "列出指定目录中的文件和子目录。支持递归（depth 控制深度，默认 1）。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode dirPath = props.putObject("path");
        dirPath.put("type", "string");
        dirPath.put("description", "目录路径，不填则为当前工作目录");
        ObjectNode depth = props.putObject("depth");
        depth.put("type", "integer");
        depth.put("description", "递归深度，默认 1。设为 2 显示一层子目录内容，以此类推");
        schema.putArray("required");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String rawPath = input.has("path") ? input.get("path").asText() : ".";
        int maxDepth = input.has("depth") ? input.get("depth").asInt() : 1;
        if (maxDepth < 1) maxDepth = 1;
        if (maxDepth > 5) maxDepth = 5;

        try {
            Path root = Path.of(rawPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                return "error: 不是目录: " + rawPath;
            }
            StringBuilder sb = new StringBuilder();
            listRecursive(root, root, 1, maxDepth, sb);
            return sb.toString();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private void listRecursive(Path root, Path dir, int currentDepth, int maxDepth,
                                StringBuilder sb) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            entries.sorted().forEach(p -> {
                String prefix = "  ".repeat(currentDepth - 1);
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    sb.append(prefix).append(name).append("/\n");
                    if (currentDepth < maxDepth) {
                        try {
                            listRecursive(root, p, currentDepth + 1, maxDepth, sb);
                        } catch (IOException ignored) {}
                    }
                } else {
                    sb.append(prefix).append(name).append("\n");
                }
            });
        }
    }
}
