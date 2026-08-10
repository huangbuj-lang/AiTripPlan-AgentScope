package managerAgent;

import com.alibaba.nacos.api.exception.NacosException;
import managerAgent.agents.ManagerAgent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类（两种模式）
 * 默认：启动Spring Boot Web应用（HTTP入口 POST /app）
 * --run-now：直跑一次默认任务（命令行测试用）
 */
@SpringBootApplication
public class ManagerAgentApplication {

    public static void main(String[] args) throws NacosException {

        if (args.length > 0 && "--run-now".equals(args[0])) { // --run-now：直跑默认任务后退出（不启动Web）
            ManagerAgent managerAgent = new ManagerAgent();
            managerAgent.run();
            return;
        }

        SpringApplication.  run(ManagerAgentApplication.class, args); // 默认：启动 Web 应用
    }
}
