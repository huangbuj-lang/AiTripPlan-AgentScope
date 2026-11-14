package managerAgent.service;

import managerAgent.tool.SecretMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import utils.AgentPrompts;
import utils.AgentPromptStore;
import utils.AgentRuntimeConfig;
import utils.Config;
import utils.ExternalServices;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/** AdminAgentConfig 的业务实现（自原控制器等价平移，逻辑未改）。 */
@Slf4j
@Service
public class AdminAgentConfigService {


    private final AtomicBoolean agentRestarting = new AtomicBoolean();
    /**
     * 作用：读取 Agent 运行时开关与提示词概览（管理端 Agent 配置页）
     * 参数：无
     * 返回值：配置 Map（工具开关/远程 Agent 开关/提示词定制状态）
     * 异常：无
     */

    public Map<String, Object> getAgentConfig() {
        Map<String, Object> r = new HashMap<>();
        Map<String, Object> tools = new HashMap<>();
        tools.put("amap", AgentRuntimeConfig.toolEnabled("amap", true));
        tools.put("localKnowledge", AgentRuntimeConfig.toolEnabled("localKnowledge", true));
        tools.put("remoteRouteAgent", AgentRuntimeConfig.toolEnabled("remoteRouteAgent", true));
        tools.put("remoteTripAgent", AgentRuntimeConfig.toolEnabled("remoteTripAgent", true));
        r.put("tools", tools);
        r.put("source", AgentRuntimeConfig.loaded() ? "nacos" : "default");
        r.put("restartInProgress", agentRestarting.get());
        r.put("success", true);
        return r;
    }
    /**
     * 作用：保存 Agent 运行时开关并发布到 Nacos 热更（高德工具/本地知识库/两个远程子 Agent）
     * 参数：amap 高德工具开关；localKnowledge 本地知识库开关；remoteRouteAgent 路线子 Agent 开关；remoteTripAgent 行程子 Agent 开关；均允许为 null 表示不改
     * 返回值：保存结果 Map
     * 异常：无（发布失败返回含错误信息的 Map）
     */

    public Map<String, Object> saveAgentConfig(Boolean amap, Boolean localKnowledge, Boolean remoteRouteAgent, Boolean remoteTripAgent) {
        Map<String, Object> r = new HashMap<>();
        if (amap == null || localKnowledge == null || remoteRouteAgent == null || remoteTripAgent == null) {
            r.put("success", false);
            r.put("message", "保存失败：工具开关不能为空");
            return r;
        }
        try {
            boolean oldAmap = AgentRuntimeConfig.toolEnabled("amap", true);
            boolean oldLocalKnowledge = AgentRuntimeConfig.toolEnabled("localKnowledge", true);
            boolean restartRequired = oldAmap != amap || oldLocalKnowledge != localKnowledge;

            Map<String, Object> config = new java.util.LinkedHashMap<>(AgentRuntimeConfig.currentConfig());
            Map<String, Object> tools = new java.util.LinkedHashMap<>(AgentRuntimeConfig.toolsConfig());
            tools.put("amap", amap);
            tools.put("localKnowledge", localKnowledge);
            tools.put("remoteRouteAgent", remoteRouteAgent);
            tools.put("remoteTripAgent", remoteTripAgent);
            config.put("tools", tools);
            config.put("updatedAt", Instant.now().toString());
            AgentRuntimeConfig.publish(config);

            r.put("success", true);
            r.put("restartRequired", restartRequired);
            if (!restartRequired) {
                r.put("restartTriggered", false);
                r.put("message", "配置已保存到Nacos，远程Agent开关调用前热生效");
            } else if (startAgentRestart()) {
                r.put("restartTriggered", true);
                r.put("message", "配置已保存到Nacos；正在重启路线/行程子Agent（8082/8085）");
            } else {
                r.put("restartTriggered", false);
                r.put("message", "配置已保存到Nacos；已有子Agent重启任务在进行中");
            }
        } catch (Exception e) {
            log.warn("Save agent runtime config failed: {}", e.getMessage(), e);
            r.put("success", false);
            r.put("message", "保存失败：" + e.getMessage());
        }
        return r;
    }
    /**
     * 作用：读取全部提示词（内置默认值与当前定制值）
     * 参数：无
     * 返回值：提示词 Map（键/中文名/默认/当前值/是否定制）
     * 异常：无
     */

