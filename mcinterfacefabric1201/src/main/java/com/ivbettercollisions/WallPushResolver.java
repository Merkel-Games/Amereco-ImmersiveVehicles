package com.ivbettercollisions;

/**
 * Accumulates the push-out each contact demands on the two horizontal axes and resolves them into one
 * net correction per axis.
 * <p>
 * The predecessor kept a single scalar per axis and took whichever push was <em>largest in magnitude</em>,
 * so two walls pushing opposite ways never cancelled - the bigger one won outright and the multi-pass
 * loop turned into a period-2 limit cycle that teleported the vehicle back and forth between the walls of
 * a narrow passage, twenty times a second.  Resolving both directions together fixes that:
 * <ul>
 *   <li><b>Contacts on one side only</b> - net is that push: the vehicle escapes the wall exactly as before.</li>
 *   <li><b>Contacts on both sides</b> (a gap genuinely narrower than the vehicle) - the vehicle cannot fit
 *       and must not be launched out of it, so the net is the midpoint of the two demands.  Each pass
 *       halves the remaining asymmetry, so the vehicle converges to sitting centred in the gap instead of
 *       oscillating.</li>
 * </ul>
 * Both accumulators are pure max/min, which are commutative: the result cannot depend on the order the
 * contacts arrive in.  That matters because MTS builds {@code allBlockCollisionBoxes} by iterating a
 * {@code HashSet}, so box order genuinely differs between the client and server JVMs - an order-sensitive
 * tie-break could resolve the same tick one way on the server and the other way on the client.
 */
public final class WallPushResolver {
    private double positiveX;
    private double negativeX;
    private double positiveZ;
    private double negativeZ;

    public void reset() {
        positiveX = 0;
        negativeX = 0;
        positiveZ = 0;
        negativeZ = 0;
    }

    /** Records a demanded push along X; sign gives the direction, magnitude the distance needed. */
    public void addPushX(double signedPush) {
        if (signedPush > positiveX) {
            positiveX = signedPush;
        }
        if (signedPush < negativeX) {
            negativeX = signedPush;
        }
    }

    /** Records a demanded push along Z; sign gives the direction, magnitude the distance needed. */
    public void addPushZ(double signedPush) {
        if (signedPush > positiveZ) {
            positiveZ = signedPush;
        }
        if (signedPush < negativeZ) {
            negativeZ = signedPush;
        }
    }

    public boolean isEmpty() {
        return positiveX == 0 && negativeX == 0 && positiveZ == 0 && negativeZ == 0;
    }

    /** True when contacts demand pushes both ways on X - the vehicle is wedged on that axis. */
    public boolean isWedgedX() {
        return positiveX > 0 && negativeX < 0;
    }

    public boolean isWedgedZ() {
        return positiveZ > 0 && negativeZ < 0;
    }

    public double netX() {
        return resolve(positiveX, negativeX);
    }

    public double netZ() {
        return resolve(positiveZ, negativeZ);
    }

    private static double resolve(double positive, double negative) {
        if (positive > 0 && negative < 0) {
            // Wedged: aim for the midpoint of the two demands rather than satisfying either one, so the
            // remaining asymmetry halves every pass instead of ping-ponging.
            return (positive + negative) * 0.5;
        }
        return positive + negative;   // one side only: exactly one term is non-zero
    }
}
