package net.camacraft.fullstop.common.physics.interaction;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.physics.rules.EntityPhysicsRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.List;
import java.util.Optional;

public class KineticBlockInteractions {

    // --- BALANCING CONSTANTS ---
    // Using a kinetic energy model: KE = 0.5 * m * v^2
    // Break Cost = Hardness * Multiplier
    private static final double HARDNESS_BREAK_THRESHOLD_MULTIPLIER = 2.0;
    private static final double GRASS_PATH_THRESHOLD = 0.5;
    private static final double MIN_VELOCITY_REQUIRED = 0.4; // Min velocity in m/tick to trigger breaking
    private static final double MIN_INTERACTION_VELOCITY = 0.15; // Min velocity in m/tick to trigger interactions
    private static final double ENERGY_DAMPING_FACTOR = 0.8; // % of energy remaining after a break

    // Event ID 3004 corresponds to LevelEvent.PARTICLES_AND_SOUND_WAX_OFF in newer mappings
    private static final int LEVEL_EVENT_WAX_OFF = 3004;

    /**
     * Handles kinetic impacts with blocks, potentially breaking them or creating paths.
     *
     * @param entity The entity impacting the blocks.
     * @param impactVelocity The entity's velocity vector at the moment of impact.
     * @param impactedBlocks A list of BlockPos that were impacted.
     * @return true if any block was broken, false otherwise.
     */
    public static boolean handleBlockImpacts(Entity entity, Vec3 impactVelocity, List<BlockPos> impactedBlocks, List<BlockHitResult> impactedHits) {
        if (entity.level().isClientSide) return false;

        if (!FullStopConfig.SERVER.kineticBlockBreaking.get()) {
            return false;
        }

        double velocityMag = impactVelocity.length();
        if (velocityMag < MIN_INTERACTION_VELOCITY) {
            return false;
        }

        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }

        // 1. Calculate Mass

        double totalMass = EntityPhysicsRules.getEntityMass(entity);

        if (entity instanceof LivingEntity living) {
            double armorValue = living.getAttributeValue(Attributes.ARMOR);

            // Armor mass contribution is a rough estimate. Full diamond (20) adds 1.0 to mass.
            totalMass += armorValue / 20.0;
            if (totalMass <= 0) return false;
        }

        // 2. Calculate Kinetic Energy (using m/tick velocity)
        double kineticEnergy = 0.5 * totalMass * (velocityMag * velocityMag);

        ServerLevel level = (ServerLevel) entity.level();
        boolean isPlayer = entity instanceof Player;

        FakePlayer fakePlayer = null;

        boolean blockBroken = false;
        for (int i = 0; i < impactedBlocks.size(); i++) {
            BlockPos pos = impactedBlocks.get(i);
            BlockState state = level.getBlockState(pos);
            BlockHitResult hitResult = impactedHits.get(i);

            // --- LOGIC 0: Bouncy Block Immunity ---
            if (state.getBlock() instanceof SlimeBlock ||
                    state.getBlock() instanceof HoneyBlock ||
                    state.getBlock() instanceof BedBlock) {
                
                // If it's an arrow hitting a bouncy block, stop processing to avoid getting stuck
                if (entity instanceof Arrow) {
                    return false;
                }
                continue;
            }

            // Initialize FakePlayer if needed
            if (fakePlayer == null) {
                fakePlayer = FakePlayerFactory.getMinecraft(level);
                fakePlayer.setPos(entity.position());
                fakePlayer.setXRot(entity.getXRot());
                fakePlayer.setYRot(entity.getYRot());
                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }

            // --- LOGIC 0.5: Kinetic Interaction ---
            if (!isBlacklisted(state)) {
                // Special Case: Note Blocks (Left Click / Attack to play sound without changing pitch)
                if (state.getBlock() instanceof NoteBlock) {
                    state.attack(level, pos, fakePlayer);
                } else {
                    // Allow collision to toggle door-like blocks. For open doors, only allow closing when hit on a side face.
                    boolean isOpenedDoor = false;
                    if (state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN)) {
                        if (state.getBlock() instanceof DoorBlock ||
                            state.getBlock() instanceof TrapDoorBlock ||
                            state.getBlock() instanceof FenceGateBlock) {
                            isOpenedDoor = true;
                        }
                    }

                    boolean canToggleOpenDoor = isOpenedDoor && hitResult.getDirection().getAxis().isHorizontal();
                    if (!isOpenedDoor || canToggleOpenDoor) {
                        InteractionResult result = state.use(level, fakePlayer, InteractionHand.MAIN_HAND, hitResult);
                        if (result.consumesAction()) {
                            continue;
                        }
                    }
                }
            }

