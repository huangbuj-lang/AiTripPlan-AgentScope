package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.LoginService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/** 用户登录注册接口 */
@RestController
@RequestMapping("/api")
public class LoginController {

    @Resource
    private LoginService loginService;

    /** 注册新用户 */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestParam String username, @RequestParam String password) {
        return loginService.register(username, password);
    }

    /** 登录（成功把用户信息存入Session） */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username, @RequestParam String password, HttpSession session) {
        LoginService.LoginResult r = loginService.login(username, password);
        Map<String, Object> result = new HashMap<>();
        result.put("success", r.success());
        result.put("message", r.message());
        if (r.success()) { // 登录成功：用户信息存入Session（供拦截器与各接口使用）
            session.setAttribute("userId", r.userId());
            session.setAttribute("username", username);
            session.setAttribute("role", r.role());
            session.setAttribute("pwdVersion", r.pwdVersion());
            session.setAttribute("status", r.status());
            result.put("username", username);
            result.put("role", r.role());
        }
        return result;
    }

    /** 退出登录（销毁Session） */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已退出登录");
        return result;
    }

    /** 检查当前是否已登录（前端页面加载时调用） */
    @GetMapping("/check-login")
    public Map<String, Object> checkLogin(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        Object username = session.getAttribute("username");
        if (username == null) {
            result.put("success", false);
            result.put("message", "未登录");
        } else {
            result.put("success", true);
            result.put("username", username);
            result.put("role", session.getAttribute("role"));
            result.put("userId", session.getAttribute("userId"));
        }
        return result;
    }
}
