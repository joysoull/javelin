package com.javelin.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 极简 .env 加载器。不引第三方库，覆盖最常见的语法即可。
 *
 * 支持：
 *   KEY=value             # 行尾注释
 *   KEY="quoted value"    支持双引号包裹（用于值里含 # 或空格）
 *   KEY='single quoted'   同上
 *   # 整行注释
 *   空行
 *
 * 不支持（保持简单）：
 *   变量插值 ${OTHER}
 *   多行字符串
 *   导出语法 `export KEY=...`（会被忽略掉 export 前缀）
 *
 * 设计说明：
 * - System.getenv() 在 Java 中是不可变的，所以我们不能"把 .env 注入环境变量"，
 *   只能把它当成普通 Map 暴露给应用层（{@link #get}）。
 * - 加载失败（文件不存在）返回空 DotEnv 实例，让调用方决定如何回退。
 */
public final class DotEnv {

    private final Map<String, String> values;

    private DotEnv(Map<String, String> values) {
        this.values = values;
    }

    public static DotEnv loadOrEmpty(Path path) {
        if (!Files.isRegularFile(path)) {
            return new DotEnv(Map.of());
        }
        try {
            List<String> lines = Files.readAllLines(path);
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < lines.size(); i++) {
                parseLine(lines.get(i), i + 1, map);
            }
            return new DotEnv(map);
        } catch (IOException e) {
            throw new RuntimeException("读取 " + path + " 失败: " + e.getMessage(), e);
        }
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /** .env 优先，找不到回退到 System.getenv()。值为空串视同未设置。 */
    public Optional<String> getOrEnv(String key) {
        String v = values.get(key);
        if (v == null || v.isEmpty()) v = System.getenv(key);
        return (v == null || v.isEmpty()) ? Optional.empty() : Optional.of(v);
    }

    private static void parseLine(String raw, int lineNo, Map<String, String> out) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) return;

        if (line.startsWith("export ")) line = line.substring("export ".length()).strip();

        int eq = line.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException(".env 第 " + lineNo + " 行缺少 '=': " + raw);
        }
        String key = line.substring(0, eq).strip();
        String value = line.substring(eq + 1).strip();

        // 引号包裹：取引号内的所有内容，不再处理 #
        if (value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                 || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            value = value.substring(1, value.length() - 1);
        } else {
            // 非引号场景：# 之前是值，之后是注释
            int hash = value.indexOf('#');
            if (hash >= 0) value = value.substring(0, hash).strip();
        }

        out.put(key, value);
    }
}
