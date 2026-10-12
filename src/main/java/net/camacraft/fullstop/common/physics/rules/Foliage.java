package net.camacraft.fullstop.common.physics.rules;

import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.List;
import java.util.Set;

/**
 * Foliage: plants and leaves of any mod, which never hurt to run into (the owner, 2026-10-10: "mods that add foliage
 * shouldn't damage me when running into them, like the Huge Clover Petal from Biomes o' Plenty"; that petal is a
 * two-pixel plate at the top of its block that a runner meets edge-on, and Full Stop billed the stop like a wall's).
 *
 * <p>Told by what a block is, not by a list of mods: a block that breaks at once or nearly (hardness up to
 * {@link #MAX_HARDNESS}, leaves' 0.2) and is a plant by vanilla's own tags (leaves, flowers, saplings, crops, what a sword
 * cuts, what a tree grows over), by its sound (grass, leaves, petals, dripleaf, moss, vines...), or by a plant's map colour
 * with a plant's manners (destroyed by a piston, replaceable, or burnt by lava). The Biomes o' Plenty petal: hardness 0,
 * petals' sound, plant colour. Not foliage: cactus (0.4), melons and pumpkins (1.0), hay, mushroom blocks (wood's sound,
 * not a plant colour), anything in {@code #fullstop:not_foliage}; always foliage: {@code #fullstop:foliage}.</p>
 *
 * <p>Running into foliage (from the side or from below) deals nothing; landing on it from a fall counts as a soft
 * landing, as dripleaves and moss do ({@code #fullstop:cushioning}). {@code foliageNeverHurts} switches it off.</p>
 */
public final class Foliage {

    /** The hardest a block can be and still be foliage by what it is (leaves are 0.2). */
    public static final float MAX_HARDNESS = 0.2f;

    private static final Set<SoundType> PLANT_SOUNDS = Set.of(SoundType.GRASS, SoundType.WET_GRASS, SoundType.CROP,
            SoundType.SWEET_BERRY_BUSH, SoundType.VINE, SoundType.CAVE_VINES, SoundType.LILY_PAD, SoundType.BIG_DRIPLEAF,
            SoundType.SMALL_DRIPLEAF, SoundType.AZALEA, SoundType.FLOWERING_AZALEA, SoundType.AZALEA_LEAVES, SoundType.MOSS,
            SoundType.MOSS_CARPET, SoundType.HANGING_ROOTS, SoundType.SPORE_BLOSSOM, SoundType.PINK_PETALS,
            SoundType.CHERRY_LEAVES, SoundType.CHERRY_SAPLING, SoundType.BAMBOO_SAPLING, SoundType.ROOTS,
            SoundType.NETHER_SPROUTS, SoundType.FUNGUS, SoundType.WEEPING_VINES, SoundType.TWISTING_VINES,
            SoundType.GLOW_LICHEN);

    private Foliage() {
    }

    private static boolean enabled() {
        return FullStopConfig.SERVER_SPEC.isLoaded() && FullStopConfig.SERVER.foliageNeverHurts.get();
    }

    /** Whether {@code state} is foliage (the class notes), the setting aside. */
    @SuppressWarnings("deprecation")
    public static boolean isFoliage(BlockState state) {
        if (state.isAir() || state.is(FullStopTags.NOT_FOLIAGE)) return false;
        if (state.is(FullStopTags.FOLIAGE)) return true;
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0.0f || hardness > MAX_HARDNESS) return false;
        if (state.is(Blocks.CACTUS)) return false;
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.CROPS)
                || state.is(BlockTags.SWORD_EFFICIENT) || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
            return true;
        }
        if (PLANT_SOUNDS.contains(state.getSoundType())) return true;
        return state.getBlock().defaultMapColor() == MapColor.PLANT
                && (state.getPistonPushReaction() == PushReaction.DESTROY || state.canBeReplaced() || state.ignitedByLava());
    }

    /**
     * Whether an impact on {@code struck} (a body's block impact) hurts nothing: every block it met is foliage and it
     * wasn't a landing (a landing on foliage is soft, see {@link #cushions}). Water and other liquids don't count either way.
     */
    public static boolean spares(List<BlockState> struck, boolean landing) {
        if (landing || struck.isEmpty() || !enabled()) return false;
        boolean any = false;
        for (BlockState state : struck) {
            if (!state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
            if (!isFoliage(state)) return false;
            any = true;
        }
        return any;
    }

    /** Whether smashing through {@code state} costs a body nothing (foliage, the setting on). */
    public static boolean harmless(BlockState state) {
        return enabled() && isFoliage(state);
    }

    /** Whether a landing on {@code state} is softened as on a cushioning block. */
    public static boolean cushions(BlockState state) {
        return enabled() && isFoliage(state);
    }
}
