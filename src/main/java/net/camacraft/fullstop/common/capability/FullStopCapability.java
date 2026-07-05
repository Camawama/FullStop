package net.camacraft.fullstop.common.capability;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.physics.rules.GForceThresholds;
import net.camacraft.fullstop.common.util.MathUtils;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Per-entity velocity/impact state.
 *
 * <h3>Unit convention</h3>
 * Everything suffixed {@code Mps} is in meters per second (= blocks/second).
 * "Native" values are Minecraft's blocks-per-tick. Conversion factor is 20
 * (native → m/s: {@code scale(20)}; m/s → native: {@code scale(0.05)}).
 *
 * <h3>Source of truth</h3>
 * The velocity for a tick is the measured position delta. For players, the
 * client-reported delta may <em>refine</em> that measurement (it captures
 * intent the server position hasn't integrated yet) but is never allowed to
 * exceed it — see {@link #tickVelocity}.
 */
public class FullStopCapability {

    public static final ResourceLocation DELTA_VELOCITY = new ResourceLocation(FullStop.MODID, "delta_velocity");

    private final Entity entity;

    @NotNull
    private Vec3 prevVelocityMps = Vec3.ZERO;
    @NotNull
    private Vec3 velocityMps = Vec3.ZERO;
    private Vec3 clientVelocityMps = null;
    private Vec3 currentPosition = Vec3.ZERO;
    private Vec3 previousPosition = Vec3.ZERO;
    private Vec3 acceleration = Vec3.ZERO;

    // Stopping force = speed lost this tick (m/s), decomposed for per-axis damage thresholds.
    private double decelerationForce = 0.0;
    private double decelerationForceHorizontal = 0.0;
    private double decelerationForceVertical = 0.0;

    private double avgAccel = 0.0;
    private double rawAvgAccel = 0.0; // Unaffected by clarity/vertigo potions

    private boolean isDamageImmune = false;
    private boolean hasTeleported = false;
    private boolean hasDismounted = false;
    private boolean firstTick = true;
    private int teleportCooldown = 0;
    private int dismountCooldown = 0;
    private int soundCooldown = 0;
    private int sonicBoomCooldown = 0;
    private int waterSkipCooldown = 0;
    private long lastTick = -1;

    private long damageCooldownUntilTick = Long.MIN_VALUE;

    private double targetAngle = Double.NaN;
    private double targetPitch = Double.NaN;

    private boolean justBounced = false;

    public FullStopCapability(Entity entity) {
        this.entity = entity;
    }

    public void tick(Entity entity) {
        if (lastTick != -1 && entity.tickCount > lastTick + 1) {
            resetVelocity();
            currentPosition = entity.position();
            previousPosition = currentPosition;
        }

        tickVelocity(entity);
        tickSpeed();
        tickImmunity();
        tickRiding();

        if (soundCooldown > 0) {
            soundCooldown--;
        }

        if (sonicBoomCooldown > 0) {
            sonicBoomCooldown--;
        }

        if (waterSkipCooldown > 0) {
            waterSkipCooldown--;
        }

        if (Double.isNaN(avgAccel)) avgAccel = 0;
        if (Double.isNaN(rawAvgAccel)) rawAvgAccel = 0;

        if (entity instanceof LivingEntity living) {
            tickGForce(living);
        }
    }

    /** Smooths the felt acceleration (g-force) that drives blackout/vignette/audio effects. */
    private void tickGForce(LivingEntity living) {
        double gravity = 0.08;
        if (!living.isNoGravity()) {
            var gravityAttr = living.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
            if (gravityAttr != null) {
                gravity = gravityAttr.getValue();
            }
        } else {
            gravity = 0.0;
        }

        gravity = Math.abs(gravity);

        // acceleration is m/s per tick; gravity attr is blocks/tick², so ×20 puts both in the same units.
        Vec3 gravityVector = new Vec3(0, -gravity * 20, 0);
        Vec3 gForceVec = acceleration.subtract(gravityVector);

        if (entity.onGround() && gForceVec.y > 0) {
            gForceVec = new Vec3(gForceVec.x, 0, gForceVec.z);
        }

        if (velocityMps.y < 0 && gForceVec.y > 0) {
            gForceVec = new Vec3(gForceVec.x, 0, gForceVec.z);
        }

        double gForceMagnitude = gForceVec.length();

        if (gForceMagnitude < 0.001 && avgAccel < 0.001) {
            avgAccel = 0.0;
            rawAvgAccel = 0.0;
        } else {
            double clampedInput = Math.min(gForceMagnitude, 20.0);

            if (justBounced) {
                clampedInput = 0;
                justBounced = false;
            }

            rawAvgAccel = (rawAvgAccel * 19 + clampedInput) / 20;

            double smoothingFactor = 20.0;
            int netLevel = GForceThresholds.netEffectLevel(living);

            if (netLevel > 0) {
                smoothingFactor = Math.max(5.0, smoothingFactor * Math.pow(0.7, netLevel));
            } else if (netLevel < 0) {
                smoothingFactor = smoothingFactor * Math.pow(1.5, -netLevel);
            }

            avgAccel = (avgAccel * (smoothingFactor - 1) + clampedInput) / smoothingFactor;
        }
    }

    private void tickVelocity(Entity entity) {
        if (firstTick) {
            currentPosition = entity.position();
            previousPosition = currentPosition.subtract(entity.getDeltaMovement());
            firstTick = false;
        } else {
            previousPosition = currentPosition;
        }

        if (hasTeleported) {
            currentPosition = entity.position();
            previousPosition = currentPosition;
            velocityMps = Vec3.ZERO;
            prevVelocityMps = Vec3.ZERO;
            return;
        }

        prevVelocityMps = velocityMps;

        currentPosition = entity.position();

        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20);

        if (entity instanceof Player && clientVelocityMps != null) {
            double actualSpeedSqr = actualVelocity.lengthSqr();
            double clientSpeedSqr = clientVelocityMps.lengthSqr();

            // The client value may refine the measurement, never inflate it: when the entity
            // is colliding or the client claims to be faster than it actually moved, trust
            // the measured position delta.
            if (entity.horizontalCollision || entity.verticalCollision || (clientSpeedSqr > actualSpeedSqr + 0.1)) {
                velocityMps = actualVelocity;
            } else {
                velocityMps = clientVelocityMps;
            }
            clientVelocityMps = null;
        } else {
            velocityMps = actualVelocity;
        }

        if (entity instanceof LivingEntity living) {
            double gravity = Objects.requireNonNull(living.getAttribute(ForgeMod.ENTITY_GRAVITY.get())).getValue();

            // Standing still on the ground still "falls" by one gravity step each tick;
            // treat that as zero vertical velocity.
            if (velocityMps.y >= gravity * -20 && velocityMps.y < 0 && entity.onGround()) {
                velocityMps = new Vec3(velocityMps.x, 0, velocityMps.z);
            }
        }
    }

    private void tickSpeed() {
        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20);

        double stoppingForceX = calculateStoppingForceComponent(actualVelocity.x, prevVelocityMps.x);
        double stoppingForceY = calculateStoppingForceComponent(actualVelocity.y, prevVelocityMps.y);
        double stoppingForceZ = calculateStoppingForceComponent(actualVelocity.z, prevVelocityMps.z);

        decelerationForceHorizontal = Math.sqrt(stoppingForceX * stoppingForceX + stoppingForceZ * stoppingForceZ);
        decelerationForceVertical = stoppingForceY;
        decelerationForce = Math.sqrt(
                stoppingForceX * stoppingForceX +
                        stoppingForceY * stoppingForceY +
                        stoppingForceZ * stoppingForceZ
        );

        acceleration = velocityMps.subtract(prevVelocityMps);
    }

    private double calculateStoppingForceComponent(double current, double old) {
        boolean slowedDown = Math.abs(current) < Math.abs(old);
        boolean bounced = isBounce(current, old);

        if (slowedDown || bounced) {
            return Math.abs(old - current);
        } else {
            return 0.0;
        }
    }

    private static boolean isBounce(double v1, double v2) {
        if (v1 == 0.0 || v2 == 0.0) {
            return false;
        }

        return Math.signum(v1) != Math.signum(v2);
    }

    public double rotationCorrection(double delta) {
        if (Double.isNaN(targetAngle)) {
            return 0;
        }

        double correction = MathUtils.angleWrap(targetAngle - entity.getYRot());

        if (Math.abs(correction) < 0.5) {
            targetAngle = Double.NaN;
        }

        return correction * 0.005 * delta;
    }

    public double pitchCorrection(double delta) {
        if (Double.isNaN(targetPitch)) {
            return 0;
        }

        double correction = MathUtils.angleWrap(targetPitch - entity.getXRot());

        if (Math.abs(correction) < 0.5) {
            targetPitch = Double.NaN;
        }

        return correction * 0.005 * delta;
    }

    private void tickImmunity() {
        if (hasTeleported) {
            teleportCooldown = 5;
            hasTeleported = false;
        }

        if (teleportCooldown > 0) {
            teleportCooldown--;
            isDamageImmune = true;
        } else {
            isDamageImmune = false;
        }
    }

    private void tickRiding() {
        if (hasDismounted) {
            dismountCooldown = 40;
            hasDismounted = false;
        }

        if (dismountCooldown > 0) {
            dismountCooldown--;
        }
    }

    /** Current velocity in blocks/tick. */
    public Vec3 getCurrentNativeVelocity() {
        return velocityMps.scale(0.05);
    }

    /** Stores the client-reported velocity (blocks/tick) for the next tick's refinement. */
    public void setCurrentNativeVelocity(Vec3 nativeVelocity) {
        this.clientVelocityMps = nativeVelocity.scale(20);
    }

    /** Previous tick's velocity in blocks/tick. */
    public Vec3 getPreviousNativeVelocity() {
        return prevVelocityMps.scale(0.05);
    }

    /** Current velocity in m/s. */
    public Vec3 getCurrentScaledVelocity() {
        return velocityMps;
    }

    /** Previous tick's velocity in m/s. */
    public Vec3 getPreviousScaledVelocity() {
        return prevVelocityMps;
    }

    /** Total speed lost this tick, m/s. */
    public double getStoppingForce() {
        return decelerationForce;
    }

    /** Horizontal speed lost this tick, m/s. */
    public double getHorizontalStoppingForce() {
        return decelerationForceHorizontal;
    }

    /** Vertical speed lost this tick, m/s. */
    public double getVerticalStoppingForce() {
        return decelerationForceVertical;
    }

    /** Velocity change this tick (m/s per tick). */
    public Vec3 getAcceleration() {
        return acceleration;
    }

    public double getRunningAverageDelta() {
        return avgAccel;
    }

    public double getRawRunningAverageDelta() {
        return rawAvgAccel;
    }

    public boolean getIsDamageImmune() {
        return isDamageImmune;
    }

    public void setHasTeleported(boolean value) {
        this.hasTeleported = value;
    }

    public void justDismounted() {
        setCurrentNativeVelocity(Vec3.ZERO);
        this.hasDismounted = true;
    }

    public double getDismountCooldown() {
        return dismountCooldown;
    }

    public long getLastTick() {
        return lastTick;
    }

    public void setLastTick(long tick) {
        this.lastTick = tick;
    }

    public void setSoundCooldown(int ticks) {
        this.soundCooldown = ticks;
    }

    public boolean canPlaySound() {
        return soundCooldown <= 0;
    }

    public void setSonicBoomCooldown(int ticks) {
        this.sonicBoomCooldown = ticks;
    }

    public boolean canSonicBoom() {
        return sonicBoomCooldown <= 0;
    }

    public int getWaterSkipCooldown() {
        return waterSkipCooldown;
    }

    public void setWaterSkipCooldown(int ticks) {
        this.waterSkipCooldown = ticks;
    }

    /**
     * Kinetic-damage cooldown. Time-based rather than per-block so sliding along a
     * wall (a new BlockPos every tick) can't re-trigger damage each tick.
     */
    public boolean isDamageOnCooldown(long currentTick) {
        return currentTick < damageCooldownUntilTick;
    }

    public void markDamageApplied(long currentTick, int cooldownTicks) {
        this.damageCooldownUntilTick = currentTick + cooldownTicks;
    }

    public void setTargetAngle(double targetAngle) {
        this.targetAngle = targetAngle;
    }

    public void setTargetPitch(double targetPitch) {
        this.targetPitch = targetPitch;
    }

    public boolean isMostlyDownward() {
        Vec3 v = prevVelocityMps;
        return (-v.y) > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    public boolean isMostlyUpward() {
        Vec3 v = prevVelocityMps;
        return v.y > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    public boolean isMostlyHorizontal() {
        Vec3 v = prevVelocityMps;
        return Math.sqrt(v.x * v.x + v.z * v.z) > Math.abs(v.y);
    }

    public void resetVelocity() {
        this.velocityMps = Vec3.ZERO;
        this.prevVelocityMps = Vec3.ZERO;
        this.clientVelocityMps = null;
    }

    public void setJustBounced(boolean justBounced) {
        this.justBounced = justBounced;
    }

    public static @Nullable FullStopCapability grabCapability(Entity entity) {
        return entity.getCapability(Provider.DELTAV_CAP).orElse(null);
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesEvent(AttachCapabilitiesEvent<Entity> event) {
        if ((event.getObject().getCapability(Provider.DELTAV_CAP).isPresent())) return;

        event.addCapability(DELTA_VELOCITY, new Provider(event.getObject()));
    }

    public static class Provider implements ICapabilityProvider {
        public static final Capability<FullStopCapability> DELTAV_CAP = CapabilityManager.get(new CapabilityToken<>() {});
        private final Entity entity;

        private FullStopCapability capability = null;
        private final LazyOptional<FullStopCapability> lazyHandler = LazyOptional.of(this::createCapability);

        public Provider(Entity entity) {
            this.entity = entity;
        }

        private FullStopCapability createCapability() {
            if (this.capability == null) {
                this.capability = new FullStopCapability(entity);
            }
            return this.capability;
        }

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            if (cap == DELTAV_CAP) {
                return lazyHandler.cast();
            }
            return LazyOptional.empty();
        }
    }
}
