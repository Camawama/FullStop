package net.camacraft.fullstop.advancement;

import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The advancements that go by what hurt a player: looked at after armour and resistance have
 * taken their share, so "Ouch, that hurts!" and "Hit by a ship" mean damage that landed. The
 * static {@link #damaged} and {@link #died} are the whole logic, so the game tests can call them
 * for a fake player (which vanilla never hurts).
 */
public final class AdvancementEvents {
    private AdvancementEvents() {
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getAmount() > 0.0f) {
            damaged(player, event.getSource(), event.getAmount());
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            died(player, event.getSource());
        }
    }

    /** {@code amount} of damage from {@code source} is about to land on the player. */
    public static void damaged(ServerPlayer player, DamageSource source, float amount) {
        if (source instanceof FullStopDamageSources.KineticSource
                || source.is(FullStopDamageSources.KINETIC) || source.is(FullStopDamageSources.KINETIC_FALL)) {
            FullStopTriggers.OUCH.trigger(player);
        }
        if (source.is(FullStopDamageSources.SHIP) && player.getHealth() > amount) {
            FullStopTriggers.HIT_BY_A_SHIP.trigger(player);
        }
    }

    /** The player died to {@code source}. */
    public static void died(ServerPlayer player, DamageSource source) {
        if (source.is(FullStopDamageSources.SHIP)) {
            FullStopTriggers.FLATTENED.trigger(player);
        }
    }
}
