package com.yuzhuo.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本项目的可调参数，对应 application.yml 里的 {@code agent.*}。
 *
 * <p>集中放在一个类里而不是散落 @Value，是为了让「这个 Agent 有哪些可调旋钮」一眼可见。
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 手写循环的步数上限，护栏的核心 */
    private int maxSteps = 6;

    /** 单步超时（模型调用 + 工具执行合起来算一步） */
    private long stepTimeoutMs = 30_000L;

    private final Memory memory = new Memory();
    private final Tools tools = new Tools();

    public static class Memory {
        /** 会话历史字符预算，超出后从头丢最老的消息 */
        private int maxChars = 8_000;

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }
    }

    public static class Tools {
        private final Sql sql = new Sql();
        private final FileRead fileRead = new FileRead();

        public Sql getSql() {
            return sql;
        }

        public FileRead getFileRead() {
            return fileRead;
        }
    }

    public static class Sql {
        /** 单次查询最多回灌的行数 */
        private int maxRows = 50;
        /** 单条 SQL 的数据库侧超时 */
        private int queryTimeoutSeconds = 5;

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }

        public int getQueryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }
    }

    public static class FileRead {
        /** 白名单根目录 */
        private String root = "data/agent-files";
        /** 单个文件的大小上限 */
        private long maxBytes = 64 * 1024;

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public long getStepTimeoutMs() {
        return stepTimeoutMs;
    }

    public void setStepTimeoutMs(long stepTimeoutMs) {
        this.stepTimeoutMs = stepTimeoutMs;
    }

    public Memory getMemory() {
        return memory;
    }

    public Tools getTools() {
        return tools;
    }
}
