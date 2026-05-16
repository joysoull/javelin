package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 局部编辑文件——用 SEARCH/REPLACE 模式修改指定内容，不改其余部分。
 *
 * 与 write_file 的区别：
 *   - write_file 是全量覆盖，适合新建或小文件重写
 *   - edit_file 只替换匹配到的 old_string，适合大文件局部修改
 *
 * 安全机制：
 *   1. old_string 必须唯一匹配，找不到或多处匹配均拒绝修改（防止误改）
 *   2. 替换后写入前不做任何格式化或转义，原样落盘
 */
public class EditFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() { return "edit_file"; }

    @Override
    public String description() {
        return "局部编辑文件：查找 old_string 并替换为 new_string。要求 old_string 在文件中唯一匹配，否则拒绝修改。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "目标文件路径（绝对路径或相对于当前工作目录）");

        ObjectNode oldStr = props.putObject("old_string");
        oldStr.put("type", "string");
        oldStr.put("description", "文件中需要被替换的原始内容。必须完全匹配（包括缩进和换行）");

        ObjectNode newStr = props.putObject("new_string");
        newStr.put("type", "string");
        newStr.put("description", "替换后的新内容");

        schema.putArray("required").add("file_path").add("old_string").add("new_string");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String rawPath = input.get("file_path").asText();
        String oldString = input.get("old_string").asText();
        String newString = input.get("new_string").asText();

        if (oldString.isEmpty()) {
            return "error: old_string 不能为空";
        }

        Path path = Path.of(rawPath);
        if (!Files.isRegularFile(path)) {
            return "error: 文件不存在: " + rawPath;
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return "error: 读取失败: " + e.getMessage();
        }

        int index = content.indexOf(oldString);
        if (index == -1) {
            return "error: 未找到匹配内容。请确保 old_string 与文件中的原文完全一致（包括缩进和换行）。";
        }

        int secondIndex = content.indexOf(oldString, index + oldString.length());
        if (secondIndex != -1) {
            return "error: 找到多处匹配，请提供更精确的 old_string 以唯一确定修改位置。";
        }

        String updated = content.substring(0, index) + newString + content.substring(index + oldString.length());

        try {
            Files.writeString(path, updated);
        } catch (IOException e) {
            return "error: 写入失败: " + e.getMessage();
        }

        int lineNo = countLines(content, index) + 1;
        return "已修改: " + path.toAbsolutePath() + " 第 " + lineNo + " 行附近 (" + oldString.length() + " 字符 → " + newString.length() + " 字符)";
    }

    /** 计算字符位置之前有多少个换行符，从而得到行号（0-based） */
    private static int countLines(String text, int endIndex) {
        int count = 0;
        for (int i = 0; i < endIndex; i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }
}
