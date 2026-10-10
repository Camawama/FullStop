package net.camacraft.fullstop.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A move that nothing stopped is no vertical collision. Vanilla calls a move's vertical part blocked when the distance
 * it could go differs from the distance it wanted to go in any bit ({@code movement.y != collided.y}), and then runs
 * the "landed on" hook of the block under the body, which takes its vertical motion ({@code Block.updateEntityAfterFallOn})
 * whichever way it was going. Valkyrien Skies passes every move through its ship collision first, and for a body still
 * touching a hull that answers with the same move a rounding error off: a body bounced off a ship's slime went up for
 * one tick and stopped dead (the owner: "the bounce attempts to bounce me but it stops me the moment it bounces"; in the
 * pack harness 3 falls of 16 on ships, never on world slime). A vertical difference under {@link #NOISE} blocks is taken
 * as none; a real collision is short by whole fractions of a block.
 */
@Mixin(Entity.class)
public abstract class EntityMoveCollideNoiseMixin {

    /** Vertical distance (blocks) a collision result may differ from the move it was asked for and still be that move. */
    @Unique
    private static final double NOISE = 1.0e-6;
    @Unique
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("fullstop.exactShipCollision"));
    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();
    @Unique
    private static long lastLog;

    @ModifyExpressionValue(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 fullstop$unblockedMoveIsExact(Vec3 collided, @Local(argsOnly = true) Vec3 movement) {
        if (!ENABLED || collided == null || movement == null) return collided;
        double off = collided.y - movement.y;
        if (off == 0.0 || Math.abs(off) >= NOISE || !ShipCompat.active()) return collided;
        if (FullStopConfig.SERVER_SPEC.isLoaded() && FullStopConfig.SERVER.debugImpactLog.get()) {
            long now = System.currentTimeMillis();
            if (now - lastLog > 1000L) {
                lastLog = now;
                Entity self = (Entity) (Object) this;
                LOGGER.info("[FullStop] {}'s move of {} blocks came back {} off (a ship's collision rounding): taken as unblocked",
                        self.getName().getString(), String.format(java.util.Locale.ROOT, "%.4f", movement.y), off);
            }
        }
        return new Vec3(collided.x, movement.y, collided.z);
    }
}
