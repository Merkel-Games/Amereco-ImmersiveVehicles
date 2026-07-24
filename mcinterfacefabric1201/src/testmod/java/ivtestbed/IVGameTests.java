package ivtestbed;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ivbettercollisions.VehicleCollisionHandler;

import mcinterfacefabric1201.BuilderEntityExisting;
import mcinterfacefabric1201.BuilderItem;
import mcinterfacefabric1201.WrapperWorld;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.items.components.AItemBase;
import minecrafttransportsimulator.items.components.AItemPack;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.packloading.PackParser;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side gametests (fabric-gametest-api-v1).  Run headlessly with
 * -Dfabric-api.gametest against a test server with the OCP jar in mods/.
 */
public class IVGameTests implements FabricGameTest {

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void packItemsRegistered(GameTestHelper helper) {
        Set<String> packIDs = PackParser.getAllPackIDs();
        if (packIDs.stream().noneMatch(id -> !id.equals("mts"))) {
            helper.fail("No content pack loaded besides the core mts pack. Pack IDs: " + packIDs);
            return;
        }
        //Core mts alone registers ~40 items; the OCP pushes this into the hundreds, so a
        //threshold of 300 cleanly distinguishes "OCP loaded" from "core only".
        long mtsItems = BuiltInRegistries.ITEM.keySet().stream().filter(k -> k.getNamespace().equals("mts")).count();
        if (mtsItems < 300) {
            helper.fail("Expected the OCP to register hundreds of mts items, got only " + mtsItems);
            return;
        }
        helper.succeed();
    }

