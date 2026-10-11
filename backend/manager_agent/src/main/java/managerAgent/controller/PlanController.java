package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.PlanService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 行程保存接口（规划结果存入MySQL） */
@RestController
@RequestMapping("/api")
public class PlanController {

    @Resource
    private PlanService planService;

    /** 保存行程到数据库 */
    @PostMapping("/save-plan")
    public Map<String, Object> savePlan(@RequestParam String city,
                                        @RequestParam int days,
                                        @RequestParam String planContent,
                                        @RequestParam(required = false) String planJson,
                                        HttpSession session) {
        return planService.savePlan((Long) session.getAttribute("userId"), city, days, planContent, planJson);
    }

    /** 查询当前用户保存的所有行程（按时间倒序） */
    @GetMapping("/my-plans")
    public Map<String, Object> myPlans(HttpSession session) {
        return planService.myPlans((Long) session.getAttribute("userId"));
    }

    /** 删除自己的行程（id + user_id 双重条件） */
    @PostMapping("/delete-plan")
    public Map<String, Object> deletePlan(@RequestParam Long id, HttpSession session) {
        return planService.deletePlan((Long) session.getAttribute("userId"), id);
    }
}
