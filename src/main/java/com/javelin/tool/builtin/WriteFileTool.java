package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 创建或覆盖写入文件。父目录不存在时会自动创建。
 */
public class WriteFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "将内容写入指定文件。如果文件已存在则覆盖，父目录不存在时自动创建。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "目标文件路径（绝对路径或相对于当前工作目录）");
        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "要写入的内容");
        schema.putArray("required").add("file_path").add("content");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String rawPath = input.get("file_path").asText();
        String content = input.get("content").asText();
        try {
            Path path = Path.of(rawPath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content);
            return "已写入: " + path.toAbsolutePath() + " (" + content.length() + " 字符)";
        } catch (IOException e) {
            return "error: 写入失败: " + e.getMessage();
        }
    }
}
