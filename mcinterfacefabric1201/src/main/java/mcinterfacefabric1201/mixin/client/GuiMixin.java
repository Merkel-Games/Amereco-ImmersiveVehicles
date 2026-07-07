package mcinterfacefabric1201.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcinterfacefabric1201.InterfaceEventsEntityRendering;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;

@Mixin(Gui.class)
public abstract class GuiMixin {
    /**
     * Need these to block overlays that we don't want to render.  These replicate the
     * cancellations Forge's RenderGuiOverlayEvent.Pre event did for the crosshair, hotbar,
     * food, health, armor, and experience overlays.
     */
    @Inject(method = "renderCrosshair(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At(value = "HEAD"), cancellable = true)
    private void inject_renderCrosshair(GuiGraphics pGuiGraphics, CallbackInfo ci) {
        if (InterfaceEventsEntityRendering.shouldBlockCrosshair()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHotbar(FLnet/minecraft/client/gui/GuiGraphics;)V", at = @At(value = "HEAD"), cancellable = true)
    private void inject_renderHotbar(float pPartialTick, GuiGraphics pGuiGraphics, CallbackInfo ci) {
        if (InterfaceEventsEntityRendering.shouldBlockCrosshair() || InterfaceEventsEntityRendering.shouldBlockHUDComponents()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderPlayerHealth(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At(value = "HEAD"), cancellable = true)
    private void inject_renderPlayerHealth(GuiGraphics pGuiGraphics, CallbackInfo ci) {
        if (InterfaceEventsEntityRendering.shouldBlockHUDComponents()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderExperienceBar(Lnet/minecraft/client/gui/GuiGraphics;I)V", at = @At(value = "HEAD"), cancellable = true)
    private void inject_renderExperienceBar(GuiGraphics pGuiGraphics, int pX, CallbackInfo ci) {
        if (InterfaceEventsEntityRendering.shouldBlockHUDComponents()) {
            ci.cancel();
        }
    }

    /**
     * Need this to render IV's 2D overlays (main overlay, vehicle HUDs, GUIs, camera overlays)
     * just before the chat window is rendered, matching Forge's CustomizeGuiOverlayEvent.Chat timing.
     */
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;III)V"))
    private void inject_renderOverlay(GuiGraphics pGuiGraphics, float pPartialTick, CallbackInfo ci) {
        InterfaceEventsEntityRendering.renderOverlay(pGuiGraphics, pPartialTick);
    }
}
