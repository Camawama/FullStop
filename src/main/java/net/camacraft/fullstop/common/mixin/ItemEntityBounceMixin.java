package net.camacraft.fullstop.common.mixin;

import net.camacraft.fullstop.common.physics.SlimeProjectiles;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dropped slime ball or slime block (#fullstop:bouncy_items) bounces off what its move ran into
 * ({@link SlimeProjectiles#bounceItem}). On {@code Entity.move} rather than in the item's tick: it is the move that
 * stops it, and the item's own call to it can't be named reliably. Everything but such an item returns at once.
 */
@Mixin(Entity.class)
public abstract class ItemEntityBounceMixin {

    /** Whether the item lay on the ground as its move began. */
    @Unique
    private boolean fullstop$wasOnGround;

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void fullstop$beforeItemMove(MoverType type, Vec3 movement, CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity item) fullstop$wasOnGround = item.onGround();
    }

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("RETURN"))
    private void fullstop$bounceItem(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (type != MoverType.SELF || !((Object) this instanceof ItemEntity item) || movement == null) return;
        if (SlimeProjectiles.bouncyItem(item)) SlimeProjectiles.bounceItem(item, movement, fullstop$wasOnGround);
    }
}
