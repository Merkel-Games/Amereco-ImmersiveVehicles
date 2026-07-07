package mcinterfacefabric1201.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import mcinterfacefabric1201.InterfaceEventsEntityRendering;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    /**
     * Need this to disable rendering of the item in the player's hand if they are holding a gun.
     * This replicates the cancellation of Forge's RenderHandEvent.
     */
    @Inject(method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V", at = @At(value = "HEAD"), cancellable = true)
    private void inject_renderHandsWithItems(float pPartialTicks, PoseStack pPoseStack, MultiBufferSource.BufferSource pBuffer, LocalPlayer pPlayerEntity, int pCombinedLight, CallbackInfo ci) {
        if (InterfaceEventsEntityRendering.shouldBlockHandRender()) {
            ci.cancel();
        }
    }
}
