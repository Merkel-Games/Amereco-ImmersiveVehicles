package com.ivbettercollisions;

import minecrafttransportsimulator.baseclasses.Point3D;

/**
 * Mutable accumulator for the contacts between two hitbox sets (or a hitbox set and world blocks).
 * Each overlapping box pair contributes its horizontal MTV direction weighted by penetration depth plus
 * its depth-weighted overlap-region centre; {@link #finalizeManifold} blends them into a single contact
 * normal, contact point and penetration for one impulse resolution.
 * <p>
 * Degenerate handling: if the per-pair MTVs cancel out (symmetric overlap), the caller-supplied
 * fallback direction (typically centre-to-centre) is used; if that is also zero, the normal defaults to
 * +X and {@link #impulseSkipped} is set so callers only separate positions without applying an impulse.
 */
public final class ContactManifold {
    /** Blended unit contact normal, valid after {@link #finalizeManifold}. */
    public final Point3D normal = new Point3D();
    /** Depth-weighted contact point, valid after {@link #finalizeManifold}. */
    public final Point3D contact = new Point3D();
    /** Deepest single-pair penetration (blocks). */
    public double penetration;
    /** True when the normal was unrecoverably degenerate - separate only, no impulse. */
    public boolean impulseSkipped;

    private final Point3D normalSum = new Point3D();
    private final Point3D contactSum = new Point3D();
    private double weightSum;

    private static final double DEGENERATE_EPSILON = 1.0e-6;

    public void reset() {
        normalSum.set(0, 0, 0);
        contactSum.set(0, 0, 0);
        weightSum = 0;
        penetration = 0;
        impulseSkipped = false;
    }

    /**
     * Adds one box-pair contact.
     *
     * @param nx    unit MTV direction x (horizontal, signed toward pushing B away from A)
     * @param nz    unit MTV direction z
     * @param depth penetration depth along the MTV axis (&gt; 0)
     * @param cx    overlap-region centre x
     * @param cy    overlap-region centre y
     * @param cz    overlap-region centre z
     */
    public void addContact(double nx, double nz, double depth, double cx, double cy, double cz) {
        normalSum.add(nx * depth, 0, nz * depth);
        contactSum.add(cx * depth, cy * depth, cz * depth);
        weightSum += depth;
        if (depth > penetration) {
            penetration = depth;
        }
    }

    public boolean isEmpty() {
        return weightSum == 0;
    }

    /**
     * Blends the accumulated contacts.  Returns false when the manifold is empty.
     *
     * @param fallbackDirection direction to use when the summed MTVs cancel (not normalized, y ignored);
     *                          may be zero, in which case the impulse is flagged skipped
     */
    public boolean finalizeManifold(Point3D fallbackDirection) {
        if (weightSum == 0) {
            return false;
        }
        contact.set(contactSum).scale(1.0 / weightSum);

        double length = Math.hypot(normalSum.x, normalSum.z);
        if (length > DEGENERATE_EPSILON) {
            normal.set(normalSum.x / length, 0, normalSum.z / length);
            return true;
        }
        double fallbackLength = Math.hypot(fallbackDirection.x, fallbackDirection.z);
        if (fallbackLength > DEGENERATE_EPSILON) {
            normal.set(fallbackDirection.x / fallbackLength, 0, fallbackDirection.z / fallbackLength);
            return true;
        }
        normal.set(1, 0, 0);
        impulseSkipped = true;
        return true;
    }
}
