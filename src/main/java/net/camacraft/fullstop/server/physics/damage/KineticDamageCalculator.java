package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.physics.VanillaFall;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.physics.rules.PointedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

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

        // Foliage (any mod's plants and leaves, told by what they are) never hurts to run into; landing on it is soft
        if (collision.collisionType == Collision.CollisionType.SOLID
                && net.camacraft.fullstop.common.physics.rules.Foliage.spares(collision.blockStates, fullstop.isMostlyDownward())) {
            return 0;
        }

        boolean isDownwardImpact = fullstop.isMostlyDownward();
        // A FALL is a downward impact with something actually pulling: in zero gravity (VS Genesis
        // space, an Ad Astra orbit, a Gravity Unbound zero field) the frame stays upright, so a
        // feet-first bump into a floor is "downward" without being a fall, and every direction
        // takes the horizontal (wall) threshold. Exactly isDownwardImpact under any gravity.
        boolean isFall = fullstop.isFall();
        boolean weightless = fullstop.isWeightless();

        // Block impacts must be corroborated by the entity actually having collided;
        // the raycast alone (e.g. a near-miss past a corner) is not enough. The
        // grace window (not the raw flags) is used because a fast impact's real
        // deceleration lands a tick after contact, by which point the flag can
        // already have cleared — see FullStopCapability.HIT_GRACE_TICKS.
        // Ship blocks are the exception: Valkyrien Skies resolves ship contact in
        // its own solver and never sets the vanilla flags, so a shipyard raycast
        // hit is accepted as its own evidence (the stopping-force thresholds still
        // gate whether it deals anything).
        boolean blockImpact = collision.collisionType != Collision.CollisionType.WATER;
        boolean shipEvidence = blockImpact && ShipCompat.anyShipBlock(entity.level(), collision.impactedPositions);
        // Evidence is per axis: onGround corroborates only VERTICAL deceleration.
        // Letting it stand in for horizontal evidence billed "wall damage" to any
        // grounded stop with no wall at all (ice runway into soul sand, boat
        // dismounts, cobwebs). "Vertical" and "horizontal" are the entity's
        // gravity FRAME's: under Gravity Unbound the flags, onGround and the
        // stopping-force decomposition are all measured in that frame, so a
        // fall onto a plated wall is a vertical impact with vertical evidence.
        boolean verticalEvidence = fullstop.hadRecentVerticalHit() || entity.onGround() || shipEvidence;
        boolean horizontalEvidence = fullstop.hadRecentHorizontalHit() || shipEvidence
                || hasFlushStopAgainstWall(entity, fullstop, collision);
        if (blockImpact && !verticalEvidence && !horizontalEvidence) {
            return 0;
        }

        boolean vanillaParity = SERVER.fallDamageMode.get() == FullStopConfig.FallDamageMode.VANILLA_PARITY;

        // Vanilla subtracts Jump Boost levels from the effective fall distance (not in zero
        // gravity, where nothing falls).
        double jumpBonus = 0;
        if (vanillaParity && !weightless && entity instanceof LivingEntity living) {
            jumpBonus = VanillaFall.jumpBoostBonus(living);
        }

        double horizontalExceedance;
        double verticalExceedance;
        if (vanillaParity) {
            // Impact speed → the vanilla fall distance that produces it → vanilla
            // damage ((distance - 3) points). Wall crashes use the same mapping so
            // hitting a wall at 20-block-fall speed hurts like a 20-block fall.
            horizontalExceedance = (!blockImpact || horizontalEvidence)
                    ? Math.max(VanillaFall.equivalentFallDistance(fullstop.getHorizontalStoppingForce())
                            - VanillaFall.SAFE_FALL_DISTANCE, 0)
                    : 0;
            verticalExceedance = (!blockImpact || verticalEvidence)
                    ? Math.max(VanillaFall.equivalentFallDistance(fullstop.getVerticalStoppingForce())
                            - VanillaFall.SAFE_FALL_DISTANCE - jumpBonus, 0)
                    : 0;
        } else {
            horizontalExceedance = (!blockImpact || horizontalEvidence)
                    ? Math.max(fullstop.getHorizontalStoppingForce() - SERVER.velocityDamageThresholdHorizontal.get(), 0)
                    : 0;
            verticalExceedance = (!blockImpact || verticalEvidence)
                    ? Math.max(fullstop.getVerticalStoppingForce()
                            - (weightless ? SERVER.velocityDamageThresholdHorizontal.get() : verticalThresholdMps()), 0)
                    : 0;
        }

        // A pointed block (data: PointedBlocks - pointed dripstone, Supplementaries' spikes, any pack's) met at its
        // point hurts from the first block of exceedance, more the faster; rammed on its side, its flat side damage.
        // Only on real contact with real deceleration: the one-tick-lookahead ray sees points BEFORE contact, and
        // without a stopping-force requirement merely walking toward a column dealt damage.
        Vec3 before = fullstop.getPreImpactWorldVelocity();
        PointedBlocks.Hit point = PointedBlocks.collisionHit(entity, collision, before, before.subtract(fullstop.getCurrentWorldVelocity()));
        if (point != null) {
            GravityFrame frame = fullstop.getFrame();
            boolean alongVertical = Math.abs(frame.vertical(point.tip())) > 0.7;
            if ((alongVertical ? verticalEvidence : horizontalEvidence) && point.speed() > point.rule().minSpeed()) {
                // a fall onto an upward point takes vanilla's Jump Boost allowance (VANILLA_PARITY)
                boolean ontoUpwardPoint = isFall && frame.vertical(point.tip()) > 0.7;
                return PointedBlocks.damage(point.rule(), point.speed(), alongVertical, weightless,
                        ontoUpwardPoint ? jumpBonus : 0.0);
            }
        } else if (!isDownwardImpact) {
            // Ramming a point's side: needs an actual horizontal hit + a real (walking-speed) deceleration, not just
            // proximity.
            PointedBlocks.Rule side = PointedBlocks.anyStruck(collision);
            if (side != null && side.sideDamage() > 0 && horizontalEvidence && fullstop.getHorizontalStoppingForce() > 2.0) {
                return PointedBlocks.sideDamage(side);
            }
        }

        double damage = Math.max(horizontalExceedance, verticalExceedance);
        // Vanilla rounds fall damage up to whole points.
        if (vanillaParity) damage = Math.ceil(damage);

        if (damage <= 0) return 0;

        return finishDamage(entity, damage, isDownwardImpact, collision.blockStates,
                collision.collisionType == Collision.CollisionType.SOLID, vanillaParity);
    }

    /**
     * Damage for an impact that took {@code lostMps} of speed off the victim against
     * {@code struck} - the same model as any other kinetic impact (the configured thresholds
     * or vanilla parity, the struck blocks' hardness and softness, the solid-impact floor,
     * kineticDamageMultiplier), for callers that measured the impact themselves: a ship's hull
     * hitting an entity. Immunity is the caller's to check.
     *
     * @param vertical whether the impact was along the victim's up/down (the vertical threshold,
     *                 unless the victim is weightless: in zero gravity every direction takes the
     *                 horizontal one)
     */
    public static double damageForSpeedLost(Entity victim, double lostMps, boolean vertical, List<BlockState> struck) {
        boolean vanillaParity = SERVER.fallDamageMode.get() == FullStopConfig.FallDamageMode.VANILLA_PARITY;
        double damage;
        if (vanillaParity) {
            damage = Math.ceil(Math.max(VanillaFall.equivalentFallDistance(lostMps) - VanillaFall.SAFE_FALL_DISTANCE, 0));
        } else {
            double threshold = vertical && !Gravity.isWeightless(victim)
                    ? verticalThresholdMps() : SERVER.velocityDamageThresholdHorizontal.get();
            damage = Math.max(lostMps - threshold, 0);
        }
        if (damage <= 0) return 0;
        return finishDamage(victim, damage, false, struck, true, vanillaParity);
    }

    /**
     * The shared tail of every kinetic damage figure: water dives and belly flops, soft blocks,
     * then (KINETIC mode) block hardness, the 1.07 scale and the solid-impact floor, and finally
     * kineticDamageMultiplier.
     */
    private static double finishDamage(Entity entity, double damage, boolean isDownwardImpact,
                                       List<BlockState> blockStates, boolean solid, boolean vanillaParity) {
        boolean hitWater = false;
        for (BlockState state : blockStates) {
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

        for (BlockState state : blockStates) {
            if (state.is(FullStopTags.SOFT_LANDING)) {
                damage *= 0.3;
                break;
            }
            if (state.is(FullStopTags.CUSHIONING) || net.camacraft.fullstop.common.physics.rules.Foliage.cushions(state)) {
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
                if (!blockStates.isEmpty()) {
                    double totalHardness = 0;
                    int count = 0;
                    for (BlockState state : blockStates) {
                        // Water's block hardness is 100 (bedrock-tier) — meaningless for an
                        // impact; its softness is already handled by the dive/flop factor.
                        if (state.is(Blocks.WATER)) continue;
                        // Any other liquid's block also has hardness 100; met as a solid (oobleck
                        // struck hard, Camafy's solidAboveMps) it lands like stone, not bedrock.
                        if (net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector.isLiquidBlock(state)) {
                            totalHardness += 1.5;
                            count++;
                            continue;
                        }

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
            if (solid && damage > 0 && minimumSolid > 0) {
                damage = Math.max(damage, minimumSolid);
            }
        }

        return damage * SERVER.kineticDamageMultiplier.get();
    }

    /**
     * The KINETIC-mode vertical threshold (m/s): the impact speed of a
     * {@code minimumFallDistance}-block fall when that is set, else the raw
     * {@code velocityDamageThresholdVertical}. Both describe the same limit;
     * the height form is what most people actually mean ("falls under N
     * blocks are free").
     */
    public static double verticalThresholdMps() {
        double blocks = SERVER.minimumFallDistance.get();
        if (blocks > 0) {
            return VanillaFall.impactSpeedForFallDistance(blocks);
        }
        return SERVER.velocityDamageThresholdVertical.get();
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
        GravityFrame frame = fullstop.getFrame();

        for (BlockHitResult hit : collision.impactedHits) {
            Direction face = hit.getDirection();
            // A wall as the FRAME sees it: the four side faces in the vanilla
            // frame; under a rotated frame whichever world faces stand across
            // the frame's up (floors and ceilings carry their own evidence).
            if (!frame.isWallFace(face)) continue;
            Direction.Axis axis = face.getAxis();

            // Speed must actually have been lost on this face's (world) axis this tick.
            if (fullstop.getAxisStoppingForce(axis) < CommonCollisionDetector.MIN_APPROACH_SPEED_MPS) continue;

            BlockPos pos = hit.getBlockPos();
            // Ship faces are addressed in shipyard coordinates — no world plane
            // to compare against (ship impacts are their own evidence, see
            // shipEvidence in calculateDamage).
            if (ShipCompat.isShipBlock(entity.level(), pos)) continue;

            boolean negativeFace = face.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
            double facePlane = negativeFace ? pos.get(axis) : pos.get(axis) + 1.0;
            double entitySide = negativeFace ? box.max(axis) : box.min(axis);
            double residualMps = Math.abs(current.get(axis));

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
