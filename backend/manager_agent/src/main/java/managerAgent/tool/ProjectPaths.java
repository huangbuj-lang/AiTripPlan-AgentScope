package managerAgent.tool;

import lombok.extern.slf4j.Slf4j;
import utils.Config;

import java.io.File;

/**
 * 项目根目录定位（模型配置、数据管线脚本都从这里找 data-pipeline/**）。
 */
@Slf4j  // @Slf4j：注入日志对象 log
public final class ProjectPaths {  // 类声明：项目根目录定位工具
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private ProjectPaths() {  // 私有构造：禁止 new
    }  // 构造器结束

    /**
     * 作用：定位项目根目录（从当前工作目录向上找到含 pom.xml 的层级）
     * 参数：无
     * 返回值：项目根目录 File 对象
     * 异常：无
     */
    public static File root() {  // root 签名：返回项目根目录
        try {  // try：解析失败不抛出
            String configured = Config.get("PROJECT_ROOT", "");  // 先读配置 PROJECT_ROOT
            if (!configured.isBlank()) {  // 配置非空
                java.io.File configuredDir = new java.io.File(configured).getCanonicalFile();  // 转成规范路径 File
                if (new java.io.File(configuredDir, "data-pipeline").isDirectory()) return configuredDir;  // 该目录下有 data-pipeline 就认定是根目录，直接返回
            }  // if 结束
            java.io.File dir = new java.io.File(System.getProperty("user.dir")).getCanonicalFile();  // 否则从当前工作目录 user.dir 开始向上找
            while (dir != null) {  // 逐级上溯直到根
                if (new java.io.File(dir, "data-pipeline").isDirectory()  // 同时存在 data-pipeline 与 backend 两个目录，
                        && new java.io.File(dir, "backend").isDirectory()) {  // 续：说明这就是项目根目录
                    return dir;  // 返回找到的根目录
                }  // if 结束
                dir = dir.getParentFile();  // 上移一层继续找
            }  // while 结束
        } catch (Exception e) {  // 捕获异常
            log.warn("解析项目根目录失败: {}", e.getMessage());  // warn 日志：解析根目录失败
        }  // catch 结束
        return new java.io.File(System.getProperty("user.dir"));  // 兜底：返回当前工作目录
    }  // 方法结束
}  // 类结束

