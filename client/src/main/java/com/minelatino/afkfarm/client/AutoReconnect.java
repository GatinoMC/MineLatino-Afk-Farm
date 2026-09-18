package com.minelatino.afkfarm.client;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** Adds reconnect controls only when the local setting is enabled. */
public final class AutoReconnect {
    private static ServerData lastServer;
    private static DisconnectedScreen screen;
    private static StringWidget statusWidget;
    private static long deadline;
    private static int attempt;
    private static boolean cancelled;

    private AutoReconnect() {}

    public static void remember(ServerData server) {
        if (server != null) lastServer = server;
    }

    public static void install(DisconnectedScreen disconnected, Consumer<AbstractWidget> add) {
        Minecraft minecraft = Minecraft.getInstance();
        remember(minecraft.getCurrentServer());
        int delaySeconds = AfkFarmClient.instance().onDisconnected();
        if (delaySeconds < 0 || lastServer == null) return;
        screen = disconnected;
        cancelled = false;
        attempt++;
        deadline = System.currentTimeMillis() + delaySeconds * 1000L;
        int width = Math.min(180, Math.max(120, disconnected.width - 8));
        int x = Math.max(4, disconnected.width - width - 4);
        statusWidget = new StringWidget(x, 7, width, 18, Component.empty(), minecraft.font);
        add.accept(statusWidget);
        add.accept(Button.builder(Component.literal("Reconectar ahora"), button -> reconnect())
                .bounds(x, 27, width, 20).build());
        add.accept(Button.builder(Component.literal("Cancelar reconexión"), button -> cancel())
                .bounds(x, 51, width, 20).build());
        updateStatus();
    }

    public static void tick() {
        if (screen == null || Minecraft.getInstance().screen != screen || cancelled) return;
        if (!AfkFarmClient.instance().active()) { cancel(); return; }
        updateStatus();
        if (System.currentTimeMillis() >= deadline) reconnect();
    }

    public static void connected() {
        screen = null;
        statusWidget = null;
        cancelled = false;
        attempt = 0;
    }

    private static void reconnect() {
        if (screen == null || cancelled || lastServer == null) return;
        DisconnectedScreen parent = screen;
        screen = null;
        statusWidget = null;
        Minecraft minecraft = Minecraft.getInstance();
        ConnectScreen.startConnecting(parent, minecraft, ServerAddress.parseString(lastServer.ip),
                lastServer, false, null);
    }

    private static void cancel() {
        cancelled = true;
        AfkFarmClient.instance().cancel("Reconexión y flujo AFK cancelados por el usuario");
        if (statusWidget != null) statusWidget.setMessage(Component.literal("Reconexión cancelada"));
    }

    private static void updateStatus() {
        if (statusWidget == null) return;
        long seconds = Math.max(0, (deadline - System.currentTimeMillis() + 999) / 1000);
        statusWidget.setMessage(Component.literal("Reconectando en " + formatDelay(seconds) + " · Intento " + attempt));
    }

    private static String formatDelay(long seconds) {
        return seconds >= 60 ? String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                : seconds + " segundos";
    }

}
