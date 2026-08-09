package net.camacraft.fullstop.server.physics.pressure;

import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.registry.ModAttributes;
import net.camacraft.fullstop.common.registry.ModEffects;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.minecraft.world.effect.MobEffects;
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

    /** Ticks of thin-air exposure until the drain reaches the altitude's full rate (5 s). */
    private static final int EXPOSURE_RAMP_TICKS = 100;

    /** Blocks above the start level at which the drain rate stops growing. */
    private static final double ALTITUDE_FULL_SEVERITY_RANGE = 150.0;

    /**
     * Hard cap on net air lost per tick (beyond the regen compensation) at the
     * default config rate. 300 air / 6 per tick = 2.5 s from full bubbles to
     * suffocation even at the ramp's top — so a teleport 5000 blocks up still
     * leaves a few seconds of grace instead of popping every bubble instantly
     * (the old formula scaled with raw altitude, unbounded).
     */
    private static final double MAX_EXTRA_AIR_LOSS_PER_TICK = 6.0;

    private static void handleAltitude(LivingEntity living) {
        if (!(living instanceof Player) && !FullStopConfig.SERVER.altitudePressureAffectsMobs.get()) return;

        int altitudeStart = FullStopConfig.SERVER.highAltitudeStartLevel.get();
        boolean inThinAir = living.getY() > altitudeStart && !living.isUnderWater();

        // Acclimation is to thin air what Water Breathing is to water: no drain,
        // and no exposure build-up while it holds.
        boolean acclimated = living.hasEffect(ModEffects.ACCLIMATION.get());
        boolean suffering = inThinAir && !acclimated;

        // The exposure clock (in the capability) ramps the drain in over several
        // seconds: entering thin air pops bubbles slowly at first — reduced
        // oxygen, not zero oxygen — then faster the longer/higher the exposure.
        FullStopCapability cap = FullStopCapability.grabCapability(living);
        int exposure = cap != null ? cap.tickThinAirExposure(suffering) : EXPOSURE_RAMP_TICKS;

        if (!suffering) return;

        double altitudeFactor = Math.min(1.0, (living.getY() - altitudeStart) / ALTITUDE_FULL_SEVERITY_RANGE);
        double rampFactor = Math.min(1.0, exposure / (double) EXPOSURE_RAMP_TICKS);

        // Lung Capacity attribute: the base for how long breath can be held.
        // 2.0 pops bubbles half as fast, 0.5 twice as fast.
        double lungCapacity = 1.0;
        var lungAttr = living.getAttribute(ModAttributes.LUNG_CAPACITY.get());
        if (lungAttr != null) lungCapacity = Math.max(lungAttr.getValue(), 0.1);

        // Config rate scales the cap around its 0.5 default so existing configs
        // keep meaning "higher is faster".
        double maxExtraLoss = MAX_EXTRA_AIR_LOSS_PER_TICK
                * (FullStopConfig.SERVER.highAltitudeAirLossRate.get() / 0.5);
        double rawExtraLoss = maxExtraLoss * altitudeFactor * rampFactor;

        // Rate 0 means OFF. The 1.0 floor below exists so any active drain beats
        // vanilla's regen; unconditional, it kept draining at the configured
        // minimum and made 0 impossible to actually configure.
        if (rawExtraLoss <= 0) return;

        double extraLoss = Math.max(1.0, rawExtraLoss) / lungCapacity;

        // Respiration slows the thin-air drain the same way it slows drowning —
        // by sparing THE EXTRA LOSS, never the regen compensation below.
        // Skipping the whole tick let vanilla's +4/tick regen push air back UP
        // on every spared tick: the bubble HUD flickered pop animations
        // non-stop, and with Respiration III (3 of 4 ticks spared) air ROSE on
        // net — the bubbles never went down at all. A spared tick now holds
        // air exactly level, matching how Respiration reads underwater.
        int extra = Math.max(1, (int) Math.round(extraLoss));
        if (respirationSpares(living)) extra = 0;

        // +4 compensates vanilla's out-of-water air regen (+4/tick, LivingEntity
        // baseTick) — without it any loss below 4 was silently regenerated and
        // the whole system was inert until far above the start level.
        int airLoss = 4 + extra;

        // Clamp to -20 so damage cadence doesn't depend on how far past the
        // threshold a single tick overshoots.
        int nextAir = Math.max(living.getAirSupply() - airLoss, -20);
        living.setAirSupply(nextAir);

        // Spared ticks (extra == 0) never damage, like vanilla drowning ticks
        // spared by Respiration — the -4 above is transient bookkeeping that
        // vanilla's regen returns later this tick.
        if (extra > 0 && nextAir <= -20) {
            living.setAirSupply(0);
            living.hurt(FullStopDamageSources.atmosphere(living), 2.0F);
        }
    }

    private static void handleDepth(LivingEntity living) {
        if (!living.isUnderWater()) return;

        // The extra deep-water drain must honor the same protections as vanilla
        // drowning — Water Breathing, Conduit Power, and Respiration previously
        // bypassed it entirely because setAirSupply is written directly.
        if (living.hasEffect(MobEffects.WATER_BREATHING) || living.hasEffect(MobEffects.CONDUIT_POWER)) return;
        if (respirationSpares(living)) return;

        int deepWaterStart = FullStopConfig.SERVER.deepWaterStartLevel.get();
        if (living.getY() < deepWaterStart) {
            // Config-named "multiplier" but applied as extra air lost per tick on
            // top of vanilla drowning below the start level.
            int extraAirLossPerTick = (int) (double) FullStopConfig.SERVER.deepWaterAirLossMultiplier.get();
            living.setAirSupply(Math.max(living.getAirSupply() - extraAirLossPerTick, -20));
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

    /** Vanilla-style Respiration roll: level N spares the air loss N out of N+1 ticks. */
    private static boolean respirationSpares(LivingEntity living) {
        int respiration = net.minecraft.world.item.enchantment.EnchantmentHelper.getRespiration(living);
        return respiration > 0 && living.getRandom().nextInt(respiration + 1) > 0;
    }
}
