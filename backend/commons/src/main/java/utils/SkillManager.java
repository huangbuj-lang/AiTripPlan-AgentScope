package utils;

import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** Skill 统一管理（适配 AgentScope 1.0.8）：Nacos skill 源 + 定时热刷。 */
@Slf4j
public final class SkillManager {

    private static volatile NacosSkillRepository repository; // 全局单例：缓存共享
    private static volatile Thread refreshThread;
    private static volatile boolean started = false;

    private SkillManager() {}

    /** 是否启用（环境变量开关，默认开） */
    public static boolean enabled() {
        return Config.get("SKILL_NACOS_ENABLED", "true").equalsIgnoreCase("true");
    }

    /** 获取/创建全局仓库单例 */
    public static synchronized NacosSkillRepository repository() throws Exception {
        if (repository == null) {
            repository = new NacosSkillRepository(NacosUtil.getNacosClient());
        }
        return repository;
    }

    /** 为 Agent 构建接线：创建 SkillBox、注册仓库内全部 skill、启动热刷线程、返回要拼进 sysPrompt 的技能说明。 */
    public static String wireAndPrompt(AgentSkillRepository repo, io.agentscope.core.tool.Toolkit toolkit, int refreshIntervalSec) {
        try {
            if (!enabled()) {
                log.info("Skill 热更未启用(SKILL_NACOS_ENABLED=false)");
                return "";
            }
            SkillBox box = new SkillBox(toolkit); // 技能箱绑定 Agent 的 toolkit（load_skill 工具挂进工具组）
            box.registerSkillLoadTool();          // 注册 load_skill 工具
            for (var skill : repo.getAllSkills()) {
                box.registerSkill(skill);
            }
            startHotRefresh(repo, refreshIntervalSec); // 定时拉新版本并同步进 SkillBox
            String prompt = box.getSkillPrompt();
            log.info("★Skill 已接线: {} 个skill, prompt={} chars", repo.getAllSkills().size(),
                    prompt == null ? 0 : prompt.length());
            return prompt == null ? "" : prompt;
        } catch (Exception e) {
            log.warn("Skill 接线失败(降级无skill): {}", e.getMessage());
            return "";
        }
    }

    /** 供 Agent 直接调用的安全入口：传 toolkit（SkillBox 绑定必需）； */
    public static String wireAndPromptSafely(io.agentscope.core.tool.Toolkit toolkit, int refreshIntervalSec) {
        try {
            if (toolkit == null) throw new IllegalArgumentException("Toolkit cannot be null");
            return wireAndPrompt(repository(), toolkit, refreshIntervalSec);
        } catch (Exception e) {
            log.warn("Skill 接线失败(降级无skill): {}", e.getMessage());
            return "";
        }
    }

    /** 定时热刷：版本变化时 reload 全部 skill 到已注册的 SkillBox */
    private static synchronized void startHotRefresh(AgentSkillRepository repo, int intervalSec) {
        if (started) return; // 只启动一次
        started = true;
        int sec = Math.max(15, intervalSec);
        refreshThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(sec * 1000L);
                    boolean changed = ((NacosSkillRepository) repo).refresh();
                    if (changed) log.info("★Skill 热更新完成: 当前 {} 个skill", repo.getAllSkills().size());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("Skill 刷新轮询异常(继续): {}", e.getMessage());
                }
            }
        }, "skill-hot-refresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
        log.info("★Skill 热刷线程已启动: 每{}秒检查一次", sec);
    }
}
