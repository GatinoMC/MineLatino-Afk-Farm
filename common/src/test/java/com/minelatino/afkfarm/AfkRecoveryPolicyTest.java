package com.minelatino.afkfarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AfkRecoveryPolicyTest {
    @Test void directModeNeverReconnectsOrRecoversTransfers() {
        assertEquals(-1, AfkRecoveryPolicy.reconnectDelaySeconds(AfkFarmConfig.FarmMode.DIRECT, true));
        assertFalse(AfkRecoveryPolicy.recoversUnexpectedTransfer(AfkFarmConfig.FarmMode.DIRECT));
    }

    @Test void reconnectModeOnlyHandlesDisconnections() {
        assertEquals(5, AfkRecoveryPolicy.reconnectDelaySeconds(AfkFarmConfig.FarmMode.RECONNECT, true));
        assertFalse(AfkRecoveryPolicy.recoversUnexpectedTransfer(AfkFarmConfig.FarmMode.RECONNECT));
    }

    @Test void autonomousModeWaitsFiveMinutesForEitherInterruption() {
        assertEquals(300, AfkRecoveryPolicy.reconnectDelaySeconds(AfkFarmConfig.FarmMode.AUTONOMOUS, true));
        assertTrue(AfkRecoveryPolicy.recoversUnexpectedTransfer(AfkFarmConfig.FarmMode.AUTONOMOUS));
        assertEquals(-1, AfkRecoveryPolicy.reconnectDelaySeconds(AfkFarmConfig.FarmMode.AUTONOMOUS, false));
    }
}
