package com.yuzhuo.agent.memory;

import com.yuzhuo.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryTest {

    private static ConversationMemory memoryWithBudget(int maxChars) {
        AgentProperties properties = new AgentProperties();
        properties.getMemory().setMaxChars(maxChars);
        return new ConversationMemory(properties);
    }

    @Test
    @DisplayName("预算之内：全部保留，且保持先后顺序")
    void keepsEverythingWithinBudget() {
        ConversationMemory memory = memoryWithBudget(100);
        memory.append("s", new UserMessage("aaa"));
        memory.append("s", new AssistantMessage("bbb"));

        assertThat(memory.history("s")).hasSize(2);
        assertThat(memory.history("s").get(0).getText()).isEqualTo("aaa");
        assertThat(memory.history("s").get(1).getText()).isEqualTo("bbb");
    }

    @Test
    @DisplayName("超出预算：从最老的一端滑掉，最新的内容一定留得住")
    void trimsOldestWhenOverBudget() {
        ConversationMemory memory = memoryWithBudget(30);
        memory.append("s", new UserMessage("1".repeat(10)));
        memory.append("s", new UserMessage("2".repeat(10)));
        memory.append("s", new UserMessage("3".repeat(10)));
        memory.append("s", new UserMessage("4".repeat(10)));

        assertThat(memory.size("s")).isEqualTo(3);
        assertThat(memory.history("s")).extracting(message -> message.getText().substring(0, 1))
                .containsExactly("2", "3", "4");
    }

    @Test
    @DisplayName("单条就超预算时至少保留它：否则模型会对着空历史回答")
    void alwaysKeepsAtLeastOneMessage() {
        ConversationMemory memory = memoryWithBudget(10);
        memory.append("s", new UserMessage("x".repeat(500)));

        assertThat(memory.size("s")).isEqualTo(1);
    }

    @Test
    @DisplayName("会话之间互相隔离")
    void isolatesSessions() {
        ConversationMemory memory = memoryWithBudget(1000);
        memory.append("a", new UserMessage("A 的话"));
        memory.append("b", new UserMessage("B 的话"));

        assertThat(memory.history("a")).hasSize(1);
        assertThat(memory.history("a").get(0).getText()).isEqualTo("A 的话");
        assertThat(memory.history("b").get(0).getText()).isEqualTo("B 的话");
    }

    @Test
    @DisplayName("未知会话返回空列表而不是 null")
    void unknownSessionIsEmpty() {
        assertThat(memoryWithBudget(100).history("never-seen")).isEmpty();
    }

    @Test
    @DisplayName("clear 清掉指定会话")
    void clearRemovesSession() {
        ConversationMemory memory = memoryWithBudget(100);
        memory.append("s", new UserMessage("hi"));
        memory.clear("s");

        assertThat(memory.history("s")).isEmpty();
    }

    @Test
    @DisplayName("history 返回快照，外部改动不影响内部状态")
    void historyIsSnapshot() {
        ConversationMemory memory = memoryWithBudget(100);
        memory.append("s", new UserMessage("hi"));

        memory.history("s").clear();

        assertThat(memory.size("s")).isEqualTo(1);
    }
}
