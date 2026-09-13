package managerAgent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关（新增配置文件，不改动现有 WebConfig）。
 *
 * <p>Spring 的 {@code @Scheduled} 注解只在存在 {@code @EnableScheduling} 时才会被注册执行；
 * 项目原先没有任何定时任务，因此这里单独新增一个 @Configuration 打开开关，
 * 供「行中提醒」（{@code managerAgent.job.WeatherBriefJob}，每天早上 7:00 的当日天气早报）使用。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
