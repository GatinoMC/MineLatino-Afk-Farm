package com.minelatino.afkfarm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class AfkRuntimePolicyTest {
    @Test void limitsOnlyAnActiveAfkLeaseAndRestoresThePreviousEffectiveLimit() {
        assertEquals(35, AfkRuntimePolicy.framerateLimit(true, 144));
        assertEquals(144, AfkRuntimePolicy.framerateLimit(false, 144));
        assertEquals(0, AfkRuntimePolicy.framerateLimit(false, 0));
    }
}
