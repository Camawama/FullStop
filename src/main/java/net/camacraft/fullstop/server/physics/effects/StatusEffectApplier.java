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
        if (entity instanceof LivingEntity livingEntity) {
            if (DamageImmunityRules.isDamageImmune(livingEntity)) return;
            if (fullstop.getRunningAverageDelta() > 8.0) {
                livingEntity.addEffect(new MobEffectInstance(
                        MobEffects.BLINDNESS, 30, 0, true, false));
            }

            if (fullstop.getRunningAverageDelta() > 5.0) {
                livingEntity.addEffect(new MobEffectInstance(
                        MobEffects.CONFUSION, 90, 0, true, false));
            }
        }
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

        livingEntity.addEffect(new MobEffectInstance(ModEffects.SPRAIN.get(),
                (int) (damage * 5 * scale), 0, false, false));
        livingEntity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                (int) (damage * 5 * scale), (int) ((damage / 2) * scale), false, false));
    }
}
