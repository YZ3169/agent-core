package com.yuzhuo.agent.agent.parse;

/** 模型一轮输出的三种归宿。 */
public enum ActionType {

    /** 模型认为信息够了，给出最终答案，循环结束。 */
    FINAL_ANSWER,

    /** 模型要求调用某个工具。 */
    TOOL_CALL,

    /**
     * 输出不符合约定格式（没写 Action、Action Input 不是合法 JSON 等）。
     *
     * <p>这不是"错误"，而是需要回灌给模型的一次反馈：把解析失败的原因作为 Observation 发回去，
     * 模型下一轮通常就能自我纠正。直接中断整轮才是最差的选择。
     */
    INVALID
}
