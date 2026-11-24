package net.camacraft.fullstop.common.physics.rules;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FallCushion;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Landings another mod has made harmless, which Full Stop's landing damage honours. Full Stop cancels vanilla's fall
 * damage and bills a landing itself, so a mod that spares a fall the vanilla way (before Forge's {@code LivingFallEvent}
 * is ever posted) was not heard, and the landing hurt as anywhere.
 *
 * <p><b>DimDoors' Limbo</b> ({@code limboLandingsHarmless}). DimDoors sends a player to Limbo high in the air (a death
 * there at Y 512, a rift into it at Y 255) and lets them fall to the ground: its {@code PlayerEntityMixin} answers
 * {@code Player.causeFallDamage} with false at its head whenever the player is in Limbo ({@code ModDimensions
 * .isLimboDimension}: the level's key is {@code dimdoors:limbo}), so vanilla's fall never hurts a player there. Full
 * Stop's landing of a player in that world deals nothing now, as vanilla's would; side and ceiling impacts still hurt,
 * and mobs (which DimDoors leaves to vanilla's fall damage) land as anywhere.</p>
 *
 * <p><b>A negative fall distance</b> ({@code fallCushionsHonoured}). Vanilla only ever adds to a body's fall distance as
 * it drops and resets it to 0, so a negative one is a mod's cushion: the next that many blocks of fall don't count, and
 * vanilla's landing deals nothing while it is below 0 (DimDoors' escape from Limbo sets -500 after its teleport). A
 * living body on such a cushion takes no landing damage. The cushion is a state, not a moment: it is noted whenever the
 * fall distance is seen below 0, at any move ({@code EntityFallCushionMixin}, at the head of vanilla's
 * {@code checkFallDamage}, which every move of a body runs, a player's at each move packet) or at Full Stop's tick, and it
 * lasts the whole fall, however long; it ends when the fall distance is seen above 0 (the cushion used up, or a fall
 * counted afresh from the ground) or when the body has stood on the ground for {@link #CUSHION_GROUND_TICKS} ticks after
 * its landing. A first version kept it 3 ticks from the last tick it was seen at the end of a level tick: a player's
 * landing spared on its first reading was billed in full when the same stop was read again 3 ticks later (pack harness
 * {@code fs_limbo}, 14.38 at tick 27 of a 16-block drop whose plain twin landed at tick 24).</p>
 */
public final class HarmlessFalls {

    /** DimDoors' Limbo: {@code ModDimensions.LIMBO}, the key its own Limbo test compares the level's with. */
    public static final ResourceLocation LIMBO = new ResourceLocation("dimdoors", "limbo");
    /**
     * Ticks a cushioned body stands on the ground, its fall distance 0, before the cushion is over: every reading of its
     * landing (a two-tick impact, a player's moves reaching the server late) comes within it, and any new fall clears it
     * sooner, since a fall counted from the ground has a fall distance above 0.
     */
    public static final int CUSHION_GROUND_TICKS = 40;

    private HarmlessFalls() {
    }

    /**
     * The body's fall cushion at Full Stop's tick (every tick the body is processed, before its landing is judged): noted
     * while the fall distance is below 0, over once it is above 0 or the body has stood on the ground long enough.
     */
    public static void sample(Entity entity) {
        if (!(entity instanceof FallCushion cushion)) return;
        if (entity.fallDistance < 0.0F) {
            cushion.fullstop$setFallCushioned(true);
            cushion.fullstop$setCushionGroundTicks(0);
            return;
        }
        if (!cushion.fullstop$fallCushioned()) return;
        if (entity.fallDistance > 0.0F) {
            cushion.fullstop$setFallCushioned(false);
        } else if (entity.onGround()) {
            int grounded = cushion.fullstop$cushionGroundTicks() + 1;
            cushion.fullstop$setCushionGroundTicks(grounded);
            if (grounded > CUSHION_GROUND_TICKS) cushion.fullstop$setFallCushioned(false);
        }
    }

    /** Whether the body is on a fall cushion now (its fall distance below 0, or seen below 0 during this fall). */
    public static boolean cushioned(Entity entity) {
        return entity.fallDistance < 0.0F || (entity instanceof FallCushion cushion && cushion.fullstop$fallCushioned());
    }

    /**
     * Whether this tick's landing (a {@code FullStopCapability.isFall()} fall) is one another mod has made harmless: a
     * player in DimDoors' Limbo, or a living body landing on a mod's fall cushion.
     */
    public static boolean spared(Entity entity) {
        if (!FullStopConfig.SERVER_SPEC.isLoaded()) return false;
        if (entity instanceof Player && inLimbo(entity) && FullStopConfig.SERVER.limboLandingsHarmless.get()) return true;
        return entity instanceof LivingEntity && FullStopConfig.SERVER.fallCushionsHonoured.get() && cushioned(entity);
    }

    /** Whether the body is in DimDoors' Limbo (as DimDoors itself tells it: by the level's key). */
    public static boolean inLimbo(Entity entity) {
        return LIMBO.equals(entity.level().dimension().location());
    }
}
