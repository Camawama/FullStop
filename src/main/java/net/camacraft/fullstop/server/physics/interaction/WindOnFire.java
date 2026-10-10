package net.camacraft.fullstop.server.physics.interaction;

import com.google.common.collect.MapMaker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.compat.ShipFireCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * Speed blows a fire out ({@code windPutsOutFire}): a burning body moving through the air loses fire time faster the
 * faster it goes, on top of the tick vanilla takes each tick. Nothing below {@code fireWindMinSpeed} (a walk), then
 * {@code fireWindDrain} fire ticks per tick for every m/s over it, at most {@code fireWindMaxDrain}: the flames die
 * down sooner, never at once. A body in lava or standing in fire keeps burning (vanilla sets it alight again every
 * tick there anyway), and a fire-immune one is left alone.
 *
 * <p>The speed is the body's own step through the world each tick, so riding counts (a horse, a car, a ship's deck
 * under way) and a teleport doesn't. Fire time is the server's; clients show a body burning by the flag the server
 * sends, so both sides agree by themselves.
 *
 * <p>Valkyrien Skies ships burn in Camafy ({@code compat.vs.ShipFire}); a burning ship under way loses fire time by
 * the same rule, from its own speed or, turning, half the speed of its far end ({@link ShipFireCompat}).
 */
@Mod.EventBusSubscriber(modid = FullStop.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WindOnFire {

    /** A step this fast in one tick (m/s) is a jump (a teleport, a dimension change), not wind. */
    private static final double JUMP_MPS = 200.0;

    /** What a burning body or ship is remembered by between ticks. */
    private static final class Burning {
        Vec3 last;
        long lastTick = Long.MIN_VALUE;
        /** Fire time taken but not yet whole ticks. */
        double owed;
        /** Fire ticks taken from a ship whose Camafy can't take them itself. */
        int taken;
    }

    private static final Map<Entity, Burning> BODIES = new MapMaker().weakKeys().makeMap();
    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Burning>> SHIPS = new HashMap<>();

    /** Fire ticks the wind has taken in all, from bodies and from ships (for the tests and the harness). */
    private static long takenFromBodies, takenFromShips;

    private WindOnFire() {
    }

    public static long takenFromBodies() {
        return takenFromBodies;
    }

    public static long takenFromShips() {
        return takenFromShips;
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        FullStopConfig.ServerConfigValues cfg = FullStopConfig.SERVER;
        if (!cfg.windPutsOutFire.get()) return;
        long now = level.getGameTime();
        for (Entity entity : level.getAllEntities()) {
            if (entity.getRemainingFireTicks() <= 0) continue;
            blow(level, entity, now, cfg);
        }
        if (ShipFireCompat.available()) ships(level, now, cfg);
    }

    /** Fire ticks per tick the wind takes at {@code mps}. */
    public static double drainAt(double mps, FullStopConfig.ServerConfigValues cfg) {
        double over = mps - cfg.fireWindMinSpeed.get();
        if (!(over > 0.0)) return 0.0;
        return Math.min(cfg.fireWindMaxDrain.get(), over * cfg.fireWindDrain.get());
    }

    private static void blow(ServerLevel level, Entity entity, long now, FullStopConfig.ServerConfigValues cfg) {
        Burning state = BODIES.computeIfAbsent(entity, e -> new Burning());
        Vec3 at = entity.position();
        Vec3 last = state.last;
        boolean fresh = last == null || state.lastTick != now - 1;
        state.last = at;
        state.lastTick = now;
        if (fresh) {
            state.owed = 0.0;
            return;
        }
        double mps = at.distanceTo(last) * 20.0;
        if (mps > JUMP_MPS) return;
        double drain = drainAt(mps, cfg);
        if (drain <= 0.0 || entity.fireImmune() || entity.isInLava() || inFire(level, entity)) return;
        state.owed += drain;
        int take = (int) state.owed;
        if (take <= 0) return;
        state.owed -= take;
        int left = entity.getRemainingFireTicks() - take;
        takenFromBodies += Math.min(take, entity.getRemainingFireTicks());
        if (left > 0) {
            entity.setRemainingFireTicks(left);
            return;
        }
        // blown out: a hiss and a wisp of smoke where vanilla's fire just ends
        entity.clearFire();
        AABB box = entity.getBoundingBox();
        Vec3 c = box.getCenter();
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.NEUTRAL, 0.35F,
                1.6F + level.random.nextFloat() * 0.4F);
        level.sendParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 8, box.getXsize() * 0.3, box.getYsize() * 0.3, box.getZsize() * 0.3, 0.01);
        BODIES.remove(entity);
    }

    /** Whether the body touches fire or lava: there it is set alight anew every tick, and no wind helps. */
    private static boolean inFire(ServerLevel level, Entity entity) {
        AABB box = entity.getBoundingBox().deflate(1.0E-6);
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        if (!level.hasChunksAt(min, max)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private static void ships(ServerLevel level, long now, FullStopConfig.ServerConfigValues cfg) {
        Long2ObjectOpenHashMap<Burning> known = SHIPS.get(level.dimension());
        boolean[] any = {false};
        Long2ObjectOpenHashMap<Burning> states = known;
        Ships.forEachLoaded(level, ship -> {
            long id = ship.id();
            int left = ShipFireCompat.fireTicks(level, id);
            if (left <= 0) {
                if (states != null) states.remove(id);
                return;
            }
            any[0] = true;
            Long2ObjectOpenHashMap<Burning> map = SHIPS.computeIfAbsent(level.dimension(), d -> new Long2ObjectOpenHashMap<>());
            Burning state = map.get(id);
            if (state == null) {
                state = new Burning();
                map.put(id, state);
            }
            double drain = drainAt(airspeed(ship), cfg);
            if (drain <= 0.0) return;
            state.owed += drain;
            int take = (int) state.owed;
            if (take <= 0) return;
            state.owed -= take;
            takenFromShips += Math.min(take, left);
            if (ShipFireCompat.reduce(level, id, take)) return;
            // a Camafy that can't shorten a ship's fire: counted here, put out when it has used the fire up
            state.taken += take;
            if (state.taken >= left) {
                ShipFireCompat.extinguish(level, id);
                Vec3 at = ship.position();
                if (at != null) {
                    level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 0.7F, 1.2F);
                }
                map.remove(id);
            }
        });
        if (!any[0] && known != null && !known.isEmpty()) known.clear();
    }

    /** How fast a ship's hull goes through the air (m/s): its own speed or, turning, half the speed of its far end. */
    private static double airspeed(ShipRef ship) {
        Vec3 v = ship.velocity();
        double speed = v == null ? 0.0 : v.length();
        Vec3 spin = ship.angularVelocity();
        AABB box = ship.worldAabb();
        if (spin != null && box != null) {
            double reach = 0.5 * Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
            speed = Math.max(speed, 0.5 * spin.length() * reach);
        }
        return Double.isFinite(speed) ? speed : 0.0;
    }
}
