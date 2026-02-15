package net.camacraft.fullstop.common.enchantment;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.phys.Vec3;

public class PullbackEnchantment extends Enchantment {
    public PullbackEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot... slots) {
        super(rarity, category, slots);
    }

    @Override
    public int getMinCost(int level) {
        return 5 + 20 * (level - 1);
    }

    @Override
    public int getMaxCost(int level) {
        return super.getMinCost(level) + 50;
    }

    @Override
    public int getMaxLevel() {
        return 2;
    }

    @Override
    public void doPostAttack(LivingEntity attacker, Entity target, int level) {
        if (target instanceof LivingEntity) {
            Vec3 pullDirection = attacker.position().subtract(target.position()).normalize();
            double pullStrength = level * 0.5; // Adjust strength as needed
            
            // We want to pull them towards us, but maybe not lift them up too much
            // Or maybe we do? Knockback lifts slightly.
            
            // Vanilla knockback adds to velocity. We should do the same but towards attacker.
            // However, we need to be careful not to just cancel out their movement if they are running away.
            // We want to apply an impulse towards the attacker.
            
            target.push(pullDirection.x * pullStrength, 0.1, pullDirection.z * pullStrength);
        }
    }
}
