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
import net.camacraft.fullstop.common.physics.rules.SpinDizziness;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
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
    // The same history for the frame motion, so the world velocity of each of
    // the last ticks (own velocity + what the ship added) can be rebuilt: a
    // collision with a ship is judged against the hull's own motion, never
    // against a velocity with the carrying ship taken out.
    private Vec3 prevFrameMotionMps = Vec3.ZERO;
    private Vec3 prevPrevFrameMotionMps = Vec3.ZERO;
    // How far a Valkyrien Skies hull shoved the entity during its move of tick shipPushTick
    // (world, blocks): a hull that moves into an entity is resolved by VS moving the entity's
    // POSITION (never its velocity), so like the carry it is not the entity's own motion and is
    // taken out of the measurement. Recorded by the collide wrapper (ShipHullSweep).
    private Vec3 shipPushBpt = Vec3.ZERO;
    private int shipPushTick = Integer.MIN_VALUE;
    // The shove taken out of the latest step's measurement (m/s), kept for tickSpeed.
    private Vec3 pushMps = Vec3.ZERO;
    // Client side (the local player): the velocity change a hull that just struck it gives it
    // (ClientShipContacts), added at the end of the tick, after the move that met the hull.
    private Vec3 pendingShipKickBpt = Vec3.ZERO;
    // A rider SEATED on a Valkyrien Skies ship (a helm, a VS / Create / Clockwork seat) feels its
    // seat's acceleration, which the server works out from the ship's physics frames (the seat's
    // own motion is VS repositioning it on the ship, per server tick - bursty). Set each tick for
    // that tick; seatAccelerationTick marks it current.
    private Vec3 seatAccelerationMps2 = Vec3.ZERO;
    private int seatAccelerationTick = Integer.MIN_VALUE;
    // Client side: the server's g-force reading for the local player while it sits on a ship
    // (GForcePacket), in force until remoteGForceUntil (client game time).
    private double remoteAvgAccel = 0.0;
    private double remoteRawAvgAccel = 0.0;
    private long remoteGForceUntil = Long.MIN_VALUE;
    private long clientContactShipId = Long.MIN_VALUE;
    private long clientContactUntil = Long.MIN_VALUE;

    // The frame the entity's physics lives in this tick: which way is up.
    // DEFAULT (world +Y) without a gravity mod. Under a Gravity Unbound field
    // the frame is rotated, and every vertical/horizontal split below — the
    // stopping-force decomposition, the grounded-drift rule, the felt-gravity
    // baseline, the mostly-downward classification — is measured against it,
    // matching the per-frame collision flags the mod reports. Resolved once
    // per tick (see currentFrame).
    private GravityFrame frame = GravityFrame.DEFAULT;
    // The entity's local gravity this tick, from Camafy (every source: a Gravity Unbound field,
    // the forge:entity_gravity attribute, VS Genesis's no-gravity space, Ad Astra's planets and
    // orbits): as a world acceleration in blocks per tick squared ((0, -0.08, 0) under vanilla
    // gravity, zero in space) and on the weight scale in m/s^2 ((0, -9.81, 0), zero in space),
    // and whether nothing pulls it at all. Read once per tick with the frame.
    private Vec3 gravityBpt2 = new Vec3(0.0, -0.08, 0.0);
    private Vec3 gravityMps2 = new Vec3(0.0, -9.81, 0.0);
    private double gravityStrength = 1.0;
    private boolean weightless = false;

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
    // Consecutive steps spent on the ground (0 while airborne): a landing is
    // only a landing if the entity was in the air just before it.
    private int groundedTicks = 0;

    // Ship feedback guard: the ship whose deck momentum last threw this entity
    // (ShipDeckMomentum), and when: until it settles, its contacts with that
    // ship push the ship no more (what the ship gave its rider must not come
    // back into it).
    private long thrownByShipId = Long.MIN_VALUE;
    private long thrownAtTick = Long.MIN_VALUE;

    // Which ship the entity stood on as of each server tick (noteStandingOn):
    // this tick's answer, and the last ship it stood on BEFORE this tick. A
    // contact with a ship it was already on is a rider's, not a newcomer's.
    private long standingShipNow = Long.MIN_VALUE;
    private long standingNoteTick = Long.MIN_VALUE;
    private long stoodShipId = Long.MIN_VALUE;
    private long stoodTick = Long.MIN_VALUE;
    // Since when (game time) the entity has stood on standingShipNow without a break: a body that only got onto a ship
    // as it ran into it (a car's wheels over a low raft's edge) has stood on it for a tick or two.
    private long standingSinceTick = Long.MIN_VALUE;

    // The velocity (world, m/s) a two-body collision with a ship left this
    // entity with, and the contact normal: while the ship's own reaction is
    // still on its way to VS's physics thread, the hull (not yet moving) can
    // block the entity again; that re-block must not eat the velocity.
    private long shipExchangeShipId = Long.MIN_VALUE;
    private long shipExchangeUntil = Long.MIN_VALUE;
    private Vec3 shipExchangeVelocity = Vec3.ZERO;
    private Vec3 shipExchangeNormal = Vec3.ZERO;

    private boolean isDamageImmune = false;
    private boolean hasTeleported = false;
    // A position jump the server or this client set (teleportTo, a server-set player position, a correction): the
    // speed history forgets it, as for hasTeleported, but without the teleport's damage immunity (a player the server
    // corrects often - lag, a ship deck - must not go unhurt).
    private boolean jumped = false;
    // Ticks left in which no move is judged after a position jump or teleport (jumpedLately): the move that follows
    // one is the jump, whichever path reads it (2026-10-04: a player teleported beside a ship was struck at 463 m/s).
    private int jumpWindow = 0;
    private static final int JUMP_WINDOW_TICKS = 6;
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

    // clientCarryMps / clientPushMps: what the CLIENT's Valkyrien Skies added to that tick's
    // position (the carry of a deck it stands on, a hull's shove), as the client measured it
    // with the ship poses IT drew. The server's own ship poses run ahead of the client's (the
    // round trip plus VS's interpolation), so subtracting the server's carry from positions the
    // client measured left a residual velocity turning with the ship - 5 to 15 m/s on a rotor
    // at 64 RPM - that read as the player's own.
    private record MoveSample(Vec3 position, @Nullable Vec3 clientVelocityMps,
                              boolean horizontalCollision, boolean verticalCollision,
                              boolean onGround, float yaw,
                              @Nullable Vec3 clientCarryMps, @Nullable Vec3 clientPushMps) {
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
    @Nullable
    private Vec3 pendingCarryMps = null;
    @Nullable
    private Vec3 pendingPushMps = null;
    private boolean pendingHorizontalCollision = false;
    private boolean pendingVerticalCollision = false;
    private boolean hasPending = false;

    // DIZZINESS: sustained turning - the view spun round and round, or the
    // heading of a fast run swung in circles. The turning rate is smoothed over
    // a second and only turning past the threshold builds a dose, which drains
    // again when the turning stops (SpinDizziness): a quick 180 or a flick of
    // the mouse never gets there, spinning for a while does. Drives the nausea
    // effect alone (no vignette): spinning makes you dizzy, not black out.
    // Per-tick input is capped so a single frame-convention yaw jump (Gravity
    // Unbound moving a frame twist into yaw) cannot register.
    private static final double SPIN_HEADING_MIN_SPEED_SQR = 3.0 * 3.0;
    private double avgSpinDegPerTick = 0.0;
    // Dizziness built up: degrees turned beyond the threshold rate (see tickSpin).
    private double spinDoseDeg = 0.0;
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
    /** The pose last tick and the tick it last changed: a carried body's pose change is no motion (see tick). */
    @Nullable
    private net.minecraft.world.entity.Pose lastPose = null;
    // "long ago", and far enough from the edge that tickCount - poseChangedTick can't wrap round to a negative
    // (Integer.MIN_VALUE did: every body that never changed pose counted as having just changed it)
    private int poseChangedTick = Integer.MIN_VALUE / 2;
    /** The vehicle last seen (-1 none) and the game time it changed: getting in or out counts as a pose change. */
    private int lastVehicleId = Integer.MIN_VALUE;
    private long rideChangedGameTime = Long.MIN_VALUE / 2;
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

        // A body at rest (idleFastPaths): it hasn't moved since its last step, nothing is pending on it and its
        // measurements have settled, so stepping it again would only read the same zeros. Its last step stands; the
        // tick after it moves again measures from where it rested. No fresh sample: nothing to judge this tick.
        if (net.camacraft.fullstop.common.physics.PerfSwitches.idleFastPaths() && resting(entity)) {
            freshSample = false;
            steppedPerSample = false;
            steppedTick = entity.tickCount;
            return;
        }

        frame = currentFrame(entity);
        // one gravity read: the m/s^2 vector is the pull times the strength, and the other two are the same vector
        // on the other scales (Camafy derives all three from the same strength and pull)
        gravityMps2 = Gravity.accelerationMps2(entity);
        gravityStrength = gravityMps2.length() / net.camacraft.camafy.util.math.Units.STANDARD_GRAVITY;
        weightless = gravityStrength < Gravity.WEIGHTLESS_BELOW;
        gravityBpt2 = gravityStrength == 0.0 ? Vec3.ZERO
                : gravityMps2.scale(Gravity.VANILLA_ACCEL / net.camacraft.camafy.util.math.Units.STANDARD_GRAVITY);

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
                            entity.onGround(), entity.getYRot(), null, null));
                }
                stepSamples(entity, ride);
            }
        } else {
            freshSample = true;
            step(entity, entity.position(), clientVelocityMps,
                    entity.horizontalCollision, entity.verticalCollision,
                    entity.onGround(), entity.getYRot(), null, null);
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

    /**
     * A server-moved body at rest on the ground with nothing pending: its position is exactly where its last step
     * left it (vanilla doesn't move a body whose motion has decayed below 1e-7 and zeroes such motion), its stopping
     * force and felt acceleration have run down, no cooldown or flag is live, no ship carries it and no mod claims it.
     * Players, vehicles and their riders are never "resting" (their steps come from clients or carriers).
     */
    private boolean resting(Entity entity) {
        if (firstTick || entity instanceof Player || entity.isVehicle() || entity.isPassenger()) return false;
        if (!entity.onGround() || entity.horizontalCollision || entity.verticalCollision) return false;
        if (hasTeleported || hasDismounted || jumped || jumpWindow > 0 || isDamageImmune || physicsClaims != 0) return false;
        if (teleportCooldown > 0 || dismountCooldown > 0 || soundCooldown > 0 || waterSkipCooldown > 0 || bounceCooldown > 0) return false;
        if (standingShipNow != Long.MIN_VALUE || !samples.isEmpty()) return false;
        if (decelerationForce != 0.0 || Math.abs(avgAccel) > 1.0e-4 || Math.abs(rawAvgAccel) > 1.0e-4) return false;
        if (velocityMps.lengthSqr() != 0.0) return false;
        Vec3 at = entity.position();
        return at.x == currentPosition.x && at.y == currentPosition.y && at.z == currentPosition.z;
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
                    sample.onGround(), sample.yaw(), sample.clientCarryMps(), sample.clientPushMps());
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
     * @param carryMps          what a ship's carry added to it as the mover's client measured it
     *                          (m/s), or null to use this side's own reading
     * @param shoveMps          what a hull's shove added to it as the client measured it (m/s), or
     *                          null to use this side's own record
     */
    private void step(Entity entity, Vec3 position, @Nullable Vec3 clientVelocityMps,
                      boolean horizontalHit, boolean verticalHit, boolean onGround, float yaw,
                      @Nullable Vec3 carryMps, @Nullable Vec3 shoveMps) {
        tickVelocity(entity, position, clientVelocityMps, horizontalHit, verticalHit, onGround, carryMps, shoveMps);
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
                spinDoseDeg = 0.0;
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
        groundedTicks = onGround ? Math.min(groundedTicks + 1, 1_000_000) : 0;
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
        recordClientDelta(nativeVelocity, horizontalCollision, verticalCollision, null, null);
    }

    /**
     * As above, with what the client's Valkyrien Skies added to this tick's position: the carry
     * of the deck it stands on and a hull's shove (m/s, world; see {@link MoveSample}).
     */
    public void recordClientDelta(Vec3 nativeVelocity, boolean horizontalCollision, boolean verticalCollision,
                                  @Nullable Vec3 carryMps, @Nullable Vec3 shoveMps) {
        sampling = true;
        if (hasPending) {
            pushSample(entity.position(), entity.onGround(), entity.getYRot());
        }
        pendingVelocityMps = nativeVelocity.scale(20);
        pendingCarryMps = carryMps;
        pendingPushMps = shoveMps;
        pendingHorizontalCollision = horizontalCollision;
        pendingVerticalCollision = verticalCollision;
        hasPending = true;
    }

    /** Whether this entity's client measures it (it sends FullStop's per-tick report): a player with FullStop. */
    public boolean isClientSampled() {
        return sampling;
    }

    /** Server side: a move packet was applied - one client tick of motion ended at {@code position}. */
    public void recordMoveSample(Vec3 position, boolean onGround, float yaw) {
        sampling = true;
        pushSample(position, onGround, yaw);
    }

    /** What the player's own client said about the ground in its last move packet, and when (server game time). */
    private boolean clientOnGround;
    private long clientGroundTick = Long.MIN_VALUE;

    /** A player's move packet: whether its client had it on the ground (the server's own replay may differ). */
    public void noteClientGround(boolean onGround, long gameTime) {
        this.clientOnGround = onGround;
        this.clientGroundTick = gameTime;
    }

    /** Whether the player's client said it was off the ground in a move packet within the last {@code ticks} ticks. */
    public boolean clientSaysAirborne(long gameTime, int ticks) {
        return !clientOnGround && gameTime - clientGroundTick <= ticks;
    }

    private void pushSample(Vec3 position, boolean onGround, float yaw) {
        Vec3 velocity = hasPending ? pendingVelocityMps : null;
        Vec3 carry = hasPending ? pendingCarryMps : null;
        Vec3 shove = hasPending ? pendingPushMps : null;
        boolean horizontal = hasPending && pendingHorizontalCollision;
        boolean vertical = hasPending && pendingVerticalCollision;
        hasPending = false;
        pendingVelocityMps = null;
        pendingCarryMps = null;
        pendingPushMps = null;
        if (samples.size() >= MAX_QUEUED_SAMPLES) {
            samples.pollFirst();
        }
        samples.addLast(new MoveSample(position, velocity, horizontal, vertical, onGround, yaw, carry, shove));
    }

    /**
     * The collide wrapper saw a Valkyrien Skies hull shove this entity by {@code pushBpt}
     * (world, blocks) during its move this tick (see {@link #shipPushBpt}).
     */
    public void recordShipPush(Entity entity, Vec3 pushBpt) {
        if (shipPushTick != entity.tickCount) {
            shipPushBpt = Vec3.ZERO;
            shipPushTick = entity.tickCount;
        }
        shipPushBpt = shipPushBpt.add(pushBpt);
    }

    /** How far hulls shoved the entity during this tick's move, as a velocity (m/s, world); zero if none. */
    public Vec3 shipPushMps(Entity entity) {
        return shipPushTick == entity.tickCount ? shipPushBpt.scale(20.0) : Vec3.ZERO;
    }

    /** Client side: a hull's kick for the local player, added at the end of this tick (see ClientShipContacts). */
    public void queueShipKick(Vec3 deltaBpt) {
        pendingShipKickBpt = pendingShipKickBpt.add(deltaBpt);
    }

    /** Client side: the queued hull kick (blocks per tick, world), cleared. */
    public Vec3 takeShipKick() {
        Vec3 kick = pendingShipKickBpt;
        pendingShipKickBpt = Vec3.ZERO;
        return kick;
    }

    /** Client side: whether this client reported a contact with ship {@code shipId} so recently it reports none again yet. */
    public boolean isClientContactCooling(long shipId, long gameTime) {
        return shipId == clientContactShipId && gameTime < clientContactUntil;
    }

    public void markClientContact(long shipId, long gameTime, int ticks) {
        clientContactShipId = shipId;
        clientContactUntil = gameTime + ticks;
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

        double spin = SpinDizziness.input(Math.max(yawRate, headingRate));
        avgSpinDegPerTick = SpinDizziness.rate(avgSpinDegPerTick, spin);
        if (FullStopConfig.SERVER_SPEC.isLoaded()) {
            spinDoseDeg = SpinDizziness.dose(spinDoseDeg, avgSpinDegPerTick, GForceThresholds.spinNauseaLevel(living),
                    FullStopConfig.SERVER.spinNauseaTurns.get());
        }
    }

    /** Smoothed turning rate, degrees per tick (see {@link #tickSpin}). */
    public double getSpinRate() {
        return avgSpinDegPerTick;
    }

    /** Dizziness built up from spinning, degrees turned beyond the threshold rate (see {@link SpinDizziness}). */
    public double getSpinDose() {
        return spinDoseDeg;
    }

    /**
     * Server side: this tick, the rider sits on a ship and its seat accelerates at
     * {@code mps2} (world, m/s^2); its g-force is felt from that, not from its measured motion.
     */
    public void setSeatAcceleration(Entity entity, Vec3 mps2) {
        seatAccelerationMps2 = mps2;
        seatAccelerationTick = entity.tickCount;
    }

    /** Whether a seat acceleration was set for this tick (see {@link #setSeatAcceleration}). */
    public boolean hasSeatAcceleration(Entity entity) {
        return seatAccelerationTick == entity.tickCount;
    }

    /**
     * Client side: the server's g-force reading for the local player seated on a ship, in force
     * for {@code ticks} ticks from {@code gameTime} (the vignette, blackout and muffle read it).
     */
    public void setRemoteGForce(double avg, double raw, long gameTime, int ticks) {
        remoteAvgAccel = avg;
        remoteRawAvgAccel = raw;
        remoteGForceUntil = gameTime + ticks;
    }

    /** Smooths the felt acceleration (g-force) that drives blackout/vignette/audio effects. */
    private void tickGForce(LivingEntity living) {
        if (living.level().isClientSide && living.level().getGameTime() <= remoteGForceUntil) {
            // seated on a ship: the server felt it from the seat's physics (see setSeatAcceleration)
            avgAccel = remoteAvgAccel;
            rawAvgAccel = remoteRawAvgAccel;
            return;
        }
        // The entity's own gravity (Camafy: every source, along its pull), in the units of the
        // acceleration it is compared with: the measured one is m/s per tick in Minecraft's scale
        // (0.08 blocks per tick squared), a seat's is real m/s^2 (1 g = 9.81), each per tick.
        // Zero in space: there the pure acceleration is what is felt.
        boolean seated = seatAccelerationTick == living.tickCount;
        Vec3 gravityVector = seated ? gravityMps2.scale(0.05) : gravityBpt2.scale(20.0);
        double baseline = gravityVector.length();

        // Proper (felt) acceleration relative to freefall, minus the 1g baseline
        // everyone feels at rest. Continuous by construction: the old hard cutoffs
        // (zero the Y term when grounded / while falling) flipped on and off from
        // tick to tick at speed, making the blackout and audio muffle flash.
        // Seated on a ship: the seat's acceleration (m/s^2, as m/s per tick).
        Vec3 felt = seated ? seatAccelerationMps2.scale(0.05) : acceleration;
        double gForceMagnitude = Math.max(felt.subtract(gravityVector).length() - baseline - GFORCE_NOISE_FLOOR, 0.0);

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
                              boolean horizontalHit, boolean verticalHit, boolean onGround,
                              @Nullable Vec3 clientCarryMps, @Nullable Vec3 clientShoveMps) {
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
            prevFrameMotionMps = Vec3.ZERO;
            prevPrevFrameMotionMps = Vec3.ZERO;
            lastMeasuredSpeed = spawnVelocityMps.length();
            firstTick = false;
        } else {
            previousPosition = currentPosition;
        }

        if (hasTeleported || jumped) {
            // a jump holds until a step takes it: a player's tick can pass with no client sample (held), and the first
            // sample after it must not be measured from before the jump
            jumped = false;
            currentPosition = position;
            previousPosition = currentPosition;
            velocityMps = Vec3.ZERO;
            prevVelocityMps = Vec3.ZERO;
            prevPrevVelocityMps = Vec3.ZERO;
            frameMotionMps = Vec3.ZERO;
            prevFrameMotionMps = Vec3.ZERO;
            prevPrevFrameMotionMps = Vec3.ZERO;
            pushMps = Vec3.ZERO;
            return;
        }

        prevPrevVelocityMps = prevVelocityMps;
        prevVelocityMps = velocityMps;
        prevPrevFrameMotionMps = prevFrameMotionMps;
        prevFrameMotionMps = frameMotionMps;

        currentPosition = position;

        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20);

        // Remove world-frame motion a physics mod imposed by carrying the entity
        // (a Valkyrien Skies ship): the raw position delta on a moving/rotating
        // ship is dominated by the ship, not the entity's own movement. Done
        // before the discontinuity guard so smooth ship motion never trips it.
        // A client-measured mover's positions come from its client, drawn with
        // that client's ship poses: its own carry reading goes with them.
        // Likewise a hull's shove (VS moving the entity out of a hull that ran
        // into it): position, not the entity's velocity.
        frameMotionMps = clientCarryMps != null ? clientCarryMps : ShipCompat.shipDragVelocityMps(entity);
        pushMps = clientShoveMps != null ? clientShoveMps : shipPushMps(entity);
        actualVelocity = actualVelocity.subtract(frameMotionMps).subtract(pushMps);
        // A rider in one of a ship's seats moves only with its seat: VS puts it where the seat is every tick, so
        // all of its motion is the ship's. On a rotor that seat swings round at up to hundreds of m/s, and VS's
        // uneven physics steps made it read as a rider flying past the speed of sound and stopping dead, over and
        // over: sonic booms, and "impacts" that hurt it and threw it out of its seat. What the seat does to its
        // rider is felt through the seat's acceleration (g-force) and a crash through ShipDeckMomentum.
        if (ShipCompat.seatShip(entity) != null) {
            frameMotionMps = frameMotionMps.add(actualVelocity);
            actualVelocity = Vec3.ZERO;
        }
        // The tick a body a ship carries (or its plating holds) crouches or stands up, the server puts it in two
        // places - its client's move packet lands it where that client's ship pose had it, then VS's ship-relative
        // setPos moves it on with the ship - and the jump between the two reads as its own motion at the ship's
        // speed: a player crouching in a room on a plated ship doing 30 m/s was billed a 30 m/s strike on the
        // room's wall (7 to 17 damage per crouch, the owner's report of 2026-09-30). No pose change moves a body
        // that fast. On that tick and the next, a carried body's own motion is nothing (its carry stays the
        // ship's), and the jump is dropped rather than folded into the carry, which the ship-hit judgement reads.
        net.minecraft.world.entity.Pose pose = entity.getPose();
        if (lastPose != null && pose != lastPose) poseChangedTick = entity.tickCount;
        lastPose = pose;
        // Getting into or out of a vehicle is the same kind of jump (the seat, then the dismount spot beside it):
        // a player getting out of a car on the plating of a ship cruising at 300 m/s was read as a 12 m/s jolt of
        // the deck under them and thrown off with the deck's motion, at 350 m/s (2026-09-30).
        noteVehicle(entity);
        if (poseChangedLately(entity, 1) && ridesShip(entity)) {
            actualVelocity = Vec3.ZERO;
        }
        // The same double placement seen from the motion itself, since the server's pose can trail the packet that
        // moved the body by a tick: a carried body that was all but still and now reads as moving by the ship's carry
        // over again (its position went with the ship twice this tick), or by exactly its opposite (not at all),
        // was put in two places. Measured on a plated ship at 60 RPM: own velocity 20.4 m/s, carry 20.4 m/s, the
        // same way, for one tick at each crouch. Nothing a body does by itself matches the ship's carry like that.
        double carry = frameMotionMps.length();
        if (carry > 2.0 && actualVelocity.lengthSqr() > 25.0 && prevVelocityMps.lengthSqr() < 25.0 && ridesShip(entity)
                && (actualVelocity.distanceTo(frameMotionMps) < 0.35 * carry
                || actualVelocity.distanceTo(frameMotionMps.scale(-1.0)) < 0.35 * carry)) {
            actualVelocity = Vec3.ZERO;
        }

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
            prevFrameMotionMps = Vec3.ZERO;
            prevPrevFrameMotionMps = Vec3.ZERO;
            pushMps = Vec3.ZERO;
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

        if (entity instanceof LivingEntity && onGround) {
            // One tick of the entity's own gravity (m/s): 1.6 under vanilla gravity, less on the
            // Moon, nothing in space (Camafy's reading, every source).
            double gravityStep = gravityBpt2.length() * 20 + 1.0e-9;
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
            if (vy < 0 && vy >= -2.0 * Math.min(1.0, gravityStrength)) {
                velocityMps = frame.horizontalPart(velocityMps);
            }
        }
    }

    private void tickSpeed(boolean onGround) {
        // Same frame-motion correction as tickVelocity: the stopping force must be
        // measured relative to the ship the entity rides, not the world.
        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20).subtract(frameMotionMps).subtract(pushMps);

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
        if (jumpWindow > 0) jumpWindow--;
        if (hasTeleported) {
            jumpWindow = JUMP_WINDOW_TICKS;
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
            dismountCooldown = net.camacraft.fullstop.server.physics.interaction.EntityRiding.dismountCooldownTicks();
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

    /**
     * {@link #getPreImpactScaledVelocity()} in the WORLD frame (m/s): the same tick's velocity
     * with the motion a ship added by carrying the entity put back. What a collision with a
     * ship's hull is judged by, against that hull's own velocity.
     */
    public Vec3 getPreImpactWorldVelocity() {
        return prevPrevVelocityMps.lengthSqr() > prevVelocityMps.lengthSqr()
                ? prevPrevVelocityMps.add(prevPrevFrameMotionMps) : prevVelocityMps.add(prevFrameMotionMps);
    }

    /** This tick's velocity in the WORLD frame (m/s): own velocity plus what a ship added. */
    public Vec3 getCurrentWorldVelocity() {
        return velocityMps.add(frameMotionMps);
    }

    /** Consecutive steps the entity has spent on the ground; 0 while airborne. */
    public int getGroundedTicks() {
        return groundedTicks;
    }

    /**
     * Records which ship (id, or {@code Long.MIN_VALUE} for none) the entity stands on this
     * server tick. Called once per tick by the physics dispatch, before impacts are judged.
     */
    public void noteStandingOn(long shipId, long gameTime) {
        if (gameTime == standingNoteTick) {
            standingShipNow = shipId;
            return;
        }
        if (standingShipNow != Long.MIN_VALUE) {
            stoodShipId = standingShipNow;
            stoodTick = standingNoteTick;
        }
        if (shipId == Long.MIN_VALUE || shipId != standingShipNow || gameTime - standingNoteTick > 1) {
            standingSinceTick = gameTime;
        }
        standingShipNow = shipId;
        standingNoteTick = gameTime;
    }

    /**
     * Whether the entity has stood on ship {@code shipId} without a break since at least {@code ticks} ticks before
     * {@code gameTime}: it was aboard before whatever it is meeting now, not something that only got onto the ship as
     * it ran into it.
     *
     * @since round 2026-10-10
     */
    public boolean aboardSince(long shipId, long gameTime, int ticks) {
        return shipId == standingShipNow && standingSinceTick != Long.MIN_VALUE && gameTime - standingSinceTick >= ticks;
    }

    /**
     * Whether the entity stood on ship {@code shipId} on some tick BEFORE this one, at most
     * {@code window} ticks ago: it is that ship's rider, not something arriving at it.
     */
    public boolean stoodOnBefore(long shipId, long gameTime, int window) {
        return shipId == stoodShipId && stoodTick < gameTime && gameTime - stoodTick <= window;
    }

    /** A ship's deck momentum just threw this entity (see {@link #isShipFeedbackBlocked}). */
    public void markThrownBy(long shipId, long gameTime) {
        this.thrownByShipId = shipId;
        this.thrownAtTick = gameTime;
    }

    /**
     * Whether this entity's contacts with ship {@code shipId} must not push that ship: the ship
     * itself threw it (deck momentum) and it has not settled since - it has not stood on the
     * ground for {@link #THROW_SETTLE_TICKS} ticks, and at most {@link #THROW_FEEDBACK_TICKS}
     * have passed. What a ship did to its rider never comes back into the same ship.
     */
    public boolean isShipFeedbackBlocked(long shipId, long gameTime) {
        if (shipId != thrownByShipId) return false;
        long since = gameTime - thrownAtTick;
        if (since < 0 || since > THROW_FEEDBACK_TICKS) return false;
        return since <= THROW_SETTLE_TICKS || groundedTicks < THROW_SETTLE_TICKS;
    }

    private static final int THROW_SETTLE_TICKS = 10;
    private static final int THROW_FEEDBACK_TICKS = 60;

    /** Remembers the velocity a two-body collision with {@code shipId} left the entity with. */
    public void markShipExchange(long shipId, long gameTime, int ticks, Vec3 worldVelocityMps, Vec3 normal) {
        this.shipExchangeShipId = shipId;
        this.shipExchangeUntil = gameTime + ticks;
        this.shipExchangeVelocity = worldVelocityMps;
        this.shipExchangeNormal = normal;
    }

    /** The velocity (world, m/s) the last collision with {@code shipId} left, while it still counts; else null. */
    @Nullable
    public Vec3 shipExchangeVelocity(long shipId, long gameTime) {
        return shipId == shipExchangeShipId && gameTime < shipExchangeUntil ? shipExchangeVelocity : null;
    }

    /** The contact normal of {@link #shipExchangeVelocity} (world, from the hull toward the entity). */
    public Vec3 shipExchangeNormal() {
        return shipExchangeNormal;
    }

    /** The ship of the collision {@link #shipExchangeVelocity} remembers, or {@code Long.MIN_VALUE}. */
    public long shipExchangeShip(long gameTime) {
        return gameTime < shipExchangeUntil ? shipExchangeShipId : Long.MIN_VALUE;
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

    /** Whether the entity's pose changed within the last {@code ticks} ticks (0: this tick). */
    public boolean poseChangedLately(Entity entity, int ticks) {
        return entity.tickCount - poseChangedTick <= ticks;
    }

    /**
     * As {@link #poseChangedLately}, and also true from the moment the entity got into or out of a vehicle, before
     * this capability has ticked to note it: the ship physics may judge the entity in the same server tick, ahead
     * of its tick.
     */
    public boolean rideOrPoseChangedLately(Entity entity, int ticks) {
        int vehicleId = entity.getVehicle() == null ? -1 : entity.getVehicle().getId();
        return poseChangedLately(entity, ticks)
                || entity.level().getGameTime() - rideChangedGameTime <= ticks
                || (lastVehicleId != Integer.MIN_VALUE && vehicleId != lastVehicleId);
    }

    /** Notes the vehicle the entity rides; a change is a pose change (see tick). Cheap, called wherever the entity is refreshed. */
    private void noteVehicle(Entity entity) {
        int vehicleId = entity.getVehicle() == null ? -1 : entity.getVehicle().getId();
        if (lastVehicleId != Integer.MIN_VALUE && vehicleId != lastVehicleId) {
            poseChangedTick = entity.tickCount;
            rideChangedGameTime = entity.level().getGameTime();
        }
        lastVehicleId = vehicleId;
    }

    /**
     * Whether a ship carries this body along: Valkyrien Skies drags it with the ship it stands on (or did
     * within the last second), or Gravity Unbound's plating or field holds it to the ship.
     */
    public static boolean ridesShip(Entity entity) {
        return ShipCompat.carryingShip(entity) != null || net.camacraft.camafy.compat.vs.ShipEntities.isCarried(entity)
                || Gravity.isAnchored(entity) || Gravity.ridesShipField(entity);
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
        noteVehicle(entity);
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
        if (value) this.jumpWindow = JUMP_WINDOW_TICKS;
    }

    /**
     * Whether the entity's position was set (a teleport, a server correction, Valkyrien Skies putting it back or on a
     * ship) within the last few ticks: the move that follows is that jump, not motion, and no path judges it (impacts,
     * strikes on blocks and ship blocks, a client's hull contacts). Without it a player teleported beside a ship was
     * judged by the hull sweep's strike at 463 m/s and killed (620 damage, harness car_spin60, 2026-10-04).
     */
    public boolean jumpedLately() {
        return jumpWindow > 0;
    }

    /**
     * The entity's position was set, not travelled to (a teleport no event announced, a server-set player position):
     * the next step's motion is forgotten, as for a teleport, without its damage immunity.
     */
    public void markJump() {
        this.jumped = true;
        this.jumpWindow = JUMP_WINDOW_TICKS;
        // the client ticks queued before it were measured where the entity was: the next step starts from the new place
        samples.clear();
        hasPending = false;
        pendingVelocityMps = null;
        pendingCarryMps = null;
        pendingPushMps = null;
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

    /** The ship that last collided with this entity (either way) while that still counts, or {@code Long.MIN_VALUE}. */
    public long recentShipHit(long gameTime) {
        return gameTime < shipHitCooldownUntil ? lastShipHitId : Long.MIN_VALUE;
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

    /**
     * Whether this tick's impact is a FALL: mostly along the pull of gravity, and something
     * actually pulls the entity. In zero gravity the frame stays upright, so a feet-first bump
     * into a floor is "mostly downward" without being a fall: no fall damage type, no Feather
     * Falling, no sprain, the horizontal threshold. Body-relative uses (which armour takes it,
     * the crush factor) keep {@link #isMostlyDownward}.
     */
    public boolean isFall() {
        return !weightless && isMostlyDownward();
    }

    /** Whether nothing pulls the entity this tick (Camafy: strength below {@code Gravity.WEIGHTLESS_BELOW}). */
    public boolean isWeightless() {
        return weightless;
    }

    /** The entity's local gravity this tick as a multiple of vanilla's (Camafy's strength). */
    public double getGravityStrength() {
        return gravityStrength;
    }

    public void resetVelocity() {
        this.velocityMps = Vec3.ZERO;
        this.prevVelocityMps = Vec3.ZERO;
        this.prevPrevVelocityMps = Vec3.ZERO;
        this.frameMotionMps = Vec3.ZERO;
        this.prevFrameMotionMps = Vec3.ZERO;
        this.prevPrevFrameMotionMps = Vec3.ZERO;
        this.pushMps = Vec3.ZERO;
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

    private boolean cachedDead = false;
    private int deadRefreshIn = 0;

    /**
     * Cached {@link LivingEntity#isDeadOrDying()} (a locked synched-data read of the health), refreshed once a second;
     * a body whose death animation has begun ({@code deathTime}, a plain field) reads dead at once.
     */
    public boolean isDeadCached(LivingEntity living) {
        if (living.deathTime > 0) return true;
        if (--deadRefreshIn <= 0) {
            cachedDead = living.isDeadOrDying();
            deadRefreshIn = NO_AI_REFRESH_TICKS;
        }
        return cachedDead;
    }

    // The honey/soul-sand wall field (BlockPhasing): the block position around which no slowing block was found, and
    // when, so a body that stays within that block isn't scanned again for half a second.
    private long slowingClearPos = Long.MIN_VALUE;
    private int slowingClearTick = Integer.MIN_VALUE;

    /** Whether no slowing block was found around {@code blockPos} within the last {@code ticks} ticks. */
    public boolean slowingClearAt(long blockPos, int now, int ticks) {
        return slowingClearPos == blockPos && now - slowingClearTick < ticks;
    }

    public void noteSlowingClear(long blockPos, int now) {
        slowingClearPos = blockPos;
        slowingClearTick = now;
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
        private LazyOptional<FullStopCapability> lazyHandler = LazyOptional.of(this::createCapability);

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

        /**
         * The capability, handed out again after a revive. Forge invalidates an entity's
         * capabilities every time it is removed, a player changing dimension included
         * ({@code removePlayerImmediately}), and {@code revive()}s the player straight after; an
         * invalidated LazyOptional never comes back, so with a single one the server lost this
         * capability for a player after any dimension change (a {@code /tp} into another
         * dimension, VS Genesis's space) until the player relogged, and FullStop measured and
         * judged nothing for it. The entity's own provider hands out nothing while its
         * capabilities are invalid, so a fresh LazyOptional here is only reached after the
         * revive; the state behind it is the same object throughout.
         */
        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            if (cap == DELTAV_CAP) {
                if (!lazyHandler.isPresent()) lazyHandler = LazyOptional.of(this::createCapability);
                return lazyHandler.cast();
            }
            return LazyOptional.empty();
        }
    }
}
