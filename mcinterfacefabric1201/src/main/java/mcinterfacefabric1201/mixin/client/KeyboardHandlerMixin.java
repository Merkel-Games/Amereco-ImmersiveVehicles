package mcinterfacefabric1201.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcinterfacefabric1201.InterfaceInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    /**
     * Need this to know when keys are pressed, since Fabric doesn't have a key input event.
     * Injected at TAIL to match Forge, which fires its event after vanilla has updated key mapping states.
     */
    @Inject(method = "keyPress(JIIII)V", at = @At(value = "TAIL"))
    private void inject_ivKeyPress(long pWindowPointer, int pKey, int pScanCode, int pAction, int pModifiers, CallbackInfo ci) {
        if (pWindowPointer == Minecraft.getInstance().getWindow().getWindow()) {
            InterfaceInput.onIVKeyInput(pKey, pAction);
        }
    }
}
