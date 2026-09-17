package com.minelatino.afkfarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NearbyPlayerCacheTest {
    @Test void protectsARecentlyObservedProfileUntilItsTtlExpires() {
        NearbyPlayerCache cache = new NearbyPlayerCache(10, 4);
        UUID player = UUID.randomUUID();

        cache.remember(player, 20);

        assertTrue(cache.contains(player, 30));
        assertFalse(cache.contains(player, 31));
    }

    @Test void refreshingAProfileExtendsItsProtection() {
        NearbyPlayerCache cache = new NearbyPlayerCache(10, 4);
        UUID player = UUID.randomUUID();

        cache.remember(player, 20);
        cache.remember(player, 28);

        assertTrue(cache.contains(player, 38));
        assertFalse(cache.contains(player, 39));
    }

    @Test void evictsTheOldestEntryWhenBoundIsReached() {
        NearbyPlayerCache cache = new NearbyPlayerCache(100, 2);
        UUID oldest = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        UUID newest = UUID.randomUUID();

        cache.remember(oldest, 1);
        cache.remember(retained, 2);
        cache.remember(newest, 3);

        assertFalse(cache.contains(oldest, 3));
        assertTrue(cache.contains(retained, 3));
        assertTrue(cache.contains(newest, 3));
        assertEquals(2, cache.size(3));
    }

    @Test void clearDropsAllSessionData() {
        NearbyPlayerCache cache = new NearbyPlayerCache();
        UUID player = UUID.randomUUID();
        cache.remember(player, 1);

        cache.clear();

        assertFalse(cache.contains(player, 1));
        assertEquals(0, cache.size(1));
    }
}
