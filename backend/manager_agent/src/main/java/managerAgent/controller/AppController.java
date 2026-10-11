package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.TripTaskService;
import managerAgent.tool.AmapPlaceGeocoder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.SafeModel;

import java.util.HashMap;
import java.util.Map;

/** 用户与Agent互动的HTTP接口（业务在 TripTaskService）。 */
@RestController
public class AppController {

    @Resource
    private TripTaskService tripTaskService;

    /** HTTP入口：提交任务给主管Agent异步处理 */
    @RequestMapping(value = "/app", method = RequestMethod.POST)
    public String app(@RequestParam(name = "prompt", required = false, defaultValue = "帮我制定2026年元旦,深圳到惠州3日游自驾游计划，请包含吃住行，天气，酒店，餐饮美食。") String prompt,
                      @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
                      @RequestParam(name = "multiScheme", required = false, defaultValue = "false") String multiScheme,
                      @RequestParam(name = "skipPrefs", required = false, defaultValue = "false") boolean skipPrefs,
                      HttpSession session) {
        return tripTaskService.submit(sessionId, prompt, multiScheme, skipPrefs, (Long) session.getAttribute("userId"));
    }

    /** 获取任务最终结果（未完成时返回提示）（按会话隔离） */
    @RequestMapping(value = "/result", method = RequestMethod.GET)
    public String result(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
                         HttpSession session) {
        return tripTaskService.result(sessionId, (Long) session.getAttribute("userId"));
    }

    /** 中断当前任务 */
    @RequestMapping(value = "/cancel", method = RequestMethod.POST)
    public String cancel(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,
                         HttpSession session) {
        return tripTaskService.cancel(sessionId, (Long) session.getAttribute("userId"));
    }

    /** 观测接口：返回本进程的模型调用次数（临时调试用） */
    @RequestMapping(value = "/stats", method = RequestMethod.GET)
    public Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        m.put("model_calls", SafeModel.MODEL_CALLS.get()); // 本进程模型调用次数（每次ReAct轮=1次）
        return m;
    }

    /** 高德地名->经纬度代理（前端点击景点直接定位用） */
    @RequestMapping(value = "/amap/geocode", method = RequestMethod.GET)
    public Map<String, Object> amapGeocode(
            @RequestParam(name = "name", required = false, defaultValue = "") String name,
            @RequestParam(name = "city", required = false, defaultValue = "") String city) {
        return AmapPlaceGeocoder.geocodeFirstPoi(name, city);
    }
}
