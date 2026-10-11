package managerAgent.tool;

import lombok.extern.slf4j.Slf4j;
import utils.Config;

import java.io.File;

/**
 * 项目根目录定位（模型配置、数据管线脚本都从这里找 data-pipeline/**）。
 */
@Slf4j
public final class ProjectPaths {

    private ProjectPaths() {
    }

    /** 项目根目录（定位失败返回 null） */
    public static File root() {
        try {
            String configured = Config.get("PROJECT_ROOT", "");
            if (!configured.isBlank()) {
                java.io.File configuredDir = new java.io.File(configured).getCanonicalFile();
                if (new java.io.File(configuredDir, "data-pipeline").isDirectory()) return configuredDir;
            }
            java.io.File dir = new java.io.File(System.getProperty("user.dir")).getCanonicalFile();
            while (dir != null) {
                if (new java.io.File(dir, "data-pipeline").isDirectory()
                        && new java.io.File(dir, "backend").isDirectory()) {
                    return dir;
                }
                dir = dir.getParentFile();
            }
        } catch (Exception e) {
            log.warn("解析项目根目录失败: {}", e.getMessage());
        }
        return new java.io.File(System.getProperty("user.dir"));
    }
}
