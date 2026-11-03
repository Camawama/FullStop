package net.camacraft.fullstop.common.physics.rules;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.StuckInBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Blocks that hold a body cushion it (the owner, 2026-10-31: "Falling onto powdered snow gives fall damage, when it
 * should actually dampen fall damage").
 *
 * <p>Vanilla's powder snow, cobwebs and sweet berry bushes (and any mod's block built the same way) call
 * {@code Entity.makeStuckInBlock} from {@code entityInside}: the fall distance is reset, and the next {@code move} scales
 * the motion by the block's multiplier and then sets the velocity to zero. A fast fall into powder snow also lands on the
 * block's falling shape (0.9 high, for a fall of over 2.5 blocks), whose {@code fallOn} only plays a sound. Either way
 * vanilla deals nothing: the block takes the body's speed as a cushion. Full Stop measures the speed a body loses in a
 * tick and bills it like a floor's, so that zeroed velocity read as a hard landing (a 30-block fall into powder snow cost
 * a pig as much as stone; powder snow's hardness is 0.25).</p>
 *
 * <p>Now a stop in a tick where a block holds the body, or within {@link #WINDOW_TICKS} after it, is the block's: nothing
 * of the speed it took counts as lost or is felt (no impact damage, sound, block damage or g-force from it), and the body
 * is measured afresh from where the block holds it, so what it does afterwards (sinking through the snow onto the stone
 * under it at 2 m/s, dropping out of a web) is billed for that speed alone. The window covers the tick in which the
 * zeroed velocity is read, and a player's move being checked a server tick after their client stepped it; a body that
 * has just been held moves at most a few tenths of a block a tick, so nothing within it can be a real impact.
 * {@code stuckBlocksCushion} (on) switches it off.</p>
 */
public final class StuckBlocks {

    /** Ticks after a block's hold in which a stop is still the block's. */
    public static final int WINDOW_TICKS = 2;

    private StuckBlocks() {
    }

    private static boolean enabled() {
        return FullStopConfig.SERVER_SPEC.isLoaded() && FullStopConfig.SERVER.stuckBlocksCushion.get();
    }

    /**
     * Whether a block held {@code entity} this tick or within {@link #WINDOW_TICKS} before it, or it is in powder snow now
     * or was last tick (the setting on). Powder snow is asked by vanilla's own flags as well: its {@code entityInside} holds
     * a living body only once its cached feet block is the snow, and the feet block is cached before the move, so on the
     * tick a fall lands on the snow's falling shape there is no hold yet (vanilla needs none: the shape's {@code fallOn}
     * only makes a sound), while {@code isInPowderSnow} is set for every body inside it that same tick.
     */
    public static boolean cushions(Entity entity) {
        if (entity.isInPowderSnow || entity.wasInPowderSnow) return enabled();
        if (!(entity instanceof StuckInBlock stuck)) return false;
        int tick = stuck.fullstop$stuckTick();
        if (tick == Integer.MIN_VALUE) return false;
        int since = entity.tickCount - tick;
        if (since < 0 || since > WINDOW_TICKS) return false;
        return enabled();
    }

    /**
     * Whether {@code state} is a block that catches bodies ({@code #fullstop:catching}: powder snow, cobwebs, sweet berry
     * bushes). A fast body never smashes, cracks or craters one: powder snow's falling shape stopped the rays, its
     * hardness of 0.25 made it a break-through, and the body met the floor under it instead (the first gametests: the
     * snow was gone before the pig reached it).
     */
    public static boolean catches(BlockState state) {
        return enabled() && state.is(FullStopTags.CATCHING);
    }

    /** The block that last held {@code entity}, or null. */
    @Nullable
    public static BlockState lastHolder(Entity entity) {
        return entity instanceof StuckInBlock stuck ? stuck.fullstop$stuckState() : null;
    }
}
