package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector;
import net.camacraft.fullstop.common.physics.math.VanillaFallMath;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Computes kinetic impact damage.
 *
 * The trigger is the measured stopping force (speed actually lost this tick),
 * gated by real collision evidence (the entity's own collision flags). The
 * raycast Collision only classifies what was hit. Because the trigger is a
 * per-tick delta, leaning against a wall while holding W is inherently
 * damage-free: after the first contact tick there is no further deceleration.
 */
public class KineticDamageCalculator {

    public static double calculateDamage(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (collision.fake()) return 0;

        if (entity instanceof LivingEntity living) {
            if (DamageImmunityRules.isDamageImmune(living)) return 0;
        }

        if (entity instanceof Mob mob) {
            if (mob.isLeashed() && fullstop.isMostlyDownward()) return 0;
        }

        boolean bouncySurface = collision.collisionType == Collision.CollisionType.SLIME ||
                collision.collisionType == Collision.CollisionType.BED ||
                collision.collisionType == Collision.CollisionType.HONEY;
        if (bouncySurface && !entity.isCrouching()) {
            return 0;
        }

        boolean isDownwardImpact = fullstop.isMostlyDownward();

        // Block impacts must be corroborated by the entity actually having collided;
        // the raycast alone (e.g. a near-miss past a corner) is not enough. The
        // grace window (not the raw flags) is used because a fast impact's real
        // deceleration lands a tick after contact, by which point the flag can
        // already have cleared — see FullStopCapability.HIT_GRACE_TICKS.
        // Ship blocks are the exception: Valkyrien Skies resolves ship contact in
        // its own solver and never sets the vanilla flags, so a shipyard raycast
        // hit is accepted as its own evidence (the stopping-force thresholds still
        // gate whether it deals anything).
        boolean blockImpact = collision.collisionType != Collision.CollisionType.ENTITY
                && collision.collisionType != Collision.CollisionType.WATER;
        boolean shipEvidence = blockImpact && ShipCompat.anyShipBlock(entity.level(), collision.impactedPositions);
        // Evidence is per axis: onGround corroborates only VERTICAL deceleration.
        // Letting it stand in for horizontal evidence billed "wall damage" to any
        // grounded stop with no wall at all (ice runway into soul sand, boat
        // dismounts, cobwebs).
        boolean verticalEvidence = fullstop.hadRecentVerticalHit() || entity.onGround() || shipEvidence;
        boolean horizontalEvidence = fullstop.hadRecentHorizontalHit() || shipEvidence
                || hasFlushStopAgainstWall(entity, fullstop, collision);
        if (blockImpact && !verticalEvidence && !horizontalEvidence) {
            return 0;
        }

        boolean vanillaParity = SERVER.fallDamageMode.get() == FullStopConfig.FallDamageMode.VANILLA_PARITY;

        // Vanilla subtracts Jump Boost levels from the effective fall distance.
        double jumpBonus = 0;
        if (vanillaParity && entity instanceof LivingEntity living) {
            MobEffectInstance jump = living.getEffect(MobEffects.JUMP);
            if (jump != null) jumpBonus = jump.getAmplifier() + 1;
        }

        double horizontalExceedance;
        double verticalExceedance;
        if (vanillaParity) {
            // Impact speed → the vanilla fall distance that produces it → vanilla
            // damage ((distance - 3) points). Wall crashes use the same mapping so
            // hitting a wall at 20-block-fall speed hurts like a 20-block fall.
            horizontalExceedance = (!blockImpact || horizontalEvidence)
                    ? Math.max(VanillaFallMath.equivalentFallDistance(fullstop.getHorizontalStoppingForce()) - 3.0, 0)
                    : 0;
            verticalExceedance = (!blockImpact || verticalEvidence)
                    ? Math.max(VanillaFallMath.equivalentFallDistance(fullstop.getVerticalStoppingForce()) - 3.0 - jumpBonus, 0)
                    : 0;
        } else {
            horizontalExceedance = (!blockImpact || horizontalEvidence)
                    ? Math.max(fullstop.getHorizontalStoppingForce() - SERVER.velocityDamageThresholdHorizontal.get(), 0)
                    : 0;
            verticalExceedance = (!blockImpact || verticalEvidence)
                    ? Math.max(fullstop.getVerticalStoppingForce() - SERVER.velocityDamageThresholdVertical.get(), 0)
                    : 0;
        }

        // Dripstone is special: an upward spike hurts from the first block of
        // exceedance — but only on real contact with real deceleration. The
        // one-tick-lookahead ray sees spikes BEFORE contact, and without a
        // stopping-force requirement merely walking toward a column dealt damage.
        for (BlockState state : collision.blockStates) {
            if (state.is(Blocks.POINTED_DRIPSTONE)) {
                Direction tipDirection = state.getValue(PointedDripstoneBlock.TIP_DIRECTION);
                if (isDownwardImpact && tipDirection == Direction.UP) {
                    // ~a one-block drop's landing speed; below that it's a step, not a fall.
                    if (verticalEvidence && fullstop.getVerticalStoppingForce() > 4.0) {
                        if (vanillaParity) {
                            // Vanilla: causeFallDamage(distance + 2, 2.0) → ((d + 2 − 3 − jump) × mult).
                            double dist = VanillaFallMath.equivalentFallDistance(fullstop.getVerticalStoppingForce());
                            return Math.max(dist - 1.0 - jumpBonus, 0.5)
                                    * SERVER.dripstoneDamageMultiplier.get()
                                    * SERVER.kineticDamageMultiplier.get();
                        }
                        return (1.0 + (verticalExceedance * SERVER.dripstoneDamageMultiplier.get()))
                                * SERVER.kineticDamageMultiplier.get();
                    }
                } else if (!isDownwardImpact) {
                    // Ramming a spike sideways: needs an actual horizontal hit + a real
                    // (walking-speed) deceleration, not just proximity.
                    if (horizontalEvidence && fullstop.getHorizontalStoppingForce() > 2.0) {
                        return 2.0 * SERVER.kineticDamageMultiplier.get();
                    }
                }
            }
        }

        double damage;
        if (collision.collisionType == Collision.CollisionType.ENTITY) {
            if (!SERVER.entityCollisionDamage.get()) {
                return 0;
            }
            // Relative speed alone would bill two fast movers that merely pass
            // close by; require the mover to have lost some speed OR to carry a
            // fresh impulse from EntityCollisionHandler (which runs just before
            // this in the dispatch). The impulse check matters on the overlap
            // tick itself: the position-delta stopping force only shows the
            // momentum transfer one tick later, by which point the knocked-back
            // target may already be outside the detection box.
            if (fullstop.getStoppingForce() < 1.0 && !entity.hasImpulse) {
                return 0;
            }
            double averageRelativeSpeed = collision.collidingEntities.stream()
                    .mapToDouble(other -> VelocityMath.entityVelocity(entity).subtract(VelocityMath.entityVelocity(other)).length())
                    .average()
                    .orElse(0.0);
            double threshold = isDownwardImpact
                    ? SERVER.velocityDamageThresholdVertical.get()
                    : SERVER.velocityDamageThresholdHorizontal.get();
            damage = Math.max(averageRelativeSpeed - threshold, 0);
        } else {
            damage = Math.max(horizontalExceedance, verticalExceedance);
            // Vanilla rounds fall damage up to whole points.
            if (vanillaParity) damage = Math.ceil(damage);
        }

        if (damage <= 0) return 0;

        boolean hitWater = false;
        for (BlockState state : collision.blockStates) {
            if (state.is(Blocks.WATER)) {
                hitWater = true;
                break;
            }
        }

        // Hitting water flat in the prone/swim pose is a belly flop — the water
        // may as well be pavement. A clean feet-first dive keeps almost nothing.
        if (hitWater && isDownwardImpact) {
            damage *= isBellyFlop(entity) ? 1.2 : 0.05;
        }

        for (BlockState state : collision.blockStates) {
            if (state.is(FullStopTags.SOFT_LANDING)) {
                damage *= 0.3;
                break;
            }
            if (state.is(FullStopTags.CUSHIONING)) {
                damage *= 0.7;
                break;
            }
        }

        // VANILLA_PARITY skips the hardness curve and the solid-impact floor:
        // vanilla fall damage doesn't care what you land on beyond the soft-block
        // multipliers above, and adding either would break the 1:1 calibration.
        if (!vanillaParity) {
            if (SERVER.hardnessAffectsDamage.get()) {
                double averageHardness = 1.0;
                if (!collision.blockStates.isEmpty()) {
                    double totalHardness = 0;
                    int count = 0;
                    for (BlockState state : collision.blockStates) {
                        // Water's block hardness is 100 (bedrock-tier) — meaningless for an
                        // impact; its softness is already handled by the dive/flop factor.
                        if (state.is(Blocks.WATER)) continue;

                        float hardness = state.getDestroySpeed(entity.level(), entity.blockPosition());
                        if (state.is(FullStopTags.FRAGILE)) {
                            hardness = 0.05f;
                        }
                        if (hardness >= 0) {
                            totalHardness += hardness;
                        } else {
                            totalHardness += 100.0; // unbreakable blocks hit like bedrock
                        }
                        count++;
                    }
                    if (count > 0) {
                        averageHardness = totalHardness / count;
                    }
                }

                double hardnessMultiplier = 0.5 + (averageHardness / 4.0);
                hardnessMultiplier = Mth.clamp(hardnessMultiplier, 0.2, 2.0);
                damage *= hardnessMultiplier;
            }

            damage *= 1.07;

            // Crossing the speed threshold on a solid impact always costs at least
            // the configured floor, even when soft/weak blocks would shrink it to a
            // scratch — landing spot choice should matter, but a real overspeed
            // landing is never free.
            double minimumSolid = SERVER.minimumSolidImpactDamage.get();
            if (collision.collisionType == Collision.CollisionType.SOLID && damage > 0 && minimumSolid > 0) {
                damage = Math.max(damage, minimumSolid);
            }
        }

        return damage * SERVER.kineticDamageMultiplier.get();
    }

    /**
     * Flag-independent wall-hit evidence: the entity is parked flush against an
     * opposing face it approached, with the speed on that face's axis actually
     * gone. Only a physical hit produces that end state — a near-miss elytra
     * turn keeps moving (never flush + residual speed), and rubbing along a
     * wall keeps its tangential speed but shows no normal-axis stopping force.
     * Exists because the client's collision-flag report is the PRIMARY horizontal
     * evidence, and any path that loses it (desync, other mods intercepting
     * movement) silently zeroed all grounded wall damage.
     */
    private static boolean hasFlushStopAgainstWall(Entity entity, FullStopCapability fullstop, Collision collision) {
        AABB box = entity.getBoundingBox();
        Vec3 current = fullstop.getCurrentScaledVelocity();

        for (BlockHitResult hit : collision.impactedHits) {
            Direction face = hit.getDirection();
            if (!face.getAxis().isHorizontal()) continue;

            // Speed must actually have been lost on this face's axis this tick.
            if (fullstop.getAxisStoppingForce(face.getAxis())
                    < CommonCollisionDetector.MIN_APPROACH_SPEED_MPS) continue;

            BlockPos pos = hit.getBlockPos();
            double facePlane;
            double entitySide;
            double residualMps;
            if (face.getAxis() == Direction.Axis.X) {
                facePlane = face == Direction.WEST ? pos.getX() : pos.getX() + 1.0;
                entitySide = face == Direction.WEST ? box.maxX : box.minX;
                residualMps = Math.abs(current.x);
            } else {
                facePlane = face == Direction.NORTH ? pos.getZ() : pos.getZ() + 1.0;
                entitySide = face == Direction.NORTH ? box.maxZ : box.minZ;
                residualMps = Math.abs(current.z);
            }

            if (Math.abs(entitySide - facePlane) > 0.1) continue; // not actually against the face
            if (residualMps > 1.5) continue;                      // still moving through — not a stop
            return true;
        }
        return false;
    }

    /**
     * A prone (swim-pose) body hitting water flat. isSwimming covers the common
     * case — the player holding sprint into the water is already sprint-swimming
     * on the tick the impact damage lands; isVisuallySwimming also catches the
     * crawl pose carried into the fall.
     */
    public static boolean isBellyFlop(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        return living.isSwimming() || living.isVisuallySwimming();
    }
}
