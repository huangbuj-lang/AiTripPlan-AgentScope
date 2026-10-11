package managerAgent.service;

import dto.TripRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import managerAgent.repository.ProfileRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import utils.JsonUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人中心业务（P0）：资料编辑 / 修改密码 / 旅行偏好 / 注销账号；
 * 另含“给行程规划注入用户偏好与画像”的两个读取方法。
 * 只做业务，SQL 在 ProfileRepository；Session 读写留在控制器（HTTP 层）。
 */
@Slf4j
@Service
public class ProfileService {

    @Resource
    private ProfileRepository profileRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 修改密码结果：控制器据此同步当前会话的凭证版本号 */
    public record PasswordChangeResult(boolean success, String message, Integer pwdVersion) {
    }

    /** 查询资料（username/角色/create_time 来自 users，其余来自 user_profiles） */
    public Map<String, Object> getProfile(long uid) {
        profileRepository.ensureRow(uid);
        Map<String, Object> user = profileRepository.findUserBrief(uid);
        Map<String, Object> profile = profileRepository.findProfile(uid);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("username", user.get("username"));
        result.put("role", user.get("role"));
        result.put("create_time", user.get("create_time"));
        result.put("nickname", profile.get("nickname"));
        result.put("avatar_url", profile.get("avatar_url"));
        result.put("email", profile.get("email"));
        result.put("bio", profile.get("bio"));
        return result;
    }

    /** 更新资料（昵称/头像/邮箱/简介），邮箱非空时需唯一 */
    public Map<String, Object> updateProfile(long uid, String nickname, String avatarUrl, String email, String bio) {
        profileRepository.ensureRow(uid);
        Map<String, Object> result = new HashMap<>();
        try {
            String em = (email == null || email.isBlank()) ? null : email.trim();
            if (em != null && profileRepository.countEmailUsedByOthers(em, uid) > 0) {
                result.put("success", false);
                result.put("message", "该邮箱已被其他账号使用");
                return result;
            }
            profileRepository.updateProfile(uid,
                    nickname == null || nickname.isBlank() ? null : nickname.trim(),
                    avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl.trim(),
                    em,
                    bio == null || bio.isBlank() ? null : bio.trim());
            result.put("success", true);
            result.put("message", "资料已更新");
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "更新失败：" + e.getMessage());
            return result;
        }
    }

    /** 修改密码：旧密码校验 + bump pwd_version（强制全端下线） */
    public PasswordChangeResult changePassword(long uid, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            return new PasswordChangeResult(false, "新密码至少6位", null);
        }
        String hashed = profileRepository.findPasswordHash(uid);
        if (hashed == null || !passwordEncoder.matches(oldPassword, hashed)) {
            return new PasswordChangeResult(false, "旧密码错误", null);
        }
        profileRepository.updatePasswordAndBumpVersion(uid, passwordEncoder.encode(newPassword));
        //bump pwd_version -> 其它会话在 LoginInterceptor 校验失败被强制下线；当前会话同步新版本号以保持登录
        return new PasswordChangeResult(true, "密码已修改，其它设备已下线", profileRepository.findPwdVersion(uid));
    }

    /** 查询旅行偏好（无记录时返回默认值） */
    public Map<String, Object> getPreferences(long uid) {
        List<Map<String, Object>> rows = profileRepository.findPreferences(uid);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        if (rows.isEmpty()) {
            result.put("travel_style", null);
            result.put("budget_level", null);
            result.put("pace", null);
            result.put("avoid_list", null);
            result.put("use_by_default", 1);
        } else {
            Map<String, Object> row = rows.get(0);
            result.put("travel_style", row.get("travel_style"));
            result.put("budget_level", row.get("budget_level"));
            result.put("pace", row.get("pace"));
            result.put("avoid_list", row.get("avoid_list"));
            result.put("use_by_default", row.get("use_by_default"));
        }
        return result;
    }

    /** 保存旅行偏好（travelStyle/avoid 传 JSON 数组字符串） */
    public Map<String, Object> updatePreferences(long uid, String travelStyle, Integer budgetLevel, Integer pace,
                                                 String avoidList, Integer useByDefault) {
        Map<String, Object> result = new HashMap<>();
        //校验 JSON 数组合法性
        if (travelStyle != null && !travelStyle.isBlank() && JsonUtils.parseStringList(travelStyle) == null) {
            result.put("success", false);
            result.put("message", "风格标签格式非法");
            return result;
        }
        if (avoidList != null && !avoidList.isBlank() && JsonUtils.parseStringList(avoidList) == null) {
            result.put("success", false);
            result.put("message", "回避项格式非法");
            return result;
        }
        String style = (travelStyle == null || travelStyle.isBlank()) ? null : travelStyle;
        String avoid = (avoidList == null || avoidList.isBlank()) ? null : avoidList;
        profileRepository.upsertPreferences(uid, style, budgetLevel, pace, avoid, useByDefault == null ? 1 : useByDefault);
        managerAgent.tool.ProfileHook.trigger(uid); // 主动偏好变化->画像即时更新
        result.put("success", true);
        result.put("message", "偏好已保存");
        return result;
    }

    /** 注销账号：逻辑删除（status=2），会话销毁由控制器处理 */
    public Map<String, Object> deleteAccount(long uid) {
        profileRepository.deactivate(uid);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "账号已注销");
        return result;
    }

    /** 读用户沉淀偏好（user_preferences 表；用户关闭默认注入时返回 null） */
    public TripRequest.Preferences preferencesForPlanning(long userId) {
        try {
            List<Map<String, Object>> rows = profileRepository.findPreferences(userId);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            if (row.get("use_by_default") != null && ((Number) row.get("use_by_default")).intValue() != 1) {
                return null; // 用户关闭了默认注入
            }
            TripRequest.Preferences p = new TripRequest.Preferences();
            p.setTravelStyle(JsonUtils.parseStringList((String) row.get("travel_style")));
            p.setBudgetLevel(row.get("budget_level") == null ? null : ((Number) row.get("budget_level")).intValue());
            p.setPace(row.get("pace") == null ? null : ((Number) row.get("pace")).intValue());
            p.setAvoid(JsonUtils.parseStringList((String) row.get("avoid_list")));
            return p.isEmpty() ? null : p;
        } catch (Exception e) {
            log.warn("加载用户偏好失败: {}", e.getMessage());
            return null;
        }
    }

    /** 读用户画像标签，拼成"【用户画像】..."行注入主管。 */
    public String profileLineForPlanning(Long userId) {
        if (userId == null) return "";
        try {
            var rows = profileRepository.findProfileTags(userId);
            if (rows.isEmpty() || rows.get(0).get("tags") == null) return "";
            Object raw = rows.get(0).get("tags");
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> tags = om.readValue(raw.toString(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            if (tags.isEmpty()) return "";
            List<String> parts = tags.entrySet().stream()
                    .sorted((a, b) -> Long.compare(
                            ((Number) b.getValue()).longValue(), ((Number) a.getValue()).longValue()))
                    .limit(8)
                    .map(e -> e.getKey() + (e.getValue() instanceof Number n && n.longValue() > 1
                            ? "×" + n.longValue() : ""))
                    .collect(java.util.stream.Collectors.toList());
            return "【用户画像】" + String.join("、", parts) + "\\n";
        } catch (Exception e) {
            return "";
        }
    }
}
