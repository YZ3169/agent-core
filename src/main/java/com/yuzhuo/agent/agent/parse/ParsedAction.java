package com.yuzhuo.agent.agent.parse;

import java.util.Map;

/**
 * 一轮模型输出被解析后的结果。
 *
 * <p>用 record：它是不可变的值对象，构造后不再改动，equals/hashCode/toString 自动生成，
 * 测试里可以直接断言相等，省掉一堆样板代码。
 *
 * @param type        这次输出的类型
 * @param thought     推理过程，用于 SSE 的 thought 事件与 trace
 * @param toolName    工具名，仅 TOOL_CALL 有效
 * @param arguments   工具参数，仅 TOOL_CALL 有效
 * @param finalAnswer 最终答案，仅 FINAL_ANSWER 有效
 * @param invalidReason 解析失败的原因，仅 INVALID 有效
 */
public record ParsedAction(
        ActionType type,
        String thought,
        String toolName,
        Map<String, Object> arguments,
        String finalAnswer,
        String invalidReason) {

    public static ParsedAction finalAnswer(String thought, String answer) {
        return new ParsedAction(ActionType.FINAL_ANSWER, thought, null, null, answer, null);
    }

    public static ParsedAction toolCall(String thought, String toolName, Map<String, Object> arguments) {
        return new ParsedAction(ActionType.TOOL_CALL, thought, toolName, arguments, null, null);
    }

    public static ParsedAction invalid(String thought, String reason) {
        return new ParsedAction(ActionType.INVALID, thought, null, null, null, reason);
    }
}
