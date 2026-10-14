package net.camacraft.fullstop.common.compat.create.kinetic;

import com.google.common.collect.MapMaker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.scale.EntityScale;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.Impulses;
import net.camacraft.camafy.weight.Weights;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.compat.create.CreateCompat;
import net.camacraft.fullstop.server.physics.ship.ShipContactPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gears on what isn't a living body: vehicles ({@code gearsPushVehicles}) and Valkyrien Skies ships
 * ({@code gearsPushShips}). The same teeth and the same rules as for bodies ({@link KineticHazards},
 * {@link KineticGrip}): a single gear pushes what touches its teeth and flings it from {@code gearFlingSpeed}; the
 * in-running nip of two meshing gears draws it in and is jammed by it ({@code gearJamStress} for its mass).
 * Nothing here is hurt: a car and a hull have no health of their own.
 *
 * <p><b>Vehicles</b> are the entity types in {@code #fullstop:gear_pushed} (Automobility's and RIAutomobility's
 * cars). They are moved on the side that drives them (the server for an empty car, the driver's own client for a
 * driven one) and only through Camafy's {@code Impulses}, so a car another mod scripts takes the push in its own
 * terms; the jam is the server's either way. A car held to a surface by the gravity mod isn't moved. A heavy
 * vehicle is pushed and flung less (a 1000 kg car about a quarter of a player's share).
 *
 * <p><b>Ships.</b> Every block of a ship beside a turning gear is a body to that gear: the nearest one in the teeth
 * takes the push, as an impulse on the ship where it touches (so a hull caught off its middle turns), scaled by
 * {@code gearShipMass} over the ship's mass. A block in a nip jams the pair with the ship's whole mass and is
 * drawn in until they stall. Gears on a ship act on other ships, never on their own; ships under a fifth of full
 * size are left alone (their blocks are smaller than a tooth).
 */
public final class GearPushables {

    /** Vehicles gears push: entity types, not living bodies (those are {@link KineticHazards}'). */
    public static final TagKey<EntityType<?>> GEAR_PUSHED =
            TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(FullStop.MODID, "gear_pushed"));

    private static final int SCAN_EVERY = 10;
    private static final double RESCAN_MOVE_SQR = 0.5 * 0.5;
    /** Ticks between searches for gears around a ship (each ship on its own tick). */
    private static final int SHIP_SCAN_EVERY = 20;
    /** Ships smaller than this share of full size are left alone. */
    private static final double MIN_SHIP_SCALE = 0.2;
    /** Most ship blocks looked at around one gear, each way from it. */
    private static final int MAX_BLOCK_REACH = 3;

    /** What one vehicle or ship knows about the gears around it, on one side. */
    private static final class Seen {
        final List<Hazard> hazards = new ArrayList<>();
        @Nullable
        Vec3 scannedAt;
        long scannedTick = Long.MIN_VALUE;
        @Nullable
        Vec3 last;
        long lastTick = Long.MIN_VALUE;
        @Nullable
        Hazard flungBy;
        long flungTick = Long.MIN_VALUE;
        double lastFlingMps;
    }

    private static final Map<Entity, Seen> VEHICLES = new MapMaker().weakKeys().makeMap();
    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Seen>> SHIPS = new HashMap<>();

    /** Counters for the game tests and the harness (server; a driven vehicle's pushes are its client's). */
    private static long vehiclePushes, vehicleFlings, vehicleJams, shipPushes, shipFlings, shipJams;
    private static double lastShipFlingMps;

    private GearPushables() {
    }

    public static long vehiclePushes() { return vehiclePushes; }
    public static long vehicleFlings() { return vehicleFlings; }
    public static long vehicleJams() { return vehicleJams; }
    public static long shipPushes() { return shipPushes; }
    public static long shipFlings() { return shipFlings; }
    public static long shipJams() { return shipJams; }
    /** The change of speed (m/s) the last fling of a ship gave it. */
    public static double lastShipFlingMps() { return lastShipFlingMps; }

    /** The speed (m/s) {@code vehicle} was last flung at by a single gear on this side, 0 if never. */
    public static double lastFlingMps(Entity vehicle) {
        Seen seen = VEHICLES.get(vehicle);
        return seen == null ? 0.0 : seen.lastFlingMps;
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (!CreateCompat.active()) return;
        FullStopConfig.ServerConfigValues cfg = FullStopConfig.SERVER;
        if (!cfg.gearHazards.get()) return;
        if (cfg.gearsPushVehicles.get()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType().is(GEAR_PUSHED)) tickVehicle(entity);
            }
        }
        if (cfg.gearsPushShips.get() && Ships.available()) tickShips(level, cfg);
    }

    // ---- vehicles ---------------------------------------------------------------------------------------------

    /**
     * One tick of the gears on a vehicle, on either side: the side that drives it moves it, the server jams the gears.
     * The server calls it for every vehicle; a client for the one its own player drives ({@code GearVehiclesClient}).
     */
    public static void tickVehicle(Entity body) {
        Level level = body.level();
        if (!CreateCompat.active() || !body.getType().is(GEAR_PUSHED)) return;
        FullStopConfig.ServerConfigValues cfg = FullStopConfig.SERVER;
        if (!cfg.gearHazards.get() || !cfg.gearsPushVehicles.get()) return;
        if (body.isRemoved() || body.noPhysics || body.isPassenger()) return;
        boolean client = level.isClientSide();
        boolean simulate = body.isControlledByLocalInstance();
        if (client && !simulate) return;
        long now = level.getGameTime();
        Seen seen = VEHICLES.computeIfAbsent(body, b -> new Seen());
        Vec3 at = body.position();
        // its step through the world this tick: a scripted vehicle's own velocity field says little
        Vec3 v = seen.last != null && seen.lastTick == now - 1 ? at.subtract(seen.last) : Vec3.ZERO;
        seen.last = at;
        seen.lastTick = now;
        AABB box = body.getBoundingBox();
        if (seen.scannedAt == null || now - seen.scannedTick >= SCAN_EVERY || seen.scannedAt.distanceToSqr(at) > RESCAN_MOVE_SQR) {
            seen.hazards.clear();
            HazardScanner.scan(level, box, true, false, seen.hazards);
            seen.scannedAt = at;
            seen.scannedTick = now;
        }
        if (seen.hazards.isEmpty()) return;
        List<Hazard.Live> cogs = driven(level, seen.hazards, cfg.gearMinRpm.get(), Hazard.NO_SHIP);
        if (cogs.isEmpty()) {
            debug(body, now, "no driven gear among " + seen.hazards.size());
            return;
        }
        double mass = Weights.massKg(body);
        if (!(mass > 0.0) || !Double.isFinite(mass)) mass = 1000.0;
        // heavy bodies are harder to move, as for living ones (a player's weight is 1), never easier
        double share = Math.max(0.25, Math.min(1.0, Math.sqrt(75.0 / mass)));
        boolean movable = simulate && !Gravity.isAnchored(body);

        KineticGrip.NipCatch nip = KineticHazards.deepestNip(box, cogs);
        if (nip != null) {
            KineticGrip.Nip pair = nip.nip();
            if (!client) {
                HazardScanner.jam(level, pair.a().hazard(), body, cfg.gearJamStress.get() * mass);
                vehicleJams++;
            }
            if (pair.stalled() || !movable) return;
            double speed = Math.sqrt(Math.min(pair.rpm(), 1024.0) / 64.0);
            Impulses.add(body, KineticGrip.nipPull(nip, v, cfg.gearPullStrength.get() * speed * share));
            return;
        }
        if (!movable) {
            debug(body, now, "not movable here (anchored: " + Gravity.isAnchored(body) + ")");
            return;
        }
        KineticGrip.Catch touched = nearestTouch(box, cogs, cfg.gearMinRpm.get());
        if (touched == null) {
            debug(body, now, "not touching the teeth of " + cogs.size() + " gears; box " + box);
            return;
        }
        Vec3 up = up(body);
        Hazard gear = touched.part().hazard();
        if (!gear.equals(seen.flungBy) || now - seen.flungTick >= KineticHazards.FLING_COOLDOWN) {
            Vec3 own = v.scale(20.0);
            KineticGrip.Fling thrown = KineticGrip.fling(touched, own, up, body.onGround(), cfg.entityRestitution.get(),
                    cfg.hitLift.get() * ShipContactPhysics.liftShare(body));
            if (ShipContactPhysics.atOwnScale(body, thrown.closing()) >= cfg.gearFlingSpeed.get()) {
                Vec3 change = ShipContactPhysics.capLaunch(body, own, thrown.change().scale(share));
                Impulses.add(body, EntityScale.velocityFor(body, change.scale(0.05)));
                seen.flungBy = gear;
                seen.flungTick = now;
                seen.lastFlingMps = change.length();
                if (!client) vehicleFlings++;
                Vec3 c = box.getCenter();
                float volume = (float) Math.max(0.3, Math.min(1.0, thrown.closing() / 30.0));
                // heard by all from the server (an empty car); a driver's own client plays it for the driver
                level.playSound(body.getControllingPassenger() instanceof Player p ? p : null, c.x, c.y, c.z,
                        SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.BLOCKS, volume, 0.8F);
                return;
            }
        }
        Impulses.add(body, KineticGrip.push(touched, v, up, share * cfg.gearPullStrength.get()));
        if (!client) vehiclePushes++;
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** Last debug line's game time, per side (an integrated server and its client share this class). */
    private static final long[] lastDebug = {Long.MIN_VALUE, Long.MIN_VALUE};

    /** Why a vehicle beside a gear wasn't moved (debugImpactLog; at most once a second). */
    private static void debug(Entity body, long now, String why) {
        int side = body.level().isClientSide() ? 1 : 0;
        if (!FullStopConfig.SERVER.debugImpactLog.get() || now - lastDebug[side] < 20) return;
        lastDebug[side] = now;
        LOGGER.info("[FullStop] gears: {} ({}) not moved: {}", body.getName().getString(), body.level().isClientSide() ? "client" : "server", why);
    }

    private static Vec3 up(Entity body) {
        Vec3 down = Gravity.pullDirection(body);
        return down == null || down.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : down.normalize().scale(-1.0);
    }

    /** The gears among {@code hazards} their networks drive at {@code minRpm} or more, as they are now; none of {@code notOnShip}. */
    private static List<Hazard.Live> driven(Level level, List<Hazard> hazards, double minRpm, long notOnShip) {
        List<Hazard.Live> cogs = new ArrayList<>();
        for (Hazard hazard : hazards) {
            if (!hazard.kind().gear() || (notOnShip != Hazard.NO_SHIP && hazard.shipId() == notOnShip)) continue;
            Hazard.Live live = HazardScanner.resolveAny(level, hazard);
            if (live != null && Math.abs(live.driveRpm()) >= minRpm) cogs.add(live);
        }
        return cogs;
    }

    @Nullable
    private static KineticGrip.Catch nearestTouch(AABB box, List<Hazard.Live> cogs, double minRpm) {
        KineticGrip.Catch touched = null;
        for (Hazard.Live live : cogs) {
            if (!live.turning(minRpm)) continue;
            KineticGrip.Catch k = KineticGrip.touch(box, live);
            if (k != null && (touched == null || k.outside() < touched.outside())) touched = k;
        }
        return touched;
    }

    // ---- ships ------------------------------------------------------------------------------------------------

    private static void tickShips(ServerLevel level, FullStopConfig.ServerConfigValues cfg) {
        long now = level.getGameTime();
        Long2ObjectOpenHashMap<Seen> states = SHIPS.computeIfAbsent(level.dimension(), d -> new Long2ObjectOpenHashMap<>());
        if (now % 200 == 0 && !states.isEmpty()) {
            LongSet loaded = Ships.loadedIds(level);
            it.unimi.dsi.fastutil.longs.LongIterator ids = states.keySet().iterator();
            while (ids.hasNext()) {
                if (!loaded.contains(ids.nextLong())) ids.remove();
            }
        }
        Ships.forEachLoaded(level, ship -> {
            if (ship.isStatic()) return;
            double scale = ship.scale();
            if (!(scale >= MIN_SHIP_SCALE)) return;
            long id = ship.id();
            Seen seen = states.get(id);
            if (seen == null) {
                seen = new Seen();
                states.put(id, seen);
            }
            if (seen.scannedTick == Long.MIN_VALUE || Math.floorMod(now + id, SHIP_SCAN_EVERY) == 0) {
                seen.hazards.clear();
                AABB box = ship.worldAabb();
                if (box != null) HazardScanner.scanAround(level, box, id, seen.hazards);
                seen.scannedTick = now;
            }
            if (seen.hazards.isEmpty()) return;
            tickShip(level, ship, seen, now, cfg);
        });
    }

    /** One of a ship's blocks beside a gear, as the box the gear's teeth meet. */
    private record HullBlock(AABB box, Vec3 center) {
    }

    private static void tickShip(ServerLevel level, ShipRef ship, Seen seen, long now, FullStopConfig.ServerConfigValues cfg) {
        long id = ship.id();
        double minRpm = cfg.gearMinRpm.get();
        List<Hazard.Live> cogs = driven(level, seen.hazards, minRpm, id);
        if (cogs.isEmpty()) return;
        double mass = ship.mass();
        if (!(mass > 0.0) || !Double.isFinite(mass)) return;
        double share = Math.min(1.0, cfg.gearShipMass.get() / mass);
        Vec3 up = new Vec3(0, 1, 0);

        // a block in the in-running mouth of two meshing gears: jams them, and is drawn in until they stall
        KineticGrip.NipCatch nip = null;
        HullBlock nipped = null;
        for (int i = 0; i < cogs.size(); i++) {
            for (int j = i + 1; j < cogs.size(); j++) {
                Hazard.Live a = cogs.get(i), b = cogs.get(j);
                if (!KineticGrip.meshes(a, b)) continue;
                KineticGrip.Nip pair = KineticGrip.nip(a, b);
                if (pair == null) continue;
                for (HullBlock block : hullBlocksNear(level, ship, pair.mesh(), pair.depth() + 0.5)) {
                    KineticGrip.NipCatch k = KineticGrip.inMouth(block.box(), pair);
                    if (k != null && (nip == null || k.s() > nip.s())) {
                        nip = k;
                        nipped = block;
                    }
                }
            }
        }
        if (nip != null) {
            KineticGrip.Nip pair = nip.nip();
            // the Seen is the holder: one object for as long as the ship is known here
            HazardScanner.jam(level, pair.a().hazard(), seen, cfg.gearJamStress.get() * mass);
            shipJams++;
            if (pair.stalled()) return;
            Vec3 hull = hullVelocity(ship, nipped.center());
            double speed = Math.sqrt(Math.min(pair.rpm(), 1024.0) / 64.0);
            Vec3 dv = KineticGrip.nipPull(nip, hull, cfg.gearPullStrength.get() * speed).scale(share);
            push(level, id, mass, dv.scale(20.0), nipped.center());
            return;
        }

        // a single gear's teeth: the nearest block in them is pushed off, or flung by a fast gear
        KineticGrip.Catch touched = null;
        HullBlock at = null;
        for (Hazard.Live live : cogs) {
            if (!live.turning(minRpm)) continue;
            for (HullBlock block : hullBlocksNear(level, ship, live.center(), live.radius() + 0.75)) {
                KineticGrip.Catch k = KineticGrip.touch(block.box(), live);
                if (k != null && (touched == null || k.outside() < touched.outside())) {
                    touched = k;
                    at = block;
                }
            }
        }
        if (touched == null) return;
        Vec3 hull = hullVelocity(ship, at.center());
        Hazard gear = touched.part().hazard();
        if (!gear.equals(seen.flungBy) || now - seen.flungTick >= KineticHazards.FLING_COOLDOWN) {
            KineticGrip.Fling thrown = KineticGrip.fling(touched, hull.scale(20.0), up, false, cfg.entityRestitution.get(), 0.0);
            if (thrown.closing() >= cfg.gearFlingSpeed.get()) {
                Vec3 change = thrown.change().scale(share);
                double cap = cfg.maxHitLaunchSpeed.get();
                if (cap > 0.0 && change.length() > cap) change = change.scale(cap / change.length());
                push(level, id, mass, change, at.center());
                seen.flungBy = gear;
                seen.flungTick = now;
                seen.lastFlingMps = change.length();
                lastShipFlingMps = change.length();
                shipFlings++;
                Vec3 c = at.center();
                float volume = (float) Math.max(0.3, Math.min(1.0, thrown.closing() / 30.0));
                level.playSound(null, c.x, c.y, c.z, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.BLOCKS, volume, 0.7F);
                return;
            }
        }
        Vec3 dv = KineticGrip.push(touched, hull, up, cfg.gearPullStrength.get()).scale(share);
        if (dv.lengthSqr() < 1.0E-10) return;
        push(level, id, mass, dv.scale(20.0), at.center());
        shipPushes++;
    }

    /** A change of velocity (m/s) for the ship where the teeth touch it: an impulse of its mass times that, there. */
    private static void push(ServerLevel level, long shipId, double mass, Vec3 changeMps, Vec3 worldPoint) {
        if (changeMps.lengthSqr() < 1.0E-10) return;
        ShipForces.applyImpulse(level, shipId, changeMps.scale(mass), worldPoint);
    }

    /** The hull's velocity at a world point, blocks per tick. */
    private static Vec3 hullVelocity(ShipRef ship, Vec3 worldPoint) {
        Vec3 v = ship.velocityAt(worldPoint);
        return v == null ? Vec3.ZERO : v.scale(0.05);
    }

    /**
     * The ship's solid blocks within {@code reach} (world blocks) of a world point, each as the axis-aligned box of its
     * size about its centre in the world (a turned ship's block is a little more: close enough for teeth). Reads only
     * chunks that are loaded.
     */
    private static List<HullBlock> hullBlocksNear(ServerLevel level, ShipRef ship, Vec3 worldPoint, double reach) {
        List<HullBlock> out = new ArrayList<>();
        Vec3 local = ship.toShip(worldPoint);
        double scale = ship.scale();
        if (local == null || !(scale > 0.0)) return out;
        int r = Math.min(MAX_BLOCK_REACH, Mth.ceil(reach / scale));
        BlockPos middle = BlockPos.containing(local);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double half = 0.5 * scale;
        double reachSqr = (reach + scale) * (reach + scale);
        LevelChunk chunk = null;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int bx = middle.getX() + x, bz = middle.getZ() + z;
                if (chunk == null || chunk.getPos().x != bx >> 4 || chunk.getPos().z != bz >> 4) {
                    chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
                    if (chunk == null) continue;
                }
                for (int y = -r; y <= r; y++) {
                    cursor.set(bx, middle.getY() + y, bz);
                    BlockState state = chunk.getBlockState(cursor);
                    if (state.isAir() || state.getCollisionShape(level, cursor).isEmpty()) continue;
                    Vec3 c = ship.toWorld(Vec3.atCenterOf(cursor));
                    if (c == null || c.distanceToSqr(worldPoint) > reachSqr) continue;
                    out.add(new HullBlock(new AABB(c.x - half, c.y - half, c.z - half, c.x + half, c.y + half, c.z + half), c));
                }
            }
        }
        return out;
    }
}
