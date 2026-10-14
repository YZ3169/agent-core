package com.yuzhuo.agent;

import com.yuzhuo.agent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 自研轻量级 ReAct Agent 服务的启动类。
 *
 * <p>设计上刻意不依赖 Spring AI 的 starter 自动装配，ChatModel 由 {@code ChatModelConfig}
 * 按「有没有 API Key」这个条件自己装配，因此没有 DEEPSEEK_API_KEY 时应用照常启动。
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class AgentCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCoreApplication.class, args);
    }
}
