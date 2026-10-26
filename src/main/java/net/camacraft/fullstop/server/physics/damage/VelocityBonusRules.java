package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hits FullStop's speed bonus leaves alone ({@code velocityBonusExemptions}; the owner, 2026-10-25: "Moving when hitting
 * something with a bullet should not change the damage of the bullet", and the spears' and the mace's damage "should not
 * stack with fullstop's added damage from velocity"):
 * <ul>
 *   <li>a weapon that prices its own speed in, held or being used ({@code #fullstop:own_velocity_damage}: the backported
 *   spears, whose charge goes by speed, and the mace, whose smash goes by the fall);</li>
 *   <li>a class of damage source, or of the item the attacker holds or uses, named in {@code velocityBonusExemptClasses}:
 *   other mods' guns (Vic's Point Blank's {@code HurtingItem}: its hitscan bullets arrive as a plain player attack from
 *   the shooter, which looked to FullStop like a punch thrown by a running player);</li>
 *   <li>a damage type in {@code #fullstop:no_velocity_bonus}, which holds {@code #minecraft:is_explosion}: a blast's
 *   direct entity is what went off (Point Blank's grenade or rocket, a primed TNT, a creeper), so FullStop priced the
 *   victim running into it (or away) as if the two had collided;</li>
 *   <li>a hit the attacker landed beyond its reach (a hitscan gun of any mod, a warden's boom): the attacker's body never
 *   met the victim, so its speed isn't part of the blow.</li>
 * </ul>
 * FullStop's armor layers (dampening, Kinetic Protection, Reflective) still apply to these hits.
 */
public final class VelocityBonusRules {
    public static final TagKey<Item> OWN_VELOCITY_WEAPONS =
            TagKey.create(Registries.ITEM, new ResourceLocation(FullStop.MODID, "own_velocity_damage"));
    public static final TagKey<DamageType> NO_VELOCITY_BONUS =
            TagKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "no_velocity_bonus"));

    /** How far past its reach a hit may land and still count as the attacker's body meeting the victim. */
    private static final double REACH_SLACK = 2.0;
    /** A mob's reach (players have their own attribute). */
    private static final double MOB_REACH = 3.0;

    private static final Map<Class<?>, Boolean> EXEMPT_CLASSES = new ConcurrentHashMap<>();
    private static volatile List<? extends String> classList;
    private static volatile Set<String> classNames = Set.of();

    private VelocityBonusRules() {}

    /** Whether FullStop's speed bonus is left off this hit (see the class comment). */
    public static boolean exempt(DamageSource source, Entity direct, LivingEntity victim) {
        if (!FullStopConfig.SERVER.velocityBonusExemptions.get()) return false;
        if (source.is(NO_VELOCITY_BONUS)) return true;
        if (exemptClass(source.getClass())) return true;
        if (direct instanceof LivingEntity attacker && attacker != victim) {
            if (ownsItsDamage(attacker.getMainHandItem())) return true;
            if (attacker.isUsingItem() && ownsItsDamage(attacker.getUseItem())) return true;
            return beyondReach(attacker, victim);
        }
        return false;
    }

    private static boolean ownsItsDamage(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(OWN_VELOCITY_WEAPONS) || exemptClass(stack.getItem().getClass()));
    }

    private static boolean beyondReach(LivingEntity attacker, LivingEntity victim) {
        double reach = attacker instanceof Player p ? p.getEntityReach() : MOB_REACH;
        double limit = reach + REACH_SLACK;
        return victim.getBoundingBox().distanceToSqr(attacker.getEyePosition()) > limit * limit;
    }

    private static boolean exemptClass(Class<?> type) {
        List<? extends String> list = FullStopConfig.SERVER.velocityBonusExemptClasses.get();
        if (list != classList) { // edited (or first read): look every class up again
            classList = list;
            classNames = Set.copyOf(list.stream().map(String::trim).toList());
            EXEMPT_CLASSES.clear();
        }
        if (classNames.isEmpty()) return false;
        return EXEMPT_CLASSES.computeIfAbsent(type, c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                if (classNames.contains(k.getName())) return true;
            }
            return false;
        });
    }
}
