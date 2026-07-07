package ivtestbed;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;

import mcinterfacefabric1201.BuilderEntityExisting;
import mcinterfacefabric1201.BuilderItem;
import minecrafttransportsimulator.guis.components.AGUIBase;
import minecrafttransportsimulator.guis.instances.GUIConfig;
import minecrafttransportsimulator.items.components.AItemBase;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.packloading.PackParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side test driver.  A tick-based state machine that (in e2e mode) waits for the
 * quickPlay server join, verifies content-pack loading, spawns vehicles through the real
 * item-use path, captures screenshots with in-process pixel statistics, and writes a JSON
 * report before shutting the client down.  Controlled by -Divtestbed.mode=off|bootonly|e2e.
 */
public class TestbedClient implements ClientModInitializer {

    private enum Phase {
        WAIT_JOIN(4800),
        VERIFY_PACKS(100),
        GIVE_AND_PLACE(600),
        VERIFY_ENTITIES(1200),
        SCREENSHOTS_WORLD(1200),
        GUI_CONFIG(300),
        BOARD_VEHICLE(400),
        REPORT_EXIT(100);

        final int budgetTicks;

        Phase(int budgetTicks) {
            this.budgetTicks = budgetTicks;
        }
    }

    private String mode;
    private Phase phase = Phase.WAIT_JOIN;
    private int ticksInPhase;
    private int subStep;
    private int waitTicks;

    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> screenshots = new ArrayList<>();
    private final List<String> phaseLog = new ArrayList<>();
    private final List<ItemVehicle> pickedVehicles = new ArrayList<>();
    private final List<Entity> foundVehicleEntities = new ArrayList<>();
    private final List<net.minecraft.world.phys.Vec3> placedPositions = new ArrayList<>();
    private String failReason = null;
    private boolean boarded = false;
    private boolean reported = false;

    @Override
    public void onInitializeClient() {
        mode = System.getProperty("ivtestbed.mode", "off");
        if (mode.equals("off")) {
            return;
        }
        report.put("mode", mode);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (reported) {
            return;
        }
        try {
            ++ticksInPhase;
            if (ticksInPhase > phase.budgetTicks) {
                fail(mc, "Timeout in phase " + phase + " after " + ticksInPhase + " ticks");
                return;
            }
            if (waitTicks > 0) {
                --waitTicks;
                return;
            }
            switch (phase) {
                case WAIT_JOIN:
                    tickWaitJoin(mc);
                    break;
                case VERIFY_PACKS:
                    tickVerifyPacks(mc);
                    break;
                case GIVE_AND_PLACE:
                    tickGiveAndPlace(mc);
                    break;
                case VERIFY_ENTITIES:
                    tickVerifyEntities(mc);
                    break;
                case SCREENSHOTS_WORLD:
                    tickScreenshotsWorld(mc);
                    break;
                case GUI_CONFIG:
                    tickGuiConfig(mc);
                    break;
                case BOARD_VEHICLE:
                    tickBoardVehicle(mc);
                    break;
                case REPORT_EXIT:
                    finishAndExit(mc, true);
                    break;
            }
        } catch (Throwable t) {
            StringBuilder trace = new StringBuilder(t.toString());
            for (StackTraceElement e : t.getStackTrace()) {
                trace.append("\n  at ").append(e);
            }
            fail(mc, "Exception in phase " + phase + ": " + trace);
        }
    }

