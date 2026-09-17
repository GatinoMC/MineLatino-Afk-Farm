package com.minelatino.afkfarm.client;

import com.minelatino.afkfarm.ArtificialPlayerPolicy;
import com.minelatino.afkfarm.NearbyPlayerCache;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** Observes PlayerInfo lifetime without relying on names, skins or server-side APIs. */
final class ArtificialPlayerTracker {
    private static final int STALE_ENTITY_TICKS = 20;
    private static final int NEARBY_PLAYER_SCAN_INTERVAL_TICKS = 20;
    private static final double NEARBY_PLAYER_RADIUS = AfkFarmClient.ATTACK_SEARCH_RADIUS + 2.0;

    private static final class Observation {
        int entityId;
        boolean observedProfile;
        boolean profilePresent;
        int currentProfileTicks;
        int longestProfileTicks;
        int removedTicks;
        long lastEntityTick;

        Observation(int entityId) { this.entityId = entityId; }

        void observe(boolean present, long tick) {
            lastEntityTick = tick;
            profilePresent = present;
            if (present) {
                observedProfile = true;
                currentProfileTicks++;
                longestProfileTicks = Math.max(longestProfileTicks, currentProfileTicks);
                removedTicks = 0;
            } else {
                currentProfileTicks = 0;
                if (observedProfile) removedTicks++;
            }
        }

        ArtificialPlayerPolicy.Verdict verdict() {
            return ArtificialPlayerPolicy.classify(observedProfile, longestProfileTicks,
                    profilePresent, removedTicks);
        }
    }

    private final Map<UUID, Observation> observations = new HashMap<>();
    private final Set<UUID> confirmedRealProfiles = new HashSet<>();
    private final NearbyPlayerCache nearbyNetworkPlayers = new NearbyPlayerCache();
    private Object observedLevel;
    private long ticks;
    private long lastNearbyPlayerScanTick = Long.MIN_VALUE;

    void tick(Minecraft minecraft, boolean afkActive) {
        ticks++;
        if (minecraft.level != observedLevel) {
            observations.clear();
            confirmedRealProfiles.clear();
            clearNearbyPlayers();
            observedLevel = minecraft.level;
        }
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            observations.clear();
            clearNearbyPlayers();
            return;
        }

        boolean scanNearbyPlayers = afkActive && (lastNearbyPlayerScanTick == Long.MIN_VALUE
                || ticks - lastNearbyPlayerScanTick >= NEARBY_PLAYER_SCAN_INTERVAL_TICKS);
        double nearbyRadiusSquared = NEARBY_PLAYER_RADIUS * NEARBY_PLAYER_RADIUS;

        Set<UUID> visible = new HashSet<>();
        for (Player player : minecraft.level.players()) {
            if (player == minecraft.player) continue;
            UUID uuid = player.getUUID();
            visible.add(uuid);
            Observation observation = observations.compute(uuid, (ignored, previous) ->
                    previous == null || previous.entityId != player.getId()
                            ? new Observation(player.getId()) : previous);
            observation.observe(minecraft.getConnection().getPlayerInfo(uuid) != null, ticks);
            if (observation.verdict() == ArtificialPlayerPolicy.Verdict.REAL_PLAYER)
                confirmedRealProfiles.add(uuid);
            if (scanNearbyPlayers && observation.profilePresent
                    && minecraft.player.distanceToSqr(player) <= nearbyRadiusSquared)
                nearbyNetworkPlayers.remember(uuid, ticks);
        }
        observations.entrySet().removeIf(entry -> !visible.contains(entry.getKey())
                && ticks - entry.getValue().lastEntityTick > STALE_ENTITY_TICKS);
        if (scanNearbyPlayers) lastNearbyPlayerScanTick = ticks;
        if (afkActive) nearbyNetworkPlayers.removeExpired(ticks);
        else clearNearbyPlayers();
    }

    boolean isNearbyNetworkPlayer(Player player) {
        return nearbyNetworkPlayers.contains(player.getUUID(), ticks);
    }

    boolean isArtificial(Player player) {
        Observation observation = observations.get(player.getUUID());
        return !confirmedRealProfiles.contains(player.getUUID())
                && observation != null && observation.entityId == player.getId()
                && observation.verdict() == ArtificialPlayerPolicy.Verdict.ARTIFICIAL_ENTITY;
    }

    String diagnostic(Player player) {
        if (isNearbyNetworkPlayer(player)) return "Perfil de jugador cercano · excluido temporalmente";
        Observation observation = observations.get(player.getUUID());
        if (confirmedRealProfiles.contains(player.getUUID())) return "Jugador real confirmado · excluido";
        if (observation == null || observation.entityId != player.getId())
            return "Analizando perfil de entidad artificial";
        return switch (observation.verdict()) {
            case REAL_PLAYER -> "Jugador real confirmado · excluido";
            case ARTIFICIAL_ENTITY -> "Entidad artificial confirmada";
            case UNKNOWN -> !observation.observedProfile
                    ? "Perfil de red no observado · entidad excluida por seguridad"
                    : observation.profilePresent
                            ? "Verificando si es un jugador real"
                            : "Analizando disguise · " + Math.min(observation.removedTicks,
                                    ArtificialPlayerPolicy.REMOVED_PROFILE_TICKS) + "/"
                                    + ArtificialPlayerPolicy.REMOVED_PROFILE_TICKS + " ticks";
        };
    }

    void clearNearbyPlayers() {
        nearbyNetworkPlayers.clear();
        lastNearbyPlayerScanTick = Long.MIN_VALUE;
    }
}
