package managerAgent.controller;

import org.springframework.web.bind.annotation.*;
import utils.Config;
import java.util.HashMap;
import java.util.Map;
import managerAgent.service.AdminPipelineService;
import jakarta.annotation.Resource;

@RestController
@RequestMapping("/api/admin")
public class AdminPipelineController {

    @Resource
    private AdminPipelineService adminPipelineService;

    /** 定价专用刷新：只跑 pricing.py（秒级），不碰采集管线 */
    @PostMapping("/pricing/run")
    public Map<String, Object> runPricing() {
        return adminPipelineService.runPricing();
    }

    /** 客流预测专用刷新：只跑 forecast.py（本地统计 trip_plans 生成 forecast_output，秒级，不调高德/采集管线） */
    @PostMapping("/forecast/run")
    public Map<String, Object> runForecast() {
        return adminPipelineService.runForecast();
    }


    @PostMapping("/pipeline/run")
    public Map<String, Object> runPipeline() {
        return adminPipelineService.runPipeline();
    }

    /** 查询更新任务状态 */
    @GetMapping("/pipeline/status")
    public Map<String, Object> pipelineStatus() {
        return adminPipelineService.pipelineStatus();
    }

    /** 停止正在执行的数据更新任务（杀掉 python 子进程树） */
    @PostMapping("/pipeline/stop")
    public Map<String, Object> stopPipeline() {
        return adminPipelineService.stopPipeline();
    }
}
