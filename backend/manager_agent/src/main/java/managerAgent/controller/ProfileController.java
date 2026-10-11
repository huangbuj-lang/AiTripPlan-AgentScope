package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.ProfileService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 个人中心接口（P0）：资料编辑 / 修改密码 / 旅行偏好 / 注销账号。 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    @Resource
    private ProfileService profileService;

    private long userId(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    /** 查询资料 */
    @GetMapping
    public Map<String, Object> getProfile(HttpSession session) {
        return profileService.getProfile(userId(session));
    }

    /** 更新资料（昵称/头像/邮箱/简介） */
    @PutMapping
    public Map<String, Object> updateProfile(@RequestParam(required = false) String nickname,
                                             @RequestParam(required = false) String avatarUrl,
                                             @RequestParam(required = false) String email,
                                             @RequestParam(required = false) String bio,
                                             HttpSession session) {
        return profileService.updateProfile(userId(session), nickname, avatarUrl, email, bio);
    }

    /** 修改密码：旧密码校验 + bump pwd_version（强制全端下线） */
    @PutMapping("/password")
    public Map<String, Object> changePassword(@RequestParam String oldPassword,
                                              @RequestParam String newPassword,
                                              HttpSession session) {
        ProfileService.PasswordChangeResult r = profileService.changePassword(userId(session), oldPassword, newPassword);
        if (r.success()) { // 当前会话同步新版本号，保持登录
            session.setAttribute("pwdVersion", r.pwdVersion());
        }
        return Map.of("success", r.success(), "message", r.message());
    }

    /** 查询旅行偏好 */
    @GetMapping("/preferences")
    public Map<String, Object> getPreferences(HttpSession session) {
        return profileService.getPreferences(userId(session));
    }

    /** 保存旅行偏好（travelStyle/avoid 传 JSON 数组字符串） */
    @PutMapping("/preferences")
    public Map<String, Object> updatePreferences(@RequestParam(required = false) String travelStyle,
                                                 @RequestParam(required = false) Integer budgetLevel,
                                                 @RequestParam(required = false) Integer pace,
                                                 @RequestParam(required = false) String avoidList,
                                                 @RequestParam(required = false, defaultValue = "1") Integer useByDefault,
                                                 HttpSession session) {
        return profileService.updatePreferences(userId(session), travelStyle, budgetLevel, pace, avoidList, useByDefault);
    }

    /** 注销账号：逻辑删除（status=2）+ 销毁会话 */
    @DeleteMapping
    public Map<String, Object> deleteAccount(HttpSession session) {
        Map<String, Object> result = profileService.deleteAccount(userId(session));
        session.invalidate();
        return result;
    }
}
