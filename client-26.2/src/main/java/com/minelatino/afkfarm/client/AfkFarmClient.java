package com.minelatino.afkfarm.client;

import com.minelatino.afkfarm.AfkFarmConfig;
import com.minelatino.afkfarm.AfkFarmAttackPolicy;
import com.minelatino.afkfarm.AfkRecoveryPolicy;
import com.minelatino.afkfarm.RecordedRouteNavigator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/** Tick-driven AFK workflow. All Minecraft interaction happens on the client thread. */
public final class AfkFarmClient {
    /** Compile-time attack policy. It is intentionally absent from afk-farm.json and the backend. */
    public static final double ATTACK_SEARCH_RADIUS = 4.5;
    private static final int WORLD_READY_TICKS = 20;
    private static final int TRANSFER_SCREEN_GRACE_TICKS = 200;
    private static final double ROUTE_RECORDING_STEP = 0.65;
    private static final double WAYPOINT_RADIUS = 0.72;
    private static final int ROUTE_STUCK_JUMP_TICKS = 24;
    private static final int ROUTE_ABORT_TICKS = 120;
    private static final int JUMP_PULSE_TICKS = 2;
    private static final int JUMP_COOLDOWN_TICKS = 18;
    private static final double FARM_ANCHOR_RADIUS = 24.0;
    private static final int RELOCATION_CONFIRM_TICKS = 60;
    private static final String AUTHORIZED_SERVER = "play.minelatino.com";
    private static final AfkFarmClient INSTANCE = new AfkFarmClient();

    private enum State { IDLE, WAITING_WORLD, WAITING_RECOVERY, WAITING_COMMAND, WAITING_MOVEMENT, MOVING, ATTACKING, COMPLETE }

    private State state = State.IDLE;
    private boolean active;
    private boolean driving;
    private boolean recording;
    private String recordingName = "";
    private final List<AfkFarmConfig.RoutePoint> recordedPoints = new ArrayList<>();
    private final ArtificialPlayerTracker artificialPlayers = new ArtificialPlayerTracker();
    private long lastRecordedTick;
    private int readyTicks;
    private Object observedLevel;
    private State suspendedState;
    private long suspendedRemainingTicks;
    private int transferScreenGrace;
    private boolean sequenceStarted;
    private long ticks;
    private long deadline;
    private long lastAttackTick = Long.MIN_VALUE / 2;
    private int lockedTargetId = -1;
    private UUID lockedTargetUuid;
    private int commandIndex;
    private int routeIndex;
    private boolean routeInitialized;
    private double routeBestDistance;
    private long routeProgressTick;
    private int jumpPulseTicks;
    private int jumpCooldownTicks;
    private AfkFarmConfig.FarmMode activeMode = AfkFarmConfig.FarmMode.DIRECT;
    private boolean recoveryPending;
    private boolean disconnectedRecovery;
    private boolean anchorSet;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private int outsideAnchorTicks;
    private String status = "";

    public static AfkFarmClient instance() { return INSTANCE; }
    public boolean active() { return active; }
    public boolean recording() { return recording; }
    public int recordedPointCount() { return recordedPoints.size(); }
    public String status() { return status; }
    public AfkFarmConfig.FarmMode activeMode() { return activeMode; }

    /** Opening the assistant always leaves automation stopped until the user starts it again. */
    public void pauseForAssistant() {
        if (active) cancel("AFK Farm pausado al abrir el asistente IA");
        if (recording) {
            recording = false;
            recordedPoints.clear();
            status = "Grabación cancelada al abrir el asistente IA";
        }
    }

    public void start() {
        AfkFarmConfig.Snapshot config = config();
        boolean hasTargets = config.attackArtificialPlayers()
                || config.attackHostileMobs() && !config.allowedHostileMobs().isEmpty()
                || config.attackAnimals() && !config.allowedAnimals().isEmpty();
        if (!hasTargets) {
            status = "Selecciona al menos un mob antes de iniciar AFK Farm";
            return;
        }
        status = "Validando tiempo de uso AFK Farm";
        AfkUsageController.instance().start(this::startAuthorized, message -> status = message);
    }

