package managerAgent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/** 登录拦截器：未登录用户不能调用任务/保存接口 已登录 -> 放行； */
public class LoginInterceptor implements HandlerInterceptor {

    private final JdbcTemplate jdbcTemplate;
    /**
     * 作用：构造登录拦截器，注入数据库模板用于会话/用户校验
     * 参数：jdbcTemplate 数据库模板
     * 返回值：无
     * 异常：无
     */

    public LoginInterceptor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    /**
     * 作用：登录拦截器：校验登录态，未登录或无权访问时直接返回 JSON 错误
     * 参数：request HTTP 请求；response HTTP 响应；handler 目标处理器
     * 返回值：true=放行；false=已拦截
     * 异常：Exception 写响应失败时抛出
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        Object userIdObj = (session == null) ? null : session.getAttribute("userId");
        if (userIdObj == null) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
        }

        //P0：凭证版本/账号状态校验（改密/封禁后强制下线）
        long userId = ((Number) userIdObj).longValue();
        Object sessionVer = session.getAttribute("pwdVersion");
        if (sessionVer != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT status, pwd_version FROM users WHERE id = ?", userId);
            if (rows.isEmpty() || ((Number) rows.get(0).get("status")).intValue() != 1
                    || ((Number) rows.get(0).get("pwd_version")).intValue() != ((Number) sessionVer).intValue()) {
                session.invalidate();
                return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "账号状态已变更，请重新登录");
            }
        }
        return true; // 已登录，放行
    }
    /**
     * 作用：输出统一的 JSON 拒绝响应
     * 参数：response HTTP 响应；status 状态码；message 提示信息
     * 返回值：恒为 false（表示已拦截）
     * 异常：Exception 写响应失败时抛出
     */

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
        return false;
    }
}
