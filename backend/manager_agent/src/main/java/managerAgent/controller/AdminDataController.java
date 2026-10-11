package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import java.util.Map;




/** 管理端-数据管理接口：用户与行程管理、内容审核、业务看板、公告发布、数据中心。 */
@RestController
@RequestMapping("/api/admin")
public class AdminDataController {

    @Resource
    private managerAgent.service.AdminDataService adminDataService;



    /** 用户列表（含角色） */
    @GetMapping("/users")
    public Map<String, Object> users() {
        return adminDataService.users();
    }

    /** 删除用户（连同其名下行程、发帖、点赞、偏好、画像一起删） */
    @DeleteMapping("/users")
    public Map<String, Object> deleteUser(@RequestParam Long id) {
        return adminDataService.deleteUser(id);
    }

    /** 行程列表（关联用户名，预览内容） */
    @GetMapping("/plans")
    public Map<String, Object> plans() {
        return adminDataService.plans();
    }

    /** 删除指定行程 */
    @DeleteMapping("/plans")
    public Map<String, Object> deletePlan(@RequestParam Long id) {
        return adminDataService.deletePlan(id);
    }
    //==================== 内容审核（阶段三） ====================

    /** 审核队列：待处理举报 + AI 审核 review/block 记录 + 被隐藏内容 */
    @GetMapping("/moderation/queue")
    public Map<String, Object> moderationQueue() {
        return adminDataService.moderationQueue();
    }

    /** 处理举报（标记已处理） */
    @PutMapping("/moderation/resolve")
    public Map<String, Object> resolveReport(@RequestParam Long id) {
        return adminDataService.resolveReport(id);
    }

    /** 隐藏/恢复内容（targetType: 1帖子 2评论； */
    @PostMapping("/moderation/action")
    public Map<String, Object> moderationAction(@RequestParam int targetType,
                                                @RequestParam Long targetId,
                                                @RequestParam String action) {
        return adminDataService.moderationAction(targetType, targetId, action);
    }

    //==================== 业务看板（阶段三） ====================

    /** 业务统计：用户/行程/DAU/热门目的地/论坛活跃 */
    @GetMapping("/stats/business")
    public Map<String, Object> businessStats() {
        return adminDataService.businessStats();
    }

    //==================== 公告发布（阶段三） ====================

    /** 公告列表 */
    @GetMapping("/announcements")
    public Map<String, Object> announcements() {
        return adminDataService.announcements();
    }

    /** 发布公告 -> 全员通知（type=5） + SSE 实时推送 */
    @PostMapping("/announcements")
    public Map<String, Object> createAnnouncement(@RequestParam String title,
                                                  @RequestParam String content,
                                                  HttpSession session) {
        long adminId = ((Number) session.getAttribute("userId")).longValue();
        return adminDataService.createAnnouncement(adminId, title, content);
    }
//==================== 数据中心 ====================

/** 数据统计明细（type=attractions/foods/hotels/cities/profiles）。 */
    @GetMapping("/data-detail")
    public Map<String, Object> dataDetail(@RequestParam String type) {
        return adminDataService.dataDetail(type);
    }

    /** 手动编辑数据明细（景点/美食/酒店）。 */
    @PostMapping("/data-detail/edit")
    public Map<String, Object> editDataDetail(@RequestParam String type, @RequestParam Long id,
                                              @RequestParam String field, @RequestParam String value) {
        return adminDataService.editDataDetail(type, id, field, value);
    }

    /** 数据管线统计：各表行数（表不存在返回0） */
    @GetMapping("/data-stats")
    public Map<String, Object> dataStats() {
        return adminDataService.dataStats();
    }

    /** 动态定价结果 */
    @GetMapping("/pricing")
    public Map<String, Object> pricing() {
        return adminDataService.pricing();
    }

    /** 客流预测（未来7天） */
    @GetMapping("/forecast")
    public Map<String, Object> forecast() {
        return adminDataService.forecast();
    }

    /** 用户画像 */
    @GetMapping("/profiles")
    public Map<String, Object> profiles() {
        return adminDataService.profiles();
    }
}
