package net.camacraft.fullstop.common.capability;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.camacraft.camafy.util.math.EntityRotation;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.physics.rules.GForceThresholds;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
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

    // The frame the entity's physics lives in this tick: which way is up.
    // DEFAULT (world +Y) without a gravity mod. Under a Gravity Unbound field
    // the frame is rotated, and every vertical/horizontal split below — the
    // stopping-force decomposition, the grounded-drift rule, the felt-gravity
    // baseline, the mostly-downward classification — is measured against it,
    // matching the per-frame collision flags the mod reports. Resolved once
    // per tick (see currentFrame).
    private GravityFrame frame = GravityFrame.DEFAULT;

    // Stopping force = speed lost this tick (m/s), decomposed for per-axis damage
    // thresholds. Vertical/horizontal are measured in the entity's gravity FRAME.
    private double decelerationForce = 0.0;
    private double decelerationForceHorizontal = 0.0;
    private double decelerationForceVertical = 0.0;
    // Per-WORLD-axis components, for evidence that a specific wall FACE was hit
    // (flush-stop corroboration in KineticDamageCalculator): block faces are
    // world-aligned whatever frame the entity is in, so this stays in world axes.
    private double decelerationForceX = 0.0;
    private double decelerationForceY = 0.0;
    private double decelerationForceZ = 0.0;

    private double avgAccel = 0.0;
    private double rawAvgAccel = 0.0; // Unaffected by clarity/vertigo potions

    // Grounded state of the last two ticks, for the jump-is-not-a-bounce rule
    // in tickSpeed (same window as prevVelocityMps/prevPrevVelocityMps).
    private boolean prevOnGround = false;
    private boolean prevPrevOnGround = false;

    private boolean isDamageImmune = false;
    private boolean hasTeleported = false;
    private boolean wasSleeping = false;
    private boolean hasDismounted = false;
    private boolean firstTick = true;
    private int teleportCooldown = 0;
    private int dismountCooldown = 0;
    private int soundCooldown = 0;
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

    // CLIENT-TICK SAMPLING (server side, client-driven movers). A player's
    // server position only changes when a move packet is applied, and the
    // client's ticks are not phase-locked to the server's: whenever the two
    // clocks drift into alignment a server tick sees TWO move packets (a
    // doubled position delta) and the next sees NONE (a dead stop). Measured
    // per server tick that read as a 2x speed followed by a full stop - a
    // phantom 18 m/s "landing" out of a normal jump (damage), and at elytra
    // speed a +-60 m/s acceleration spike every such tick (blackouts while
    // flying straight). Each move packet (and each of the client's own
    // per-tick velocity reports) is therefore recorded as one SAMPLE - one
    // client tick of motion - and the physics steps once per sample: twice
    // on a two-packet tick, not at all on an empty one.
    private static final int MAX_QUEUED_SAMPLES = 8;

    private record MoveSample(Vec3 position, @Nullable Vec3 clientVelocityMps,
                              boolean horizontalCollision, boolean verticalCollision,
                              boolean onGround, float yaw) {
    }

    private final java.util.ArrayDeque<MoveSample> samples = new java.util.ArrayDeque<>();
    private boolean sampling = false;
    // Where each step of the LAST tick() ended, and whether that tick stepped
    // per client tick (samples, or mirrored from the vehicle). A passenger of
    // a sampled vehicle replays these instead of its own per-server-tick
    // position delta: the rider is only repositioned once per server tick, so
    // on the vehicle's two-packet ticks it read 2x speed and on the empty ones
    // a dead stop - racers and boat passengers were blinded and nauseated by a
    // perfectly smooth ride.
    private final java.util.ArrayList<Vec3> steppedPositions = new java.util.ArrayList<>(4);
    private boolean steppedPerSample = false;
    private long steppedTick = Long.MIN_VALUE;
    // false on a server tick that received no client tick of motion: the
    // dispatcher skips impact processing (nothing moved) that tick
    private boolean freshSample = true;
    // the client's velocity report for the client tick whose move packet
    // has not arrived yet (it is sent first, every tick, moved or not)
    @Nullable
    private Vec3 pendingVelocityMps = null;
    private boolean pendingHorizontalCollision = false;
    private boolean pendingVerticalCollision = false;
    private boolean hasPending = false;

    // DIZZINESS: sustained turning - the view spun round and round, or the
    // heading of a fast run swung in circles - smoothed over ~2 s. Drives the
    // nausea effect alone (no vignette): spinning makes you dizzy, not black
    // out. Per-tick input is capped so a single frame-convention yaw jump
    // (Gravity Unbound moving a frame twist into yaw) cannot register.
    private static final double SPIN_WINDOW_TICKS = 40.0;
    private static final double SPIN_INPUT_CAP_DEG = 60.0;
    private static final double SPIN_NOISE_FLOOR_DEG = 1.5;
    private static final double SPIN_HEADING_MIN_SPEED_SQR = 3.0 * 3.0;
    private double avgSpinDegPerTick = 0.0;
    private float lastYaw = Float.NaN;
    @Nullable
    private Vec3 lastHeading = null;

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

    // Ship weight: the landing impulse already delivered to a ship for the
    // impact on cooldown - the same top-up as damage, so a two-tick landing
    // pushes the deck once, by its full momentum.
    private long shipImpulseUntilTick = Long.MIN_VALUE;
    private double recentShipImpulseNs = 0.0;

    // Ship collisions: the ship that last hit this entity, and the game time
    // until which that ship can't hit it again (one hit per contact, not one
    // per tick while the hull shoves it along).
    private long lastShipHitId = Long.MIN_VALUE;
    private long shipHitCooldownUntil = Long.MIN_VALUE;

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

    // Which parts of this entity's physics other mods own this tick (Camafy's
    // PhysicsOwnership, see PhysicsClaims): refreshed once per tick by the
    // physics dispatch and read by every FullStop system that acts on the
    // entity. Volatile: block-shape queries (PhaseableBlockMixin) can read it
    // off the main thread.
    private volatile int physicsClaims = 0;
    // Game time of the last PhysicsOwnership.markDiscontinuity already handled.
    private long lastDiscontinuitySeen = Long.MIN_VALUE;

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

        frame = currentFrame(entity);

        // Which measurement drives this tick: the queued client-tick samples
        // (a player moving under its own client, a vehicle that client
        // drives), the vehicle's samples replayed (a passenger of such a
        // vehicle), or the entity's live state (everything server-simulated,
        // the local player's client-side copy).
        boolean useSamples = sampling && sampledMover(entity);
        if (!useSamples) {
            samples.clear();
            hasPending = false;
        }
        FullStopCapability carrier = useSamples ? null : sampledCarrier(entity);

        steppedPositions.clear();
        if (useSamples) {
            if (samples.isEmpty()) {
                // no client tick of motion reached the server this tick:
                // hold - nothing moved, nothing was felt, no history shifts
                freshSample = false;
                holdStep();
            } else {
                freshSample = true;
                stepSamples(entity, samples);
            }
        } else if (carrier != null) {
            // Ride along one step per vehicle sample: each of the vehicle's
            // client ticks, shifted by where this passenger sits on it.
            clientVelocityMps = null;
            if (carrier.steppedPositions.isEmpty()) {
                freshSample = false;
                holdStep();
            } else {
                freshSample = true;
                Vec3 seat = entity.position().subtract(entity.getVehicle().position());
                java.util.ArrayDeque<MoveSample> ride = new java.util.ArrayDeque<>(carrier.steppedPositions.size());
                for (Vec3 vehicleAt : carrier.steppedPositions) {
                    ride.addLast(new MoveSample(vehicleAt.add(seat), null,
                            entity.horizontalCollision, entity.verticalCollision,
                            entity.onGround(), entity.getYRot()));
                }
                stepSamples(entity, ride);
            }
        } else {
            freshSample = true;
            step(entity, entity.position(), clientVelocityMps,
                    entity.horizontalCollision, entity.verticalCollision,
                    entity.onGround(), entity.getYRot());
            clientVelocityMps = null;
        }
        steppedPerSample = useSamples || carrier != null;
        steppedTick = entity.tickCount;

        tickImmunity();
        tickRiding();

        if (soundCooldown > 0) {
            soundCooldown--;
        }

        if (waterSkipCooldown > 0) {
            waterSkipCooldown--;
        }

        if (bounceCooldown > 0) {
            bounceCooldown--;
        }
    }

    /** Whether this entity's motion is owned by a client right now (see the sampling notes above). */
    private static boolean sampledMover(Entity entity) {
        if (entity instanceof Player) {
            return !entity.isPassenger();
        }
        return entity.getControllingPassenger() instanceof Player;
    }

    /**
     * The vehicle's capability when this entity rides something that was measured per client
     * tick THIS server tick (see {@link #steppedPositions}); null otherwise. The server's
     * physics dispatch ticks every vehicle before its passengers, so the vehicle's steps are
     * already known here.
     */
    @Nullable
    private static FullStopCapability sampledCarrier(Entity entity) {
        Entity vehicle = entity.getVehicle();
        if (vehicle == null) return null;
        FullStopCapability cap = grabCapability(vehicle);
        if (cap == null || !cap.steppedPerSample || cap.steppedTick != vehicle.tickCount) return null;
        return cap;
    }

    /**
     * Steps once per queued sample (consuming the queue) and hands the impact pipeline the
     * hardest stop among them: it runs once per server tick, the samples are client ticks.
     */
    private void stepSamples(Entity entity, java.util.ArrayDeque<MoveSample> queue) {
        int count = queue.size();
        double bestForce = -1.0;
        double bH = 0, bV = 0, bX = 0, bY = 0, bZ = 0, bTotal = 0;
        MoveSample sample;
        while ((sample = queue.pollFirst()) != null) {
            step(entity, sample.position(), sample.clientVelocityMps(),
                    sample.horizontalCollision(), sample.verticalCollision(),
                    sample.onGround(), sample.yaw());
            steppedPositions.add(sample.position());
            if (count > 1 && decelerationForce > bestForce) {
                bestForce = decelerationForce;
                bTotal = decelerationForce;
                bH = decelerationForceHorizontal;
                bV = decelerationForceVertical;
                bX = decelerationForceX;
                bY = decelerationForceY;
                bZ = decelerationForceZ;
            }
        }
        if (count > 1) {
            decelerationForce = bTotal;
            decelerationForceHorizontal = bH;
            decelerationForceVertical = bV;
            decelerationForceX = bX;
            decelerationForceY = bY;
            decelerationForceZ = bZ;
        }
    }

    /**
     * One client tick of motion: the velocity measurement, the stopping force,
     * the collision-evidence grace, the felt g-force and the spin metric.
     *
     * @param position          where the entity is at the end of this tick of motion
     * @param clientVelocityMps the mover's own velocity report for it, if any (m/s)
     * @param horizontalHit     the collision flags that belong to this tick (the
     *                          client's for a sampled mover, the entity's own otherwise)
     * @param onGround          grounded at the end of it
     * @param yaw               the body yaw at the end of it (spin metric)
     */
    private void step(Entity entity, Vec3 position, @Nullable Vec3 clientVelocityMps,
                      boolean horizontalHit, boolean verticalHit, boolean onGround, float yaw) {
        tickVelocity(entity, position, clientVelocityMps, horizontalHit, verticalHit, onGround);
        tickSpeed(onGround);
        tickHitGrace(entity, horizontalHit, verticalHit);

        if (Double.isNaN(avgAccel)) avgAccel = 0;
        if (Double.isNaN(rawAvgAccel)) rawAvgAccel = 0;

        if (entity instanceof LivingEntity living) {
            if (PhysicsAspect.G_FORCE.in(physicsClaims)) {
                // Another mod owns the felt g (MKW's racers): nothing builds up,
                // so no blackout is waiting the moment the claim ends.
                avgAccel = 0.0;
                rawAvgAccel = 0.0;
                avgSpinDegPerTick = 0.0;
                lastYaw = Float.NaN;
                lastHeading = null;
            } else {
                tickGForce(living);
                tickSpin(living, yaw);
            }
        }

        // After tickSpeed, which reads the previous ticks' values.
        prevPrevOnGround = prevOnGround;
        prevOnGround = onGround;
    }

    /** A server tick with no client tick of motion in it: no speed was lost and nothing was felt. */
    private void holdStep() {
        decelerationForce = 0.0;
        decelerationForceHorizontal = 0.0;
        decelerationForceVertical = 0.0;
        decelerationForceX = 0.0;
        decelerationForceY = 0.0;
        decelerationForceZ = 0.0;
        acceleration = Vec3.ZERO;
    }

    /**
     * True when this tick carried a fresh measurement. False only for a
     * client-driven mover on a server tick that received none of its client
     * ticks - the dispatcher then skips the impact pipeline for it.
     */
    public boolean hasFreshSample() {
        return freshSample;
    }

    /**
     * Server side: the client's velocity report for its current tick
     * (PlayerDeltaPacket). Sent BEFORE that tick's move packet, so it opens
     * the sample the move packet then completes; if the previous report was
     * never completed (an idle client tick sends no move packet at all) that
     * sample is closed at the entity's current position first.
     */
    public void recordClientDelta(Vec3 nativeVelocity, boolean horizontalCollision, boolean verticalCollision) {
        sampling = true;
        if (hasPending) {
            pushSample(entity.position(), entity.onGround(), entity.getYRot());
        }
        pendingVelocityMps = nativeVelocity.scale(20);
        pendingHorizontalCollision = horizontalCollision;
        pendingVerticalCollision = verticalCollision;
        hasPending = true;
    }

    /** Server side: a move packet was applied - one client tick of motion ended at {@code position}. */
    public void recordMoveSample(Vec3 position, boolean onGround, float yaw) {
        sampling = true;
        pushSample(position, onGround, yaw);
    }

    private void pushSample(Vec3 position, boolean onGround, float yaw) {
        Vec3 velocity = hasPending ? pendingVelocityMps : null;
        boolean horizontal = hasPending && pendingHorizontalCollision;
        boolean vertical = hasPending && pendingVerticalCollision;
        hasPending = false;
        pendingVelocityMps = null;
        if (samples.size() >= MAX_QUEUED_SAMPLES) {
            samples.pollFirst();
        }
        samples.addLast(new MoveSample(position, velocity, horizontal, vertical, onGround, yaw));
    }

    /**
     * Dizziness: how fast the entity has been turning lately, degrees per
     * tick smoothed over {@link #SPIN_WINDOW_TICKS}. The larger of the body
     * yaw rate (spinning the view) and the heading rate of a fast run
     * (circling). Players only - it exists for the nausea effect.
     */
    private void tickSpin(LivingEntity living, float yaw) {
        if (!(living instanceof Player)) {
            return;
        }
        double yawRate = 0.0;
        if (!Float.isNaN(lastYaw)) {
            yawRate = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - lastYaw));
        }
        lastYaw = yaw;

        double headingRate = 0.0;
        Vec3 heading = frame.horizontalPart(velocityMps);
        if (lastHeading != null
                && heading.lengthSqr() > SPIN_HEADING_MIN_SPEED_SQR
                && lastHeading.lengthSqr() > SPIN_HEADING_MIN_SPEED_SQR) {
            double cos = heading.dot(lastHeading) / (heading.length() * lastHeading.length());
            headingRate = Math.toDegrees(Math.acos(net.minecraft.util.Mth.clamp(cos, -1.0, 1.0)));
        }
        lastHeading = heading;

        double spin = Math.min(Math.max(yawRate, headingRate), SPIN_INPUT_CAP_DEG);
        if (spin < SPIN_NOISE_FLOOR_DEG) {
            spin = 0.0;
        }
        avgSpinDegPerTick += (spin - avgSpinDegPerTick) / SPIN_WINDOW_TICKS;
        if (Double.isNaN(avgSpinDegPerTick)) avgSpinDegPerTick = 0.0;
    }

    /** Smoothed turning rate, degrees per tick (see {@link #tickSpin}). */
    public double getSpinRate() {
        return avgSpinDegPerTick;
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
        // Along the FRAME's down: inside a gravity field the 1g everyone feels
        // at rest pulls along the field, not world -Y (Gravity Unbound applies
        // its pull through this same attribute, along its frame).
        Vec3 gravityVector = frame.isDefault() ? new Vec3(0, -baseline, 0) : frame.up().scale(-baseline);

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

    private void tickVelocity(Entity entity, Vec3 position, @Nullable Vec3 clientVelocityMps,
                              boolean horizontalHit, boolean verticalHit, boolean onGround) {
        if (firstTick) {
            currentPosition = position;
            previousPosition = currentPosition.subtract(entity.getDeltaMovement());
            // Seed the whole velocity history from the spawn motion. An entity
            // is born already moving (projectiles at FULL speed), and an empty
            // history cost the first ticks twice over: pre-impact velocity read
            // zero (the dispatcher skipped processing entirely), and a spawn
            // speed over 60 m/s tripped the teleport-discontinuity guard
            // (lastMeasuredSpeed was 0). A tagged grappling hook fired at
            // nearby ice hit it before FullStop ever processed one tick.
            Vec3 spawnVelocityMps = entity.getDeltaMovement().scale(20);
            velocityMps = spawnVelocityMps;
            prevVelocityMps = spawnVelocityMps;
            prevPrevVelocityMps = spawnVelocityMps;
            lastMeasuredSpeed = spawnVelocityMps.length();
            firstTick = false;
        } else {
            previousPosition = currentPosition;
        }

        if (hasTeleported) {
            currentPosition = position;
            previousPosition = currentPosition;
            velocityMps = Vec3.ZERO;
            prevVelocityMps = Vec3.ZERO;
            prevPrevVelocityMps = Vec3.ZERO;
            frameMotionMps = Vec3.ZERO;
            return;
        }

        prevPrevVelocityMps = prevVelocityMps;
        prevVelocityMps = velocityMps;

        currentPosition = position;

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
            return;
        }
        lastMeasuredSpeed = measuredSpeed;

        // Player-controlled vehicles consume the driver's report too — the packet
        // stored it on the vehicle's capability, but only players ever read it,
        // so the whole vehicle branch was dead.
        boolean clientControlled = entity instanceof Player || entity.getControllingPassenger() instanceof Player;
        if (clientControlled && clientVelocityMps != null) {
            // The client value may REFINE the measurement UPWARD only — it exists
            // to capture intent the server position hasn't integrated yet (the
            // first tick of a jump or dash), bounded by the plausibility band so
            // a cheat client can't inflate it either. It must NEVER replace the
            // measurement with a smaller value: vanilla multiplies deltaMovement
            // by ground friction (~0.55) AFTER moving, so a grounded runner's
            // end-of-tick report is systematically ~half their real speed — and
            // the old both-ways band accepted it every tick, which halved every
            // grounded impact's stopping force (ground-level wall slams dealt no
            // damage at speeds where the same airborne slam hurt). A cheat
            // client under-reporting now simply falls back to the measurement.
            double toleranceSqr = Math.max(1.0, actualVelocity.lengthSqr() * 0.25);
            boolean plausible = clientVelocityMps.subtract(actualVelocity).lengthSqr() <= toleranceSqr;
            boolean refinesUpward = clientVelocityMps.lengthSqr() >= actualVelocity.lengthSqr();
            if (!horizontalHit && !verticalHit && plausible && refinesUpward) {
                velocityMps = clientVelocityMps;
            } else {
                velocityMps = actualVelocity;
            }
        } else {
            velocityMps = actualVelocity;
        }

        if (entity instanceof LivingEntity living && onGround) {
            // Null-safe: an exotic modded living entity without the Forge gravity
            // attribute must degrade to the vanilla 0.08, not crash the tick loop.
            var gravityAttr = living.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
            double gravityStep = (gravityAttr != null ? gravityAttr.getValue() : 0.08) * 20;
            // How far vanilla's auto-step can lift the entity in a single tick.
            double stepUp = entity.maxUpStep() * 20;
            // Along the frame's up: standing on a plated wall, the floor drift
            // and the step pop happen along the field, and onGround is the
            // frame's grounded flag.
            double vy = frame.vertical(velocityMps);

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
                velocityMps = frame.horizontalPart(velocityMps);
            }
        } else if (onGround) {
            // Non-living grounded movers (boats, minecarts) carry the same
            // gravity-drift artifact but have no gravity attribute; a small
            // fixed window covers their per-tick drift. Left in, the drift
            // tilts the collision rays slightly downward, which is what let a
            // boat gliding on ice clip the floor seams ahead as "wall" hits.
            double vy = frame.vertical(velocityMps);
            if (vy < 0 && vy >= -2.0) {
                velocityMps = frame.horizontalPart(velocityMps);
            }
        }
    }

    private void tickSpeed(boolean onGround) {
        // Same frame-motion correction as tickVelocity: the stopping force must be
        // measured relative to the ship the entity rides, not the world.
        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20).subtract(frameMotionMps);

        // WORLD-axis components first: block faces are world-aligned whatever
        // frame the entity is in, so the flush-stop evidence (speed lost on a
        // specific face's axis) reads these.
        decelerationForceX = calculateStoppingForceComponent(actualVelocity.x, preImpact(prevVelocityMps.x, prevPrevVelocityMps.x));
        decelerationForceY = calculateStoppingForceComponent(actualVelocity.y, preImpact(prevVelocityMps.y, prevPrevVelocityMps.y));
        decelerationForceZ = calculateStoppingForceComponent(actualVelocity.z, preImpact(prevVelocityMps.z, prevPrevVelocityMps.z));

        // The damage decomposition is measured in the entity's gravity FRAME:
        // "vertical" is along the frame's up, "horizontal" across it. That is
        // what the damage thresholds mean (falls vs wall runs) and what the
        // frame's collision flags corroborate. In the default frame the local
        // vectors ARE the world vectors and everything below is bit-identical
        // to the world math. Under a rotated frame all three history samples
        // are rotated by the SAME (current) frame, so a steady world velocity
        // in a turning frame never reads as a stop — only a real change of
        // world velocity does.
        Vec3 current = frame.worldToLocal(actualVelocity);
        Vec3 prev = frame.worldToLocal(prevVelocityMps);
        Vec3 prevPrev = frame.worldToLocal(prevPrevVelocityMps);

        // A fast impact loses its speed across TWO ticks: the contact tick still
        // travels most of the sub-block gap to the wall, so the full stop only
        // shows up the next tick. Measuring each tick's drop in isolation splits
        // one big impact into two smaller ones that can both fall under the damage
        // threshold. Comparing against the larger of the last two velocities per
        // axis measures the true speed lost and keeps fast hits from slipping
        // through. It never invents deceleration: while speeding up the current
        // speed is the largest, so the stopping force stays zero.
        double stoppingForceX = calculateStoppingForceComponent(current.x, preImpact(prev.x, prevPrev.x));
        double stoppingForceZ = calculateStoppingForceComponent(current.z, preImpact(prev.z, prevPrev.z));

        // A JUMP IS NOT A BOUNCE. An upward velocity born on the ground is
        // self-powered: the ground can only stop a fall to zero, never fling
        // the entity back up. Left to the generic bounce rule, jumping on the
        // exact landing tick sign-flipped against the fall's remnant inside the
        // two-tick window and billed fall + jump speed as one huge vertical
        // stopping force — a harmless hop plus a well-timed space press dealt
        // heavy "impact" damage. Measure the landing against zero instead.
        // Slime/bed bounces are unaffected: they are applied PRE-contact, so
        // the entity is never grounded during a genuine bounce reversal.
        double oldY = preImpact(prev.y, prevPrev.y);
        double stoppingForceY;
        if (current.y > 0 && (prevOnGround || prevPrevOnGround)) {
            stoppingForceY = calculateStoppingForceComponent(0.0, oldY);
        } else if (oldY > 0 && (prevOnGround || prevPrevOnGround)
                && oldY <= Math.max(13.0, entity.maxUpStep() * 20.0 + 1.0)) {
            // The mirror case: a step/jump-scale upward spike already IN the
            // history, recorded on a tick where onGround flickered false (stair
            // and slab CORNERS do this — auto-step pops the entity up by as
            // much as maxUpStep, 12 m/s, mid-move). Sign-flipping that spike
            // against the next falling tick billed it as a violent bounce:
            // hopping onto the corner of stairs dealt repeated phantom hits
            // that could kill from a two-block drop. Near-ground upward motion
            // at self-power scale is not momentum — measure against zero.
            stoppingForceY = calculateStoppingForceComponent(current.y, 0.0);
        } else {
            stoppingForceY = calculateStoppingForceComponent(current.y, oldY);
        }

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

        double correction = EntityRotation.wrapDegrees(targetAngle - entity.getYRot());

        if (Math.abs(correction) < 0.5) {
            targetAngle = Double.NaN;
        }

        return correction * 0.005 * delta;
    }

    public double pitchCorrection(double delta) {
        if (Double.isNaN(targetPitch)) {
            return 0;
        }

        double correction = EntityRotation.wrapDegrees(targetPitch - entity.getXRot());

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
    private void tickHitGrace(Entity entity, boolean horizontalHit, boolean verticalHit) {
        if (entity.horizontalCollision || horizontalHit || clientHorizontalCollision) {
            horizontalHitGrace = HIT_GRACE_TICKS;
        } else if (horizontalHitGrace > 0) {
            horizontalHitGrace--;
        }

        if (entity.verticalCollision || verticalHit || clientVerticalCollision) {
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

    /** Speed lost this tick on one WORLD axis (m/s). See flush-stop corroboration. */
    public double getAxisStoppingForce(Direction.Axis axis) {
        return switch (axis) {
            case X -> decelerationForceX;
            case Y -> decelerationForceY;
            case Z -> decelerationForceZ;
        };
    }

    /**
     * The gravity frame this entity's physics lives in, as of its last tick:
     * {@link GravityFrame#DEFAULT} (up = world +Y) without a gravity mod, the
     * field's frame under Gravity Unbound. Every vertical/horizontal split in
     * FullStop reads it — see {@link GravityFrame}.
     */
    public GravityFrame getFrame() {
        return frame;
    }

    /**
     * The entity's gravity frame right now, asked of the gravity mod (Gravity
     * Unbound registers itself with Camafy). {@link GravityFrame#DEFAULT} when
     * none is installed or the {@code enableGravityUnboundCompat} server config
     * is off — FullStop then behaves exactly as if the mod were not installed.
     * Costs a capability lookup in the gravity mod: per-tick code reads the
     * frame cached on the capability ({@link #getFrame}) instead.
     */
    public static GravityFrame currentFrame(Entity entity) {
        if (!Gravity.available()
                || !FullStopConfig.SERVER_SPEC.isLoaded()
                || !FullStopConfig.SERVER.enableGravityUnboundCompat.get()) {
            return GravityFrame.DEFAULT;
        }
        return Gravity.frame(entity);
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

    /**
     * Re-reads which aspects other mods own of this entity (once per tick, before {@link #tick})
     * and treats the two hand-over moments like a teleport, so motion FullStop didn't watch is
     * never billed as an impact: IMPACT going from claimed to free (a player thrown off an MKW
     * kart is back under FullStop from a clean slate), and a newer
     * {@link PhysicsOwnership#markDiscontinuity} (a snap onto a rail, a gravity-flip reposition).
     *
     * @return the claims now in force (also {@link #getClaims})
     */
    public int refreshClaims(Entity entity) {
        int now = PhysicsClaims.live(entity);
        if (PhysicsAspect.IMPACT.in(physicsClaims) && !PhysicsAspect.IMPACT.in(now)) {
            hasTeleported = true;
        }
        physicsClaims = now;

        long discontinuity = PhysicsOwnership.lastDiscontinuity(entity);
        if (discontinuity != Long.MIN_VALUE && discontinuity > lastDiscontinuitySeen) {
            lastDiscontinuitySeen = discontinuity;
            hasTeleported = true;
        }
        return now;
    }

    // Client side: the last discontinuity already reported to the server.
    private long lastDiscontinuitySent = Long.MIN_VALUE;

    /**
     * Client side: whether a {@link PhysicsOwnership#markDiscontinuity} was made on this entity
     * since the last call - the server can't see client-side marks, so PlayerDeltaPacket carries it.
     */
    public boolean takeUnsentDiscontinuity(Entity entity) {
        long discontinuity = PhysicsOwnership.lastDiscontinuity(entity);
        if (discontinuity == Long.MIN_VALUE || discontinuity <= lastDiscontinuitySent) return false;
        lastDiscontinuitySent = discontinuity;
        return true;
    }

    /** The aspects other mods owned of this entity at its last {@link #refreshClaims}, as a bit mask. */
    public int getClaims() {
        return physicsClaims;
    }

    /** Whether another mod owned {@code aspect} of this entity at its last {@link #refreshClaims}. */
    public boolean isClaimed(PhysicsAspect aspect) {
        return aspect.in(physicsClaims);
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

    // The vehicle this entity most recently mounted or dismounted, valid while
    // the dismount cooldown runs. Exists so the entity-collision system can
    // exempt the pair: vanilla parks a dismounted player right beside the
    // still-moving boat, and the momentum exchange (against the player's
    // server-side deltaMovement of ~0) dead-stopped the boat while launching
    // the player.
    private int exchangedVehicleId = -1;

    public void markVehicleExchange(int vehicleId) {
        this.exchangedVehicleId = vehicleId;
    }

    /** True while this entity's mount/dismount grace covers {@code other}. */
    public boolean recentlyExchangedWith(Entity other) {
        return dismountCooldown > 0 && exchangedVehicleId == other.getId();
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

    // Whether the entity is currently past the sound barrier. A sonic boom is
    // the CROSSING, not a state: one boom fires on the subsonic→supersonic
    // transition, then nothing until the entity has genuinely dropped back
    // below the barrier (with hysteresis) and crosses again. The old
    // cooldown-based version re-boomed every second of sustained supersonic
    // flight, which read as an arbitrary interval.
    private boolean supersonic = false;

    // The pressure wave: players who have already been swept by THIS
    // supersonic episode's shock cone. Each bystander the flyer passes hears
    // the boom exactly once; the set resets when the entity drops subsonic so
    // a new crossing booms everyone again. Lazily allocated — supersonic
    // entities are rare.
    private java.util.Set<java.util.UUID> boomHeardBy = null;

    public boolean isSupersonic() {
        return supersonic;
    }

    public void setSupersonic(boolean supersonic) {
        this.supersonic = supersonic;
        if (!supersonic) {
            boomHeardBy = null;
        }
    }

    /** Marks a listener as swept by the current shock cone; true if newly marked. */
    public boolean markBoomHeard(java.util.UUID listener) {
        if (boomHeardBy == null) {
            boomHeardBy = new java.util.HashSet<>();
        }
        return boomHeardBy.add(listener);
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

    /** Newton-seconds already pushed into a ship for the landing on cooldown, or 0 once it lapsed. */
    public double getRecentShipImpulse(long currentTick) {
        return currentTick < shipImpulseUntilTick ? recentShipImpulseNs : 0.0;
    }

    public void markShipImpulse(long currentTick, int cooldownTicks, double totalNewtonSeconds) {
        this.shipImpulseUntilTick = currentTick + cooldownTicks;
        this.recentShipImpulseNs = totalNewtonSeconds;
    }

    /** Whether ship {@code shipId} hit this entity so recently that it can't hit it again yet. */
    public boolean isShipHitOnCooldown(long shipId, long gameTime) {
        return shipId == lastShipHitId && gameTime < shipHitCooldownUntil;
    }

    public void markShipHit(long shipId, long gameTime, int cooldownTicks) {
        this.lastShipHitId = shipId;
        this.shipHitCooldownUntil = gameTime + cooldownTicks;
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

    // "Down" is the frame's down: inside a gravity field a slam along the
    // field's pull is a fall whichever world axis it happens along.

    public boolean isMostlyDownward() {
        Vec3 v = getPreImpactScaledVelocity();
        return (-frame.vertical(v)) > frame.horizontalDistance(v);
    }

    public boolean isMostlyUpward() {
        Vec3 v = getPreImpactScaledVelocity();
        return frame.vertical(v) > frame.horizontalDistance(v);
    }

    public boolean isMostlyHorizontal() {
        Vec3 v = getPreImpactScaledVelocity();
        return frame.horizontalDistance(v) > Math.abs(frame.vertical(v));
    }

    public void resetVelocity() {
        this.velocityMps = Vec3.ZERO;
        this.prevVelocityMps = Vec3.ZERO;
        this.prevPrevVelocityMps = Vec3.ZERO;
        this.frameMotionMps = Vec3.ZERO;
        this.clientVelocityMps = null;
        this.lastHeading = null;
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
