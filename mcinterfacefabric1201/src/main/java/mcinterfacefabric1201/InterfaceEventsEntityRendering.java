package mcinterfacefabric1201;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.Window;

import mcinterfacefabric1201.mixin.client.CameraMixin;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.components.AEntityB_Existing;
import minecrafttransportsimulator.entities.instances.EntityPlayerGun;
import minecrafttransportsimulator.entities.instances.PartSeat;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.systems.CameraSystem;
import minecrafttransportsimulator.systems.CameraSystem.CameraMode;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;

/**
 * Interface for handling events pertaining to entity rendering.  This modifies the player's rendered state
 * to handle them being in vehicles, as well as ensuring their model adapts to any objects they may be holding.
 * This also handles the 2D GUI rendering.  On Fabric, these hooks are called from mixins rather than Forge events.
 *
 * @author don_bruce
 */
public class InterfaceEventsEntityRendering {
    public static final Point3D cameraAdjustedPosition = new Point3D();
    public static final RotationMatrix cameraAdjustedOrientation = new RotationMatrix();
    public static boolean adjustedCamera;
    /**
     * Roll set by {@link #onCameraSetup(Camera, float)} and applied to the world PoseStack by GameRendererMixin,
     * since vanilla has no camera roll concept like Forge's ComputeCameraAngles event.
     */
    public static float cameraRoll;
    private static Player mcPlayer;

    private static int lastScreenWidth;
    private static int lastScreenHeight;
    /**
     * Changes camera rotation to match custom rotation, and also gets custom position for custom cameras.
     * Called by GameRendererMixin right after {@link Camera#setup} during level rendering, which is the same
     * point Forge fires ViewportEvent.ComputeCameraAngles.
     */
    public static void onCameraSetup(Camera camera, float partialTicks) {
        cameraRoll = 0;
        if (camera.getEntity() instanceof Player) {
            mcPlayer = (Player) camera.getEntity();
            IWrapperPlayer player = WrapperPlayer.getWrapperFor(mcPlayer);
            cameraAdjustedPosition.set(0, 0, 0);
            cameraAdjustedOrientation.setToZero();
            adjustedCamera = false;
            if (CameraSystem.adjustCamera(player, cameraAdjustedPosition, cameraAdjustedOrientation, partialTicks)) {
                //Camera adjustments occur backwards here.  Reverse order in the matrix.
                //Also need to reverse sign of Y, since that's backwards in MC.
                cameraAdjustedOrientation.convertToAngles();
                if (InterfaceManager.clientInterface.getCameraMode() == CameraMode.THIRD_PERSON_INVERTED) {
                    //Inverted third-person needs roll and pitch flipped due to the opposite perspective.
                    //It also needs the camera rotated 180 in the Y to face the other direction.
                    cameraRoll = (float) -cameraAdjustedOrientation.angles.z;
                    ((CameraMixin) camera).invoke_setRotation((float) (-cameraAdjustedOrientation.angles.y + 180), (float) -cameraAdjustedOrientation.angles.x);
                } else {
                    cameraRoll = (float) cameraAdjustedOrientation.angles.z;
                    ((CameraMixin) camera).invoke_setRotation((float) -cameraAdjustedOrientation.angles.y, (float) cameraAdjustedOrientation.angles.x);
                }
                //Move the info's setup to the set position of the camera.
                //This will offset the player's eye position to match the camera.
                //We do this in first-person mode since third-person adds zoom stuff.
                ((CameraMixin) camera).invoke_setPosition(cameraAdjustedPosition.x, cameraAdjustedPosition.y, cameraAdjustedPosition.z);
                adjustedCamera = true;
            }
        }
    }

    /**
     * Blocks all overlays that we don't want to render.  These two checks are called by GuiMixin
     * on the vanilla overlay render methods, matching Forge's RenderGuiOverlayEvent.Pre cancellations.
     * This one blocks the crosshairs and the hotbar when a custom camera overlay is active.
     */
    public static boolean shouldBlockCrosshair() {
        //If we are rendering the custom camera overlay, block the crosshairs and the hotbar.
        return CameraSystem.customCameraOverlay != null;
    }

    /**
     * Blocks the hotbar, food, health, armor, and experience overlays when we are seated in a
     * controller seat and are rendering GUIs.
     */
    public static boolean shouldBlockHUDComponents() {
        //If we are seated in a controller seat, and are rendering GUIs, disable the hotbar.
        if (InterfaceManager.clientInterface.getCameraMode() == CameraMode.FIRST_PERSON ? ConfigSystem.client.renderingSettings.renderHUD_1P.value : ConfigSystem.client.renderingSettings.renderHUD_3P.value) {
            IWrapperPlayer player = InterfaceManager.clientInterface.getClientPlayer();
            AEntityB_Existing ridingEntity = player.getEntityRiding();
            return ridingEntity instanceof PartSeat && ((PartSeat) ridingEntity).placementDefinition.isController;
        }
        return false;
    }

    /**
     * Renders all overlay things.  This is essentially anything that's a 2D render, such as the main overlay,
     * vehicle HUds, GUIs, camera overlays, etc.  Called by GuiMixin just before the chat window is rendered,
     * matching Forge's CustomizeGuiOverlayEvent.Chat timing.
     */
    public static void renderOverlay(GuiGraphics graphics, float partialTicks) {
        //Do overlay rendering before the chat window is rendered.
        //This renders them over the main hotbar, but doesn't block the chat window.
        Window window = Minecraft.getInstance().getWindow();
        long displaySize = InterfaceManager.clientInterface.getPackedDisplaySize();
        int screenWidth = (int) (displaySize >> Integer.SIZE);
        int screenHeight = (int) displaySize;
        double[] xPos = new double[1];
        double[] yPos = new double[1];
        GLFW.glfwGetCursorPos(window.getWindow(), xPos, yPos);
        int mouseX = (int) (xPos[0] * screenWidth / window.getScreenWidth());
        int mouseY = (int) (yPos[0] * screenHeight / window.getScreenHeight());

        boolean updateGUIs = screenWidth != lastScreenWidth || screenHeight != lastScreenHeight;
        if (updateGUIs) {
            lastScreenWidth = screenWidth;
            lastScreenHeight = screenHeight;
        }

        InterfaceRender.renderGUI(graphics, mouseX, mouseY, screenWidth, screenHeight, partialTicks, updateGUIs);
    }

    /**
     * Hand and arm render checks.  We use these to disable rendering of the item in the player's hand
     * if they are holding a gun.  Not sure why there's two hooks, but we block them both!  These are
     * called by ItemInHandRendererMixin and PlayerRendererMixin respectively, matching Forge's
     * RenderHandEvent and RenderArmEvent cancellations.
     */
    public static boolean shouldBlockHandRender() {
        EntityPlayerGun entity = EntityPlayerGun.playerClientGuns.get(Minecraft.getInstance().player.getUUID());
        return (entity != null && entity.activeGun != null) || CameraSystem.activeCamera != null;
    }

    public static boolean shouldBlockArmRender() {
        EntityPlayerGun entity = EntityPlayerGun.playerClientGuns.get(Minecraft.getInstance().player.getUUID());
        return (entity != null && entity.activeGun != null) || CameraSystem.activeCamera != null;
    }
}
