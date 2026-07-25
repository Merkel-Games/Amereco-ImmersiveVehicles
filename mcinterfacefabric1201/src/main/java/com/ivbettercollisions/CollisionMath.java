package com.ivbettercollisions;

import minecrafttransportsimulator.baseclasses.Point3D;

/**
 * Pure collision math for the Ultimate Collisions model.  Every function here is static, side-effect
 * free (except into caller-supplied output objects) and depends only on {@link Point3D} and primitives,
 * so the whole impact model is verifiable by gametests without spawning a single vehicle.
 * <p>
 * Conventions: contact normals are horizontal unit vectors (y = 0).  For vehicle pairs the normal
 * points from A towards B.  Yaw torque about Y from a force F applied at lever arm r (both horizontal)
 * is {@code r.z * F.x - r.x * F.z}; the {@code *YawFactor} config values can be negative to flip
 * handedness if the in-game sense is inverted.
 */
public final class CollisionMath {

    /** MTS's yaw moment-of-inertia approximation is 3 * mass (EntityVehicleF_Physics.momentYaw). */
    private static final double YAW_MOMENT_PER_MASS = 3.0;

    private CollisionMath() {
    }

    /** Overlap of two 1-D intervals; &lt;= 0 means no overlap. */
    public static double overlap(double minA, double maxA, double minB, double maxB) {
        return Math.min(maxA, maxB) - Math.max(minA, minB);
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * True when a box/block overlap is a vertical (resting, stacking, kerb-top) contact rather than a
     * side contact.  A block directly beneath a vehicle overlaps by the full block footprint horizontally
     * but only by the sink depth vertically, so resolving it on a horizontal axis would fling the vehicle
     * sideways by up to a full block; such contacts belong to MTS's ground-device system, not to us.
     */
    public static boolean isVerticalContact(double overlapX, double overlapY, double overlapZ) {
        return overlapY <= Math.min(overlapX, overlapZ);
    }

    /**
     * True when a contacted block is low enough for the vehicle to drive over it (kerb, doorstep, slab)
     * rather than a wall to bounce off.  Mirrors the wall-versus-kerb discrimination Automobility does
     * with a dual-height probe, but needs no second world query: the block's top face is already known.
     *
     * @param blockTop   world Y of the contacted block's top face
     * @param boxBottom  world Y of the vehicle box's bottom face
     * @param curbHeight climb height (blocks) below which contacts are treated as driveable
     */
    public static boolean isClimbable(double blockTop, double boxBottom, double curbHeight) {
        return blockTop - boxBottom <= curbHeight;
    }

    /**
     * Signed expansion of one axis for the swept impact probe: how far the box travels along that axis
     * this tick, clamped to {@code maxProbe}.  Positive expands the max face, negative the min face -
     * the box is only ever grown along the direction of travel, never sideways or backwards, which is
     * what makes a corridor's side walls structurally unable to produce a phantom contact.
     */
    public static double sweptExpansion(double motionComponent, double speedFactor, double maxProbe) {
        return clamp(motionComponent * speedFactor, -maxProbe, maxProbe);
    }

    /**
     * Impulse magnitude for a 1-D collision along the contact normal.
     *
     * @param closingSpeed relative normal velocity (B.motion - A.motion) dot n; negative when closing
     * @param restitution  coefficient of restitution e (0..1)
     * @param massA        mass of A (must be &gt;= some positive floor, caller-clamped)
     * @param massB        mass of B
     * @return j &gt;= 0; zero when the pair is separating or at rest ({@code closingSpeed >= 0})
     */
    public static double impulseMagnitude(double closingSpeed, double restitution, double massA, double massB) {
        if (closingSpeed >= 0) {
            return 0;
        }
        return -(1.0 + restitution) * closingSpeed / (1.0 / massA + 1.0 / massB);
    }

    /**
     * Scales the impulse down so that neither vehicle's velocity change ({@code j/mass}) exceeds
     * {@code maxDeltaV}.
     */
    public static double clampImpulse(double j, double massA, double massB, double maxDeltaV) {
        return Math.min(j, maxDeltaV * Math.min(massA, massB));
    }

    /**
     * Post-impact horizontal motion for a wall hit: sets the normal component to the given outgoing
     * speed (typically {@code restitution * approach}, or the current outward speed if larger - the
     * caller decides, which also prevents re-bouncing a vehicle already rebounding) and scrubs the
     * tangential component by the friction loss.  {@code motionOut.y} is preserved from
     * {@code motionIn} - vertical always belongs to MTS's ground-device system.
     *
     * @param motionIn            current motion (read-only)
     * @param normal              horizontal unit contact normal pointing away from the wall
     * @param outgoingNormalSpeed post-impact speed along +normal (&gt;= 0)
     * @param frictionLoss        fraction of tangential speed lost, in [0..1)
     * @param motionOut           receives the result (may be the same object as motionIn)
     */
    public static void wallResponse(Point3D motionIn, Point3D normal, double outgoingNormalSpeed, double frictionLoss, Point3D motionOut) {
        double vn = motionIn.x * normal.x + motionIn.z * normal.z;
        double tx = motionIn.x - vn * normal.x;
        double tz = motionIn.z - vn * normal.z;
        double keep = 1.0 - frictionLoss;
        motionOut.set(tx * keep + normal.x * outgoingNormalSpeed, motionIn.y, tz * keep + normal.z * outgoingNormalSpeed);
    }

    /**
     * Yaw impulse (degrees) from a wall impact.  Physically {@code torqueY / momentYaw} with
     * {@code J = m*(1+e)*approach} and {@code momentYaw = 3m}, so the mass cancels and the result is
     * mass-independent - exactly the arcade-consistent GTA feel.
     *
     * @param lever    contact point minus vehicle position, y ignored
     * @param normal   horizontal unit contact normal (push direction, away from wall)
     * @param approach impact approach speed (&gt;= 0)
     */
    public static double wallYawDegrees(Point3D lever, Point3D normal, double approach, double restitution, double yawFactor, double maxDegrees) {
        double torquePerMass = (1.0 + restitution) * approach * (lever.z * normal.x - lever.x * normal.z);
        double deg = yawFactor * Math.toDegrees(torquePerMass / YAW_MOMENT_PER_MASS);
        return clamp(deg, -maxDegrees, maxDegrees);
    }

    /**
     * Yaw impulse (degrees) on one vehicle of a v2v pair from impulse {@code signedJ * normal} applied
     * at lever arm {@code lever} (contact - vehicle.position).  {@code signedJ} is negative for vehicle
     * A (pushed against the normal) and positive for B.
     */
    public static double vehicleYawDegrees(Point3D lever, Point3D normal, double signedJ, double mass, double yawFactor, double maxDegrees) {
        double torqueY = signedJ * (lever.z * normal.x - lever.x * normal.z);
        double deg = yawFactor * Math.toDegrees(torqueY / (YAW_MOMENT_PER_MASS * mass));
        return clamp(deg, -maxDegrees, maxDegrees);
    }
}
