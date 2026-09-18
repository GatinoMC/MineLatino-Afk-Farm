package com.minelatino.afkfarm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local settings for the three AFK modes. Nothing here controls attack frequency. */
public final class AfkFarmConfig {
    public static final int MAX_COMMANDS = 10;
    public static final int MAX_ALLOWED_ENTITIES = 64;
    public static final int MAX_ROUTES = 20;
    public static final int MAX_ROUTE_POINTS = 8192;
    public static final int AUTONOMOUS_RECOVERY_SECONDS = 300;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AfkFarmConfig current;

    public enum FarmMode {
        DIRECT("AFK Directo", "Iniciar AFK Directo"),
        RECONNECT("AFK Reconexión", "Iniciar AFK Reconexión"),
        AUTONOMOUS("AFK Autónomo", "Iniciar AFK Autónomo");

        private final String displayName;
        private final String startLabel;

        FarmMode(String displayName, String startLabel) {
            this.displayName = displayName;
            this.startLabel = startLabel;
        }

        public String displayName() { return displayName; }
        public String startLabel() { return startLabel; }
    }

    private static final class ModeData {
        boolean autoReconnect;
        boolean commandsEnabled;
        List<String> commands = new ArrayList<>();
        int postJoinDelaySeconds = 10;
        int betweenCommandsDelaySeconds = 3;
        int movementStartDelaySeconds = 10;
        double targetX;
        double targetY = 64;
        double targetZ;
        double arrivalRadius = 1.5;
        boolean navigationEnabled;
        boolean autoAttackEnabled;
        boolean attackHostileMobs;
        boolean attackAnimals;
        boolean attackArtificialPlayers;
        List<String> allowedHostileMobs = new ArrayList<>();
        List<String> allowedAnimals = new ArrayList<>();
        double maxCameraRotationDegreesPerTick = 8.0;
        String activeRoute = "";
    }

    private static final class Data {
        int version = 5;
        FarmMode selectedMode = FarmMode.DIRECT;
        ModeData directProfile = new ModeData();
        ModeData reconnectProfile = reconnectProfile();
        ModeData autonomousProfile = reconnectProfile();
        List<SavedRoute> routes = new ArrayList<>();

        // Version 4 fields retained only so an existing JSON can be migrated losslessly.
        boolean autoReconnect;
        boolean commandsEnabled;
        List<String> commands = new ArrayList<>();
        int postJoinDelaySeconds = 10;
        int betweenCommandsDelaySeconds = 3;
        int movementStartDelaySeconds = 10;
        double targetX;
        double targetY = 64;
        double targetZ;
        double arrivalRadius = 1.5;
        boolean navigationEnabled;
        boolean autoAttackEnabled;
        boolean attackHostileMobs;
        boolean attackAnimals;
        boolean attackArtificialPlayers;
        List<String> allowedHostileMobs = new ArrayList<>();
        List<String> allowedAnimals = new ArrayList<>();
        double maxCameraRotationDegreesPerTick = 8.0;
        String activeRoute = "";
    }

    public record RoutePoint(double x, double y, double z) {}
    public record SavedRoute(String name, List<RoutePoint> points) {}

    public record Snapshot(
            boolean autoReconnect,
            boolean commandsEnabled,
            List<String> commands,
            int postJoinDelaySeconds,
            int betweenCommandsDelaySeconds,
            int movementStartDelaySeconds,
            double targetX,
            double targetY,
            double targetZ,
            double arrivalRadius,
            boolean navigationEnabled,
            boolean autoAttackEnabled,
            boolean attackHostileMobs,
            boolean attackAnimals,
            boolean attackArtificialPlayers,
            List<String> allowedHostileMobs,
            List<String> allowedAnimals,
            double maxCameraRotationDegreesPerTick,
            String activeRoute,
            List<SavedRoute> routes,
            FarmMode mode,
            int recoveryWaitSeconds) {}

    private final Path path;
    private Data data;

    private AfkFarmConfig(Path path, Data data) {
        this.path = path;
        this.data = normalize(data == null ? new Data() : data);
    }

    public static synchronized AfkFarmConfig get(Path gameDirectory) {
        Path path = gameDirectory.resolve("config/minelatino-afk-farm/afk-farm.json");
        if (current != null && current.path.equals(path)) return current;
        Data loaded = null;
        try {
            if (Files.isRegularFile(path)) loaded = GSON.fromJson(Files.readString(path), Data.class);
        } catch (Exception ignored) {
            // A missing or damaged file always falls back to safe disabled defaults.
        }
        current = new AfkFarmConfig(path, loaded);
        current.save();
        return current;
    }

