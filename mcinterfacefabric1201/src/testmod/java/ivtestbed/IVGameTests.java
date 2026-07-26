package ivtestbed;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ivbettercollisions.BoxCategory;
import com.ivbettercollisions.CollisionConfig;
import com.ivbettercollisions.CollisionMath;
import com.ivbettercollisions.ContactManifold;
import com.ivbettercollisions.VehicleCollisionHandler;
import com.ivbettercollisions.VehicleCollisionPass;
import com.ivbettercollisions.WallPushResolver;

import mcinterfacefabric1201.BuilderEntityExisting;
import mcinterfacefabric1201.BuilderItem;
import mcinterfacefabric1201.WrapperWorld;
import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.items.components.AItemBase;
import minecrafttransportsimulator.items.components.AItemPack;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.jsondefs.JSONAction;
import minecrafttransportsimulator.jsondefs.JSONCollisionBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup.CollisionType;
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

    /**
     * Pure-math checks for the Ultimate Collisions impulse model: mass-weighted split, momentum
     * conservation, separating pairs, restitution scaling and the per-vehicle delta-v clamp.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void collisionMathImpulse(GameTestHelper helper) {
        //Equal masses, head-on at closing speed 1, e=0: each side sheds 0.5.
        double j = CollisionMath.impulseMagnitude(-1.0, 0, 1000, 1000);
        if (Math.abs(j / 1000 - 0.5) > 1.0e-9) {
            helper.fail("Equal-mass impulse split wrong: dv=" + (j / 1000));
            return;
        }
        //10:1 mass ratio: the light vehicle takes 10x the delta-v, total closing speed fully removed.
        double j2 = CollisionMath.impulseMagnitude(-1.0, 0, 1000, 100);
        double dvHeavy = j2 / 1000;
        double dvLight = j2 / 100;
        if (Math.abs(dvLight / dvHeavy - 10.0) > 1.0e-9 || Math.abs(dvHeavy + dvLight - 1.0) > 1.0e-9) {
            helper.fail("Mass-ratio impulse wrong: dvHeavy=" + dvHeavy + " dvLight=" + dvLight);
            return;
        }
        //Separating pair gets no impulse.
        if (CollisionMath.impulseMagnitude(0.5, 0.25, 1000, 1000) != 0) {
            helper.fail("Separating pair received an impulse");
            return;
        }
        //Restitution scales the impulse by (1+e).
        double j3 = CollisionMath.impulseMagnitude(-1.0, 0.25, 1000, 1000);
        if (Math.abs(j3 / j - 1.25) > 1.0e-9) {
            helper.fail("Restitution scaling wrong: ratio=" + (j3 / j));
            return;
        }
        //Clamp caps the lighter vehicle's delta-v.
        double clamped = CollisionMath.clampImpulse(1.0e9, 1000, 100, 1.5);
        if (Math.abs(clamped - 1.5 * 100) > 1.0e-9) {
            helper.fail("Impulse clamp wrong: " + clamped);
            return;
        }
        helper.succeed();
    }

    /**
     * Contact-manifold math: depth-weighted normal blending and contact points, corner blending,
     * degenerate fallbacks, and the vertical (stacking) contact filter of the box narrow phase.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void collisionMathManifold(GameTestHelper helper) {
        ContactManifold m = new ContactManifold();

        //Two +X contacts blend to a +X normal; contact point and penetration are depth-weighted.
        m.reset();
        m.addContact(1, 0, 0.2, 10, 64, 5);
        m.addContact(1, 0, 0.1, 10, 64, 8);
        if (!m.finalizeManifold(new Point3D()) || Math.abs(m.normal.x - 1) > 1.0e-9 || Math.abs(m.normal.z) > 1.0e-9
                || Math.abs(m.contact.z - 6.0) > 1.0e-9 || Math.abs(m.penetration - 0.2) > 1.0e-9) {
            helper.fail("Basic manifold blend wrong: n=" + m.normal + " c=" + m.contact + " pen=" + m.penetration);
            return;
        }
        //Equal +X and +Z contacts blend to a diagonal normal (corner hit).
        m.reset();
        m.addContact(1, 0, 0.1, 0, 0, 0);
        m.addContact(0, 1, 0.1, 0, 0, 0);
        m.finalizeManifold(new Point3D());
        if (Math.abs(m.normal.x - Math.sqrt(0.5)) > 1.0e-6 || Math.abs(m.normal.z - Math.sqrt(0.5)) > 1.0e-6) {
            helper.fail("Corner blend wrong: n=" + m.normal);
            return;
        }
        //Opposing MTVs cancel: the fallback direction takes over.
        m.reset();
        m.addContact(1, 0, 0.1, 0, 0, 0);
        m.addContact(-1, 0, 0.1, 0, 0, 0);
        m.finalizeManifold(new Point3D(0, 0, 4));
        if (m.impulseSkipped || Math.abs(m.normal.z - 1) > 1.0e-9) {
            helper.fail("Fallback normal wrong: n=" + m.normal + " skipped=" + m.impulseSkipped);
            return;
        }
        //Cancelled MTVs plus a zero fallback: +X default with the impulse flagged skipped.
        m.reset();
        m.addContact(1, 0, 0.1, 0, 0, 0);
        m.addContact(-1, 0, 0.1, 0, 0, 0);
        m.finalizeManifold(new Point3D());
        if (!m.impulseSkipped || Math.abs(m.normal.x - 1) > 1.0e-9) {
            helper.fail("Degenerate handling wrong: n=" + m.normal + " skipped=" + m.impulseSkipped);
            return;
        }
        //Narrow phase: a mostly-vertical (stacking) overlap must be filtered out entirely...
        BoundingBox base = new BoundingBox(new Point3D(0, 64, 0), 1, 0.5, 1);
        BoundingBox stacked = new BoundingBox(new Point3D(0, 64.9, 0), 1, 0.5, 1);
        m.reset();
        VehicleCollisionPass.collectContacts(List.of(base), List.of(stacked), m);
        if (!m.isEmpty()) {
            helper.fail("Stacking contact was not filtered");
            return;
        }
        //...while a genuine side contact yields the expected +X MTV.
        BoundingBox side = new BoundingBox(new Point3D(1.8, 64, 0), 1, 0.5, 1);
        m.reset();
        VehicleCollisionPass.collectContacts(List.of(base), List.of(side), m);
        if (m.isEmpty()) {
            helper.fail("Side contact not detected");
            return;
        }
        m.finalizeManifold(new Point3D());
        if (Math.abs(m.normal.x - 1) > 1.0e-9 || Math.abs(m.penetration - 0.2) > 1.0e-6) {
            helper.fail("Side contact MTV wrong: n=" + m.normal + " pen=" + m.penetration);
            return;
        }
        helper.succeed();
    }

    /**
     * Wall impact response math: head-on leaves only the restitution rebound, a shallow graze keeps
     * (1-friction) of the tangential speed, vertical motion is preserved, and the off-centre yaw
     * impulse flips sign with the lever-arm side and honors its cap.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void collisionMathWallResponse(GameTestHelper helper) {
        Point3D n = new Point3D(1, 0, 0);
        Point3D out = new Point3D();

        //Head-on at speed 1 with e=0.2: outgoing normal speed 0.2, no tangential, y preserved.
        CollisionMath.wallResponse(new Point3D(-1.0, -0.05, 0), n, 0.2, 0.25, out);
        if (Math.abs(out.x - 0.2) > 1.0e-9 || Math.abs(out.z) > 1.0e-9 || Math.abs(out.y + 0.05) > 1.0e-9) {
            helper.fail("Head-on response wrong: " + out);
            return;
        }
        //20-degree graze with friction 0.25: tangential keeps 75%, normal component removed.
        CollisionMath.wallResponse(new Point3D(-0.342, 0, 0.940), n, 0, 0.25, out);
        if (Math.abs(out.z - 0.940 * 0.75) > 1.0e-6 || Math.abs(out.x) > 1.0e-9) {
            helper.fail("Graze response wrong: " + out);
            return;
        }
        //Yaw flips sign with the lever side, equal magnitude (and is mass-independent by construction).
        double yawFront = CollisionMath.wallYawDegrees(new Point3D(0, 0, 2), n, 0.5, 0.2, 1.0, 45);
        double yawBack = CollisionMath.wallYawDegrees(new Point3D(0, 0, -2), n, 0.5, 0.2, 1.0, 45);
        if (yawFront == 0 || Math.abs(yawFront + yawBack) > 1.0e-9) {
            helper.fail("Yaw handedness wrong: front=" + yawFront + " back=" + yawBack);
            return;
        }
        //Cap honored on an extreme lever.
        double capped = CollisionMath.wallYawDegrees(new Point3D(0, 0, 100), n, 5, 0.2, 1.0, 25);
        if (Math.abs(capped) > 25 + 1.0e-9) {
            helper.fail("Yaw cap not honored: " + capped);
            return;
        }
        helper.succeed();
    }

    /**
     * Runs the box narrow phase against a REAL vehicle's block hitbox set (a bare placed frame - no
     * readiness needed, we call the pure functions directly) and a probe box clipping it, asserting a
     * sane manifold: contacts found, unit normal, positive penetration.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
    public void manifoldOnRealVehicleBoxes(GameTestHelper helper) {
        ItemVehicle vehicleItem = firstVehicleItem(helper);
        if (vehicleItem == null) {
            return;
        }
        placeVehicle(helper, vehicleItem, new BlockPos(4, 1, 4));
        helper.runAfterDelay(80, () -> {
            EntityVehicleF_Physics vehicle = vehicleNear(helper, new BlockPos(4, 1, 4), 8);
            if (vehicle == null) {
                helper.fail("Vehicle did not spawn");
                return;
            }
            if (vehicle.allBlockCollisionBoxes.isEmpty()) {
                helper.fail("Spawned vehicle has no block collision boxes");
                return;
            }
            BoundingBox probe = new BoundingBox(vehicle.position.copy().add(1.0, 0.5, 0), 1.5, 3, 3);
            ContactManifold m = new ContactManifold();
            m.reset();
            VehicleCollisionPass.collectContacts(vehicle.allBlockCollisionBoxes, List.of(probe), m);
            if (m.isEmpty()) {
                helper.fail("No contacts between real vehicle boxes and probe");
                return;
            }
            m.finalizeManifold(new Point3D(1, 0, 0));
            double length = Math.hypot(m.normal.x, m.normal.z);
            if (Math.abs(length - 1) > 1.0e-6 || m.penetration <= 0) {
                helper.fail("Manifold not sane: |n|=" + length + " pen=" + m.penetration);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Extends the unfinished-vehicle guard to the v2v path: two bare frames placed side by side (no
     * wheels, not ready) must not be shoved by the collision system - by the per-tick event handler or
     * by explicit passes - no matter how their boxes overlap.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
    public void overlappingUnfinishedVehiclesNotMoved(GameTestHelper helper) {
        ItemVehicle vehicleItem = firstVehicleItem(helper);
        if (vehicleItem == null) {
            return;
        }
        placeVehicle(helper, vehicleItem, new BlockPos(3, 1, 4));
        placeVehicle(helper, vehicleItem, new BlockPos(5, 1, 4));
        helper.runAfterDelay(80, () -> {
            List<EntityVehicleF_Physics> nearby = WrapperWorld.getWrapperFor(helper.getLevel())
                    .getEntitiesOfType(EntityVehicleF_Physics.class).stream()
                    .filter(v -> v.position.distanceTo(toPoint(helper, new BlockPos(4, 1, 4))) < 8)
                    .toList();
            if (nearby.size() < 2) {
                helper.fail("Expected 2 frames near the test area, found " + nearby.size());
                return;
            }
            for (EntityVehicleF_Physics vehicle : nearby) {
                vehicle.ticksExisted = 100;   //past the spawn grace period, so only the readiness guard gates us
            }
            double[][] before = new double[nearby.size()][2];
            for (int i = 0; i < nearby.size(); i++) {
                before[i][0] = nearby.get(i).position.x;
                before[i][1] = nearby.get(i).position.z;
            }
            for (int i = 0; i < 3; i++) {
                VehicleCollisionHandler.onWorldTickEnd(helper.getLevel(), false);
            }
            for (int i = 0; i < nearby.size(); i++) {
                double moved = Math.hypot(nearby.get(i).position.x - before[i][0], nearby.get(i).position.z - before[i][1]);
                if (moved > 0.05) {
                    helper.fail("Unfinished frame " + i + " was shoved " + moved + " blocks");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * Regression for the narrow-passage phantom collision: a vehicle driving down a corridor WIDER than
     * itself must not be touched at all.  The predecessor inflated each hitbox by a fixed margin and
     * reused that inflated overlap as the push depth, so both walls "collided" at once, the larger push
     * won outright, and the multi-pass loop became a period-2 cycle that flung the vehicle wall to wall
     * (traced at +-0.78 blocks, 20x a second).  Simulated here directly on the true-box geometry.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void wallCorridorStability(GameTestHelper helper) {
        //Corridor free span x in [1,4]; vehicle half-width 1.25 (2.5 wide) => 0.25 clearance per side.
        double wallWestMax = 1.0, wallEastMin = 4.0, halfWidth = 1.25;
        double centre = 2.5;
        for (int tick = 0; tick < 20; tick++) {
            WallPushResolver resolver = new WallPushResolver();
            double westOverlap = CollisionMath.overlap(centre - halfWidth, centre + halfWidth, -1, wallWestMax);
            if (westOverlap > 0) {
                resolver.addPushX(westOverlap);
            }
            double eastOverlap = CollisionMath.overlap(centre - halfWidth, centre + halfWidth, wallEastMin, 6);
            if (eastOverlap > 0) {
                resolver.addPushX(-eastOverlap);
            }
            centre += resolver.netX();
        }
        if (Math.abs(centre - 2.5) > 1.0e-9) {
            helper.fail("Vehicle in an over-wide corridor was moved to " + centre + " (expected to stay at 2.5)");
            return;
        }
        helper.succeed();
    }

    /**
     * A gap genuinely narrower than the vehicle must settle it centred rather than launching it: each
     * pass halves the remaining asymmetry instead of satisfying one wall at the other's expense.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void wallWedgedNoTeleport(GameTestHelper helper) {
        double wallWestMax = 1.0, wallEastMin = 3.0, halfWidth = 1.25;   //2.0 gap, 2.5 wide vehicle
        double centre = 1.9;   //off-centre start
        double maxStep = 0;
        for (int tick = 0; tick < 20; tick++) {
            WallPushResolver resolver = new WallPushResolver();
            double westOverlap = CollisionMath.overlap(centre - halfWidth, centre + halfWidth, -1, wallWestMax);
            if (westOverlap > 0) {
                resolver.addPushX(westOverlap);
            }
            double eastOverlap = CollisionMath.overlap(centre - halfWidth, centre + halfWidth, wallEastMin, 6);
            if (eastOverlap > 0) {
                resolver.addPushX(-eastOverlap);
            }
            if (!resolver.isWedgedX()) {
                helper.fail("Vehicle in an undersized gap should report wedged");
                return;
            }
            double step = resolver.netX();
            maxStep = Math.max(maxStep, Math.abs(step));
            centre += step;
        }
        if (maxStep > 0.3) {
            helper.fail("Wedged vehicle was teleported by " + maxStep + " blocks in one pass");
            return;
        }
        if (Math.abs(centre - 2.0) > 1.0e-6) {
            helper.fail("Wedged vehicle settled at " + centre + ", expected centred at 2.0");
            return;
        }
        helper.succeed();
    }

    /**
     * Push resolution must not depend on the order contacts arrive in.  MTS builds
     * allBlockCollisionBoxes by iterating a HashSet, so box order genuinely differs between the client
     * and server JVMs; an order-sensitive tie-break would resolve the same tick one way on the server and
     * the other on the client, producing rubber-band on top of the jolt.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void wallPushOrderIndependent(GameTestHelper helper) {
        double[] pushes = {0.4, -0.25, 0.1, -0.4, 0.35};
        WallPushResolver forward = new WallPushResolver();
        for (double push : pushes) {
            forward.addPushX(push);
            forward.addPushZ(push * 0.5);
        }
        WallPushResolver backward = new WallPushResolver();
        for (int i = pushes.length - 1; i >= 0; i--) {
            backward.addPushX(pushes[i]);
            backward.addPushZ(pushes[i] * 0.5);
        }
        if (Math.abs(forward.netX() - backward.netX()) > 1.0e-12 || Math.abs(forward.netZ() - backward.netZ()) > 1.0e-12) {
            helper.fail("Push resolution is order-dependent: " + forward.netX() + " vs " + backward.netX());
            return;
        }
        //A single-sided set must still escape fully (no halving when not wedged).
        WallPushResolver oneSided = new WallPushResolver();
        oneSided.addPushX(0.2);
        oneSided.addPushX(0.5);
        if (oneSided.isWedgedX() || Math.abs(oneSided.netX() - 0.5) > 1.0e-12) {
            helper.fail("Single-sided push should be the deepest demand, got " + oneSided.netX());
            return;
        }
        helper.succeed();
    }

    /**
     * The block a vehicle is driving OVER must never produce a horizontal shove.  Its vertical overlap is
     * the sink depth (tiny) while the horizontal overlaps are the full block footprint, so resolving it
     * on a horizontal axis would fling the vehicle a whole block sideways - the "collides with nothing on
     * flat ground" report.  Reachable in practice because a wheel jammed against a kerb is excluded from
     * MTS's climb correction yet still added to the collision box list.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void wallFloorContactFiltered(GameTestHelper helper) {
        //Box sunk 0.001 into the floor block directly beneath it.
        if (!CollisionMath.isVerticalContact(1.0, 0.001, 1.0)) {
            helper.fail("Floor contact under the vehicle was not classified as vertical");
            return;
        }
        //A genuine side wall: deep vertical overlap, shallow horizontal penetration.
        if (CollisionMath.isVerticalContact(0.05, 1.0, 1.0)) {
            helper.fail("Side wall contact was misclassified as vertical and would be ignored");
            return;
        }
        helper.succeed();
    }

    /**
     * The impact probe must reach only along the direction of travel: a wall running parallel to the
     * vehicle's path (a corridor side) can then never enter it, which is what makes the phantom
     * structurally impossible rather than merely tuned away.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void wallSweptProbeIsDirectional(GameTestHelper helper) {
        double speedFactor = 0.35;
        //Driving north (-Z) at 1.0 m/tick: Z expands, X does not.
        double expZ = CollisionMath.sweptExpansion(-1.0, speedFactor, 0.75);
        double expX = CollisionMath.sweptExpansion(0.0, speedFactor, 0.75);
        if (expZ >= 0 || Math.abs(expZ + 0.35) > 1.0e-9) {
            helper.fail("Travel-axis expansion wrong: " + expZ);
            return;
        }
        if (expX != 0) {
            helper.fail("Cross-axis expansion must be zero, got " + expX);
            return;
        }
        //Box x in [-1,1]; a corridor wall at x in [1.2, 2.2] is 0.2 clear and must NOT be probed...
        double sweptMinX = -1 + Math.min(expX, 0), sweptMaxX = 1 + Math.max(expX, 0);
        if (CollisionMath.overlap(sweptMinX, sweptMaxX, 1.2, 2.2) > 0) {
            helper.fail("Parallel corridor wall entered the swept probe");
            return;
        }
        //...while a wall ahead at z in [-1.3,-0.3], 0.3 clear of a box z in [-1,1] shifted by travel, IS.
        double sweptMinZ = -1 + Math.min(expZ, 0), sweptMaxZ = 1 + Math.max(expZ, 0);
        if (CollisionMath.overlap(sweptMinZ, sweptMaxZ, -1.3, -1.05) <= 0) {
            helper.fail("Wall ahead was not reached by the swept probe");
            return;
        }
        //Probe distance is capped.
        if (CollisionMath.sweptExpansion(100, speedFactor, 0.75) != 0.75) {
            helper.fail("Probe distance cap not honored");
            return;
        }
        helper.succeed();
    }

    /**
     * Kerbs, doorsteps and slabs are driven over by MTS's climb system, so they must not trigger a bounce
     * or a yaw kick on the way; anything taller than the climb height still counts as a wall.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void wallCurbSkipsImpact(GameTestHelper helper) {
        double boxBottom = 64.0;
        if (!CollisionMath.isClimbable(64.5, boxBottom, 1.0)) {
            helper.fail("Half-slab kerb should be climbable");
            return;
        }
        if (!CollisionMath.isClimbable(65.0, boxBottom, 1.0)) {
            helper.fail("Full block at exactly the climb height should be climbable");
            return;
        }
        if (CollisionMath.isClimbable(66.0, boxBottom, 1.0)) {
            helper.fail("A two-block wall must not be treated as a kerb");
            return;
        }
        helper.succeed();
    }

    /**
     * The addon picks its bodywork hitboxes by the colour MTS draws them in, so the classifier must match
     * {@code BoundingBox.renderWireframe}'s check order exactly - otherwise a colour named in the config
     * would not mean the boxes the player sees in that colour.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void boxCategoryMatchesRenderOrder(GameTestHelper helper) {
        if (BoxCategory.of(box(null, CollisionType.BLOCK)) != BoxCategory.RED) {
            helper.fail("BLOCK box should be RED");
            return;
        }
        if (BoxCategory.of(box(null, CollisionType.BULLET)) != BoxCategory.ORANGE) {
            helper.fail("BULLET box should be ORANGE");
            return;
        }
        //Render checks BULLET before BLOCK, so a box tagged both draws orange - classify it the same way.
        if (BoxCategory.of(box(null, CollisionType.BULLET, CollisionType.BLOCK)) != BoxCategory.ORANGE) {
            helper.fail("BULLET+BLOCK box should be ORANGE, matching the render order");
            return;
        }
        //Ordinary bodywork: tagged, but with neither BLOCK nor BULLET.
        if (BoxCategory.of(box(null, CollisionType.ENTITY, CollisionType.ATTACK, CollisionType.CLICK)) != BoxCategory.BLACK) {
            helper.fail("ENTITY/ATTACK/CLICK box should be BLACK");
            return;
        }
        //An action wins over every collision type, including BLOCK.
        if (BoxCategory.of(box(new JSONAction(), CollisionType.BLOCK)) != BoxCategory.GREEN) {
            helper.fail("A box with an action should be GREEN even when tagged BLOCK");
            return;
        }
        //No JSON definition at all.
        BoundingBox generated = new BoundingBox(new Point3D(0, 64, 0), 1, 1, 1);
        if (BoxCategory.of(generated) != BoxCategory.YELLOW) {
            helper.fail("A box with no definition should be YELLOW");
            return;
        }
        helper.succeed();
    }

    /**
     * The shipped default must select the bodywork colours (red plus black) and nothing else: green is
     * interactive trim, orange is bullet-only, yellow is not real bodywork.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void boxCategoryDefaultSelection(GameTestHelper helper) {
        Set<BoxCategory> selected = CollisionConfig.collisionBoxColors;
        if (!selected.contains(BoxCategory.RED) || !selected.contains(BoxCategory.BLACK)) {
            helper.fail("Default selection must include RED and BLACK, got " + selected);
            return;
        }
        if (selected.contains(BoxCategory.GREEN) || selected.contains(BoxCategory.ORANGE) || selected.contains(BoxCategory.YELLOW)) {
            helper.fail("Default selection must exclude GREEN/ORANGE/YELLOW, got " + selected);
            return;
        }
        helper.succeed();
    }

    /**
     * The point of the whole change, measured on a real vehicle: selecting by colour must yield strictly
     * more bodywork than MTS's own BLOCK filter did.  Pack cars carry BLOCK on only a few permanent boxes
     * (a Mustang has six - rear bumper and roof strips - because the rest of its BLOCK groups are
     * wheel-stubs that vanish once wheels are fitted), which is why they used to collide in reverse and
     * drive straight through walls going forwards.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
    public void boxSelectionCoversMoreThanBlockOnly(GameTestHelper helper) {
        ItemVehicle vehicleItem = firstVehicleItem(helper);
        if (vehicleItem == null) {
            return;
        }
        placeVehicle(helper, vehicleItem, new BlockPos(4, 1, 4));
        helper.runAfterDelay(80, () -> {
            EntityVehicleF_Physics vehicle = vehicleNear(helper, new BlockPos(4, 1, 4), 8);
            if (vehicle == null) {
                helper.fail("Vehicle did not spawn");
                return;
            }
            int blockOnly = 0;
            int selected = 0;
            Map<BoxCategory, Integer> byCategory = new HashMap<>();
            for (BoundingBox box : vehicle.allCollisionBoxes) {
                BoxCategory category = BoxCategory.of(box);
                byCategory.merge(category, 1, Integer::sum);
                if (box.collisionTypes != null && box.collisionTypes.contains(CollisionType.BLOCK)) {
                    ++blockOnly;
                }
                if (CollisionConfig.collisionBoxColors.contains(category)) {
                    ++selected;
                }
            }
            System.out.println("[IVTEST] " + vehicleItem.definition.systemName + " boxes by category: " + byCategory
                    + " | BLOCK-only=" + blockOnly + " selected=" + selected);
            if (selected == 0) {
                helper.fail("Colour selection produced no bodywork boxes at all");
                return;
            }
            if (selected < blockOnly) {
                helper.fail("Colour selection (" + selected + ") lost boxes the BLOCK filter had (" + blockOnly + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** Builds a JSON-backed box in one collision group, for classifier tests. */
    private static BoundingBox box(JSONAction action, CollisionType... types) {
        JSONCollisionBox definition = new JSONCollisionBox();
        definition.pos = new Point3D();
        definition.width = 1;
        definition.height = 1;
        definition.action = action;
        JSONCollisionGroup group = new JSONCollisionGroup();
        group.collisionTypes = new HashSet<>(List.of(types));
        return new BoundingBox(definition, group);
    }

    // ---- shared helpers for the vehicle-placement tests -------------------------------------------

    /** First (alphabetically) vehicle item of the first non-mts pack; fails the test and returns null if absent. */
    private static ItemVehicle firstVehicleItem(GameTestHelper helper) {
        String contentPackID = PackParser.getAllPackIDs().stream().filter(id -> !id.equals("mts")).sorted().findFirst().orElse(null);
        if (contentPackID == null) {
            helper.fail("No content pack loaded");
            return null;
        }
        ItemVehicle vehicleItem = BuilderItem.itemMap.keySet().stream()
                .filter(item -> item instanceof ItemVehicle && ((ItemVehicle) item).definition.packID.equals(contentPackID))
                .map(item -> (ItemVehicle) item)
                .min(Comparator.comparing(AItemBase::getRegistrationName))
                .orElse(null);
        if (vehicleItem == null) {
            helper.fail("Content pack " + contentPackID + " has no vehicles");
        }
        return vehicleItem;
    }

    /** Lays a stone floor around the position and places the vehicle on it via the real item-use path. */
    private static void placeVehicle(GameTestHelper helper, ItemVehicle vehicleItem, BlockPos floorRel) {
        for (int dx = 2; dx <= 6; dx++) {
            for (int dz = 2; dz <= 6; dz++) {
                helper.setBlock(new BlockPos(dx, 0, dz), Blocks.STONE);
            }
        }
        BlockPos floorAbs = helper.absolutePos(floorRel.below());
        Player player = helper.makeMockPlayer();
        ItemStack stack = new ItemStack(BuilderItem.itemMap.get(vehicleItem));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floorAbs).add(0, 0.5, 0), Direction.UP, floorAbs, false);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    /** Closest spawned vehicle within maxDistance of the structure-relative position, or null. */
    private static EntityVehicleF_Physics vehicleNear(GameTestHelper helper, BlockPos rel, double maxDistance) {
        Point3D target = toPoint(helper, rel);
        return WrapperWorld.getWrapperFor(helper.getLevel()).getEntitiesOfType(EntityVehicleF_Physics.class).stream()
                .filter(v -> v.position.distanceTo(target) < maxDistance)
                .min(Comparator.comparingDouble(v -> v.position.distanceTo(target)))
                .orElse(null);
    }

    private static Point3D toPoint(GameTestHelper helper, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        return new Point3D(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
    }
}
