package net.camacraft.fullstop.common.enchantments;

import net.camacraft.fullstop.FullStop;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, FullStop.MOD_ID);

    public static final RegistryObject<Enchantment> KINETIC_PROTECTION = ENCHANTMENTS.register(
            "kinetic_protection",
            () -> new KineticProtectionEnchantment(Enchantment.Rarity.UNCOMMON, EnchantmentCategory.ARMOR,
                    new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
    );

    public static final RegistryObject<Enchantment> REFLECTIVE = ENCHANTMENTS.register(
            "reflective",
            () -> new ReflectiveEnchantment(Enchantment.Rarity.RARE, EnchantmentCategory.ARMOR,
                    new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
    );
}
