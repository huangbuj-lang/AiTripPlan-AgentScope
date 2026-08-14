package managerAgent.config;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * 拦截器注册与静态资源缓存配置
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor(jdbcTemplate))
                .addPathPatterns("/app", "/result", "/events", "/cancel",
                        "/api/save-plan", "/api/my-plans", "/api/delete-plan", "/api/logout",
                        "/api/profile/**", "/api/trips/**", "/api/admin/**")
                .excludePathPatterns("/api/login", "/api/register", "/api/check-login", "/api/share/**");
        // 管理接口：登录拦截器先拦（未登录401），再校验管理员角色（非管理员403）
        registry.addInterceptor(new AdminInterceptor())
                .addPathPatterns("/api/admin/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**") // 静态资源禁用缓存
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
