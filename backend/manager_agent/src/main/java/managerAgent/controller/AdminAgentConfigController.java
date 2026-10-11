package managerAgent.controller;

import managerAgent.tool.SecretMasker;
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
import managerAgent.service.AdminAgentConfigService;
import jakarta.annotation.Resource;

@RestController
@RequestMapping("/api/admin")
public class AdminAgentConfigController {

    @Resource
    private AdminAgentConfigService adminAgentConfigService;

    /** Read effective agent runtime switches. */
    @GetMapping("/agent-config")
    public Map<String, Object> getAgentConfig() {
        return adminAgentConfigService.getAgentConfig();
    }

    /** Save switches to Nacos. */
    @PostMapping("/agent-config")
    public Map<String, Object> saveAgentConfig(@RequestParam Boolean amap,
                                               @RequestParam Boolean localKnowledge,
                                               @RequestParam Boolean remoteRouteAgent,
                                               @RequestParam Boolean remoteTripAgent) {
        return adminAgentConfigService.saveAgentConfig(amap, localKnowledge, remoteRouteAgent, remoteTripAgent);
    }

    //==================== Agent 提示词与工具描述 ====================

    /** Read prompts managed by Nacos Prompt management; the admin UI is read-only. */
    @GetMapping("/agent-prompts")
    public Map<String, Object> getAgentPrompts() {
        return adminAgentConfigService.getAgentPrompts();
    }

    //==================== 高德凭证 ====================

    /** Show masked AMap keys; blank inputs on save mean "keep the current key". */
    @GetMapping("/amap-credentials")
    public Map<String, Object> getAmapCredentials() {
        return adminAgentConfigService.getAmapCredentials();
    }

    /** Save AMap keys to Nacos. */
    @PostMapping("/amap-credentials")
    public Map<String, Object> saveAmapCredentials(
            @RequestParam(required = false) String webKey,
            @RequestParam(required = false) String mcpKey) {
        return adminAgentConfigService.saveAmapCredentials(webKey, mcpKey);
    }
}
