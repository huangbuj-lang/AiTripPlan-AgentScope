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
public final class ExternalRules {  // 类声明：规则文件外置加载（磁盘优先，回退 classpath）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private ExternalRules() {}  // 私有构造：禁止 new

    /**
     * 作用：取外部规则文件目录（缺省为工作目录下的规则目录）
     * 参数：无
     * 返回值：目录路径字符串
     * 异常：无
     */
    public static String dir() {  // dir：取规则文件目录
        String d = System.getenv("AGENT_RULES_DIR");  // 读环境变量 AGENT_RULES_DIR
        return (d == null || d.isBlank()) ? "agent-rules" : d.trim();  // 没配就用默认目录 agent-rules（相对工作目录）
    }  // 方法结束

    /**
     * 作用：按文件名加载规则：优先读磁盘文件，缺失时从 classpath 读并导出到磁盘
     * 参数：fileName 规则文件名
     * 返回值：规则文本；都取不到返回空串
     * 异常：无（读取异常内部捕获）
     */
    public static String load(String fileName) {  // load（单参）：磁盘名与资源名相同
        return load(fileName, fileName);  // 委托两参重载
    }  // 方法结束

    /**
     * 作用：按显式文件名与 classpath 资源名加载规则（便于磁盘文件与资源名不一致的场景）
     * 参数：diskFileName 磁盘文件名；classpathResource classpath 资源路径
     * 返回值：规则文本；都取不到返回空串
     * 异常：无（读取异常内部捕获）
     */
    public static String load(String diskFileName, String classpathResource) {  // load（双参）：磁盘文件名 + classpath 资源名
        Path disk = Paths.get(dir(), diskFileName);  // disk：磁盘上的目标路径

        // 1) 磁盘优先：直接改文件即可生效，免重打包
        try {  // try：读盘异常不抛出
            if (Files.exists(disk)) {  // 磁盘文件存在
                String s = Files.readString(disk, StandardCharsets.UTF_8);  // 按 UTF-8 读全文
                if (s != null && !s.isBlank()) {  // 内容非空
                    System.out.println("[ExternalRules] 使用磁盘规则: " + disk.toAbsolutePath());  // 打印“使用磁盘规则”+ 绝对路径
                    return s;  // 直接返回磁盘内容（改文件即生效，免重打包）
                }  // if 结束
            }  // if 结束
        } catch (Exception e) {  // 捕获异常
            System.err.println("[ExternalRules] 读磁盘规则失败(" + disk + "): " + e.getMessage());  // 打印读盘失败原因
        }  // catch 结束

        // 2) 回退 classpath（jar 内），并导出到磁盘供后续编辑
        try (var in = ExternalRules.class.getResourceAsStream("/" + classpathResource)) {  // 回退：从 classpath（jar 内）读，try-with-resources 自动关流
            if (in == null) {  // 资源不存在
                System.err.println("[ExternalRules] 磁盘与内置均无规则: " + diskFileName + " / " + classpathResource);  // 打印“磁盘与内置均无规则”
                return null;  // 返回 null
            }  // if 结束
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);  // 读全部字节按 UTF-8 解码
            exportToDisk(disk, s);  // 导出到磁盘，供后续直接编辑
            System.out.println("[ExternalRules] 使用内置规则并导出到: " + disk.toAbsolutePath());  // 打印“使用内置规则并导出”
            return s;  // 返回内置内容
        } catch (Exception e) {  // 捕获异常
            System.err.println("[ExternalRules] 读内置规则失败(" + classpathResource + "): " + e.getMessage());  // 打印读内置失败
            return null;  // 返回 null
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：把 classpath 内容导出到磁盘文件，便于运维直接编辑
     * 参数：disk 目标路径；content 文件内容
     * 返回值：无
     * 异常：无（写失败只记日志）
     */
    private static void exportToDisk(Path disk, String content) {  // exportToDisk：把内置内容写到磁盘
        try {  // try：写失败只记日志
            if (disk.getParent() != null) Files.createDirectories(disk.getParent());  // 父目录不存在就先创建
            if (!Files.exists(disk)) {  // 文件已存在就不覆盖
                Files.writeString(disk, content, StandardCharsets.UTF_8);  // 写入 UTF-8 文本
            }  // if 结束
        } catch (Exception e) {  // 捕获异常
            System.err.println("[ExternalRules] 导出规则失败(" + disk + "): " + e.getMessage());  // 打印导出失败
        }  // catch 结束
    }  // 方法结束
}  // 类结束

