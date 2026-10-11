package managerAgent.controller;

import managerAgent.tool.HttpProbe;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import org.springframework.web.bind.annotation.*;
import utils.CircuitBreaker;
import utils.NacosUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import managerAgent.service.AdminSystemService;
import jakarta.annotation.Resource;

@RestController
@RequestMapping("/api/admin")
public class AdminSystemController {

    @Resource
    private AdminSystemService adminSystemService;

    /** 各 Agent 健康/注册状态、端口、Nacos 连接情况 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return adminSystemService.status();
    }

    //==================== 任务监控 ====================

    /** LLM 成本观测：各维度(任务/管线/论坛AI等)的调用次数与token消耗 + 全局汇总 */
    @GetMapping("/costs")
    public Map<String, Object> costs() {
        return adminSystemService.costs();
    }

    /** Agent健康度：主管(本进程指标) + 子Agent(探活8082/8085 + 主管侧A2A成功率) 三卡综合 */
    @GetMapping("/agents/health")
    public Map<String, Object> agentsHealth() {
        return adminSystemService.agentsHealth();
    }

    /** 工具层细粒度观测：各子Agent的综合工具/知识库/内部MCP工具 调用数与成功率（探活 /stats 聚合） */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        return adminSystemService.tools();
    }

    //==================== 模型监控 ====================

    /** 熔断器状态 / 失败计数 / 主备模型 / 限速 QPS */
    //==================== 模型配置热更 ====================
    @GetMapping("/breaker")
    public Map<String, Object> breaker() {
        return adminSystemService.breaker();
    }
}
