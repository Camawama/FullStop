package net.camacraft.fullstop.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes falling blocks respect doTileDrops (RULE_DOBLOCKDROPS). Vanilla gates
 * its three drop sites in tick() on doEntityDrops only.
 *
 * Expression-value AND rather than writing {@code dropItem = false}: dropItem
 * is persisted to NBT ("DropItem"), so mutating it permanently muted drops on
 * any block that ticked once while the rule was off — and clobbered other mods'
 * intentional dropItem values.
 */
@Mixin(FallingBlockEntity.class)
public class FallingBlockEntityMixin {

    @ModifyExpressionValue(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean fullstop$alsoRequireTileDrops(boolean original) {
        FallingBlockEntity self = (FallingBlockEntity) (Object) this;
        return original && self.level().getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS);
    }
}
