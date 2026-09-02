package tripPlannerAgent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import utils.mcp.DomainCandidateService;

import java.util.Map;

/**
 * 内部直连端点（仅供主管服务 8081 调用）：审查定向重跑/增量修正时绕过门面 ReAct Agent，
 * 直接调度三域候选服务。参数由主管从 TravelState 精确构造（无 LLM 参与传参），
 * 消除门面"决定调工具+复述JSON块"的两轮大 prompt LLM 时延。
 */
@Slf4j
@RestController
public class InternalCandidateController {

    @PostMapping(value = "/internal/candidates", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> candidates(@RequestBody Map<String, String> body) {
        long t0 = System.currentTimeMillis();
        String destination = body.getOrDefault("destination", "").trim();
        String refine = body.getOrDefault("refine", "");
        log.info("[internal/candidates] destination={} refine={}", destination, refine.isBlank() ? "-" : "yes");
        if (destination.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("⚠️[internal/candidates] destination 必填");
        }
        String out = DomainCandidateService.run(destination,
                body.getOrDefault("days", "").trim(),
                body.getOrDefault("preference", "").trim(),
                body.getOrDefault("waypoints", "").trim(),
                body.getOrDefault("brief", ""),
                refine);
        log.info("[internal/candidates] 完成 耗时{}ms 输出{}字符",
                System.currentTimeMillis() - t0, out.length());
        return ResponseEntity.ok(out);
    }
}
