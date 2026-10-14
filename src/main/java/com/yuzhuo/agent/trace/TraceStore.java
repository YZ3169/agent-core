package com.yuzhuo.agent.trace;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用链存储。和 {@link com.yuzhuo.agent.memory.ConversationMemory} 一样选择内存：
 * 它是排障用的短期数据，不是业务数据，落库反而带来"谁来清理"的新问题。
 *
 * <p>只保留每个会话最近一次运行。理由：回放接口的用途是"刚才那一轮发生了什么"，
 * 保留全部历史需要一个分页接口和保留策略，对这个项目是过度设计。
 */
@Component
public class TraceStore {

    private final Map<String, TraceSession> sessions = new ConcurrentHashMap<>();

    /** 开始一段新运行：覆盖该会话上一次的 trace，保证回放看到的永远是最近一次。 */
    public TraceSession startRun(String sessionId) {
        TraceSession session = new TraceSession(sessionId);
        sessions.put(sessionId, session);
        return session;
    }

    public Optional<TraceSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** 供测试与运维清场。 */
    public void clear() {
        sessions.clear();
    }
}
