package pl.peterwolf.vote;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VoteConfig {
    private static final Gson GSON = new Gson();
    public String apiBaseUrl = "http://127.0.0.1:3000";
    public String apiKey = "REPLACE_ME";
    public String serverId = "default";
    public int timeoutSeconds = 6;
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("vote-in-minecraft.json");

    public static VoteConfig load() {
        try {
            if (!Files.exists(PATH)) {
                Files.createDirectories(PATH.getParent());
                Files.writeString(PATH, GSON.toJson(new VoteConfig()) + "\n", StandardCharsets.UTF_8);
            }
            VoteConfig config = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), VoteConfig.class);
            if (config == null || config.apiBaseUrl == null || config.apiKey == null || config.serverId == null)
                throw new IllegalArgumentException("Missing config fields");
            if (!config.apiBaseUrl.startsWith("https://") &&
                !config.apiBaseUrl.startsWith("http://127.0.0.1:") &&
                !config.apiBaseUrl.startsWith("http://localhost:"))
                throw new IllegalArgumentException("API requires HTTPS outside localhost");
            if (config.timeoutSeconds < 1 || config.timeoutSeconds > 30)
                throw new IllegalArgumentException("timeoutSeconds must be 1-30");
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load config/vote-in-minecraft.json", e);
        }
    }
}
