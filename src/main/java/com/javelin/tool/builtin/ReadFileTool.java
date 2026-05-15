package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读取文件内容，返回全文。
 * 支持通过 offset/limit 读取文件片段，避免大文件撑爆上下文。
 */
public class ReadFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() { return "read_file"; }

    @Override
    public String description() {
        return "读取指定文件的内容。可选 offset（起始行，从 1 开始）和 limit（读取行数）。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "文件路径（绝对路径或相对于当前工作目录）");
        ObjectNode offset = props.putObject("offset");
        offset.put("type", "integer");
        offset.put("description", "起始行号，从 1 开始。不填则从头读取");
        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "最大读取行数，不填则读取全部");
        schema.putArray("required").add("file_path");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String rawPath = input.get("file_path").asText();
        try {
            Path path = Path.of(rawPath);
            if (!Files.isRegularFile(path)) {
                return "error: 文件不存在: " + rawPath;
            }
            String content = Files.readString(path);
            String[] lines = content.split("\n", -1);

            int start = 0;
            if (input.has("offset")) {
                start = input.get("offset").asInt() - 1;
                if (start < 0) start = 0;
            }
            int end = lines.length;
            if (input.has("limit")) {
                end = Math.min(end, start + input.get("limit").asInt());
            }

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%6d\t%s%n", i + 1, lines[i]));
            }
            return sb.toString();
        } catch (IOException e) {
            return "error: 读取失败: " + e.getMessage();
        }
    }
}
