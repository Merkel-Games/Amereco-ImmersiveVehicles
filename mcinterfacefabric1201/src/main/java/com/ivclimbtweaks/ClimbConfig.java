package com.ivclimbtweaks;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Configuration for the climb-height tweaks, persisted as {@code config/ivclimbtweaks.json}.
 * <p>
 * {@code climbHeights} maps a content-pack part's systemName (the JSON filename without extension) to the
 * {@code ground.climbHeight} value (in blocks) it should be forced to. Only parts whose systemName appears
 * in the map are touched; everything else keeps its pack-defined value. Stored as JSON so the values can be
 * tuned on a server without recompiling.
 *
 * @see ClimbHeightOverrider
 */
public final class ClimbConfig {
    /** Master switch for the whole tweak. */
    public static boolean enabled = true;
    /** The content pack whose parts get patched (default: the Official Content Pack). */
    public static String packID = "mtsofficialpack";
    /** systemName -> forced ground.climbHeight (blocks). */
    public static Map<String, Double> climbHeights = defaults();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "ivclimbtweaks.json";

    private ClimbConfig() {
    }

    private static LinkedHashMap<String, Double> defaults() {
        LinkedHashMap<String, Double> m = new LinkedHashMap<>();
        m.put("wheelhuge", 1.5);
        m.put("wheellarge", 1.0);
        m.put("wheelmedium", 1.0);
        m.put("wheelsmall", 0.5);
        return m;
    }

    /** DTO used only for (de)serialisation, carrying the canonical defaults. */
    private static final class Data {
        boolean enabled = true;
        String packID = "mtsofficialpack";
        LinkedHashMap<String, Double> climbHeights = defaults();
    }

    /**
     * Loads the config from {@code <configDir>/ivclimbtweaks.json}, creating it with defaults if absent,
     * and copies the values into the live static fields. Never throws - on any error it logs and keeps the
     * built-in defaults so a bad config can never stop the mod loading.
     */
    public static void load(Path configDir) {
        Data data = new Data();
        Path file = configDir.resolve(FILE_NAME);
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    Data parsed = GSON.fromJson(reader, Data.class);
                    if (parsed != null) {
                        data = parsed;
                    }
                }
            } else {
                Files.createDirectories(configDir);
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(data, writer);
                }
                IVClimbTweaks.LOGGER.info("[IVClimb] Wrote default config to {}", file);
            }
        } catch (Exception e) {
            IVClimbTweaks.LOGGER.error("[IVClimb] Failed to load config, using defaults", e);
        }

        enabled = data.enabled;
        packID = (data.packID != null && !data.packID.isEmpty()) ? data.packID : "mtsofficialpack";
        climbHeights = (data.climbHeights != null) ? data.climbHeights : defaults();
    }
}
