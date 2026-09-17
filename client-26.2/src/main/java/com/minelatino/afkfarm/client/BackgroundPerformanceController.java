package com.minelatino.afkfarm.client;

import net.minecraft.client.Minecraft;

/** Temporarily prevents focus loss from pausing the client during an AFK lease. */
public final class BackgroundPerformanceController {
    private static boolean applied;
    private static boolean previousPauseOnLostFocus;

    private BackgroundPerformanceController() {}

    public static void activate() {
        if (applied) return;
        Minecraft minecraft = Minecraft.getInstance();
        previousPauseOnLostFocus = minecraft.options.pauseOnLostFocus;
        minecraft.options.pauseOnLostFocus = false;
        applied = true;
    }

    public static void deactivate() {
        if (!applied) return;
        Minecraft.getInstance().options.pauseOnLostFocus = previousPauseOnLostFocus;
        applied = false;
    }
}
