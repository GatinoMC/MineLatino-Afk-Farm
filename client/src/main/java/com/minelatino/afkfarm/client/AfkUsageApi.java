package com.minelatino.afkfarm.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Restricted AFK usage API. Player-supplied durations are never accepted. */
public final class AfkUsageApi {
    public record Usage(long remainingSeconds, boolean allowed, boolean active, boolean exhausted, String sessionId) {}
    public static final class Failure extends RuntimeException {
        private final int status;
        Failure(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
    private record Credential(String token, long expiresAt, String parentToken) {}

    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile Credential credential;

    public CompletableFuture<Usage> status(CosmeticsSessionBridge.LinkedSession session) {
        return request(session, "GET", "/v1/afk/status", null, true);
    }
    public CompletableFuture<Usage> start(CosmeticsSessionBridge.LinkedSession session) {
        return request(session, "POST", "/v1/afk/sessions", "{}", true);
    }
    public CompletableFuture<Usage> heartbeat(CosmeticsSessionBridge.LinkedSession session, String id) {
        return request(session, "POST", "/v1/afk/sessions/" + id + "/heartbeat", "{}", true);
    }
    public CompletableFuture<Usage> stop(CosmeticsSessionBridge.LinkedSession session, String id) {
        return request(session, "POST", "/v1/afk/sessions/" + id + "/stop", "{}", true);
    }

    private CompletableFuture<Usage> request(CosmeticsSessionBridge.LinkedSession session, String method,
                                              String path, String body, boolean retry) {
        return credential(session).thenCompose(token -> send(session.apiBaseUrl() + path, method, body, token))
                .thenCompose(response -> {
                    if (response.statusCode() == 401 && retry) {
                        credential = null;
                        return request(session, method, path, body, false);
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) throw failure(response);
                    JsonObject json = parse(response.body());
                    return CompletableFuture.completedFuture(new Usage(
                            json.has("remainingSeconds") ? Math.max(0, json.get("remainingSeconds").getAsLong()) : 0,
                            json.has("allowed") && json.get("allowed").getAsBoolean(),
                            json.has("active") && json.get("active").getAsBoolean(),
                            json.has("exhausted") && json.get("exhausted").getAsBoolean(),
                            json.has("sessionId") && !json.get("sessionId").isJsonNull() ? json.get("sessionId").getAsString() : null));
                });
    }

    private CompletableFuture<String> credential(CosmeticsSessionBridge.LinkedSession session) {
        Credential current = credential;
        if (current != null && Objects.equals(current.parentToken(), session.token())
                && current.expiresAt() - System.currentTimeMillis() > 120_000)
            return CompletableFuture.completedFuture(current.token());
        return send(session.apiBaseUrl() + "/v1/afk/token", "POST", "{}", session.token()).thenApply(response -> {
            if (response.statusCode() != 201) throw failure(response);
            JsonObject json = parse(response.body());
            String token = json.get("token").getAsString();
            long expiresAt = json.get("expiresAt").getAsLong();
            if (token.length() < 32 || expiresAt <= System.currentTimeMillis()) throw new Failure(502, "Permiso AFK inválido");
            credential = new Credential(token, expiresAt, session.token());
            return token;
        });
    }

    private CompletableFuture<HttpResponse<String>> send(String url, String method, String body, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .header("X-MineLatino-Client", "afk-farm/0.1");
        if ("POST".equals(method)) builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        else builder.GET();
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static JsonObject parse(String value) {
        try { JsonObject json = GSON.fromJson(value, JsonObject.class); if (json == null) throw new IllegalArgumentException(); return json; }
        catch (RuntimeException error) { throw new Failure(502, "Respuesta de tiempo inválida"); }
    }
    private static Failure failure(HttpResponse<String> response) {
        String message = switch (response.statusCode()) {
            case 401, 403 -> "Vuelve a vincular tu cuenta MineLatino";
            case 402 -> "No tienes tiempo disponible para AFK Farm";
            case 429 -> "Demasiadas solicitudes; espera un momento";
            default -> "No se pudo comprobar el tiempo de AFK Farm";
        };
        try { JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            if (json != null && json.has("error") && json.get("error").getAsString().length() <= 180) message = json.get("error").getAsString();
        } catch (RuntimeException ignored) {}
        return new Failure(response.statusCode(), message);
    }
}
