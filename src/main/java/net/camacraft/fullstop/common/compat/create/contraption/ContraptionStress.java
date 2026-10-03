package net.camacraft.fullstop.common.compat.create.contraption;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryContraption;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.compat.create.CreateCompat;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * What a contraption's hits on the terrain and on ships do to the contraption itself
 * ({@code contraptionImpactStress}): Create's own stress.
 *
 * <p><b>Contraptions on a kinetic network</b> (a mechanical or windmill bearing, a piston, a rope pulley, a
 * gantry riding its shaft): every hit loads the block that drives it with an extra stress impact,
 * {@code contraptionStressPerImpact} SU per FullStop energy unit of the hit, which fades by a tenth each tick.
 * It is added where Create sums a network's stress, {@code KineticBlockEntity.calculateStressApplied}
 * ({@code KineticStressMixin}), so Create's own accounting carries it: the network's stress rises on a
 * stressometer, and a load past the network's capacity overstresses it, which stops everything on it exactly as
 * Create stops any overstressed network (and shows it so in the goggles).
 *
 * <p>A <b>jam</b> - a hit nothing gave way to: bedrock, a block too tough to break this hit (it may crack), a ship far
 * heavier than the contraption - <b>stalls</b> it where it met the obstacle, held: the contraption is set back (or on)
 * to the pose where its block's face meets the obstacle and stalled the way Create stalls a contraption whose drill
 * is still mining ({@link StallControl}: it holds still, and the clients are sent exactly where), and its network is
 * loaded with twice its capacity, so it stays overstressed. It stays so for as long as its drive would still turn
 * or push it into the obstacle and the obstacle is still there: stopping the motor (or anything that drops the
 * network's speed to 0) or reversing it lets go, as does the obstacle going (mined, replaced, a ship moving away).
 * Then the load is dropped at once and the network runs again; a drive started again into the same obstacle hits it
 * afresh (at its new speed: a slow restart only stalls again). While it is held nothing grinds: the contraption isn't
 * moving. A bearing whose motor stops sets its arm down as blocks (Create's "place when stopped") at the last quarter
 * turn it passed ({@link #settleAngle}), never through the obstacle. A gantry sets itself down against the obstacle as
 * soon as its shaft stops turning (Create's own gantry stop), so its jam ends there with the contraption gone.
 *
 * <p><b>Minecart contraptions and trains</b> have no network: a hit takes its energy out of their motion
 * ({@code v' = v sqrt(1 - E_hit / E_motion)}), and a jam stops them dead.
 *
 * <p>Server only. The extra load lives here, weakly keyed by the block entity, so nothing of it is saved: an
 * unloaded or rebuilt network simply comes back without it.
 */
public final class ContraptionStress {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The share of an impact load left after each tick (about a second to fade to a tenth). */
    static final double DECAY = 0.9;
    /** A load below this many SU is forgotten. */
    private static final double FORGET_SU = 0.5;
    /** A jam's load on top of twice the network's capacity (SU), so even an empty network overstresses. */
    private static final double JAM_EXTRA_SU = 256.0;

    /** The impact load on each driving block entity (server thread only). */
    private static final Map<KineticBlockEntity, Load> LOADS = new WeakHashMap<>();
    /** The last few hits' stress, for the game tests and the harness. */
    private static final Map<KineticBlockEntity, Double> PEAK = new WeakHashMap<>();

    /** A load held on a block while something refreshes it every tick: a body wedged in its gears (SU per RPM). */
    private record HeldImpact(double perRpm, long tick) {}

    /** The held loads on each block, by what holds them (server thread only; weak both ways). */
    private static final Map<KineticBlockEntity, Map<Object, HeldImpact>> HELD_IMPACTS = new WeakHashMap<>();
    /** A held load not refreshed for this many ticks is dropped. */
    private static final int HELD_TICKS = 2;

    /** Ticks after a stall begins that the drive's state is sent to the clients again (once they hold the stall too). */
    private static final int RESEND_TICKS = 3;
    /** How far a ship's block may move from where a contraption jammed on it before the jam lets go (blocks). */
    private static final double SHIP_MOVED_OFF = 0.3;

    private static final class Load {
        double su;
        /** The jam this drive is held in, or null. */
        @Nullable
        Stall stall;
        long resendAt = Long.MIN_VALUE;
    }

    /**
     * What a jammed contraption is pressed against: a world cell (or, with {@code shipId}, a ship's cell in its
     * shipyard), the block there when it jammed, and for a ship where that cell's centre was in the world then.
     * {@code retry}: it held only because the level had broken all the blocks it may in one tick
     * ({@code terrainMaxBlocksPerTick}); such a jam lets go after {@value #RETRY_TICKS} ticks so the hit is judged again.
     */
    public record Obstacle(BlockPos cell, @Nullable Long shipId, Block block, @Nullable Vec3 shipCellWorld, boolean retry) {}

    /** Ticks a jam held only by the break budget waits before the hit is judged again. */
    static final int RETRY_TICKS = 2;

    /** A jam held: the contraption, the way its drive turned it into the obstacle (the sign of its speed), the obstacle. */
    private record Stall(WeakReference<AbstractContraptionEntity> contraption, int sign, Obstacle obstacle, long since) {}

    /** The contraptions held stalled right now (server thread only). */
    private static final Set<AbstractContraptionEntity> HELD = Collections.newSetFromMap(new WeakHashMap<>());

    /** Where a bearing's contraption came to rest against its obstacle (its angle) and which way its drive turned it. */
    private record Pressed(int sign, float angle) {}

    /** Every bearing contraption pressed against an obstacle, kept after the jam lets go until it turns (server thread only). */
    private static final Map<AbstractContraptionEntity, Pressed> PRESSED = new WeakHashMap<>();

    private static Field bearingAngle;
    private static Field bearingPrevAngle;
    private static boolean bearingAngleFailed;

    /** What drives a contraption: the kinetic block whose network it loads, or the cart or train it rides. */
    public record Drive(@Nullable KineticBlockEntity kinetic, @Nullable AbstractMinecart cart, @Nullable Train train) {}

    private static Field controllerPos;
    private static boolean controllerPosFailed;

    private ContraptionStress() {
    }

    /** Whether hits load the contraption's network (or slow its cart or train): Create and {@code contraptionImpactStress}. */
    public static boolean enabled() {
        return CreateCompat.active() && SERVER.contraptionImpactStress.get();
    }

    /**
     * The extra stress impact (SU per RPM, as Create counts a block's impact) FullStop puts on {@code be} now; 0
     * on the client and for every block not carrying a hit. Asked by {@code KineticStressMixin}.
     */
    public static float extraPerRpm(KineticBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide || (LOADS.isEmpty() && HELD_IMPACTS.isEmpty())) return 0.0f;
        double perRpm = heldImpact(be);
        Load load = LOADS.get(be);
        if (load != null && load.su > 0.0) perRpm += load.su / Math.max(1.0, Math.abs(be.getTheoreticalSpeed()));
        return (float) perRpm;
    }

    /**
     * A load {@code holder} keeps on {@code be}'s network while it refreshes it every tick: {@code perRpm} stress units
     * per RPM, as Create counts a consumer's impact (a body wedged in the nip of two gears, {@code gearJamStress}). It
     * stays through an overstress (the network stopped, the body still wedged) and is dropped {@value #HELD_TICKS}
     * ticks after the last refresh (the body got out, or died). Server only.
     */
    public static void holdImpact(KineticBlockEntity be, Object holder, double perRpm) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide || be.isRemoved() || !(perRpm > 0.0)) return;
        Map<Object, HeldImpact> held = HELD_IMPACTS.computeIfAbsent(be, k -> new WeakHashMap<>());
        HeldImpact before = held.put(holder, new HeldImpact(perRpm, level.getGameTime()));
        if (before == null || Math.abs(before.perRpm() - perRpm) > 0.01 * perRpm) push(be);
    }

    /** The held loads on {@code be} right now (SU per RPM), 0 without any. */
    public static double heldImpact(KineticBlockEntity be) {
        Map<Object, HeldImpact> held = HELD_IMPACTS.get(be);
        if (held == null || held.isEmpty()) return 0.0;
        double sum = 0.0;
        for (HeldImpact h : held.values()) sum += h.perRpm();
        return sum;
    }

    /** Drops the held loads nobody refreshed lately, and tells their networks. */
    private static void expireHeld(ServerLevel level) {
        if (HELD_IMPACTS.isEmpty()) return;
        long now = level.getGameTime();
        List<KineticBlockEntity> changed = new ArrayList<>();
        Iterator<Map.Entry<KineticBlockEntity, Map<Object, HeldImpact>>> it = HELD_IMPACTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<KineticBlockEntity, Map<Object, HeldImpact>> entry = it.next();
            KineticBlockEntity be = entry.getKey();
            if (be == null || be.isRemoved()) {
                it.remove();
                continue;
            }
            if (be.getLevel() != level) continue;
            if (entry.getValue().values().removeIf(h -> now - h.tick() > HELD_TICKS)) changed.add(be);
            if (entry.getValue().isEmpty()) it.remove();
        }
        for (KineticBlockEntity be : changed) push(be);
    }

    /** The impact load (SU) on {@code be} right now, 0 without one. */
    public static double loadSu(KineticBlockEntity be) {
        Load load = LOADS.get(be);
        return load == null ? 0.0 : load.su;
    }

    /** The largest impact load (SU) {@code be} has carried since it was last asked, then forgotten. */
    public static double takePeak(KineticBlockEntity be) {
        Double peak = PEAK.remove(be);
        return peak == null ? 0.0 : peak;
    }

    /** Whether {@code be} is held in a jam right now (its contraption stalled against an obstacle). */
    public static boolean jammed(KineticBlockEntity be) {
        Load load = LOADS.get(be);
        return load != null && load.stall != null;
    }

    /** Whether FullStop holds {@code c} stalled against an obstacle (asked every tick by {@code ContraptionStallMixin}). */
    public static boolean holds(AbstractContraptionEntity c) {
        return !HELD.isEmpty() && HELD.contains(c);
    }

    /**
     * The angle a bearing's contraption that was held against an obstacle is set down at when Create takes it apart
     * (its motor stopped), asked by {@code ContraptionStallMixin}: the last quarter turn it passed on its way into the
     * obstacle. Create rounds to the nearest quarter turn, which for an arm stopped past the half-way mark is the one
     * through the obstacle, and Create breaks whatever is there to place the arm (or drops the arm's block where it
     * can't, at bedrock). NaN for any other contraption, and for one that has turned since it was held.
     */
    public static float settleAngle(AbstractContraptionEntity c) {
        if (PRESSED.isEmpty() || !(c instanceof ControlledContraptionEntity controlled)) return Float.NaN;
        Pressed pressed = PRESSED.get(c);
        if (pressed == null) return Float.NaN;
        float now = controlled.getAngle(1.0f);
        if (Math.abs(Mth.wrapDegrees(now - pressed.angle())) > 0.01f) {
            PRESSED.remove(c);
            return Float.NaN;
        }
        double quarters = now / 90.0;
        return (float) (90.0 * (pressed.sign() > 0 ? Math.floor(quarters) : Math.ceil(quarters)));
    }

    /** What drives {@code c}, or null when nothing FullStop can load does (a contraption on its own). */
    @Nullable
    public static Drive driveOf(ServerLevel level, AbstractContraptionEntity c) {
        if (c instanceof ControlledContraptionEntity controlled) {
            BlockPos pos = controllerPos(controlled);
            if (pos != null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof KineticBlockEntity kinetic) {
                return new Drive(kinetic, null, null);
            }
            return null;
        }
        if (c instanceof GantryContraptionEntity && c.getContraption() instanceof GantryContraption gantry) {
            // the gantry shaft the carriage's pinion runs on (as GantryContraptionEntity finds it)
            BlockPos shaft = BlockPos.containing(c.getAnchorVec().add(0.5, 0.5, 0.5)).relative(gantry.getFacing().getOpposite());
            if (level.isLoaded(shaft) && level.getBlockEntity(shaft) instanceof KineticBlockEntity kinetic) {
                return new Drive(kinetic, null, null);
            }
            return null;
        }
        if (c instanceof CarriageContraptionEntity carriageEntity) {
            Carriage carriage = carriageEntity.getCarriage();
            if (carriage != null && carriage.train != null) return new Drive(null, null, carriage.train);
            return null;
        }
        if (c instanceof OrientedContraptionEntity && c.getVehicle() instanceof AbstractMinecart cart) {
            return new Drive(null, cart, null);
        }
        return null;
    }

    /**
     * A hit of {@code energyUnits} (FullStop energy units) on {@code drive}: its network takes the stress, or its
     * cart or train loses that much of its motion's energy ({@code contraptionMassKg} moving at its own speed).
     */
    public static void hit(ServerLevel level, @Nullable Drive drive, double energyUnits, double contraptionMassKg) {
        if (drive == null || !enabled() || !(energyUnits > 0.0)) return;
        if (drive.kinetic() != null) {
            addLoad(level, drive.kinetic(), energyUnits * SERVER.contraptionStressPerImpact.get());
        } else {
            slow(drive, energyUnits, contraptionMassKg);
        }
    }

    /**
     * A hit nothing gave way to. A contraption on a kinetic network is stalled where its block meets the obstacle (at
     * fraction {@code contactFraction} of the tick's motion, as {@link ContraptionImpacts#toGlobal} counts it) and
     * held there, its network overstressed, until its drive stops or turns away or the obstacle goes; a cart or a
     * train stops dead.
     */
    public static void jam(ServerLevel level, @Nullable Drive drive, AbstractContraptionEntity c, Obstacle obstacle, double contactFraction) {
        if (drive == null || !enabled()) return;
        KineticBlockEntity be = drive.kinetic();
        if (be != null) {
            if (be.isRemoved() || !be.hasNetwork()) return;
            float theoretical = be.getTheoreticalSpeed();
            if (theoretical == 0.0f) return; // nothing drives it into the obstacle
            Load load = LOADS.computeIfAbsent(be, k -> new Load());
            load.stall = new Stall(new WeakReference<>(c), (int) Math.signum(theoretical), obstacle, level.getGameTime());
            HELD.add(c);
            LAST_RELEASE.remove(be);
            load.su = Math.max(load.su, jamLoad(be));
            load.resendAt = level.getGameTime() + RESEND_TICKS;
            peak(be, load.su);
            pin(c, be, contactFraction);
            if (c instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null) {
                PRESSED.put(c, new Pressed(load.stall.sign(), controlled.getAngle(1.0f)));
            }
            if (c instanceof StallControl stall) stall.fullstop$stall();
            push(be);
            return;
        }
        if (drive.cart() != null) {
            drive.cart().setDeltaMovement(Vec3.ZERO);
            drive.cart().hurtMarked = true;
        } else if (drive.train() != null) {
            drive.train().speed = 0.0;
        }
    }

    private static void addLoad(ServerLevel level, KineticBlockEntity be, double su) {
        if (!(su > 0.0) || be.isRemoved() || !be.hasNetwork()) return;
        Load load = LOADS.computeIfAbsent(be, k -> new Load());
        load.su += su;
        peak(be, load.su);
        push(be);
    }

    private static void peak(KineticBlockEntity be, double su) {
        PEAK.merge(be, su, Math::max);
    }

    /** A cart or train pays a hit out of its motion. */
    private static void slow(Drive drive, double energyUnits, double massKg) {
        double units = EntityStackUtils.kgToUnits(Math.max(massKg, 1.0));
        if (drive.cart() != null) {
            AbstractMinecart cart = drive.cart();
            Vec3 v = cart.getDeltaMovement();
            double speed = v.length(); // blocks per tick
            double motion = 0.5 * units * speed * speed;
            double factor = motion > 1.0e-9 ? Math.sqrt(Math.max(0.0, 1.0 - energyUnits / motion)) : 0.0;
            cart.setDeltaMovement(v.scale(factor));
            cart.hurtMarked = true;
        } else if (drive.train() != null) {
            Train train = drive.train();
            double speed = Math.abs(train.speed); // blocks per tick
            double motion = 0.5 * units * speed * speed;
            double factor = motion > 1.0e-9 ? Math.sqrt(Math.max(0.0, 1.0 - energyUnits / motion)) : 0.0;
            train.speed *= factor;
        }
    }

    /** A jam's load: twice the network's capacity and then some, so it overstresses whatever drives it. */
    private static double jamLoad(KineticBlockEntity be) {
        double capacity = 0.0;
        try {
            KineticNetwork network = be.getOrCreateNetwork();
            if (network != null) capacity = network.calculateCapacity();
        } catch (RuntimeException e) {
            // a network mid-rebuild: the jam's own floor still stops it
        }
        return 2.0 * Math.max(0.0, capacity) + JAM_EXTRA_SU;
    }

    /** Has Create re-sum the network with the block's current load (calculateStressApplied carries it). */
    private static void push(KineticBlockEntity be) {
        try {
            if (be.hasNetwork() && !be.isRemoved()) {
                be.getOrCreateNetwork().updateStressFor(be, be.calculateStressApplied());
            }
        } catch (RuntimeException e) {
            LOGGER.debug("[FullStop] couldn't update the stress of {}: {}", be.getBlockPos(), e.toString());
        }
    }

    /** Each server level tick, after the hits: jams held, loads fading, the networks told. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        expireHeld(level);
        if (LOADS.isEmpty()) return;
        if (!enabled()) {
            clearHits();
            return;
        }
        long now = level.getGameTime();
        List<KineticBlockEntity> settle = new ArrayList<>();
        Iterator<Map.Entry<KineticBlockEntity, Load>> it = LOADS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<KineticBlockEntity, Load> entry = it.next();
            KineticBlockEntity be = entry.getKey();
            if (be == null || be.getLevel() != level) continue;
            Load load = entry.getValue();
            if (be.isRemoved()) {
                if (load.stall != null) {
                    AbstractContraptionEntity held = load.stall.contraption().get();
                    if (held != null) HELD.remove(held);
                }
                it.remove();
                continue;
            }
            if (load.stall != null) {
                String why = letGo(level, be, load.stall);
                if (why != null) {
                    release(level, be, load, why);
                } else {
                    load.su = Math.max(load.su, jamLoad(be));
                    if (load.resendAt != Long.MIN_VALUE && now >= load.resendAt) {
                        // the clients now hold the stall too: the drive's state again, so its pose is taken exactly
                        load.resendAt = Long.MIN_VALUE;
                        be.sendData();
                    }
                }
            } else {
                load.su *= DECAY;
            }
            if (load.stall == null && load.su < FORGET_SU) {
                it.remove();
            }
            settle.add(be);
        }
        // outside the iteration: telling a network can reach other block entities' loads
        for (KineticBlockEntity be : settle) push(be);
    }

    /** Forgets every load and lets every held contraption go (a config reload switching the feature off, a game test's reset). */
    public static void clear() {
        List<KineticBlockEntity> held = new ArrayList<>(HELD_IMPACTS.keySet());
        HELD_IMPACTS.clear();
        clearHits();
        for (KineticBlockEntity be : held) {
            if (be != null) push(be);
        }
    }

    /** Forgets the hits' loads and lets every jammed contraption go ({@code contraptionImpactStress} switched off); held loads stay. */
    private static void clearHits() {
        List<KineticBlockEntity> all = new ArrayList<>(LOADS.keySet());
        LOADS.clear();
        HELD.clear();
        PRESSED.clear();
        for (KineticBlockEntity be : all) {
            if (be != null) push(be);
        }
    }

    /**
     * Why a held jam lets go now, or null while it holds: the drive stopped or turned the other way, the contraption
     * is gone, or the obstacle is no longer there (mined or replaced; a ship gone or moved off). The drive comes
     * first: a bearing whose motor stops takes its contraption apart in the same tick (Create's "place when stopped"),
     * and a gantry takes itself apart as soon as its shaft stops turning, so its jam ends with the contraption gone.
     */
    @Nullable
    private static String letGo(ServerLevel level, KineticBlockEntity be, Stall stall) {
        float theoretical = be.getTheoreticalSpeed();
        if (theoretical == 0.0f) return "its drive stopped";
        if (!be.hasNetwork()) return "its drive left the network";
        if ((int) Math.signum(theoretical) != stall.sign()) return "its drive turned the other way";
        AbstractContraptionEntity c = stall.contraption().get();
        if (c == null || !c.isAlive()) return "the contraption is gone";
        Obstacle obstacle = stall.obstacle();
        if (obstacle.retry() && level.getGameTime() - stall.since() >= RETRY_TICKS) return "the level may break blocks again";
        if (obstacle.shipId() == null) {
            BlockState state = loadedState(level, obstacle.cell());
            if (state == null) return null; // its chunk unloaded: hold until it's back
            if (state.getBlock() != obstacle.block() || !ContraptionTerrainImpacts.solid(level, obstacle.cell(), state)) {
                return "the obstacle is gone";
            }
            return null;
        }
        if (!Ships.available()) return "the ship is gone";
        ShipRef ship = Ships.byId(level, obstacle.shipId());
        if (ship == null || !ship.exists()) return "the ship is gone";
        BlockState state = loadedState(level, obstacle.cell());
        if (state == null || state.getBlock() != obstacle.block() || !ContraptionTerrainImpacts.solid(level, obstacle.cell(), state)) {
            return "the ship's block is gone";
        }
        Vec3 now = ship.toWorld(Vec3.atCenterOf(obstacle.cell()));
        if (now == null || obstacle.shipCellWorld() == null || now.distanceTo(obstacle.shipCellWorld()) > SHIP_MOVED_OFF) {
            return "the ship moved off";
        }
        return null;
    }

    /** Lets a held jam go: the contraption may move again, the network's load is dropped at once. */
    private static void release(ServerLevel level, KineticBlockEntity be, Load load, String why) {
        AbstractContraptionEntity c = load.stall.contraption().get();
        if (c != null) {
            HELD.remove(c);
            ContraptionTerrainImpacts.forgetContacts(c, level.getGameTime());
        }
        if (SERVER.debugContraptionLog.get()) {
            LOGGER.info("[FullStop] contraption {} lets go of its jam after {} ticks: {}", c == null ? "?" : c.getId(),
                    level.getGameTime() - load.stall.since(), why);
        }
        LAST_RELEASE.put(be, why);
        load.stall = null;
        load.resendAt = Long.MIN_VALUE;
        load.su = 0.0;
    }

    /** Why each drive's last held jam let go (for the game tests and the harness). */
    private static final Map<KineticBlockEntity, String> LAST_RELEASE = new WeakHashMap<>();

    /** Why {@code be}'s last held jam let go, or null if it hasn't been held or still is. */
    @Nullable
    public static String lastRelease(KineticBlockEntity be) {
        return jammed(be) ? null : LAST_RELEASE.get(be);
    }

    /**
     * Sets a bearing's contraption to its pose at fraction {@code f} of the tick's motion: where its striking block's
     * face met the obstacle (Create lets a bearing's blocks pass through the terrain, so without this it would hold
     * wherever the tick left it, short of the obstacle or already into it). Sliding contraptions need nothing: Create
     * stops a piston, pulley or gantry at a solid block itself.
     */
    private static void pin(AbstractContraptionEntity c, KineticBlockEntity be, double f) {
        if (!(c instanceof ControlledContraptionEntity controlled) || controlled.getRotationAxis() == null) return;
        if (!(be instanceof MechanicalBearingBlockEntity bearing) || !Double.isFinite(f) || bearingAngleFailed) return;
        float angle = controlled.getAngle((float) f) % 360.0f;
        try {
            if (bearingAngle == null) {
                bearingAngle = MechanicalBearingBlockEntity.class.getDeclaredField("angle");
                bearingAngle.setAccessible(true);
                bearingPrevAngle = MechanicalBearingBlockEntity.class.getDeclaredField("prevAngle");
                bearingPrevAngle.setAccessible(true);
            }
            bearingAngle.setFloat(bearing, angle);
            bearingPrevAngle.setFloat(bearing, angle);
            controlled.setAngle(angle);
        } catch (ReflectiveOperationException | RuntimeException e) {
            bearingAngleFailed = true;
            LOGGER.warn("[FullStop] can't set Create's bearing angle ({}); a jammed bearing holds wherever it stopped", e.toString());
        }
    }

    /** The block at {@code pos}, or null when its chunk isn't loaded (never loads one). */
    @Nullable
    private static BlockState loadedState(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return null;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    private static Field rotationAxis;
    private static boolean rotationAxisFailed;

    /**
     * The axis a contraption turns about, in its own frame (a bearing's: its facing's axis), or null for one that
     * doesn't turn on a fixed axis (a piston, pulley or gantry slides; carts and trains ride).
     */
    @Nullable
    public static net.minecraft.core.Direction.Axis rotationAxis(AbstractContraptionEntity c) {
        if (!(c instanceof ControlledContraptionEntity) || rotationAxisFailed) return null;
        try {
            if (rotationAxis == null) {
                rotationAxis = ControlledContraptionEntity.class.getDeclaredField("rotationAxis");
                rotationAxis.setAccessible(true);
            }
            return (net.minecraft.core.Direction.Axis) rotationAxis.get(c);
        } catch (ReflectiveOperationException | RuntimeException e) {
            rotationAxisFailed = true;
            LOGGER.warn("[FullStop] can't read Create's bearing axes ({}); turning contraptions hit with their whole mass", e.toString());
            return null;
        }
    }

    /** The block a bearing, piston or pulley contraption is driven from (its world cell), or null for any other. */
    @Nullable
    static BlockPos controllerPosOf(AbstractContraptionEntity c) {
        return c instanceof ControlledContraptionEntity controlled ? controllerPos(controlled) : null;
    }

    @Nullable
    private static BlockPos controllerPos(ControlledContraptionEntity entity) {
        if (controllerPosFailed) return null;
        try {
            if (controllerPos == null) {
                controllerPos = ControlledContraptionEntity.class.getDeclaredField("controllerPos");
                controllerPos.setAccessible(true);
            }
            return (BlockPos) controllerPos.get(entity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            controllerPosFailed = true;
            LOGGER.warn("[FullStop] can't find what drives Create's bearings and pistons ({}); their hits won't stress them", e.toString());
            return null;
        }
    }

    /** Whether {@code entity} is a block entity FullStop is loading (for the debug log). */
    static boolean loaded(BlockEntity entity) {
        return entity instanceof KineticBlockEntity k && LOADS.containsKey(k);
    }

    /** The drive's mass-independent description, for the debug log. */
    static String describe(@Nullable Drive drive) {
        if (drive == null) return "nothing";
        if (drive.kinetic() != null) return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(drive.kinetic().getBlockState().getBlock())
                + " at " + drive.kinetic().getBlockPos().toShortString();
        if (drive.cart() != null) return "cart " + drive.cart().getId();
        return "train " + drive.train().id;
    }

    /** The entity the drive answers through (for a train or cart, the cart), or null. */
    @Nullable
    static Entity entityOf(@Nullable Drive drive) {
        return drive == null ? null : drive.cart();
    }
}