            // --- LOGIC 0.6: Falling Block Specifics ---
            if (entity instanceof FallingBlockEntity fallingBlock) {
                BlockState fallingState = fallingBlock.getBlockState();
                float fallingHardness = fallingState.getDestroySpeed(level, entity.blockPosition());
                float hitHardness = state.getDestroySpeed(level, pos);

                // Abrasive Logic (Sand Blasting)
                if (fallingState.is(BlockTags.SAND)) {
                    boolean blasted = false;

                    // Copper Oxidation Removal
                    if (state.getBlock() instanceof WeatheringCopper) {
                        Optional<BlockState> previous = WeatheringCopper.getPrevious(state);
                        if (previous.isPresent()) {
                            level.setBlockAndUpdate(pos, previous.get());
                            blasted = true;
                        }
                    }

                    // General Tool Stripping (Logs, etc)
                    if (!blasted) {
                        ItemStack axe = new ItemStack(Items.IRON_AXE);
                        UseOnContext context = new UseOnContext(level, fakePlayer, InteractionHand.MAIN_HAND, axe, hitResult);
                        BlockState modified = state.getToolModifiedState(context, ToolActions.AXE_STRIP, false);
                        if (modified != null) {
                            level.setBlockAndUpdate(pos, modified);
                            blasted = true;
                        }
                    }

                    if (blasted) {
                        level.levelEvent(LEVEL_EVENT_WAX_OFF, pos, 0); // Particles/Sound for scraping

                        // Sand breaks upon blasting
                        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, entity.blockPosition(), Block.getId(fallingState));
                        if (level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
                            fallingBlock.spawnAtLocation(fallingState.getBlock());
                        }
                        fallingBlock.discard();
                        return true; // Interaction happened
                    }
                }

                // Hardness Check: If falling block is softer than what it hits, it breaks
                if (hitHardness > fallingHardness) {
                    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, entity.blockPosition(), Block.getId(fallingState));
                    if (level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
                        fallingBlock.spawnAtLocation(fallingState.getBlock());
                    }
                    fallingBlock.discard();
                    return false; // Stop processing, entity is gone
                }
            }

            // --- LOGIC 1: Destruction ---

            // Check mob griefing rule for breaking
            if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) && !isPlayer) {
                continue;
            }

            // Check velocity threshold for breaking
            if (velocityMag < MIN_VELOCITY_REQUIRED) {
                continue;
            }

            float hardness = state.getDestroySpeed(level, pos);
            
            // --- FRAGILE BLOCK LOGIC ---
            boolean isFragile = false;
            if (state.is(Blocks.GLASS) ||
                    state.is(Blocks.GLASS_PANE) ||
                    state.is(Blocks.ICE) ||
                    state.is(Blocks.SNOW) ||
                    state.is(Blocks.SNOW_BLOCK) ||
                    state.getBlock() instanceof StainedGlassBlock ||
                    state.getBlock() instanceof StainedGlassPaneBlock) {

                // Treat glass/ice as extremely fragile (almost 0 hardness for breaking calc)
                hardness = 0.01f;
                isFragile = true;
            }

            if (hardness < 0 || state.isAir()) continue;

            double breakCost = hardness * HARDNESS_BREAK_THRESHOLD_MULTIPLIER;

            if (kineticEnergy >= breakCost) {
                level.destroyBlock(pos, true, entity);
                blockBroken = true;

                // Consume energy and update velocity to prevent tunneling
                kineticEnergy -= breakCost;
                
                if (!isFragile) {
                    kineticEnergy *= ENERGY_DAMPING_FACTOR; // Dampen remaining energy
                }

                if (kineticEnergy < 0) kineticEnergy = 0;

                double newVelocityMag = Math.sqrt(2 * kineticEnergy / totalMass);
                Vec3 newVelocity = impactVelocity.normalize().scale(newVelocityMag);

                entity.setDeltaMovement(newVelocity);
                entity.hurtMarked = true; // Force velocity update to client
            }

            // --- LOGIC 2: Grass Paths ---
            if (kineticEnergy > GRASS_PATH_THRESHOLD) {
                Vec3 dir = impactVelocity.normalize();
                // Only convert if mostly downward
                if (dir.y < -0.5 && state.is(Blocks.GRASS_BLOCK)) {
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

    private static boolean isBlacklisted(BlockState state) {
        Block block = state.getBlock();
        return block instanceof AnvilBlock ||
               block instanceof RepeaterBlock ||
               block instanceof ComparatorBlock ||
               block instanceof RedStoneWireBlock ||
               block instanceof DaylightDetectorBlock;
    }
}