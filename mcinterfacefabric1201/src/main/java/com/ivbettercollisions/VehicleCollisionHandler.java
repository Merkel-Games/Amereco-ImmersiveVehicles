package com.ivbettercollisions;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.world.level.Level;

/**
 * The post-tick collision safety net.  After MTS has moved every vehicle for the tick, this runs once
 * more and:
 * <ul>
 *   <li><b>Wall push-out</b> - inflates each vehicle BLOCK hitbox by a margin, finds any solid block it
 *       is penetrating, and pushes it out along the horizontal axis of least penetration (a per-axis
 *       Minimum-Translation-Vector).  Only the motion component driving into the wall is cancelled, so
 *       vehicles slide along walls instead of clipping through.  Vertical collisions are left to MTS's
 *       ground-device system.</li>
 *   <li><b>Vehicle-to-vehicle</b> - a sphere broad-phase, then a mass-weighted positional push-apart plus
 *       a decaying knockback impulse and an off-centre-impact spin.  Tow-chained vehicles are skipped.</li>
 * </ul>
 * This is a clean-room reimplementation of the technique used by the Forge "IV Better Collisions" addon,
 * written against IV's own public APIs (no reflection).  It only ever mutates a vehicle's position, motion
 * and orientation - it never touches blocks or the world - so it is safe to run on both the server (the
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
 * already flows through the delta channel via {@code motionApplied} on the next tick.
 */
public final class VehicleCollisionHandler {

    /**
     * Decaying knockback / spin injected by vehicle-to-vehicle impacts, keyed by vehicle UUID.  Held once
     * per physics side: in single-player the integrated server and the client run on different threads and
     * must not share this mutable state.
     */
    private static final class State {
        final Map<UUID, Point3D> knockback = new HashMap<>();
        final Map<UUID, Double> spin = new HashMap<>();
    }

    private static final State SERVER_STATE = new State();
    private static final State CLIENT_STATE = new State();

    private static final double EPSILON = 1.0e-4;
    private static final double MIN_KNOCKBACK = 1.0e-3;
    private static final double MIN_SPIN = 1.0e-2;
    /** Caps to keep vehicle-to-vehicle response visually sane and prevent launches / teleport-spins. */
    private static final double MAX_KNOCKBACK = 2.0;
    private static final double MAX_TOTAL_SPIN = 720.0;
    private static final double MAX_SPIN_PER_TICK = 20.0;

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
        State state = isClient ? CLIENT_STATE : SERVER_STATE;

        // 1) Apply and decay knockback / spin carried over from previous ticks' impacts.
        applyPendingMomentum(state, vehicles);

        // 2) Push each vehicle out of any solid blocks it is clipping into (horizontal only).
        for (EntityVehicleF_Physics vehicle : vehicles) {
            correctWallCollisions(level, vehicle);
        }

