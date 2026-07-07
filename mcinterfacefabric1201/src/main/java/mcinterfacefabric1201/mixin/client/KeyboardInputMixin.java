package mcinterfacefabric1201.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcinterfacefabric1201.InterfaceInput;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    /**
     * Need this to modify movement inputs after MC calculates them, since Fabric doesn't have a movement input event.
     */
    @Inject(method = "tick(ZF)V", at = @At(value = "TAIL"))
    private void inject_ivTick(boolean pIsSneaking, float pSneakingSpeedMultiplier, CallbackInfo ci) {
        InterfaceInput.onIVMovementInput((Input) (Object) this);
    }
}
