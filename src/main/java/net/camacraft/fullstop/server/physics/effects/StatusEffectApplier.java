package net.camacraft.fullstop.server.physics.effects;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.effect.ModEffects;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
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
            if (fullstop.getRunningAverageDelta() > 7.0) {
                livingEntity.addEffect(new MobEffectInstance(
                        MobEffects.BLINDNESS, 30, 0, true, false));
            }

            if (fullstop.getRunningAverageDelta() > 4.0) {
                livingEntity.addEffect(new MobEffectInstance(
                        MobEffects.CONFUSION, 90, 0, true, false));
            }
        }
    }

    public static void applyDamageEffects(Entity entity, FullStopCapability fullstop, double damage) {
        if (damage <= 0) return;

        if (fullstop.isMostlyHorizontal() || fullstop.isMostlyUpward()) return;

        if (entity instanceof LivingEntity living && DamageImmunityRules.isDamageImmune(living)) {
            return;
        }

        if (fullstop.getIsDamageImmune()) return;
        if (entity.isInvulnerable()) return;

        if (entity instanceof LivingEntity livingEntity) {
            int fallProtLevel = livingEntity.getItemBySlot(EquipmentSlot.FEET).getEnchantmentLevel(Enchantments.FALL_PROTECTION);

            if (fullstop.isMostlyDownward()) {
                livingEntity.addEffect(new MobEffectInstance(ModEffects.SPRAIN.get(),
                        (int) (damage * 5 * (1.0 - fallProtLevel * 0.2)), 0, false, false));
            } else {
                livingEntity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,
                        (int) (damage * 5 * (1.0 - fallProtLevel * 0.2)), (int) ((damage / 2) * (1.0 - fallProtLevel * 0.2)), false, false));
            }
            livingEntity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                    (int) (damage * 5 * (1.0 - fallProtLevel * 0.2)), (int) ((damage / 2) * (1.0 - fallProtLevel * 0.2)), false, false));
        }
    }
}
