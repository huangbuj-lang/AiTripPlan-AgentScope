package dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TravelState 统一状态中心单元测试。
 */
class TravelStateTest {

    @Test
    void defaultsAreSafe() {
        TravelState s = new TravelState();
        assertEquals(0, s.budgetTotal(), 0.001);
        assertTrue(s.hardConstraints().isEmpty());
        assertFalse(s.isFromCache());
        assertNotNull(s.getTrace());
        assertNotNull(s.getRequest());
    }

    @Test
    void budgetAndConstraintsDelegateToRequest() {
        TravelState s = new TravelState();
        TripRequest req = new TripRequest();
        req.setBudgetTotal(9000);
        req.getConstraints().add(new TripRequest.Constraint("预算不超过9000元", "P0"));
        req.getConstraints().add(new TripRequest.Constraint("想吃当地菜", "P2"));
        s.setRequest(req);
        assertEquals(9000, s.budgetTotal(), 0.001);
        assertEquals(List.of("预算不超过9000元"), s.hardConstraints());
    }

    @Test
    void addTraceAppendsStepAndDetail() {
        TravelState s = new TravelState();
        s.addTrace("解析", "深圳→惠州 3天 预算5000");
        assertEquals(1, s.getTrace().size());
        assertEquals("解析: 深圳→惠州 3天 预算5000", s.getTrace().get(0));
    }
}
