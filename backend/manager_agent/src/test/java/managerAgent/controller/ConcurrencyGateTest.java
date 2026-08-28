package managerAgent.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyGateTest {

    @Test
    void rejectsTasksBeyondConfiguredLimit() {
        ConcurrencyGate gate = new ConcurrencyGate(1);

        assertTrue(gate.tryAcquire());
        assertFalse(gate.tryAcquire());
        assertEquals(1, gate.active());

        gate.release();
        assertTrue(gate.tryAcquire());
    }

    @Test
    void appliesValidRuntimeLimitAndRejectsInvalidLimit() {
        ConcurrencyGate gate = new ConcurrencyGate(1);

        gate.setMax(3);
        assertEquals(3, gate.max());

        gate.setMax(0);
        assertEquals(1, gate.max());

        gate.setMax(99);
        assertEquals(ConcurrencyGate.HARD_LIMIT, gate.max());
    }
}
