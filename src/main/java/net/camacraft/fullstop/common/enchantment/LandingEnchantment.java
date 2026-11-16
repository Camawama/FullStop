package net.camacraft.fullstop.common.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * A boots enchantment about landings, I to III, compatible with every other (Stomp and Rebound; Acrobat has its own
 * class). Each acts at its own point of a landing (the body landed on, what the ground gives back), so none of them
 * shuts another out: the numbers keep them in balance. What they do is {@code server.physics.damage.Stomp} and
 * {@code Rebound}.
 */
public class LandingEnchantment extends Enchantment {
    private final int baseCost;
    private final int costPerLevel;

    public LandingEnchantment(Rarity rarity, int baseCost, int costPerLevel) {
        super(rarity, EnchantmentCategory.ARMOR_FEET, new EquipmentSlot[] {EquipmentSlot.FEET});
        this.baseCost = baseCost;
        this.costPerLevel = costPerLevel;
    }

    @Override
    public int getMinCost(int level) {
        return baseCost + (level - 1) * costPerLevel;
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
