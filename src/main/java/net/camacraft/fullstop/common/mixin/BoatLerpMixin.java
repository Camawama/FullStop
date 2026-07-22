package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Vanilla boats interpolate toward their server position over 10 ticks (every
 * other entity uses 3). At speed that leaves the rendered boat several blocks
 * behind its real position — so any server-side reaction FullStop applies
 * (slime bounce, collision sound/particles) looks like it happens long before
 * the boat visually reaches the wall. Bringing the lerp in line with other
 * entities makes the rendered boat track its true position closely enough for
 * bounces to look like contact.
 */
@Mixin(Boat.class)
public abstract class BoatLerpMixin {

    @ModifyConstant(method = "lerpTo", constant = @Constant(intValue = 10))
    private int fullstop$fasterBoatLerp(int lerpSteps) {
        return 3;
    }
}
