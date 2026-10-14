package com.yuzhuo.agent.tool.builtin;

import com.yuzhuo.agent.config.AgentProperties;
import com.yuzhuo.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static com.yuzhuo.agent.tool.AgentTool.requireString;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

/**
 * 读取白名单根目录下文本文件的工具。
 *
 * <p>防目录穿越的两步（缺一不可）：
 * <ol>
 *   <li><b>规范化后判包含</b>：{@code root.resolve(userInput).normalize()} 会把 {@code ../../etc/passwd}
 *       折叠掉，然后判断结果是否仍在 root 之下。绝对路径会被 resolve 直接采纳，
 *       于是判定落在 root 之外而被拒 —— 这一步天然覆盖了绝对路径注入。</li>
 *   <li><b>解析真实路径后判包含</b>：规范化不解析符号链接。如果白名单目录里有一个指向
 *       {@code C:\Windows} 的软链接，第 1 步会放行、第 2 步能拦住。多这一步的代价是一次
 *       文件系统调用，换来的是"软链接绕过"这条路径也被堵上。</li>
 * </ol>
 */
@Component
public class FileReadTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    private final Path root;
    private final long maxBytes;

    public FileReadTool(AgentProperties properties) {
        this.root = Paths.get(properties.getTools().getFileRead().getRoot())
                .toAbsolutePath()
                .normalize();
        this.maxBytes = properties.getTools().getFileRead().getMaxBytes();
        // 根目录不存在时启动期就建出来，避免第一次调用才发现配置写错。
        // 建不出来只告警不阻断启动 —— 服务本身（比如只是查 SQL）不该因为读文件不可用就起不来。
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("白名单根目录创建失败，file_read 工具将不可用: root={}, cause={}", root, e.getMessage());
        }
        log.info("file_read 白名单根目录: {}（单文件上限 {} 字节）", root, maxBytes);
    }

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public String description() {
        return "读取白名单目录下的文本文件。参数是相对该目录的路径，例如 notes.txt。"
                + "不允许使用绝对路径，也不允许用 .. 跳出目录。";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{\
                "path":{"type":"string","description":"相对白名单根目录的文件路径，例如 notes.txt 或 sub/a.md"}\
                },"required":["path"]}""";
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String relativePath = requireString(arguments, "path");

        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException(
                    "拒绝访问：路径 '" + relativePath + "' 解析后超出了白名单目录，不允许目录穿越");
        }
        if (!Files.exists(candidate)) {
            throw new IllegalArgumentException("文件不存在: " + relativePath);
        }
        if (Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("'" + relativePath + "' 是一个目录，file_read 只读文件");
        }
        long size = Files.size(candidate);
        if (size > maxBytes) {
            throw new IllegalArgumentException(
                    "文件过大（" + size + " 字节），超过上限 " + maxBytes + " 字节，拒绝读取");
        }
        // 真实路径复核：挡住"白名单目录内的软链接指向外部"这种情况
        Path realCandidate = candidate.toRealPath();
        Path realRoot = root.toRealPath();
        if (!isWithin(realRoot, realCandidate)) {
            throw new IllegalArgumentException(
                    "拒绝访问：路径 '" + relativePath + "' 的真实位置在符号链接之外，超出了白名单目录");
        }

        return "文件 " + relativePath + " 的内容：\n" + Files.readString(candidate, StandardCharsets.UTF_8);
    }

    /**
     * Windows 文件系统大小写不敏感，而 {@link Path#startsWith} 是大小写敏感的，
     * 直接用会把 {@code D:\Data} 和 {@code d:\data} 判成两个目录、把合法读取误拒。
     * 非 Windows 保持严格比较。
     */
    private static boolean isWithin(Path parent, Path child) {
        if (child.startsWith(parent)) {
            return true;
        }
        if (isWindows()) {
            String parentPrefix = parent.toString().toLowerCase(Locale.ROOT) + File.separator;
            String childPath = child.toString().toLowerCase(Locale.ROOT) + File.separator;
            return childPath.startsWith(parentPrefix);
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
