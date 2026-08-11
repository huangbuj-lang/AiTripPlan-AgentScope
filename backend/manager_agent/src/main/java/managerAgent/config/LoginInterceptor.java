package managerAgent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器：未登录用户不能调用任务/保存接口
 * 已登录 → 放行；未登录 → 返回401 + JSON提示
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            return true; // 已登录，放行
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 未登录：返回401，前端识别后跳登录页
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"请先登录\"}");
        return false;
    }
}
