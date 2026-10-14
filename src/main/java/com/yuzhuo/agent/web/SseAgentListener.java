package com.yuzhuo.agent.web;

import com.yuzhuo.agent.agent.AgentListener;
import com.yuzhuo.agent.error.AgentErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 Agent 的运行事件翻译成 SSE 事件帧。
 *
 * <p>事件名与 {@link AgentListener} 的方法一一对应：thought / tool_call / observation / answer / error。
 * 客户端按事件名分流即可，不需要解析一个大 JSON 去猜类型。
 *
 * <p>连接断开（浏览器关标签页、网络抖动）时 {@code send} 会抛 IOException。
 * 这里记一个标记位之后就不再尝试发送 —— 对已经没人听的连接反复重试，
 * 只会把日志刷满，还会拖慢整轮循环。
 */
public class SseAgentListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(SseAgentListener.class);

    private final SseEmitter emitter;
    private volatile boolean disconnected;

    public SseAgentListener(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onThought(int step, String thought) {
        send("thought", Map.of("step", step, "text", thought));
    }

    @Override
    public void onToolCall(int step, String toolName, Map<String, Object> arguments) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", step);
        payload.put("tool", toolName);
        payload.put("arguments", arguments);
        send("tool_call", payload);
    }

    @Override
    public void onObservation(int step, String toolName, String observation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", step);
        // toolName 可能为 null（模型输出格式不合法时的回灌），此时不放这个字段，
        // 而不是塞一个 null 让前端还要判空
        if (toolName != null) {
            payload.put("tool", toolName);
        }
        payload.put("text", observation);
        send("observation", payload);
    }

    @Override
    public void onAnswer(String answer) {
        send("answer", Map.of("text", answer));
    }

    @Override
    public void onError(AgentErrorCode errorCode, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", errorCode.code());
        payload.put("message", message);
        send("error", payload);
    }

    private void send(String eventName, Object payload) {
        if (disconnected) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            disconnected = true;
            log.debug("SSE 连接已断开，停止推送事件（{}）：{}", eventName, e.getMessage());
        }
    }
}
