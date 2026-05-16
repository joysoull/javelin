package com.javelin.config;

import com.javelin.llm.LlmProvider;
import com.javelin.llm.impl.AnthropicProvider;
import com.javelin.llm.impl.OpenAICompatProvider;

import java.io.PrintStream;

/**
 * 根据 .env / 环境变量创建对应的 {@link LlmProvider} 实例。
 *
 * 选择逻辑：
 *   LLM_PROVIDER=anthropic → Anthropic 协议
 *   LLM_PROVIDER=openai     → OpenAI 协议（DeepSeek / GLM / Kimi / 智谱 …）
 *   未设置                  → 默认 OpenAI 协议
 */
public final class ProviderFactory {

    private ProviderFactory() {}

    /**
     * 读取配置并创建 Provider。
     *
     * @param dotenv 已加载的 .env 配置
     * @param out    用于输出错误信息的流
     * @return 配置好的 LlmProvider
     * @throws RuntimeException 缺少 API Key 或配置了未知 provider 时抛出
     */
    public static LlmProvider create(DotEnv dotenv, PrintStream out) {
        String apiKey = dotenv.getOrEnv("LLM_API_KEY")
                .orElseThrow(() -> {
                    out.println(com.javelin.ui.Ansi.red("[error] 未找到 LLM_API_KEY。请任选一种："));
                    out.println("  1) 在 .env 写入 LLM_API_KEY=你的key");
                    out.println("  2) 设置环境变量 LLM_API_KEY");
                    return new RuntimeException("缺少 LLM_API_KEY");
                });

        String baseUrl = dotenv.getOrEnv("LLM_BASE_URL").orElse(null);
        String modelId = dotenv.getOrEnv("LLM_MODEL").orElse(null);
        String providerChoice = dotenv.getOrEnv("LLM_PROVIDER").orElse(null);
        boolean thinkingDisabled = "disabled".equalsIgnoreCase(dotenv.getOrEnv("LLM_THINKING").orElse(""));

        if ("anthropic".equalsIgnoreCase(providerChoice)) {
            return new AnthropicProvider(apiKey, baseUrl, modelId);
        } else if ("openai".equalsIgnoreCase(providerChoice)) {
            return new OpenAICompatProvider(apiKey, baseUrl, modelId, thinkingDisabled);
        } else if (providerChoice != null && !providerChoice.isBlank()) {
            throw new RuntimeException("未知 LLM_PROVIDER=" + providerChoice + "，可选：anthropic / openai");
        } else {
            return new OpenAICompatProvider(apiKey, baseUrl, modelId, thinkingDisabled);
        }
    }
}
