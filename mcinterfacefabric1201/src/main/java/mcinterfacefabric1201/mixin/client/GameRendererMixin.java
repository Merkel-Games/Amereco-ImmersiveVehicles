package mcinterfacefabric1201.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import mcinterfacefabric1201.InterfaceEventsEntityRendering;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Camera mainCamera;

    /**
     * Need this to adjust the camera position and rotation for custom cameras, and to apply camera roll
     * to the world pose stack.  This runs right after the camera does its vanilla setup, which is the same
     * point Forge fires ViewportEvent.ComputeCameraAngles and applies the event's roll to the pose stack.
     */
    @Inject(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V", shift = At.Shift.AFTER))
    public void inject_renderLevelCameraSetup(float pPartialTicks, long pFinishTimeNano, PoseStack pPoseStack, CallbackInfo ci) {
        InterfaceEventsEntityRendering.onCameraSetup(mainCamera, pPartialTicks);
        pPoseStack.mulPose(Axis.ZP.rotationDegrees(InterfaceEventsEntityRendering.cameraRoll));
    }
}