    public synchronized Snapshot snapshot() {
        ModeData profile = selectedProfile();
        return new Snapshot(profile.autoReconnect, profile.commandsEnabled, List.copyOf(profile.commands),
                profile.postJoinDelaySeconds, profile.betweenCommandsDelaySeconds, profile.movementStartDelaySeconds,
                profile.targetX, profile.targetY, profile.targetZ, profile.arrivalRadius, profile.navigationEnabled,
                profile.autoAttackEnabled, profile.attackHostileMobs, profile.attackAnimals,
                profile.attackArtificialPlayers, List.copyOf(profile.allowedHostileMobs),
                List.copyOf(profile.allowedAnimals), profile.maxCameraRotationDegreesPerTick, profile.activeRoute,
                data.routes.stream().map(route -> new SavedRoute(route.name(), List.copyOf(route.points()))).toList(),
                data.selectedMode, AUTONOMOUS_RECOVERY_SECONDS);
    }

    public synchronized void setMode(FarmMode mode) {
        data.selectedMode = mode == null ? FarmMode.DIRECT : mode;
        selectedProfile().autoAttackEnabled = true;
        save();
    }

    public synchronized void setAutoReconnect(boolean value) { selectedProfile().autoReconnect = value; save(); }
    public synchronized void setCommandsEnabled(boolean value) { selectedProfile().commandsEnabled = value; save(); }
    public synchronized void setNavigationEnabled(boolean value) { selectedProfile().navigationEnabled = value; save(); }
    public synchronized void setAutoAttackEnabled(boolean value) { selectedProfile().autoAttackEnabled = value; save(); }
    public synchronized void setAttackHostileMobs(boolean value) {
        selectedProfile().attackHostileMobs = value;
        selectedProfile().autoAttackEnabled = true;
        save();
    }
    public synchronized void setAttackAnimals(boolean value) {
        selectedProfile().attackAnimals = value;
        selectedProfile().autoAttackEnabled = true;
        save();
    }
    public synchronized void setAttackArtificialPlayers(boolean value) {
        selectedProfile().attackArtificialPlayers = value;
        selectedProfile().autoAttackEnabled = true;
        save();
    }

    public synchronized void setCommands(List<String> commands) {
        ModeData profile = selectedProfile();
        profile.commands = sanitizeCommands(commands);
        profile.commandsEnabled = !profile.commands.isEmpty();
        save();
    }

    public synchronized void setDelays(int postJoin, int between, int movement) {
        ModeData profile = selectedProfile();
        profile.postJoinDelaySeconds = clamp(postJoin, 0, 300);
        profile.betweenCommandsDelaySeconds = clamp(between, 0, 60);
        profile.movementStartDelaySeconds = clamp(movement, 0, 300);
        save();
    }

    public synchronized void setNavigation(double x, double y, double z, double radius, double rotation) {
        ModeData profile = selectedProfile();
        profile.targetX = finite(x, 0);
        profile.targetY = finite(y, 64);
        profile.targetZ = finite(z, 0);
        profile.arrivalRadius = clamp(finite(radius, 1.5), 0.25, 32);
        profile.maxCameraRotationDegreesPerTick = clamp(finite(rotation, 8), 0.5, 30);
        save();
    }

    public synchronized void setAllowedEntities(List<String> hostile, List<String> animals) {
        ModeData profile = selectedProfile();
        profile.allowedHostileMobs = sanitizeIds(hostile);
        profile.allowedAnimals = sanitizeIds(animals);
        profile.autoAttackEnabled = true;
        save();
    }

    public synchronized void saveRoute(String name, List<RoutePoint> points) {
        String safeName = sanitizeRouteName(name);
        List<RoutePoint> safePoints = sanitizePoints(points);
        if (safePoints.size() < 2) throw new IllegalArgumentException("El recorrido necesita al menos 2 puntos");
        Map<String, SavedRoute> routes = new LinkedHashMap<>();
        for (SavedRoute route : data.routes) routes.put(route.name(), route);
        routes.put(safeName, new SavedRoute(safeName, safePoints));
        data.routes = routes.values().stream().limit(MAX_ROUTES)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        selectedProfile().activeRoute = safeName;
        selectedProfile().navigationEnabled = true;
        save();
    }

    public synchronized void selectRoute(String name) {
        String safeName = name == null ? "" : name.trim();
        ModeData profile = selectedProfile();
        profile.activeRoute = data.routes.stream().anyMatch(route -> route.name().equals(safeName)) ? safeName : "";
        profile.navigationEnabled = !profile.activeRoute.isBlank();
        save();
    }

