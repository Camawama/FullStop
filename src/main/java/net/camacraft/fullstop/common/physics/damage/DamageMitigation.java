package net.camacraft.fullstop.common.physics.damage;

import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

public class DamageMitigation {

    public static float applyArmorReduction(LivingEntity entity, float rawDamage, boolean mostlyDownward, boolean mostlyUpward) {
        double armor = entity.getAttributeValue(Attributes.ARMOR);
        double toughness = entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS);

        double toughnessReduction = Math.max(0.5, 1.0 - (toughness / 40.0));

        rawDamage *= (float) toughnessReduction;

        if (mostlyDownward) {
            ItemStack boots = entity.getItemBySlot(EquipmentSlot.FEET);
            if (!boots.isEmpty()) {
                rawDamage *= 0.9f;
                damageArmor(entity, boots, 1);
            }

            int featherFallingLevel = boots.getEnchantmentLevel(Enchantments.FALL_PROTECTION);
            if (featherFallingLevel > 0) {
                float reduction = 0.2f * featherFallingLevel;
                rawDamage *= Math.max(0.0f, 1.0f - reduction);

                if (featherFallingLevel >= 4) {
                    float maxSurvivableDamage = entity.getMaxHealth() - 1.0f;
                    if (maxSurvivableDamage < 1.0f) maxSurvivableDamage = 1.0f;

                    rawDamage = Math.min(rawDamage, maxSurvivableDamage);
                }
            }
            return rawDamage;
        } else if (mostlyUpward) {
            ItemStack helmet = entity.getItemBySlot(EquipmentSlot.HEAD);
            if (!helmet.isEmpty()) {
                rawDamage *= 0.85f;
                damageArmor(entity, helmet, 1);
            }
            return CombatRules.getDamageAfterAbsorb(rawDamage, (float) armor, (float) toughness);
        } else {
            damageArmor(entity, entity.getItemBySlot(EquipmentSlot.HEAD), 1);
            damageArmor(entity, entity.getItemBySlot(EquipmentSlot.CHEST), 1);
            damageArmor(entity, entity.getItemBySlot(EquipmentSlot.LEGS), 1);
            damageArmor(entity, entity.getItemBySlot(EquipmentSlot.FEET), 1);

            return CombatRules.getDamageAfterAbsorb(rawDamage, (float) armor, (float) toughness);
        }
    }

    private static void damageArmor(LivingEntity entity, ItemStack stack, int amount) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return;

        if (stack.getItem() instanceof ArmorItem armorItem) {
            if (armorItem.getMaterial().getKnockbackResistance() > 0) return;
        }

        stack.hurtAndBreak(amount, entity, (e) -> {});
    }
}
