package com.ivbettercollisions;

import java.util.List;

import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.world.level.Level;

/**
 * Wall pass: pushes vehicles out of solid blocks (multi-pass horizontal MTV) and applies the GTA-style
 * impact response - restitution rebound, tangential friction scrub (block-slipperiness aware) and an
 * off-centre yaw impulse - instead of the old unconditional motion-zeroing.
 * <p>
 * Impact speed comes from {@code max(0, -(motion·n), -(prevMotion·n))}: core captures {@code prevMotion}
 * at the START of the vehicle tick, before MTS's own collision handling absorbs the into-wall component,
 * so it holds the true approach speed even when core already stopped the vehicle this tick.  The
 * {@code max(0,..)} form makes a vehicle that is already rebounding (or resting against the wall) read
 * approach ~0, which lands in the rest branch (pure slide) - no repeated bouncing.
 * <p>
 * Vertical resolution stays fully delegated to MTS's ground-device system; local Point3D scratch is
 * allocated per call because the pass runs concurrently on the server and client threads.
 */
final class WallCollisionPass {

    private WallCollisionPass() {
    }

    /**
     * Runs the wall correction for one vehicle.  {@code priorShift} is the position shift already
     * applied to this vehicle earlier this tick (v2v separation, knockback) - the vehicle's boxes only
     * rebuild next tick, so block queries are offset by it; the pass adds its own correction to it.
     */
    static void correct(Level level, EntityVehicleF_Physics vehicle, VehicleCollisionHandler.State state, Point3D priorShift) {
        if (ConfigSystem.settings.general.noclipVehicles.value) {
            return;
        }
        if (!VehicleCollisionHandler.isCollidable(vehicle) || vehicle.allBlockCollisionBoxes.isEmpty()) {
            return;
        }
        // Speed-adaptive margin: inflate detection with per-tick displacement so high speeds can't skip
        // past the static margin in one tick.
        double horizSpeed = Math.hypot(vehicle.motion.x, vehicle.motion.z);
        double margin = CollisionConfig.wallBoxMargin
                + Math.min(horizSpeed * vehicle.speedFactor * CollisionConfig.wallMarginSpeedScale, CollisionConfig.wallMarginSpeedMax);
        double maxCorrection = CollisionConfig.maxCorrection;
        double totalX = 0;
        double totalZ = 0;

        // Contact accumulation for the impact model - gathered on the FIRST pass only (later passes
        // re-detect the same blocks at shifted positions and would double-count).
        double contactX = 0;
        double contactZ = 0;
        double contactWeight = 0;
        double deepest = 0;
        double deepestBlockX = 0;
        double deepestBlockY = 0;
        double deepestBlockZ = 0;

        for (int pass = 0; pass < CollisionConfig.maxPasses; pass++) {
            double passX = 0;
            double passZ = 0;
            boolean collided = false;

            for (BoundingBox box : vehicle.allBlockCollisionBoxes) {
                double halfX = box.widthRadius + margin;
                double halfY = box.heightRadius;   // vertical is left to MTS's ground-device system
                double halfZ = box.depthRadius + margin;
                // Query at the box's current centre plus whatever has been applied this tick (the
                // vehicle's boxes are only rebuilt next tick, so we offset the query rather than mutate them).
                double cx = box.globalCenter.x + priorShift.x + totalX;
                double cy = box.globalCenter.y;
                double cz = box.globalCenter.z + priorShift.z + totalZ;

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
                    if (pass == 0) {
                        double depth = Math.min(overlapX, overlapZ);
                        contactX += depth * (Math.max(boxMinX, b[0]) + Math.min(boxMaxX, b[3])) * 0.5;
                        contactZ += depth * (Math.max(boxMinZ, b[2]) + Math.min(boxMaxZ, b[5])) * 0.5;
                        contactWeight += depth;
                        if (depth > deepest) {
                            deepest = depth;
                            deepestBlockX = (b[0] + b[3]) * 0.5;
                            deepestBlockY = (b[1] + b[4]) * 0.5;
                            deepestBlockZ = (b[2] + b[5]) * 0.5;
                        }
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
        priorShift.add(totalX, 0, totalZ);

        // ---- impact response ----------------------------------------------------------------------
        double normalLength = Math.hypot(totalX, totalZ);
        Point3D normal = new Point3D(totalX / normalLength, 0, totalZ / normalLength);
        Point3D motion = vehicle.motion;
        double motionDotN = motion.x * normal.x + motion.z * normal.z;
        double approach = Math.max(0, Math.max(-motionDotN,
                -(vehicle.prevMotion.x * normal.x + vehicle.prevMotion.z * normal.z)));

        if (approach >= CollisionConfig.wallMinImpactSpeed) {
            // Real impact: restitution rebound + friction scrub + off-centre yaw.
            double frictionLoss = CollisionConfig.wallFriction;
            if (CollisionConfig.wallFrictionUseSlipperiness && deepest > 0) {
                float slipperiness = vehicle.world.getBlockSlipperiness(new Point3D(deepestBlockX, deepestBlockY, deepestBlockZ));
                if (slipperiness > 0) {
                    frictionLoss = CollisionMath.clamp(frictionLoss * (0.6 / slipperiness), 0, 0.9);
                }
            }
            // Never reduce an already-outward normal speed (prevents killing a rebound in progress).
            double outgoing = Math.max(motionDotN, CollisionConfig.wallRestitution * approach);
            CollisionMath.wallResponse(motion, normal, outgoing, frictionLoss, motion);

            if (contactWeight > 0 && CollisionConfig.wallYawFactor != 0) {
                Point3D lever = new Point3D(contactX / contactWeight - vehicle.position.x, 0,
                        contactZ / contactWeight - vehicle.position.z);
                double yaw = CollisionMath.wallYawDegrees(lever, normal, approach,
                        CollisionConfig.wallRestitution, CollisionConfig.wallYawFactor, CollisionConfig.wallYawMaxPerImpact);
                if (yaw != 0) {
                    VehicleCollisionHandler.addSpin(state, vehicle.uniqueUUID, yaw);
                }
            }
        } else {
            // Rest contact: pure wall slide - cancel only the motion component driving into the wall.
            if (totalX > 0 && motion.x < 0) {
                motion.x = 0;
            } else if (totalX < 0 && motion.x > 0) {
                motion.x = 0;
            }
            if (totalZ > 0 && motion.z < 0) {
                motion.z = 0;
            } else if (totalZ < 0 && motion.z > 0) {
                motion.z = 0;
            }
        }
        vehicle.velocity = motion.length();
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }
}
