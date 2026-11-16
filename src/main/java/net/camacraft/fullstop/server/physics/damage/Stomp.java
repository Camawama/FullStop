package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.camafy.minigame.MatchPolicies;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Stomp (boots, I to III): a body that comes down on a standing body (Full Stop's crush, {@code BodyCollisions}) passes
 * {@code stompShare} a level (20, 40, 60 percent) of its own landing damage to it, on top of the crush, and is spared
 * that share when it lands on the ground just after: the body underneath takes that part of the landing. The landing
 * damage is Full Stop's own figure for the closing speed ({@link KineticDamageCalculator#damageForSpeedLost}), so only a
 * landing hard enough to hurt stomps. The stomp is part of the crush's own hit ({@code fullstop:kinetic}, "crushed by"
 * the stomper), so the death message, Kinetic Protection and armour read it as they read any crush.
 *
 * <p>Who may be stomped: what Full Stop's collisions already allow between the two (Camafy's {@code MatchPolicies.canHarm}
 * for every body of both stacks, the scoreboard teams' collision rules, nothing another mod owns the contacts of), and a
 * player's hit also passes vanilla's friendly-fire rule; never the stomper's own pets (and nothing is spared then), never
 * its riders or its mount (one ride stack is one body, which never collides with itself). A player in Stomp boots who
 * would ride what it lands on (Full Stop's Entity Riding) stomps it instead when the landing would hurt; a gentler hop
 * still rides.
 */
public final class Stomp {
    /** Ticks after a stomp in which the stomper's own landing is spared (it falls on past the body to the ground). */
    static final int CREDIT_TICKS = 12;

    private record Credit(double share, long tick) {}

    private static final Map<Entity, Credit> CREDIT = new WeakHashMap<>();

    private Stomp() {}

    public static int level(Entity entity) {
        return entity instanceof LivingEntity living
                ? EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.STOMP.get(), living.getItemBySlot(EquipmentSlot.FEET)) : 0;
    }

    /** The share of the landing passed on, for a level. */
    public static double share(int level) {
        return level <= 0 ? 0.0 : Math.min(1.0, SERVER.stompShare.get() * level);
    }

    /** The stomper's own landing damage for coming down at {@code closing} m/s. */
    public static double landingDamage(Entity stomper, double closing) {
        return KineticDamageCalculator.damageForSpeedLost(stomper, closing, true, List.of());
    }

    /** Whether {@code stomper} stomps (rather than rides) what it comes down on at {@code closing} m/s. */
    public static boolean stompsInsteadOfRiding(Entity stomper, double closing) {
        return level(stomper) > 0 && landingDamage(stomper, closing) >= 1.0;
    }

    /** Whether {@code target} (the root of the stack landed on) may take a stomp from {@code stomper}. */
    public static boolean mayStomp(Entity stomper, Entity target) {
        if (!(target instanceof LivingEntity) || target == stomper) return false;
        if (stomper.getRootVehicle() == target.getRootVehicle()) return false;
        if (target instanceof OwnableEntity pet) {
            UUID owner = pet.getOwnerUUID();
            if (owner != null && owner.equals(stomper.getUUID())) return false;
        }
        if (!MatchPolicies.canHarm(stomper, target)) return false;
        if (stomper instanceof Player p && target instanceof Player q && !p.canHarmPlayer(q)) return false;
        if (stomper instanceof Player p && target instanceof OwnableEntity pet && pet.getOwner() instanceof Player owner
                && owner != p && !p.canHarmPlayer(owner)) return false;
        return true;
    }

    /**
     * The extra damage a crush deals {@code target} for {@code stomper} coming down on it at {@code closing} m/s (0 when
     * it doesn't stomp), and the stomper's credit for its own landing.
     */
    public static double stomp(Entity stomper, Entity target, double closing) {
        int level = level(stomper);
        if (level <= 0 || !mayStomp(stomper, target)) return 0.0;
        double landing = landingDamage(stomper, closing);
        if (landing < 1.0) return 0.0;
        double share = share(level);
        CREDIT.put(stomper, new Credit(share, stomper.level().getGameTime()));
        if (stomper.level() instanceof ServerLevel level2) {
            double x = target.getX(), y = target.getY() + target.getBbHeight(), z = target.getZ();
            level2.sendParticles(ParticleTypes.SWEEP_ATTACK, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            level2.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0f, 0.7f);
        }
        return landing * share;
    }

    /** The share of its own landing a stomper is spared (0 without a stomp in the last few ticks). */
    public static double spared(Entity lander) {
        Credit credit = CREDIT.get(lander);
        if (credit == null || lander.level().getGameTime() - credit.tick > CREDIT_TICKS) return 0.0;
        return credit.share;
    }

    /** Gametests only: forget a body's stomp. */
    public static void forget(Entity entity) {
        CREDIT.remove(entity);
    }
}
