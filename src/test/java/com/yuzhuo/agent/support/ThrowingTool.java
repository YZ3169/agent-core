package com.yuzhuo.agent.support;

import com.yuzhuo.agent.tool.AgentTool;

import java.util.Map;

/**
 * 一个永远失败的工具，用来验证"工具抛异常必须变成 Observation 回灌，而不是中断整轮"。
 *
 * <p>故意写成固定的测试替身，而不是让某个真实工具"碰巧失败"——
 * 测试要断言的是循环的错误处理路径，这条路径必须能被稳定地触发。
 */
public class ThrowingTool implements AgentTool {

    private final String name;

    public ThrowingTool(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "一个必定失败的工具，仅用于测试";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        throw new IllegalStateException("磁盘炸了");
    }
}
