package utils;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Nacos Skill 仓库（适配 AgentScope 1.0.8 的 AgentSkillRepository 接口）。 */
@Slf4j  // @Slf4j：注入日志对象 log
public class NacosSkillRepository implements AgentSkillRepository {  // 类声明：实现 AgentScope 的 AgentSkillRepository，数据源是 Nacos

    private final AiService aiService;  // aiService：Nacos AI 服务客户端（下载 skill 包）
    private final Map<String, CachedSkill> cache = new ConcurrentHashMap<>();  // cache：skill 名 → 缓存项（并发安全）
    private volatile long lastVersion = -1; // 远端版本号（内容hash），变化才重建  // lastVersion：远端版本粗hash（内容变化才重建）
    private volatile boolean writeable = false; // 本仓库只读（管理端经 Nacos 控制台上传）  // writeable：本仓库只读（管理端经 Nacos 控制台上传）

    /**
     * 作用：Skill 缓存项：保存解析后的 AgentSkill 与其 SKILL.md 原文
     * 参数：skill 解析后的技能对象；md SKILL.md 全文
     * 返回值：无
     * 异常：无
     */
    private record CachedSkill(AgentSkill skill, String md) {}  // CachedSkill：解析后的技能对象 + SKILL.md 原文
    /**
     * 作用：构造只读的 Nacos Skill 仓库：从 Nacos 拉取 skill 包并缓存解析结果
     * 参数：aiService Nacos AI 服务客户端
     * 返回值：无
     * 异常：无
     */

    public NacosSkillRepository(AiService aiService) {  // 构造器：绑定 Nacos 客户端
        this.aiService = aiService;  // 保存客户端引用
        refresh(); // 初始拉取一次  // 创建时先全量拉一次（启动即加载 skill）
    }  // 构造器结束

