package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Acrobat's rolls (boots, I to III). A fall that would hurt ({@code #minecraft:is_fall}: Full Stop's landings, vanilla
 * falls of bodies Full Stop leaves alone, other mods' falls) is rolled once: a perfect landing
 * ({@code acrobatPerfectLandingChance} a level) deals nothing; otherwise a roll ({@code acrobatRollChance} a level) deals
 * {@code acrobatRollDamage} of it; otherwise it lands as it would. Side impacts are not landings and stay Kinetic
 * Protection's. Feather Falling, Kinetic Protection and armour have already taken their share, so the three stack.
 *
 * <p>A perfect landing is turned away in {@link LivingAttackEvent}, before the hurt: no damage, no red flash, no thud,
 * no sprain, and Full Stop's impact counts nothing dealt. The outcome is kept for a few ticks, so the second tick of a
 * two-tick impact (Full Stop tops a landing up when it deals more on the next tick) gets the same answer instead of a
 * new roll. The sound and the puff are the server's ({@code ServerLevel.sendParticles}, {@code playSound(null, ...)}).
 */
@Mod.EventBusSubscriber
public final class Acrobat {
    /** What a landing came to. */
    public enum Outcome {
        NONE, ROLL, PERFECT
    }

    /** How long one landing's outcome holds (Full Stop's impact top-up window). */
    static final int HOLD_TICKS = 5;

    private record Held(Outcome outcome, long tick) {}

    private static final Map<LivingEntity, Held> HELD = new WeakHashMap<>();

    /** Gametests only: the random every roll draws from (null: the body's own). */
    @Nullable
    private static RandomSource testRandom;
    /** Gametests only: the outcome every roll gives (null: rolled). */
    @Nullable
    private static Outcome testOutcome;

    private Acrobat() {}

    /** The roll itself, for a level and the chances per level. */
    public static Outcome roll(int level, double perfectPerLevel, double rollPerLevel, RandomSource random) {
        if (level <= 0) return Outcome.NONE;
        if (random.nextDouble() < Math.min(1.0, perfectPerLevel * level)) return Outcome.PERFECT;
        if (random.nextDouble() < Math.min(1.0, rollPerLevel * level)) return Outcome.ROLL;
        return Outcome.NONE;
    }

    /** The share of a landing's damage taken for an outcome. */
    public static float damageShare(Outcome outcome) {
        return switch (outcome) {
            case PERFECT -> 0.0f;
            case ROLL -> SERVER.acrobatRollDamage.get().floatValue();
            case NONE -> 1.0f;
        };
    }

    /** The share this body's landing of the last few ticks came to (1 without one), for the sprain. */
    public static float recentShare(LivingEntity entity) {
        Held held = HELD.get(entity);
        if (held == null || entity.level().getGameTime() - held.tick > HOLD_TICKS) return 1.0f;
        return damageShare(held.outcome);
    }

    /** Whether this body's landing of the last few ticks was stuck perfectly (Rebound doesn't bounce one). */
    public static boolean landedPerfectly(LivingEntity entity) {
        Held held = HELD.get(entity);
        return held != null && held.outcome == Outcome.PERFECT && entity.level().getGameTime() - held.tick <= HOLD_TICKS;
    }

    public static int level(LivingEntity entity) {
        return EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.ACROBAT.get(), entity.getItemBySlot(EquipmentSlot.FEET));
    }

    /** Gametests only: draw from this random (null: each body's own). */
    public static void useRandomForTests(@Nullable RandomSource random) {
        testRandom = random;
    }

    /** Gametests only: every roll gives this (null: rolled). */
    public static void forceOutcomeForTests(@Nullable Outcome outcome) {
        testOutcome = outcome;
    }

    /** Gametests only: forget a body's held landing, so the next hurt rolls anew. */
    public static void forget(LivingEntity entity) {
        HELD.remove(entity);
    }

    private static boolean applies(LivingEntity entity, DamageSource source, float amount) {
        if (amount <= 0 || entity.level().isClientSide || !entity.isAlive()) return false;
        if (!source.is(DamageTypeTags.IS_FALL)) return false;
        if (entity instanceof Player player && (player.getAbilities().invulnerable || player.isSpectator())) return false;
        return !entity.isInvulnerableTo(source);
    }

    /** This landing's outcome: the one held from the last few ticks, or a new roll (then held). */
    private static Outcome outcome(LivingEntity entity, int level) {
        long now = entity.level().getGameTime();
        Held held = HELD.get(entity);
        if (held != null && now - held.tick <= HOLD_TICKS) return held.outcome;
        Outcome outcome = testOutcome != null ? testOutcome
                : roll(level, SERVER.acrobatPerfectLandingChance.get(), SERVER.acrobatRollChance.get(),
                        testRandom != null ? testRandom : entity.getRandom());
        HELD.put(entity, new Held(outcome, now));
        if (outcome != Outcome.NONE) feedback(entity, outcome);
        return outcome;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onAttack(LivingAttackEvent event) {
        LivingEntity entity = event.getEntity();
        if (!applies(entity, event.getSource(), event.getAmount())) return;
        int level = level(entity);
        if (level <= 0) return;
        if (outcome(entity, level) == Outcome.PERFECT) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity entity = event.getEntity();
        if (!applies(entity, event.getSource(), event.getAmount())) return;
        int level = level(entity);
        if (level <= 0) return;
        Outcome outcome = outcome(entity, level);
        if (outcome == Outcome.PERFECT) {
            event.setCanceled(true);
        } else if (outcome == Outcome.ROLL) {
            event.setAmount(event.getAmount() * damageShare(outcome));
        }
    }

    private static void feedback(LivingEntity entity, Outcome outcome) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        double x = entity.getX();
        double y = entity.getY() + 0.1;
        double z = entity.getZ();
        if (outcome == Outcome.PERFECT) {
            level.sendParticles(ParticleTypes.CLOUD, x, y, z, 12, 0.4, 0.05, 0.4, 0.04);
            level.sendParticles(ParticleTypes.CRIT, x, y + 0.6, z, 8, 0.3, 0.3, 0.3, 0.2);
            level.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.7f, 1.6f);
            level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 1.2f);
        } else {
            level.sendParticles(ParticleTypes.POOF, x, y, z, 6, 0.3, 0.05, 0.3, 0.02);
            level.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.5f, 1.2f);
        }
    }
}
