package net.camacraft.fullstop.common.physics;

import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class KineticInteractions {

    // --- BALANCING CONSTANTS ---
    // Using a kinetic energy model: KE = 0.5 * m * v^2
    // Break Cost = Hardness * Multiplier
    private static final double HARDNESS_BREAK_THRESHOLD_MULTIPLIER = 2.0;
    private static final double GRASS_PATH_THRESHOLD = 1.0;
    private static final double MIN_VELOCITY_REQUIRED = 0.4; // Min velocity in m/tick to trigger interactions
    private static final double ENERGY_DAMPING_FACTOR = 0.1; // % of energy remaining after a break

    /**
     * Handles kinetic impacts with blocks, potentially breaking them or creating paths.
     *
     * @param entity The entity impacting the blocks.
     * @param impactVelocity The entity's velocity vector at the moment of impact.
     * @param impactedBlocks A list of BlockPos that were impacted.
     * @return true if any block was broken, false otherwise.
     */
    public static boolean handleBlockImpacts(LivingEntity entity, Vec3 impactVelocity, List<BlockPos> impactedBlocks) {
        if (entity.level().isClientSide) return false;

        if (!FullStopConfig.SERVER.kineticBlockBreaking.get()) {
            return false;
        }

        double velocityMag = impactVelocity.length();
        if (velocityMag < MIN_VELOCITY_REQUIRED) {
            return false;
        }

        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }

        // 1. Calculate Mass
        double baseMass = Physics.getEntityMass(entity);
        double armorValue = entity.getAttributeValue(Attributes.ARMOR);
        // Armor mass contribution is a rough estimate. Full diamond (20) adds 1.0 to mass.
        double totalMass = baseMass + (armorValue / 20.0);
        if (totalMass <= 0) return false;

        // 2. Calculate Kinetic Energy (using m/tick velocity)
        double kineticEnergy = 0.5 * totalMass * (velocityMag * velocityMag);

        ServerLevel level = (ServerLevel) entity.level();

        boolean isPlayer = entity instanceof Player;
        if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING) && !isPlayer) {
            return false;
        }

        boolean blockBroken = false;
        for (BlockPos pos : impactedBlocks) {
            BlockState state = level.getBlockState(pos);

            // --- LOGIC 0: Bouncy Block Immunity ---
            if (state.getBlock() instanceof SlimeBlock ||
                    state.getBlock() instanceof HoneyBlock ||
                    state.getBlock() instanceof BedBlock) {
                continue;
            }

            float hardness = state.getDestroySpeed(level, pos);
            if (hardness < 0 || state.isAir()) continue;

            // --- LOGIC 1: Destruction ---
            double breakCost = hardness * HARDNESS_BREAK_THRESHOLD_MULTIPLIER;

            if (kineticEnergy >= breakCost) {
                level.destroyBlock(pos, true, entity);
                blockBroken = true;

                // Consume energy and update velocity to prevent tunneling
                kineticEnergy -= breakCost;
                kineticEnergy *= ENERGY_DAMPING_FACTOR; // Dampen remaining energy
                if (kineticEnergy < 0) kineticEnergy = 0;

                double newVelocityMag = Math.sqrt(2 * kineticEnergy / totalMass);
                Vec3 newVelocity = impactVelocity.normalize().scale(newVelocityMag);

                entity.setDeltaMovement(newVelocity);
                entity.hurtMarked = true; // Force velocity update to client

                // Stop processing other blocks this tick to prevent tunneling
                break;
            }

            // --- LOGIC 2: Grass Paths ---
            if (kineticEnergy > GRASS_PATH_THRESHOLD) {
                if (state.is(Blocks.GRASS_BLOCK)) {
                    level.playSound(null, pos, SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 1.0f, 1.0f);
                    level.setBlockAndUpdate(pos, Blocks.DIRT_PATH.defaultBlockState());
                }
            }

            // --- LOGIC 3: Partial Damage ---
            double partialThreshold = 0.4;
            double damageRatio = kineticEnergy / breakCost;
            if (damageRatio > partialThreshold) {
                int crackStage = (int) (((damageRatio - partialThreshold) / (1.0 - partialThreshold)) * 9);
                level.destroyBlockProgress(entity.getId(), pos, crackStage);
            }
        }
        return blockBroken;
    }
}