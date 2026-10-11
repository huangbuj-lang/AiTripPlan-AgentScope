package utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ★2026-09-11 规范文件外置：sysPrompt 用的 agent-rules.md 优先从<b>工作目录磁盘</b>读取，
 * 回退 classpath（jar 内）；首次启动自动把内置规则导出到磁盘，之后直接编辑磁盘文件即可生效，
 * <b>不用重新打包</b>（原来规则打进 jar，改一条 prompt 也要重打对应服务的包）。
 *
 * 目录由环境变量 AGENT_RULES_DIR 指定，默认 "agent-rules"（相对进程工作目录）。
 * 文件名各模块不同，便于分别编辑，如 tripPlanner-agent-rules.md / routeMaking-agent-rules.md。
 */
public final class ExternalRules {

    private ExternalRules() {}

    /** 外置规则目录（环境变量 AGENT_RULES_DIR 可覆盖，默认工作目录下的 agent-rules/） */
    public static String dir() {
        String d = System.getenv("AGENT_RULES_DIR");
        return (d == null || d.isBlank()) ? "agent-rules" : d.trim();
    }

    /**
     * 读取规则文件。
     *
     * @param fileName 规则文件名（同时作为 classpath 内的资源名），如 tripPlanner-agent-rules.md
     * @return 规则正文；磁盘与 classpath 都没有时返回 null（调用方自行回退默认文本）
     */
    public static String load(String fileName) {
        return load(fileName, fileName);
    }

    /**
     * 读取规则文件（磁盘文件名与 jar 内资源名不同时用这个重载）。
     *
     * @param diskFileName      磁盘上的文件名（<AGENT_RULES_DIR>/<diskFileName>）
     * @param classpathResource classpath 内的资源名（jar 内，如 agent-rules.md）
     * @return 规则正文；磁盘与 classpath 都没有时返回 null（调用方自行回退默认文本）
     */
    public static String load(String diskFileName, String classpathResource) {
        Path disk = Paths.get(dir(), diskFileName);

        // 1) 磁盘优先：直接改文件即可生效，免重打包
        try {
            if (Files.exists(disk)) {
                String s = Files.readString(disk, StandardCharsets.UTF_8);
                if (s != null && !s.isBlank()) {
                    System.out.println("[ExternalRules] 使用磁盘规则: " + disk.toAbsolutePath());
                    return s;
                }
            }
        } catch (Exception e) {
            System.err.println("[ExternalRules] 读磁盘规则失败(" + disk + "): " + e.getMessage());
        }

        // 2) 回退 classpath（jar 内），并导出到磁盘供后续编辑
        try (var in = ExternalRules.class.getResourceAsStream("/" + classpathResource)) {
            if (in == null) {
                System.err.println("[ExternalRules] 磁盘与内置均无规则: " + diskFileName + " / " + classpathResource);
                return null;
            }
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            exportToDisk(disk, s);
            System.out.println("[ExternalRules] 使用内置规则并导出到: " + disk.toAbsolutePath());
            return s;
        } catch (Exception e) {
            System.err.println("[ExternalRules] 读内置规则失败(" + classpathResource + "): " + e.getMessage());
            return null;
        }
    }

    /** 首次导出：仅当磁盘不存在该文件时写入（不覆盖用户已编辑的内容） */
    private static void exportToDisk(Path disk, String content) {
        try {
            if (disk.getParent() != null) Files.createDirectories(disk.getParent());
            if (!Files.exists(disk)) {
                Files.writeString(disk, content, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[ExternalRules] 导出规则失败(" + disk + "): " + e.getMessage());
        }
    }
}