    /**
     * Regression guard for the null-entity raycast crash.  IV's {@code WrapperWorld.getBlockHit}
     * (used by bullet collision and third-person camera collision) builds a {@link ClipContext}
     * with a null entity, which crashes on vanilla 1.20.1 without our {@code ClipContextMixin}.
     * This exercises the exact vanilla call so a broken/missing mixin fails the suite instead of
     * the player's game.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void nullEntityRaycastDoesNotCrash(GameTestHelper helper) {
        Vec3 from = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 3, 1)));
        Vec3 to = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
        try {
            helper.getLevel().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, (Entity) null));
        } catch (NullPointerException e) {
            helper.fail("Null-entity ClipContext raycast crashed — ClipContextMixin not applied: " + e.getMessage());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
    public void vehicleSpawnAndNbtRoundtrip(GameTestHelper helper) {
        String contentPackID = PackParser.getAllPackIDs().stream().filter(id -> !id.equals("mts")).sorted().findFirst().orElse(null);
        if (contentPackID == null) {
            helper.fail("No content pack loaded");
            return;
        }
        ItemVehicle vehicleItem = BuilderItem.itemMap.keySet().stream()
                .filter(item -> item instanceof ItemVehicle && ((ItemVehicle) item).definition.packID.equals(contentPackID))
                .map(item -> (ItemVehicle) item)
                .min(Comparator.comparing(AItemBase::getRegistrationName))
                .orElse(null);
        if (vehicleItem == null) {
            helper.fail("Content pack " + contentPackID + " has no vehicles");
            return;
        }

        //Build a floor and use the real vehicle item on it, exercising the
        //item-use -> core placement -> entity spawn path.
        BlockPos floorRel = new BlockPos(4, 1, 4);
        helper.setBlock(floorRel.below(), Blocks.STONE);
        BlockPos floorAbs = helper.absolutePos(floorRel.below());

        Player player = helper.makeMockPlayer();
        ItemStack stack = new ItemStack(BuilderItem.itemMap.get(vehicleItem));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floorAbs).add(0, 0.5, 0), Direction.UP, floorAbs, false);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));

        helper.runAfterDelay(100, () -> {
            AABB search = new AABB(helper.absolutePos(new BlockPos(4, 2, 4))).inflate(24);
            List<BuilderEntityExisting> found = helper.getLevel().getEntitiesOfClass(BuilderEntityExisting.class, search);
            if (found.isEmpty()) {
                helper.fail("Vehicle " + vehicleItem.getRegistrationName() + " did not spawn a BuilderEntityExisting");
                return;
            }
            Entity vehicle = found.get(0);
            CompoundTag saved = new CompoundTag();
            if (!vehicle.save(saved) || saved.isEmpty()) {
                helper.fail("Vehicle entity produced an empty save tag");
                return;
            }
            Entity reloaded = EntityType.loadEntityRecursive(saved, helper.getLevel(), e -> e);
            if (reloaded == null || reloaded.getType() != vehicle.getType()) {
                helper.fail("Vehicle NBT did not round-trip through EntityType.loadEntityRecursive");
                return;
            }
            reloaded.discard();
            helper.succeed();
        });
    }

    /**
     * Validates the block-shape query that the Better Collisions wall pass is built on: a box overlapping
     * a solid block must report that block, and a box in open air must report nothing.  This is the
     * deterministic core of the wall push-out, independent of vehicle physics.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void solidBlockQueryDetectsWalls(GameTestHelper helper) {
        BlockPos rel = new BlockPos(2, 2, 2);
        helper.setBlock(rel, Blocks.STONE);
        BlockPos abs = helper.absolutePos(rel);
        WrapperWorld world = WrapperWorld.getWrapperFor(helper.getLevel());

        double cx = abs.getX() + 0.5, cy = abs.getY() + 0.5, cz = abs.getZ() + 0.5;
        List<double[]> hit = world.getSolidBlockCollisions(cx, cy, cz, 0.6, 0.6, 0.6);
        if (hit.isEmpty()) {
            helper.fail("getSolidBlockCollisions did not detect an overlapping stone block");
            return;
        }
        //A box floating well above the block must find nothing.
        List<double[]> miss = world.getSolidBlockCollisions(cx, cy + 6, cz, 0.4, 0.4, 0.4);
        if (!miss.isEmpty()) {
            helper.fail("getSolidBlockCollisions reported a collision in open air");
            return;
        }
        helper.succeed();
    }

    /**
     * Guards the "leave unfinished vehicles alone" rule: place a bare vehicle frame (no wheels, so
     * groundDeviceCollective.isReady() is false), embed a solid block column in its body, run several
     * collision passes, and assert it was NOT moved.  A frame with no ground devices can't be held up,
     * so shoving it every tick makes it thrash across the world (the reported bug); the readiness guard
     * in VehicleCollisionHandler#isCollidable must skip it.  If that guard is removed this test fails.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
    public void unfinishedVehicleNotShovedByCollisions(GameTestHelper helper) {
        String contentPackID = PackParser.getAllPackIDs().stream().filter(id -> !id.equals("mts")).sorted().findFirst().orElse(null);
        if (contentPackID == null) {
            helper.fail("No content pack loaded");
            return;
        }
        ItemVehicle vehicleItem = BuilderItem.itemMap.keySet().stream()
                .filter(item -> item instanceof ItemVehicle && ((ItemVehicle) item).definition.packID.equals(contentPackID))
                .map(item -> (ItemVehicle) item)
                .min(Comparator.comparing(AItemBase::getRegistrationName))
                .orElse(null);
        if (vehicleItem == null) {
            helper.fail("Content pack " + contentPackID + " has no vehicles");
            return;
        }

        //Lay a floor and place the vehicle on it via the real item-use path.
        for (int dx = 2; dx <= 6; dx++) {
            for (int dz = 2; dz <= 6; dz++) {
                helper.setBlock(new BlockPos(dx, 0, dz), Blocks.STONE);
            }
        }
        BlockPos floorRel = new BlockPos(4, 1, 4);
        BlockPos floorAbs = helper.absolutePos(floorRel.below());
        Player player = helper.makeMockPlayer();
        ItemStack stack = new ItemStack(BuilderItem.itemMap.get(vehicleItem));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floorAbs).add(0, 0.5, 0), Direction.UP, floorAbs, false);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));

        helper.runAfterDelay(80, () -> {
            EntityVehicleF_Physics vehicle = WrapperWorld.getWrapperFor(helper.getLevel())
                    .getEntitiesOfType(EntityVehicleF_Physics.class).stream().findFirst().orElse(null);
            if (vehicle == null) {
                helper.fail("Vehicle " + vehicleItem.getRegistrationName() + " did not spawn");
                return;
            }
            if (vehicle.allBlockCollisionBoxes.isEmpty()) {
                helper.fail("Spawned vehicle has no block collision boxes to test against");
                return;
            }
            vehicle.ticksExisted = 100;   //past the spawn grace period, so only the readiness guard gates us

            //A vehicle placed as a bare frame has no wheels, so groundDeviceCollective.isReady() is false.
            //Confirm that premise, then confirm the guard: the handler must leave such an unfinished vehicle
            //completely alone.  Shoving a frame that has no ground devices to hold it up makes it thrash
            //across the world (the reported bug), so even with a block embedded in it we must not move it.
            if (vehicle.groundDeviceCollective.isReady()) {
                helper.fail("Test premise broken: a freshly-placed bare frame unexpectedly reports ready");
                return;
            }

            //Embed a solid column in the vehicle body so at least one collision box is penetrating.
            int bx = (int) Math.floor(vehicle.position.x);
            int by = (int) Math.floor(vehicle.position.y);
            int bz = (int) Math.floor(vehicle.position.z);
            for (int dy = 0; dy <= 2; dy++) {
                helper.getLevel().setBlockAndUpdate(new BlockPos(bx, by + dy, bz), Blocks.STONE.defaultBlockState());
            }

            double beforeX = vehicle.position.x;
            double beforeZ = vehicle.position.z;
            for (int i = 0; i < 3; i++) {
                VehicleCollisionHandler.onWorldTickEnd(helper.getLevel(), false);
            }
            double movedX = Math.abs(vehicle.position.x - beforeX);
            double movedZ = Math.abs(vehicle.position.z - beforeZ);
            if (movedX > 0.05 || movedZ > 0.05) {
                helper.fail("Unfinished (not-ready) vehicle must not be shoved but moved (dx=" + movedX + ", dz=" + movedZ + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Verifies the IVClimbTweaks init hook applied the configured ground.climbHeight overrides to the OCP
     * wheel parts (huge=1.5, large=1.0, medium=1.0, small=0.5). If the hook did not run (or ran before the
     * pack was parsed), the wheels would still read the pack default 1.5 and this fails.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void ocpClimbHeightOverridden(GameTestHelper helper) {
        if (!PackParser.getAllPackIDs().contains("mtsofficialpack")) {
            helper.fail("OCP (mtsofficialpack) not loaded");
            return;
        }
        Map<String, Float> expected = new HashMap<>();
        expected.put("wheelhuge", 1.5F);
        expected.put("wheellarge", 1.0F);
        expected.put("wheelmedium", 1.0F);
        expected.put("wheelsmall", 0.5F);

        int checked = 0;
        for (AItemPack<?> item : PackParser.getAllItemsForPack("mtsofficialpack", false)) {
            Float want = expected.get(item.definition.systemName);
            if (want != null && item.definition instanceof JSONPart) {
                JSONPart part = (JSONPart) item.definition;
                if (part.ground == null) {
                    helper.fail("OCP " + item.definition.systemName + " unexpectedly has no ground device");
                    return;
                }
                if (Math.abs(part.ground.climbHeight - want) > 1.0e-4) {
                    helper.fail("OCP " + item.definition.systemName + " climbHeight=" + part.ground.climbHeight
                            + ", expected " + want + " (IVClimbTweaks hook did not apply)");
                    return;
                }
                ++checked;
            }
        }
        if (checked < expected.size()) {
            helper.fail("Only matched " + checked + "/" + expected.size() + " OCP wheels; systemNames changed?");
            return;
        }
        helper.succeed();
    }
}
