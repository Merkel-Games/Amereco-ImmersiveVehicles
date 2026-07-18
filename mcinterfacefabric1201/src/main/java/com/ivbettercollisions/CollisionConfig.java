package com.ivbettercollisions;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Configuration for the Better Collisions system, persisted as {@code config/ivbettercollisions.json}.
 * <p>
 * This mirrors the option set (and default values) of the original Forge "IV Better Collisions" addon,
 * but is stored as JSON rather than TOML so we don't pull in a new dependency (Gson is already on the
 * classpath).  All options are exposed as {@code static} fields so the hot per-tick collision code can
 * read them without map lookups.
 *
 * @see VehicleCollisionHandler
 */
public final class CollisionConfig {
    /** Master switch for the whole collision system. */
    public static boolean enabled = true;

    /** Extra push-out distance applied beyond exact contact when ejecting a vehicle from a block (blocks). */
    public static double epsilon = 0.1;
    /** Maximum penetration depth that will be corrected (blocks).  Deeper embeds are left alone. */
    public static double maxCorrection = 2.0;
    /** Number of wall-correction passes per tick (handles corners / stacked blocks). */
    public static int maxPasses = 5;
    /** Extra margin added around each BLOCK hitbox on the horizontal axes to catch high-speed clipping (blocks). */
    public static double wallBoxMargin = 0.54;

    /** Minimum vehicle age (ticks) before corrections apply - avoids fighting the MTS spawn/placement system. */
    public static int minTickAge = 40;

    /** Enable vehicle-to-vehicle collision. */
    public static boolean v2vEnabled = true;
    /** Collision sphere radius around each vehicle centre (blocks). */
    public static double v2vSphereRadius = 3.0;
    /** Friction applied to collision-induced knockback / spin each tick (0-1).  Higher = faster settle. */
    public static double v2vFrictionFactor = 0.15;
    /** Minimum gap kept after separation (blocks) - prevents immediate re-collision next tick. */
    public static double v2vSeparationEpsilon = 0.02;
    /** Spin intensity on an off-centre impact (degrees per unit of tangential velocity).  0 disables spin. */
    public static double v2vSpinFactor = 600.0;
    /** Maximum distance between vehicle centres to bother checking for a collision (blocks). */
    public static double v2vBroadPhaseRadius = 30.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "ivbettercollisions.json";

    private CollisionConfig() {
    }

    /**
     * Plain data-transfer object used purely for (de)serialisation, with the JSON key names and the
     * canonical defaults.  Kept separate from the live static fields so a malformed/partial file falls
     * back cleanly to defaults for any missing key.
     */
    private static final class Data {
        boolean enabled = true;
        double epsilon = 0.1;
        double maxCorrection = 2.0;
        int maxPasses = 5;
        double wallBoxMargin = 0.54;
        int minTickAge = 40;
        boolean v2vEnabled = true;
        double v2vSphereRadius = 3.0;
        double v2vFrictionFactor = 0.15;
        double v2vSeparationEpsilon = 0.02;
        double v2vSpinFactor = 600.0;
        double v2vBroadPhaseRadius = 30.0;
    }

    /**
     * Loads the config from {@code <configDir>/ivbettercollisions.json}, creating it with defaults if it
     * does not exist, and copies the values into the live static fields.  Never throws - on any error it
     * logs and keeps the built-in defaults so a bad config can never stop the mod loading.
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
                IVBetterCollisions.LOGGER.info("[IVBC] Wrote default config to {}", file);
            }
        } catch (Exception e) {
            IVBetterCollisions.LOGGER.error("[IVBC] Failed to load config, using defaults", e);
        }

        enabled = data.enabled;
        epsilon = data.epsilon;
        maxCorrection = data.maxCorrection;
        maxPasses = Math.max(1, data.maxPasses);
        wallBoxMargin = Math.max(0, data.wallBoxMargin);
        minTickAge = Math.max(0, data.minTickAge);
        v2vEnabled = data.v2vEnabled;
        v2vSphereRadius = Math.max(0, data.v2vSphereRadius);
        v2vFrictionFactor = Math.min(1, Math.max(0, data.v2vFrictionFactor));
        v2vSeparationEpsilon = Math.max(0, data.v2vSeparationEpsilon);
        v2vSpinFactor = data.v2vSpinFactor;
        v2vBroadPhaseRadius = Math.max(0, data.v2vBroadPhaseRadius);
    }
}