    public Map<String, Object> getAgentPrompts() {
        Map<String, Object> r = new HashMap<>();
        Map<String, Object> prompts = new LinkedHashMap<>();
        Map<String, String> labels = AgentPrompts.labels();
        AgentPrompts.defaults().forEach((key, defaultValue) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("label", labels.getOrDefault(key, key));
            item.put("value", AgentPromptStore.prompt(key, defaultValue));
            item.put("defaultValue", defaultValue);
            item.put("nacosPromptKey", AgentPromptStore.promptKey(key));
            item.put("customized", AgentPromptStore.customized(key));
            item.put("editable", false);
            prompts.put(key, item);
        });
        r.put("prompts", prompts);
        r.put("source", AgentPromptStore.source());
        r.put("success", true);
        return r;
    }
    /**
     * 作用：读取高德凭据配置（脱敏展示）
     * 参数：无
     * 返回值：凭据 Map（Web Key / MCP Key，已脱敏）
     * 异常：无
     */

    public Map<String, Object> getAmapCredentials() {
        Map<String, Object> r = new HashMap<>();
        String webKey = ExternalServices.amapKey();
        String mcpKey = ExternalServices.amapMcpKey();
        Map<String, Object> stored = AgentRuntimeConfig.credentialsConfig();
        r.put("webKeyMasked", SecretMasker.mask(webKey));
        r.put("webKeyConfigured", webKey != null && !webKey.isBlank());
        r.put("mcpKeyMasked", SecretMasker.mask(mcpKey));
        r.put("mcpKeyConfigured", mcpKey != null && !mcpKey.isBlank());
        r.put("source", stored.containsKey("amapKey") || stored.containsKey("amapMcpKey")
                ? "nacos" : "env");
        r.put("restartInProgress", agentRestarting.get());
        r.put("success", true);
        return r;
    }
    /**
     * 作用：保存高德凭据并发布到 Nacos 热更
     * 参数：webKey 高德 Web 服务 Key；mcpKey 高德 MCP Key；均允许为空表示不改
     * 返回值：保存结果 Map
     * 异常：无（发布失败返回含错误信息的 Map）
     */

    public Map<String, Object> saveAmapCredentials(String webKey, String mcpKey) {
        Map<String, Object> r = new HashMap<>();
        try {
            webKey = webKey == null ? "" : webKey.trim();
            mcpKey = mcpKey == null ? "" : mcpKey.trim();
            if (webKey.isBlank() && mcpKey.isBlank()) {
                r.put("success", true);
                r.put("message", "没有需要保存的高德Key修改");
                return r;
            }

            String oldWebKey = ExternalServices.amapKey();
            String oldMcpKey = ExternalServices.amapMcpKey();
            Map<String, Object> credentials = new java.util.LinkedHashMap<>(AgentRuntimeConfig.credentialsConfig());
            if (!webKey.isBlank()) credentials.put("amapKey", webKey);
            if (!mcpKey.isBlank()) credentials.put("amapMcpKey", mcpKey);

            String storedWeb = String.valueOf(credentials.getOrDefault("amapKey", "")).trim();
            String storedMcp = String.valueOf(credentials.getOrDefault("amapMcpKey", "")).trim();
            String newWebKey = storedWeb.isBlank() ? oldWebKey : storedWeb;
            String newMcpKey = storedMcp.isBlank()
                    ? (storedWeb.isBlank() ? oldMcpKey : storedWeb)
                    : storedMcp;
            boolean webChanged = !newWebKey.equals(oldWebKey);
            boolean mcpChanged = !newMcpKey.equals(oldMcpKey);
            if (!webChanged && !mcpChanged) {
                r.put("success", true);
                r.put("restartRequired", false);
                r.put("restartTriggered", false);
                r.put("message", "高德Key未变化");
                return r;
            }

            Map<String, Object> config = new java.util.LinkedHashMap<>(AgentRuntimeConfig.currentConfig());
            config.put("credentials", credentials);
            config.put("updatedAt", Instant.now().toString());
            AgentRuntimeConfig.publish(config);

            r.put("success", true);
            r.put("restartRequired", mcpChanged);
            if (!mcpChanged) {
                r.put("restartTriggered", false);
                r.put("message", "高德Key已保存到Nacos，主管接口热生效");
            } else if (startAgentRestart()) {
                r.put("restartTriggered", true);
                r.put("message", "高德Key已保存到Nacos；MCP Key变化，正在重启路线/行程子Agent（8082/8085）");
            } else {
                r.put("restartTriggered", false);
                r.put("message", "高德Key已保存到Nacos；已有子Agent重启任务在进行中");
            }
        } catch (Exception e) {
            log.warn("Save AMap credentials failed: {}", e.getMessage(), e);
            r.put("success", false);
            r.put("message", "保存失败：" + e.getMessage());
        }
        return r;
    }
    /**
     * 作用：异步重启三个 Agent 进程（管理端一键重启）
     * 参数：无
     * 返回值：true=重启脚本已启动；false=脚本不存在或启动失败
     * 异常：无（异常内部捕获）
     */

    private boolean startAgentRestart() {
        if (!agentRestarting.compareAndSet(false, true)) return false;
        Thread thread = new Thread(() -> {
            try {
                java.io.File root = managerAgent.tool.ProjectPaths.root();
                boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
                java.io.File script = new java.io.File(root, windows ? "restart-agents.ps1" : "restart-agents.sh");
                if (!script.isFile()) {
                    log.error("Agent restart script not found: {}", script.getAbsolutePath());
                    return;
                }
                java.io.File logDir = new java.io.File(root, "logs");
                if (!logDir.isDirectory() && !logDir.mkdirs()) {
                    log.error("Agent restart log directory cannot be created: {}", logDir.getAbsolutePath());
                    return;
                }
                java.io.File logFile = new java.io.File(logDir, "agent-tool-restart.log");
                ProcessBuilder processBuilder;
                if (windows) {
                    String windir = System.getenv("WINDIR");
                    java.io.File powershell = windir == null
                            ? null
                            : new java.io.File(windir, "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
                    String executable = powershell != null && powershell.isFile()
                            ? powershell.getAbsolutePath()
                            : "powershell";
                    processBuilder = new ProcessBuilder(executable, "-NoProfile", "-NonInteractive",
                            "-ExecutionPolicy", "Bypass", "-File", script.getCanonicalPath());
                } else {
                    processBuilder = new ProcessBuilder(bashExecutable(),
                            script.getCanonicalPath().replace('\\', '/'));
                }
                processBuilder.directory(root);
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFile));
                log.info("Restarting child agents using {}", processBuilder.command());
                Process process = processBuilder.start();
                int code = process.waitFor();
                if (code == 0) {
                    log.info("Child agents restarted after agent tool switch change");
                } else {
                    log.error("Child agent restart failed with code {}: {}", code, logFile.getAbsolutePath());
                }
            } catch (Exception e) {
                log.error("Child agent restart failed", e);
            } finally {
                agentRestarting.set(false);
            }
        }, "agent-tool-restart");
        thread.setDaemon(true);
        thread.start();
        return true;
    }
    /**
     * 作用：定位可用的 bash 可执行文件（供重启脚本调用）
     * 参数：无
     * 返回值：bash 路径；找不到返回空串
     * 异常：无
     */

    private String bashExecutable() {
        String configured = Config.get("BASH_BIN", "");
        if (!configured.isBlank()) return configured;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return "bash";

        for (String key : new String[]{"GIT_BASH_PATH", "HERMES_GIT_BASH_PATH"}) {
            String configuredPath = System.getenv(key);
            if (configuredPath != null && new java.io.File(configuredPath).isFile()) {
                return configuredPath;
            }
        }

        String gitBash = bashExecutableFromGitWhere();
        if (gitBash != null) return gitBash;

        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String path : pathEnv.split(";")) {
                if (path.isBlank()) continue;
                java.io.File directory = new java.io.File(path);
                roots.add(directory.getPath());
                java.io.File parent = directory.getParentFile();
                if (parent != null) roots.add(parent.getPath());
            }
        }
        for (String key : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) roots.add(value);
        }
        roots.add("C:\\Program Files");
        roots.add("C:\\Program Files (x86)");

        for (String root : roots) {
            for (String relative : new String[]{"Git\\bin\\bash.exe", "Git\\usr\\bin\\bash.exe",
                    "bin\\bash.exe", "usr\\bin\\bash.exe"}) {
                java.io.File candidate = new java.io.File(root, relative);
                if (candidate.isFile()) return candidate.getAbsolutePath();
            }
        }

        log.warn("Windows Git Bash not found; falling back to PATH bash. Set BASH_BIN to Git Bash's bash.exe");
        return "bash";
    }
    /**
     * 作用：通过 git 安装目录推断 bash 路径
     * 参数：无
     * 返回值：bash 路径；推断不到返回空串
     * 异常：无
     */

    private String bashExecutableFromGitWhere() {
        try {
            Process process = new ProcessBuilder("where.exe", "git").start();
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    process.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    java.io.File git = new java.io.File(line.trim());
                    if (!git.isFile()) continue;

                    java.io.File directory = git.getParentFile();
                    for (int depth = 0; depth < 3 && directory != null; depth++) {
                        for (String relative : new String[]{"bin\\bash.exe", "usr\\bin\\bash.exe"}) {
                            java.io.File candidate = new java.io.File(directory, relative);
                            if (candidate.isFile()) return candidate.getAbsolutePath();
                        }
                        directory = directory.getParentFile();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Cannot locate Git Bash through where.exe git", e);
        }
        return null;
    }
}
