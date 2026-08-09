package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources.VelocityDisplay;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class KineticDamageApplier {

    /** Ticks after a kinetic hit during which further kinetic damage is suppressed. */
    private static final int DAMAGE_COOLDOWN_TICKS = 5;

    /** @return whether any damage was actually dealt (drives the cooldown, impact sound, and sprain). */
    public static boolean apply(Entity entity, FullStopCapability fullstop, Collision collision, double damage) {
        if (damage < 1) return false;
        if (entity instanceof ItemEntity) return false;

        // Time-based rather than per-block, so sliding along a wall (a new block
        // every tick) can't deal damage every tick. But a two-tick impact can
        // land a small partial hit on the contact tick and its REAL stopping
        // force one tick later; hard-blocking on the cooldown made the partial
        // swallow the real hit (the source of wildly inconsistent fall damage —
        // some heights dealt one heart because only the contact-tick sliver ever
        // applied). During the cooldown the damage is topped UP to the larger
        // value instead: only the difference is dealt.
        double alreadyApplied = fullstop.getRecentAppliedDamage(entity.tickCount);
        if (alreadyApplied > 0) {
            double topUp = damage - alreadyApplied;
            if (topUp < 1) return false;
            damage = topUp;
        }
        double totalThisImpact = alreadyApplied + damage;

        // Pre-impact speed: on the damage tick of a two-tick impact, last tick's
        // velocity is just the post-contact remnant — the death message would
        // claim a near-zero speed for a lethal hit.
        double previousSpeed = fullstop.getPreImpactScaledVelocity().length();
        VelocityDisplay velocity = FullStopDamageSources.velocityDisplay(previousSpeed);
        DamageSources sources = entity.damageSources();

        boolean anyHurt = false;
        if (collision.collisionType != Collision.CollisionType.ENTITY) {
            anyHurt = applyBlockCollisionDamage(entity, fullstop, collision, damage, velocity);
        } else if (!collision.collidingEntities.isEmpty()) {
            anyHurt = applyEntityCollisionDamage(entity, fullstop, collision, damage, velocity, sources);
        }

        // Only an impact that actually hurt something starts the cooldown. Marking
        // it for fully-mitigated grazes swallowed the REAL hit that followed within
        // 5 ticks (elytra flights grazing terrain just before a wall, for example)
        // — and with vanilla fall damage cancelled, that meant no damage at all.
        if (anyHurt) {
            fullstop.markDamageApplied(entity.tickCount, DAMAGE_COOLDOWN_TICKS, totalThisImpact);
        }
        return anyHurt;
    }

    private static boolean applyBlockCollisionDamage(Entity entity, FullStopCapability fullstop, Collision collision,
                                                     double damage, VelocityDisplay velocity) {
        boolean hitDripstone = false;
        for (BlockState state : collision.blockStates) {
            if (state.is(Blocks.POINTED_DRIPSTONE) && state.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.UP) {
                hitDripstone = true;
                break;
            }
        }

        boolean downward = fullstop.isMostlyDownward();
        boolean upward = fullstop.isMostlyUpward();
        boolean waterFlop = collision.collisionType == Collision.CollisionType.WATER
                && downward && KineticDamageCalculator.isBellyFlop(entity);

        DamageSource customSource = FullStopDamageSources.kineticSelf(
                entity, velocity, downward, upward, hitDripstone && downward, waterFlop);

        boolean anyHurt = false;
        if (downward) {
            // Riders press down on whatever they sit on: the bottom of the stack takes
            // its share of the whole stack's mass.
            double totalMass = EntityStackUtils.getAllEntitiesInStack(entity).stream()
                    .mapToDouble(EntityStackUtils::getEntityMass).sum();
            double selfMass = Math.max(EntityStackUtils.getEntityMass(entity), 0.001);

            float crushFactor = (float) (totalMass / selfMass);
            float selfDamage = (float) damage * crushFactor;

            if (entity instanceof LivingEntity living) {
                selfDamage = DamageMitigation.applyArmorReduction(living, selfDamage, true, false);
            }
            if (selfDamage > 0) {
                anyHurt |= entity.hurt(customSource, selfDamage);
            }

            float passengerDamage = (float) damage * 0.5f;
            if (passengerDamage > 0) {
                for (Entity passenger : EntityStackUtils.getAllEntitiesInStack(entity)) {
                    if (passenger == entity) continue;
                    anyHurt |= hurtPassenger(passenger, customSource, passengerDamage, true, false);
                }
            }
        } else {
            float selfDamage = (float) damage;
            if (entity instanceof LivingEntity living) {
                selfDamage = DamageMitigation.applyArmorReduction(living, selfDamage, false, upward);
            }
            if (selfDamage > 0) {
                anyHurt |= entity.hurt(customSource, selfDamage);
            }

            if (entity.isPassenger() && damage > 5) {
                entity.stopRiding();
            }
        }
        return anyHurt;
    }

    /** Passengers get the same immunity rules and armor mitigation as everyone else. */
    private static boolean hurtPassenger(Entity passenger, DamageSource source, float damage,
                                         boolean downward, boolean upward) {
        if (passenger instanceof LivingEntity living) {
            if (DamageImmunityRules.isDamageImmune(living)) return false;
            damage = DamageMitigation.applyArmorReduction(living, damage, downward, upward);
        }
        return damage > 0 && passenger.hurt(source, damage);
    }

    private static boolean applyEntityCollisionDamage(Entity entity, FullStopCapability fullstop, Collision collision,
                                                      double damage, VelocityDisplay velocity, DamageSources sources) {
        List<LivingEntity> validTargets = collision.collidingEntities.stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(living -> !(living instanceof IronGolem))
                .toList();

        reactGolems(entity, collision, damage);

        // ANY entity, not just living ones: filtering for LivingEntity here made
        // a boat/minecart collider fall through to the generic "experienced
        // kinetic energy" message instead of naming the vehicle.
        Entity collidedExample = collision.collidingEntities.get(0);

        int colliders = validTargets.size();
        float splitEntityDamage = (float) damage / (colliders + 1);
        splitEntityDamage = scaleDamageByRelativeVelocity(entity, collision, splitEntityDamage);

        Entity firstCollider = collision.collidingEntities.get(0);
        if (!entity.onGround() && !firstCollider.onGround()) {
            if (VelocityMath.velocitiesAreSimilar(entity.getDeltaMovement(), firstCollider.getDeltaMovement(), 0.1)) {
                splitEntityDamage = 0;
            }
        }

        Vec3 myVel = VelocityMath.entityVelocity(entity);
        Vec3 otherVel = VelocityMath.entityVelocity(firstCollider);
        double myMomentum = myVel.length() * EntityStackUtils.getEntityMass(entity);
        double otherMomentum = otherVel.length() * EntityStackUtils.getEntityMass(firstCollider);
        double impactSpeed = myVel.subtract(otherVel).length();

        boolean otherHasMoreMomentum = otherMomentum > myMomentum;
        boolean bothMovingFast = myVel.length() > 2.0 && otherVel.length() > 2.0;

        VelocityDisplay displayVelocity;
        if (bothMovingFast) {
            displayVelocity = FullStopDamageSources.mutualVelocityDisplay(impactSpeed);
        } else if (otherHasMoreMomentum) {
            displayVelocity = FullStopDamageSources.velocityDisplay(otherVel.length());
        } else {
            displayVelocity = velocity;
        }

        boolean downward = fullstop.isMostlyDownward();
        boolean upward = fullstop.isMostlyUpward();

        DamageSource selfSource;
        if (bothMovingFast) {
            selfSource = FullStopDamageSources.entityMutualCollision(entity, collidedExample, displayVelocity);
        } else if (otherHasMoreMomentum) {
            selfSource = FullStopDamageSources.entityAttacker(sources, collidedExample, displayVelocity, downward);
        } else {
            selfSource = FullStopDamageSources.entityCollisionSelf(entity, collidedExample, displayVelocity, downward, upward);
        }

        // Self-damage and dealt-damage are tracked separately: an immune or
        // non-living MOVER (creative player, minecart) takes nothing itself but
        // still hits what it rams — zeroing the shared value silenced both.
        float selfSplitDamage = splitEntityDamage;
        if (!(entity instanceof LivingEntity self) || DamageImmunityRules.isDamageImmune(self) || fullstop.getIsDamageImmune()) {
            selfSplitDamage = 0;
        }

        if (entity instanceof AbstractHorse && selfSplitDamage < 1.0 && downward) {
            selfSplitDamage = 0;
        }

        double stackMass = EntityStackUtils.getAllEntitiesInStack(entity).stream()
                .mapToDouble(EntityStackUtils::getEntityMass).sum();
        double selfMass = Math.max(EntityStackUtils.getEntityMass(entity), 0.001);

        float selfDamage = selfSplitDamage;
        if (downward) {
            selfDamage *= (float) (stackMass / selfMass);
        }

        boolean anyHurt = false;
        if (selfDamage > 0 && !(entity instanceof AbstractMinecart) && !(entity instanceof IronGolem)) {
            anyHurt |= entity.hurt(selfSource, selfDamage);
        }

        if (downward && selfSplitDamage > 0) {
            float passengerDamage = selfSplitDamage * 0.5f;
            for (Entity passenger : EntityStackUtils.getAllEntitiesInStack(entity)) {
                if (passenger == entity) continue;
                anyHurt |= hurtPassenger(passenger, selfSource, passengerDamage, downward, upward);
            }
        }

        // ANY mover damages what it rams. The old `instanceof LivingEntity` gate
        // silently disabled this whole loop for boats, minecarts and falling
        // blocks — a boat plowing into a standing player dealt nothing (the only
        // damage a MOVING player saw came from their own victim-side pass, which
        // a standing player never runs).
        for (LivingEntity target : validTargets) {
            DamageSource attackerSource;
            if (otherHasMoreMomentum) {
                attackerSource = FullStopDamageSources.entityCollisionSelf(target, entity, displayVelocity, downward, upward);
            } else {
                attackerSource = FullStopDamageSources.entityAttacker(sources, entity, velocity, downward);
            }

            double targetMass = Math.max(EntityStackUtils.getEntityMass(target), 0.001);
            float targetScaledDamage = splitEntityDamage * (float) (stackMass / targetMass);

            if (DamageImmunityRules.isDamageImmune(target)) {
                continue;
            }
            targetScaledDamage = DamageMitigation.applyArmorReduction(target, targetScaledDamage, downward, upward);

            if (targetScaledDamage > 0) {
                anyHurt |= target.hurt(attackerSource, targetScaledDamage);
            }
        }
        return anyHurt;
    }

    /** Golems shrug off impacts, clang, and get angry at whoever hit them hard. */
    private static void reactGolems(Entity entity, Collision collision, double damage) {
        for (Entity collided : collision.collidingEntities) {
            if (!(collided instanceof IronGolem golem)) continue;

            float volume = Mth.clamp(0.6f + (float) damage * 0.05f, 0.6f, 1.8f);
            float pitch = Mth.clamp(1.0f - (float) damage * 0.02f, 0.7f, 1.2f);

            FSSoundPlayer.playSoundServer(golem, SoundEvents.IRON_GOLEM_HURT, SoundSource.NEUTRAL, volume, pitch);

            // Only a genuinely hard hit reads as an attack — a gentle bump (or being
            // nudged into the golem by something else) shouldn't make it hunt you.
            if (entity instanceof LivingEntity livingTarget && damage >= 4.0) {
                golem.setTarget(livingTarget);
            }
        }
    }

    private static float scaleDamageByRelativeVelocity(Entity entity, Collision collision, float baseDamage) {
        if (collision.collidingEntities.isEmpty()) {
            return baseDamage;
        }

        double attackerSpeed = VelocityMath.entityVelocity(entity).length();
        double averageTargetSpeed = collision.collidingEntities.stream()
                .mapToDouble(other -> VelocityMath.entityVelocity(other).length())
                .average()
                .orElse(0.0);
        double maxSpeed = Math.max(attackerSpeed, averageTargetSpeed);
        if (maxSpeed <= 0.001) {
            return baseDamage;
        }

        double averageRelativeSpeed = collision.collidingEntities.stream()
                .mapToDouble(other -> VelocityMath.entityVelocity(entity).subtract(VelocityMath.entityVelocity(other)).length())
                .average()
                .orElse(attackerSpeed);

        double scale = Mth.clamp(averageRelativeSpeed / maxSpeed, 0.0, 2.0);
        return (float) (baseDamage * scale);
    }
}
