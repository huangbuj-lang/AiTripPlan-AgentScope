package managerAgent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import utils.NacosModelConfig;
import utils.AgentRuntimeConfig;

/** 启动类：启动 Spring Boot Web 应用（HTTP入口 POST /app） */
@SpringBootApplication  // @SpringBootApplication：开启自动配置与组件扫描
public class ManagerAgentApplication {  // 类声明：主管进程启动类
    /**
     * 作用：子 Agent 进程启动入口：Spring Boot 引导并注册到 Nacos，供主管通过 A2A 发现调用
     * 参数：args 命令行参数
     * 返回值：无
     * 异常：无（启动失败由 Spring 抛出，进程退出）
     */

    public static void main(String[] args) {  // main：进程入口
        NacosModelConfig.start();  // 先启动 Nacos 模型配置长轮询（拿模型 Key/超时等）
        AgentRuntimeConfig.start();  // 再启动运行时配置（并发、限流等开关）
        utils.AgentPromptStore.start();  // 再启动提示词库（从 Nacos 拉 sysPrompt）
        SpringApplication.run(ManagerAgentApplication.class, args); // 启动 Web 应用  // 最后启动 Spring Boot Web 应用（对外 8091）
    }  // main 结束
}  // 类结束

