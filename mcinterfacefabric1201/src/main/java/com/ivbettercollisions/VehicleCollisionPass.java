package com.ivbettercollisions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;

/**
 * Vehicle-to-vehicle pass.  Default "box" mode: narrow phase over the two vehicles' real hitbox sets
 * ({@code allBlockCollisionBoxes} - the same boxes that define the solid body against the world, laid
 * out each tick from the rotated orientation, so the SET approximates the oriented hull), blended into
 * a {@link ContactManifold}, then one impulse resolution per pair per tick:
 * <ol>
 *   <li>positional de-penetration with a resting slop (parked touching vehicles = zero correction,
 *       zero jitter), mass-weighted, through the delta-sync hook;</li>
 *   <li>a proper collision impulse {@code j = -(1+e)*closing / (1/mA + 1/mB)} applied as direct motion
 *       writes - exact momentum conservation, so rear-ending shoves the car ahead by mass ratio and MTS's
 *       own ground friction then brings it to rest;</li>
 *   <li>yaw impulses to both vehicles from the contact lever arms, fed into the decaying spin state.</li>
 * </ol>
 * Contacts whose overlap is smallest on Y are stacking/riding contacts and are skipped - vertical
 * support belongs to MTS (same delegation as the wall pass).  The legacy v1 sphere model is preserved
 * behind {@code v2vMode="sphere"}.
 */
public final class VehicleCollisionPass {

    private static final double EPSILON = 1.0e-4;

    private VehicleCollisionPass() {
    }

    static void resolve(VehicleCollisionHandler.State state, List<EntityVehicleF_Physics> vehicles, Map<UUID, Point3D> appliedShift) {
        if ("sphere".equals(CollisionConfig.v2vMode)) {
            resolveSphere(state, vehicles);
        } else {
            resolveBox(state, vehicles, appliedShift);
        }
    }

    // ---- box mode (v2) ----------------------------------------------------------------------------

    private static void resolveBox(VehicleCollisionHandler.State state, List<EntityVehicleF_Physics> vehicles, Map<UUID, Point3D> appliedShift) {
        double broad = CollisionConfig.v2vBroadPhaseRadius;
        Map<UUID, double[]> unionCache = new HashMap<>();
        ContactManifold manifold = new ContactManifold();
        Point3D fallback = new Point3D();
        int count = vehicles.size();

        for (int a = 0; a < count; a++) {
            EntityVehicleF_Physics va = vehicles.get(a);
            if (!VehicleCollisionHandler.isCollidable(va) || va.allBlockCollisionBoxes.isEmpty()) {
                continue;
            }
            for (int b = a + 1; b < count; b++) {
                EntityVehicleF_Physics vb = vehicles.get(b);
                if (!VehicleCollisionHandler.isCollidable(vb) || vb.allBlockCollisionBoxes.isEmpty()) {
                    continue;
                }
                if (va.position.distanceTo(vb.position) > broad) {
                    continue;
                }
                double[] unionA = unionBox(unionCache, va);
                double[] unionB = unionBox(unionCache, vb);
                if (CollisionMath.overlap(unionA[0], unionA[3], unionB[0], unionB[3]) <= 0
                        || CollisionMath.overlap(unionA[1], unionA[4], unionB[1], unionB[4]) <= 0
                        || CollisionMath.overlap(unionA[2], unionA[5], unionB[2], unionB[5]) <= 0) {
                    continue;
                }
                if (VehicleCollisionHandler.shareTowChain(va, vb)) {
                    continue;
                }
                if (va.collidedEntities.contains(vb) || vb.collidedEntities.contains(va)) {
                    continue;   // riding/stacked - core's ride-along logic owns this pair
                }

                manifold.reset();
                collectContacts(va.allBlockCollisionBoxes, vb.allBlockCollisionBoxes, manifold);
                if (manifold.isEmpty()) {
                    continue;
                }
                fallback.set(vb.position.x - va.position.x, 0, vb.position.z - va.position.z);
                manifold.finalizeManifold(fallback);
                resolvePair(state, va, vb, manifold, appliedShift);
            }
        }
    }

