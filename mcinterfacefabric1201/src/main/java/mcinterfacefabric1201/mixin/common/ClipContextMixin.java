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
 */
@Mixin(ClipContext.class)
public abstract class ClipContextMixin {
    @Redirect(method = "<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/CollisionContext;of(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/shapes/CollisionContext;"))
    private CollisionContext iv$nullSafeCollisionContext(Entity entity) {
        return entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
    }
}
