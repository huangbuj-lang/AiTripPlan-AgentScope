package managerAgent.service;

import jakarta.annotation.Resource;
import managerAgent.repository.PlanRepository;
import org.springframework.stereotype.Service;
import utils.JsonUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 行程业务：保存行程（含 JSON 校验、画像提取、常去城市重算）、查询我的行程、删除自己的行程。
 * 只做业务，SQL 在 PlanRepository。
 */
@Service
public class PlanService {

    private static final Map<String, Object> NOT_LOGIN = Map.of("success", false, "message", "请先登录");

    @Resource
    private PlanRepository planRepository;

    /** 保存行程：空串/非法 JSON 存 NULL（JSON 列不接受空串，非法 JSON 会触发 MySQL 500） */
    public Map<String, Object> savePlan(Long userId, String city, int days, String planContent, String planJson) {
        if (userId == null) {
            return NOT_LOGIN;
        }
        String json = (planJson == null || planJson.isBlank()) ? null : planJson;
        if (json != null && JsonUtils.parse(json, Map.class) == null) {
            json = null; // 非法 JSON：放弃结构化字段，仍保存 markdown
        }
        long newPlanId = planRepository.insert(userId, city, days, planContent, json);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "保存成功！可在「我的行程」中查看");
        result.put("planId", newPlanId);
        //异步用 LLM 从行程内容提取偏好标签，追加到该用户画像
        triggerPreferenceExtract(userId, newPlanId, planContent);
        //保存行程 -> 重算"常去-城市"标签
        managerAgent.tool.ProfileHook.trigger(userId);
        return result;
    }

    /** 查询当前用户保存的所有行程（按时间倒序） */
    public Map<String, Object> myPlans(Long userId) {
        if (userId == null) {
            return NOT_LOGIN;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("plans", planRepository.listByUser(userId));
        return result;
    }

    /** 删除自己的行程（id + user_id 双重条件） */
    public Map<String, Object> deletePlan(Long userId, Long id) {
        if (userId == null) {
            return NOT_LOGIN;
        }
        int rows = planRepository.deleteOwn(id, userId);
        if (rows > 0) {
            managerAgent.tool.ProfileHook.trigger(userId); // 删除后重算画像
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已删除" : "删除失败（行程不存在或无权删除）");
        return result;
    }

    /** 异步调用 data-pipeline/extract_pref.py， 用 LLM 从保存的行程内容提取偏好标签并写入该用户画像（用户无感，失败静默）。 */
    private void triggerPreferenceExtract(long userId, long planId, String planContent) {
        try {
            if (planContent == null || planContent.isBlank()) return;
            java.io.File script = new java.io.File(System.getProperty("user.dir"),
                    "../../data-pipeline/extract_pref.py");
            if (!script.exists()) return;
            String contentArg = planContent.length() > 3000 ? planContent.substring(0, 3000) : planContent;
            final ProcessBuilder pb = new ProcessBuilder(utils.Config.get("PYTHON_BIN", "python"), script.getCanonicalPath(),
                    "--user", String.valueOf(userId), "--content", contentArg);
            pb.redirectErrorStream(true);
            pb.redirectOutput(java.io.File.createTempFile("atp_extract_", ".log"));
            new Thread(() -> {
                try {
                    Process pr = pb.start();
                    pr.waitFor();
                } catch (Exception ignored) { }
            }, "pref-extract-" + planId).start();
        } catch (Exception ignored) { }
    }
}
