package com.yuzhuo.agent.agent;

import com.yuzhuo.agent.error.AgentErrorCode;

import java.util.Map;

/**
 * Agent 运行过程的事件出口。
 *
 * <p>SSE 接口与测试都通过它观察循环内部：接口把它翻译成 HTTP 事件流，
 * 测试用它断言"循环确实按预期走了这几步"。循环本身完全不知道外面是 SSE 还是别的东西。
 *
 * <p>所有方法都有默认空实现，实现方只关心自己需要的事件，不必为无关回调写空方法。
 */
public interface AgentListener {

    /** 模型这一轮的推理内容。 */
    default void onThought(int step, String thought) {
    }

    /** 决定调用工具，工具尚未执行。 */
    default void onToolCall(int step, String toolName, Map<String, Object> arguments) {
    }

    /** 工具执行完毕（成功或失败），observation 是要回灌给模型的内容。 */
    default void onObservation(int step, String toolName, String observation) {
    }

    /** 最终答案。正常结束与达到 max-steps 都会触发，保证客户端总能收到一个终止事件。 */
    default void onAnswer(String answer) {
    }

    /** 运行失败。与 onAnswer 互斥。 */
    default void onError(AgentErrorCode errorCode, String message) {
    }

    /** 什么都不做的实现，用于不关心过程的调用场景。 */
    AgentListener NOOP = new AgentListener() {
    };
}
