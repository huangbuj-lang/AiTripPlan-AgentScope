package managerAgent.controller;

import jakarta.annotation.Resource;
import managerAgent.service.InsightsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** 用户侧行程参考信息：只读展示数据库已生成的城市级定价与客流结果。 */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    @Resource
    private InsightsService insightsService;

    @GetMapping
    public Map<String, Object> insights(
            @RequestParam String city,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false, defaultValue = "1") Integer days) {
        return insightsService.insights(city, startDate, days);
    }
}
