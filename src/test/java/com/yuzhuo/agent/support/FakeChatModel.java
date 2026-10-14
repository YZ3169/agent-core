package com.yuzhuo.agent.support;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个按剧本返回预置文本的 {@link ChatModel}。
 *
 * <p>它是整套测试能在无网络、无 API Key 的环境下跑起来的关键：ReActAgent 依赖的只是
 * ChatModel 这个接口，把接口钉死成"第 N 次调用返回第 N 条预置字符串"，
 * 循环的逻辑（步数、解析、工具执行、异常回灌、超时）就全都可以被确定性地验证。
 *
 * <p>同时记录收到的 Prompt，用来断言"Observation 真的被回灌进了下一轮的上下文"——
 * 只看最终答案无法区分"模型是照着 Observation 推的"还是"碰巧猜对的"。
 */
public class FakeChatModel implements ChatModel {

    private final Queue<String> script = new ConcurrentLinkedQueue<>();
    private final List<Prompt> receivedPrompts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile long delayMs;

    public FakeChatModel(String... responses) {
        reset(responses);
    }

    /** 重设剧本。同一个实例在多条测试间复用时需要它来清场。 */
    public final void reset(String... responses) {
        script.clear();
        script.addAll(Arrays.asList(responses));
        receivedPrompts.clear();
        callCount.set(0);
    }

    /** 让模型调用阻塞指定毫秒，用于验证单步超时。 */
    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }

    public int callCount() {
        return callCount.get();
    }

    public List<Prompt> receivedPrompts() {
        synchronized (receivedPrompts) {
            return new ArrayList<>(receivedPrompts);
        }
    }

    /** 第 index 次调用（从 0 开始）收到的完整上下文文本。 */
    public String promptTextAt(int index) {
        Prompt prompt = receivedPrompts().get(index);
        StringBuilder builder = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            builder.append(message.getText() == null ? "" : message.getText()).append('\n');
        }
        return builder.toString();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        callCount.incrementAndGet();
        receivedPrompts.add(prompt);

        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("模型调用被中断", e);
            }
        }

        String next = script.poll();
        if (next == null) {
            throw new IllegalStateException("FakeChatModel 剧本已用尽：第 " + callCount.get() + " 次调用没有预置响应");
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(next))));
    }
}