    private void startAuthorized() {
        activeMode = config().mode();
        if (recording) stopRecording();
        BackgroundPerformanceController.activate();
        active = true;
        state = State.WAITING_WORLD;
        sequenceStarted = false;
        suspendedState = null;
        recoveryPending = false;
        disconnectedRecovery = false;
        anchorSet = false;
        outsideAnchorTicks = 0;
        readyTicks = 0;
        clearLockedTarget();
        observedLevel = Minecraft.getInstance().level;
        status = "Esperando que el mundo termine de cargar";
    }

    public boolean startRecording(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!worldReady(minecraft)) { status = "Entra a un servidor antes de grabar"; return false; }
        cancel(null);
        recording = true;
        recordingName = name == null || name.isBlank() ? "Recorrido " + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd-MM HH-mm")) : name.trim();
        recordedPoints.clear();
        lastRecordedTick = Long.MIN_VALUE / 2;
        recordPoint(minecraft, true);
        status = "Grabando recorrido · 1 punto";
        return true;
    }

    public boolean stopRecording() {
        if (!recording) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (worldReady(minecraft)) recordPoint(minecraft, true);
        recording = false;
        if (recordedPoints.size() < 2) {
            status = "Recorrido descartado: camina antes de guardarlo";
            recordedPoints.clear();
            return false;
        }
        AfkFarmConfig.get(minecraft.gameDirectory.toPath()).saveRoute(recordingName, List.copyOf(recordedPoints));
        status = "Recorrido guardado · " + recordedPoints.size() + " puntos";
        return true;
    }

    public void cancel(String reason) {
        releaseControls();
        AfkUsageController.instance().stop();
        active = false;
        BackgroundPerformanceController.deactivate();
        state = State.IDLE;
        sequenceStarted = false;
        suspendedState = null;
        recoveryPending = false;
        disconnectedRecovery = false;
        anchorSet = false;
        outsideAnchorTicks = 0;
        clearLockedTarget();
        artificialPlayers.clearNearbyPlayers();
        status = reason == null ? "" : reason;
    }

    /** Called by the disconnect screen. A negative value means no automatic reconnect. */
    public int onDisconnected() {
        if (!active) return -1;
        AfkFarmConfig.Snapshot config = config();
        int delay = AfkRecoveryPolicy.reconnectDelaySeconds(activeMode, config.autoReconnect());
        if (delay < 0) {
            cancel(activeMode == AfkFarmConfig.FarmMode.DIRECT
                    ? "AFK Directo detenido por desconexión"
                    : "Reconexión automática desactivada");
            return -1;
        }
        releaseControls();
        clearLockedTarget();
        recoveryPending = true;
        disconnectedRecovery = true;
        suspendedState = null;
        state = State.WAITING_WORLD;
        status = "Desconexión detectada · reconectando en " + delay + " segundos";
        return delay;
    }

    public void tick() {
        ticks++;
        Minecraft minecraft = Minecraft.getInstance();
        artificialPlayers.tick(minecraft, active);
        // Key presses must be consumed every client tick. Keeping this here makes
        // Fabric's Minecraft mixin and Forge's client tick event share the exact
        // same behaviour and also lets the configured key close an open assistant.
        AiAssistantKeybind.handleTick(minecraft);
        AfkUsageController.instance().tick(active);
        AutoReconnect.tick();
        AutoReconnect.remember(minecraft.getCurrentServer());

        if (recording) {
            if (worldReady(minecraft)) {
                recordPoint(minecraft, false);
                status = "Grabando recorrido · " + recordedPoints.size() + " puntos";
            } else status = "Grabación pausada durante el cambio de servidor";
            return;
        }

        if (minecraft.level != observedLevel) {
            clearLockedTarget();
            if (active && sequenceStarted && state == State.ATTACKING) handleUnexpectedTransfer();
            else if (active && sequenceStarted && state != State.WAITING_WORLD
                    && state != State.WAITING_RECOVERY) suspendForTransfer();
            observedLevel = minecraft.level;
            readyTicks = 0;
            transferScreenGrace = TRANSFER_SCREEN_GRACE_TICKS;
        }

        if (!worldReady(minecraft)) {
            readyTicks = 0;
            releaseControls();
            if (active) {
                if (sequenceStarted && state != State.WAITING_WORLD
                        && state != State.WAITING_RECOVERY) suspendForTransfer();
                if (state != State.WAITING_RECOVERY) {
                    state = State.WAITING_WORLD;
                    status = "Cambio de host detectado · esperando el nuevo mundo";
                }
            }
            return;
        }

        readyTicks++;
        if (readyTicks == WORLD_READY_TICKS) {
            AutoReconnect.connected();
            if (active && state == State.WAITING_WORLD) {
                if (recoveryPending && disconnectedRecovery) beginSequence();
                else if (suspendedState != null) resumeAfterTransfer();
                else beginInitialFarm(minecraft);
            }
        }
        if (!active || readyTicks < WORLD_READY_TICKS) return;

        if (transferScreenGrace > 0) transferScreenGrace--;
        if (minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof AfkFarmScreen)
                && !(minecraft.gui.screen() instanceof EntitySelectionScreen)
                && transferScreenGrace <= 0 && !isTransferScreen(minecraft.gui.screen())) {
            cancel("Secuencia detenida al cambiar de pantalla");
            return;
        }
        if (manualMovement(minecraft)) {
            cancel("Secuencia detenida por movimiento manual");
            return;
        }

        switch (state) {
            case WAITING_RECOVERY -> tickRecoveryWait();
            case WAITING_COMMAND -> tickCommands(minecraft);
            case WAITING_MOVEMENT -> tickMovementDelay();
            case MOVING -> tickMovement(minecraft);
            case ATTACKING -> tickAttack(minecraft);
            default -> {}
        }
    }

    private void beginInitialFarm(Minecraft minecraft) {
        sequenceStarted = true;
        setFarmAnchor(minecraft);
        beginAttackOrComplete(config());
    }

    private void handleUnexpectedTransfer() {
        if (!AfkRecoveryPolicy.recoversUnexpectedTransfer(activeMode)) {
            cancel(activeMode == AfkFarmConfig.FarmMode.DIRECT
                    ? "AFK Directo detenido por cambio de mundo o host"
                    : "AFK Reconexión detenido por cambio de mundo o host");
            return;
        }
        beginAutonomousRecovery("Cambio inesperado de mundo o host");
    }

    private void beginAutonomousRecovery(String reason) {
        releaseControls();
        clearLockedTarget();
        recoveryPending = true;
        disconnectedRecovery = false;
        suspendedState = null;
        state = State.WAITING_RECOVERY;
        deadline = ticks + seconds(config().recoveryWaitSeconds());
        status = reason + " · recuperación en " + remainingSeconds() + " segundos";
    }

    private void tickRecoveryWait() {
        if (ticks < deadline) {
            status = "Reinicio de host detectado · recuperación en " + remainingSeconds() + " segundos";
            return;
        }
        beginSequence();
    }

    private void beginSequence() {
        AfkFarmConfig.Snapshot config = config();
        sequenceStarted = true;
        commandIndex = 0;
        if (config.commandsEnabled() && !config.commands().isEmpty()) {
            state = State.WAITING_COMMAND;
            deadline = ticks + seconds(config.postJoinDelaySeconds());
            updateCommandStatus(config);
        } else {
            beginMovementDelay(config);
        }
    }

    private void tickCommands(Minecraft minecraft) {
        AfkFarmConfig.Snapshot config = config();
        if (!config.commandsEnabled() || config.commands().isEmpty() || commandIndex >= config.commands().size()) {
            beginMovementDelay(config);
            return;
        }
        if (ticks < deadline) {
            updateCommandStatus(config);
            return;
        }
        // This state is reached only after player, level and connection stayed ready for WORLD_READY_TICKS.
        minecraft.player.connection.sendCommand(config.commands().get(commandIndex));
        commandIndex++;
        if (commandIndex < config.commands().size()) {
            deadline = ticks + seconds(config.betweenCommandsDelaySeconds());
            updateCommandStatus(config);
        } else {
            beginMovementDelay(config);
        }
    }

    private void updateCommandStatus(AfkFarmConfig.Snapshot config) {
        String command = commandIndex < config.commands().size() ? config.commands().get(commandIndex) : "";
        status = "Ejecutando /" + command + " en " + remainingSeconds() + " segundos";
    }

    private void beginMovementDelay(AfkFarmConfig.Snapshot config) {
        routeIndex = 0;
        routeInitialized = false;
        jumpPulseTicks = 0;
        jumpCooldownTicks = 0;
        state = State.WAITING_MOVEMENT;
        deadline = ticks + seconds(config.movementStartDelaySeconds());
        status = "Esperando teletransporte · comenzando movimiento en " + remainingSeconds() + " segundos";
    }

    private void tickMovementDelay() {
        AfkFarmConfig.Snapshot config = config();
        if (ticks < deadline) {
            status = "Comenzando movimiento en " + remainingSeconds() + " segundos";
            return;
        }
        if (config.navigationEnabled()) {
            state = State.MOVING;
            status = "Caminando hacia el destino";
        } else {
            beginAttackOrComplete(config);
        }
    }

    private void tickMovement(Minecraft minecraft) {
        AfkFarmConfig.Snapshot config = config();
        if (!config.navigationEnabled()) {
            releaseControls();
            beginAttackOrComplete(config);
            return;
        }
        AfkFarmConfig.SavedRoute route = selectedRoute(config);
        if (route == null || route.points().size() < 2) {
            releaseControls();
            cancel("Selecciona o graba un recorrido antes de caminar");
            return;
        }
        if (!routeInitialized) initializeRoute(route, minecraft);
        int previousIndex = routeIndex;
        routeIndex = RecordedRouteNavigator.advance(route.points(), routeIndex,
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(), WAYPOINT_RADIUS);
        AfkFarmConfig.RoutePoint finalPoint = route.points().getLast();
        double finalDistance = distance(finalPoint, minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
        if (routeIndex >= route.points().size() - 1 && finalDistance <= config.arrivalRadius()) {
            releaseControls();
            beginAttackOrComplete(config);
            return;
        }
        double progressDistance = distance(route.points().get(routeIndex), minecraft.player.getX(),
                minecraft.player.getY(), minecraft.player.getZ());
        if (routeIndex != previousIndex) markRouteProgress(progressDistance);
        else if (progressDistance + 0.18 < routeBestDistance) markRouteProgress(progressDistance);
        long stuckTicks = ticks - routeProgressTick;
        if (stuckTicks >= ROUTE_ABORT_TICKS) {
            cancel("Recorrido bloqueado durante 6 segundos · vuelve a grabarlo alrededor del obstáculo");
            return;
        }

        int lookAhead = RecordedRouteNavigator.lookAheadIndex(route.points(), routeIndex);
        AfkFarmConfig.RoutePoint point = route.points().get(lookAhead);
        double dx = point.x() - minecraft.player.getX();
        double dz = point.z() - minecraft.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        minecraft.player.setYRot(approachAngle(minecraft.player.getYRot(), targetYaw,
                (float)config.maxCameraRotationDegreesPerTick()));
        minecraft.player.setYHeadRot(minecraft.player.getYRot());
        driving = true;
        minecraft.options.keyUp.setDown(true);
        if (jumpCooldownTicks > 0) jumpCooldownTicks--;
        boolean recordedStep = RecordedRouteNavigator.maximumRise(route.points(), routeIndex, lookAhead,
                minecraft.player.getY()) > 0.42;
        if (jumpPulseTicks <= 0 && jumpCooldownTicks <= 0
                && (minecraft.player.horizontalCollision || recordedStep || stuckTicks >= ROUTE_STUCK_JUMP_TICKS)) {
            jumpPulseTicks = JUMP_PULSE_TICKS;
            jumpCooldownTicks = JUMP_COOLDOWN_TICKS;
        }
        boolean jumping = jumpPulseTicks > 0;
        minecraft.options.keyJump.setDown(jumping);
        if (jumping) jumpPulseTicks--;
        status = String.format(Locale.ROOT, "Recorrido %s · punto %d/%d · %.1f bloques%s",
                route.name(), routeIndex + 1, route.points().size(), distance,
                stuckTicks >= ROUTE_STUCK_JUMP_TICKS ? " · sorteando obstáculo" : "");
    }

    private void beginAttackOrComplete(AfkFarmConfig.Snapshot config) {
        clearLockedTarget();
        if (!config.autoAttackEnabled()) {
            complete("Destino alcanzado");
            return;
        }
        if (!attackAuthorized(Minecraft.getInstance())) {
            complete("Ataque bloqueado: servidor no autorizado");
            return;
        }
        if (recoveryPending && anchorSet && !nearFarmAnchor(Minecraft.getInstance())) {
            cancel("No se pudo confirmar el regreso a la zona de farmeo");
            return;
        }
        recoveryPending = false;
        disconnectedRecovery = false;
        setFarmAnchor(Minecraft.getInstance());
        state = State.ATTACKING;
        status = "Buscando objetivos permitidos";
    }

    private void tickAttack(Minecraft minecraft) {
        AfkFarmConfig.Snapshot config = config();
        if (!config.autoAttackEnabled() || !attackAuthorized(minecraft)) {
            complete("Ataque automático detenido");
            return;
        }
        if (tickUnexpectedRelocation(minecraft)) return;
        LivingEntity target = nearestTarget(minecraft, config);
        if (target == null) {
            status = targetDiagnostic(minecraft, config);
            return;
        }
        rotateToward(minecraft, target, (float)config.maxCameraRotationDegreesPerTick());
        String targetName = target instanceof Player ? "disguise artificial"
                : BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        double attackRange = minecraft.player.entityInteractionRange();
        double distanceSquared = minecraft.player.distanceToSqr(target);
        if (distanceSquared > attackRange * attackRange) {
            status = String.format(Locale.ROOT, "Acércate a %s · %.1f bloques", targetName,
                    Math.sqrt(distanceSquared));
            return;
        }
        float attackStrength = minecraft.player.getAttackStrengthScale(0f);
        if (ticks - lastAttackTick < AfkFarmAttackPolicy.MINIMUM_ATTACK_INTERVAL_TICKS) {
            status = "Objetivo fijado · límite seguro de ataque";
            return;
        }
        if (attackStrength < AfkFarmAttackPolicy.REQUIRED_ATTACK_STRENGTH) {
            status = String.format(Locale.ROOT, "Esperando cooldown · %.0f%%", attackStrength * 100.0f);
            return;
        }
        if (!AfkFarmAttackPolicy.mayAttempt(ticks, lastAttackTick, attackStrength)) return;
        if (minecraft.gameMode == null) return;
        minecraft.gameMode.attack(minecraft.player, target);
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        lastAttackTick = ticks;
        status = "Atacando " + targetName;
    }

    private boolean tickUnexpectedRelocation(Minecraft minecraft) {
        if (!anchorSet || minecraft.player == null) return false;
        double dx = minecraft.player.getX() - anchorX;
        double dy = minecraft.player.getY() - anchorY;
        double dz = minecraft.player.getZ() - anchorZ;
        if (dx * dx + dy * dy + dz * dz <= FARM_ANCHOR_RADIUS * FARM_ANCHOR_RADIUS) {
            outsideAnchorTicks = 0;
            return false;
        }
        if (++outsideAnchorTicks < RELOCATION_CONFIRM_TICKS) return false;
        outsideAnchorTicks = 0;
        if (activeMode == AfkFarmConfig.FarmMode.AUTONOMOUS)
            beginAutonomousRecovery("Traslado fuera de la zona de farmeo");
        else cancel(activeMode == AfkFarmConfig.FarmMode.DIRECT
                ? "AFK Directo detenido por cambio de ubicación"
                : "AFK Reconexión detenido por cambio de ubicación");
        return true;
    }

    private void setFarmAnchor(Minecraft minecraft) {
        if (minecraft.player == null) return;
        anchorX = minecraft.player.getX();
        anchorY = minecraft.player.getY();
        anchorZ = minecraft.player.getZ();
        anchorSet = true;
        outsideAnchorTicks = 0;
    }

    private boolean nearFarmAnchor(Minecraft minecraft) {
        if (minecraft.player == null) return false;
        double dx = minecraft.player.getX() - anchorX;
        double dy = minecraft.player.getY() - anchorY;
        double dz = minecraft.player.getZ() - anchorZ;
        return dx * dx + dy * dy + dz * dz <= FARM_ANCHOR_RADIUS * FARM_ANCHOR_RADIUS;
    }

    private LivingEntity nearestTarget(Minecraft minecraft, AfkFarmConfig.Snapshot config) {
        LivingEntity locked = lockedTarget(minecraft, config);
        if (locked != null) return locked;
        List<Entity> entities = minecraft.level.getEntities(minecraft.player,
                minecraft.player.getBoundingBox().inflate(ATTACK_SEARCH_RADIUS), entity ->
                        entity instanceof LivingEntity living && living.isAlive() && entity != minecraft.player
                                && minecraft.player.hasLineOfSight(entity) && allowed(living, config));
        double interactionRangeSquared = Math.pow(minecraft.player.entityInteractionRange(), 2);
        LivingEntity selected = entities.stream().map(entity -> (LivingEntity)entity)
                .min(Comparator
                        .comparing((LivingEntity entity) -> minecraft.player.distanceToSqr(entity)
                                > interactionRangeSquared)
                        .thenComparingDouble(minecraft.player::distanceToSqr))
                .orElse(null);
        if (selected != null && minecraft.player.distanceToSqr(selected) <= interactionRangeSquared) {
            lockedTargetId = selected.getId();
            lockedTargetUuid = selected.getUUID();
        }
        return selected;
    }

    /** Keeps a valid target between ticks, avoiding a full entity scan before every hit. */
    private LivingEntity lockedTarget(Minecraft minecraft, AfkFarmConfig.Snapshot config) {
        if (lockedTargetId < 0 || lockedTargetUuid == null) return null;
        Entity entity = minecraft.level.getEntity(lockedTargetId);
        if (!(entity instanceof LivingEntity living) || !living.isAlive()
                || !lockedTargetUuid.equals(living.getUUID())
                || minecraft.player.distanceToSqr(living) > ATTACK_SEARCH_RADIUS * ATTACK_SEARCH_RADIUS
                || minecraft.player.distanceToSqr(living) > Math.pow(minecraft.player.entityInteractionRange(), 2)
                || !minecraft.player.hasLineOfSight(living) || !allowed(living, config)) {
            clearLockedTarget();
            return null;
        }
        return living;
    }

    private void clearLockedTarget() {
        lockedTargetId = -1;
        lockedTargetUuid = null;
    }

    private void complete(String reason) {
        releaseControls();
        AfkUsageController.instance().stop();
        active = false;
        BackgroundPerformanceController.deactivate();
        state = State.COMPLETE;
        artificialPlayers.clearNearbyPlayers();
        status = reason;
    }

    private boolean allowed(LivingEntity entity, AfkFarmConfig.Snapshot config) {
        if (entity instanceof Player player) {
            if (artificialPlayers.isNearbyNetworkPlayer(player)) return false;
            return config.attackArtificialPlayers() && artificialPlayers.isArtificial(player);
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if (entity instanceof Enemy) return AfkFarmAttackPolicy.allowsId(
                config.attackHostileMobs(), config.allowedHostileMobs(), id);
        if (entity instanceof Animal animal) {
            if (animal instanceof TamableAnimal tameable && tameable.isTame()) return false;
            return AfkFarmAttackPolicy.allowsId(config.attackAnimals(), config.allowedAnimals(), id);
        }
        return false;
    }

    private String targetDiagnostic(Minecraft minecraft, AfkFarmConfig.Snapshot config) {
        List<LivingEntity> nearby = minecraft.level.getEntitiesOfClass(LivingEntity.class,
                minecraft.player.getBoundingBox().inflate(12), entity -> entity.isAlive() && entity != minecraft.player);
        LivingEntity entity = nearby.stream().min(Comparator
                .comparingInt(AfkFarmClient::targetDiagnosticPriority)
                .thenComparingDouble(minecraft.player::distanceToSqr)).orElse(null);
        if (entity == null) return "Sin entidades vivas en 12 bloques";
        double blocks = Math.sqrt(minecraft.player.distanceToSqr(entity));
        if (blocks > ATTACK_SEARCH_RADIUS)
            return String.format(Locale.ROOT, "Entidad a %.1f bloques · alcance de búsqueda %.1f", blocks, ATTACK_SEARCH_RADIUS);
        if (!minecraft.player.hasLineOfSight(entity)) return "Entidad detectada sin línea de visión";
        if (entity instanceof Player player) {
            if (!config.attackArtificialPlayers())
                return String.format(Locale.ROOT, "Entidad con apariencia de jugador a %.1f bloques · activa disguises", blocks);
            return artificialPlayers.diagnostic(player);
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if (entity instanceof Enemy && !config.attackHostileMobs()) return "Activa mobs hostiles para " + id;
        if (entity instanceof Enemy && !AfkFarmAttackPolicy.allowsId(true, config.allowedHostileMobs(), id))
            return id + " no está seleccionado";
        if (entity instanceof Animal && !config.attackAnimals()) return "Activa animales para " + id;
        if (entity instanceof Animal && !AfkFarmAttackPolicy.allowsId(true, config.allowedAnimals(), id))
            return id + " no está seleccionado";
        return id + " no es una categoría atacable";
    }

    private static int targetDiagnosticPriority(LivingEntity entity) {
        if (entity instanceof Enemy || entity instanceof Animal) return 0;
        if (entity instanceof Player) return 1;
        return 2;
    }

    private void initializeRoute(AfkFarmConfig.SavedRoute route, Minecraft minecraft) {
        routeIndex = RecordedRouteNavigator.startingIndex(route.points(), minecraft.player.getX(),
                minecraft.player.getY(), minecraft.player.getZ());
        routeInitialized = true;
        markRouteProgress(distance(route.points().get(routeIndex), minecraft.player.getX(),
                minecraft.player.getY(), minecraft.player.getZ()));
    }

    private void markRouteProgress(double distance) {
        routeBestDistance = distance;
        routeProgressTick = ticks;
    }

    private void rotateToward(Minecraft minecraft, LivingEntity target, float maximum) {
        double dx = target.getX() - minecraft.player.getX();
        double dz = target.getZ() - minecraft.player.getZ();
        double eye = target.getEyeY() - minecraft.player.getEyeY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float)-Math.toDegrees(Math.atan2(eye, horizontal));
        minecraft.player.setYRot(approachAngle(minecraft.player.getYRot(), yaw, maximum));
        minecraft.player.setXRot(approachAngle(minecraft.player.getXRot(), pitch, maximum));
        minecraft.player.setYHeadRot(minecraft.player.getYRot());
    }

    private boolean manualMovement(Minecraft minecraft) {
        if (driving) {
            return minecraft.options.keyDown.isDown() || minecraft.options.keyLeft.isDown()
                    || minecraft.options.keyRight.isDown() || minecraft.options.keyShift.isDown();
        }
        return minecraft.options.keyUp.isDown() || minecraft.options.keyDown.isDown()
                || minecraft.options.keyLeft.isDown() || minecraft.options.keyRight.isDown()
                || minecraft.options.keyJump.isDown() || minecraft.options.keyShift.isDown();
    }

    private void releaseControls() {
        if (!driving) return;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keyJump.setDown(false);
        driving = false;
    }

    private void recordPoint(Minecraft minecraft, boolean force) {
        AfkFarmConfig.RoutePoint next = new AfkFarmConfig.RoutePoint(
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
        AfkFarmConfig.RoutePoint previous = recordedPoints.isEmpty() ? null : recordedPoints.getLast();
        double distance = previous == null ? Double.MAX_VALUE : Math.sqrt(distanceSquared(previous, next));
        if (force || (ticks - lastRecordedTick >= 2 && distance >= ROUTE_RECORDING_STEP)) {
            if (previous == null || distance >= 0.05) recordedPoints.add(next);
            lastRecordedTick = ticks;
        }
    }

    private void suspendForTransfer() {
        releaseControls();
        suspendedState = state;
        suspendedRemainingTicks = Math.max(0, deadline - ticks);
        state = State.WAITING_WORLD;
        transferScreenGrace = TRANSFER_SCREEN_GRACE_TICKS;
    }

    private void resumeAfterTransfer() {
        state = suspendedState;
        suspendedState = null;
        deadline = ticks + suspendedRemainingTicks;
        status = switch (state) {
            case WAITING_COMMAND -> "Host conectado · continuando comandos";
            case WAITING_MOVEMENT -> "Host conectado · continuando espera de movimiento";
            case MOVING -> "Host conectado · retomando recorrido";
            case ATTACKING -> "Host conectado · retomando búsqueda de objetivos";
            default -> "Host conectado · retomando flujo AFK";
        };
    }

    private static boolean isTransferScreen(net.minecraft.client.gui.screens.Screen screen) {
        String name = screen.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return name.contains("receiving") || name.contains("progress") || name.contains("connect")
                || name.contains("message") || name.contains("downloadterrain");
    }

    private static AfkFarmConfig.SavedRoute selectedRoute(AfkFarmConfig.Snapshot config) {
        return config.routes().stream().filter(route -> route.name().equals(config.activeRoute())).findFirst().orElse(null);
    }

    private static double distanceSquared(AfkFarmConfig.RoutePoint a, AfkFarmConfig.RoutePoint b) {
        double x = a.x() - b.x(), y = a.y() - b.y(), z = a.z() - b.z();
        return x * x + y * y + z * z;
    }

    private static double distance(AfkFarmConfig.RoutePoint point, double x, double y, double z) {
        double dx = point.x() - x, dy = point.y() - y, dz = point.z() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean attackAuthorized(Minecraft minecraft) {
        if (minecraft.getCurrentServer() == null || minecraft.getCurrentServer().ip == null) return false;
        String address = minecraft.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            address = end >= 0 ? address.substring(1, end) : address;
        } else {
            int colon = address.lastIndexOf(':');
            if (colon > 0) address = address.substring(0, colon);
        }
        return AUTHORIZED_SERVER.equals(address);
    }

    private AfkFarmConfig.Snapshot config() {
        return AfkFarmConfig.get(Minecraft.getInstance().gameDirectory.toPath()).snapshot();
    }

    private long remainingSeconds() { return Math.max(0, (deadline - ticks + 19) / 20); }
    private static long seconds(int value) { return Math.max(0, value) * 20L; }
    private static boolean worldReady(Minecraft minecraft) {
        return minecraft.level != null && minecraft.player != null && minecraft.player.connection != null;
    }
    private static float approachAngle(float current, float target, float maximum) {
        return current + Mth.clamp(Mth.wrapDegrees(target - current), -maximum, maximum);
    }
}
