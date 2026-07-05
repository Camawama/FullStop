package net.camacraft.fullstop;

import net.camacraft.fullstop.client.physics.PhysicsDispatchClient;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.EntityWeight;
import net.camacraft.fullstop.common.registry.ModAttributes;
import net.camacraft.fullstop.common.registry.ModEffects;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.camacraft.fullstop.common.registry.ModPotions;
import net.camacraft.fullstop.server.CancelEvents;
import net.camacraft.fullstop.server.physics.PhysicsDispatchServer;
import net.camacraft.fullstop.server.physics.pressure.PressureHandler;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.FullStopConfig.SERVER_SPEC;
import static net.camacraft.fullstop.FullStopConfig.CLIENT_SPEC;

@Mod(FullStop.MODID)
public class FullStop
{
    public static final String MODID = "fullstop";

    public FullStop() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEffects.register(modEventBus);
        ModPotions.register(modEventBus);
        ModAttributes.register(modEventBus);
        ModEnchantments.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);

        MinecraftForge.EVENT_BUS.register(FullStop.class);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(PhysicsDispatchClient.class);
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        }

        MinecraftForge.EVENT_BUS.register(PhysicsDispatchServer.class);
        MinecraftForge.EVENT_BUS.register(PressureHandler.class);
        MinecraftForge.EVENT_BUS.register(FullStopCapability.class);
        MinecraftForge.EVENT_BUS.register(CancelEvents.class);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            PacketHandler.register();
            ModPotions.registerBrewingRecipes();
        });
    }

    public void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getType() == ModConfig.Type.SERVER) {
            EntityWeight.loadConfigWeights();
        }
    }

    public void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.SERVER) {
            EntityWeight.loadConfigWeights();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!SERVER_SPEC.isLoaded()) return;

        if (!(SERVER.projectilesHaveMomentum.get())) return;
        if (!(event.getEntity() instanceof Projectile projectile) || projectile.level().isClientSide) return;
        if (projectile.getOwner() == null) return;

        Vec3 ownerVelocity = VelocityMath.entityVelocity(projectile.getOwner()).scale((double) 1 / 20);
        if (ownerVelocity.equals(Vec3.ZERO)) return;

        projectile.setDeltaMovement(projectile.getDeltaMovement().add(ownerVelocity));
    }
}
