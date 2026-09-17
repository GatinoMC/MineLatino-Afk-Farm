package com.minelatino.afkfarm;

/** Runtime-only limits applied while an authorized AFK lease is active. */
public final class AfkRuntimePolicy {
    public static final int BACKGROUND_FPS = 35;

    private AfkRuntimePolicy() {}

    public static int framerateLimit(boolean active, int original) {
        return active ? BACKGROUND_FPS : original;
    }
}
