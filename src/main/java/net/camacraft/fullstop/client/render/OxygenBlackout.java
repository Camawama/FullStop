package net.camacraft.fullstop.client.render;

import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.world.entity.player.Player;

/**
 * How far the local player has lost consciousness for want of air, 0 (awake) to 1 (out), read by the
 * blackout overlay and the low-pass muffle alike.
 * <p>
 * The air bar is mostly a reserve: nothing dims or muffles until the last two bubbles, which take
 * consciousness down to {@link #AT_EMPTY} with the air. With the bar empty it fades faster, from there
 * to out cold over {@link #FADE_TICKS} (vanilla's drowning damage keeps ticking meanwhile), and comes
 * back over {@link #RECOVER_TICKS} once the player breathes again. The fade runs on the player's own
 * tick count, so it is the same at any frame rate and catches up when a caller skips frames. With
 * {@code blackoutOnlyWithoutAir} off, the old curve: (1 - air/max)^3, black at no air.
 */
public final class OxygenBlackout {
    /** How far gone the player is the moment the bar empties, reached over the last two bubbles. */
    private static final float AT_EMPTY = 0.25f;
    private static final float FADE_TICKS = 100f;   // 5 s from an empty bar to out cold
    private static final float RECOVER_TICKS = 30f; // 1.5 s back from out cold after a breath
    /** "No bubbles left": vanilla shows none at 2 air or less; a little above that absorbs the
     *  +4 a tick vanilla gives back out of water while something else (fumes) drains it again. */
    private static final int EMPTY_AIR = 5;

    private static int lastTick = Integer.MIN_VALUE;
    private static float level;

    private OxygenBlackout() {}

    public static float intensity(Player player) {
        int air = player.getAirSupply();
        int maxAir = player.getMaxAirSupply();
        if (!FullStopConfig.CLIENT.blackoutOnlyWithoutAir.get()) {
            if (air >= maxAir) return 0f;
            float i = 1.0f - (float) air / (float) maxAir;
            return i * i * i;
        }
        int now = player.tickCount;
        int dt = now - lastTick;
        lastTick = now;
        if (dt < 0) {           // a new player entity (respawn, dimension change) or the first call
            level = 0f;
            dt = 0;
        }
        dt = Math.min(dt, 20);  // a long pause or a lag spike is not 20 s without air
        if (air <= EMPTY_AIR) {
            level = Math.min(1f, Math.max(level, AT_EMPTY) + dt * (1f - AT_EMPTY) / FADE_TICKS);
        } else {
            float lastTwoBubbles = maxAir / 5f;
            float target = air < lastTwoBubbles ? AT_EMPTY * (1f - air / lastTwoBubbles) : 0f;
            // going down, follow the air; coming back up, recover at RECOVER_TICKS' pace
            level = level > target ? Math.max(target, level - dt / RECOVER_TICKS) : target;
        }
        return level;
    }
}
