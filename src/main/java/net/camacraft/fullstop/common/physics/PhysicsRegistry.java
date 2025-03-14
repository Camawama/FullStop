package net.camacraft.fullstop.common.physics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.telemetry.events.WorldLoadEvent;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.logging.Level;

public class PhysicsRegistry extends HashSet<Entity> {
    public static final PhysicsRegistry client = new PhysicsRegistry(), server = new PhysicsRegistry();
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event){
        if (event.getLevel().isClientSide) {
            client.add(event.getEntity());
        } else {
            server.add(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event){
        if (event.getLevel().isClientSide) {
            if (event.getEntity() == Minecraft.getInstance().player)
                client.clear();
            else
                client.remove(event.getEntity());
        } else {
            server.remove(event.getEntity());
        }
    }


}
