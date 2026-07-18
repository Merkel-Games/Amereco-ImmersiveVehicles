package com.ivbettercollisions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mcinterfacefabric1201.WrapperWorld;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.world.level.Level;

/**
 * The single point of coupling between the loader-agnostic Better Collisions logic and the Immersive
 * Vehicles Fabric port ({@code mcinterfacefabric1201}).  Everything else in {@code com.ivbettercollisions}
 * depends only on the Fabric API and the public IV / {@code mccore} classes.
 * <p>
 * If this package is ever lifted out into a standalone Fabric addon, this is the <em>only</em> class that
 * needs to change: a standalone addon would resolve MTS vehicles and block shapes through IV's public API
 * (or the builder entity via reflection), exactly as the original Forge addon does.
 */
public final class MtsAccess {
    private MtsAccess() {
    }

    /**
     * A snapshot list of every MTS physics vehicle currently loaded in the given world, or an empty list
     * if the world has no MTS wrapper yet.  Never creates a wrapper (no side effects).
     */
    public static List<EntityVehicleF_Physics> getVehicles(Level level) {
        WrapperWorld world = WrapperWorld.getWrapperIfPresent(level);
        if (world == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(world.getEntitiesOfType(EntityVehicleF_Physics.class));
    }

    /**
     * Solid-block collision AABBs ({@code {minX, minY, minZ, maxX, maxY, maxZ}}) overlapping the world-space
     * box defined by the passed-in centre and half-extents.  Leaves and liquids are excluded.  Read-only.
     */
    public static List<double[]> getSolidBlockCollisions(Level level, double cx, double cy, double cz, double hx, double hy, double hz) {
        WrapperWorld world = WrapperWorld.getWrapperIfPresent(level);
        if (world == null) {
            return Collections.emptyList();
        }
        return world.getSolidBlockCollisions(cx, cy, cz, hx, hy, hz);
    }
}
