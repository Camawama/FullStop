package net.camacraft.fullstop.server.physics.interaction;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Blocks reacting to high-speed impacts: door-likes fling open, note blocks play,
 * buttons/bells trigger, fragile blocks shatter, hard impacts crack or break blocks.
 *
 * Interactions are an explicit ALLOWLIST. Never call BlockState.use() generically
 * here — that made bumping into levers/chests/item frames activate them.
 */
public class KineticBlockInteractions {

    private static final double HARDNESS_BREAK_THRESHOLD_MULTIPLIER = 2.0;
    private static final double GRASS_PATH_THRESHOLD = 0.5;
    private static final double MIN_VELOCITY_REQUIRED = 0.4;    // blocks/tick (8 m/s) to break blocks
    private static final double MIN_INTERACTION_VELOCITY = 0.15; // blocks/tick (3 m/s) to trigger interactions
    private static final double ENERGY_DAMPING_FACTOR = 0.8;

    private static final int LEVEL_EVENT_WAX_OFF = 3004;
    private static final int PARTICLES_DESTROY_BLOCK = 2001;

    /** Last block-crack overlay sent per entity id, so stale cracks can be cleared. */
    private static final Map<Integer, BlockPos> LAST_CRACK = new HashMap<>();

    /**
     * @param impactVelocity previous-tick velocity in native blocks/tick
     * @return true if a block was broken (suppresses the bounce for this impact)
     */
    public static boolean handleBlockImpacts(Entity entity, Vec3 impactVelocity, List<BlockPos> impactedBlocks, List<BlockHitResult> impactedHits) {
        if (entity.level().isClientSide || !entity.isAlive() || !FullStopConfig.SERVER.kineticBlockBreaking.get()) {
            return false;
        }

        ServerLevel level = (ServerLevel) entity.level();

        if (handleDripstoneSideImpact(entity, impactVelocity, impactedBlocks, impactedHits)) {
            return true;
        }

        double velocityMag = impactVelocity.length();
        if (velocityMag < MIN_INTERACTION_VELOCITY) {
            clearCrack(entity, level);
            return false;
        }

        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }

        double totalMass = EntityStackUtils.getEntityMass(entity);
        if (entity instanceof LivingEntity living) {
            totalMass += living.getAttributeValue(Attributes.ARMOR) / 20.0;
        }
        if (totalMass <= 0) return false;

        double kineticEnergy = 0.5 * totalMass * (velocityMag * velocityMag);
        boolean isPlayer = entity instanceof Player;
        FakePlayer fakePlayer = null;
        boolean blockBroken = false;
        BlockPos crackPosThisPass = null;
        final List<BlockPos> processedToggles = new ArrayList<>();