    public synchronized void deleteRoute(String name) {
        data.routes.removeIf(route -> route.name().equals(name));
        for (ModeData profile : profiles()) {
            if (profile.activeRoute.equals(name)) {
                profile.activeRoute = data.routes.isEmpty() ? "" : data.routes.getFirst().name();
                profile.navigationEnabled = !profile.activeRoute.isBlank();
            }
        }
        save();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(path.getParent());
            Path pending = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(pending, GSON.toJson(data));
            try {
                Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {}
    }

    private ModeData selectedProfile() {
        return switch (data.selectedMode) {
            case DIRECT -> data.directProfile;
            case RECONNECT -> data.reconnectProfile;
            case AUTONOMOUS -> data.autonomousProfile;
        };
    }

    private List<ModeData> profiles() {
        return List.of(data.directProfile, data.reconnectProfile, data.autonomousProfile);
    }

    private static Data normalize(Data value) {
        int sourceVersion = value.version;
        if (sourceVersion < 4 && value.autoAttackEnabled && value.attackHostileMobs)
            value.attackArtificialPlayers = true;
        value.routes = sanitizeRoutes(value.routes);
        if (sourceVersion < 5) {
            ModeData migrated = legacyProfile(value);
            value.directProfile = copyProfile(migrated);
            value.reconnectProfile = copyProfile(migrated);
            value.reconnectProfile.autoReconnect = true;
            value.autonomousProfile = copyProfile(migrated);
            value.autonomousProfile.autoReconnect = true;
            value.selectedMode = value.autoReconnect ? FarmMode.RECONNECT : FarmMode.DIRECT;
        }
        value.version = 5;
        if (value.selectedMode == null) value.selectedMode = FarmMode.DIRECT;
        if (value.directProfile == null) value.directProfile = new ModeData();
        if (value.reconnectProfile == null) value.reconnectProfile = reconnectProfile();
        if (value.autonomousProfile == null) value.autonomousProfile = reconnectProfile();
        normalizeProfile(value.directProfile, value.routes);
        normalizeProfile(value.reconnectProfile, value.routes);
        normalizeProfile(value.autonomousProfile, value.routes);
        return value;
    }

    private static ModeData reconnectProfile() {
        ModeData result = new ModeData();
        result.autoReconnect = true;
        return result;
    }

    private static ModeData legacyProfile(Data value) {
        ModeData result = new ModeData();
        result.autoReconnect = value.autoReconnect;
        result.commandsEnabled = value.commandsEnabled;
        result.commands = value.commands;
        result.postJoinDelaySeconds = value.postJoinDelaySeconds;
        result.betweenCommandsDelaySeconds = value.betweenCommandsDelaySeconds;
        result.movementStartDelaySeconds = value.movementStartDelaySeconds;
        result.targetX = value.targetX;
        result.targetY = value.targetY;
        result.targetZ = value.targetZ;
        result.arrivalRadius = value.arrivalRadius;
        result.navigationEnabled = value.navigationEnabled;
        result.autoAttackEnabled = value.autoAttackEnabled;
        result.attackHostileMobs = value.attackHostileMobs;
        result.attackAnimals = value.attackAnimals;
        result.attackArtificialPlayers = value.attackArtificialPlayers;
        result.allowedHostileMobs = value.allowedHostileMobs;
        result.allowedAnimals = value.allowedAnimals;
        result.maxCameraRotationDegreesPerTick = value.maxCameraRotationDegreesPerTick;
        result.activeRoute = value.activeRoute;
        return result;
    }

    private static ModeData copyProfile(ModeData source) {
        ModeData result = new ModeData();
        result.autoReconnect = source.autoReconnect;
        result.commandsEnabled = source.commandsEnabled;
        result.commands = new ArrayList<>(source.commands == null ? List.of() : source.commands);
        result.postJoinDelaySeconds = source.postJoinDelaySeconds;
        result.betweenCommandsDelaySeconds = source.betweenCommandsDelaySeconds;
        result.movementStartDelaySeconds = source.movementStartDelaySeconds;
        result.targetX = source.targetX;
        result.targetY = source.targetY;
        result.targetZ = source.targetZ;
        result.arrivalRadius = source.arrivalRadius;
        result.navigationEnabled = source.navigationEnabled;
        result.autoAttackEnabled = source.autoAttackEnabled;
        result.attackHostileMobs = source.attackHostileMobs;
        result.attackAnimals = source.attackAnimals;
        result.attackArtificialPlayers = source.attackArtificialPlayers;
        result.allowedHostileMobs = new ArrayList<>(source.allowedHostileMobs == null ? List.of() : source.allowedHostileMobs);
        result.allowedAnimals = new ArrayList<>(source.allowedAnimals == null ? List.of() : source.allowedAnimals);
        result.maxCameraRotationDegreesPerTick = source.maxCameraRotationDegreesPerTick;
        result.activeRoute = source.activeRoute;
        return result;
    }

    private static void normalizeProfile(ModeData value, List<SavedRoute> routes) {
        value.postJoinDelaySeconds = clamp(value.postJoinDelaySeconds, 0, 300);
        value.betweenCommandsDelaySeconds = clamp(value.betweenCommandsDelaySeconds, 0, 60);
        value.movementStartDelaySeconds = clamp(value.movementStartDelaySeconds, 0, 300);
        value.targetX = finite(value.targetX, 0);
        value.targetY = finite(value.targetY, 64);
        value.targetZ = finite(value.targetZ, 0);
        value.arrivalRadius = clamp(finite(value.arrivalRadius, 1.5), 0.25, 32);
        value.maxCameraRotationDegreesPerTick = clamp(finite(value.maxCameraRotationDegreesPerTick, 8), 0.5, 30);
        value.commands = sanitizeCommands(value.commands);
        value.allowedHostileMobs = sanitizeIds(value.allowedHostileMobs);
        value.allowedAnimals = sanitizeIds(value.allowedAnimals);
        if (value.activeRoute == null) value.activeRoute = "";
        if (!value.navigationEnabled) value.activeRoute = "";
        else if (routes.stream().noneMatch(route -> route.name().equals(value.activeRoute))) {
            value.activeRoute = routes.isEmpty() ? "" : routes.getFirst().name();
            value.navigationEnabled = !value.activeRoute.isBlank();
        }
        if (value.commands.isEmpty()) value.commandsEnabled = false;
        if (value.activeRoute.isBlank()) value.navigationEnabled = false;
    }

    private static ArrayList<SavedRoute> sanitizeRoutes(List<SavedRoute> values) {
        Map<String, SavedRoute> unique = new LinkedHashMap<>();
        if (values != null) for (SavedRoute route : values) {
            if (route == null) continue;
            try {
                String name = sanitizeRouteName(route.name());
                List<RoutePoint> points = sanitizePoints(route.points());
                if (points.size() >= 2) unique.put(name, new SavedRoute(name, points));
            } catch (IllegalArgumentException ignored) {}
            if (unique.size() >= MAX_ROUTES) break;
        }
        return new ArrayList<>(unique.values());
    }

    private static String sanitizeRouteName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (name.isBlank()) name = "Recorrido";
        if (name.length() > 48) name = name.substring(0, 48).trim();
        return name;
    }

