package managerAgent.service;

import jakarta.annotation.Resource;
import managerAgent.controller.NotificationHub;
import managerAgent.repository.AdminDataRepository;
import managerAgent.repository.NotificationRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端「数据管理 / 内容审核 / 业务看板 / 数据中心」业务。
 * 只做业务，SQL 在 AdminDataRepository；全员通知写入走 NotificationRepository。
 */
@Service
public class AdminDataService {

    @Resource
    private AdminDataRepository adminDataRepository;

    @Resource
    private NotificationRepository notificationRepository;

    //==================== 用户与行程 ====================

    /** 用户列表（含角色） */
    public Map<String, Object> users() {
        List<Map<String, Object>> users = adminDataRepository.listUsers();
        for (Map<String, Object> u : users) {
            u.putIfAbsent("role", "user"); // 老库无 role 列时默认 user
        }
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("users", users);
        return r;
    }

    /** 删除用户（连同其名下行程、发帖、点赞、偏好、画像一起删） */
    public Map<String, Object> deleteUser(Long id) {
        int rows = adminDataRepository.deleteUserAndOwned(id);
        Map<String, Object> r = new HashMap<>();
        r.put("success", rows > 0);
        r.put("message", rows > 0 ? "已删除用户及其名下行程" : "用户不存在");
        return r;
    }

    /** 行程列表（关联用户名，预览内容；城市名归一化到标准城市库） */
    public Map<String, Object> plans() {
        List<Map<String, Object>> plans = adminDataRepository.listPlans();
        List<String> stdCities = adminDataRepository.listStdCities();
        for (Map<String, Object> pl : plans) {
            Object c = pl.get("city");
            if (c == null) continue;
            String raw = String.valueOf(c).trim();
            if (stdCities.contains(raw)) continue;
            String best = null;
            for (String s : stdCities) {
                if (raw.startsWith(s) && (best == null || s.length() > best.length())) best = s;
            }
            if (best != null) pl.put("city", best);
        }
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("plans", plans);
        return r;
    }

    /** 删除指定行程 */
    public Map<String, Object> deletePlan(Long id) {
        int rows = adminDataRepository.deletePlan(id);
        Map<String, Object> r = new HashMap<>();
        r.put("success", rows > 0);
        r.put("message", rows > 0 ? "已删除行程" : "行程不存在");
        return r;
    }

    //==================== 内容审核 ====================

    /** 审核队列：待处理举报 + AI 审核 review/block 记录 + 被隐藏内容 */
    public Map<String, Object> moderationQueue() {
        Map<String, Object> r = new HashMap<>();
        r.put("reports", adminDataRepository.listPendingReports());
        r.put("records", adminDataRepository.listModerationRecords());
        r.put("hiddenPosts", adminDataRepository.listHiddenPosts());
        r.put("hiddenComments", adminDataRepository.listHiddenComments());
        r.put("success", true);
        return r;
    }

    /** 处理举报（标记已处理） */
    public Map<String, Object> resolveReport(Long id) {
        int rows = adminDataRepository.resolveReport(id);
        Map<String, Object> r = new HashMap<>();
        r.put("success", rows > 0);
        r.put("message", rows > 0 ? "举报已标记处理" : "举报不存在");
        return r;
    }

    /** 隐藏/恢复内容（targetType: 1帖子 2评论） */
    public Map<String, Object> moderationAction(int targetType, Long targetId, String action) {
        String table = targetType == 1 ? "posts" : "comments";
        int status = "hide".equals(action) ? 0 : 1;
        int rows = adminDataRepository.updateContentStatus(table, targetId, status);
        Map<String, Object> r = new HashMap<>();
        r.put("success", rows > 0);
        r.put("message", rows > 0 ? ("hide".equals(action) ? "已隐藏" : "已恢复") : "内容不存在");
        return r;
    }

    //==================== 业务看板 ====================

