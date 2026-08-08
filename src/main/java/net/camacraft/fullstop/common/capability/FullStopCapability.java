package net.camacraft.fullstop.common.capability;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.physics.rules.GForceThresholds;
import net.camacraft.fullstop.common.util.MathUtils;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
    private Vec3 prevPrevVelocityMps = Vec3.ZERO;
    @NotNull
    private Vec3 velocityMps = Vec3.ZERO;
    private Vec3 clientVelocityMps = null;
    private Vec3 currentPosition = Vec3.ZERO;
    private Vec3 previousPosition = Vec3.ZERO;
    private Vec3 acceleration = Vec3.ZERO;
    // World-space motion (m/s) a physics mod (Valkyrien Skies) imposed on the
    // entity this tick; removed from the measured velocity so ship rides don't
    // read as the entity's own kinetic energy. ZERO in a plain world.
    private Vec3 frameMotionMps = Vec3.ZERO;

    // Stopping force = speed lost this tick (m/s), decomposed for per-axis damage thresholds.
    private double decelerationForce = 0.0;
    private double decelerationForceHorizontal = 0.0;
    private double decelerationForceVertical = 0.0;

    private double avgAccel = 0.0;
    private double rawAvgAccel = 0.0; // Unaffected by clarity/vertigo potions

    private boolean isDamageImmune = false;
    private boolean hasTeleported = false;
    private boolean wasSleeping = false;
    private boolean hasDismounted = false;
    private boolean firstTick = true;
    private int teleportCooldown = 0;
    private int dismountCooldown = 0;
    private int soundCooldown = 0;
    private int sonicBoomCooldown = 0;
    private int waterSkipCooldown = 0;
    private int bounceCooldown = 0;
    private int sloshCooldown = 0;

    // A fast impact's deceleration lands one tick AFTER the collision flag is set
    // (on the contact tick the entity travels the whole sub-block gap, so little
    // speed is lost yet). These keep the "we hit something" evidence alive for a
    // couple ticks so the late stopping-force tick still counts as an impact —
    // otherwise fast wall/ceiling hits (esp. elytra, where the flag clears as the
    // flyer deflects away) silently deal no damage.
    private static final int HIT_GRACE_TICKS = 2;
    private int horizontalHitGrace = 0;
    private int verticalHitGrace = 0;

    // Collision flags as the CLIENT measured them, reported via PlayerDeltaPacket.
    // A server-side player's own flags are nearly always false: the move packets
    // arrive already clipped to the wall, so the server-side move() never detects
    // the contact (onGround comes from the packet, which is why only vertical
    // damage ever worked). These stand in as contact evidence for players.
    private boolean clientHorizontalCollision = false;
    private boolean clientVerticalCollision = false;

    // Felt-acceleration below this (m/s per tick, on top of the 1g baseline) is
    // treated as ordinary movement noise and ignored, so the constant small
    // accelerations of steady elytra flight / takeoff never accumulate into the
    // blackout. Only genuine maneuvers — loops, hard turns, high-speed reversals —
    // clear it. Raise it to make the effects even less twitchy; lower it toward 0
    // to restore the old hair-trigger behavior.
    private static final double GFORCE_NOISE_FLOOR = 2.0;
    private double lastMeasuredSpeed = 0.0;
    private long lastTick = -1;

    private long damageCooldownUntilTick = Long.MIN_VALUE;
    private double recentAppliedDamage = 0.0;

    // How long the entity has been in thin air (ticks). Drives the ramp-up of
    // high-altitude air loss so a teleport straight to y=5000 still pops the
    // bubbles gradually instead of instantly (PressureHandler).
    private int thinAirExposureTicks = 0;

    private double targetAngle = Double.NaN;
    private double targetPitch = Double.NaN;

    private boolean justBounced = false;

    // Mob.isNoAi() is a SynchedEntityData read behind a read-write lock, made for
    // every mob every tick just to filter them out. NoAI flips rarely (commands,
    // map setup), so poll it once a second instead.
    private static final int NO_AI_REFRESH_TICKS = 20;
    private boolean cachedNoAi = false;
    private int noAiRefreshIn = 0;

    public FullStopCapability(Entity entity) {
        this.entity = entity;
    }

    public void tick(Entity entity) {
        if (lastTick != -1 && entity.tickCount > lastTick + 1) {
            resetVelocity();
            currentPosition = entity.position();
            previousPosition = currentPosition;
        }

        // Getting into or out of a bed repositions the entity (startSleeping /
        // stopSleeping call setPos directly — no teleport event fires), and the
        // jump is far too small for tickVelocity's 60 m/s discontinuity guard.
        // Without this, the wake-up tick reads as a violent move followed by a
        // dead stop and the resulting "impact" can kill sleepers (villagers at
        // sunrise). Route it through the teleport path instead.
        if (entity instanceof LivingEntity living) {
            boolean sleeping = living.isSleeping();
            if (sleeping || wasSleeping) {
                hasTeleported = true;
            }
            wasSleeping = sleeping;
        }

        // Boarding or leaving a Valkyrien Skies ship snaps the entity's world
        // position; swallow that jump like a teleport instead of reading it as a
        // violent move (which would deal a phantom impact on the boarding tick).
        if (ShipCompat.justChangedShip(entity)) {
            hasTeleported = true;
        }

        tickVelocity(entity);
        tickSpeed();
        tickImmunity();
        tickRiding();
        tickHitGrace(entity);

        if (soundCooldown > 0) {
            soundCooldown--;
        }

        if (sonicBoomCooldown > 0) {
            sonicBoomCooldown--;
        }

        if (waterSkipCooldown > 0) {
            waterSkipCooldown--;
        }

        if (bounceCooldown > 0) {
            bounceCooldown--;
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
        double baseline = gravity * 20;
        Vec3 gravityVector = new Vec3(0, -baseline, 0);

        // Proper (felt) acceleration relative to freefall, minus the 1g baseline
        // everyone feels at rest. Continuous by construction: the old hard cutoffs
        // (zero the Y term when grounded / while falling) flipped on and off from
        // tick to tick at speed, making the blackout and audio muffle flash.
        double gForceMagnitude = Math.max(acceleration.subtract(gravityVector).length() - baseline - GFORCE_NOISE_FLOOR, 0.0);

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
            prevPrevVelocityMps = Vec3.ZERO;
            frameMotionMps = Vec3.ZERO;
            return;
        }

        prevPrevVelocityMps = prevVelocityMps;
        prevVelocityMps = velocityMps;

        currentPosition = entity.position();

        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20);

        // Remove world-frame motion a physics mod imposed by carrying the entity
        // (a Valkyrien Skies ship): the raw position delta on a moving/rotating
        // ship is dominated by the ship, not the entity's own movement. Done
        // before the discontinuity guard so smooth ship motion never trips it.
        frameMotionMps = ShipCompat.shipDragVelocityMps(entity);
        actualVelocity = actualVelocity.subtract(frameMotionMps);

        // Position discontinuity (respawn, /tp, mod teleports that fire no event):
        // no physical process triples an entity's speed past 60 m/s in a single
        // tick. Compared against the last MEASURED delta, not the tracked velocity,
        // so a genuinely fast entity is only suppressed for the one flagged tick.
        double measuredSpeed = actualVelocity.length();
        if (measuredSpeed > lastMeasuredSpeed * 3 + 60.0) {
            lastMeasuredSpeed = measuredSpeed;
            previousPosition = currentPosition;
            velocityMps = Vec3.ZERO;
            prevVelocityMps = Vec3.ZERO;
            prevPrevVelocityMps = Vec3.ZERO;
            frameMotionMps = Vec3.ZERO;
            clientVelocityMps = null;
            return;
        }
        lastMeasuredSpeed = measuredSpeed;

        // Player-controlled vehicles consume the driver's report too — the packet
        // stored it on the vehicle's capability, but only players ever read it,
        // so the whole vehicle branch was dead.
        boolean clientControlled = entity instanceof Player || entity.getControllingPassenger() instanceof Player;
        if (clientControlled && clientVelocityMps != null) {
            // The client value may REFINE the measurement, never replace it:
            // accept it only when it is close to the measured delta in BOTH
            // directions. The old inflation-only clamp let a cheat client
            // under-report constantly, zeroing its own velocity history — and
            // with it all stopping force (= kinetic-damage immunity).
            double toleranceSqr = Math.max(1.0, actualVelocity.lengthSqr() * 0.25);
            boolean plausible = clientVelocityMps.subtract(actualVelocity).lengthSqr() <= toleranceSqr;
            if (!entity.horizontalCollision && !entity.verticalCollision && plausible) {
                velocityMps = clientVelocityMps;
            } else {
                velocityMps = actualVelocity;
            }
            clientVelocityMps = null;
        } else {
            velocityMps = actualVelocity;
        }

        if (entity instanceof LivingEntity living && entity.onGround()) {
            // Null-safe: an exotic modded living entity without the Forge gravity
            // attribute must degrade to the vanilla 0.08, not crash the tick loop.
            var gravityAttr = living.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
            double gravityStep = (gravityAttr != null ? gravityAttr.getValue() : 0.08) * 20;
            // How far vanilla's auto-step can lift the entity in a single tick.
            double stepUp = entity.maxUpStep() * 20;
            double vy = velocityMps.y;

            // Vertical motion while grounded is a movement artifact, not a real
            // launch/fall, and must not register as felt g-force or as an impact:
            //  - a small downward drift is just gravity pressing into the floor;
            //  - a positive spike within step height is auto-step climbing a
            //    slab/stair/ledge (a real jump or bounce leaves the ground, so
            //    onGround is already false and this never fires for those).
            // Left in, the step spike ramps the blackout on stairs and — paired
            // with the impact path — reads as a high-speed fall when you leave the
            // ledge you were auto-stepping against.
            boolean groundedDrift = vy < 0 && vy >= -gravityStep;
            boolean autoStep = vy > 0 && vy <= stepUp;
            if (groundedDrift || autoStep) {
                velocityMps = new Vec3(velocityMps.x, 0, velocityMps.z);
            }
        } else if (entity.onGround()) {
            // Non-living grounded movers (boats, minecarts) carry the same
            // gravity-drift artifact but have no gravity attribute; a small
            // fixed window covers their per-tick drift. Left in, the drift
            // tilts the collision rays slightly downward, which is what let a
            // boat gliding on ice clip the floor seams ahead as "wall" hits.
            double vy = velocityMps.y;
            if (vy < 0 && vy >= -2.0) {
                velocityMps = new Vec3(velocityMps.x, 0, velocityMps.z);
            }
        }
    }

    private void tickSpeed() {
        // Same frame-motion correction as tickVelocity: the stopping force must be
        // measured relative to the ship the entity rides, not the world.
        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20).subtract(frameMotionMps);

        // A fast impact loses its speed across TWO ticks: the contact tick still
        // travels most of the sub-block gap to the wall, so the full stop only
        // shows up the next tick. Measuring each tick's drop in isolation splits
        // one big impact into two smaller ones that can both fall under the damage
        // threshold. Comparing against the larger of the last two velocities per
        // axis measures the true speed lost and keeps fast hits from slipping
        // through. It never invents deceleration: while speeding up the current
        // speed is the largest, so the stopping force stays zero.
        double stoppingForceX = calculateStoppingForceComponent(actualVelocity.x, preImpact(prevVelocityMps.x, prevPrevVelocityMps.x));
        double stoppingForceY = calculateStoppingForceComponent(actualVelocity.y, preImpact(prevVelocityMps.y, prevPrevVelocityMps.y));
        double stoppingForceZ = calculateStoppingForceComponent(actualVelocity.z, preImpact(prevVelocityMps.z, prevPrevVelocityMps.z));

        decelerationForceHorizontal = Math.sqrt(stoppingForceX * stoppingForceX + stoppingForceZ * stoppingForceZ);
        decelerationForceVertical = stoppingForceY;
        decelerationForce = Math.sqrt(
                stoppingForceX * stoppingForceX +
                        stoppingForceY * stoppingForceY +
                        stoppingForceZ * stoppingForceZ
        );

        acceleration = velocityMps.subtract(prevVelocityMps);
    }

    /** The more extreme of the last two velocities on an axis — the speed just before an impact split across two ticks. */
    private static double preImpact(double prev, double prevPrev) {
        return Math.abs(prevPrev) > Math.abs(prev) ? prevPrev : prev;
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

    /** Remembers a block contact for a couple ticks so a late impact tick still counts. See {@link #HIT_GRACE_TICKS}. */
    private void tickHitGrace(Entity entity) {
        if (entity.horizontalCollision || clientHorizontalCollision) {
            horizontalHitGrace = HIT_GRACE_TICKS;
        } else if (horizontalHitGrace > 0) {
            horizontalHitGrace--;
        }

        if (entity.verticalCollision || clientVerticalCollision) {
            verticalHitGrace = HIT_GRACE_TICKS;
        } else if (verticalHitGrace > 0) {
            verticalHitGrace--;
        }

        // One report covers one tick; the client sends a fresh one every tick.
        clientHorizontalCollision = false;
        clientVerticalCollision = false;
    }

    /** Stores the client-measured collision flags for this tick (see PlayerDeltaPacket). */
    public void reportClientCollisions(boolean horizontal, boolean vertical) {
        this.clientHorizontalCollision |= horizontal;
        this.clientVerticalCollision |= vertical;
    }

    /** True if the entity has hit a block horizontally within the last {@link #HIT_GRACE_TICKS} ticks. */
    public boolean hadRecentHorizontalHit() {
        return horizontalHitGrace > 0;
    }

    /** True if the entity has hit a block vertically within the last {@link #HIT_GRACE_TICKS} ticks. */
    public boolean hadRecentVerticalHit() {
        return verticalHitGrace > 0;
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

    /**
     * The velocity the entity carried INTO the impact currently being resolved, in
     * m/s: the faster of the last two tick velocities. A fast impact resolves over
     * two ticks (contact tick + full-stop tick), and on the second tick the
     * one-tick-old velocity is just the small contact-tick remnant — using it for
     * the collision ray or direction classification makes the ray too short to
     * reach the block and misreads the impact direction, silently dropping damage.
     */
    public Vec3 getPreImpactScaledVelocity() {
        return prevPrevVelocityMps.lengthSqr() > prevVelocityMps.lengthSqr()
                ? prevPrevVelocityMps : prevVelocityMps;
    }

    /** {@link #getPreImpactScaledVelocity()} in blocks/tick. */
    public Vec3 getPreImpactNativeVelocity() {
        return getPreImpactScaledVelocity().scale(0.05);
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

    /** Water-slosh sound rate limit (WaterSlowdownMixin); transient by design. */
    public int getSloshCooldown() {
        return sloshCooldown;
    }

    public void setSloshCooldown(int ticks) {
        this.sloshCooldown = ticks;
    }

    public void setWaterSkipCooldown(int ticks) {
        this.waterSkipCooldown = ticks;
    }

    /**
     * Bounce rate limit. Adjacent slime faces (e.g. a corner seam) can produce
     * alternating hit normals that mirror the velocity back and forth every tick,
     * pinning the entity to the wall; a short refractory period breaks the loop.
     */
    public boolean canBounce() {
        return bounceCooldown <= 0;
    }

    public void setBounceCooldown(int ticks) {
        this.bounceCooldown = ticks;
    }

    /**
     * Kinetic-damage cooldown. Time-based rather than per-block so sliding along a
     * wall (a new BlockPos every tick) can't re-trigger damage each tick.
     */
    public boolean isDamageOnCooldown(long currentTick) {
        return currentTick < damageCooldownUntilTick;
    }

    public void markDamageApplied(long currentTick, int cooldownTicks, double totalDamageThisImpact) {
        this.damageCooldownUntilTick = currentTick + cooldownTicks;
        this.recentAppliedDamage = totalDamageThisImpact;
    }

    /**
     * Damage already dealt for the impact currently on cooldown, or 0 once the
     * cooldown has lapsed. A two-tick impact can deal a small partial hit on the
     * contact tick; without this, that partial started the cooldown and swallowed
     * the real (much larger) stopping-force hit one tick later — the cause of the
     * wildly inconsistent fall damage (9-block falls dealing one heart). The
     * applier tops the damage up to the larger value instead.
     */
    public double getRecentAppliedDamage(long currentTick) {
        return isDamageOnCooldown(currentTick) ? recentAppliedDamage : 0.0;
    }

    public void setTargetAngle(double targetAngle) {
        this.targetAngle = targetAngle;
    }

    public void setTargetPitch(double targetPitch) {
        this.targetPitch = targetPitch;
    }

    // Direction classification uses the pre-impact velocity: on the tick a fast
    // impact's damage lands, the one-tick-old velocity is only the post-contact
    // remnant and can point anywhere (e.g. sliding along the wall it just hit).

    public boolean isMostlyDownward() {
        Vec3 v = getPreImpactScaledVelocity();
        return (-v.y) > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    public boolean isMostlyUpward() {
        Vec3 v = getPreImpactScaledVelocity();
        return v.y > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    public boolean isMostlyHorizontal() {
        Vec3 v = getPreImpactScaledVelocity();
        return Math.sqrt(v.x * v.x + v.z * v.z) > Math.abs(v.y);
    }

    public void resetVelocity() {
        this.velocityMps = Vec3.ZERO;
        this.prevVelocityMps = Vec3.ZERO;
        this.prevPrevVelocityMps = Vec3.ZERO;
        this.frameMotionMps = Vec3.ZERO;
        this.clientVelocityMps = null;
    }

    public void setJustBounced(boolean justBounced) {
        this.justBounced = justBounced;
    }

    /**
     * Advances the thin-air exposure clock and returns its new value. Exposure
     * builds by 1/tick in thin air and recovers 4× as fast below the altitude
     * threshold, so dipping under the line briefly doesn't reset the whole ramp.
     */
    public int tickThinAirExposure(boolean inThinAir) {
        if (inThinAir) {
            if (thinAirExposureTicks < Integer.MAX_VALUE - 1) thinAirExposureTicks++;
        } else {
            thinAirExposureTicks = Math.max(0, thinAirExposureTicks - 4);
        }
        return thinAirExposureTicks;
    }

    /** Cached {@link Mob#isNoAi()}, refreshed once a second. See {@link #NO_AI_REFRESH_TICKS}. */
    public boolean isNoAiCached(Mob mob) {
        if (--noAiRefreshIn <= 0) {
            cachedNoAi = mob.isNoAi();
            noAiRefreshIn = NO_AI_REFRESH_TICKS;
        }
        return cachedNoAi;
    }

    public static @Nullable FullStopCapability grabCapability(Entity entity) {
        // Hot path: the mixin cache turns the capability-dispatcher walk into a
        // field read. The fallback only exists for exotic Entity subclasses the
        // mixin somehow didn't reach.
        if (entity instanceof FullStopCapabilityCache cache) {
            return cache.fullstop$getCapability();
        }
        return entity.getCapability(Provider.DELTAV_CAP).orElse(null);
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesEvent(AttachCapabilitiesEvent<Entity> event) {
        if ((event.getObject().getCapability(Provider.DELTAV_CAP).isPresent())) return;

        Provider provider = new Provider(event.getObject());
        event.addCapability(DELTA_VELOCITY, provider);
        // Without this, invalidateCaps() never reaches our LazyOptional and the
        // mixin cache's invalidation listener could never fire.
        event.addListener(provider::invalidate);
    }

    public static class Provider implements ICapabilityProvider {
        public static final Capability<FullStopCapability> DELTAV_CAP = CapabilityManager.get(new CapabilityToken<>() {});
        private final Entity entity;

        private FullStopCapability capability = null;
        private final LazyOptional<FullStopCapability> lazyHandler = LazyOptional.of(this::createCapability);

        public Provider(Entity entity) {
            this.entity = entity;
        }

        /** Called via AttachCapabilitiesEvent listener when the entity's caps are invalidated. */
        public void invalidate() {
            lazyHandler.invalidate();
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
