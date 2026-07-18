package mcinterfacefabric1201.mixin.common;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * IV performs block raycasts (bullet collision in {@code EntityBullet.update} and third-person
 * camera collision in {@code CameraSystem.applyCameraCollision}) with no owning entity, passing a
 * {@code null} entity to the {@link ClipContext} constructor.  Vanilla 1.20.1's only
 * {@code ClipContext(..., Entity)} constructor calls {@code CollisionContext.of(entity)} with no
 * null-check, so {@code EntityCollisionContext} then dereferences the null entity and crashes.
 * <p>
 * Forge/NeoForge patch this at the game level, which is why the shared wrapper code works there.
 * Here we redirect the {@code CollisionContext.of} call inside the constructor to fall back to
 * {@link CollisionContext#empty()} when the entity is null — matching the Forge behaviour exactly.
 * Fixes the crash when pressing F5 / sitting in a gun seat / firing any gun (e.g. the signal cannon
 * and anti-aircraft gun).
 * <p>
 * <b>Coexistence with other mods:</b> Porting Lib (bundled with Create and others) ships the very
 * same Forge-parity redirect on this exact call.  Two {@code @Redirect}s cannot both own one
 * instruction — the higher-priority one wins and rewrites the call, and the loser scans zero
 * targets.  To avoid a hard crash we (a) set this mixin's {@code priority} <i>below</i> Porting Lib's
 * default (1000) so Porting Lib deterministically wins and supplies the identical null-safe
 * behaviour (and does not itself fail its own required injection), and (b) set
 * {@code require = 0}/{@code expect = 0} so our redirector quietly no-ops instead of failing the
 * injection check when it loses.  When Porting Lib (or any other mod patching this call) is absent,
 * our redirect is the sole one on the call, wins, and provides the fix on its own.
 */
@Mixin(value = ClipContext.class, priority = 900)
public abstract class ClipContextMixin {
    @Redirect(method = "<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/CollisionContext;of(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/shapes/CollisionContext;"), require = 0, expect = 0)
    private CollisionContext iv$nullSafeCollisionContext(Entity entity) {
        return entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
    }
}
