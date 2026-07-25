package com.ivbettercollisions;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.world.level.Level;

/**
 * The post-tick collision orchestrator.  After MTS has moved every vehicle for the tick, this runs the
 * three passes in order:
 * <ol>
 *   <li><b>Pending momentum</b> - applies and decays knockback/spin carried over from earlier impacts;</li>
 *   <li><b>Vehicle-to-vehicle</b> ({@link VehicleCollisionPass}) - box-set contact manifolds with a
 *       proper collision impulse (or the legacy sphere model behind {@code v2vMode});</li>
 *   <li><b>Walls</b> ({@link WallCollisionPass}) - MTV push-out with the GTA-style impact response;
 *       runs last so the wall constraint wins the tick.</li>
 * </ol>
 * This is a clean-room reimplementation inspired by the Forge "IV Better Collisions" addon, written
 * against IV's own public APIs (no reflection).  It only ever mutates a vehicle's position, motion and
 * orientation - it never touches blocks or the world - so it is safe to run on both the server (the
 * authority) and the client (prediction).
 * <p>
 * <b>Netcode:</b> every position/orientation correction is applied through
 * {@link minecrafttransportsimulator.entities.instances.AEntityVehicleD_Moving#applyExternalCollisionCorrection}
 * rather than by writing {@code position}/{@code orientation} directly.  MTS reconciles client and server by
 * comparing accumulated motion/rotation <em>deltas</em>, not absolute position; a correction applied outside
 * that channel becomes a permanent offset the rubberband can never heal (and, run independently on both
 * sides, makes them fight - the server hitbox lags behind in a wall while the client drives on).  Folding
 * the correction into the delta accumulators (and broadcasting it from the server) keeps the two sides in
 * lock-step, so walls feel solid with no rubberband snap-back.  Motion is left as a direct field write - it
 * already flows through the delta channel via {@code motionApplied} on the next tick.  Vehicles are
 * iterated in UUID order so both sides resolve pairs identically.
 */
public final class VehicleCollisionHandler {

    /**
     * Decaying knockback / spin injected by impacts, keyed by vehicle UUID.  Held once per physics
     * side: in single-player the integrated server and the client run on different threads and must not
     * share this mutable state.
     */
    static final class State {
        final Map<UUID, Point3D> knockback = new HashMap<>();
        final Map<UUID, Double> spin = new HashMap<>();
        /** Ticks remaining before a vehicle may receive another wall bounce/yaw (slide is never gated). */
        final Map<UUID, Integer> wallImpactCooldown = new HashMap<>();
    }

    private static final State SERVER_STATE = new State();
    private static final State CLIENT_STATE = new State();

    private static final double MIN_KNOCKBACK = 1.0e-3;
    private static final double MIN_SPIN = 1.0e-2;
    /** Cap on accumulated knockback to keep the legacy sphere response visually sane. */
    private static final double MAX_KNOCKBACK = 2.0;

    private VehicleCollisionHandler() {
    }

    /**
     * Runs one collision pass over every vehicle in the world.  Called from the END phase of the server
     * world tick and the client tick, after MTS has already moved all vehicles this tick.
     */
    public static void onWorldTickEnd(Level level, boolean isClient) {
        if (!CollisionConfig.enabled || level == null) {
            return;
        }
        List<EntityVehicleF_Physics> vehicles = MtsAccess.getVehicles(level);
        if (vehicles.isEmpty()) {
            return;
        }
        // Deterministic iteration: pair resolution is order-sensitive, and the entity list carries no
        // ordering guarantee - sort by UUID so client and server resolve identically.
        vehicles.sort((a, b) -> a.uniqueUUID.compareTo(b.uniqueUUID));
        State state = isClient ? CLIENT_STATE : SERVER_STATE;

        // Position shifts applied this tick, per vehicle.  Boxes only rebuild next tick, so the wall
        // pass offsets its block queries by these.
        Map<UUID, Point3D> appliedShift = new HashMap<>();

        // 0) Age the wall-impact cooldowns.
        state.wallImpactCooldown.values().removeIf(ticks -> ticks <= 1);
        state.wallImpactCooldown.replaceAll((uuid, ticks) -> ticks - 1);

        // 1) Apply and decay knockback / spin carried over from previous ticks' impacts.
        applyPendingMomentum(state, vehicles, appliedShift);

        // 2) Resolve vehicle-to-vehicle contacts.
        if (CollisionConfig.v2vEnabled && vehicles.size() > 1) {
            VehicleCollisionPass.resolve(state, vehicles, appliedShift);
        }

        // 3) Push each vehicle out of any solid blocks it is clipping into (horizontal only).  Last, so
        // the wall constraint wins over anything the v2v pass did.
        Point3D zeroShift = new Point3D();
        for (EntityVehicleF_Physics vehicle : vehicles) {
            Point3D shift = appliedShift.get(vehicle.uniqueUUID);
            WallCollisionPass.correct(level, vehicle, state, shift != null ? shift : zeroShift);
        }
    }

    // ---- knockback / spin bookkeeping -------------------------------------------------------------

