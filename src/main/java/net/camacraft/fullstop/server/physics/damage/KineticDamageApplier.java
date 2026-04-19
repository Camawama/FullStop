package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.damage.DamageMitigation;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.common.util.MathUtils;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

public class KineticDamageApplier {

    public static void apply(Entity entity, FullStopCapability fullstop, Collision collision, double damage) {
        double previousVelocity = fullstop.getPreviousScaledVelocity().length();
        if (damage < 1) return;
        if (entity instanceof ItemEntity) return;

        BlockPos collisionPos = collision.impactedPositions.isEmpty() ? null : collision.impactedPositions.get(0);
        int collisionEntityId = (collision.collidingEntities != null && !collision.collidingEntities.isEmpty())
                ? collision.collidingEntities.get(0).getId()
                : -1;
        if (fullstop.isCollisionOnCooldown(entity.tickCount, collisionPos, collisionEntityId, 5)) {
            return;
        }

        TextColor color;
        double maxVelocity = 78.40;
        double t = Math.min(previousVelocity / maxVelocity, 1.0);

        int green   = 0x00FF00;
        int yellow  = 0xFFFF00;
        int red     = 0xFF0000;
        int darkRed = 0x800000;

        int rgb;
        if (t < 0.33) {
            double nt = t / 0.33;
            rgb = MathUtils.lerpColor(green, yellow, nt);
        } else if (t < 0.66) {
            double nt = (t - 0.33) / 0.33;
            rgb = MathUtils.lerpColor(yellow, red, nt);
        } else {
            double nt = (t - 0.66) / 0.34;
            rgb = MathUtils.lerpColor(red, darkRed, nt);
        }
        color = TextColor.fromRgb(rgb);

        String velocityToDisplay = String.format("(going %.2f m/s)", previousVelocity);
        DamageSources sources = entity.damageSources();

        if (collision.collisionType != Collision.CollisionType.ENTITY) {
            applyBlockCollisionDamage(entity, fullstop, damage, velocityToDisplay, color, sources);
        }

        if (collision.collisionType == Collision.CollisionType.ENTITY && collision.collidingEntities != null && !collision.collidingEntities.isEmpty()) {
            applyEntityCollisionDamage(entity, fullstop, collision, damage, velocityToDisplay, color, sources);
        }

        fullstop.recordCollision(entity.tickCount, collisionPos, collisionEntityId);
    }

    private static void applyBlockCollisionDamage(Entity entity, FullStopCapability fullstop, double damage, String velocityToDisplay, TextColor color, DamageSources sources) {
        DamageSource baseSource;
        if (fullstop.isMostlyDownward()) {
            baseSource = sources.fall();
        } else if (fullstop.isMostlyUpward()) {
            baseSource = sources.flyIntoWall();
        } else {
            baseSource = sources.flyIntoWall();
        }

        DamageSource customSource = net.camacraft.fullstop.common.physics.damage.KineticDamageSources.makeSelfSource(baseSource, velocityToDisplay, color, fullstop.isMostlyDownward(), fullstop.isMostlyUpward());

        if (fullstop.isMostlyDownward()) {
            double totalMass = EntityStackUtils.getAllEntitiesInStack(entity).stream().mapToDouble(EntityStackUtils::getEntityMass).sum();
            double selfMass = EntityStackUtils.getEntityMass(entity);
            if (selfMass < 0.001) selfMass = 0.001;

            float crushFactor = (float) (totalMass / selfMass);
            float selfDamage = (float) damage * crushFactor;

            if (entity instanceof LivingEntity living) {
                selfDamage = DamageMitigation.applyArmorReduction(living, selfDamage, fullstop.isMostlyDownward(), fullstop.isMostlyUpward());
                entity.hurt(customSource, selfDamage);
            } else {
                entity.hurt(customSource, selfDamage);
            }

            float passengerDamage = (float) damage * 0.5f;
            for (Entity passenger : EntityStackUtils.getAllEntitiesInStack(entity)) {
                if (passenger == entity) continue;
                passenger.hurt(customSource, passengerDamage);
            }

        } else {
            if (entity instanceof LivingEntity living) {
                float finalSelfDamage = DamageMitigation.applyArmorReduction(living, (float) damage, fullstop.isMostlyDownward(), fullstop.isMostlyUpward());
                entity.hurt(customSource, finalSelfDamage);
            } else {
                entity.hurt(customSource, (float) damage);
            }

            if (entity.isPassenger() && damage > 5) {
                entity.stopRiding();
            }
        }
    }

