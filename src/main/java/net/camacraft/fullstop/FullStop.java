package net.camacraft.fullstop;

import net.camacraft.fullstop.client.message.LogToChat;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.effects.EffectEvents;
import net.camacraft.fullstop.server.CancelEvents;
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
import net.minecraftforge.fml.loading.FMLEnvironment;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.FullStopConfig.SERVER_SPEC;
import static net.camacraft.fullstop.common.physics.Physics.entityVelocity;

@Mod(FullStop.MOD_ID)
public class FullStop
{
    public static final String MOD_ID = "fullstop";
    /**
     * For some reason entities on the ground still have a negative delta Y change of this value.
     */

    public FullStop() {
        MinecraftForge.EVENT_BUS.register(FullStop.class);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.client.physics.PhysicsDispatch.class);
        }

        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.server.physics.PhysicsDispatch.class);

        MinecraftForge.EVENT_BUS.register(FullStopConfig.class);
        MinecraftForge.EVENT_BUS.register(FullStopCapability.class);
        MinecraftForge.EVENT_BUS.register(CancelEvents.class);
        MinecraftForge.EVENT_BUS.register(EffectEvents.class);
        MinecraftForge.EVENT_BUS.register(LogToChat.class);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);

    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(SERVER.projectilesHaveMomentum.get())) return;
        if (!(event.getEntity() instanceof Projectile projectile) || projectile.level().isClientSide) return;
        if (projectile.getOwner() == null) return;

        Vec3 ownerVelocity = entityVelocity(projectile.getOwner()).scale((double) 1 / 20);
        if (ownerVelocity.equals(Vec3.ZERO)) return;

        projectile.setDeltaMovement(projectile.getDeltaMovement().add(ownerVelocity));
    }
}