package com.yuzhuo.agent.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 只有当 {@code spring.ai.openai.api-key} 有非空白内容时才成立。
 *
 * <p>为什么不用 {@code @ConditionalOnProperty}：那个注解只判断"属性在不在"，
 * 而 yml 里写的是 {@code api-key: ${DEEPSEEK_API_KEY:}} —— 环境变量缺省时属性值是空串，
 * 属性存在但没意义。必须判"非空白"，才能做到「没配 Key 就不装配 ChatModel，服务照常启动」。
 */
public class OnApiKeyPresentCondition implements Condition {

    static final String API_KEY_PROPERTY = "spring.ai.openai.api-key";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty(API_KEY_PROPERTY);
        return StringUtils.hasText(apiKey);
    }
}
