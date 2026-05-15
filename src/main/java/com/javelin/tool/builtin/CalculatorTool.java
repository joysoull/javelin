package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

/**
 * 计算器工具：用 JavaScript 引擎跑一段算术表达式。
 *
 * 选它作为"第一个工具"的原因：
 * - 几乎没有副作用，验证 ReAct 循环最干净
 * - 输入输出都是纯文本，方便打印观察
 * - 让 LLM 通过工具做数学，比让它"硬算 23*47"靠谱得多 —— 这正是 agent 的价值之一
 *
 * 注意：javax.script + Nashorn 在 JDK 17 上不再内置 JS 引擎，
 * 这里用一个手写的"安全四则运算解析器"代替，避免引入额外依赖。
 */
public class CalculatorTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "Evaluate a basic arithmetic expression with + - * / and parentheses. "
                + "Use this whenever you need to compute a number — do not do mental math.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode expr = props.putObject("expression");
        expr.put("type", "string");
        expr.put("description", "Arithmetic expression, e.g. \"(23 + 7) * 4\"");
        schema.putArray("required").add("expression");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        JsonNode exprNode = input.get("expression");
        if (exprNode == null || !exprNode.isTextual()) {
            return "error: missing string field 'expression'";
        }
        try {
            double result = new Parser(exprNode.asText()).parse();
            // 整数结果就不显示小数点，让输出更自然
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return Long.toString((long) result);
            }
            return Double.toString(result);
        } catch (RuntimeException e) {
            return "error: " + e.getMessage();
        }
    }

    /** 极小的递归下降解析器：expr = term (('+'|'-') term)* ; term = factor (('*'|'/') factor)* ; factor = number | '(' expr ')' | '-' factor. */
    private static class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        double parse() {
            double v = expr();
            skipSpace();
            if (pos != src.length()) throw new RuntimeException("unexpected char at " + pos + ": '" + src.charAt(pos) + "'");
            return v;
        }

        private double expr() {
            double v = term();
            while (true) {
                skipSpace();
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    char op = src.charAt(pos++);
                    double r = term();
                    v = (op == '+') ? v + r : v - r;
                } else return v;
            }
        }

        private double term() {
            double v = factor();
            while (true) {
                skipSpace();
                if (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/')) {
                    char op = src.charAt(pos++);
                    double r = factor();
                    v = (op == '*') ? v * r : v / r;
                } else return v;
            }
        }

        private double factor() {
            skipSpace();
            if (pos >= src.length()) throw new RuntimeException("unexpected end of input");
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                double v = expr();
                skipSpace();
                if (pos >= src.length() || src.charAt(pos) != ')') throw new RuntimeException("missing ')'");
                pos++;
                return v;
            }
            if (c == '-') { pos++; return -factor(); }
            if (c == '+') { pos++; return factor(); }
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            if (start == pos) throw new RuntimeException("expected number at " + pos);
            return Double.parseDouble(src.substring(start, pos));
        }

        private void skipSpace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }
}
