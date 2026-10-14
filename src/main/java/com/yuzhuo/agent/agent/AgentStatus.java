package com.yuzhuo.agent.agent;

/** 一次 Agent 运行的终态。调用方靠它判断"是正常答完了，还是撞上了护栏"。 */
public enum AgentStatus {

    /** 模型给出了最终答案。 */
    COMPLETED,

    /** 用完 max-steps 仍没给出最终答案。是护栏生效，不是崩溃。 */
    MAX_STEPS_EXCEEDED,

    /** 运行过程中失败（无模型、超时、模型调用失败等），错误码见响应体。 */
    ERROR
}
