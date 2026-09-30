package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.OptionalDouble;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * How tough a block is when a Valkyrien Skies ship crashes with it, which of two touching blocks
 * gives way, and what breaking it costs.
 *
 * <h3>Toughness</h3>
 * {@code T = hardness * (mass / 1000 kg) ^ massInfluence}. Hardness alone misleads (oak planks,
 * 2.0, would beat stone, 1.5), so the block's weight counts too - Valkyrien Skies' own mass for
 * it, the figure its ships are built from. With the default {@code massInfluence} of 1: leaves
 * 0.04, grass block 0.75, dirt 0.63, oak planks 1.0, logs 1.6, stone 3.75, cobblestone 4.8,
 * deepslate 8.7, iron block 39, obsidian 250. {@code fullstop:fragile} blocks (glass, snow) count
 * as hardness 0.01; unbreakable blocks and {@code fullstop:ship_unbreakable} are infinitely tough.
 *
 * <h3>Which block gives</h3>
 * Only the WEAKER of the two: the terrain when the hull is tougher, the hull's own block when the
 * terrain is. Equal toughness: neither (obsidian against obsidian). Breaking the weaker costs
 * {@code T_weak * breakCostMultiplier * r / (r - 1)}, {@code r = T_strong / T_weak}: against a
 * far tougher striker about {@code T_weak * breakCostMultiplier}, climbing without bound as the
 * two approach each other, so near-equal materials take an enormous crash and equal ones never
 * break.
 *
 * <p>Ship against ship ({@link #whichGives(double, double, double)}): two blocks within
 * {@code mutualBreakRatio} of each other BOTH give, neither being tough enough to survive the
 * other (planks meeting planks at speed splinter both), each at
 * {@link #mutualCost}: {@code T * breakCostMultiplier * R / (R - 1)}, the cost the curve above
 * has reached at the edge of that band ({@code R = mutualBreakRatio}), so the two rules join up.
 * At a ratio of 1 the band is empty and equal blocks never break, as against terrain.
 *
 * <p>The number-only functions here ({@link #toughness(double, double, double)},
 * {@link #whichGives}, {@link #breakCost}) touch nothing of the game and are what the tests pin.
 */
public final class ShipMaterials {

    /** What Valkyrien Skies gives a block it has no entry for (its {@code defaultBlockMass}). */
    public static final double DEFAULT_BLOCK_MASS_KG = 1000.0;
    /** The hardness FullStop gives {@code fullstop:fragile} blocks. */
    public static final double FRAGILE_HARDNESS = 0.01;

    private ShipMaterials() {
    }

    /** Which of two touching blocks gives way. */
    public enum Gives { NEITHER, FIRST, SECOND, BOTH }

    // ------------------------------------------------------------------
    // The numbers
    // ------------------------------------------------------------------

    /**
     * Toughness from a hardness (negative: unbreakable, infinitely tough) and a mass in kg (not
     * positive or not finite: VS's default, {@link #DEFAULT_BLOCK_MASS_KG}).
     */
    public static double toughness(double hardness, double massKg, double massInfluence) {
        if (hardness < 0 || Double.isInfinite(hardness)) return Double.POSITIVE_INFINITY;
        if (Double.isNaN(hardness)) return 0.0;
        double mass = massKg > 0 && Double.isFinite(massKg) ? massKg : DEFAULT_BLOCK_MASS_KG;
        return hardness * Math.pow(mass / 1000.0, Math.max(0.0, massInfluence));
    }

    /** The weaker of two touching blocks gives; equal (or incomparable) ones both hold. */
    public static Gives whichGives(double first, double second) {
        if (first < second) return Gives.FIRST;
        if (second < first) return Gives.SECOND;
        return Gives.NEITHER;
    }

    /**
     * {@link #whichGives(double, double)}, except that two finite toughnesses within
     * {@code mutualRatio} of each other (the tougher at most that many times the weaker) BOTH
     * give: neither block is tough enough to survive the other. A ratio of 1 or less never says
     * BOTH. Two blocks of no toughness at all are BOTH (each costs nothing).
     */
    public static Gives whichGives(double first, double second, double mutualRatio) {
        if (mutualRatio > 1.0 && Double.isFinite(first) && Double.isFinite(second)) {
            double lo = Math.min(first, second);
            double hi = Math.max(first, second);
            if (hi <= 0.0 || hi <= lo * mutualRatio) return Gives.BOTH;
        }
        return whichGives(first, second);
    }

    /**
     * What breaking a block of {@code toughness} costs (FullStop energy units) when it and the
     * block it met both give ({@link Gives#BOTH}): {@code toughness * multiplier * R / (R - 1)},
     * {@code R = mutualRatio} - what {@link #breakCost} charges at the edge of the mutual band, so
     * the price doesn't drop as two materials get closer. Infinite for an unbreakable block, or
     * with a ratio of 1 or less; nothing for a block of no toughness.
     */
    public static double mutualCost(double toughness, double multiplier, double mutualRatio) {
        if (Double.isInfinite(toughness) || !(mutualRatio > 1.0)) return Double.POSITIVE_INFINITY;
        if (!(toughness > 0.0)) return 0.0;
        return toughness * multiplier * mutualRatio / (mutualRatio - 1.0);
    }

    /**
     * FullStop energy units it takes to break a block of toughness {@code weaker} struck by one
     * of {@code stronger}: {@code weaker * multiplier * r / (r - 1)} with {@code r = stronger /
     * weaker}. Infinite when {@code stronger} isn't tougher (nothing breaks); about
     * {@code weaker * multiplier} when it is far tougher.
     */
    public static double breakCost(double weaker, double stronger, double multiplier) {
        if (!(stronger > weaker) || Double.isInfinite(weaker)) return Double.POSITIVE_INFINITY;
        if (weaker <= 0.0) return 0.0;
        if (Double.isInfinite(stronger)) return weaker * multiplier;
        // r / (r - 1), written so it stays exact for a large ratio
        return weaker * multiplier * stronger / (stronger - weaker);
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    /** A block's toughness at {@code pos} under the current {@code massInfluence}. */
    public static double toughness(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.is(FullStopTags.SHIP_UNBREAKABLE)) return Double.POSITIVE_INFINITY;
        double hardness = state.is(FullStopTags.FRAGILE) ? FRAGILE_HARDNESS : state.getDestroySpeed(level, pos);
        return toughness(hardness, massKg(state), SERVER.massInfluence.get());
    }

    /** Valkyrien Skies' mass for a block state, kg; its default when VS has none to give. */
    public static double massKg(BlockState state) {
        OptionalDouble mass = Ships.blockMassKg(state);
        return mass.isPresent() && mass.getAsDouble() > 0 ? mass.getAsDouble() : DEFAULT_BLOCK_MASS_KG;
    }

    /**
     * Whether a hull ploughs straight through this terrain block: soft vegetation in
     * {@code fullstop:ship_sweepable}, except leaves players placed while {@code sweepPlacedLeaves}
     * is off, and anything waterlogged (no water is ever let loose). Such blocks always give way
     * to a hull, whatever their toughness.
     */
    public static boolean sweepable(BlockState state) {
        if (!state.is(FullStopTags.SHIP_SWEEPABLE) || !state.getFluidState().isEmpty()) return false;
        return SERVER.sweepPlacedLeaves.get() || !state.hasProperty(LeavesBlock.PERSISTENT)
                || !state.getValue(LeavesBlock.PERSISTENT);
    }
}
