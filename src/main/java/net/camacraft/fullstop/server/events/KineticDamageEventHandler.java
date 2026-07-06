package net.camacraft.fullstop.server.events;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.registry.ModAttributes;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.camacraft.fullstop.common.util.EnchantmentUtils;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

/**
 * Scales attack damage by attacker/victim approach velocity and applies FullStop's
 * damage-mitigation layers (Kinetic Dampening attribute, Kinetic Protection and
 * Reflective enchantments).
 *
 * FullStop's own collision damage ({@link FullStopDamageSources.KineticSource})
 * gets the mitigation layers but NOT the approach-velocity scaling — it is already
 * velocity-derived, so re-scaling would double-dip.
 */
@Mod.EventBusSubscriber
public class KineticDamageEventHandler {

    /** Kinetic Dampening granted per leather armor piece (fraction of damage removed). */
    private static final double LEATHER_DAMPENING_PER_PIECE = 0.05;

    private static final UUID[] LEATHER_DAMPENING_UUIDS = {
            UUID.fromString("0d6890a5-3ac4-43f5-b273-2ffa4bbc8c1a"), // FEET
            UUID.fromString("70a02f1e-6dd6-4bdf-92a7-1a4c53bcd9b6"), // LEGS
            UUID.fromString("2d4a9a20-52bb-45f5-a3b0-9df01e9f0c4d"), // CHEST
            UUID.fromString("b3a706e3-3a52-4cd8-8f31-5a3312cf62d7"), // HEAD
    };

    /** Leather armor automatically grants a bit of Kinetic Dampening. */
    @SubscribeEvent
    public static void onItemAttributeModifiers(ItemAttributeModifierEvent event) {
        if (!(event.getItemStack().getItem() instanceof ArmorItem armor)) return;
        if (armor.getMaterial() != ArmorMaterials.LEATHER) return;
        if (event.getSlotType() != armor.getEquipmentSlot()) return;

        event.addModifier(ModAttributes.KINETIC_DAMPENING.get(), new AttributeModifier(
                LEATHER_DAMPENING_UUIDS[event.getSlotType().getIndex()],
                "fullstop.leather_dampening",
                LEATHER_DAMPENING_PER_PIECE,
                AttributeModifier.Operation.ADDITION));
    }

    // Lowest priority so other mods have a chance to change the damage prior to this
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        if (event.getEntity().level().isClientSide) return;

        boolean fullstopKinetic = event.getSource() instanceof FullStopDamageSources.KineticSource;
        Entity direct = event.getSource().getDirectEntity();

        if (direct == null && !fullstopKinetic) return;

        if (direct instanceof Projectile && SERVER.projectileMultiplier.get() == 0) return;
        if (direct instanceof AbstractArrow && !(SERVER.wildMode.get())) return;

        float newDamage = fullstopKinetic ? event.getAmount() : calcNewDamage(event);

        // Apply Kinetic Dampening Attribute (leather armor grants it; see above)
        var dampeningAttr = event.getEntity().getAttribute(ModAttributes.KINETIC_DAMPENING.get());
        if (dampeningAttr != null && dampeningAttr.getValue() > 0) {
            newDamage *= (float) (1.0 - dampeningAttr.getValue());
        }

        newDamage = applyKineticProtection(event.getEntity(), event.getSource(), fullstopKinetic, newDamage);

        // Reflective absorbs hits delivered BY another entity (velocity-transfer
        // collisions and attacks) — plain wall/ground impacts are not reflected.
        if (event.getSource().getEntity() != null && event.getSource().getEntity() != event.getEntity()) {
            newDamage = applyReflective(event.getEntity(), newDamage);
        }

        event.setAmount(newDamage);
    }

    private static float applyKineticProtection(LivingEntity entity, net.minecraft.world.damagesource.DamageSource source,
                                                boolean fullstopKinetic, float damage) {
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

        boolean mostlyDownward;
        boolean mostlyUpward;
        boolean mostlyHorizontal;

        Entity attacker = source.getEntity();
        if (fullstopKinetic && attacker != null && attacker != entity) {
            // Rammed by a mover: the hit direction is the attacker's motion, seen
            // from the victim's frame — something falling ON you strikes your head
            // (helmet), something rising from below strikes your feet (boots).
            FullStopCapability attackerCap = grabCapability(attacker);
            if (attackerCap == null) return damage;
            mostlyDownward = attackerCap.isMostlyUpward();
            mostlyUpward = attackerCap.isMostlyDownward();
            mostlyHorizontal = attackerCap.isMostlyHorizontal();
        } else {
            FullStopCapability cap = grabCapability(entity);
            if (cap == null) return damage;
            mostlyDownward = cap.isMostlyDownward();
            mostlyUpward = cap.isMostlyUpward();
            mostlyHorizontal = cap.isMostlyHorizontal();
        }

        double reduction = 0.0;
        double reductionPerLevel = 0.04; // 4% per level

        if (hasBoots && mostlyDownward) {
            reduction += protectionLevel * reductionPerLevel;
        }
        if (hasHelmet && mostlyUpward) {
            reduction += protectionLevel * reductionPerLevel;
        }
        if (hasHelmet && hasChest && hasLegs && hasBoots && mostlyHorizontal) {
            reduction += protectionLevel * reductionPerLevel;
        }

        // Cap reduction at 80%
        reduction = Math.min(reduction, 0.8);

        return damage * (float) (1.0 - reduction);
    }

    /**
     * Reflective's damage absorption. The physical "reflection" (keeping/returning
     * momentum) is owned by EntityCollisionHandler — applying it here too was
     * double-dipping, and pushing projectile "attackers" after impact did nothing.
     */
    private static float applyReflective(LivingEntity entity, float damage) {
        int reflectiveLevel = EnchantmentUtils.totalArmorLevel(entity, ModEnchantments.REFLECTIVE.get());
        if (reflectiveLevel == 0) return damage;

        float absorption = Math.min(reflectiveLevel * 0.05f, 0.5f);
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

        // Velocity-boosted melee hits cost extra weapon durability.
        if (attacker instanceof LivingEntity living && !(attacker instanceof Projectile) && originalDamage > 0) {
            int extraWear = Math.round(newDamage / originalDamage) - 1;
            if (extraWear > 0) {
                ItemStack item = living.getItemInHand(InteractionHand.MAIN_HAND);
                if (item.isDamageableItem()) {
                    // hurtAndBreak respects Unbreaking and handles item breaking properly.
                    item.hurtAndBreak(extraWear, living, e -> e.broadcastBreakEvent(EquipmentSlot.MAINHAND));
                }
            }
        }

        // Only bother nearby players with the crit sound when a player is involved.
        boolean playerInvolved = entity instanceof Player || attacker instanceof Player
                || event.getSource().getEntity() instanceof Player;
        if (newDamage > originalDamage && playerInvolved) {
            entity.level().playSound(null, entity.blockPosition(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.6F, 0.9F);
        }
        return newDamage;
    }
}
