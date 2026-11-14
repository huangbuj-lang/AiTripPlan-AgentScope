package utils;

import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** Skill 统一管理（适配 AgentScope 1.0.8）：Nacos skill 源 + 定时热刷。 */
@Slf4j  // @Slf4j：注入日志对象 log
public final class SkillManager {  // 类声明：Skill 统一管理（Nacos 源 + 定时热刷）

    private static volatile NacosSkillRepository repository; // 全局单例：缓存共享  // repository：全局单例（volatile 保证可见性）
    private static volatile Thread refreshThread;  // refreshThread：热刷线程
    private static volatile boolean started = false;  // started：是否已启动热刷（进程内只启一次）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private SkillManager() {}  // 私有构造：禁止 new

    /**
     * 作用：判断 Skill 热更是否启用（环境变量 SKILL_NACOS_ENABLED，默认启用）
     * 参数：无
     * 返回值：true=启用
     * 异常：无
     */
    public static boolean enabled() {  // enabled：Skill 热更开关
        return Config.get("SKILL_NACOS_ENABLED", "true").equalsIgnoreCase("true");  // 读配置 SKILL_NACOS_ENABLED（默认 true）
    }  // 方法结束

    /**
     * 作用：获取全局 Skill 仓库单例，不存在时创建
     * 参数：无
     * 返回值：NacosSkillRepository 实例
     * 异常：Exception 创建 Nacos 客户端失败时抛出
     */
    public static synchronized NacosSkillRepository repository() throws Exception {  // repository：取全局 Skill 仓库单例
        if (repository == null) {  // 还没创建
            repository = new NacosSkillRepository(NacosUtil.getNacosClient());  // 用 Nacos 客户端创建仓库
        }  // if 结束
        return repository;  // 返回单例
    }  // 方法结束

    /**
     * 作用：为 Agent 接线 Skill：创建 SkillBox、注册全部 skill 与 load_skill 工具、启动热刷线程，返回要拼进 sysPrompt 的技能说明
     * 参数：repo Skill 仓库；toolkit Agent 的工具包（SkillBox 绑定的必要参数）；refreshIntervalSec 热刷间隔秒数
     * 返回值：技能说明文本，失败或未启用时返回空串
     * 异常：无（内部捕获全部异常并降级为无 skill）
     */
    public static String wireAndPrompt(AgentSkillRepository repo, io.agentscope.core.tool.Toolkit toolkit, int refreshIntervalSec) {  // wireAndPrompt：给 Agent 接线 Skill
        try {  // try：任何失败都降级为“无 skill”
            if (!enabled()) {  // 开关关闭
                log.info("Skill 热更未启用(SKILL_NACOS_ENABLED=false)");  // info 日志
                return "";  // 返回空串（不注入技能说明）
            }  // if 结束
            SkillBox box = new SkillBox(toolkit); // 技能箱绑定 Agent 的 toolkit（load_skill 工具挂进工具组）  // SkillBox 绑定 Agent 的 toolkit（load_skill 工具要挂进工具组）
            box.registerSkillLoadTool();          // 注册 load_skill 工具  // 注册 load_skill 工具（模型用它按需加载技能正文）
            for (var skill : repo.getAllSkills()) {  // 遍历仓库里的全部 skill
                box.registerSkill(skill);  // 逐个注册进 SkillBox
            }  // for 结束
            startHotRefresh(repo, refreshIntervalSec); // 定时拉新版本并同步进 SkillBox  // 启动后台热刷线程
            String prompt = box.getSkillPrompt();  // 取技能说明文本（拼进 sysPrompt 的部分）
            log.info("★Skill 已接线: {} 个skill, prompt={} chars", repo.getAllSkills().size(),  // info 日志：skill 个数 + prompt 长度
                    prompt == null ? 0 : prompt.length());  // 续行
            return prompt == null ? "" : prompt;  // prompt 为 null 归一空串返回
        } catch (Exception e) {  // 捕获异常
            log.warn("Skill 接线失败(降级无skill): {}", e.getMessage());  // warn：降级为无 skill
            return "";  // 返回空串
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：Agent 侧安全入口：校验 toolkit 后调用 wireAndPrompt，任何失败都降级为无 skill
     * 参数：toolkit Agent 的工具包，不可为 null；refreshIntervalSec 热刷间隔秒数
     * 返回值：技能说明文本，失败时返回空串
     * 异常：无（异常内部捕获并降级）
     */
    public static String wireAndPromptSafely(io.agentscope.core.tool.Toolkit toolkit, int refreshIntervalSec) {  // wireAndPromptSafely：Agent 侧安全入口
        try {  // try
            if (toolkit == null) throw new IllegalArgumentException("Toolkit cannot be null");  // toolkit 不能为 null，否则抛参数异常
            return wireAndPrompt(repository(), toolkit, refreshIntervalSec);  // 委托 wireAndPrompt（内部取仓库单例）
        } catch (Exception e) {  // 捕获异常
            log.warn("Skill 接线失败(降级无skill): {}", e.getMessage());  // warn：降级
            return "";  // 返回空串
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：启动后台 Skill 热刷线程（进程内只启动一次，最小间隔 15s），版本变化时重载全部 skill
     * 参数：repo Skill 仓库；intervalSec 期望的热刷间隔秒数
     * 返回值：无
     * 异常：无
     */
    private static synchronized void startHotRefresh(AgentSkillRepository repo, int intervalSec) {  // startHotRefresh：启动后台热刷（只启一次）
        if (started) return; // 只启动一次  // 已启动就直接返回
        started = true;  // 置位
        int sec = Math.max(15, intervalSec);  // 最小间隔 15 秒（防配得太小打爆 Nacos）
        refreshThread = new Thread(() -> {  // 起守护线程
            while (!Thread.currentThread().isInterrupted()) {  // 未被中断就一直轮询
                try {  // try
                    Thread.sleep(sec * 1000L);  // 睡 intervalSec 秒
                    boolean changed = ((NacosSkillRepository) repo).refresh();  // refresh 拉新版本，返回是否变化
                    if (changed) log.info("★Skill 热更新完成: 当前 {} 个skill", repo.getAllSkills().size());  // 有变化就记日志
                } catch (InterruptedException ie) {  // 捕获中断
                    Thread.currentThread().interrupt();  // 重新置中断位（保持中断语义）
                    break;  // 退出循环
                } catch (Exception e) {  // 捕获其他异常
                    log.debug("Skill 刷新轮询异常(继续): {}", e.getMessage());  // debug 记一笔，下一轮继续
                }  // catch 结束
            }  // while 结束
        }, "skill-hot-refresh");  // 线程名 skill-hot-refresh
        refreshThread.setDaemon(true);  // 设为守护线程（不阻止进程退出）
        refreshThread.start();  // 启动线程
        log.info("★Skill 热刷线程已启动: 每{}秒检查一次", sec);  // info 日志：间隔秒数
    }  // 方法结束
}  // 类结束

