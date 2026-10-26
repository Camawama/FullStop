package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.physics.rules.PointedBlocks;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources.VelocityDisplay;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import org.jetbrains.annotations.Nullable;

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
        boolean anyHurt = applyBlockCollisionDamage(entity, fullstop, collision, damage, velocity);

        // Only an impact that actually hurt something starts the cooldown. Marking
        // it for fully-mitigated grazes swallowed the REAL hit that followed within
        // 5 ticks (elytra flights grazing terrain just before a wall, for example)
        // — and with vanilla fall damage cancelled, that meant no damage at all.
        if (anyHurt) {
            fullstop.markDamageApplied(entity.tickCount, DAMAGE_COOLDOWN_TICKS, totalThisImpact);
        }
        // "Terminal velocity": a landing from a fall at 50 m/s or more, lived through.
        if (entity instanceof net.minecraft.server.level.ServerPlayer player && player.isAlive()
                && fullstop.isFall()
                && previousSpeed >= net.camacraft.fullstop.advancement.FullStopTriggers.TERMINAL_VELOCITY_MPS) {
            net.camacraft.fullstop.advancement.FullStopTriggers.TERMINAL_VELOCITY.trigger(player);
        }
        return anyHurt;
    }

    private static boolean applyBlockCollisionDamage(Entity entity, FullStopCapability fullstop, Collision collision,
                                                     double damage, VelocityDisplay velocity) {
        boolean downward = fullstop.isMostlyDownward();
        boolean upward = fullstop.isMostlyUpward();
        // Downward is body-relative (the boots, the crush factor, a belly flop into water); a
        // FALL also needs gravity (the fall damage type, Feather Falling, the stalagmite).
        boolean fall = fullstop.isFall();
        boolean waterFlop = collision.collisionType == Collision.CollisionType.WATER
                && downward && KineticDamageCalculator.isBellyFlop(entity);

        // Met at a pointed block's point (PointedBlocks): its own damage type (dripstone's stalagmite), for a fall onto
        // the point or a ram into one at the side; a body rising into a point from below hits a ceiling.
        Vec3 before = fullstop.getPreImpactWorldVelocity();
        PointedBlocks.Hit point = PointedBlocks.collisionHit(entity, collision, before, before.subtract(fullstop.getCurrentWorldVelocity()));
        boolean pointVertical = point != null && Math.abs(fullstop.getFrame().vertical(point.tip())) > 0.7;
        net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> pointType =
                point != null && point.speed() > point.rule().minSpeed() && (fall || !pointVertical) ? point.rule().damageType() : null;
        DamageSource customSource = FullStopDamageSources.kineticSelf(
                entity, velocity, downward, upward, fall, pointType, waterFlop);

        boolean anyHurt = false;
        // The riders, taken BEFORE the vehicle is hurt: a boat hurt hard enough breaks and throws
        // them out, and then they would miss their share of its crash.
        List<Entity> riders = new java.util.ArrayList<>();
        for (Entity passenger : EntityStack.selfAndPassengers(entity)) {
            if (passenger != entity) riders.add(passenger);
        }
        if (downward) {
            // Riders press down on whatever they sit on: the bottom of the stack takes
            // its share of the whole stack's mass.
            double totalMass = EntityStack.sum(entity, EntityStackUtils::getEntityMass);
            double selfMass = Math.max(EntityStackUtils.getEntityMass(entity), 0.001);

            float crushFactor = (float) (totalMass / selfMass);
            float selfDamage = (float) damage * crushFactor;

            if (entity instanceof LivingEntity living) {
                selfDamage = DamageMitigation.applyArmorReduction(living, selfDamage, true, false, fall);
            }
            if (selfDamage > 0) {
                anyHurt |= entity.hurt(customSource, selfDamage);
                if (waterFlop && anyHurt && entity instanceof net.minecraft.server.level.ServerPlayer player && player.isAlive()) {
                    net.camacraft.fullstop.advancement.FullStopTriggers.BELLY_FLOP.trigger(player);
                }
            }

            float passengerDamage = (float) damage * 0.5f;
            if (passengerDamage > 0) {
                for (Entity passenger : riders) {
                    anyHurt |= hurtPassenger(passenger, customSource, passengerDamage, true, false, fall);
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

            // A vehicle that isn't alive itself (a boat, a cart, a modded vehicle such as an MKW
            // kart) crashing into a wall: its own hurt() barely matters (a kart's does nothing) and
            // its riders' boxes never touch the wall, so they get their share here, as they do of
            // a landing (above) and of a ship's hit - each under its own immunity and IMPACT claim.
            if (!(entity instanceof LivingEntity)) {
                float passengerDamage = (float) damage * 0.5f;
                if (passengerDamage > 0) {
                    for (Entity passenger : riders) {
                        anyHurt |= hurtPassenger(passenger, customSource, passengerDamage, false, upward, false);
                    }
                }
            }

            // Only a hit that actually landed throws the rider off: a hurt()
            // cancelled by another mod (a racer's damage shield) or fully
            // absorbed must not eject them anyway. A ship's seats hold their
            // riders whatever happens (seat belts: see deckMomentumSeated).
            if (anyHurt && entity.isPassenger() && damage > 5
                    && !PhysicsClaims.handsOff(entity, PhysicsAspect.IMPACT)
                    && net.camacraft.fullstop.common.compat.ShipCompat.seatShip(entity) == null) {
                entity.stopRiding();
            }
        }
        return anyHurt;
    }

    /**
     * Passengers get the same immunity rules and armor mitigation as everyone else - and none of
     * it when FullStop ignores them or another mod owns their impacts (an MKW kart's driver).
     */
    private static boolean hurtPassenger(Entity passenger, DamageSource source, float damage,
                                         boolean downward, boolean upward, boolean fall) {
        if (PhysicsClaims.handsOff(passenger, PhysicsAspect.IMPACT)) return false;
        if (passenger instanceof LivingEntity living) {
            if (DamageImmunityRules.isDamageImmune(living)) return false;
            damage = DamageMitigation.applyArmorReduction(living, damage, downward, upward, fall);
        }
        return damage > 0 && passenger.hurt(source, damage);
    }

    /**
     * A ship's hull hitting {@code root} (the bottom of a ride stack): full damage to it, half to
     * each rider, the same immunity rules, armor mitigation, top-up cooldown and hard-hit
     * dismount as every other kinetic impact. Riders another mod owns the impacts of are spared.
     *
     * @param hullAbove the hull came down on it (the helmet takes the armor share)
     * @param hullBelow the hull rose into it (the boots do, Feather Falling included)
     * @return whether any damage was dealt
     */
    public static boolean applyShipHit(Entity root, @Nullable FullStopCapability fullstop, DamageSource source,
                                       double damage, boolean hullAbove, boolean hullBelow) {
        if (damage < 1) return false;

        long tick = root.tickCount;
        double alreadyApplied = fullstop != null ? fullstop.getRecentAppliedDamage(tick) : 0.0;
        if (alreadyApplied > 0) {
            double topUp = damage - alreadyApplied;
            if (topUp < 1) return false;
            damage = topUp;
        }
        double totalThisImpact = alreadyApplied + damage;

        boolean anyHurt = false;
        float rootDamage = (float) damage;
        // A hull rising into the feet is taken like a landing (Feather Falling) only where
        // something pulls; in zero gravity the boots take it like any other hit.
        boolean fall = hullBelow && !Gravity.isWeightless(root);
        if (root instanceof LivingEntity living) {
            if (!DamageImmunityRules.isDamageImmune(living)) {
                rootDamage = DamageMitigation.applyArmorReduction(living, rootDamage, hullBelow, hullAbove, fall);
                anyHurt |= rootDamage > 0 && root.hurt(source, rootDamage);
            }
        } else if (rootDamage > 0) {
            // A boat or cart takes the hit like any attack (hard enough, it breaks).
            anyHurt |= root.hurt(source, rootDamage);
        }

        float passengerDamage = (float) damage * 0.5f;
        List<Entity> riders = new java.util.ArrayList<>();
        for (Entity passenger : EntityStack.selfAndPassengers(root)) {
            if (passenger == root) continue;
            riders.add(passenger);
            anyHurt |= hurtPassenger(passenger, source, passengerDamage, hullBelow, hullAbove, fall);
        }

        // A hit that landed hard throws the riders off (not those another mod
        // keeps seated, an MKW racer, nor anyone in a ship's seat).
        if (anyHurt && damage > 5) {
            for (Entity rider : riders) {
                if (rider.getVehicle() == root && !PhysicsClaims.handsOff(rider, PhysicsAspect.IMPACT)
                        && net.camacraft.fullstop.common.compat.ShipCompat.seatShip(rider) == null) {
                    rider.stopRiding();
                }
            }
        }

        if (anyHurt && fullstop != null) {
            fullstop.markDamageApplied(tick, DAMAGE_COOLDOWN_TICKS, totalThisImpact);
        }
        return anyHurt;
    }
}
