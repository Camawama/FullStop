package net.camacraft.fullstop.server.physics.interaction;

import net.camacraft.camafy.compat.scale.EntityScale;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Knockback between resized entities goes by their sizes relative to each other ({@code scaledKnockback}).
 *
 * <p>Pehkui (which VS Genesis bundles: everything in its space is 1/16) moves a resized entity by only its motion
 * scale's share of its own velocity, and leaves the knockback it is dealt alone. So a full-size player's hit carried a
 * 1/16 mob a sixteenth as far as a normal one - a tiny mob on a lead barely swung - while two 1/16 bodies knocked each
 * other about as normal ones do (in their own lengths). The strength is multiplied by the hitter's motion scale over the
 * victim's: two bodies of the same size (both 1/16, or both normal) knock each other back exactly as vanilla does; a
 * full-size hit sends a 1/16 mob as far across the world as it sends a normal one (sixteen of its own lengths); a 1/16
 * player's hit barely moves a full-size mob. The hitter is the one that hurt the victim this tick (a projectile counts
 * as itself: Pehkui sizes a projectile by its shooter); knockback nobody dealt (a shield's recoil) is left alone.</p>
 */
public final class ScaledKnockback {

    /** The hitter far bigger or smaller than this relative to the victim changes nothing more. */
    private static final double MAX_RATIO = 64.0;

    private record Hitter(Entity entity, long tick) {}

    /** Who hurt each entity this tick: vanilla's knockback comes after the hurt, in the same call. */
    private static final Map<LivingEntity, Hitter> HITTERS = Collections.synchronizedMap(new WeakHashMap<>());

    private ScaledKnockback() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onAttack(LivingAttackEvent event) {
        if (event.isCanceled()) return;
        Entity hitter = event.getSource().getDirectEntity();
        if (hitter == null) hitter = event.getSource().getEntity();
        LivingEntity victim = event.getEntity();
        if (hitter == null || hitter == victim) return;
        HITTERS.put(victim, new Hitter(hitter, victim.level().getGameTime()));
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onKnockback(LivingKnockBackEvent event) {
        if (!FullStopConfig.SERVER_SPEC.isLoaded() || !FullStopConfig.SERVER.scaledKnockback.get()) return;
        LivingEntity victim = event.getEntity();
        Hitter hitter = HITTERS.get(victim);
        if (hitter == null || hitter.tick() != victim.level().getGameTime()) return;
        double ratio = EntityScale.motion(hitter.entity()) / EntityScale.motion(victim);
        if (!Double.isFinite(ratio) || Math.abs(ratio - 1.0) < 1.0e-6) return;
        event.setStrength((float) (event.getStrength() * Mth.clamp(ratio, 1.0 / MAX_RATIO, MAX_RATIO)));
    }
}
