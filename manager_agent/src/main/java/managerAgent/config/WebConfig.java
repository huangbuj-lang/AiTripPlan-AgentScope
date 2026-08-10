package managerAgent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * 拦截器注册 + 静态资源缓存配置
 * 拦截：/app /result /events /api/* 等业务接口（登录后才能调）
 * 放行：登录注册接口 + 静态页面
 * 静态资源：禁用缓存（部署新版后页面自动更新，无需手动?v=）
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/app", "/result", "/events", "/api/save-plan", "/api/my-plans", "/api/delete-plan", "/api/logout")
                .excludePathPatterns("/api/login", "/api/register", "/api/check-login");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**") // 静态资源禁用缓存（只影响页面文件，不影响Controller接口）
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
