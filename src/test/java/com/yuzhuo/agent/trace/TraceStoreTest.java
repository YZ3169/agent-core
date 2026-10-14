package com.yuzhuo.agent.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceStoreTest {

    @Test
    @DisplayName("记录工具调用与模型调用，结束后状态可回放")
    void recordsCalls() {
        TraceStore store = new TraceStore();
        TraceSession session = store.startRun("s1");

        session.addModelCall(new ModelCallRecord(1, 120, 500, 80));
        session.addToolCall(new ToolCallRecord(1, "calculator", Map.of("expression", "1+1"), 3, true, "1+1 = 2"));
        session.finish("COMPLETED", 1, 250);

        TraceSession found = store.find("s1").orElseThrow();
        assertThat(found.getStatus()).isEqualTo("COMPLETED");
        assertThat(found.getSteps()).isEqualTo(1);
        assertThat(found.getDurationMs()).isEqualTo(250);
        assertThat(found.getToolCalls()).hasSize(1);
        assertThat(found.getToolCalls().get(0).arguments()).containsEntry("expression", "1+1");
        assertThat(found.getModelCalls()).hasSize(1);
        assertThat(found.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("同一会话再跑一次时覆盖上一次的 trace：回放看到的永远是最近一次")
    void startRunReplacesPrevious() {
        TraceStore store = new TraceStore();
        store.startRun("s1").addToolCall(new ToolCallRecord(1, "old", Map.of(), 1, true, "旧记录"));

        store.startRun("s1").addToolCall(new ToolCallRecord(1, "new", Map.of(), 1, true, "新记录"));

        assertThat(store.find("s1").orElseThrow().getToolCalls())
                .extracting(ToolCallRecord::tool)
                .containsExactly("new");
    }

    @Test
    @DisplayName("从未运行过的会话返回空 Optional，由接口层翻译成 404")
    void unknownSession() {
        assertThat(new TraceStore().find("nobody")).isEmpty();
    }

    @Test
    @DisplayName("返回的列表是快照，外部修改不影响内部")
    void returnsSnapshots() {
        TraceStore store = new TraceStore();
        TraceSession session = store.startRun("s1");
        session.addToolCall(new ToolCallRecord(1, "t", Map.of(), 1, true, "x"));

        session.getToolCalls().clear();

        assertThat(session.getToolCalls()).hasSize(1);
    }
}
