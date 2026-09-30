package net.camacraft.fullstop;

import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.client.physics.PhysicsDispatchClient;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.LegacyWeightRS;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.physics.BlockPhasing;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.registry.ModAttributes;
import net.camacraft.fullstop.common.registry.ModEffects;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.camacraft.fullstop.common.registry.ModPotions;
import net.camacraft.fullstop.server.CancelEvents;
import net.camacraft.fullstop.server.command.FullStopCommand;
import net.camacraft.fullstop.server.physics.PhysicsDispatchServer;
import net.camacraft.fullstop.server.physics.interaction.GravityBlockHandler;
import net.camacraft.fullstop.server.physics.pressure.PressureHandler;
import net.camacraft.fullstop.server.physics.ship.ShipInertia;
import net.camacraft.fullstop.server.physics.ship.ShipTerrainImpacts;
import net.camacraft.fullstop.server.physics.ship.ShipWeightHandler;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
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

        MinecraftForge.EVENT_BUS.register(FullStop.class);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(PhysicsDispatchClient.class);
            // Valkyrien Skies lets go of a player jumping off its deck (ClientShipRelease).
            MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.client.physics.ClientShipRelease.class);
            // Mobs a hull pushes are drawn against its face, not inside it (ClientHullClearance).
            MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.client.physics.ClientHullClearance.class);
            // Hulls running into the local player are answered on its client (ClientShipContacts).
            net.camacraft.fullstop.common.physics.ShipHullSweep.clientShoves =
                    net.camacraft.fullstop.client.physics.ClientShipContacts::onShove;
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        }

        MinecraftForge.EVENT_BUS.register(PhysicsDispatchServer.class);
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.server.physics.interaction.RideStackHandler.class);
        // Knockback carries a Pehkui-resized entity as far as a normal-size one (scaledKnockback).
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.server.physics.interaction.ScaledKnockback.class);
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.server.physics.SonicBooms.class);
        MinecraftForge.EVENT_BUS.register(GravityBlockHandler.class);
        MinecraftForge.EVENT_BUS.register(PressureHandler.class);
        MinecraftForge.EVENT_BUS.register(BlockPhasing.class);
        MinecraftForge.EVENT_BUS.register(FullStopCapability.class);
        MinecraftForge.EVENT_BUS.register(CancelEvents.class);
        MinecraftForge.EVENT_BUS.register(ShipWeightHandler.class);
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> FullStopCommand.register(e.getDispatcher()));

        // WeightRS's weight model into Camafy's config, before any config loads.
        LegacyWeightRS.bootstrap();

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        modEventBus.addListener(this::onConfigLoading);
        modEventBus.addListener(this::onConfigReloading);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            PacketHandler.register();
            ModPotions.registerBrewingRecipes();
            // Each ship's inertia, read on VS's physics thread (a no-op without VS).
            ShipInertia.register();
        });
        if (ShipCompat.WEIGHTRS_PRESENT) {
            LOGGER.warn("[FullStop] WeightRS is installed: Full Stop's ship weight stays off so entities don't weigh on "
                    + "ships twice. Its features are part of Full Stop now - remove WeightRS; its settings import automatically.");
        }
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() != SERVER_SPEC) return;
        // A world's server config: WeightRS's ship-weight settings, once per world.
        LegacyWeightRS.onServerConfigLoading(event.getConfig());
        ShipTerrainImpacts.syncSubscription();
    }

    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != SERVER_SPEC) return;
        ShipTerrainImpacts.syncSubscription();
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!SERVER_SPEC.isLoaded()) return;

        // Fires for chunk loads too — without this, a persisted arrow re-gained
        // its owner's current velocity every time its chunk reloaded.
        if (event.loadedFromDisk()) return;

        if (!(SERVER.projectilesHaveMomentum.get())) return;
        if (!(event.getEntity() instanceof Projectile projectile) || projectile.level().isClientSide) return;
        if (projectile.getOwner() == null) return;
        // Inheriting the owner's velocity is a MOTION write, and the owner's
        // velocity is only the owner's when no other mod scripts it (an MKW racer).
        if (PhysicsClaims.handsOff(projectile, PhysicsAspect.MOTION)
                || PhysicsClaims.handsOff(projectile.getOwner(), PhysicsAspect.MOTION)) return;

        Vec3 ownerVelocity = VelocityMath.entityVelocity(projectile.getOwner()).scale((double) 1 / 20);
        if (ownerVelocity.equals(Vec3.ZERO)) return;

        projectile.setDeltaMovement(projectile.getDeltaMovement().add(ownerVelocity));
    }
}
