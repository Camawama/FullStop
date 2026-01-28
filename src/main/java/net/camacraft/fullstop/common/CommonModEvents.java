package net.camacraft.fullstop.common;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.attributes.ModAttributes;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = FullStop.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class CommonModEvents {

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PacketHandler::register);
    }

    @SubscribeEvent
    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES) {
            if (LivingEntity.class.isAssignableFrom(type.getBaseClass())) {
                event.add(type, ModAttributes.KINETIC_DAMPENING.get());
            }
        }
    }

}
