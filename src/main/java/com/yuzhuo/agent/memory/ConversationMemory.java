package com.yuzhuo.agent.memory;

import com.yuzhuo.agent.config.AgentProperties;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按会话隔离的对话记忆，用字符预算做滑动裁剪。
 *
 * <p>为什么是"字符预算"而不是"消息条数"：一条 Observation 可能是 50 个字符，
 * 也可能是整张表的 2000 个字符。按条数裁剪会让上下文规模完全不可控，按字符裁才真的封住上限。
 * （更精确的做法是数 token，但那需要引入 tokenizer 依赖，对这个量级的项目是过度设计；
 * 字符数与 token 数在中文场景下近似线性，用它做预算足够。）
 *
 * <p>为什么是内存而不是 MySQL：这是会话级、短生命周期、可重建的数据，
 * 重启丢掉不影响任何业务事实。为了它引入一个数据库，收益远小于运维成本。
 * {@link ConcurrentHashMap} + 每个会话一把自己的锁，已足够应付多请求并发。
 */
@Component
public class ConversationMemory {

    private final int maxChars;
    private final Map<String, Deque<Message>> sessions = new ConcurrentHashMap<>();

    public ConversationMemory(AgentProperties properties) {
        this.maxChars = properties.getMemory().getMaxChars();
    }

    /** 追加一条消息，并立即按预算裁剪。 */
    public void append(String sessionId, Message message) {
        Deque<Message> messages = sessions.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(message);
            trim(messages);
        }
    }

    /**
     * 取出该会话当前的完整历史（已裁剪）。返回快照而不是内部引用，避免调用方在遍历时被并发修改。
     */
    public List<Message> history(String sessionId) {
        Deque<Message> messages = sessions.get(sessionId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    public int size(String sessionId) {
        Deque<Message> messages = sessions.get(sessionId);
        if (messages == null) {
            return 0;
        }
        synchronized (messages) {
            return messages.size();
        }
    }

    public int getMaxChars() {
        return maxChars;
    }

    /**
     * 从最老的一端开始丢，直到总字符数进入预算。
     *
     * <p>已知取舍：裁剪是单条粒度，不保证成对。极长的 ReAct 会话被裁到中途时，
     * 可能出现"Observation 还在、产生它的 Action 已丢"的残缺片段。
     * 因为每条 Observation 自身是自包含的文本，这种残缺不会让模型读不懂，
     * 只是少了一点推理链上下文 —— 比"一刀切清空历史"体验好得多。
     *
     * <p>至少保留最后一条：本轮刚追加的内容如果立刻被裁掉，模型就会对着空历史回答。
     */
    private void trim(Deque<Message> messages) {
        while (messages.size() > 1 && totalChars(messages) > maxChars) {
            messages.removeFirst();
        }
    }

    private int totalChars(Deque<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            String text = message.getText();
            total += text == null ? 0 : text.length();
        }
        return total;
    }
}
