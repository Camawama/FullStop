package net.camacraft.fullstop.common.compat.create;

import net.camacraft.fullstop.common.compat.create.contraption.ContraptionImpacts;
import net.minecraftforge.common.MinecraftForge;

/**
 * Registers the Create hazards' event handlers. Only ever loaded when Create is installed
 * ({@link CreateCompat#init()}): the handlers it names reference Create types.
 */
final class CreateHooks {

    private CreateHooks() {
    }

    static void register() {
        // contraptions strike what they run into, like ships (contraptionImpacts)
        MinecraftForge.EVENT_BUS.register(ContraptionImpacts.class);
        // and meet the terrain and ships, both sides giving, the contraption's network feeling it (contraptionTerrainImpacts...)
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.common.compat.create.contraption.ContraptionTerrainImpacts.class);
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.common.compat.create.contraption.ContraptionStress.class);
        // and boom past the speed of sound, like ships (contraptionSonicBooms)
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.common.compat.create.contraption.ContraptionSonicBooms.class);
        // spinning gears pull in and chew, water wheels drag under (gearHazards, waterWheelHazards)
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.common.compat.create.kinetic.KineticHazards.class);
        // bodies standing on a contraption turning about the vertical turn with it (contraptionRidersTurn); a player
        // on its own client, every frame
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.common.compat.create.contraption.ContraptionRiders.class);
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.client.compat.create.ContraptionRidersClient.class);
        }
    }
}
