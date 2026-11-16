package net.camacraft.fullstop.common.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * Acrobat (boots, I to III): a landing may be rolled out of (part of the fall's damage) or, rarely, stuck perfectly
 * (none of it). A chance, where Feather Falling and Kinetic Protection take a steady share every time; it is rolled
 * after them, so the three stack without making falls free. The rolls and their effect are
 * {@link net.camacraft.fullstop.server.physics.damage.Acrobat}.
 */
public class AcrobatEnchantment extends Enchantment {
    public AcrobatEnchantment(Rarity rarity) {
        super(rarity, EnchantmentCategory.ARMOR_FEET, new EquipmentSlot[] {EquipmentSlot.FEET});
    }

    @Override
    public int getMinCost(int level) {
        return 5 + (level - 1) * 10;
    }

    @Override
    public int getMaxCost(int level) {
        return this.getMinCost(level) + 15;
    }

    @Override
    public int getMaxLevel() {
        return 3;
    }
}
