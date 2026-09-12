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
@Slf4j
public class NacosSkillRepository implements AgentSkillRepository {

    private final AiService aiService;
    private final Map<String, CachedSkill> cache = new ConcurrentHashMap<>();
    private volatile long lastVersion = -1; // 远端版本号（内容hash），变化才重建
    private volatile boolean writeable = false; // 本仓库只读（管理端经 Nacos 控制台上传）

    /** 缓存的 skill：名称 -> (AgentSkill, 原始md) */
    private record CachedSkill(AgentSkill skill, String md) {}

    public NacosSkillRepository(AiService aiService) {
        this.aiService = aiService;
        refresh(); // 初始拉取一次
    }

    /** 拉取全部 skill 并重建缓存； */
    public synchronized boolean refresh() {
        try {
            //1) 列出全部 skill 名：Nacos 3.2 admin API 无法从 AiService 列举，
            //约定用配置里的固定清单 SKILL_NAMES（逗号分隔），缺省为空=无skill。
            List<String> names = configuredSkillNames();
            if (names.isEmpty()) return false;
            boolean changed = false;
            long versionSum = 0;
            for (String name : names) {
                try {
                    //Nacos skill 版本状态机：draft 的包 client API 拉不到，必须 online 后用明确版本号拉。
                    byte[] zipBytes = null;
                    for (String ver : Config.get("SKILL_VERSION", "0.0.1").split("[,，]")) {
                        try {
                            zipBytes = aiService.downloadSkillZipByVersion(name, ver.trim());
                            if (zipBytes != null && zipBytes.length > 0) break;
                        } catch (Exception ignore) { }
                    }
                    if (zipBytes == null) { // 兜底：默认最新
                        zipBytes = aiService.downloadSkillZip(name);
                    }
                    if (zipBytes == null || zipBytes.length == 0) continue;
                    versionSum = versionSum * 31 + zipBytes.length; // 粗粒度版本：长度hash（够判断变更）
                    String md = readSkillMd(zipBytes);
                    if (md == null || md.isBlank()) continue;
                    CachedSkill existing = cache.get(name);
                    if (existing == null || !existing.md().equals(md)) {
                        cache.put(name, new CachedSkill(parseSkill(name, md), md));
                        changed = true;
                        log.info("★Skill 已加载/更新: {} ({} chars)", name, md.length());
                    }
                } catch (NacosException ne) {
                    if (existing0(name)) { // 远端删除了该skill：本地移除
                        cache.remove(name);
                        changed = true;
                        log.info("★Skill 已移除: {}", name);
                    }
                }
            }
            lastVersion = versionSum;
            return changed;
        } catch (Exception e) {
            log.warn("★Skill 刷新失败(保留旧快照): {}", e.getMessage());
            return false;
        }
    }

    /** 配置的 skill 清单 */
    private static List<String> configuredSkillNames() {
        String raw = Config.get("SKILL_NAMES", "");
        if (raw.isBlank()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : raw.split("[,，]")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** 从 zip 字节里读 SKILL.md 全文 */
    private static String readSkillMd(byte[] zipBytes) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("SKILL.md")) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** 解析 SKILL.md -> AgentSkill（front-matter name/description + 正文全文作为 skillContent） */
    private static AgentSkill parseSkill(String fallbackName, String md) {
        String name = fallbackName;
        String description = "";
        String content = md;
        //YAML front-matter: ---\nname: xx\ndescription: yy\n---
        Matcher fm = Pattern.compile("(?s)^---\\s*(.*?)\\s*---\\s*(.*)$").matcher(md);
        if (fm.find()) {
            Map<String, String> meta = parseSimpleYaml(fm.group(1));
            if (meta.containsKey("name")) name = meta.get("name");
            description = meta.getOrDefault("description", "");
            content = fm.group(2);
        } else {
            //无 front-matter：首行 # 标题当 name/description
            Matcher h1 = Pattern.compile("(?m)^#\\s+(.+)$").matcher(md);
            if (h1.find()) {
                description = h1.group(1).trim();
                name = fallbackName;
            }
        }
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("SKILL.md", md); // 全文也作为资源，供 load_skill 完整读取
        return new AgentSkill(name, description, content, resources, "nacos");
    }

    /** 极简 YAML 键值解析（name: value 形式） */
    private static Map<String, String> parseSimpleYaml(String yaml) {
        Map<String, String> out = new HashMap<>();
        for (String line : yaml.split("\n")) {
            int i = line.indexOf(':');
            if (i > 0) {
                out.put(line.substring(0, i).trim(), line.substring(i + 1).trim()
                        .replaceAll("^\"|\"$", ""));
            }
        }
        return out;
    }

    private boolean existing0(String name) { return cache.containsKey(name); }

    //==================== AgentSkillRepository 接口实现 ====================

    @Override public AgentSkill getSkill(String name) {
        CachedSkill c = cache.get(name);
        return c == null ? null : c.skill();
    }

    @Override public boolean skillExists(String name) { return cache.containsKey(name); }

    @Override public List<String> getAllSkillNames() { return new ArrayList<>(cache.keySet()); }

    @Override public List<AgentSkill> getAllSkills() {
        List<AgentSkill> out = new ArrayList<>();
        cache.values().forEach(c -> out.add(c.skill()));
        return out;
    }

    @Override public boolean save(List<AgentSkill> skills, boolean b) { return false; } // 只读：上传走 Nacos 控制台
    @Override public boolean delete(String name) { return false; }

    @Override public AgentSkillRepositoryInfo getRepositoryInfo() { return null; } // 可空：仅信息展示用

    @Override public String getSource() { return "nacos:" + Config.get("NACOS_SERVER_ADDR", "localhost:8848"); }

    @Override public void setWriteable(boolean w) { this.writeable = w; }
    @Override public boolean isWriteable() { return writeable; }

    /** 当前缓存快照条数（观测） */
    public int cachedCount() { return cache.size(); }
}
