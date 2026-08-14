package managerAgent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员拦截器：只有 role=admin 的用户才能访问 /api/admin/** 管理接口
 * 已登录但非管理员 → 返回 403 + JSON 提示
 */
public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        if (session != null && "admin".equals(session.getAttribute("role"))) {
            return true; // 管理员：放行
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 非管理员：返回403
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"无管理员权限\"}");
        return false;
    }
}
