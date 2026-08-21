package managerAgent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类：启动 Spring Boot Web 应用（HTTP入口 POST /app）
 */
@SpringBootApplication
public class ManagerAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManagerAgentApplication.class, args); // 启动 Web 应用
    }
}
