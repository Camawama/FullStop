package net.camacraft.fullstop.common.compat.create.contraption;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlock;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlock;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlock;
import com.simibubi.create.content.contraptions.pulley.PulleyBlock;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * What kind of contraption struck something, for its damage type and death message
 * ({@code fullstop:contraption_<kind>}, {@code death.fullstop.contraption.<kind>.*}): a train, a gantry, a minecart
 * contraption, and for the ones a block drives, that block: a windmill bearing, a clockwork bearing, a mechanical
 * piston, an elevator pulley, a rope pulley, and any other bearing turning one ("spinning"). Null when FullStop
 * can't tell (another mod's contraption): the plain {@code fullstop:contraption}.
 */
public final class ContraptionKinds {

    private ContraptionKinds() {
    }

    @Nullable
    public static String of(ServerLevel level, AbstractContraptionEntity c) {
        if (c instanceof CarriageContraptionEntity) return "train";
        if (c instanceof GantryContraptionEntity) return "gantry";
        if (c instanceof OrientedContraptionEntity) return "minecart";
        if (c instanceof ControlledContraptionEntity) {
            BlockPos pos = ContraptionStress.controllerPosOf(c);
            Block block = pos != null && level.isLoaded(pos) ? level.getBlockState(pos).getBlock() : null;
            if (block instanceof WindmillBearingBlock) return "windmill";
            if (block instanceof ClockworkBearingBlock) return "clock";
            if (block instanceof MechanicalPistonBlock) return "piston";
            if (block instanceof ElevatorPulleyBlock) return "elevator";
            if (block instanceof PulleyBlock) return "pulley";
            if (ContraptionStress.rotationAxis(c) != null) return "spinning";
        }
        return null;
    }
}
