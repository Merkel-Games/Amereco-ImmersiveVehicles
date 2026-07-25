package com.ivbettercollisions;

import java.util.List;

import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.world.level.Level;

/**
 * Wall pass.  Two jobs that must never share a measurement:
 * <ol>
 *   <li><b>Push-out</b> - measured against the vehicle's TRUE hitboxes, so it only ever fires when a box
 *       is genuinely inside a block.  This is anti-stuck insurance, nothing more.</li>
 *   <li><b>Impact response</b> - restitution rebound, tangential friction scrub and off-centre yaw,
 *       triggered by a <b>swept probe</b> that grows each box only along the direction of travel.</li>
 * </ol>
 * Keeping these apart is the whole point.  The predecessor inflated every box by a fixed margin and then
 * reused that inflated overlap as the penetration depth, which gave every solid block an invisible
 * half-block force field: driving down a passage, both walls "collided" at once, the larger push won
 * outright, and the multi-pass loop settled into a period-2 cycle that flung the vehicle from wall to
 * wall every tick.  Measuring push-out against the true box makes that impossible - in a passage wider
 * than the vehicle there is simply no overlap to react to - while the swept probe keeps impacts feeling
 * solid, because it only ever reaches in the direction the vehicle is actually moving.  A corridor's side
 * walls are parallel to travel and therefore can never enter the probe.
 * <p>
 * Impact speed comes from {@code max(0, -(motion·n), -(prevMotion·n))}: core captures {@code prevMotion}
 * at the START of the vehicle tick, before MTS's own collision handling absorbs the into-wall component,
 * so it still holds the true approach speed even though core has already stopped the vehicle short of the
 * wall by the time this pass runs.  On the following tick {@code prevMotion} points away from the wall,
 * the probe finds nothing, and no second bounce is applied - the model self-limits.
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
        // The swept probe reaches along this tick's travel.  prevMotion, not motion: by now core has
        // already subtracted the into-wall component from motion on exactly the tick of a real impact.
        double probeX = CollisionMath.sweptExpansion(vehicle.prevMotion.x, vehicle.speedFactor, CollisionConfig.wallProbeMaxDistance);
        double probeZ = CollisionMath.sweptExpansion(vehicle.prevMotion.z, vehicle.speedFactor, CollisionConfig.wallProbeMaxDistance);

        WallPushResolver resolver = new WallPushResolver();
        double totalX = 0;
        double totalZ = 0;

        // Impact contacts gathered on the FIRST pass only; later passes re-detect the same blocks at
        // shifted positions and would double-count the contact centroid.
        double contactX = 0;
        double contactZ = 0;
        double contactWeight = 0;
        double probePushX = 0;
        double probePushZ = 0;
        double deepest = 0;
        double deepestBlockX = 0;
        double deepestBlockY = 0;
        double deepestBlockZ = 0;

        for (int pass = 0; pass < CollisionConfig.maxPasses; pass++) {
            resolver.reset();
            boolean firstPass = pass == 0;

            for (BoundingBox box : vehicle.allBlockCollisionBoxes) {
                if (box.definition == null) {
                    // Ground-device boxes: their globalCenter carries an extra tick of motion look-ahead
                    // (VehicleGroundDeviceBox applies vehicle.motion * speedFactor) and they can appear in
                    // this list twice, so their position is not a sound basis for a positional correction.
                    // Wheel-versus-ground is the ground-device system's job anyway.
                    continue;
                }
                // Query at the box's current centre plus whatever has been applied this tick (the
                // vehicle's boxes are only rebuilt next tick, so we offset the query rather than mutate them).
                double cx = box.globalCenter.x + priorShift.x + totalX;
                double cy = box.globalCenter.y;
                double cz = box.globalCenter.z + priorShift.z + totalZ;

                // Candidate query only - inflated wide enough to cover the true box AND the swept probe.
                double queryX = box.widthRadius + CollisionConfig.wallBoxMargin + Math.abs(probeX);
                double queryZ = box.depthRadius + CollisionConfig.wallBoxMargin + Math.abs(probeZ);
                List<double[]> blocks = MtsAccess.getSolidBlockCollisions(level, cx, cy, cz, queryX, box.heightRadius, queryZ);
                if (blocks.isEmpty()) {
                    continue;
                }
                // TRUE box - the only geometry a push-out is ever measured against.
                double boxMinX = cx - box.widthRadius, boxMaxX = cx + box.widthRadius;
                double boxMinY = cy - box.heightRadius, boxMaxY = cy + box.heightRadius;
                double boxMinZ = cz - box.depthRadius, boxMaxZ = cz + box.depthRadius;
                // Swept box - the true box grown along the direction of travel only.
                double sweptMinX = boxMinX + Math.min(probeX, 0), sweptMaxX = boxMaxX + Math.max(probeX, 0);
                double sweptMinZ = boxMinZ + Math.min(probeZ, 0), sweptMaxZ = boxMaxZ + Math.max(probeZ, 0);

                for (double[] b : blocks) {
                    double overlapY = CollisionMath.overlap(boxMinY, boxMaxY, b[1], b[4]);
                    if (overlapY <= 0) {
                        continue;
                    }
                    double overlapX = CollisionMath.overlap(boxMinX, boxMaxX, b[0], b[3]);
                    double overlapZ = CollisionMath.overlap(boxMinZ, boxMaxZ, b[2], b[5]);

                    // ---- push-out: genuine penetration of the true box only ----
                    if (overlapX > 0 && overlapZ > 0 && !CollisionMath.isVerticalContact(overlapX, overlapY, overlapZ)) {
                        if (overlapX <= overlapZ) {
                            double push = overlapX + CollisionConfig.epsilon;
                            resolver.addPushX(cx < (b[0] + b[3]) * 0.5 ? -push : push);
                        } else {
                            double push = overlapZ + CollisionConfig.epsilon;
                            resolver.addPushZ(cz < (b[2] + b[5]) * 0.5 ? -push : push);
                        }
                    }

                    // ---- impact detection: swept probe, first pass only ----
                    if (!firstPass || CollisionMath.isClimbable(b[4], boxMinY, CollisionConfig.wallCurbHeight)) {
                        continue;   // kerbs and doorsteps get driven over, not bounced off
                    }
                    double sweptX = CollisionMath.overlap(sweptMinX, sweptMaxX, b[0], b[3]);
                    double sweptZ = CollisionMath.overlap(sweptMinZ, sweptMaxZ, b[2], b[5]);
                    if (sweptX <= 0 || sweptZ <= 0 || CollisionMath.isVerticalContact(sweptX, overlapY, sweptZ)) {
                        continue;
                    }
                    double depth = Math.min(sweptX, sweptZ);
                    // Contact centroid from the TRUE overlap region where one exists, so the yaw lever arm
                    // is not biased outward by the probe's reach.
                    contactX += depth * (Math.max(boxMinX, b[0]) + Math.min(boxMaxX, b[3])) * 0.5;
                    contactZ += depth * (Math.max(boxMinZ, b[2]) + Math.min(boxMaxZ, b[5])) * 0.5;
                    contactWeight += depth;
                    if (sweptX <= sweptZ) {
                        probePushX += cx < (b[0] + b[3]) * 0.5 ? -depth : depth;
                    } else {
                        probePushZ += cz < (b[2] + b[5]) * 0.5 ? -depth : depth;
                    }
                    if (depth > deepest) {
                        deepest = depth;
                        deepestBlockX = (b[0] + b[3]) * 0.5;
                        deepestBlockY = (b[1] + b[4]) * 0.5;
                        deepestBlockZ = (b[2] + b[5]) * 0.5;
                    }
                }
            }

            double netX = resolver.netX();
            double netZ = resolver.netZ();
            if (Math.abs(netX) < CollisionConfig.wallWedgeThreshold && Math.abs(netZ) < CollisionConfig.wallWedgeThreshold) {
                break;   // settled (or wedged and correctly refusing to teleport)
            }
            totalX += netX;
            totalZ += netZ;
            if (Math.abs(totalX) > CollisionConfig.maxCorrection || Math.abs(totalZ) > CollisionConfig.maxCorrection) {
                // Buried far deeper than a stray clip - a vehicle spawned inside a building, say.  Do not
                // launch it; leave it where it is.
                totalX = 0;
                totalZ = 0;
                break;
            }
        }

        if (totalX != 0 || totalZ != 0) {
            // Route through the delta-sync channel (see AEntityVehicleD_Moving#applyExternalCollisionCorrection)
            // so the server-authoritative push-out reconciles with the client instead of diverging.
            vehicle.applyExternalCollisionCorrection(new Point3D(totalX, 0, totalZ), 0);
            priorShift.add(totalX, 0, totalZ);
        }

        // ---- impact response ----------------------------------------------------------------------
        double normalLength = Math.hypot(probePushX, probePushZ);
        if (normalLength == 0) {
            return;   // nothing in the direction of travel: free driving, or scraping along a wall
        }
        Point3D normal = new Point3D(probePushX / normalLength, 0, probePushZ / normalLength);
        Point3D motion = vehicle.motion;
        double motionDotN = motion.x * normal.x + motion.z * normal.z;
        double approach = Math.max(0, Math.max(-motionDotN,
                -(vehicle.prevMotion.x * normal.x + vehicle.prevMotion.z * normal.z)));

        if (approach >= CollisionConfig.wallMinImpactSpeed && VehicleCollisionHandler.canTakeWallImpact(state, vehicle.uniqueUUID)) {
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
            VehicleCollisionHandler.startWallImpactCooldown(state, vehicle.uniqueUUID);

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
            // Rest contact (or cooling down): pure wall slide - cancel only the motion driving into the wall.
            if (motionDotN < 0) {
                motion.x -= motionDotN * normal.x;
                motion.z -= motionDotN * normal.z;
            }
        }
        vehicle.velocity = motion.length();
    }
}
