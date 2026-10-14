package com.yuzhuo.agent.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一个会话最近一次运行的调用链。
 *
 * <p>写操作集中在 ReActAgent 的单线程执行流里，读操作来自 HTTP 线程（GET /agent/trace），
 * 所以用并发容器兜住可见性，不额外加锁 —— 回放接口读到的是"某一瞬间的快照"，
 * 这次运行还在进行中时读到半截也是可接受的语义。
 */
public class TraceSession {

    private final String sessionId;
    private final Instant startedAt = Instant.now();
    private final List<ModelCallRecord> modelCalls = new CopyOnWriteArrayList<>();
    private final List<ToolCallRecord> toolCalls = new CopyOnWriteArrayList<>();
    private volatile String status = "RUNNING";
    private volatile int steps;
    private volatile long durationMs;
    private volatile Instant finishedAt;

    public TraceSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public void addModelCall(ModelCallRecord record) {
        modelCalls.add(record);
    }

    public void addToolCall(ToolCallRecord record) {
        toolCalls.add(record);
    }

    public void finish(String status, int steps, long durationMs) {
        this.status = status;
        this.steps = steps;
        this.durationMs = durationMs;
        this.finishedAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public int getSteps() {
        return steps;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public List<ModelCallRecord> getModelCalls() {
        return new ArrayList<>(modelCalls);
    }

    public List<ToolCallRecord> getToolCalls() {
        return new ArrayList<>(toolCalls);
    }
}
