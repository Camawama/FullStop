package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Runs a projectile's own hit behaviour ({@code Projectile.onHit}, protected): an egg that meets
 * another projectile in the air breaks as it would on anything it hits - particles, maybe a chick -
 * a potion splashes there, a pearl lands there (see ProjectileCollisions). The call is virtual, so
 * each projectile's own override runs.
 */
@Mixin(Projectile.class)
public interface ProjectileHitInvoker {

    @Invoker("onHit")
    void fullstop$onHit(HitResult result);
}
