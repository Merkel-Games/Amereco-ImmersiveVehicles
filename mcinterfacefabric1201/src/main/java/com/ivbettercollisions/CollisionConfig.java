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
 * Schema v2 ("Ultimate Collisions"): adds the wall impact model (restitution / friction / yaw) and the
 * box-set vehicle-to-vehicle impulse model on top of the original v1 options.  Migration is automatic:
 * a v1 file (no {@code configVersion} key) is parsed with every v1 key keeping its old meaning, the new
 * keys fall back to their defaults, and the file is written back in the full v2 schema.  All options are
 * exposed as {@code static} fields so the hot per-tick collision code can read them without map lookups.
 *
 * @see VehicleCollisionHandler
 */
public final class CollisionConfig {
    /** Current schema version written to new/migrated files. */
    public static final int CURRENT_VERSION = 2;

    /** Master switch for the whole collision system. */
    public static boolean enabled = true;

    // ---- wall pass (v1 core) ----------------------------------------------------------------------
    /** Extra push-out distance applied beyond exact contact when ejecting a vehicle from a block (blocks). */
    public static double epsilon = 0.1;
    /** Maximum penetration depth that will be corrected (blocks).  Deeper embeds are left alone. */
    public static double maxCorrection = 2.0;
    /** Number of wall-correction passes per tick (handles corners / stacked blocks). */
    public static int maxPasses = 5;
    /** Base extra margin added around each BLOCK hitbox on the horizontal axes (blocks). */
    public static double wallBoxMargin = 0.54;
    /** Minimum vehicle age (ticks) before corrections apply - avoids fighting the MTS spawn/placement system. */
    public static int minTickAge = 40;

    // ---- wall impact model (v2) -------------------------------------------------------------------
    /** Coefficient of restitution for wall impacts (0 = dead stop, ~0.2 = small GTA-style rebound). */
    public static double wallRestitution = 0.2;
    /** Fraction of tangential (along-wall) speed lost per impact tick (scraping friction). */
    public static double wallFriction = 0.25;
    /** Scale wall friction by 0.6/blockSlipperiness so ice (0.98) scrubs less speed than stone (0.6). */
    public static boolean wallFrictionUseSlipperiness = true;
    /** Multiplier on the physically-derived yaw impulse from off-centre wall hits.  Negative flips handedness. */
    public static double wallYawFactor = 1.0;
    /** Cap (degrees) on the yaw fed into the spin state from a single wall impact. */
    public static double wallYawMaxPerImpact = 25.0;
    /** Approach speed (blocks/tick) below which a wall contact is "rest": pure slide, no bounce or yaw. */
    public static double wallMinImpactSpeed = 0.02;
    /** Extra detection margin per block of per-tick displacement (catches high-speed clipping). */
    public static double wallMarginSpeedScale = 0.5;
    /** Cap (blocks) on the speed-adaptive part of the wall margin. */
    public static double wallMarginSpeedMax = 1.0;

    // ---- vehicle-to-vehicle -----------------------------------------------------------------------
    /** Enable vehicle-to-vehicle collision. */
    public static boolean v2vEnabled = true;
    /** Collision model: "box" = box-set manifold + impulse (v2), "sphere" = legacy v1 model. */
    public static String v2vMode = "box";
    /** Coefficient of restitution for vehicle-to-vehicle impacts. */
    public static double v2vRestitution = 0.25;
    /** Closing speed (blocks/tick) below which a v2v contact is "rest" (no restitution). */
    public static double v2vRestSpeed = 0.03;
    /** Allowed resting overlap (blocks) before positional correction kicks in - parked contact is jitter-free. */
    public static double v2vPenetrationSlop = 0.02;
    /** Fraction of the remaining penetration corrected per tick (Baumgarte-style relaxation). */
    public static double v2vCorrectionPercent = 0.8;
    /** Cap on the velocity change (blocks/tick) either vehicle can receive from a single impact. */
    public static double v2vMaxImpulse = 1.5;
    /** Multiplier on the physically-derived v2v yaw impulses.  Negative flips handedness. */
    public static double v2vYawFactor = 1.0;
    /** Cap (degrees) on the yaw fed into the spin state per vehicle from a single v2v impact. */
    public static double v2vYawMaxPerImpact = 30.0;
    /** Maximum spin (degrees) actually applied to a vehicle in one tick. */
    public static double v2vMaxSpinPerTick = 15.0;
    /** Cap on the total accumulated pending spin (degrees) per vehicle. */
    public static double v2vMaxTotalSpin = 360.0;
    /** Friction applied to collision-induced knockback / spin each tick (0-1).  Higher = faster settle. */
    public static double v2vFrictionFactor = 0.15;
    /** Maximum distance between vehicle centres to bother checking for a collision (blocks). */
    public static double v2vBroadPhaseRadius = 30.0;
    /** Inflation (blocks) of each vehicle's union hitbox AABB in the box broad phase. */
    public static double v2vBroadPhaseMargin = 0.5;

    // ---- legacy sphere mode only ------------------------------------------------------------------
    /** (sphere mode) Collision sphere radius around each vehicle centre (blocks). */
    public static double v2vSphereRadius = 3.0;
    /** (sphere mode) Minimum gap kept after separation (blocks). */
    public static double v2vSeparationEpsilon = 0.02;
    /** (sphere mode) Spin intensity on an off-centre impact (degrees per unit of tangential velocity). */
    public static double v2vSpinFactor = 600.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "ivbettercollisions.json";

