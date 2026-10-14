package com.yuzhuo.agent.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 在 Spring 上下文测试里把 ChatModel 换成剧本模型。
 *
 * <p>标 {@code @Primary} 是为了就算某台机器上真的配了 DEEPSEEK_API_KEY（于是真实的
 * OpenAiChatModel 也在容器里），注入的仍然是这个假模型 —— 测试结果不会因为开发机的环境变量而变。
 */
@TestConfiguration
public class TestChatModelConfig {

    /**
     * 只注册这一个 ChatModel 类型的 Bean。测试类还会把 api-key 置空
     * （{@code properties = "spring.ai.openai.api-key="}），因此真实模型不会被装配，
     * {@code ObjectProvider#getIfAvailable} 拿到的必然是这个假模型。
     */
    @Bean
    @Primary
    public FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }
}
