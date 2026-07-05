package net.camacraft.fullstop.common.physics.rules;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/** Equipment/effect checks shared by mixins, damage sources, and rules. */
public final class EquipmentRules {
    private EquipmentRules() {
    }

    public static boolean hasDolphinsGrace(LivingEntity entity) {
        return entity instanceof Player player && player.hasEffect(MobEffects.DOLPHINS_GRACE);
    }

    public static boolean hasElytraEquipped(LivingEntity entity) {
        ItemStack chestStack = entity.getItemBySlot(EquipmentSlot.CHEST);

        if (chestStack.getItem() instanceof ElytraItem) {
            int remainingDurability = chestStack.getMaxDamage() - chestStack.getDamageValue();
            return remainingDurability > 1;
        }

        return false;
    }

    public static boolean hasDepthStrider(LivingEntity entity) {
        ItemStack boots = entity.getItemBySlot(EquipmentSlot.FEET);

        return !boots.isEmpty() &&
                EnchantmentHelper.getItemEnchantmentLevel(Enchantments.DEPTH_STRIDER, boots) > 0;
    }
}
