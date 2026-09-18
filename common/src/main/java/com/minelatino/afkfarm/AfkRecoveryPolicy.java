package com.minelatino.afkfarm;

/** Pure recovery decisions shared by every Minecraft and loader variant. */
public final class AfkRecoveryPolicy {
    public static final int QUICK_RECONNECT_SECONDS = 5;

    private AfkRecoveryPolicy() {}

    public static int reconnectDelaySeconds(AfkFarmConfig.FarmMode mode, boolean enabled) {
        if (!enabled || mode == AfkFarmConfig.FarmMode.DIRECT) return -1;
        return mode == AfkFarmConfig.FarmMode.AUTONOMOUS
                ? AfkFarmConfig.AUTONOMOUS_RECOVERY_SECONDS : QUICK_RECONNECT_SECONDS;
    }

    public static boolean recoversUnexpectedTransfer(AfkFarmConfig.FarmMode mode) {
        return mode == AfkFarmConfig.FarmMode.AUTONOMOUS;
    }
}
