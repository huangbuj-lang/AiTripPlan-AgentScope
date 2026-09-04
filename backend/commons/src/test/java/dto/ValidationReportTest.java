package dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ValidationReport.checksBrief()：校验事件流"已执行/跳过检查计数"简注。
 */
class ValidationReportTest {

    @Test
    void checksBriefCountsExecutedAndSkipped() {
        ValidationReport vr = new ValidationReport();
        vr.setExecutedChecks(List.of("checkDailyPace", "checkHotelLocation", "checkOpenTime"));
        vr.setSkippedChecks(List.of("checkBudget"));
        assertEquals("（已执行 3 项确定性检查，跳过 1 项）", vr.checksBrief());
    }

    @Test
    void checksBriefOmitsSkippedWhenEmpty() {
        ValidationReport vr = new ValidationReport();
        vr.setExecutedChecks(List.of("checkDailyPace"));
        assertEquals("（已执行 1 项确定性检查）", vr.checksBrief());
    }

    @Test
    void checksBriefHandlesEmptyLists() {
        assertEquals("（已执行 0 项确定性检查）", new ValidationReport().checksBrief());
    }
}