    /**
     * Narrow phase: tests every box pair of the two hitbox sets, filters vertical (stacking) contacts,
     * and accumulates the horizontal MTV of each genuinely-overlapping pair into the manifold.  The MTV
     * direction is signed to push set B away from set A.  Public-static and world-free so gametests can
     * run it against real vehicle boxes without a ready vehicle.
     */
    public static void collectContacts(Iterable<BoundingBox> setA, Iterable<BoundingBox> setB, ContactManifold manifold) {
        for (BoundingBox boxA : setA) {
            double aMinX = boxA.globalCenter.x - boxA.widthRadius, aMaxX = boxA.globalCenter.x + boxA.widthRadius;
            double aMinY = boxA.globalCenter.y - boxA.heightRadius, aMaxY = boxA.globalCenter.y + boxA.heightRadius;
            double aMinZ = boxA.globalCenter.z - boxA.depthRadius, aMaxZ = boxA.globalCenter.z + boxA.depthRadius;
            for (BoundingBox boxB : setB) {
                double overlapX = CollisionMath.overlap(aMinX, aMaxX, boxB.globalCenter.x - boxB.widthRadius, boxB.globalCenter.x + boxB.widthRadius);
                if (overlapX <= 0) {
                    continue;
                }
                double overlapY = CollisionMath.overlap(aMinY, aMaxY, boxB.globalCenter.y - boxB.heightRadius, boxB.globalCenter.y + boxB.heightRadius);
                if (overlapY <= 0) {
                    continue;
                }
                double overlapZ = CollisionMath.overlap(aMinZ, aMaxZ, boxB.globalCenter.z - boxB.depthRadius, boxB.globalCenter.z + boxB.depthRadius);
                if (overlapZ <= 0) {
                    continue;
                }
                if (overlapY <= Math.min(overlapX, overlapZ)) {
                    continue;   // stacking/ramp contact - vertical support is core's job
                }
                double depth;
                double nx = 0;
                double nz = 0;
                if (overlapX <= overlapZ) {
                    depth = overlapX;
                    nx = boxB.globalCenter.x >= boxA.globalCenter.x ? 1 : -1;
                } else {
                    depth = overlapZ;
                    nz = boxB.globalCenter.z >= boxA.globalCenter.z ? 1 : -1;
                }
                manifold.addContact(nx, nz, depth,
                        (Math.max(aMinX, boxB.globalCenter.x - boxB.widthRadius) + Math.min(aMaxX, boxB.globalCenter.x + boxB.widthRadius)) * 0.5,
                        (Math.max(aMinY, boxB.globalCenter.y - boxB.heightRadius) + Math.min(aMaxY, boxB.globalCenter.y + boxB.heightRadius)) * 0.5,
                        (Math.max(aMinZ, boxB.globalCenter.z - boxB.depthRadius) + Math.min(aMaxZ, boxB.globalCenter.z + boxB.depthRadius)) * 0.5);
            }
        }
    }

    private static void resolvePair(VehicleCollisionHandler.State state, EntityVehicleF_Physics va, EntityVehicleF_Physics vb,
            ContactManifold manifold, Map<UUID, Point3D> appliedShift) {
        Point3D n = manifold.normal;
        double massA = Math.max(va.currentMass, 1.0);
        double massB = Math.max(vb.currentMass, 1.0);
        double ratioA = massB / (massA + massB);   // lighter vehicle moves more
        double ratioB = massA / (massA + massB);

        // 1) Positional de-penetration, tolerant of the resting slop.
        double correction = Math.max(manifold.penetration - CollisionConfig.v2vPenetrationSlop, 0) * CollisionConfig.v2vCorrectionPercent;
        if (correction > 0) {
            Point3D shiftA = new Point3D(-n.x * correction * ratioA, 0, -n.z * correction * ratioA);
            Point3D shiftB = new Point3D(n.x * correction * ratioB, 0, n.z * correction * ratioB);
            va.applyExternalCollisionCorrection(shiftA, 0);
            vb.applyExternalCollisionCorrection(shiftB, 0);
            appliedShift.computeIfAbsent(va.uniqueUUID, k -> new Point3D()).add(shiftA);
            appliedShift.computeIfAbsent(vb.uniqueUUID, k -> new Point3D()).add(shiftB);
        }

        // 2) Collision impulse - only when actually closing, with rest-speed cutoff for restitution.
        if (manifold.impulseSkipped) {
            return;
        }
        double closing = (vb.motion.x - va.motion.x) * n.x + (vb.motion.z - va.motion.z) * n.z;
        if (closing >= 0) {
            return;
        }
        double restitution = -closing >= CollisionConfig.v2vRestSpeed ? CollisionConfig.v2vRestitution : 0;
        double j = CollisionMath.impulseMagnitude(closing, restitution, massA, massB);
        j = CollisionMath.clampImpulse(j, massA, massB, CollisionConfig.v2vMaxImpulse);
        if (j <= 0) {
            return;
        }
        va.motion.add(-n.x * j / massA, 0, -n.z * j / massA);
        vb.motion.add(n.x * j / massB, 0, n.z * j / massB);
        va.velocity = va.motion.length();
        vb.velocity = vb.motion.length();

        // 3) Yaw impulses from the contact lever arms (both spin, opposite senses on a T-bone).
        if (CollisionConfig.v2vYawFactor != 0) {
            Point3D leverA = new Point3D(manifold.contact.x - va.position.x, 0, manifold.contact.z - va.position.z);
            Point3D leverB = new Point3D(manifold.contact.x - vb.position.x, 0, manifold.contact.z - vb.position.z);
            double yawA = CollisionMath.vehicleYawDegrees(leverA, n, -j, massA, CollisionConfig.v2vYawFactor, CollisionConfig.v2vYawMaxPerImpact);
            double yawB = CollisionMath.vehicleYawDegrees(leverB, n, j, massB, CollisionConfig.v2vYawFactor, CollisionConfig.v2vYawMaxPerImpact);
            if (yawA != 0) {
                VehicleCollisionHandler.addSpin(state, va.uniqueUUID, yawA);
            }
            if (yawB != 0) {
                VehicleCollisionHandler.addSpin(state, vb.uniqueUUID, yawB);
            }
        }
    }

