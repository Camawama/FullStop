package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FallingBlockEntity.class)
public class FallingBlockEntityMixin {

    /**
     * Makes falling blocks respect doTileDrops (RULE_DOBLOCKDROPS).
     * Vanilla FallingBlockEntity uses doEntityDrops for its item-drop checks,
     * so we disable dropItem whenever doTileDrops is false.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void fullstop$disableDropsWhenTileDropsOff(CallbackInfo ci) {
        FallingBlockEntity self = (FallingBlockEntity) (Object) this;

        if (!self.level().isClientSide
                && !self.level().getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            self.dropItem = false;
        }
    }
}