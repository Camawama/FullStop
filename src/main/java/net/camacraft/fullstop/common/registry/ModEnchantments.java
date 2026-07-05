package net.camacraft.fullstop.common.registry;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.enchantment.KineticProtectionEnchantment;
import net.camacraft.fullstop.common.enchantment.PullbackEnchantment;
import net.camacraft.fullstop.common.enchantment.ReflectiveEnchantment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, FullStop.MODID);

    public static final RegistryObject<Enchantment> KINETIC_PROTECTION =
            ENCHANTMENTS.register("kinetic_protection",
                    () -> new KineticProtectionEnchantment(Enchantment.Rarity.UNCOMMON,
                            EnchantmentCategory.ARMOR, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET));

    public static final RegistryObject<Enchantment> REFLECTIVE =
            ENCHANTMENTS.register("reflective",
                    () -> new ReflectiveEnchantment(Enchantment.Rarity.RARE,
                            EnchantmentCategory.ARMOR, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET));

    public static final RegistryObject<Enchantment> PULLBACK =
            ENCHANTMENTS.register("pullback",
                    () -> new PullbackEnchantment(Enchantment.Rarity.RARE,
                            EnchantmentCategory.WEAPON, EquipmentSlot.MAINHAND));

    public static void register(IEventBus eventBus) {
        ENCHANTMENTS.register(eventBus);
    }
}