    private CollisionConfig() {
    }

    /**
     * Plain data-transfer object used purely for (de)serialisation, with the JSON key names and the
     * canonical defaults.  Kept separate from the live static fields so a malformed/partial file falls
     * back cleanly to defaults for any missing key - which is also what makes v1-&gt;v2 migration free:
     * a v1 file simply lacks the new keys, so they deserialize to these defaults.
     */
    private static final class Data {
        // Deliberately 0, NOT CURRENT_VERSION: Gson runs field initializers (Data has a default
        // constructor), so a v1 file with no configVersion key must deserialize to a value that is
        // detectably old - otherwise migration never triggers.  Set explicitly before every write.
        int configVersion = 0;
        boolean enabled = true;

        double epsilon = 0.1;
        double maxCorrection = 2.0;
        int maxPasses = 5;
        double wallBoxMargin = 0.54;
        int minTickAge = 40;

        double wallRestitution = 0.2;
        double wallFriction = 0.25;
        boolean wallFrictionUseSlipperiness = true;
        double wallYawFactor = 1.0;
        double wallYawMaxPerImpact = 25.0;
        double wallMinImpactSpeed = 0.02;
        double wallMarginSpeedScale = 0.5;
        double wallMarginSpeedMax = 1.0;

        boolean v2vEnabled = true;
        String v2vMode = "box";
        double v2vRestitution = 0.25;
        double v2vRestSpeed = 0.03;
        double v2vPenetrationSlop = 0.02;
        double v2vCorrectionPercent = 0.8;
        double v2vMaxImpulse = 1.5;
        double v2vYawFactor = 1.0;
        double v2vYawMaxPerImpact = 30.0;
        double v2vMaxSpinPerTick = 15.0;
        double v2vMaxTotalSpin = 360.0;
        double v2vFrictionFactor = 0.15;
        double v2vBroadPhaseRadius = 30.0;
        double v2vBroadPhaseMargin = 0.5;

        double v2vSphereRadius = 3.0;
        double v2vSeparationEpsilon = 0.02;
        double v2vSpinFactor = 600.0;
    }

    /**
     * Loads the config from {@code <configDir>/ivbettercollisions.json}, creating it with defaults if it
     * does not exist and rewriting it in the v2 schema if it is an older version.  Never throws - on any
     * error it logs and keeps the built-in defaults so a bad config can never stop the mod loading.
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
                if (data.configVersion < CURRENT_VERSION) {
                    // v1 keys survive with their old values; new keys are at defaults.  Persist the
                    // merged result so the file on disk gains the full v2 schema.
                    data.configVersion = CURRENT_VERSION;
                    try (Writer writer = Files.newBufferedWriter(file)) {
                        GSON.toJson(data, writer);
                    }
                    IVBetterCollisions.LOGGER.info("[IVBC] Migrated config to schema v{} at {}", CURRENT_VERSION, file);
                }
            } else {
                Files.createDirectories(configDir);
                data.configVersion = CURRENT_VERSION;
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

        wallRestitution = Math.min(1, Math.max(0, data.wallRestitution));
        wallFriction = Math.min(1, Math.max(0, data.wallFriction));
        wallFrictionUseSlipperiness = data.wallFrictionUseSlipperiness;
        wallYawFactor = data.wallYawFactor;
        wallYawMaxPerImpact = Math.max(0, data.wallYawMaxPerImpact);
        wallMinImpactSpeed = Math.max(0, data.wallMinImpactSpeed);
        wallMarginSpeedScale = Math.max(0, data.wallMarginSpeedScale);
        wallMarginSpeedMax = Math.max(0, data.wallMarginSpeedMax);

        v2vEnabled = data.v2vEnabled;
        v2vMode = "sphere".equalsIgnoreCase(data.v2vMode) ? "sphere" : "box";
        v2vRestitution = Math.min(1, Math.max(0, data.v2vRestitution));
        v2vRestSpeed = Math.max(0, data.v2vRestSpeed);
        v2vPenetrationSlop = Math.max(0, data.v2vPenetrationSlop);
        v2vCorrectionPercent = Math.min(1, Math.max(0, data.v2vCorrectionPercent));
        v2vMaxImpulse = Math.max(0, data.v2vMaxImpulse);
        v2vYawFactor = data.v2vYawFactor;
        v2vYawMaxPerImpact = Math.max(0, data.v2vYawMaxPerImpact);
        v2vMaxSpinPerTick = Math.max(0, data.v2vMaxSpinPerTick);
        v2vMaxTotalSpin = Math.max(0, data.v2vMaxTotalSpin);
        v2vFrictionFactor = Math.min(1, Math.max(0, data.v2vFrictionFactor));
        v2vBroadPhaseRadius = Math.max(0, data.v2vBroadPhaseRadius);
        v2vBroadPhaseMargin = Math.max(0, data.v2vBroadPhaseMargin);

        v2vSphereRadius = Math.max(0, data.v2vSphereRadius);
        v2vSeparationEpsilon = Math.max(0, data.v2vSeparationEpsilon);
        v2vSpinFactor = data.v2vSpinFactor;
    }
}
