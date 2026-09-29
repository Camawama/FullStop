package net.camacraft.fullstop.common.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.camacraft.fullstop.common.physics.ShipHullSweep;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Solid ship hulls at any speed (see {@link ShipHullSweep}): wraps the {@code collide} call in
 * {@code Entity.move}, the same call Valkyrien Skies wraps with its entity-vs-ship resolver.
 *
 * <p>Priority 1100, above VS's default 1000: MixinExtras nests wrappers of one call in the order
 * they are applied and mixins are applied by ascending priority, so this wrapper is the OUTER one
 * - it gets the entity's whole move first and hands VS's resolver the short steps. (Applied the
 * other way round it would only ever see VS's answer, the damage already done.) The game tests
 * check the order: a flying player hitting a hull at 22 m/s must stop at it.
 */
@Mixin(value = Entity.class, priority = 1100)
public abstract class EntityShipSweepMixin {

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 fullstop$solidHulls(Entity self, Vec3 movement, Operation<Vec3> original) {
        return ShipHullSweep.collide(self, movement, original);
    }
}
