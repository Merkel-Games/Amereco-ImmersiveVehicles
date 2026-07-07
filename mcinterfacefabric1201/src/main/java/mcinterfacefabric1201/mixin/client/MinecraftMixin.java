package mcinterfacefabric1201.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mcinterfacefabric1201.InterfaceClientLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /**
     * Need this to force dev multiplayer.
     * MC otherwise voids this due to access token crap.
     */
    @Inject(method = "allowsMultiplayer()Z", at = @At(value = "HEAD"), cancellable = true)
    private void inject_ivAllowsMultiplayer(CallbackInfoReturnable<Boolean> ci) {
        ci.setReturnValue(true);
    }

    /**
     * Fire the IV client world-unload hooks when the level is swapped (dimension change) or
     * cleared (leaving a world).  Forge dispatched these via LevelEvent.Unload client-side.
     */
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void inject_ivSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        InterfaceClientLoader.fireClientWorldUnload(((Minecraft) (Object) this).level);
    }

    @Inject(method = "clearLevel()V", at = @At("HEAD"))
    private void inject_ivClearLevel(CallbackInfo ci) {
        InterfaceClientLoader.fireClientWorldUnload(((Minecraft) (Object) this).level);
    }
}
