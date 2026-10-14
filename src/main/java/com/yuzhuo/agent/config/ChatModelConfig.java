package com.yuzhuo.agent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * 装配唯一一个 ChatModel Bean：DeepSeek（OpenAI 兼容协议）。
 *
 * <p>三个刻意的取舍：
 * <ol>
 *   <li><b>不用 starter 自动装配</b>，自己 new。这样"没有 Key 会怎样"完全由本项目决定，
 *       不会因为引入一个 starter 就在启动期被别人的断言拦住。</li>
 *   <li><b>重试次数设为 2</b>。Spring AI 的默认重试是 10 次指数退避，
 *       对交互式接口来说意味着一次限流就要挂起很久，宁可快速失败并把错误码交给调用方。</li>
 *   <li><b>不注册任何 ToolCallback</b>。Spring AI 自带的 tool-calling 会自动执行工具并再次调用模型，
 *       那正是本项目要手写的东西；这里只把它当"Prompt 进、文本出"的 HTTP 客户端用。</li>
 * </ol>
 */
@Configuration
public class ChatModelConfig {

    /**
     * @return 只在配置了非空 API Key 时存在。没有它，ReActAgent 会走降级分支返回 1001。
     */
    @Bean
    @Conditional(OnApiKeyPresentCondition.class)
    public ChatModel deepSeekChatModel(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:deepseek-chat}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:0.0}") Double temperature,
            @Value("${spring.ai.openai.chat.options.max-tokens:1024}") Integer maxTokens) {

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(defaultOptions)
                .retryTemplate(RetryTemplate.builder()
                        .maxAttempts(2)
                        .fixedBackoff(500)
                        .build())
                .build();
    }
}
