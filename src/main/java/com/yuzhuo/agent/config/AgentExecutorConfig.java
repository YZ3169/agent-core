package com.yuzhuo.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 两个线程池。分开是因为它们的职责完全不同，混用会互相饿死：
 * <ul>
 *   <li>{@code agentStepExecutor} 跑"一次模型调用"，目的是给单步加超时。
 *       它的任务可能被 {@code Future.cancel(true)} 打断，所以不能被 SSE 的长连接任务占满。</li>
 *   <li>{@code agentSseExecutor} 跑"一整轮 Agent 运行"。一次 SSE 请求从头占到尾，
 *       绝不能放在 step 池里 —— 几个并发的慢会话就能把模型调用的执行位置全占掉。</li>
 * </ul>
 *
 * <p>都用守护线程：应用关闭时不希望还有人卡在等模型返回，导致进程迟迟退不掉。
 */
@Configuration
public class AgentExecutorConfig {

    @Bean(name = "agentStepExecutor", destroyMethod = "shutdownNow")
    public ExecutorService agentStepExecutor() {
        return Executors.newCachedThreadPool(namedDaemonThreadFactory("agent-step-"));
    }

    @Bean(name = "agentSseExecutor", destroyMethod = "shutdownNow")
    public ExecutorService agentSseExecutor() {
        return Executors.newCachedThreadPool(namedDaemonThreadFactory("agent-sse-"));
    }

    private static java.util.concurrent.ThreadFactory namedDaemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
