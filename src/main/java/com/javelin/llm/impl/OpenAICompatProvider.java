package com.javelin.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javelin.llm.*;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * OpenAI 协议兼容 Provider —— 同时覆盖 DeepSeek、GLM、Kimi、通义千问等。
 *
 * 与 Anthropic 协议的核心差异（对照学习用）：
 * 1. 工具定义：OpenAI 用 function.parameters，Anthropic 用 input_schema（结构相同）
 * 2. 工具调用：OpenAI 是 message.tool_calls[] 字段（独立），Anthropic 是 content[] 里的 tool_use 块
 * 3. 工具结果：OpenAI 是 role:"tool" 消息（tool_call_id），Anthropic 是 user 消息里的 tool_result 块
 * 4. 参数格式：OpenAI arguments 是 JSON 字符串，Anthropic input 是 JSON 对象
 * 5. 停止原因：OpenAI finish_reason:"tool_calls"，Anthropic stop_reason:"tool_use"
 */
public class OpenAICompatProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OpenAIClient client;
    private final String modelId;
    private final boolean thinkingDisabled;

    public OpenAICompatProvider(String apiKey, String baseUrl, String modelId, boolean thinkingDisabled) {
        OpenAIOkHttpClient.Builder cb = OpenAIOkHttpClient.builder().apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) cb.baseUrl(baseUrl);
        this.client = cb.build();
        this.modelId = modelId != null && !modelId.isBlank() ? modelId : "deepseek-chat";
        this.thinkingDisabled = thinkingDisabled;
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolDef> tools, String systemPrompt) {
        // ── 1) 构建 SDK 消息列表 ──
        List<ChatCompletionMessageParam> params = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            params.add(ChatCompletionMessageParam.ofSystem(
                ChatCompletionSystemMessageParam.builder().content(systemPrompt).build()));
        }

        for (LlmMessage m : messages) {
            if (m.role() == LlmMessage.Role.USER) {
                if (m.hasToolResults()) {
                    // OpenAI：每个 tool_result 是独立的一条 role:"tool" 消息
                    for (LlmMessage.ToolResultBlock tr : m.toolResults()) {
                        params.add(ChatCompletionMessageParam.ofTool(
                            ChatCompletionToolMessageParam.builder()
                                .toolCallId(tr.toolCallId())
                                .content(tr.content())
                                .build()));
                    }
                } else {
                    params.add(ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder().content(m.text()).build()));
                }
            } else {
                // assistant 消息
                List<ChatCompletionMessageToolCall> tcList = new ArrayList<>();
                for (ToolCall tc : m.toolCalls()) {
                    tcList.add(ChatCompletionMessageToolCall.ofFunction(
                        ChatCompletionMessageFunctionToolCall.builder()
                            .id(tc.id())
                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name(tc.name())
                                .arguments(tc.argumentsJson()) // OpenAI arguments 就是 JSON 字符串
                                .build())
                            .build()));
                }
                ChatCompletionAssistantMessageParam.Builder ab = ChatCompletionAssistantMessageParam.builder();
                if (m.text() != null && !m.text().isEmpty()) ab.content(m.text());
                if (!tcList.isEmpty()) ab.toolCalls(tcList);
                // DeepSeek 推理模型：reasoning_content 必须在后续请求中原样回传
                if (m.reasoningContent() != null && !m.reasoningContent().isEmpty()) {
                    ab.putAdditionalProperty("reasoning_content",
                        JsonValue.from(m.reasoningContent()));
                }
                params.add(ChatCompletionMessageParam.ofAssistant(ab.build()));
            }
        }

        // ── 2) 构建工具列表 ──
        List<ChatCompletionTool> sdkTools = new ArrayList<>();
        for (ToolDef td : tools) sdkTools.add(toSdkTool(td));

        // ── 3) 调 API ──
        ChatCompletionCreateParams.Builder req = ChatCompletionCreateParams.builder()
                .model(modelId)
                .messages(params)
                .maxTokens(2048L);
        if (!sdkTools.isEmpty()) req.tools(sdkTools);

        // DeepSeek 推理模型默认开启 thinking，通过 extra body 参数关掉
        if (thinkingDisabled) {
            req.putAdditionalBodyProperty("thinking",
                JsonValue.from(java.util.Map.of("type", "disabled")));
        }

        ChatCompletion resp = client.chat().completions().create(req.build());

        // ── 4) 解析响应 ──
        ChatCompletion.Choice choice = resp.choices().get(0);
        ChatCompletionMessage msg = choice.message();

        String text = msg.content().orElse("");
        List<ToolCall> toolCalls = new ArrayList<>();
        if (msg.toolCalls().isPresent()) {
            for (ChatCompletionMessageToolCall tc : msg.toolCalls().get()) {
                if (tc.isFunction()) {
                    ChatCompletionMessageFunctionToolCall fn = tc.asFunction();
                    // OpenAI：arguments() 已经是 JSON 字符串，直接拿
                    String args = fn.function().arguments();
                    toolCalls.add(new ToolCall(fn.id(), fn.function().name(), args));
                }
            }
        }

        // DeepSeek 推理模型的思考过程，存储在 _additionalProperties 中
        String reasoning = "";
        if (msg._additionalProperties().containsKey("reasoning_content")) {
            JsonValue rv = msg._additionalProperties().get("reasoning_content");
            reasoning = rv.convert(String.class);
            if (reasoning == null) reasoning = "";
        }

        String finish = choice.finishReason().asString();
        return new LlmResponse(text, toolCalls, finish, reasoning);
    }

    /** ToolDef → ChatCompletionTool */
    private static ChatCompletionTool toSdkTool(ToolDef td) {
        try {
            JsonNode schema = MAPPER.readTree(td.parametersJson());

            // 用 Jackson 构建完整的 parameters JSON（含 type/properties/required），
            // 然后逐键放入 FunctionParameters（它是一个扁平的 additionalProperties map）
            com.fasterxml.jackson.databind.node.ObjectNode paramsNode = MAPPER.createObjectNode();
            paramsNode.put("type", "object");
            paramsNode.set("properties", schema.get("properties"));
            if (schema.has("required")) {
                paramsNode.set("required", schema.get("required"));
            }

            FunctionParameters.Builder pb = FunctionParameters.builder();
            Iterator<String> fields = paramsNode.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                pb.putAdditionalProperty(key, JsonValue.fromJsonNode(paramsNode.get(key)));
            }

            FunctionDefinition.Builder fb = FunctionDefinition.builder()
                    .name(td.name())
                    .description(td.description())
                    .parameters(pb.build());
            return ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder().function(fb.build()).build());
        } catch (Exception e) {
            throw new RuntimeException("无法解析 tool schema: " + td.name(), e);
        }
    }
}