        for (int i = 0; i < impactedBlocks.size(); i++) {
            BlockPos pos = impactedBlocks.get(i);
            BlockState state = level.getBlockState(pos);
            BlockHitResult hitResult = impactedHits.get(i);
            Block block = state.getBlock();

            if (block instanceof SlimeBlock || block instanceof HoneyBlock || block instanceof BedBlock) {
                continue; // bounce handling owns these
            }

            // --- Interaction allowlist ------------------------------------
            if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock) {
                BlockPos mainPos = (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                        ? pos.below() : pos;
                if (processedToggles.contains(mainPos)) {
                    continue;
                }
                processedToggles.add(mainPos);

                fakePlayer = preparedFakePlayer(fakePlayer, level, entity);
                // use() handles per-material open sounds, hand-openable checks (iron
                // doors PASS) and both door halves — no casting to DoorBlock needed.
                if (state.use(level, fakePlayer, InteractionHand.MAIN_HAND, hitResult).consumesAction()) {
                    continue;
                }
                // Not hand-openable (e.g. iron door): fall through to the break check.
            } else if (block instanceof NoteBlock) {
                fakePlayer = preparedFakePlayer(fakePlayer, level, entity);
                state.attack(level, pos, fakePlayer);
                continue;
            } else if (block instanceof ButtonBlock button) {
                if (!state.getValue(BlockStateProperties.POWERED)) {
                    button.press(state, level, pos);
                }
                continue;
            } else if (block instanceof BellBlock bell) {
                bell.attemptToRing(level, pos, hitResult.getDirection());
                continue;
            }

            if (entity instanceof FallingBlockEntity fallingBlock) {
                fakePlayer = preparedFakePlayer(fakePlayer, level, entity);
                return handleFallingBlockInteraction(level, pos, state, hitResult, fakePlayer, fallingBlock);
            }

            if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) && !isPlayer) continue;
            if (velocityMag < MIN_VELOCITY_REQUIRED) continue;

            float hardness = state.getDestroySpeed(level, pos);
            boolean isFragile = state.is(FullStopTags.FRAGILE);
            if (isFragile) hardness = 0.01f;

            if (hardness < 0 || state.isAir()) continue;

            double breakCost = hardness * HARDNESS_BREAK_THRESHOLD_MULTIPLIER;
            if (kineticEnergy >= breakCost) {
                if (level.destroyBlock(pos, true, entity)) {
                    blockBroken = true;
                    kineticEnergy -= breakCost;
                    if (!isFragile) kineticEnergy *= ENERGY_DAMPING_FACTOR;
                    if (kineticEnergy < 0) kineticEnergy = 0;
                    double newVelocityMag = Math.sqrt(2 * kineticEnergy / totalMass);
                    Vec3 newVelocity = impactVelocity.normalize().scale(newVelocityMag);
                    entity.setDeltaMovement(newVelocity);
                    entity.hasImpulse = true;
                    entity.setOnGround(false);
                    entity.verticalCollision = false;
                    entity.horizontalCollision = false;
                    entity.hurtMarked = true;
                }
            }

            if (kineticEnergy > GRASS_PATH_THRESHOLD) {
                if (impactVelocity.normalize().y < -0.5 && state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) {
                    level.playSound(null, pos, SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 1.0f, 1.0f);
                    level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState());
                }
            }

            double partialThreshold = 0.4;
            double damageRatio = kineticEnergy / breakCost;
            if (!blockBroken && damageRatio > partialThreshold && damageRatio < 1.0) {
                int crackStage = (int) (((damageRatio - partialThreshold) / (1.0 - partialThreshold)) * 9);
                level.destroyBlockProgress(entity.getId(), pos, crackStage);
                crackPosThisPass = pos;
            }
        }

        updateCrack(entity, level, crackPosThisPass);
        return blockBroken;
    }

    /** Clears this entity's crack overlay if it stopped hitting the cracked block. */
    public static void clearCrack(Entity entity, ServerLevel level) {
        updateCrack(entity, level, null);
    }

    private static void updateCrack(Entity entity, ServerLevel level, BlockPos newCrack) {
        BlockPos previous = LAST_CRACK.get(entity.getId());
        if (previous != null && !previous.equals(newCrack)) {
            level.destroyBlockProgress(entity.getId(), previous, -1);
        }
        if (newCrack != null) {
            LAST_CRACK.put(entity.getId(), newCrack);
        } else {
            LAST_CRACK.remove(entity.getId());
        }
    }

    private static FakePlayer preparedFakePlayer(FakePlayer existing, ServerLevel level, Entity entity) {
        if (existing != null) return existing;
        FakePlayer fakePlayer = FakePlayerFactory.getMinecraft(level);
        fakePlayer.setPos(entity.position());
        fakePlayer.setXRot(entity.getXRot());
        fakePlayer.setYRot(entity.getYRot());
        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return fakePlayer;
    }

    private static boolean handleDripstoneSideImpact(Entity entity, Vec3 impactVelocity, List<BlockPos> impactedBlocks, List<BlockHitResult> impactedHits) {
        if (impactVelocity.horizontalDistanceSqr() < 0.09) return false;

        ServerLevel level = (ServerLevel) entity.level();

        for (int i = 0; i < impactedBlocks.size(); i++) {
            BlockPos pos = impactedBlocks.get(i);
            BlockState state = level.getBlockState(pos);
            BlockHitResult hitResult = impactedHits.get(i);

            if (state.getBlock() instanceof PointedDripstoneBlock && hitResult.getDirection().getAxis().isHorizontal()) {
                if (level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
                    level.playSound(null, pos, SoundEvents.POINTED_DRIPSTONE_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
                    FallingBlockEntity fallingSpike = FallingBlockEntity.fall(level, pos, state);
                    // Vanilla handles the impact from here: hurtEntities gives the spike
                    // the proper falling_stalactite damage on whatever it lands on.
                    fallingSpike.setHurtsEntities(1.0F, 40);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean handleFallingBlockInteraction(ServerLevel level, BlockPos pos, BlockState state, BlockHitResult hitResult, FakePlayer fakePlayer, FallingBlockEntity fallingBlock) {
        BlockState fallingState = fallingBlock.getBlockState();

        if (fallingState.getBlock() instanceof PointedDripstoneBlock) {
            level.destroyBlock(pos, true);
            level.levelEvent(PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
            fallingBlock.discard();
            return true;
        }

        float fallingHardness = fallingState.getDestroySpeed(level, fallingBlock.blockPosition());
        float hitHardness = state.getDestroySpeed(level, pos);

        if (fallingState.is(BlockTags.SAND)) {
            boolean blasted = false;
            if (state.getBlock() instanceof WeatheringCopper) {
                Optional<BlockState> previous = WeatheringCopper.getPrevious(state);
                if (previous.isPresent()) {
                    level.setBlockAndUpdate(pos, previous.get());
                    blasted = true;
                }
            }
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
                level.levelEvent(LEVEL_EVENT_WAX_OFF, pos, 0);
                level.levelEvent(PARTICLES_DESTROY_BLOCK, fallingBlock.blockPosition(), Block.getId(fallingState));
                if (level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
                    fallingBlock.spawnAtLocation(fallingState.getBlock());
                }
                fallingBlock.discard();
                return true;
            }
        }

        if (hitHardness > fallingHardness) {
            level.levelEvent(PARTICLES_DESTROY_BLOCK, fallingBlock.blockPosition(), Block.getId(fallingState));
            if (level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
                fallingBlock.spawnAtLocation(fallingState.getBlock());
            }
            fallingBlock.discard();
            return true;
        }
        return false;
    }
}
