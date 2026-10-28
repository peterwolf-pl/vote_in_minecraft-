package pl.peterwolf.vote;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class VoteApi {
    private static final Gson GSON = new Gson();
    private final VoteConfig config;
    private final HttpClient http;

    public VoteApi(VoteConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private URI uri(String path) {
        return URI.create(config.apiBaseUrl.replaceAll("/+$", "") + path);
    }
    public CompletableFuture<JsonArray> activeBallots() {
        HttpRequest req = HttpRequest.newBuilder(uri("/api/v1/ballots/active"))
            .timeout(Duration.ofSeconds(config.timeoutSeconds)).GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200)
                throw new IllegalStateException("API HTTP " + response.statusCode());
            return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("ballots");
        });
    }
    public CompletableFuture<String> submit(String ballotId, String optionId, String uuid,
                                             String playerName, boolean consent, Integer appealId) {
        JsonObject data = new JsonObject();
        data.addProperty("playerUuid", uuid);
        data.addProperty("playerName", playerName);
        data.addProperty("serverId", config.serverId);
        if (optionId != null) data.addProperty("optionId", optionId);
        if (consent) {
            data.addProperty("consentToPublishName", true);
            data.addProperty("consentPrivacy", true);
        }
        if (appealId != null) data.addProperty("appealId", appealId);
        HttpRequest req = HttpRequest.newBuilder(uri("/api/v1/ballots/" + ballotId + "/submissions"))
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .header("Authorization", "Bearer " + config.apiKey)
            .header("X-Minecraft-Server", config.serverId)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(data))).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 201) return "Zapisano głos/podpis. Dziękujemy!";
            if (response.statusCode() == 409) return "Oddałeś już głos albo głosowanie jest zamknięte.";
            if (response.statusCode() == 401) return "Błąd autoryzacji API.";
            return "API odrzuciło zgłoszenie (HTTP " + response.statusCode() + ").";
        });
    }
    public static String string(JsonObject obj, String key) {
        JsonElement val = obj.get(key);
        return val == null || val.isJsonNull() ? "" : val.getAsString();
    }
}
