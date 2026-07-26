package com.ivbettercollisions;

import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup.CollisionType;

/**
 * Classifies a vehicle hitbox by the colour MTS draws it in when vanilla hitbox rendering (F3+B) is on.
 * <p>
 * Packs are wildly inconsistent about which collision types a vehicle's bodywork ends up tagged with.
 * No Official Content Pack vehicle declares BLOCK explicitly - every BLOCK box comes from the legacy
 * default, which skips BLOCK entirely for groups flagged {@code isInterior}, and most of the car groups
 * that would get it are wheel-stub groups gated on {@code part_present_N} that exist only while the wheel
 * is missing.  A stock Ford Mustang is therefore left with six permanent BLOCK boxes (rear bumper and
 * roof strips), a Mercedes with four roof strips, and a quad with none at all - so following MTS's own
 * BLOCK filter made such a car collide correctly in reverse and drive straight through walls going
 * forwards.  Selecting the box set by colour lets a pack like that be fixed from the config, using
 * exactly the colours the player sees under F3+B.
 * <p>
 * The check order below mirrors {@code BoundingBox.renderWireframe} exactly - including that a box tagged
 * both BULLET and BLOCK renders (and therefore classifies) as {@link #ORANGE} - so a colour named in the
 * config always means the same boxes the player sees in that colour.
 */
public enum BoxCategory {
    /** Interactive hitboxes: doors, hatches, buttons.  Anything with a click action. */
    GREEN,
    /** Bullet-only hitboxes, used by guns rather than by the bodywork. */
    ORANGE,
    /** Boxes MTS itself uses for world collision. */
    RED,
    /** Ordinary bodywork boxes tagged with something other than BLOCK/BULLET. */
    BLACK,
    /**
     * Boxes with no JSON definition - generated rather than authored, and present in a vehicle's
     * {@code allCollisionBoxes} in some numbers (a Bell 206 carries 21).  Excluded by default: they are
     * not bodywork.  Note the generated ground-device boxes are not among them; MTS appends those
     * straight to {@code allBlockCollisionBoxes}, and renders them blue via a forced colour.
     */
    YELLOW;

    /** Classifies a box exactly as MTS colours it in the F3+B hitbox view. */
    public static BoxCategory of(BoundingBox box) {
        if (box.definition == null) {
            return YELLOW;
        }
        if (box.definition.action != null) {
            return GREEN;
        }
        if (box.groupDef != null && box.groupDef.collisionTypes != null) {
            if (box.groupDef.collisionTypes.contains(CollisionType.BULLET)) {
                return ORANGE;
            }
            if (box.groupDef.collisionTypes.contains(CollisionType.BLOCK)) {
                return RED;
            }
        }
        return BLACK;
    }
}
