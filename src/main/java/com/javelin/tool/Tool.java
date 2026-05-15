package com.javelin.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个 Tool 描述了 agent 能调用的一种"外部能力"。
 *
 * 学习要点：
 * 1. name() / description() / inputSchema() 会被打包成 JSON 发给 LLM，
 *    告诉它"你有哪些工具、参数长什么样"。LLM 据此决定要不要调用、怎么调。
 * 2. execute() 是真正的本地执行逻辑。LLM 永远不会"自己跑代码"，
 *    它只会要求我们跑工具，再把结果给它看。
 * 3. inputSchema 必须是合法的 JSON Schema (object 类型)，
 *    SDK 内部会校验，写错了 API 会拒绝。
 */
public interface Tool {

    /** 工具名（agent 与 LLM 双方用来识别这个工具）。必须唯一、英文小写下划线。 */
    String name();

    /** 给 LLM 看的工具说明。写得越清楚，模型越知道何时该用、怎么传参。 */
    String description();

    /**
     * JSON Schema，描述 input 的结构。最小形态如：
     * <pre>
     * {
     *   "type": "object",
     *   "properties": { "expression": { "type": "string" } },
     *   "required": ["expression"]
     * }
     * </pre>
     */
    JsonNode inputSchema();

    /**
     * 真正执行工具。
     *
     * @param input  LLM 给的入参（已解析为 JsonNode）
     * @return       返回给 LLM 的字符串结果。出错时建议返回包含 "error:" 的字符串，
     *               这样模型能感知失败并尝试别的方式 —— 这就是"错误回灌"机制。
     */
    String execute(JsonNode input) throws Exception;
}