    /** 业务统计：用户/行程/DAU/热门目的地/论坛活跃 */
    public Map<String, Object> businessStats() {
        Map<String, Object> r = new HashMap<>();
        r.put("totalUsers", adminDataRepository.countUsers());
        r.put("totalPlans", adminDataRepository.countPlans());
        r.put("todayPlans", adminDataRepository.countTodayPlans());
        r.put("totalPosts", adminDataRepository.countPosts());
        r.put("todayPosts", adminDataRepository.countTodayPosts());
        r.put("dau", adminDataRepository.countDau());
        r.put("hotDestinations", adminDataRepository.listHotDestinations());
        r.put("success", true);
        return r;
    }

    //==================== 公告发布 ====================

    /** 公告列表 */
    public Map<String, Object> announcements() {
        Map<String, Object> r = new HashMap<>();
        r.put("announcements", adminDataRepository.listAnnouncements());
        r.put("success", true);
        return r;
    }

    /** 发布公告 -> 全员通知（type=5） + SSE 实时推送 */
    public Map<String, Object> createAnnouncement(long adminId, String title, String content) {
        Map<String, Object> r = new HashMap<>();
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            r.put("success", false);
            r.put("message", "标题和内容不能为空");
            return r;
        }
        String cleanTitle = title.trim();
        adminDataRepository.insertAnnouncement(cleanTitle, content, adminId);
        //全员推送：所有正常用户各发一条通知 + SSE 实时推
        List<Long> userIds = adminDataRepository.listActiveUserIds();
        for (Long uid : userIds) {
            try {
                notificationRepository.insert(uid, 5, cleanTitle, null);
                java.util.Map<String, Object> msg = new java.util.LinkedHashMap<>();
                msg.put("type", 5);
                msg.put("content", cleanTitle);
                msg.put("link", "");
                NotificationHub.push(uid, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(msg));
            } catch (Exception ignored) { /* 单条失败不影响整体 */ }
        }
        r.put("success", true);
        r.put("message", "公告已发布并推送给 " + userIds.size() + " 位用户");
        return r;
    }

    //==================== 数据中心 ====================

    /** 数据统计明细（type=attractions/foods/hotels/cities/profiles） */
    public Map<String, Object> dataDetail(String type) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        try {
            List<Map<String, Object>> rows;
            List<String> columns;
            String title;
            switch (type) {
                case "attractions":
                    title = "景点数据明细";
                    columns = List.of("名称", "城市", "评分", "参考费用", "地址");
                    //参考费用优先取特征表真实票价（高德enrich），无则退主表cost
                    rows = adminDataRepository.listAttractionsDetail();
                    break;
                case "foods":
                    title = "美食数据明细";
                    columns = List.of("名称", "城市", "评分", "人均", "地址");
                    rows = adminDataRepository.listFoodsDetail();
                    break;
                case "hotels":
                    title = "酒店数据明细";
                    columns = List.of("名称", "城市", "评分", "参考价", "地址");
                    //参考价取特征表cost（高德biz_ext，仅少量有值），其余显示"-"（OTA无公开房价源）
                    rows = adminDataRepository.listHotelsDetail();
                    break;
                case "cities":
                    title = "覆盖城市明细";
                    columns = List.of("城市", "景点数", "美食数", "酒店数");
                    rows = adminDataRepository.listCitiesDetail();
                    break;
                case "profiles":
                    title = "用户画像明细";
                    columns = List.of("用户名", "用户ID", "画像标签(JSON)", "更新时间");
                    rows = adminDataRepository.listProfilesDetail();
                    break;
                default:
                    r.put("error", "未知类型: " + type);
                    return r;
            }
            r.put("success", true);
            r.put("title", title);
            //行转对象数组，前端按 key 显示（keySet 无序不可靠）
            List<List<Object>> aligned = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                List<Object> line = new ArrayList<>();
                //首格=主键id（前端编辑用），columns 展示时跳过
                line.add(row.get("id") == null ? "-" : row.get("id").toString());
                for (String col : columns) {
                    Object v = row.get(colKey(type, col));
                    line.add(v == null ? "-" : v.toString());
                }
                aligned.add(line);
            }
            r.put("columns", columns);
            //画像明细：标签JSON保留原文（前端标签云渲染），时间只去掉毫秒
            if ("profiles".equals(type)) {
                List<List<Object>> pretty = new ArrayList<>();
                for (List<Object> line : aligned) {
                    List<Object> out = new ArrayList<>(line); // [用户名, 用户ID, tagsJSON, updatedAt]
                    if (out.size() >= 4) {
                        out.set(3, String.valueOf(out.get(3)).replaceAll("\\.\\d+$", ""));
                    }
                    pretty.add(out);
                }
                r.put("rows", pretty);
            } else {
                r.put("rows", aligned);
            }
        } catch (Exception e) {
            r.put("error", e.getMessage());
        }
        return r;
    }

    /** 明细列名 -> SQL 返回 key 映射（中文列头对应数据库字段） */
    private String colKey(String type, String col) {
        switch (col) {
            case "名称": return "name";
            case "城市": return "city";
            case "评分": return "rating";
            case "参考费用": return "cost";
            case "人均": return "cost";
            case "参考价": return "cost";
            case "地址": return "address";
            case "景点数": return "attractions";
            case "美食数": return "foods";
            case "酒店数": return "hotels";
            case "类型": return "action";
            case "用户名": return "username";
            case "用户ID": return "user_id";
            case "行程ID": return "plan_id";
            case "时间": return "created_at";
            case "画像标签(JSON)": return "tags";
            case "更新时间": return "updated_at";
            default: return col;
        }
    }

    /** 手动编辑数据明细（景点/美食/酒店） */
    public Map<String, Object> editDataDetail(String type, Long id, String field, String value) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        if (!java.util.Set.of("attractions", "foods", "hotels").contains(type)) {
            r.put("message", "类型不支持"); return r;
        }
        if (!java.util.Set.of("name", "city", "rating", "cost", "address").contains(field)) {
            r.put("message", "字段不支持: " + field); return r;
        }
        try {
            String val = (value == null ? "" : value.trim());
            if (field.equals("cost")) {
                int mainRows = adminDataRepository.updateMainField(tableName(type), "cost", val, id);
                if (mainRows == 0) {
                    r.put("message", "主记录不存在，未保存");
                    return r;
                }
                int featureRows = adminDataRepository.updateFeatureCost(type, val, id);
                if (featureRows == 0) {
                    r.put("message", "主记录已保存，但关联特征记录不存在；动态定价可能不会生效");
                    return r;
                }
            } else {
                int rows = adminDataRepository.updateMainField(tableName(type), field, val, id);
                if (rows == 0) {
                    r.put("message", "记录不存在，未保存");
                    return r;
                }
            }
            r.put("success", true);
            r.put("message", "已保存");
        } catch (Exception e) {
            r.put("message", "保存失败: " + e.getMessage());
        }
        return r;
    }

    /** 数据统计手动编辑辅助：表名白名单 */
    private String tableName(String type) {
        return type.equals("attractions") ? "attractions" : type.equals("foods") ? "foods" : "hotels";
    }

    /** 数据管线统计：各表行数（表不存在返回0） */
    public Map<String, Object> dataStats() {
        Map<String, Object> r = new HashMap<>();
        r.put("attractions", adminDataRepository.countSafe("SELECT COUNT(*) FROM attractions"));
        r.put("foods", adminDataRepository.countSafe("SELECT COUNT(*) FROM foods"));
        r.put("hotels", adminDataRepository.countSafe("SELECT COUNT(*) FROM hotels"));
        r.put("cities", adminDataRepository.countSafe("SELECT COUNT(DISTINCT city) FROM attractions"));
        r.put("profiles", adminDataRepository.countSafe("SELECT COUNT(*) FROM user_profiles_tags"));
        r.put("success", true);
        return r;
    }

    /** 动态定价结果 */
    public Map<String, Object> pricing() {
        Map<String, Object> r = new HashMap<>();
        r.put("rows", adminDataRepository.listPricing());
        r.put("success", true);
        return r;
    }

    /** 客流预测（未来7天） */
    public Map<String, Object> forecast() {
        Map<String, Object> r = new HashMap<>();
        r.put("rows", adminDataRepository.listForecast());
        r.put("success", true);
        return r;
    }

    /** 用户画像 */
    public Map<String, Object> profiles() {
        Map<String, Object> r = new HashMap<>();
        r.put("profiles", adminDataRepository.listProfileTags());
        r.put("success", true);
        return r;
    }
}
