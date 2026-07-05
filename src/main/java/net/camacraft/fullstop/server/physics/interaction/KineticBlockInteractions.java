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
import net.minecraft.world.level.ClipContext;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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

    private static final double HARDNESS_BREAK_THRESHOLD_MULTIPLIER = 1.0;
    private static final double GRASS_PATH_THRESHOLD = 0.5;
    private static final double MIN_VELOCITY_REQUIRED = 0.4;    // blocks/tick (8 m/s) to break blocks
    private static final double MIN_INTERACTION_VELOCITY = 0.15; // blocks/tick (3 m/s) to trigger interactions
    private static final double ENERGY_DAMPING_FACTOR = 0.8;

    /** Minimum speed (m/s) for a collision to shove a door/gate/trapdoor open. Sprinting is ~5.6. */
    private static final double DOOR_OPEN_MIN_SPEED_MPS = 5.0;
    /** Crashing through blocks grazes the entity: damage per broken block per m/s above this speed. */
    private static final double GRAZE_MIN_SPEED_MPS = 8.0;
    private static final double GRAZE_DAMAGE_FACTOR = 0.08;

    /**
     * Outcome of an impact pass. {@code smashDamage} is kinetic damage earned by
     * crashing through blocks (graze cuts plus any speed actually lost breaking
     * them) — the regular stopping-force damage never sees a break-through,
     * because the entity keeps moving instead of physically colliding.
     */
    public record ImpactResult(boolean brokeBlock, double smashDamage) {
        public static final ImpactResult NONE = new ImpactResult(false, 0);
    }

    private static final int LEVEL_EVENT_WAX_OFF = 3004;
    private static final int PARTICLES_DESTROY_BLOCK = 2001;

    /** Last block-crack overlay sent per entity id, so stale cracks can be cleared. */
    private static final Map<Integer, BlockPos> LAST_CRACK = new HashMap<>();

    /**
     * @param impactVelocity previous-tick velocity in native blocks/tick
     * @return whether a block was broken (suppresses the bounce for this impact)
     *         and any smash-through damage earned
     */
    public static ImpactResult handleBlockImpacts(Entity entity, Vec3 impactVelocity, List<BlockPos> impactedBlocks, List<BlockHitResult> impactedHits) {
        if (entity.level().isClientSide || !entity.isAlive() || !FullStopConfig.SERVER.kineticBlockBreaking.get()) {
            return ImpactResult.NONE;
        }

        ServerLevel level = (ServerLevel) entity.level();

        if (handleDripstoneSideImpact(entity, impactVelocity, impactedBlocks, impactedHits)) {
            return new ImpactResult(true, 0);
        }

        double velocityMag = impactVelocity.length();
        if (velocityMag < MIN_INTERACTION_VELOCITY) {
            clearCrack(entity, level);
            return ImpactResult.NONE;
        }

        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return ImpactResult.NONE;
        }

        double totalMass = EntityStackUtils.getEntityMass(entity);
        if (entity instanceof LivingEntity living) {
            totalMass += living.getAttributeValue(Attributes.ARMOR) / 20.0;
        }
        if (totalMass <= 0) return ImpactResult.NONE;

        double kineticEnergy = 0.5 * totalMass * (velocityMag * velocityMag);
        boolean isPlayer = entity instanceof Player;
        FakePlayer fakePlayer = null;
        boolean blockBroken = false;
        double smashDamage = 0;
        double remainingVelocityMag = velocityMag;
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

                if (shouldKineticOpen(state, impactVelocity)) {
                    fakePlayer = preparedFakePlayer(fakePlayer, level, entity);
                    // use() handles per-material open sounds, hand-openable checks (iron
                    // doors PASS) and both door halves — no casting to DoorBlock needed.
                    if (state.use(level, fakePlayer, InteractionHand.MAIN_HAND, hitResult).consumesAction()) {
                        continue;
                    }
                    // Not hand-openable (e.g. iron door): fall through to the break check.
                }
                // Wrong side / too slow / already open: no toggle. A fast enough
                // impact can still smash the door via the generic break check below.
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
                return new ImpactResult(handleFallingBlockInteraction(level, pos, state, hitResult, fakePlayer, fallingBlock), 0);
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
                    remainingVelocityMag = newVelocityMag;
                    smashDamage += Math.max(velocityMag * 20 - GRAZE_MIN_SPEED_MPS, 0) * GRAZE_DAMAGE_FACTOR;
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

        if (blockBroken) {
            // Speed actually lost punching through counts like any other stopping force.
            double speedLostMps = (velocityMag - remainingVelocityMag) * 20;
            boolean mostlyVertical = Math.abs(impactVelocity.y) > impactVelocity.horizontalDistance();
            double threshold = mostlyVertical
                    ? FullStopConfig.SERVER.velocityDamageThresholdVertical.get()
                    : FullStopConfig.SERVER.velocityDamageThresholdHorizontal.get();
            smashDamage += Math.max(speedLostMps - threshold, 0);
        }

        return new ImpactResult(blockBroken, blockBroken ? smashDamage : 0);
    }

    /**
     * Decides whether a kinetic impact may toggle a door/trapdoor/fence gate open.
     * Door-likes are never slammed shut, never open below sprint speed, and doors
     * only give way when pushed the way they swing (with FACING) — hitting the
     * pull side or sliding along the wall leaves them closed. Fence gates swing
     * away from whoever opens them, so they open from both broad sides.
     */
    private static boolean shouldKineticOpen(BlockState state, Vec3 impactVelocityNative) {
        if (state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN)) {
            return false;
        }

        if (state.getBlock() instanceof TrapDoorBlock) {
            return impactVelocityNative.length() * 20 >= DOOR_OPEN_MIN_SPEED_MPS;
        }

        Vec3 horizontal = new Vec3(impactVelocityNative.x, 0, impactVelocityNative.z);
        if (horizontal.length() * 20 < DOOR_OPEN_MIN_SPEED_MPS) return false;
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return false;

        Vec3 facingVec = Vec3.atLowerCornerOf(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getNormal());
        double alignment = horizontal.normalize().dot(facingVec);

        if (state.getBlock() instanceof DoorBlock) {
            return alignment > 0.5;
        }
        return Math.abs(alignment) > 0.5;
    }

    /**
     * Opens doors/fence gates in a fast mover's path BEFORE it reaches them, so
     * sprinting through a door never scrubs speed: the raycast collision only sees
     * a door about one tick ahead, and by the time the server's block update
     * reaches the client, the client has already predicted a collision against
     * the still-closed panel. Looking three ticks of travel ahead hides both.
     */
    public static void openDoorsAhead(Entity entity, Vec3 nativeVelocity) {
        if (!(entity.level() instanceof ServerLevel level) || !entity.isAlive()) return;
        if (entity instanceof Player player && player.isSpectator()) return;

        Vec3 horizontal = new Vec3(nativeVelocity.x, 0, nativeVelocity.z);
        if (horizontal.length() * 20 < DOOR_OPEN_MIN_SPEED_MPS) return;

        Vec3 direction = horizontal.normalize();
        double reach = Math.max(horizontal.length() * 3, 1.0);

        AABB box = entity.getBoundingBox();
        Vec3 center = box.getCenter();
        Vec3 side = new Vec3(-direction.z, 0, direction.x).scale(box.getXsize() * 0.45);
        List<Vec3> starts = List.of(
                new Vec3(center.x, box.minY + 0.1, center.z),
                new Vec3(center.x, box.maxY - 0.1, center.z),
                new Vec3(center.x, box.minY + 0.1, center.z).add(side),
                new Vec3(center.x, box.minY + 0.1, center.z).subtract(side)
        );

        List<BlockPos> handled = new ArrayList<>();
        FakePlayer fakePlayer = null;
        for (Vec3 start : starts) {
            BlockHitResult hit = level.clip(new ClipContext(start, start.add(direction.scale(reach)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            if (hit.getType() != HitResult.Type.BLOCK) continue;

            BlockPos pos = hit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof FenceGateBlock)) continue;

            BlockPos mainPos = (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                    ? pos.below() : pos;
            if (handled.contains(mainPos)) continue;
            handled.add(mainPos);

            if (!shouldKineticOpen(state, nativeVelocity)) continue;

            fakePlayer = preparedFakePlayer(fakePlayer, level, entity);
            state.use(level, fakePlayer, InteractionHand.MAIN_HAND, hit);
        }
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
