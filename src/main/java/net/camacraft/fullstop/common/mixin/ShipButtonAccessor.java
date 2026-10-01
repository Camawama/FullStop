package net.camacraft.fullstop.common.mixin;

import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A button's set type (its click sounds) and how long it stays pressed, for ships that press buttons. */
@Mixin(ButtonBlock.class)
public interface ShipButtonAccessor {

    @Accessor("type")
    BlockSetType fullstop$type();

    @Accessor("ticksToStayPressed")
    int fullstop$ticksToStayPressed();
}
