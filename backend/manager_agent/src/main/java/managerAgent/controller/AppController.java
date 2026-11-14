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
@RestController  // @RestController：把所有方法返回的字符串/对象直接序列化成 HTTP 响应
public class AppController {  // 类声明：AppController

    @Resource  // @Resource：让 Spring 注入下面这个字段
    private TripTaskService tripTaskService;  // 声明 TripTaskService 字段 tripTaskService——本类唯一的依赖，所有方法都转给它

    /**
     * 作用：规划任务入口：并发闸限流 → 清旧事件 → 后台线程创建编排器执行 → 结果写入会话缓存（实际执行下沉到 TripTaskService）
     * 参数：prompt 用户需求文本；sessionId 会话 ID；multiScheme 为 true 时生成多套风格差异化方案；skipPrefs 为 true 时跳过个人画像与偏好注入；session 当前 HTTP 会话（取登录用户 userId）
     * 返回值：提示文本（已接收/并发已满）
     * 异常：无（执行异常在后台线程内捕获并写入失败结果）
     */
    @RequestMapping(value = "/app", method = RequestMethod.POST)  // 映射为 POST /app——前端点“开始规划”打的就是这个地址
    public String app(@RequestParam(name = "prompt", required = false, defaultValue = "帮我制定2026年元旦,深圳到惠州3日游自驾游计划，请包含吃住行，天气，酒店，餐饮美食。") String prompt,  // 方法签名第 1 行：prompt 参数，不传时用示例需求（深圳→惠州 3 日自驾）兜底，保证空参也能跑
                      @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,  // sessionId 参数，默认 default：前端每次会话一个 ID，事件、结果、取消都按它归档
                      @RequestParam(name = "multiScheme", required = false, defaultValue = "false") String multiScheme,  // multiScheme 参数（字符串），默认 false：传 "true" 走多套风格方案；用字符串是为了兼容前端传的各种写法
                      @RequestParam(name = "skipPrefs", required = false, defaultValue = "false") boolean skipPrefs,  // skipPrefs 参数（布尔），默认 false：true 表示不注入个人画像与偏好（游客/想重置口味时用）
                      HttpSession session) {  // HttpSession 是容器注入的，不是请求参数：用它取登录用户 userId
        return tripTaskService.submit(sessionId, prompt, multiScheme, skipPrefs, (Long) session.getAttribute("userId"));  // 唯一一行业务：把四个参数 + userId（可能为 null）交给 tripTaskService.submit，返回值原样透传
    }  // 方法结束

    /**
     * 作用：轮询任务结果：校验会话归属后返回最终结果
     * 参数：sessionId 会话 ID；session 当前 HTTP 会话
     * 返回值：结果文本；未就绪时返回进行中提示
     * 异常：无
     */
    @RequestMapping(value = "/result", method = RequestMethod.GET)  // 映射为 GET /result——前端定时轮询拿最终结果
    public String result(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,  // 签名：只有 sessionId 一个业务参数（默认 default）
                         HttpSession session) {  // HttpSession 参数，用于取 userId
        return tripTaskService.result(sessionId, (Long) session.getAttribute("userId"));  // 转交 tripTaskService.result：归属校验与“执行中”提示都在 service 里
    }  // 方法结束

    /**
     * 作用：请求中断任务：校验归属后置取消标志并打断正在执行的远程调用
     * 参数：sessionId 会话 ID；session 当前 HTTP 会话
     * 返回值：操作提示文本
     * 异常：无
     */
    @RequestMapping(value = "/cancel", method = RequestMethod.POST)  // 映射为 POST /cancel——用户点“停止”就走这里
    public String cancel(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,  // 签名：sessionId（默认 default）
                         HttpSession session) {  // HttpSession 参数
        return tripTaskService.cancel(sessionId, (Long) session.getAttribute("userId"));  // 转交 tripTaskService.cancel：越权判断与置标志都在 service 里
    }  // 方法结束

    /**
     * 作用：读取当前并发与任务统计（管理端展示）
     * 参数：无
     * 返回值：统计 Map
     * 异常：无
     */
    @RequestMapping(value = "/stats", method = RequestMethod.GET)  // 映射为 GET /stats
    public Map<String, Object> stats() {  // 签名：无参数，返回 Map<String,Object>
        Map<String, Object> m = new HashMap<>();  // 新建 HashMap 装统计项
        m.put("model_calls", SafeModel.MODEL_CALLS.get()); // 本进程模型调用次数（每次ReAct轮=1次）  // 放入 model_calls＝本进程累计模型调用次数（每个 ReAct 轮算 1 次）；读的是 SafeModel 的静态计数器
        return m;  // 返回这个 Map，Spring 自动转 JSON
    }  // 方法结束

    /**
     * 作用：地名转经纬度接口：调高德 place/text 查询首个 POI 的坐标
     * 参数：name 待转坐标的地名；city 限定城市，可为空
     * 返回值：含 ok/坐标/错误信息的 Map
     * 异常：无（外部调用异常转成 ok=false 的响应）
     */
    @RequestMapping(value = "/amap/geocode", method = RequestMethod.GET)  // 映射为 GET /amap/geocode
    public Map<String, Object> amapGeocode(  // 签名开始，返回 Map
            @RequestParam(name = "name", required = false, defaultValue = "") String name,  // name 参数，默认空串
            @RequestParam(name = "city", required = false, defaultValue = "") String city) {  // city 参数，默认空串（不传就在全国范围查）
        return AmapPlaceGeocoder.geocodeFirstPoi(name, city);  // 调 AmapPlaceGeocoder.geocodeFirstPoi 做真正的查询，结果直接返回
    }  // 方法结束
}  // 类结束

