package net.camacraft.fullstop.server.handler.events;

import net.camacraft.fullstop.common.attribute.ModAttributes;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.enchantment.ModEnchantments;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

@Mod.EventBusSubscriber
public class KineticDamageEventHandler {

    // Lowest priority so other mods have a chance to change the damage prior to this
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        if (event.getSource().getDirectEntity() == null) return;
        if (event.getEntity().level().isClientSide) return;

        if (event.getSource().getDirectEntity() instanceof Projectile && SERVER.projectileMultiplier.get() == 0) return;
        if (event.getSource().getDirectEntity() instanceof AbstractArrow && !(SERVER.wildMode.get())) return;

        float newDamage = calcNewDamage(event);

        // Apply Kinetic Dampening Attribute
        var dampeningAttr = event.getEntity().getAttribute(ModAttributes.KINETIC_DAMPENING.get());
        if (dampeningAttr != null && dampeningAttr.getValue() > 0) {
            newDamage *= (1.0 - dampeningAttr.getValue());
        }

        // Apply Leather Armor Dampening
        int leatherPieces = 0;
        for (ItemStack stack : event.getEntity().getArmorSlots()) {
            if (stack.getItem() instanceof ArmorItem armor && armor.getMaterial() == ArmorMaterials.LEATHER) {
                leatherPieces++;
            }
        }
        if (leatherPieces > 0) {
            // 5% reduction per piece, up to 20%
            newDamage *= (1.0 - (leatherPieces * 0.05));
        }

        // Apply Kinetic Protection Enchantment
        newDamage = applyKineticProtection(event.getEntity(), newDamage);

        // Apply Reflective Enchantment
        newDamage = applyReflective(event.getEntity(), event.getSource().getDirectEntity(), newDamage);

        event.setAmount(newDamage);
    }

    private static float applyKineticProtection(LivingEntity entity, float damage) {
        int protectionLevel = 0;
        boolean hasHelmet = false;
        boolean hasChest = false;
        boolean hasLegs = false;
        boolean hasBoots = false;

        for (ItemStack stack : entity.getArmorSlots()) {
            int level = EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.KINETIC_PROTECTION.get(), stack);
            if (level > 0) {
                protectionLevel += level;
                if (stack.getItem() instanceof ArmorItem armor) {
                    switch (armor.getEquipmentSlot()) {
                        case HEAD -> hasHelmet = true;
                        case CHEST -> hasChest = true;
                        case LEGS -> hasLegs = true;
                        case FEET -> hasBoots = true;
                    }
                }
            }
        }

        if (protectionLevel == 0) return damage;

        FullStopCapability cap = grabCapability(entity);
        if (cap == null) return damage;

        double reduction = 0.0;
        double reductionPerLevel = 0.04; // 4% per level

        if (hasBoots && cap.isMostlyDownward()) {
            reduction += protectionLevel * reductionPerLevel;
        }
        if (hasHelmet && cap.isMostlyUpward()) {
            reduction += protectionLevel * reductionPerLevel;
        }
        if (hasHelmet && hasChest && hasLegs && hasBoots && cap.isMostlyHorizontal()) {
            reduction += protectionLevel * reductionPerLevel;
        }

        // Cap reduction at 80%
        reduction = Math.min(reduction, 0.8);

        return damage * (float) (1.0 - reduction);
    }

    private static float applyReflective(LivingEntity entity, Entity attacker, float damage) {
        int reflectiveLevel = 0;
        for (ItemStack stack : entity.getArmorSlots()) {
            reflectiveLevel += EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.REFLECTIVE.get(), stack);
        }

        if (reflectiveLevel == 0) return damage;

        // Reflect velocity
        // The logic for velocity transfer is in EntityCollisionHandler, but we can add an extra bounce here or modify the attacker's velocity
        // However, the prompt says: "it will absorb a small amount of the incoming damage and also reflect the incoming velocity from the entity"
        
        // Damage absorption: 5% per total level?
        float absorption = reflectiveLevel * 0.05f;
        absorption = Math.min(absorption, 0.5f); // Cap at 50%

        // Velocity reflection
        // We push the attacker back
        if (attacker != null) {
            double reflectionStrength = reflectiveLevel * 0.15;
            // Vector from entity to attacker
            var pushDir = attacker.position().subtract(entity.position()).normalize();
            attacker.push(pushDir.x * reflectionStrength, pushDir.y * reflectionStrength, pushDir.z * reflectionStrength);
            attacker.hurtMarked = true;
        }

        return damage * (1.0f - absorption);
    }

    public static float calculateNewDamage(float approachVelocity, float originalDamage) {
        if (approachVelocity == 0) {
            return originalDamage;
        }

        float arbitraryVelocity = Math.abs(approachVelocity) / SERVER.velocityIncrement.get().floatValue();
        float multiplier = (float) (Math.pow(arbitraryVelocity, SERVER.exponentiationConstant.get().floatValue()) / 2F);
        float percentageBonus = originalDamage * multiplier;

        if (approachVelocity < 0) {
            float minDamage = originalDamage * SERVER.minDamagePercent.get().floatValue();
            return Math.max(minDamage, originalDamage - percentageBonus);
        }

        float maxDamage = originalDamage * SERVER.maxDamagePercent.get().floatValue();
        return Math.min(maxDamage, originalDamage + percentageBonus);
    }

    public static float calcNewDamage(LivingHurtEvent event) {
        Entity entity = event.getEntity();

        Entity attacker = event.getSource().getDirectEntity();

        float originalDamage = event.getAmount();

        double approachVelocity = VelocityMath.calculateApproachVelocity(attacker, event.getEntity());
        float newDamage = calculateNewDamage((float) approachVelocity, originalDamage);
        int damageRatio = Math.round(newDamage / originalDamage);

        if (attacker instanceof LivingEntity living) {
            ItemStack item = living.getItemInHand(InteractionHand.MAIN_HAND);
            if (item.isDamageableItem()) {
                int currentValue = item.getDamageValue();
                item.setDamageValue(currentValue + damageRatio - 1);
            }
        }

        if (newDamage > originalDamage) {
            entity.level().playSound(null, entity.blockPosition(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.6F, 0.9F);
        }
        return newDamage;
    }
}
