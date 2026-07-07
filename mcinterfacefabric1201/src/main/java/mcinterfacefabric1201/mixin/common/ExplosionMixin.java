package mcinterfacefabric1201.mixin.common;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcinterfacefabric1201.BuilderEntityExisting;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;

@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow
    @Final
    private Level level;
    @Shadow
    @Final
    private double x;
    @Shadow
    @Final
    private double y;
    @Shadow
    @Final
    private double z;

    /**
     * Need this to know where explosions occur in the world, as the damage source
     * they attack our entities with is position-less.  We save the position at the
     * start of the explosion, before any entities are damaged.  This replaces the
     * Forge ExplosionEvent.Detonate hook.
     */
    @Inject(method = "explode", at = @At(value = "HEAD"))
    private void inject_explode(CallbackInfo ci) {
        if (!level.isClientSide) {
            BuilderEntityExisting.onExplosion((Explosion) (Object) this, x, y, z);
        }
    }
}
