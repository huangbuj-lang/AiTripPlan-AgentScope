package routeMakingAgent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import routeMakingAgent.mcp.BaiduMapTool;
import utils.SafeModel;

import java.util.HashMap;
import java.util.Map;

/**
 * 观测接口：返回本进程的模型调用次数 + MCP调用次数（临时调试用）
 */
@RestController
public class StatsController {

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        m.put("model_calls", SafeModel.MODEL_CALLS.get()); // 本进程模型调用次数
        m.put("mcp_calls", BaiduMapTool.MCP_CALLS.get());  // 本进程MCP网络请求次数
        return m;
    }
}
