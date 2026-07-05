package net.camacraft.fullstop.common.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public final class EnchantmentUtils {
    private EnchantmentUtils() {
    }

    /** Sum of the given enchantment's levels across all worn armor pieces. */
    public static int totalArmorLevel(LivingEntity entity, Enchantment enchantment) {
        int total = 0;
        for (ItemStack stack : entity.getArmorSlots()) {
            total += EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack);
        }
        return total;
    }
}