        // 3) Resolve vehicle-to-vehicle overlaps.
        if (CollisionConfig.v2vEnabled && vehicles.size() > 1) {
            resolveVehicleCollisions(state, vehicles);
        }
    }

    // ---- knockback / spin bookkeeping -------------------------------------------------------------

    private static void applyPendingMomentum(State state, List<EntityVehicleF_Physics> vehicles) {
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
            double applied = clamp(spin, -MAX_SPIN_PER_TICK, MAX_SPIN_PER_TICK);
            vehicle.applyExternalCollisionCorrection(null, applied);
            // Drain the applied part, decay the rest, so v2vSpinFactor is roughly the total rotation but
            // never lands more than MAX_SPIN_PER_TICK in a single tick.
            double remaining = (spin - applied) * keep;
            if (Math.abs(remaining) < MIN_SPIN) {
                sIt.remove();
            } else {
                entry.setValue(remaining);
            }
        }
    }

    private static void addKnockback(State state, UUID uuid, Point3D direction, double magnitude) {
        Point3D knock = state.knockback.computeIfAbsent(uuid, k -> new Point3D());
        knock.addScaled(direction, magnitude);
        double length = knock.length();
        if (length > MAX_KNOCKBACK) {
            knock.scale(MAX_KNOCKBACK / length);
        }
    }

    private static void addSpin(State state, UUID uuid, double degrees) {
        double total = clamp(state.spin.getOrDefault(uuid, 0.0) + degrees, -MAX_TOTAL_SPIN, MAX_TOTAL_SPIN);
        state.spin.put(uuid, total);
    }

    // ---- wall push-out ----------------------------------------------------------------------------

    private static void correctWallCollisions(Level level, EntityVehicleF_Physics vehicle) {
        if (ConfigSystem.settings.general.noclipVehicles.value) {
            return;
        }
        if (!isCollidable(vehicle) || vehicle.allBlockCollisionBoxes.isEmpty()) {
            return;
        }
        double margin = CollisionConfig.wallBoxMargin;
        double maxCorrection = CollisionConfig.maxCorrection;
        double totalX = 0;
        double totalZ = 0;

        for (int pass = 0; pass < CollisionConfig.maxPasses; pass++) {
            double passX = 0;
            double passZ = 0;
            boolean collided = false;

            for (BoundingBox box : vehicle.allBlockCollisionBoxes) {
                double halfX = box.widthRadius + margin;
                double halfY = box.heightRadius;   // vertical is left to MTS's ground-device system
                double halfZ = box.depthRadius + margin;
                // Query at the box's current centre plus whatever we've already corrected this tick (the
                // vehicle's boxes are only rebuilt next tick, so we offset the query rather than mutate them).
                double cx = box.globalCenter.x + totalX;
                double cy = box.globalCenter.y;
                double cz = box.globalCenter.z + totalZ;

                List<double[]> blocks = MtsAccess.getSolidBlockCollisions(level, cx, cy, cz, halfX, halfY, halfZ);
                if (blocks.isEmpty()) {
                    continue;
                }
                double boxMinX = cx - halfX, boxMaxX = cx + halfX;
                double boxMinY = cy - halfY, boxMaxY = cy + halfY;
                double boxMinZ = cz - halfZ, boxMaxZ = cz + halfZ;

                for (double[] b : blocks) {
                    double overlapX = Math.min(boxMaxX, b[3]) - Math.max(boxMinX, b[0]);
                    double overlapY = Math.min(boxMaxY, b[4]) - Math.max(boxMinY, b[1]);
                    double overlapZ = Math.min(boxMaxZ, b[5]) - Math.max(boxMinZ, b[2]);
                    if (overlapX <= 0 || overlapY <= 0 || overlapZ <= 0) {
                        continue;   // not actually overlapping in 3D
                    }
                    // Resolve on the horizontal axis of least penetration (Minimum Translation Vector).
                    if (overlapX <= overlapZ) {
                        if (overlapX > maxCorrection) {
                            continue;   // intentionally embedded - leave it be
                        }
                        double push = overlapX + CollisionConfig.epsilon;
                        double blockCenterX = (b[0] + b[3]) * 0.5;
                        if (cx < blockCenterX) {
                            push = -push;
                        }
                        if (Math.abs(push) > Math.abs(passX)) {
                            passX = push;
                        }
                        collided = true;
                    } else {
                        if (overlapZ > maxCorrection) {
                            continue;
                        }
                        double push = overlapZ + CollisionConfig.epsilon;
                        double blockCenterZ = (b[2] + b[5]) * 0.5;
                        if (cz < blockCenterZ) {
                            push = -push;
                        }
                        if (Math.abs(push) > Math.abs(passZ)) {
                            passZ = push;
                        }
                        collided = true;
                    }
                }
            }

            if (!collided) {
                break;
            }
            totalX = clamp(totalX + passX, -maxCorrection, maxCorrection);
            totalZ = clamp(totalZ + passZ, -maxCorrection, maxCorrection);
        }

        if (totalX == 0 && totalZ == 0) {
            return;
        }
        // Route through the delta-sync channel (see AEntityVehicleD_Moving#applyExternalCollisionCorrection)
        // so the server-authoritative push-out reconciles with the client instead of diverging.
        vehicle.applyExternalCollisionCorrection(new Point3D(totalX, 0, totalZ), 0);
        // Wall slide: cancel only the motion component driving into the wall; keep tangential motion.
        if (totalX > 0 && vehicle.motion.x < 0) {
            vehicle.motion.x = 0;
        } else if (totalX < 0 && vehicle.motion.x > 0) {
            vehicle.motion.x = 0;
        }
        if (totalZ > 0 && vehicle.motion.z < 0) {
            vehicle.motion.z = 0;
        } else if (totalZ < 0 && vehicle.motion.z > 0) {
            vehicle.motion.z = 0;
        }
        vehicle.velocity = vehicle.motion.length();
    }

    // ---- vehicle-to-vehicle -----------------------------------------------------------------------

    private static void resolveVehicleCollisions(State state, List<EntityVehicleF_Physics> vehicles) {
        double broad = CollisionConfig.v2vBroadPhaseRadius;
        double sumRadii = 2.0 * CollisionConfig.v2vSphereRadius;
        int count = vehicles.size();
        for (int a = 0; a < count; a++) {
            EntityVehicleF_Physics va = vehicles.get(a);
            if (!isCollidable(va)) {
                continue;   // don't let an unfinished/wheel-less frame push (or be pushed by) others
            }
            for (int b = a + 1; b < count; b++) {
                EntityVehicleF_Physics vb = vehicles.get(b);
                if (!isCollidable(vb)) {
                    continue;
                }
                double dist = va.position.distanceTo(vb.position);
                if (dist > broad) {
                    continue;
                }
                double overlap = sumRadii - dist;
                if (overlap <= 0) {
                    continue;
                }
                if (shareTowChain(va, vb)) {
                    continue;
                }
                // Horizontal unit normal from A to B.
                Point3D normal = vb.position.copy().subtract(va.position);
                normal.y = 0;
                double len = normal.length();
                if (len < EPSILON) {
                    normal.set(1, 0, 0);
                } else {
                    normal.scale(1.0 / len);
                }
                double massA = Math.max(va.currentMass, 1.0);
                double massB = Math.max(vb.currentMass, 1.0);
                double ratioA = massB / (massA + massB);   // lighter vehicle moves more
                double ratioB = massA / (massA + massB);

                // Positional separation so they never stay interpenetrating. Routed through the
                // delta-sync channel (copy() so the shared `normal` is left intact for the impulse below).
                double separation = overlap + CollisionConfig.v2vSeparationEpsilon;
                va.applyExternalCollisionCorrection(normal.copy().scale(-separation * ratioA), 0);
                vb.applyExternalCollisionCorrection(normal.copy().scale(separation * ratioB), 0);

                // Impulse / spin: only when the two are actually closing along the normal.
                Point3D relVel = vb.motion.copy().subtract(va.motion);
                double closing = relVel.dotProduct(normal, false);
                if (closing < 0) {
                    double impulse = -closing;
                    addKnockback(state, va.uniqueUUID, normal, -impulse * ratioA);
                    addKnockback(state, vb.uniqueUUID, normal, impulse * ratioB);

                    if (CollisionConfig.v2vSpinFactor != 0) {
                        // The tangential (off-centre) part of the relative velocity drives the spin.
                        Point3D tangential = relVel.copy().addScaled(normal, -closing);
                        double tangSpeed = tangential.length();
                        if (tangSpeed > EPSILON) {
                            double sign = Math.signum(normal.x * tangential.z - normal.z * tangential.x);
                            if (sign == 0) {
                                sign = 1;
                            }
                            double spin = CollisionConfig.v2vSpinFactor * tangSpeed * sign;
                            addSpin(state, va.uniqueUUID, spin * ratioA);
                            addSpin(state, vb.uniqueUUID, -spin * ratioB);
                        }
                    }
                }
            }
        }
    }

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
    private static boolean isCollidable(EntityVehicleF_Physics vehicle) {
        return vehicle.ticksExisted >= CollisionConfig.minTickAge && vehicle.groundDeviceCollective.isReady();
    }

    private static boolean shareTowChain(EntityVehicleF_Physics a, EntityVehicleF_Physics b) {
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

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }
}
