package ivtestbed;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import mcinterfacefabric1201.BuilderEntityExisting;
import mcinterfacefabric1201.BuilderItem;
import minecrafttransportsimulator.items.components.AItemBase;
import minecrafttransportsimulator.items.instances.ItemVehicle;
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
}