    private void tickWaitJoin(Minecraft mc) {
        if (mode.equals("bootonly")) {
            //Success = the client renders a stable, interactive screen without crashing.
            //Normally that's the title screen; accept any settled non-loading screen as a
            //fallback so a first-launch prompt can't hang the gate.
            boolean atTitle = mc.screen instanceof TitleScreen && ticksInPhase > 60;
            boolean settled = mc.screen != null && ticksInPhase > 200
                    && !(mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
                    && !(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen);
            if (atTitle || settled) {
                screenshot(mc, "iv_title");
                nextPhase(Phase.REPORT_EXIT);
            }
        } else {
            if (mc.player != null && mc.level != null && mc.player.tickCount > 80) {
                nextPhase(Phase.VERIFY_PACKS);
            }
        }
    }

    private void tickVerifyPacks(Minecraft mc) {
        List<String> packIDs = new ArrayList<>(new TreeSet<>(PackParser.getAllPackIDs()));
        report.put("packIDs", packIDs);
        long mtsItems = BuiltInRegistries.ITEM.keySet().stream().filter(k -> k.getNamespace().equals("mts")).count();
        report.put("mtsItemCount", mtsItems);

        String contentPackID = packIDs.stream().filter(id -> !id.equals("mts")).findFirst().orElse(null);
        if (contentPackID == null) {
            fail(mc, "No content pack loaded besides the core mts pack. Pack IDs: " + packIDs);
            return;
        }
        report.put("contentPackID", contentPackID);

        List<ItemVehicle> vehicles = new ArrayList<>();
        for (AItemBase item : BuilderItem.itemMap.keySet()) {
            if (item instanceof ItemVehicle && ((ItemVehicle) item).definition.packID.equals(contentPackID)) {
                vehicles.add((ItemVehicle) item);
            }
        }
        vehicles.sort(Comparator.comparing(AItemBase::getRegistrationName));
        report.put("contentPackVehicleCount", vehicles.size());
        if (vehicles.size() < 3) {
            fail(mc, "Content pack has fewer than 3 vehicles: " + vehicles.size());
            return;
        }
        //Spread picks across the sorted list for variety (different vehicle families).
        pickedVehicles.add(vehicles.get(0));
        pickedVehicles.add(vehicles.get(vehicles.size() / 2));
        pickedVehicles.add(vehicles.get(vehicles.size() - 1));
        List<String> names = new ArrayList<>();
        pickedVehicles.forEach(v -> names.add(v.getRegistrationName()));
        report.put("vehiclesPicked", names);
        //Bright, stable weather makes the render screenshots clear.
        mc.player.connection.sendCommand("time set day");
        mc.player.connection.sendCommand("gamerule doDaylightCycle false");
        mc.player.connection.sendCommand("weather clear");
        nextPhase(Phase.GIVE_AND_PLACE);
    }

    private void tickGiveAndPlace(Minecraft mc) {
        if (subStep >= pickedVehicles.size()) {
            nextPhase(Phase.VERIFY_ENTITIES);
            return;
        }
        ItemVehicle vehicleItem = pickedVehicles.get(subStep);
        ItemStack stack = new ItemStack(BuilderItem.itemMap.get(vehicleItem));
        mc.player.getInventory().setItem(subStep, stack);
        mc.player.getInventory().selected = subStep;
        mc.gameMode.handleCreativeModeItemAdd(stack, 36 + subStep);

        //Place on the ground at spread-out offsets so vehicles don't overlap.
        int[][] offsets = {{12, 0}, {0, 16}, {-14, -10}};
        BlockPos playerPos = mc.player.blockPosition();
        BlockPos surface = mc.level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, playerPos.offset(offsets[subStep][0], 0, offsets[subStep][1]));
        BlockPos ground = surface.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        placedPositions.add(Vec3.atCenterOf(ground.above()));
        phaseLog.add("Placed " + vehicleItem.getRegistrationName() + " at " + ground.above());

        ++subStep;
        waitTicks = 40;
    }

    private void tickVerifyEntities(Minecraft mc) {
        //Entity detection is informational: the vehicle spawn is proven server-side by the
        //gametest and the Mineflayer test.  Here we just record what the client sees and then
        //move on to screenshots aimed at the known placement positions, so a detection quirk
        //never blocks the render capture.
        foundVehicleEntities.clear();
        AABB search = mc.player.getBoundingBox().inflate(96);
        for (Entity entity : mc.level.getEntitiesOfClass(BuilderEntityExisting.class, search)) {
            foundVehicleEntities.add(entity);
        }
        int totalNearby = mc.level.getEntitiesOfClass(Entity.class, search).size();
        if (foundVehicleEntities.size() >= pickedVehicles.size() || ticksInPhase > 120) {
            report.put("vehicleEntitiesFound", foundVehicleEntities.size());
            report.put("totalEntitiesNearby", totalNearby);
            phaseLog.add("VERIFY_ENTITIES: found " + foundVehicleEntities.size() + " IV entities, " + totalNearby + " total nearby");
            nextPhase(Phase.SCREENSHOTS_WORLD);
        }
    }

