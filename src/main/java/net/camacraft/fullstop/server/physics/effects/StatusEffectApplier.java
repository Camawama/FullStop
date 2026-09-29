package net.camacraft.fullstop.server.physics.effects;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.GForceThresholds;
import net.camacraft.fullstop.common.physics.rules.SpinDizziness;
import net.camacraft.fullstop.common.registry.ModEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.Enchantments;

public class StatusEffectApplier {

    // Blindness is the G-LOC itself: it must outlast the g that caused it.
    private static final int BLACKOUT_BLINDNESS_TICKS = 60;
    // Nausea after a blackout lingers well past the blindness ("when the
    // blackout fades, the nausea lasts a bit longer").
    private static final int BLACKOUT_NAUSEA_TICKS = 160;
    // Dizziness from sustained g or from spinning: nausea alone.
    private static final int DIZZY_NAUSEA_TICKS = 120;

    /**
     * The g-force status effects, from two independent measurements on the
     * capability (both smoothed, so a one-tick spike never counts):
     * <ul>
     * <li>the felt LINEAR acceleration - an abrupt change of velocity at
     *     speed (a hard turn, a launch, a slam). Past the configured
     *     blackout threshold the player is BLINDED, with nausea that lingers
     *     after the blindness fades; halfway between the two thresholds
     *     nausea alone sets in.</li>
     * <li>the TURNING rate - the view spinning round, or a fast run
     *     circling. That is dizziness: nausea, never a blackout, and only once
     *     sustained spinning past the threshold has built it up
     *     ({@link SpinDizziness}).</li>
     * </ul>
     * Both thresholds come from the server config, scaled by the Clarity and
     * Vertigo potions per player.
     */
    public static void applyForceEffects(Entity entity, FullStopCapability fullstop) {
        if (!(entity instanceof LivingEntity livingEntity)) return;

        // Plain field reads first; the threshold lookups and the immunity
        // check (which reads SynchedEntityData) only run when they can matter.
        double avgAccel = fullstop.getRunningAverageDelta();
        double spin = fullstop.getSpinRate();
        if (avgAccel <= 0.5 && spin <= 0.5 && fullstop.getSpinDose() <= 0.0) return;

        GForceThresholds.Range range = GForceThresholds.configured(livingEntity);
        double nauseaAt = GForceThresholds.nauseaLevel(range);
        double blackoutAt = GForceThresholds.blackoutLevel(range);
        double spinAt = GForceThresholds.spinNauseaLevel(livingEntity);

        boolean blackout = avgAccel >= blackoutAt;
        // Spinning only makes you dizzy once enough of it has built up (SpinDizziness).
        boolean dizzy = avgAccel >= nauseaAt || SpinDizziness.dizzy(fullstop.getSpinDose(), spin, spinAt,
                net.camacraft.fullstop.FullStopConfig.SERVER.spinNauseaTurns.get());
        if (!blackout && !dizzy) return;

        if (DamageImmunityRules.isDamageImmune(livingEntity)) return;

        // Re-adding an active effect every tick syncs entity data every tick for
        // every affected mob; only top it up as it approaches running out. The
        // refresh floors sit ABOVE the client's fade-out thresholds (blindness fog
        // fades under ~20 ticks, nausea scales with remaining time), so sustained
        // high-G reads as a steady effect instead of a pulsing one.
        if (blackout && shouldRefresh(livingEntity, MobEffects.BLINDNESS, 25)) {
            livingEntity.addEffect(new MobEffectInstance(
                    MobEffects.BLINDNESS, BLACKOUT_BLINDNESS_TICKS, 0, true, false));
        }

        int nauseaTicks = blackout ? BLACKOUT_NAUSEA_TICKS : DIZZY_NAUSEA_TICKS;
        // a blackout's longer nausea must not be trimmed by a running shorter one
        MobEffectInstance nausea = livingEntity.getEffect(MobEffects.CONFUSION);
        if (nausea == null || nausea.getDuration() < 70 || (blackout && nausea.getDuration() < nauseaTicks - 40)) {
            livingEntity.addEffect(new MobEffectInstance(
                    MobEffects.CONFUSION, nauseaTicks, 0, true, false));
        }
    }

    private static boolean shouldRefresh(LivingEntity entity, net.minecraft.world.effect.MobEffect effect, int refreshBelowTicks) {
        MobEffectInstance active = entity.getEffect(effect);
        return active == null || active.getDuration() < refreshBelowTicks;
    }

    /**
     * Hard landings sprain the legs and slow the entity down for a while.
     * Landing-only by design: ramming something sideways (walls or entities) is
     * not a sprain, and for entity collisions the faller must actually have come
     * down on top of the other entity.
     */
    public static void applyDamageEffects(Entity entity, FullStopCapability fullstop, Collision collision, double damage) {
        if (damage <= 0) return;
        // A real fall only: in zero gravity a feet-first bump into a floor sprains nothing.
        if (!fullstop.isFall()) return;

        if (collision.collisionType == Collision.CollisionType.ENTITY) {
            boolean landedOnSomeone = false;
            for (Entity other : collision.collidingEntities) {
                // "on top of" as the faller's gravity frame sees it
                if (fullstop.getFrame().vertical(entity.position().subtract(other.position())) >= other.getBbHeight() * 0.5) {
                    landedOnSomeone = true;
                    break;
                }
            }
            if (!landedOnSomeone) return;
        }

        if (!(entity instanceof LivingEntity livingEntity)) return;
        if (DamageImmunityRules.isDamageImmune(livingEntity)) return;
        if (fullstop.getIsDamageImmune()) return;
        if (entity.isInvulnerable()) return;

        int fallProtLevel = livingEntity.getItemBySlot(EquipmentSlot.FEET).getEnchantmentLevel(Enchantments.FALL_PROTECTION);
        double scale = 1.0 - fallProtLevel * 0.2;
        if (scale <= 0) return;

        // Clamp: unclamped, a 20-damage survivable landing handed out Slowness X+
        // (fully frozen) for an unbounded duration.
        int duration = Math.min((int) (damage * 5 * scale), 600);
        int slownessAmplifier = Math.min((int) ((damage / 2) * scale), 4);

        livingEntity.addEffect(new MobEffectInstance(ModEffects.SPRAIN.get(),
                duration, 0, false, false));
        livingEntity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                duration, slownessAmplifier, false, false));
    }
}