    private static List<RoutePoint> sanitizePoints(List<RoutePoint> values) {
        if (values == null) return List.of();
        ArrayList<RoutePoint> result = new ArrayList<>();
        for (RoutePoint point : values) {
            if (point == null || !validWorldCoordinate(point.x()) || !Double.isFinite(point.y()) || !validWorldCoordinate(point.z())) continue;
            double y = clamp(point.y(), -2048, 2048);
            RoutePoint safe = new RoutePoint(point.x(), y, point.z());
            if (result.isEmpty() || distanceSquared(result.getLast(), safe) >= 0.0025) result.add(safe);
            if (result.size() >= MAX_ROUTE_POINTS) break;
        }
        return List.copyOf(result);
    }

    private static boolean validWorldCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000;
    }

    private static double distanceSquared(RoutePoint a, RoutePoint b) {
        double x = a.x() - b.x(), y = a.y() - b.y(), z = a.z() - b.z();
        return x * x + y * y + z * z;
    }

    private static List<String> sanitizeCommands(List<String> values) {
        if (values == null) return new ArrayList<>();
        return values.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .map(value -> value.startsWith("/") ? value.substring(1).trim() : value)
                .filter(value -> !value.isBlank() && value.length() <= 256)
                .limit(MAX_COMMANDS).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static List<String> sanitizeIds(List<String> values) {
        if (values == null) return new ArrayList<>();
        return values.stream().filter(java.util.Objects::nonNull).map(value -> value.trim().toLowerCase())
                .filter(value -> value.equals("*") || value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                .distinct().limit(MAX_ALLOWED_ENTITIES)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
