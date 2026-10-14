package com.yuzhuo.agent.web;

import com.yuzhuo.agent.agent.AgentListener;
import com.yuzhuo.agent.agent.AgentRequest;
import com.yuzhuo.agent.agent.AgentResponse;
import com.yuzhuo.agent.agent.AgentStatus;
import com.yuzhuo.agent.agent.ReActAgent;
import com.yuzhuo.agent.config.AgentProperties;
import com.yuzhuo.agent.error.AgentErrorCode;
import com.yuzhuo.agent.error.AgentException;
import com.yuzhuo.agent.trace.TraceSession;
import com.yuzhuo.agent.trace.TraceStore;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;

/**
 * Agent 的 HTTP 入口。
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ReActAgent agent;
    private final TraceStore traceStore;
    private final AgentProperties properties;
    private final ExecutorService sseExecutor;

    public AgentController(ReActAgent agent,
                           TraceStore traceStore,
                           AgentProperties properties,
                           @Qualifier("agentSseExecutor") ExecutorService sseExecutor) {
        this.agent = agent;
        this.traceStore = traceStore;
        this.properties = properties;
        this.sseExecutor = sseExecutor;
    }

    /**
     * 流式接口。按事件类型推送 thought / tool_call / observation / answer（失败时是 error）。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        AgentRequest agentRequest = new AgentRequest(request.sessionId(), request.message()).normalized();

        // 整个循环的最坏耗时 = 每一步的超时 × 步数，再留两倍余量给收尾。
        // 不给 SseEmitter 设超时的话，一个卡住的会话会永久占着容器线程。
        long timeoutMs = properties.getStepTimeoutMs() * (properties.getMaxSteps() + 2);
        SseEmitter emitter = new SseEmitter(timeoutMs);

        sseExecutor.execute(() -> {
            SseAgentListener listener = new SseAgentListener(emitter);
            try {
                agent.run(agentRequest, listener);
                emitter.complete();
            } catch (AgentException e) {
                listener.onError(e.getErrorCode(), e.getMessage());
                emitter.complete();
            } catch (Exception e) {
                // 兜底：SSE 已经返回 200 了，改不了状态码，只能把错误作为最后一个事件推给客户端
                log.error("SSE 会话异常终止（sessionId={}）", agentRequest.sessionId(), e);
                listener.onError(AgentErrorCode.INTERNAL_ERROR, "服务内部错误：" + e.getMessage());
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 同步接口：返回完整结果，便于测试与不需要流式的调用方。
     *
     * <p>没有 API Key 时返回 HTTP 503 + 结构化错误码 1001，不是 500 ——
     * "服务是好的，只是模型没配"和"服务内部炸了"必须让调用方分得清。
     */
    @PostMapping("/chat/sync")
    public ResponseEntity<AgentResponse> chatSync(@Valid @RequestBody ChatRequest request) {
        AgentRequest agentRequest = new AgentRequest(request.sessionId(), request.message()).normalized();

        AgentResponse response;
        try {
            response = agent.run(agentRequest, AgentListener.NOOP);
        } catch (AgentException e) {
            // 超时 / 模型调用失败这类异常走到这里，同样翻译成结构化响应
            response = AgentResponse.error(agentRequest.sessionId(), e.getErrorCode(), e.getMessage(), 0);
        }
        return ResponseEntity.status(resolveHttpStatus(response)).body(response);
    }

    /** 回放某会话最近一次运行的调用链。没有记录时返回 404，而不是空对象 —— 空对象无法区分"没跑过"和"跑过但没调工具"。 */
    @GetMapping("/trace/{sessionId}")
    public ResponseEntity<TraceSession> trace(@PathVariable String sessionId) {
        return traceStore.find(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static HttpStatus resolveHttpStatus(AgentResponse response) {
        if (AgentStatus.ERROR.name().equals(response.status())) {
            return AgentErrorCode.fromCode(response.errorCode()).httpStatus();
        }
        // COMPLETED 与 MAX_STEPS_EXCEEDED 都是"跑完了"，HTTP 层是成功的；
        // 后者靠响应体里的 status/errorCode 区分，调用方据此决定要不要提示用户换个问法
        return HttpStatus.OK;
    }
}