    private static double[] unionBox(Map<UUID, double[]> cache, EntityVehicleF_Physics vehicle) {
        return cache.computeIfAbsent(vehicle.uniqueUUID, k -> {
            double margin = CollisionConfig.v2vBroadPhaseMargin;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (BoundingBox box : vehicle.allBlockCollisionBoxes) {
                minX = Math.min(minX, box.globalCenter.x - box.widthRadius);
                minY = Math.min(minY, box.globalCenter.y - box.heightRadius);
                minZ = Math.min(minZ, box.globalCenter.z - box.depthRadius);
                maxX = Math.max(maxX, box.globalCenter.x + box.widthRadius);
                maxY = Math.max(maxY, box.globalCenter.y + box.heightRadius);
                maxZ = Math.max(maxZ, box.globalCenter.z + box.depthRadius);
            }
            return new double[] {minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin};
        });
    }

    // ---- legacy sphere mode (v1, verbatim behavior) -----------------------------------------------

    private static void resolveSphere(VehicleCollisionHandler.State state, List<EntityVehicleF_Physics> vehicles) {
        double broad = CollisionConfig.v2vBroadPhaseRadius;
        double sumRadii = 2.0 * CollisionConfig.v2vSphereRadius;
        int count = vehicles.size();
        for (int a = 0; a < count; a++) {
            EntityVehicleF_Physics va = vehicles.get(a);
            if (!VehicleCollisionHandler.isCollidable(va)) {
                continue;   // don't let an unfinished/wheel-less frame push (or be pushed by) others
            }
            for (int b = a + 1; b < count; b++) {
                EntityVehicleF_Physics vb = vehicles.get(b);
                if (!VehicleCollisionHandler.isCollidable(vb)) {
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
                if (VehicleCollisionHandler.shareTowChain(va, vb)) {
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

                // Positional separation so they never stay interpenetrating.
                double separation = overlap + CollisionConfig.v2vSeparationEpsilon;
                va.applyExternalCollisionCorrection(normal.copy().scale(-separation * ratioA), 0);
                vb.applyExternalCollisionCorrection(normal.copy().scale(separation * ratioB), 0);

                // Impulse / spin: only when the two are actually closing along the normal.
                Point3D relVel = vb.motion.copy().subtract(va.motion);
                double closing = relVel.dotProduct(normal, false);
                if (closing < 0) {
                    double impulse = -closing;
                    VehicleCollisionHandler.addKnockback(state, va.uniqueUUID, normal, -impulse * ratioA);
                    VehicleCollisionHandler.addKnockback(state, vb.uniqueUUID, normal, impulse * ratioB);

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
                            VehicleCollisionHandler.addSpin(state, va.uniqueUUID, spin * ratioA);
                            VehicleCollisionHandler.addSpin(state, vb.uniqueUUID, -spin * ratioB);
                        }
                    }
                }
            }
        }
    }
}
