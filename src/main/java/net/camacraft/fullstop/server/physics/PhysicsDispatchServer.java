package net.camacraft.fullstop.server.physics;

import com.google.common.collect.Lists;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.particle.CollisionParticleSpawner;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.camacraft.fullstop.server.physics.collision.ServerCollisionDetector;
import net.camacraft.fullstop.server.physics.damage.KineticDamageApplier;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.camacraft.fullstop.server.physics.effects.StatusEffectApplier;
import net.camacraft.fullstop.server.physics.interaction.BounceHandler;
import net.camacraft.fullstop.server.physics.interaction.EntityCollisionHandler;
import net.camacraft.fullstop.server.physics.interaction.KineticBlockInteractions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

/**
 * Server physics orchestrator: ticks capabilities, detects collisions, and hands
 * the results to the specialized handlers. Order matters and lives only here.
 */
public class PhysicsDispatchServer {

    /**
     * Entities slower than this (m/s, squared) skip collision processing entirely.
     * 3 m/s is the smallest speed any downstream system cares about
     * (KineticBlockInteractions.MIN_INTERACTION_VELOCITY).
     */
    private static final double MIN_PROCESS_SPEED_SQR = 3.0 * 3.0;


    /** A collision only makes noise/particles when this much speed (m/s) was lost. */
    private static final double MIN_AESTHETIC_STOPPING_FORCE = 2.0;

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
            // Keeps persistent ice-crack overlays alive (clients purge unrefreshed
            // destruction progress) and heals expired ones.
            KineticBlockInteractions.tickCracks(level);