    /**
     * 作用：全量刷新：比对配置的 skill 清单，删除已下线的、拉取并解析新版本的 skill
     * 参数：无
     * 返回值：true=本次有变化；false=无变化或刷新失败
     * 异常：无（单个 skill 失败只记日志，其余继续）
     */
    public synchronized boolean refresh() {  // refresh：全量刷新（同步，防并发重入）
        try {  // try：任何异常都保留旧快照
            //1) 列出全部 skill 名：Nacos 3.2 admin API 无法从 AiService 列举，
            //约定用配置里的固定清单 SKILL_NAMES（逗号分隔），缺省为空=无skill。
            List<String> names = configuredSkillNames();  // names：配置里声明的 skill 清单
            if (names.isEmpty()) return false;  // 没配 skill → 返回无变化
            boolean changed = false;  // changed：本次是否有变化
            long versionSum = 0;  // versionSum：所有包长度的滚动 hash（粗版本号）
            for (String name : names) {  // 逐个 skill 处理
                try {  // try：单个 skill 失败不影响其他
                    //Nacos skill 版本状态机：draft 的包 client API 拉不到，必须 online 后用明确版本号拉。
                    byte[] zipBytes = null;  // zipBytes：skill 包内容，先置 null
                    for (String ver : Config.get("SKILL_VERSION", "0.0.1").split("[,，]")) {  // 遍历配置的候选版本号（SKILL_VERSION，逗号可中文逗号分隔）
                        try {  // try：某版本拉不到就试下一个
                            zipBytes = aiService.downloadSkillZipByVersion(name, ver.trim());  // 按版本号下载 skill 包
                            if (zipBytes != null && zipBytes.length > 0) break;  // 拿到内容就跳出（已经有包了）
                        } catch (Exception ignore) { }  // 失败忽略，试下一版本
                    }  // for 结束
                    if (zipBytes == null) { // 兜底：默认最新  // 所有版本都没拉到
                        zipBytes = aiService.downloadSkillZip(name);  // 兜底：按默认（最新）版本下载
                    }  // if 结束
                    if (zipBytes == null || zipBytes.length == 0) continue;  // 还是空 → 跳过该 skill
                    versionSum = versionSum * 31 + zipBytes.length; // 粗粒度版本：长度hash（够判断变更）  // 累进版本 hash（用包长度，够判断变没变）
                    String md = readSkillMd(zipBytes);  // 从包内读出 SKILL.md
                    if (md == null || md.isBlank()) continue;  // 读不到就跳过
                    CachedSkill existing = cache.get(name);  // existing：已有缓存
                    if (existing == null || !existing.md().equals(md)) {  // 新增，或内容变了
                        cache.put(name, new CachedSkill(parseSkill(name, md), md));  // 重新解析并放进缓存
                        changed = true;  // 标记有变化
                        log.info("★Skill 已加载/更新: {} ({} chars)", name, md.length());  // info 日志：已加载/更新 + 字符数
                    }  // if 结束
                } catch (NacosException ne) {  // 捕获 Nacos 异常（通常是远端已删除该 skill）
                    if (existing0(name)) { // 远端删除了该skill：本地移除  // 本地缓存里还有它
                        cache.remove(name);  // 从缓存移除
                        changed = true;  // 标记有变化
                        log.info("★Skill 已移除: {}", name);  // info 日志：已移除
                    }  // if 结束
                }  // catch 结束
            }  // for 结束
            lastVersion = versionSum;  // 记下本次版本 hash
            return changed;  // 返回是否有变化
        } catch (Exception e) {  // 捕获异常
            log.warn("★Skill 刷新失败(保留旧快照): {}", e.getMessage());  // warn：刷新失败，保留旧快照
            return false;  // 返回无变化
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：读取配置中声明的 skill 名称清单
     * 参数：无
     * 返回值：skill 名称列表，未配置时为空列表
     * 异常：无
     */
    private static List<String> configuredSkillNames() {  // configuredSkillNames：读配置里的 skill 名清单
        String raw = Config.get("SKILL_NAMES", "");  // raw：SKILL_NAMES 原始配置
        if (raw.isBlank()) return Collections.emptyList();  // 空 → 返回空列表
        List<String> out = new ArrayList<>();  // out：结果列表
        for (String s : raw.split("[,，]")) {  // 按英文/中文逗号拆分
            if (!s.isBlank()) out.add(s.trim());  // 非空就去空格加入
        }  // for 结束
        return out;  // 返回清单
    }  // 方法结束

    /**
     * 作用：从 skill 压缩包中读出 SKILL.md 文本
     * 参数：zipBytes skill 包的字节内容
     * 返回值：SKILL.md 文本，包内无该文件时返回空串
     * 异常：Exception 压缩包解析失败时抛出
     */
    private static String readSkillMd(byte[] zipBytes) throws Exception {  // readSkillMd：从 zip 包读 SKILL.md
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {  // try-with-resources 打开 zip 流
            ZipEntry entry;  // entry：当前压缩包条目
            while ((entry = zis.getNextEntry()) != null) {  // 遍历包内全部条目
                if (entry.getName().endsWith("SKILL.md")) {  // 文件名以 SKILL.md 结尾
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);  // 读全文按 UTF-8 解码并返回
                }  // if 结束
            }  // while 结束
        }  // try 结束
        return null;  // 包内没有 SKILL.md → 返回 null
    }  // 方法结束

    /**
     * 作用：把 SKILL.md 解析成 AgentSkill 对象（名称、描述、正文）
     * 参数：fallbackName 解析不到名称时的兜底名；md SKILL.md 全文
     * 返回值：解析出的 AgentSkill
     * 异常：无（缺字段时用兜底值）
     */
    private static AgentSkill parseSkill(String fallbackName, String md) {  // parseSkill：把 SKILL.md 解析成 AgentSkill
        String name = fallbackName;  // name 先取兜底名
        String description = "";  // description 初始空
        String content = md;  // content 初始为全文
        //YAML front-matter: ---\nname: xx\ndescription: yy\n---
        Matcher fm = Pattern.compile("(?s)^---\\s*(.*?)\\s*---\\s*(.*)$").matcher(md);  // 正则匹配 YAML front-matter（--- 开头、--- 结束，s 模式含换行）
        if (fm.find()) {  // 匹配到 front-matter
            Map<String, String> meta = parseSimpleYaml(fm.group(1));  // 把 front-matter 解析成键值 Map
            if (meta.containsKey("name")) name = meta.get("name");  // 有 name 字段就用它
            description = meta.getOrDefault("description", "");  // 取 description（缺失空串）
            content = fm.group(2);  // content 取 front-matter 之后的正文
        } else {  // 否则（没有 front-matter）
            //无 front-matter：首行 # 标题当 name/description
            Matcher h1 = Pattern.compile("(?m)^#\\s+(.+)$").matcher(md);  // 正则找一级标题 # xxx
            if (h1.find()) {  // 找到了
                description = h1.group(1).trim();  // 把标题当描述
                name = fallbackName;  // 名称仍用兜底名
            }  // if 结束
        }  // if 结束
        Map<String, String> resources = new LinkedHashMap<>();  // resources：附加资源表
        resources.put("SKILL.md", md); // 全文也作为资源，供 load_skill 完整读取  // 把 SKILL.md 全文作为资源，供 load_skill 完整读取
        return new AgentSkill(name, description, content, resources, "nacos");  // 构造 AgentSkill（来源标 nacos）
    }  // 方法结束

    /**
     * 作用：解析 SKILL.md frontmatter 的简单 key: value 结构
     * 参数：yaml frontmatter 文本
     * 返回值：键值 Map（值已去引号）
     * 异常：无
     */
    private static Map<String, String> parseSimpleYaml(String yaml) {  // parseSimpleYaml：解析极简 key: value
        Map<String, String> out = new HashMap<>();  // out：结果 Map
        for (String line : yaml.split("\n")) {  // 逐行处理
            int i = line.indexOf(':');  // 找第一个冒号位置
            if (i > 0) {  // 冒号不在行首（是有效键值对）
                out.put(line.substring(0, i).trim(), line.substring(i + 1).trim()  // 键 = 冒号前（去空格），值 = 冒号后
                        .replaceAll("^\"|\"$", ""));  // 续：去掉值两端的双引号
            }  // if 结束
        }  // for 结束
        return out;  // 返回键值表
    }  // 方法结束
    /**
     * 作用：判断某个 skill 名是否已在缓存中（内部快速判重）
     * 参数：name skill 名称
     * 返回值：true=已存在
     * 异常：无
     */

    private boolean existing0(String name) { return cache.containsKey(name); }  // existing0：判断该 skill 是否已在缓存

    //==================== AgentSkillRepository 接口实现 ====================
    /**
     * 作用：按名称取缓存的 skill
     * 参数：name skill 名称
     * 返回值：AgentSkill，不存在时返回 null
     * 异常：无
     */
    @Override public AgentSkill getSkill(String name) {  // getSkill：按名取技能对象
        CachedSkill c = cache.get(name);  // 取缓存项
        return c == null ? null : c.skill();  // 没有返回 null，有就返回技能对象
    }  // 方法结束
    /**
     * 作用：判断 skill 是否存在
     * 参数：name skill 名称
     * 返回值：true=存在
     * 异常：无
     */
    @Override public boolean skillExists(String name) { return cache.containsKey(name); }  // skillExists：判断 skill 是否存在
    /**
     * 作用：列出缓存中全部 skill 名称
     * 参数：无
     * 返回值：名称列表（快照拷贝）
     * 异常：无
     */
    @Override public List<String> getAllSkillNames() { return new ArrayList<>(cache.keySet()); }  // getAllSkillNames：全部 skill 名（拷贝快照）
    /**
     * 作用：列出缓存中全部 skill 对象
     * 参数：无
     * 返回值：AgentSkill 列表（快照拷贝）
     * 异常：无
     */
    @Override public List<AgentSkill> getAllSkills() {  // getAllSkills：全部 skill 对象
        List<AgentSkill> out = new ArrayList<>();  // out：结果列表
        cache.values().forEach(c -> out.add(c.skill()));  // 遍历缓存取值
        return out;  // 返回
    }  // 方法结束
    /**
     * 作用：只读仓库的空实现：上传 skill 走 Nacos 控制台
     * 参数：skills 待保存的 skill 列表；b 覆盖标志
     * 返回值：恒为 false
     * 异常：无
     */
    @Override public boolean save(List<AgentSkill> skills, boolean b) { return false; } // 只读：上传走 Nacos 控制台  // save：只读仓库，恒返回 false（上传走 Nacos 控制台）
    /**
     * 作用：只读仓库的空实现：删除 skill 走 Nacos 控制台
     * 参数：name 待删除的 skill 名称
     * 返回值：恒为 false
     * 异常：无
     */
    @Override public boolean delete(String name) { return false; }  // delete：只读仓库，恒返回 false
    /**
     * 作用：返回仓库信息（仅展示用，本实现不下发）
     * 参数：无
     * 返回值：恒为 null
     * 异常：无
     */
    @Override public AgentSkillRepositoryInfo getRepositoryInfo() { return null; } // 可空：仅信息展示用  // getRepositoryInfo：仅展示用，本实现返回 null
    /**
     * 作用：返回仓库来源标识（Nacos 地址）
     * 参数：无
     * 返回值：形如 nacos:localhost:8848 的字符串
     * 异常：无
     */
    @Override public String getSource() { return "nacos:" + AgentEndpoints.nacosAddr(); }  // getSource：返回来源标识 nacos:地址
    /**
     * 作用：设置仓库可写标志（接口要求，本实现不生效）
     * 参数：w 是否可写
     * 返回值：无
     * 异常：无
     */
    @Override public void setWriteable(boolean w) { this.writeable = w; }  // setWriteable：接口要求，只记录标志
    /**
     * 作用：读取仓库可写标志
     * 参数：无
     * 返回值：恒为 false（只读仓库）
     * 异常：无
     */
    @Override public boolean isWriteable() { return writeable; }  // isWriteable：返回可写标志（本仓库只读）

    /**
     * 作用：读取当前缓存的 skill 条数（观测用）
     * 参数：无
     * 返回值：缓存条数
     * 异常：无
     */
    public int cachedCount() { return cache.size(); }  // cachedCount：当前缓存的 skill 条数（观测用）
}  // 类结束

