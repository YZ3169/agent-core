package com.yuzhuo.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuo.agent.agent.parse.ActionParser;
import com.yuzhuo.agent.agent.parse.ActionType;
import com.yuzhuo.agent.agent.parse.ParsedAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作解析是手写 ReAct 循环里最容易出错的一环：模型不保证按格式输出。
 * 这些用例覆盖"标准输入"与"各种不听话的输入"，把解析器的行为钉死。
 */
class ActionParserTest {

    private final ActionParser parser = new ActionParser(new ObjectMapper());

    @Test
    @DisplayName("标准 Final Answer：抽出 thought 与答案")
    void parsesFinalAnswer() {
        ParsedAction action = parser.parse("""
                Thought: 我已经知道答案了
                Final Answer: 华东区销售额最高。
                """);

        assertThat(action.type()).isEqualTo(ActionType.FINAL_ANSWER);
        assertThat(action.thought()).isEqualTo("我已经知道答案了");
        assertThat(action.finalAnswer()).isEqualTo("华东区销售额最高。");
    }

    @Test
    @DisplayName("标准 Action：抽出工具名与 JSON 参数")
    void parsesToolCall() {
        ParsedAction action = parser.parse("""
                Thought: 需要算一下
                Action: calculator
                Action Input: {"expression": "(128000+39800)*0.06"}
                """);

        assertThat(action.type()).isEqualTo(ActionType.TOOL_CALL);
        assertThat(action.thought()).isEqualTo("需要算一下");
        assertThat(action.toolName()).isEqualTo("calculator");
        assertThat(action.arguments()).containsEntry("expression", "(128000+39800)*0.06");
    }

    @Test
    @DisplayName("模型把 JSON 包在 markdown 代码块里也要能解析")
    void parsesFencedJson() {
        ParsedAction action = parser.parse("""
                Thought: 查一下
                Action: sql_query
                Action Input: ```json
                {"sql": "SELECT region FROM sales_order LIMIT 5"}
                ```
                """);

        assertThat(action.type()).isEqualTo(ActionType.TOOL_CALL);
        assertThat(action.arguments()).containsEntry("sql", "SELECT region FROM sales_order LIMIT 5");
    }

    @Test
    @DisplayName("Action Input 不是合法 JSON：归为 INVALID 并带上原因")
    void invalidJsonBecomesInvalid() {
        ParsedAction action = parser.parse("""
                Thought: 查一下
                Action: sql_query
                Action Input: SELECT * FROM sales_order
                """);

        assertThat(action.type()).isEqualTo(ActionType.INVALID);
        assertThat(action.invalidReason()).contains("不是合法的 JSON");
    }

    @Test
    @DisplayName("写了 Action 却没给 Action Input")
    void missingActionInput() {
        ParsedAction action = parser.parse("Thought: 想想\nAction: calculator");

        assertThat(action.type()).isEqualTo(ActionType.INVALID);
        assertThat(action.invalidReason()).contains("没有给出");
    }

    @Test
    @DisplayName("既没有 Action 也没有 Final Answer")
    void noMarkerAtAll() {
        ParsedAction action = parser.parse("我觉得这个问题挺复杂的，让我再想想。");

        assertThat(action.type()).isEqualTo(ActionType.INVALID);
        assertThat(action.invalidReason()).contains("既没有");
    }

    @Test
    @DisplayName("空输出")
    void blankOutput() {
        assertThat(parser.parse("   ").type()).isEqualTo(ActionType.INVALID);
        assertThat(parser.parse(null).type()).isEqualTo(ActionType.INVALID);
    }

    @Test
    @DisplayName("同时出现 Action 与 Final Answer 时以 Final Answer 为准，避免多执行一次工具")
    void finalAnswerWinsOverAction() {
        ParsedAction action = parser.parse("""
                Thought: 其实我已经知道了
                Action: calculator
                Action Input: {"expression": "1+1"}
                Final Answer: 答案是 2
                """);

        assertThat(action.type()).isEqualTo(ActionType.FINAL_ANSWER);
        assertThat(action.finalAnswer()).isEqualTo("答案是 2");
    }

    @Test
    @DisplayName("Final Answer 后面是空的")
    void emptyFinalAnswer() {
        ParsedAction action = parser.parse("Thought: 嗯\nFinal Answer:   ");

        assertThat(action.type()).isEqualTo(ActionType.INVALID);
        assertThat(action.invalidReason()).contains("空的");
    }

    @Test
    @DisplayName("大小写不敏感：模型写 final answer / ACTION 也要认")
    void caseInsensitiveMarkers() {
        assertThat(parser.parse("THOUGHT: t\nFINAL ANSWER: done").type()).isEqualTo(ActionType.FINAL_ANSWER);
        assertThat(parser.parse("action: calculator\naction input: {\"expression\":\"1\"}").type())
                .isEqualTo(ActionType.TOOL_CALL);
    }
}
