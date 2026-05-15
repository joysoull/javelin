package com.javelin.llm.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javelin.llm.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 把已有的 anthropic-java SDK 包装成 {@link LlmProvider} 接口。
 *
 * 这个类封装了所有 Anthropic 协议特有逻辑：
 * - input_schema → Tool.InputSchema
 * - messages 数组拼接（assistant → toParam → addMessage；tool_result 是 user 角色）
 * - 响应解析（content[] 里的 text / tool_use 块）
 * - stop_reason → "end_turn" / "tool_use"
 */
public class AnthropicProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AnthropicClient client;
    private final String modelId;

    public AnthropicProvider(String apiKey, String baseUrl, String modelId) {
        AnthropicOkHttpClient.Builder cb = AnthropicOkHttpClient.builder().apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) cb.baseUrl(baseUrl);
        this.client = cb.build();
        this.modelId = modelId != null && !modelId.isBlank() ? modelId : "claude-sonnet-4-5-20250929";
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolDef> tools, String systemPrompt) {
        // Neutral messages → SDK MessageParam list
        List<MessageParam> params = new ArrayList<>();
        for (LlmMessage m : messages) {
            if (m.role() == LlmMessage.Role.USER) {
                if (m.hasToolResults()) {
                    // 打包 tool_result 块进一条 user 消息
                    List<ContentBlockParam> blocks = new ArrayList<>();
                    for (LlmMessage.ToolResultBlock tr : m.toolResults()) {
                        blocks.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                .toolUseId(tr.toolCallId())
                                .content(tr.content())
                                .isError(tr.isError())
                                .build()));
                    }
                    params.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(blocks)
                            .build());
                } else {
                    params.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(m.text())
                            .build());
                }
            } else {
                // assistant 消息：带 tool_use 块
                List<ContentBlockParam> blocks = new ArrayList<>();
                if (m.text() != null && !m.text().isEmpty()) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(m.text()).build()));
                }
                for (ToolCall tc : m.toolCalls()) {
                    try {
                        JsonNode inputNode = MAPPER.readTree(tc.argumentsJson());
                        blocks.add(ContentBlockParam.ofToolUse(
                            ToolUseBlockParam.builder()
                                .id(tc.id())
                                .name(tc.name())
                                .input(JsonValue.fromJsonNode(inputNode))
                                .build()));
                    } catch (Exception e) {
                        throw new RuntimeException("无法解析 tool call arguments: " + tc.argumentsJson(), e);
                    }
                }
                params.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(blocks)
                        .build());
            }
        }

        // SDK Tool 列表
        List<ToolUnion> sdkTools = new ArrayList<>();
        for (ToolDef td : tools) sdkTools.add(toSdkTool(td));

        // 调 API
        MessageCreateParams.Builder req = MessageCreateParams.builder()
                .maxTokens(2048L)
                .messages(params)
                .tools(sdkTools);
        if (systemPrompt != null && !systemPrompt.isEmpty()) req.system(systemPrompt);
        req.model(modelId);

        Message resp = client.messages().create(req.build());

        // 解析响应
        String text = "";
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ContentBlock block : resp.content()) {
            if (block.isText()) {
                String t = block.text().map(TextBlock::text).orElse("");
                if (!text.isEmpty()) text += "\n";
                text += t;
            } else if (block.isToolUse()) {
                ToolUseBlock tu = block.toolUse().orElseThrow();
                toolCalls.add(new ToolCall(tu.id(), tu.name(), tu._input().toString()));
            }
        }
        String stop = resp.stopReason().map(sr -> sr.asString()).orElse("end_turn");
        return new LlmResponse(text, toolCalls, stop, "");
    }

    /** ToolDef → SDK Tool */
    private static ToolUnion toSdkTool(ToolDef td) {
        try {
            JsonNode schema = MAPPER.readTree(td.parametersJson());
            Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
            JsonNode props = schema.get("properties");
            if (props != null && props.isObject()) {
                Iterator<String> it = props.fieldNames();
                while (it.hasNext()) {
                    String k = it.next();
                    pb.putAdditionalProperty(k, JsonValue.fromJsonNode(props.get(k)));
                }
            }
            Tool.InputSchema.Builder sb = Tool.InputSchema.builder().properties(pb.build());
            JsonNode req = schema.get("required");
            if (req != null && req.isArray()) {
                List<String> r = new ArrayList<>();
                req.forEach(n -> r.add(n.asText()));
                sb.required(r);
            }
            return ToolUnion.ofTool(
                Tool.builder().name(td.name()).description(td.description()).inputSchema(sb.build()).build());
        } catch (Exception e) {
            throw new RuntimeException("无法解析 tool schema: " + td.name(), e);
        }
    }
}
