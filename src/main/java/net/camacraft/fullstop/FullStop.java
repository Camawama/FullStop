package net.camacraft.fullstop;

import net.camacraft.fullstop.client.message.LogToChat;
import net.camacraft.fullstop.client.physics.PhysicsDispatch;
import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.camacraft.fullstop.common.capabilities.PositionCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.effects.EffectEvents;
import net.camacraft.fullstop.common.physics.PhysicsRegistry;
import net.camacraft.fullstop.server.CancelEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

import static net.camacraft.fullstop.common.capabilities.FullStopCapability.Provider.DELTAV_CAP;
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
        MinecraftForge.EVENT_BUS.register(PhysicsRegistry.class);
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.client.physics.PhysicsDispatch.class);
        MinecraftForge.EVENT_BUS.register(net.camacraft.fullstop.server.physics.PhysicsDispatch.class);
        MinecraftForge.EVENT_BUS.register(PositionCapability.class);
        MinecraftForge.EVENT_BUS.register(FullStopConfig.class);
        MinecraftForge.EVENT_BUS.register(FullStopCapability.class);
        MinecraftForge.EVENT_BUS.register(CancelEvents.class);
        MinecraftForge.EVENT_BUS.register(EffectEvents.class);
        MinecraftForge.EVENT_BUS.register(LogToChat.class);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);

    }





//    @SubscribeEvent
//    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
//        Entity entity = event.getEntity();
//        onEntityTick(entity);
//    }










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