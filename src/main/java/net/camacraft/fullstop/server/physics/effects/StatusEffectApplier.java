package net.camacraft.fullstop.server.physics.effects;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.registry.ModEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.Enchantments;

public class StatusEffectApplier {

    public static void applyForceEffects(Entity entity, FullStopCapability fullstop) {
        if (!(entity instanceof LivingEntity livingEntity)) return;

        // Threshold first: it is a plain field read and almost always fails,
        // while isDamageImmune reads SynchedEntityData (sleeping pose) per call.
        double avgAccel = fullstop.getRunningAverageDelta();
        if (avgAccel <= 5.0) return;

        if (DamageImmunityRules.isDamageImmune(livingEntity)) return;

        // Re-adding an active effect every tick syncs entity data every tick for
        // every affected mob; only top it up as it approaches running out. The
        // refresh floors sit ABOVE the client's fade-out thresholds (blindness fog
        // fades under ~20 ticks, nausea scales with remaining time), so sustained
        // high-G reads as a steady effect instead of a pulsing one.
        if (avgAccel > 8.0 && shouldRefresh(livingEntity, MobEffects.BLINDNESS, 25)) {
            livingEntity.addEffect(new MobEffectInstance(
                    MobEffects.BLINDNESS, 60, 0, true, false));
        }

        if (shouldRefresh(livingEntity, MobEffects.CONFUSION, 70)) {
            livingEntity.addEffect(new MobEffectInstance(
                    MobEffects.CONFUSION, 120, 0, true, false));
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
        if (!fullstop.isMostlyDownward()) return;

        if (collision.collisionType == Collision.CollisionType.ENTITY) {
            boolean landedOnSomeone = false;
            for (Entity other : collision.collidingEntities) {
                if (entity.getY() >= other.getY() + other.getBbHeight() * 0.5) {
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
