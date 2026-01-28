package net.camacraft.fullstop.server;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CancelEvents {

//    public static boolean debugBreak = false;

    // Cancel the fall damage event
    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {

//        if (event.getEntity() instanceof Player) {
//            debugBreak = true;
//        }

        BlockPos pos = event.getEntity().blockPosition();
        if (event.getEntity().level().getBlockState(pos).is(Blocks.POINTED_DRIPSTONE)
                || event.getEntity().level().getBlockState(pos.below()).is(Blocks.POINTED_DRIPSTONE)) {
            return;
        }

        event.setCanceled(true);
    }
}