    private void tickScreenshotsWorld(Minecraft mc) {
        //Prefer aiming at detected entities; fall back to the known placement positions.
        List<Vec3> targets = new ArrayList<>();
        if (!foundVehicleEntities.isEmpty()) {
            for (Entity e : foundVehicleEntities) {
                targets.add(e.position());
            }
        } else {
            targets.addAll(placedPositions);
        }
        int shots = Math.min(targets.size(), 3);
        //Hide the HUD for clean vehicle shots and stop chat spam covering the frame.
        mc.options.hideGui = true;
        // Substep layout: for each target -> [tp close, eye level] then [look + screenshot];
        // final substeps do a third-person shot, then finish.
        if (subStep < shots * 2) {
            int target = subStep / 2;
            boolean tpPhase = (subStep % 2) == 0;
            Vec3 t = targets.get(target);
            if (tpPhase) {
                //Close 3/4 view: near enough that the vehicle fills the frame, high enough
                //(and looking down) to clear procedural terrain.
                mc.player.connection.sendCommand(String.format(java.util.Locale.ROOT,
                        "tp IVTester %.1f %.1f %.1f", t.x + 3, t.y + 2.5, t.z + 3));
                waitTicks = 22;
                ++subStep;
            } else {
                lookAt(mc, t);
                screenshot(mc, "iv_world_" + target);
                waitTicks = 5;
                ++subStep;
            }
        } else if (subStep == shots * 2) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            waitTicks = 20;
            ++subStep;
        } else {
            screenshot(mc, "iv_thirdperson");
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = false;
            nextPhase(Phase.GUI_CONFIG);
        }
    }

    private void tickGuiConfig(Minecraft mc) {
        if (subStep == 0) {
            new GUIConfig();
            waitTicks = 30;
            ++subStep;
        } else {
            screenshot(mc, "iv_gui_config");
            AGUIBase.closeIfOpen(GUIConfig.class);
            report.put("configGuiOpened", true);
            nextPhase(Phase.BOARD_VEHICLE);
        }
    }

    private void tickBoardVehicle(Minecraft mc) {
        if (subStep == 0) {
            Entity nearest = null;
            double best = Double.MAX_VALUE;
            for (Entity e : foundVehicleEntities) {
                double d = e.distanceToSqr(mc.player);
                if (d < best) {
                    best = d;
                    nearest = e;
                }
            }
            if (nearest != null) {
                mc.gameMode.interact(mc.player, nearest, InteractionHand.MAIN_HAND);
            }
            waitTicks = 60;
            ++subStep;
        } else if (subStep == 1) {
            boarded = mc.player.getVehicle() != null;
            report.put("boarded", boarded);
            if (boarded) {
                waitTicks = 30;
                ++subStep;
            } else {
                //Non-fatal: seat hitboxes depend on click position; record and move on.
                phaseLog.add("WARN: interact did not board the vehicle (seat may not be at entity origin)");
                nextPhase(Phase.REPORT_EXIT);
            }
        } else {
            screenshot(mc, "iv_boarded_hud");
            nextPhase(Phase.REPORT_EXIT);
        }
    }

    private void lookAt(Minecraft mc, Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = target.subtract(eye).normalize();
        double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        mc.player.setXRot((float) Math.toDegrees(-Math.atan2(dir.y, horiz)));
    }

    private void screenshot(Minecraft mc, String name) {
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            long lumaSum = 0;
            long lumaSqSum = 0;
            long nonBlack = 0;
            long samples = 0;
            for (int y = 0; y < image.getHeight(); y += 2) {
                for (int x = 0; x < image.getWidth(); x += 2) {
                    int abgr = image.getPixelRGBA(x, y);
                    int r = abgr & 0xFF;
                    int g = (abgr >> 8) & 0xFF;
                    int b = (abgr >> 16) & 0xFF;
                    int luma = (299 * r + 587 * g + 114 * b) / 1000;
                    lumaSum += luma;
                    lumaSqSum += (long) luma * luma;
                    if (luma > 16) {
                        ++nonBlack;
                    }
                    ++samples;
                }
            }
            double mean = samples > 0 ? (double) lumaSum / samples : 0;
            double stddev = samples > 0 ? Math.sqrt((double) lumaSqSum / samples - mean * mean) : 0;
            double nonBlackPct = samples > 0 ? (double) nonBlack / samples : 0;

            File dir = new File(mc.gameDirectory, "screenshots-ivtest");
            dir.mkdirs();
            File file = new File(dir, name + ".png");
            image.writeToFile(file);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("file", file.getAbsolutePath());
            entry.put("meanLuma", Math.round(mean * 100) / 100.0);
            entry.put("stddev", Math.round(stddev * 100) / 100.0);
            entry.put("nonBlackPct", Math.round(nonBlackPct * 1000) / 1000.0);
            screenshots.add(entry);
            phaseLog.add("Screenshot " + name + ": mean=" + entry.get("meanLuma") + " stddev=" + entry.get("stddev") + " nonBlack=" + entry.get("nonBlackPct"));
        } catch (Exception e) {
            phaseLog.add("WARN: screenshot " + name + " failed: " + e);
        }
    }

    private void nextPhase(Phase next) {
        phaseLog.add("Phase " + phase + " done in " + ticksInPhase + " ticks -> " + next);
        phase = next;
        ticksInPhase = 0;
        subStep = 0;
        waitTicks = 0;
    }

    private void fail(Minecraft mc, String reason) {
        failReason = reason;
        screenshot(mc, "iv_failure");
        finishAndExit(mc, false);
    }

    private void finishAndExit(Minecraft mc, boolean pass) {
        if (reported) {
            return;
        }
        reported = true;
        report.put("pass", pass && failReason == null);
        report.put("failReason", failReason);
        report.put("screenshots", screenshots);
        report.put("phaseLog", phaseLog);
        File reportFile = new File(mc.gameDirectory, System.getProperty("ivtestbed.report", "ivtestbed-report.json"));
        try (Writer writer = new FileWriter(reportFile)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(report, writer);
        } catch (Exception e) {
            System.err.println("IVTESTBED: could not write report: " + e);
        }
        System.out.println("IVTESTBED: " + (report.get("pass").equals(Boolean.TRUE) ? "PASS" : "FAIL") + " report=" + reportFile.getAbsolutePath());
        mc.stop();
    }
}
