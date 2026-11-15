package net.camacraft.fullstop.server.physics.pressure;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.registry.ModAttributes;
import net.camacraft.fullstop.common.registry.ModEffects;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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
        // Pressure is ENVIRONMENT physics: not for blacklisted entities, nor while
        // another mod owns it (MKW racers on tracks above the thin-air line).
        if (PhysicsClaims.cachedHandsOff(living, PhysicsAspect.ENVIRONMENT)) return;

        boolean vacuum = inVacuum(living);
        handleAltitude(living, vacuum);
        handleDepth(living);
        if (vacuum) handleVacuumCold(living);
    }

    /**
     * Whether {@code living} has nothing to breathe here (Camafy's {@code Air.breathable} at its eyes: VS Genesis's space,
     * Ad Astra's orbits and airless worlds, outside an oxygen bubble) and isn't sealed in a whole space suit.
     */
    private static boolean inVacuum(LivingEntity living) {
        if (!FullStopConfig.SERVER.vacuumSuffocation.get() && !FullStopConfig.SERVER.vacuumFreezing.get()) return false;
        if (net.camacraft.camafy.compat.space.SpaceSuits.sealed(living)) return false;
        return !net.camacraft.camafy.compat.space.Air.breathable(living.level(), net.minecraft.core.BlockPos.containing(living.getEyePosition()));
    }

    /**
     * Vacuum's cold, as powder snow's: three frozen ticks a tick against vanilla's thaw of two, so the frost creeps in over
     * {@code getTicksRequiredToFreeze} ticks (7 s) and then vanilla's freezing hurts (every 2 s). Leather armour
     * ({@code canFreeze}) keeps it off, as in powder snow.
     */
    private static void handleVacuumCold(LivingEntity living) {
        if (!FullStopConfig.SERVER.vacuumFreezing.get() || !living.canFreeze()) return;
        if (!(living instanceof Player) && !FullStopConfig.SERVER.vacuumAffectsMobs.get()) return;
        int required = living.getTicksRequiredToFreeze();
        living.setTicksFrozen(Math.min(required + 2, living.getTicksFrozen() + 3));
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

    private static void handleAltitude(LivingEntity living, boolean inVacuum) {
        // vacuum: nothing to breathe at any height (vacuumSuffocation), the thin-air drain at its top rate
        boolean vacuum = inVacuum && FullStopConfig.SERVER.vacuumSuffocation.get();
        if (!(living instanceof Player) && !(vacuum ? FullStopConfig.SERVER.vacuumAffectsMobs.get()
                : FullStopConfig.SERVER.altitudePressureAffectsMobs.get())) return;

        int altitudeStart = FullStopConfig.SERVER.highAltitudeStartLevel.get();
        // Only where the sky thins with height (highAltitudeDimensions: the overworld). VS Genesis's space, the
        // Nether, the End and modded worlds have their own air, whatever their Y (space has none: the vacuum above).
        boolean inThinAir = vacuum || living.getY() > altitudeStart && !living.isUnderWater() && thinAirDimension(living.level());

        // Acclimation is to thin air what Water Breathing is to water: no drain,
        // and no exposure build-up while it holds. Not in vacuum: there is nothing to acclimate to.
        boolean acclimated = !vacuum && living.hasEffect(ModEffects.ACCLIMATION.get());
        boolean suffering = inThinAir && !acclimated;

        // The exposure clock (in the capability) ramps the drain in over several
        // seconds: entering thin air pops bubbles slowly at first — reduced
        // oxygen, not zero oxygen — then faster the longer/higher the exposure.
        FullStopCapability cap = FullStopCapability.grabCapability(living);
        int exposure = cap != null ? cap.tickThinAirExposure(suffering) : EXPOSURE_RAMP_TICKS;

        if (!suffering) return;

        double altitudeFactor = vacuum ? 1.0 : Math.min(1.0, (living.getY() - altitudeStart) / ALTITUDE_FULL_SEVERITY_RANGE);
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

    /**
     * Whether {@code highAltitudeDimensions} names {@code level} (Camafy's {@code Dimensions}: ids, wildcards, types,
     * kinds): its air thins above the start level.
     */
    public static boolean thinAirDimension(Level level) {
        return net.camacraft.camafy.world.Dimensions.matches(level, FullStopConfig.SERVER.highAltitudeDimensions.get());
    }

    /** The sea level the old Y settings are read against. */
    private static final int LEGACY_SEA_LEVEL = 63;
    /** Gravity counts up to this many times vanilla's toward the weight of the liquid overhead. */
    private static final double MAX_GRAVITY_FACTOR = 4.0;

    /**
     * Blocks of water overhead from which a body loses air faster: {@code deepWaterStartDepth}, or the old
     * {@code deepWaterStartLevel} (a Y level) taken under a sea at Y 63 while only that one was ever changed.
     */
    public static int deepWaterDepth() {
        int depth = FullStopConfig.SERVER.deepWaterStartDepth.get();
        int legacy = FullStopConfig.SERVER.deepWaterStartLevel.get();
        if (depth == FullStopConfig.SERVER.deepWaterStartDepth.getDefault() && legacy != FullStopConfig.SERVER.deepWaterStartLevel.getDefault()) {
            return Math.max(1, LEGACY_SEA_LEVEL - legacy);
        }
        return depth;
    }

    /** Blocks of water overhead from which the pressure hurts: {@code pressureDamageDepth}, or the old Y setting as above. */
    public static int pressureDamageDepth() {
        int depth = FullStopConfig.SERVER.pressureDamageDepth.get();
        int legacy = FullStopConfig.SERVER.pressureDamageStartDepth.get();
        if (depth == FullStopConfig.SERVER.pressureDamageDepth.getDefault() && legacy != FullStopConfig.SERVER.pressureDamageStartDepth.getDefault()) {
            return Math.max(1, LEGACY_SEA_LEVEL - legacy);
        }
        return depth;
    }

    /**
     * The water overhead, in blocks of water under vanilla's gravity: the depth of the liquid over {@code living}'s
     * eyes ({@link LiquidDepth}) times the liquid's density and the gravity there ({@code pressureByWeight}), or the
     * depth alone. 0 where its eyes are in no liquid or nothing pulls.
     */
    public static double waterOverhead(LivingEntity living) {
        if (living.getEyeInFluidType().isAir()) return 0.0;
        boolean byWeight = FullStopConfig.SERVER.pressureByWeight.get();
        // Water pressure is the weight of the water overhead: none where nothing pulls (VS
        // Genesis's space, an Ad Astra orbit).
        double gravity = Gravity.ambientStrength(living);
        if (gravity < Gravity.WEIGHTLESS_BELOW) return 0.0;
        gravity = byWeight ? Math.min(gravity, MAX_GRAVITY_FACTOR) : 1.0;
        int deepest = Math.max(deepWaterDepth(), pressureDamageDepth());
        int limit = (int) Math.min(LiquidDepth.MAX_LIMIT, Math.ceil(deepest / gravity) + 8);
        LiquidDepth.Reading reading = LiquidDepth.of(living, limit);
        if (!reading.submerged()) return 0.0;
        return reading.depth() * gravity * (byWeight ? reading.relativeDensity() : 1.0);
    }

    private static void handleDepth(LivingEntity living) {
        // only in a liquid the body can drown in (water for all but water breathers, a mod's liquid by its own rule;
        // not lava)
        net.minecraftforge.fluids.FluidType eyes = living.getEyeInFluidType();
        if (eyes.isAir() || !living.canDrownInFluidType(eyes)) return;

        // The extra deep-water drain must honor the same protections as vanilla
        // drowning — Water Breathing, Conduit Power, and Respiration previously
        // bypassed it entirely because setAirSupply is written directly.
        if (living.hasEffect(MobEffects.WATER_BREATHING) || living.hasEffect(MobEffects.CONDUIT_POWER)) return;
        if (respirationSpares(living)) return;

        // How deep is the liquid over the body, not where in the world the body is: a pool three blocks deep at the
        // bottom of a flat world presses no more than one on a mountain.
        double overhead = waterOverhead(living);
        if (overhead <= 0.0) return;

        if (overhead >= deepWaterDepth()) {
            // Config-named "multiplier" but applied as extra air lost per tick on
            // top of vanilla drowning from the start depth down.
            int extraAirLossPerTick = (int) (double) FullStopConfig.SERVER.deepWaterAirLossMultiplier.get();
            // Never below -19: vanilla's own step this tick takes one more and drowns at exactly -20; an air supply
            // set to -20 here went to -21 there and the body never drowned at all.
            int air = living.getAirSupply();
            if (air > -19) living.setAirSupply(Math.max(air - extraAirLossPerTick, -19));
        }

        if (overhead >= pressureDamageDepth()) {
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