    private static void applyEntityCollisionDamage(Entity entity, FullStopCapability fullstop, Collision collision, double damage, String velocityToDisplay, TextColor color, DamageSources sources) {
        List<LivingEntity> validTargets = collision.collidingEntities.stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(living -> !(living instanceof IronGolem))
                .toList();

        List<IronGolem> collidedGolems = collision.collidingEntities.stream()
                .filter(e -> e instanceof IronGolem)
                .map(e -> (IronGolem) e)
                .toList();

        for (IronGolem golem : collidedGolems) {
            float baseVolume = 0.6f;
            float basePitch = 1.0f;

            float volume = baseVolume + (float)damage * 0.05f;
            volume = Mth.clamp(volume, 0.6f, 1.8f);

            float pitch = basePitch - (float)damage * 0.02f;
            pitch = Mth.clamp(pitch, 0.7f, 1.2f);

            FSSoundPlayer.playSoundServer(golem, SoundEvents.IRON_GOLEM_HURT, SoundSource.NEUTRAL, volume, pitch);

            if (entity instanceof LivingEntity livingTarget) {
                golem.setTarget(livingTarget);
            }
        }

        LivingEntity collidedExample = collision.collidingEntities.stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .findFirst()
                .orElse(null);

        int colliders = validTargets.size();
        float splitEntityDamage = (float) damage / (colliders + 1);
        splitEntityDamage = scaleDamageByRelativeVelocity(entity, collision, splitEntityDamage);

        Entity firstCollider = collision.collidingEntities.get(0);
        if (!entity.onGround() && !firstCollider.onGround()) {
            if (VelocityMath.velocitiesAreSimilar(entity.getDeltaMovement(), firstCollider.getDeltaMovement(), 0.1)) {
                splitEntityDamage = 0;
            }
        }

        // Determine who is the "attacker" for the death message
        // The entity with the greater momentum (mass * velocity) should be the one "attacking".
        Vec3 myVel = VelocityMath.entityVelocity(entity);
        Vec3 otherVel = VelocityMath.entityVelocity(firstCollider);
        double myMass = EntityStackUtils.getEntityMass(entity);
        double otherMass = EntityStackUtils.getEntityMass(firstCollider);
        double myMomentum = myVel.length() * myMass;
        double otherMomentum = otherVel.length() * otherMass;

        // Calculate relative velocity (impact speed)
        double impactSpeed = myVel.subtract(otherVel).length();

        boolean otherHasMoreMomentum = otherMomentum > myMomentum;
        boolean bothMovingFast = myVel.length() > 2.0 && otherVel.length() > 2.0; // Threshold for "both moving"

        String displayVelocityStr;
        if (bothMovingFast) {
             displayVelocityStr = String.format("(at %.2f m/s)", impactSpeed);
        } else if (otherHasMoreMomentum) {
            displayVelocityStr = String.format("(going %.2f m/s)", otherVel.length());
        } else {
            displayVelocityStr = velocityToDisplay;
        }
        
        DamageSource selfSource;
        if (collidedExample != null && entity instanceof LivingEntity living) {
            if (bothMovingFast) {
                // Mutual collision
                 selfSource = net.camacraft.fullstop.common.physics.damage.KineticDamageSources.makeEntityMutualCollisionSource(
                        sources.flyIntoWall(),
                        living,
                        collidedExample,
                        displayVelocityStr,
                        color
                );
            } else if (otherHasMoreMomentum) {
                // If the other entity has more momentum, they are the attacker.
                selfSource = net.camacraft.fullstop.common.physics.damage.KineticDamageSources.makeEntityAttackerSource(
                        sources,
                        collidedExample,
                        displayVelocityStr,
                        color,
                        fullstop.isMostlyDownward() // This might need adjustment if the other entity is falling on us
                );
            } else {
                selfSource = net.camacraft.fullstop.common.physics.damage.KineticDamageSources.makeEntityCollisionSelfSource(
                        sources.flyIntoWall(),
                        living,
                        collidedExample,
                        displayVelocityStr,
                        color,
                        fullstop.isMostlyDownward(),
                        fullstop.isMostlyUpward()
                );
            }
        } else {
            selfSource = net.camacraft.fullstop.common.physics.damage.KineticDamageSources.makeSelfSource(
                    sources.flyIntoWall(),
                    displayVelocityStr,
                    color,
                    fullstop.isMostlyDownward(),
                    fullstop.isMostlyUpward()
            );
        }

        if (!(entity instanceof LivingEntity living) || DamageImmunityRules.isDamageImmune(living) || fullstop.getIsDamageImmune()) {
            splitEntityDamage = 0;
        }

        double horseFallDampening = 1.0;
        if (entity instanceof Horse && splitEntityDamage < horseFallDampening && fullstop.isMostlyDownward()) {
            splitEntityDamage = 0;
        }

        if (splitEntityDamage <= 0) {
            splitEntityDamage = 0;
        }

        double stackMass = EntityStackUtils.getAllEntitiesInStack(entity).stream().mapToDouble(EntityStackUtils::getEntityMass).sum();
        double selfMass = EntityStackUtils.getEntityMass(entity);
        if (selfMass < 0.001) selfMass = 0.001;

        float selfDamage = splitEntityDamage;
        if (fullstop.isMostlyDownward()) {
            selfDamage *= (float) (stackMass / selfMass);
        }

        if (!(entity instanceof AbstractMinecart) && !(entity instanceof IronGolem)) {
            entity.hurt(selfSource, selfDamage);
        }

        if (fullstop.isMostlyDownward()) {
            float passengerDamage = splitEntityDamage * 0.5f;
            for (Entity passenger : EntityStackUtils.getAllEntitiesInStack(entity)) {
                if (passenger == entity) continue;
                passenger.hurt(selfSource, passengerDamage);
            }
        }

        if (entity instanceof LivingEntity living) {
            for (LivingEntity target : validTargets) {
                DamageSource attackerSource;
                if (otherHasMoreMomentum) {
                     attackerSource = net.camacraft.fullstop.common.physics.damage.KineticDamageSources.makeEntityCollisionSelfSource(
                             sources.flyIntoWall(),
                             target,
                             living,
                             displayVelocityStr,
                             color,
                             fullstop.isMostlyDownward(),
                             fullstop.isMostlyUpward()
                     );
                } else {
                    attackerSource = net.camacraft.fullstop.common.physics.damage.KineticDamageSources.makeEntityAttackerSource(
                            sources,
                            living,
                            velocityToDisplay,
                            color,
                            fullstop.isMostlyDownward()
                    );
                }


                double targetMass = EntityStackUtils.getEntityMass(target);
                if (targetMass < 0.001) targetMass = 0.001;

                float targetDamageScale = (float) (stackMass / targetMass);
                float targetScaledDamage = splitEntityDamage * targetDamageScale;

                if (DamageImmunityRules.isDamageImmune(target)) {
                    targetScaledDamage = 0;
                } else {
                    targetScaledDamage = DamageMitigation.applyArmorReduction(target, targetScaledDamage, fullstop.isMostlyDownward(), fullstop.isMostlyUpward());
                }

                target.hurt(attackerSource, targetScaledDamage);
            }
        }
    }

    private static float scaleDamageByRelativeVelocity(Entity entity, Collision collision, float baseDamage) {
        if (collision.collidingEntities == null || collision.collidingEntities.isEmpty()) {
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
