package net.camacraft.fullstop.events;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.effects.status.NoJumpEffect;
import net.camacraft.fullstop.handler.PacketHandler;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = FullStop.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class CommonModEvents {

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PacketHandler::register);
    }


//    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, FullStop.MOD_ID);
//
//    // Register your NoJumpEffect here
//    public static final RegistryObject<MobEffect> NO_JUMP = MOB_EFFECTS.register("no_jump",
//            NoJumpEffect::new);
//
//    // Call this in your mod’s constructor to ensure registration
//    public static void register(IEventBus eventBus) {
//        MOB_EFFECTS.register(eventBus);
//    }

}
