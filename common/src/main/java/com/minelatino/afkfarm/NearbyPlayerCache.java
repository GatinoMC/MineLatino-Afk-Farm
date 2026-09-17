package com.minelatino.afkfarm;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded, in-memory protection for nearby player profiles.
 *
 * <p>The cache deliberately stores only UUIDs and their last observed tick. A profile must keep
 * appearing in the network player list to remain protected; short-lived profiles used by
 * disguised mobs therefore become eligible again after the TTL.</p>
 */
public final class NearbyPlayerCache {
    public static final int DEFAULT_TTL_TICKS = 15 * 20;
    public static final int DEFAULT_MAX_ENTRIES = 128;

    private final long ttlTicks;
    private final int maxEntries;
    private final Map<UUID, Long> lastSeenTicks = new HashMap<>();

    public NearbyPlayerCache() {
        this(DEFAULT_TTL_TICKS, DEFAULT_MAX_ENTRIES);
    }

    public NearbyPlayerCache(long ttlTicks, int maxEntries) {
        if (ttlTicks < 1) throw new IllegalArgumentException("ttlTicks must be positive");
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.ttlTicks = ttlTicks;
        this.maxEntries = maxEntries;
    }

    public void remember(UUID uuid, long tick) {
        if (uuid == null) return;
        removeExpired(tick);
        if (!lastSeenTicks.containsKey(uuid) && lastSeenTicks.size() >= maxEntries) {
            UUID oldest = null;
            long oldestTick = Long.MAX_VALUE;
            for (Map.Entry<UUID, Long> entry : lastSeenTicks.entrySet()) {
                if (entry.getValue() < oldestTick) {
                    oldest = entry.getKey();
                    oldestTick = entry.getValue();
                }
            }
            if (oldest != null) lastSeenTicks.remove(oldest);
        }
        lastSeenTicks.put(uuid, tick);
    }

    public boolean contains(UUID uuid, long tick) {
        Long lastSeen = lastSeenTicks.get(uuid);
        if (lastSeen == null) return false;
        if (tick - lastSeen > ttlTicks) {
            lastSeenTicks.remove(uuid);
            return false;
        }
        return true;
    }

    public void removeExpired(long tick) {
        lastSeenTicks.entrySet().removeIf(entry -> tick - entry.getValue() > ttlTicks);
    }

    public int size(long tick) {
        removeExpired(tick);
        return lastSeenTicks.size();
    }

    public void clear() {
        lastSeenTicks.clear();
    }
}
