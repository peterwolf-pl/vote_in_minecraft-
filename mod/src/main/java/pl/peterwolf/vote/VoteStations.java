package pl.peterwolf.vote;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

final class VoteStations {
    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("vote-stations.json");
    private final Set<String> positions = new HashSet<>();

    VoteStations() {
        try {
            if (Files.exists(file)) {
                var data = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
                for (var e : data) positions.add(e.getAsString());
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot read voting stations", e); }
    }

    boolean contains(String dimension, BlockPos pos) { return positions.contains(key(dimension, pos)); }
    boolean add(String dimension, BlockPos pos) {
        if (!positions.add(key(dimension, pos))) return false;
        save(); return true;
    }
    boolean remove(String dimension, BlockPos pos) {
        if (!positions.remove(key(dimension, pos))) return false;
        save(); return true;
    }

    private static String key(String dimension, BlockPos pos) {
        return dimension + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "vote-stations", ".tmp");
            Files.writeString(temp, new Gson().toJson(positions), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new IllegalStateException("Cannot save voting stations", e); }
    }
}
