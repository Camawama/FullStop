package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Whether a firework rocket is held by a gliding player (attached to them): such a rocket never bounces off slime itself. */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketAccessor {

    @Invoker("isAttachedToEntity")
    boolean fullstop$isAttachedToEntity();
}
