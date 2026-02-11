package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlimeBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(SlimeBlock.class)
public abstract class SlimeBlockNoBounceMixin {

    /**
     * @author CamaCraft
     * @reason Disable vanilla slime block bounce logic to allow FullStop to handle it.
     */
    @Overwrite
    public void updateEntityAfterFallOn(BlockGetter level, Entity entity) {
        entity.setDeltaMovement(entity.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
    }
}