    private static void applyPendingMomentum(State state, List<EntityVehicleF_Physics> vehicles, Map<UUID, Point3D> appliedShift) {
        if (state.knockback.isEmpty() && state.spin.isEmpty()) {
            return;
        }
        Map<UUID, EntityVehicleF_Physics> byUUID = new HashMap<>();
        for (EntityVehicleF_Physics vehicle : vehicles) {
            byUUID.put(vehicle.uniqueUUID, vehicle);
        }
        double keep = 1.0 - CollisionConfig.v2vFrictionFactor;

        Iterator<Map.Entry<UUID, Point3D>> kIt = state.knockback.entrySet().iterator();
        while (kIt.hasNext()) {
            Map.Entry<UUID, Point3D> entry = kIt.next();
            EntityVehicleF_Physics vehicle = byUUID.get(entry.getKey());
            if (vehicle == null) {
                kIt.remove();
                continue;
            }
            Point3D knock = entry.getValue();
            vehicle.applyExternalCollisionCorrection(knock, 0);
            appliedShift.computeIfAbsent(vehicle.uniqueUUID, k -> new Point3D()).add(knock);
            knock.scale(keep);
            if (knock.length() < MIN_KNOCKBACK) {
                kIt.remove();
            }
        }

        Iterator<Map.Entry<UUID, Double>> sIt = state.spin.entrySet().iterator();
        while (sIt.hasNext()) {
            Map.Entry<UUID, Double> entry = sIt.next();
            EntityVehicleF_Physics vehicle = byUUID.get(entry.getKey());
            if (vehicle == null) {
                sIt.remove();
                continue;
            }
            double spin = entry.getValue();
            double applied = CollisionMath.clamp(spin, -CollisionConfig.v2vMaxSpinPerTick, CollisionConfig.v2vMaxSpinPerTick);
            vehicle.applyExternalCollisionCorrection(null, applied);
            // Drain the applied part, decay the rest, so the total is roughly the impact's rotation but
            // never lands more than the per-tick cap in a single tick.
            double remaining = (spin - applied) * keep;
            if (Math.abs(remaining) < MIN_SPIN) {
                sIt.remove();
            } else {
                entry.setValue(remaining);
            }
        }
    }

    static void addKnockback(State state, UUID uuid, Point3D direction, double magnitude) {
        Point3D knock = state.knockback.computeIfAbsent(uuid, k -> new Point3D());
        knock.addScaled(direction, magnitude);
        double length = knock.length();
        if (length > MAX_KNOCKBACK) {
            knock.scale(MAX_KNOCKBACK / length);
        }
    }

    /** True when this vehicle may take another wall bounce/yaw (the cooldown never gates slide or push-out). */
    static boolean canTakeWallImpact(State state, UUID uuid) {
        return !state.wallImpactCooldown.containsKey(uuid);
    }

    static void startWallImpactCooldown(State state, UUID uuid) {
        if (CollisionConfig.wallImpactCooldownTicks > 0) {
            state.wallImpactCooldown.put(uuid, CollisionConfig.wallImpactCooldownTicks);
        }
    }

    static void addSpin(State state, UUID uuid, double degrees) {
        double total = CollisionMath.clamp(state.spin.getOrDefault(uuid, 0.0) + degrees,
                -CollisionConfig.v2vMaxTotalSpin, CollisionConfig.v2vMaxTotalSpin);
        state.spin.put(uuid, total);
    }

    // ---- shared guards ----------------------------------------------------------------------------

    /**
     * A vehicle takes part in collision correction only once it is an established, complete ground
     * vehicle: past the spawn-settling window AND with a ready ground-device set (front+rear plus a
     * side, per MTS's own {@link minecrafttransportsimulator.baseclasses.VehicleGroundDeviceCollection#isReady()}).
     * A freshly-placed frame still missing its wheels is NOT ready; without ground devices it can't be
     * held up, so shoving it every tick - into walls or off other vehicles - makes it thrash violently
     * (GMod-style) and drag whatever it overlaps along with it.  We leave such vehicles alone until
     * they're built.  {@code isReady()} counts liquid-collision boxes too, so boats/complete vehicles
     * are unaffected; only genuinely unfinished ones are skipped.
     */
    static boolean isCollidable(EntityVehicleF_Physics vehicle) {
        return vehicle.ticksExisted >= CollisionConfig.minTickAge && vehicle.groundDeviceCollective.isReady();
    }

    static boolean shareTowChain(EntityVehicleF_Physics a, EntityVehicleF_Physics b) {
        return towRoot(a) == towRoot(b);
    }

    /** Walks up the tow chain to the root (untowed) vehicle, so a whole train shares one root. */
    private static EntityVehicleF_Physics towRoot(EntityVehicleF_Physics vehicle) {
        EntityVehicleF_Physics current = vehicle;
        int guard = 0;
        while (current.towedByConnection != null && current.towedByConnection.towingVehicle != null && guard++ < 32) {
            current = current.towedByConnection.towingVehicle;
        }
        return current;
    }
}
