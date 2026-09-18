package com.minelatino.afkfarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AfkFarmConfigTest {
    @TempDir Path directory;

    @Test void defaultsAreSafeAndDisabled() {
        var value = AfkFarmConfig.get(directory).snapshot();
        assertFalse(value.autoReconnect());
        assertFalse(value.commandsEnabled());
        assertFalse(value.navigationEnabled());
        assertFalse(value.autoAttackEnabled());
        assertFalse(value.attackArtificialPlayers());
        assertTrue(value.commands().isEmpty());
        assertTrue(value.activeRoute().isBlank());
        assertTrue(value.routes().isEmpty());
    }

    @Test void artificialPlayerAttackRequiresExplicitOptIn() {
        var config = AfkFarmConfig.get(directory.resolve("artificial"));
        assertFalse(config.snapshot().attackArtificialPlayers());
        config.setAttackArtificialPlayers(true);
        assertTrue(config.snapshot().attackArtificialPlayers());
    }

    @Test void keepsIndependentSettingsForEachFarmMode() {
        var config = AfkFarmConfig.get(directory.resolve("modes"));
        config.setMode(AfkFarmConfig.FarmMode.RECONNECT);
        config.setCommands(List.of("/warp reconnect"));
        config.setAllowedEntities(List.of("minecraft:zombie"), List.of());

        config.setMode(AfkFarmConfig.FarmMode.AUTONOMOUS);
        config.setCommands(List.of("/warp autonomous"));
        config.setAllowedEntities(List.of("minecraft:skeleton"), List.of());
        assertEquals(300, config.snapshot().recoveryWaitSeconds());

        config.setMode(AfkFarmConfig.FarmMode.RECONNECT);
        assertEquals(List.of("warp reconnect"), config.snapshot().commands());
        assertEquals(List.of("minecraft:zombie"), config.snapshot().allowedHostileMobs());
        assertTrue(config.snapshot().autoReconnect());
        config.setAutoReconnect(false);
        config.setMode(AfkFarmConfig.FarmMode.DIRECT);
        config.setMode(AfkFarmConfig.FarmMode.RECONNECT);
        assertFalse(config.snapshot().autoReconnect());
    }

    @Test void migratesExistingHostileFarmsToAttackConfirmedDisguises() throws Exception {
        Path game = directory.resolve("migration");
        Path file = game.resolve("config/minelatino-afk-farm/afk-farm.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":3,\"autoAttackEnabled\":true,\"attackHostileMobs\":true,"
                + "\"attackArtificialPlayers\":false}");
        assertTrue(AfkFarmConfig.get(game).snapshot().attackArtificialPlayers());
    }

    @Test void migratesVersionFourSettingsIntoReconnectMode() throws Exception {
        Path game = directory.resolve("mode-migration");
        Path file = game.resolve("config/minelatino-afk-farm/afk-farm.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":4,\"autoReconnect\":true,\"commandsEnabled\":true,"
                + "\"commands\":[\"warp granja\"],\"attackHostileMobs\":true,"
                + "\"allowedHostileMobs\":[\"minecraft:zombie\"]}");
        var value = AfkFarmConfig.get(game).snapshot();
        assertEquals(AfkFarmConfig.FarmMode.RECONNECT, value.mode());
        assertEquals(List.of("warp granja"), value.commands());
        assertEquals(List.of("minecraft:zombie"), value.allowedHostileMobs());
    }

    @Test void clampsDelaysCommandsAndAttackLists() {
        var config = AfkFarmConfig.get(directory.resolve("clamps"));
        config.setDelays(-1, 90, 999);
        config.setCommands(List.of("/warp granja", " ", "/home"));
        config.setAllowedEntities(List.of("minecraft:zombie", "INVALID", "minecraft:zombie"),
                List.of("minecraft:cow"));
        var value = config.snapshot();
        assertEquals(0, value.postJoinDelaySeconds());
        assertEquals(60, value.betweenCommandsDelaySeconds());
        assertEquals(300, value.movementStartDelaySeconds());
        assertEquals(List.of("warp granja", "home"), value.commands());
        assertEquals(List.of("minecraft:zombie"), value.allowedHostileMobs());
    }

    @Test void damagedJsonReturnsSafeConfiguration() throws Exception {
        Path game = directory.resolve("damaged");
        Path file = game.resolve("config/minelatino-afk-farm/afk-farm.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not-json");
        var value = AfkFarmConfig.get(game).snapshot();
        assertFalse(value.autoReconnect());
        assertFalse(value.autoAttackEnabled());
    }

    @Test void savesSelectsAndDeletesRecordedRoutes() {
        var config = AfkFarmConfig.get(directory.resolve("routes"));
        config.saveRoute("Granja", List.of(
                new AfkFarmConfig.RoutePoint(1, 64, 2),
                new AfkFarmConfig.RoutePoint(1.5, 64, 2.5),
                new AfkFarmConfig.RoutePoint(2, 64, 3)));
        config.saveRoute("Animales", List.of(
                new AfkFarmConfig.RoutePoint(10, 70, 10),
                new AfkFarmConfig.RoutePoint(11, 70, 10)));

        var saved = config.snapshot();
        assertEquals("Animales", saved.activeRoute());
        assertEquals(2, saved.routes().size());
        config.selectRoute("Granja");
        assertEquals("Granja", config.snapshot().activeRoute());
        config.deleteRoute("Granja");
        assertEquals("Animales", config.snapshot().activeRoute());
        assertEquals(1, config.snapshot().routes().size());
    }

    @Test void aSavedRouteCanRemainDisabledForOneMode() {
        Path game = directory.resolve("optional-route");
        var config = AfkFarmConfig.get(game);
        config.setMode(AfkFarmConfig.FarmMode.AUTONOMOUS);
        config.saveRoute("Regreso", List.of(
                new AfkFarmConfig.RoutePoint(1, 64, 2),
                new AfkFarmConfig.RoutePoint(2, 64, 3)));
        config.selectRoute("");
        AfkFarmConfig.get(directory.resolve("other-config"));

        var reloaded = AfkFarmConfig.get(game).snapshot();
        assertTrue(reloaded.activeRoute().isBlank());
        assertFalse(reloaded.navigationEnabled());
        assertEquals(1, reloaded.routes().size());
    }

    @Test void rejectsUnusableRecordedRoutes() {
        var config = AfkFarmConfig.get(directory.resolve("invalid-routes"));
        assertThrows(IllegalArgumentException.class, () -> config.saveRoute("Solo un punto",
                List.of(new AfkFarmConfig.RoutePoint(1, 2, 3))));
        assertThrows(IllegalArgumentException.class, () -> config.saveRoute("No finito", List.of(
                new AfkFarmConfig.RoutePoint(Double.NaN, 2, 3),
                new AfkFarmConfig.RoutePoint(4, 5, 6))));
        assertTrue(config.snapshot().routes().isEmpty());
    }
}