            // Copy: handlers may mount/dismount entities, which mutates the entity list.
            List<Entity> entities = Lists.newArrayList(level.getAllEntities());
            for (Entity entity : entities) {
                onEntityTick(entity);
            }
        }
    }

    /** Drops per-level state (accumulated ice cracks) when a level unloads. */
    @SubscribeEvent
    public static void onLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            KineticBlockInteractions.onLevelUnload(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onEntityChangeDimension(EntityTravelToDimensionEvent event) {
        markTeleported(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityTeleport(EntityTeleportEvent event) {
        markTeleported(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        markTeleported(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        markTeleported(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        markTeleported(event.getEntity());
    }

    /** Clears any lingering block-crack overlay when an entity dies, unloads, or changes dimension. */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            KineticBlockInteractions.onEntityLeave(event.getEntity(), serverLevel);
        }
    }

    /** Runs on both logical sides so the dismount grace period also works on dedicated servers. */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.justDismounted();
                // Mounting/dismounting snaps the rider's position (right-clicking
                // a minecart from several blocks away teleports the player onto
                // it) — far below the 60 m/s discontinuity guard, so unmarked it
                // read as a violent move that smashed the blocks around the cart
                // and killed the rider.
                cap.setHasTeleported(true);
            }
        }

        // Momentum transfer at the mount boundary, in BOTH directions. The
        // velocity source is the vehicle's observed per-tick movement (position
        // minus old position) — valid on either logical side for any ticked
        // entity, with no capability or packet involved. Crucially, this
        // handler also runs on the DRIVER'S CLIENT, the side that actually owns
        // a driven vehicle's motion: applying the value there at the exact
        // takeover/release moment cannot race anything. (The earlier
        // server→client packet approach lost exactly that race — the boat
        // dead-stopped on entry no matter how the packet was timed.)
        Entity vehicle = event.getEntityBeingMounted();
        Vec3 vehicleMotion = new Vec3(vehicle.getX() - vehicle.xOld,
                vehicle.getY() - vehicle.yOld,
                vehicle.getZ() - vehicle.zOld);

        if (event.isMounting()) {
            // Boarding a MOVING boat keeps it moving: the new driver's client
            // otherwise starts simulating from the stale synced motion (~0).
            // First passenger only — a second rider must not reset the boat.
            if (vehicle instanceof Boat && vehicle.getFirstPassenger() == null
                    && vehicleMotion.lengthSqr() > 1.0e-6) {
                vehicle.setDeltaMovement(vehicleMotion);
            }
        } else {
            // Dismounting — including being thrown off a destroyed vehicle
            // (ejecting passengers fires this event too): the passenger keeps
            // the vehicle's momentum instead of freezing in place.
            if (vehicleMotion.lengthSqr() > 1.0e-4) {
                Entity rider = event.getEntityMounting();
                rider.setDeltaMovement(vehicleMotion);
                rider.hasImpulse = true;
            }
        }
    }

    /**
     * Vanilla already uses the falling_stalactite damage type for falling dripstone;
     * FullStop only scales the damage. (Replaces the old reflection-into-event-source hack.)
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getSource().is(DamageTypes.FALLING_STALACTITE)) {
            event.setAmount(event.getAmount() * SERVER.dripstoneDamageMultiplier.get().floatValue());
        }
    }

    private static void markTeleported(Entity entity) {
        if (entity instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.setHasTeleported(true);
            }
        }
    }

    private static void onEntityTick(Entity entity) {
        // Cheap type/field exclusions first, so drop-like entities never even
        // resolve (and on first touch, allocate) a capability. The NoAI check
        // runs after the grab so it can use the capability's once-a-second cache
        // instead of a locked SynchedEntityData read per mob per tick.
        if (DamageImmunityRules.unphysableIgnoringAi(entity)) return;

        FullStopCapability fullstop = grabCapability(entity);
        if (fullstop == null) return;

        if (entity instanceof Mob mob && fullstop.isNoAiCached(mob)) return;

        if (entity.tickCount != fullstop.getLastTick()) {
            fullstop.tick(entity);
            fullstop.setLastTick(entity.tickCount);
        }

        if (fullstop.getIsDamageImmune()) {
            fullstop.resetVelocity();
            return;
        }

        // G-force status effects depend on the smoothed average, not current speed,
        // so they must run before the speed gate (a hard stop leaves speed ~0).
        StatusEffectApplier.applyForceEffects(entity, fullstop);

        sonicBoom(entity, fullstop);

        // Pre-impact speed, not last tick's: the damage tick of a two-tick impact
        // arrives with only the small post-contact velocity remaining.
        if (fullstop.getPreImpactScaledVelocity().lengthSqr() < MIN_PROCESS_SPEED_SQR) {
            return;
        }

        // Doors ahead open before they ever become collisions (no speed scrub).
        KineticBlockInteractions.openDoorsAhead(entity, fullstop.getCurrentNativeVelocity());

        // Buttons are shapeless, invisible to the collision rays — swept separately.
        KineticBlockInteractions.pressButtonsInPath(entity, fullstop.getCurrentNativeVelocity());

        Collision collision = ServerCollisionDetector.detect(entity, fullstop);

        EntityCollisionHandler.handle(entity, fullstop, collision);

        boolean significantImpact = !collision.fake() && fullstop.getStoppingForce() > MIN_AESTHETIC_STOPPING_FORCE;
        if (significantImpact) {
            impactAesthetic(entity, collision);
            impactSound(entity, fullstop, collision);
        }

        double damage = KineticDamageCalculator.calculateDamage(entity, fullstop, collision);

        boolean brokeBlock = false;
        if (!collision.impactedPositions.isEmpty()) {
            KineticBlockInteractions.ImpactResult impactResult = KineticBlockInteractions.handleBlockImpacts(
                    entity, fullstop.getPreviousNativeVelocity(), collision.impactedPositions, collision.impactedHits);
            brokeBlock = impactResult.brokeBlock();
            damage += impactResult.smashDamage();
        } else if (entity.level() instanceof ServerLevel serverLevel) {
            KineticBlockInteractions.clearCrack(entity, serverLevel);
        }

        BounceHandler.apply(entity, fullstop, collision, brokeBlock);

        if (damage >= 1) {
            // Sound and sprain only when the applier actually hurt something —
            // it can bail on its cooldown or mitigate everything to zero, and a
            // big-fall thud with no damage reads as a bug.
            if (KineticDamageApplier.apply(entity, fullstop, collision, damage)) {
                impactDamageSound(entity, damage, collision);
                StatusEffectApplier.applyDamageEffects(entity, fullstop, collision, damage);
            }
        }
    }

    /** 343 m/s (speed of sound), squared for allocation- and sqrt-free comparison. */
    private static final double SONIC_BOOM_SPEED_SQR = 343.0 * 343.0;

    private static void sonicBoom(Entity entity, FullStopCapability fullstop) {
        // Supersonic on two consecutive ticks: a real supersonic entity sustains its
        // speed, while a respawn/teleport position jump reads as huge for one tick only.
        if (fullstop.getCurrentScaledVelocity().lengthSqr() >= SONIC_BOOM_SPEED_SQR
                && fullstop.getPreviousScaledVelocity().lengthSqr() >= SONIC_BOOM_SPEED_SQR
                && fullstop.canSonicBoom()) {
            if (entity.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.SONIC_BOOM, entity.getX(), entity.getY(), entity.getZ(), 1, 0, 0, 0, 0);
                FSSoundPlayer.playSoundServer(entity, SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 4.0f,
                        (1.0f + (entity.level().random.nextFloat() - entity.level().random.nextFloat()) * 0.2f) * 0.7f);
                FSSoundPlayer.playSoundServer(entity, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.NEUTRAL, 4.0f, 2.0f);
                fullstop.setSonicBoomCooldown(20);
            }
        }
    }

    private static void impactAesthetic(Entity entity, Collision collision) {
        for (BlockState blockState : collision.blockStates) {
            CollisionParticleSpawner.spawnParticle(entity.level(), entity.position(), collision, blockState);
        }
    }

    private static void impactSound(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (!fullstop.canPlaySound()) return;

        float volume = ((float) (fullstop.getStoppingForce() * 0.05f));
        int hitCount = collision.blockStates.size() + collision.collidingEntities.size();
        if (hitCount > 1) volume /= (float) (hitCount * 0.6);
        volume = Mth.clamp(volume, 0.0f, 1.0f);

        float minPitch = 0.9f;
        float maxPitch = 1.7f;
        float pitch = (float) Mth.clamp(minPitch + (fullstop.getStoppingForce() / 100f) * (maxPitch - minPitch), minPitch, maxPitch);

        List<SoundEvent> playedSounds = new ArrayList<>();
        for (Entity collidedEntity : collision.collidingEntities) {
            SoundEvent hitSound = SoundEvents.BOOK_PUT;
            if (!playedSounds.contains(hitSound)) {
                FSSoundPlayer.playSoundServer(entity.level(), collidedEntity.blockPosition(), hitSound, SoundSource.PLAYERS, volume, pitch);
                playedSounds.add(hitSound);
            }
        }
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
        fullstop.setSoundCooldown(4);
    }

    private static void impactDamageSound(Entity entity, double damage, Collision collision) {
        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) return;
        if (!(entity instanceof LivingEntity)) return;

        float volume = Math.min(0.1F * (float) damage, 1.0F);
        float pitch = 0.5F;
        if (collision.collisionType == Collision.CollisionType.SOLID) {
            FSSoundPlayer.playSoundServer(entity, SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, volume, pitch);
        }
    }
}
