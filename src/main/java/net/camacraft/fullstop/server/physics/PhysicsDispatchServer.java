package net.camacraft.fullstop.server.physics;

import com.google.common.collect.Lists;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.particle.CollisionParticleSpawner;
import net.camacraft.fullstop.common.physics.damage.KineticDamageSources;
import net.camacraft.fullstop.common.physics.interaction.BounceHandler;
import net.camacraft.fullstop.common.physics.interaction.EntityCollisionHandler;
import net.camacraft.fullstop.common.physics.interaction.KineticBlockInteractions;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.camacraft.fullstop.server.physics.collision.ServerCollisionDetector;
import net.camacraft.fullstop.server.physics.damage.KineticDamageApplier;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.camacraft.fullstop.server.physics.effects.StatusEffectApplier;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

public class PhysicsDispatchServer {

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
            List<Entity> entities = Lists.newArrayList(level.getAllEntities());
            for (Entity entity : entities) {
                onEntityTick(entity);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityChangeDimension(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.setHasTeleported(true);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityTeleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.setHasTeleported(true);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.setHasTeleported(true);

                if (living instanceof ServerPlayer) {
                    if (!cap.getJoinedForFirstTime()) {
                        cap.setJoinedForFirstTime(true); // Old check that could be useful for checking if a player has joined for the first time
                    }
                }
            }
        }
    }

    private static void onEntityTick(Entity entity) {
        handlePressure(entity);

        if (DamageImmunityRules.unphysable(entity)) return;

        FullStopCapability fullstop = grabCapability(entity);
        if (fullstop == null) return;

        if (entity.tickCount != fullstop.getLastTick()) {
            fullstop.tick(entity);
            fullstop.setLastTick(entity.tickCount);
        }

        if (fullstop.getIsDamageImmune()) {
            fullstop.resetVelocity();
            return;
        }

        if (fullstop.getCurrentScaledVelocity().length() >= 343.0 && fullstop.canSonicBoom()) {
            if (entity.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.SONIC_BOOM, entity.getX(), entity.getY(), entity.getZ(), 1, 0, 0, 0, 0);
                FSSoundPlayer.playSoundServer(entity, SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 4.0f, (1.0f + (entity.level().random.nextFloat() - entity.level().random.nextFloat()) * 0.2f) * 0.7f);
                FSSoundPlayer.playSoundServer(entity, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.NEUTRAL, 4.0f, 2.0f);
                fullstop.setSonicBoomCooldown(20);
            }
        }

        if (fullstop.getPreviousScaledVelocity().lengthSqr() < 0.0001) {
            return;
        }

        Collision collision = ServerCollisionDetector.detect(entity, fullstop);

        EntityCollisionHandler.handle(entity, fullstop, collision);
        StatusEffectApplier.applyForceEffects(entity, fullstop);

        impactAesthetic(entity, fullstop, collision);
        impactSound(entity, fullstop, collision);

        double damage = KineticDamageCalculator.calculateDamage(entity, fullstop, collision);

        if (damage > 0) {
            impactDamageSound(entity, damage, collision);
        }

        boolean brokeBlock = false;
        if (!collision.impactedPositions.isEmpty()) {
            brokeBlock = KineticBlockInteractions.handleBlockImpacts(entity, fullstop.getPreviousNativeVelocity(), collision.impactedPositions, collision.impactedHits);
        }

        BounceHandler.apply(entity, fullstop, collision, brokeBlock);

        if (damage > 0) {
            KineticDamageApplier.apply(entity, fullstop, collision, damage);
            StatusEffectApplier.applyDamageEffects(entity, fullstop, damage);
        }
    }

    private static void handlePressure(Entity entity) {
        if (!FullStopConfig.SERVER.enablePressureSimulation.get()) return;
        if (!(entity instanceof LivingEntity living)) return;
        if (living instanceof Player player && player.isCreative()) return;
        if (living.canBreatheUnderwater()) return; // Exclude aquatic mobs

        // High Altitude Air Loss
        if (living instanceof Player) { // Only affect players
            int altitudeStart = FullStopConfig.SERVER.highAltitudeStartLevel.get();
            if (living.getY() > altitudeStart) {
                int y = (int) living.getY();
                double rate = FullStopConfig.SERVER.highAltitudeAirLossRate.get();
                // Lose 1 air every tick, scaled by height difference and rate
                int airLoss = (int) (1 + (y - altitudeStart) * rate / 10);
                living.setAirSupply(living.getAirSupply() - airLoss);
            }
        }

        // Underwater Pressure
        if (living.isUnderWater()) {
            int deepWaterStart = FullStopConfig.SERVER.deepWaterStartLevel.get();
            if (living.getY() < deepWaterStart) {
                double multiplier = FullStopConfig.SERVER.deepWaterAirLossMultiplier.get();
                int airLoss = (int) (1 * multiplier);
                living.setAirSupply(living.getAirSupply() - airLoss);
            }

            int damageDepth = FullStopConfig.SERVER.pressureDamageStartDepth.get();
            if (living.getY() < damageDepth) {
                int tickRate = FullStopConfig.SERVER.pressureDamageTickRate.get();
                if (living.tickCount % tickRate == 0) {
                    float damage = FullStopConfig.SERVER.pressureDamageAmount.get().floatValue();
                    living.hurt(KineticDamageSources.pressure(living), damage);
                }
            }
        }
    }

    private static void impactAesthetic(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (collision.fake()) return;
        Vec3 pos = entity.position();
        if (collision.blockStates != null) {
            for (BlockState blockState : collision.blockStates) {
                CollisionParticleSpawner.spawnParticle(entity.level(), pos, collision, blockState);
            }
        }
    }

    private static void impactSound(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (!fullstop.canPlaySound() || collision.fake()) return;

        float volume = ((float) (fullstop.getStoppingForce() * 0.05f));
        int hitCount = (collision.blockStates != null ? collision.blockStates.size() : 0)
                + (collision.collidingEntities != null ? collision.collidingEntities.size() : 0);
        if (hitCount > 1) volume /= (float) (hitCount * 0.6);
        volume = Mth.clamp(volume, 0.0f, 1.0f);

        float minPitch = 0.9f;
        float maxPitch = 1.7f;
        float pitch = (float) Mth.clamp(minPitch + (fullstop.getStoppingForce() / 100f) * (maxPitch - minPitch), minPitch, maxPitch);

        List<SoundEvent> playedSounds = new ArrayList<>();
        if (collision.collidingEntities != null) {
            for (Entity collidedEntity : collision.collidingEntities) {
                SoundEvent hitSound = SoundEvents.BOOK_PUT;
                if (!playedSounds.contains(hitSound)) {
                    FSSoundPlayer.playSoundServer(entity.level(), collidedEntity.blockPosition(), hitSound, SoundSource.PLAYERS, volume, pitch);
                    playedSounds.add(hitSound);
                }
            }
        }
        if (collision.blockStates != null) {
            for (BlockState blockState : collision.blockStates) {
                SoundType soundType = blockState.getSoundType();
                SoundEvent sound = soundType.getFallSound();
                if (!playedSounds.contains(sound)) {
                    FSSoundPlayer.playSoundServer(entity.level(), entity.blockPosition(), sound, SoundSource.PLAYERS, volume, pitch);
                    playedSounds.add(sound);
                }
                if (entity instanceof Minecart) {
                    FSSoundPlayer.playSoundServer(entity.level(), entity.blockPosition(), SoundEvents.IRON_GOLEM_HURT, SoundSource.PLAYERS, volume / 2, pitch * (float) Math.random());
                    FSSoundPlayer.playSoundServer(entity.level(), entity.blockPosition(), SoundEvents.ANVIL_FALL, SoundSource.PLAYERS, volume / 2, pitch * (float) Math.random());
                }
            }
        }
        fullstop.setSoundCooldown(4);
    }

    private static void impactDamageSound(Entity entity, double damage, Collision collision) {
        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) return;
        if (!(entity instanceof LivingEntity)) return;
        if (damage <= 0) return;

        float volume = Math.min(0.1F * (float) damage, 1.0F);
        float pitch = 0.5F;
        SoundEvent sound = SoundEvents.PLAYER_BIG_FALL;
        if (collision.collisionType == Collision.CollisionType.SOLID) {
            FSSoundPlayer.playSoundServer(entity, sound, SoundSource.PLAYERS, volume, pitch);
        }
    }
}
