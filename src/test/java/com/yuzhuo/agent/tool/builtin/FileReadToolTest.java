package com.yuzhuo.agent.tool.builtin;

import com.yuzhuo.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目录穿越是这类工具最经典的漏洞，所以这里的关键用例是"把越界目标真的建出来" ——
 * 如果只是断言抛异常，无法区分"被安全策略拦住"和"文件恰好不存在"。
 */
class FileReadToolTest {

    @TempDir
    Path root;

    private FileReadTool tool;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getTools().getFileRead().setRoot(root.toString());
        properties.getTools().getFileRead().setMaxBytes(64);
        tool = new FileReadTool(properties);
    }

    @Test
    @DisplayName("读取白名单目录下的文件")
    void readsFileInsideRoot() throws Exception {
        Files.writeString(root.resolve("notes.txt"), "这是一份笔记", StandardCharsets.UTF_8);

        String result = tool.execute(Map.of("path", "notes.txt"));

        assertThat(result).contains("notes.txt").contains("这是一份笔记");
    }

    @Test
    @DisplayName("允许读取子目录里的文件")
    void readsFileInSubdirectory() throws Exception {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/a.txt"), "子目录内容", StandardCharsets.UTF_8);

        assertThat(tool.execute(Map.of("path", "sub/a.txt"))).contains("子目录内容");
    }

    @Test
    @DisplayName("拒绝 .. 目录穿越（目标文件真实存在，证明是策略拦住的）")
    void rejectsParentTraversal() throws IOException {
        Path outside = root.getParent().resolve("outside-secret.txt");
        Files.writeString(outside, "不该被读到的内容", StandardCharsets.UTF_8);

        try {
            assertThatThrownBy(() -> tool.execute(Map.of("path", "../outside-secret.txt")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超出了白名单目录");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("拒绝多层 .. 穿越")
    void rejectsDeepTraversal() throws IOException {
        Path outside = root.getParent().resolve("deep-secret.txt");
        Files.writeString(outside, "机密", StandardCharsets.UTF_8);

        try {
            assertThatThrownBy(() -> tool.execute(Map.of("path", "a/b/../../../../deep-secret.txt")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超出了白名单目录");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("拒绝绝对路径")
    void rejectsAbsolutePath() throws IOException {
        Path outside = root.getParent().resolve("abs-secret.txt");
        Files.writeString(outside, "机密", StandardCharsets.UTF_8);

        try {
            assertThatThrownBy(() -> tool.execute(Map.of("path", outside.toString())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超出了白名单目录");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("文件不存在：给出明确提示，而不是空内容")
    void reportsMissingFile() {
        assertThatThrownBy(() -> tool.execute(Map.of("path", "nope.txt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不存在");
    }

    @Test
    @DisplayName("目标是目录：拒绝")
    void rejectsDirectory() throws IOException {
        Files.createDirectories(root.resolve("adir"));

        assertThatThrownBy(() -> tool.execute(Map.of("path", "adir")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("是一个目录");
    }

    @Test
    @DisplayName("超过大小上限：拒绝，避免一个文件吃光上下文预算")
    void rejectsOversizedFile() throws IOException {
        Files.writeString(root.resolve("big.txt"), "x".repeat(200), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> tool.execute(Map.of("path", "big.txt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件过大");
    }

    @Test
    @DisplayName("缺少 path 参数")
    void rejectsMissingArgument() {
        assertThatThrownBy(() -> tool.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填参数");
    }

    @Test
    @DisplayName("工具自描述完整：名字、说明、JSON-Schema 都非空")
    void exposesMetadata() {
        assertThat(tool.name()).isEqualTo("file_read");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.parametersSchema()).contains("\"path\"").contains("required");
    }
}
