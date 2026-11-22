package net.camacraft.fullstop.common.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * A boots enchantment about landings, I to III, compatible with every other (Stomp; Acrobat has its own class). It
 * acts at its own point of a landing (the body landed on), so it shuts no other enchantment out: the numbers keep
 * them in balance. What it does is {@code server.physics.damage.Stomp}.
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
