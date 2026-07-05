package net.camacraft.fullstop.server.physics.pressure;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Atmospheric (high altitude) and underwater (depth) pressure simulation.
 * Unrelated to kinetic physics, so it lives in its own handler.
 */
public class PressureHandler {

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity living = event.getEntity();

        if (living.level().isClientSide) return;
        if (!FullStopConfig.SERVER.enablePressureSimulation.get()) return;
        if (living instanceof Player player && (player.isCreative() || player.isSpectator())) return;
        if (living.canBreatheUnderwater()) return;
        if (living.getMobType() == MobType.UNDEAD) return; // the undead don't breathe

        handleAltitude(living);
        handleDepth(living);
    }

    private static void handleAltitude(LivingEntity living) {
        if (!(living instanceof Player) && !FullStopConfig.SERVER.altitudePressureAffectsMobs.get()) return;

        int altitudeStart = FullStopConfig.SERVER.highAltitudeStartLevel.get();
        if (living.getY() <= altitudeStart || living.isUnderWater()) return;

        int y = (int) living.getY();
        double rate = FullStopConfig.SERVER.highAltitudeAirLossRate.get();
        int airLoss = (int) (1 + (y - altitudeStart) * rate / 10);

        // Clamp to -20 so damage cadence doesn't depend on how far past the
        // threshold a single tick overshoots.
        int nextAir = Math.max(living.getAirSupply() - airLoss, -20);
        living.setAirSupply(nextAir);

        if (nextAir <= -20) {
            living.setAirSupply(0);
            living.hurt(FullStopDamageSources.atmosphere(living), 2.0F);
        }
    }

    private static void handleDepth(LivingEntity living) {
        if (!living.isUnderWater()) return;

        int deepWaterStart = FullStopConfig.SERVER.deepWaterStartLevel.get();
        if (living.getY() < deepWaterStart) {
            double multiplier = FullStopConfig.SERVER.deepWaterAirLossMultiplier.get();
            int airLoss = (int) multiplier;
            living.setAirSupply(Math.max(living.getAirSupply() - airLoss, -20));
        }

        int damageDepth = FullStopConfig.SERVER.pressureDamageStartDepth.get();
        if (living.getY() < damageDepth) {
            int tickRate = FullStopConfig.SERVER.pressureDamageTickRate.get();
            if (living.tickCount % tickRate == 0) {
                float damage = FullStopConfig.SERVER.pressureDamageAmount.get().floatValue();
                living.hurt(FullStopDamageSources.pressure(living), damage);
            }
        }
    }
}
