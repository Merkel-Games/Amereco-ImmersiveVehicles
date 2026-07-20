package com.ivclimbtweaks;

import java.util.Map;

import minecrafttransportsimulator.items.components.AItemPack;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.packloading.PackParser;

/**
 * Applies the configured {@code ground.climbHeight} overrides to a content pack's ground-device parts
 * (wheels/treads/skids/pontoons) after the pack has been parsed.
 * <p>
 * This mutates the shared, live {@link JSONPart} definition instances that the physics uses (the entity,
 * the item, and PackParser all hold the same instance - no clone), so it changes climb behaviour for every
 * vehicle using those parts, on both server and client, without editing/forking the pack jar. It uses only
 * loader-agnostic {@code mccore} APIs ({@link PackParser}), so it has no tie to the Fabric port and could
 * be lifted into a standalone addon unchanged.
 */
public final class ClimbHeightOverrider {
    private ClimbHeightOverrider() {
    }

    /**
     * Runs the override pass. Safe to call once, after {@link PackParser#parsePacks} has completed. Never
     * throws; logs what it did (or why it did nothing).
     */
    public static void apply() {
        if (!ClimbConfig.enabled || ClimbConfig.climbHeights.isEmpty()) {
            return;
        }
        String packID = ClimbConfig.packID;
        if (!PackParser.getAllPackIDs().contains(packID)) {
            IVClimbTweaks.LOGGER.warn("[IVClimb] Pack '{}' is not loaded - no climb-height overrides applied", packID);
            return;
        }

        int changed = 0;
        for (AItemPack<?> item : PackParser.getAllItemsForPack(packID, false)) {
            Double target = ClimbConfig.climbHeights.get(item.definition.systemName);
            if (target != null && item.definition instanceof JSONPart) {
                JSONPart part = (JSONPart) item.definition;
                if (part.ground != null) {
                    part.ground.climbHeight = target.floatValue();
                    ++changed;
                }
            }
        }
        IVClimbTweaks.LOGGER.info("[IVClimb] Applied climbHeight overrides to {} ground part(s) in pack '{}': {}",
                changed, packID, mapForLog());
    }

    private static String mapForLog() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : ClimbConfig.climbHeights.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
