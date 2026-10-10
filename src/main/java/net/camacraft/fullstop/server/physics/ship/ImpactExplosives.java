package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.fullstop.server.physics.interaction.BlockAlterRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Set;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * TNT goes off when it is hit ({@code tntImpacts}): a TNT block of a Valkyrien Skies ship that strikes the terrain or
 * another ship (a ship that is nothing but TNT included), TNT in the terrain or on a ship that a hull strikes, and
 * with Create a contraption's own TNT or TNT a contraption runs into. A hit at {@code tntImpactMinSpeed} or faster
 * lights it with a short fuse ({@code tntImpactFuseTicks}; the lit TNT flies on with what carried it), one at
 * {@code tntImpactInstantSpeed} or faster sets it off where it is, that tick.
 *
 * <p>The struck block is the one at the contact, or one beside it in the same face of the hull (or of the terrain): a
 * hull landing flat touches at its corners, and TNT in the middle of that face is hit all the same.
 *
 * <p>Like any TNT it doesn't follow mobGriefing (TNT is nobody's mob); minigame hosts' block protection is asked
 * ({@code MatchPolicies.mayAlter}). The explosion is vanilla's (a primed TNT's, power 4), credited to the pilot or the
 * contraption's driver when there is one.
 */
public final class ImpactExplosives {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** What a hit does to an explosive block. */
    public enum Goes { NOT, LIT, AT_ONCE }

    private ImpactExplosives() {
    }

    public static boolean enabled() {
        return SERVER.tntImpacts.get();
    }

    /** Whether the block goes off when struck: TNT (and any block built on vanilla's). */
    public static boolean explosive(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof TntBlock;
    }

    /** What a hit at {@code speedMps} does to TNT. */
    public static Goes at(double speedMps) {
        if (!enabled() || !(speedMps >= SERVER.tntImpactMinSpeed.get())) return Goes.NOT;
        return speedMps >= SERVER.tntImpactInstantSpeed.get() ? Goes.AT_ONCE : Goes.LIT;
    }

    /**
     * The explosive block at {@code pos} (a world cell, or with {@code ship} a cell of that ship in the shipyard) is
     * hit at {@code speedMps}: taken out of the level and lit or set off where it stands in the world.
     * {@code carryMps} is the velocity (m/s, world) a lit one flies on with. Returns what happened; NOT leaves the
     * block alone (too slow, switched off, protected, no longer there).
     */
    public static Goes strike(ServerLevel level, BlockPos pos, @Nullable ShipRef ship, double speedMps, @Nullable Entity cause,
                              Vec3 carryMps) {
        Goes goes = at(speedMps);
        if (goes == Goes.NOT) return Goes.NOT;
        BlockState state = ShipTerrainImpacts.loadedState(level, pos);
        if (!explosive(state)) return Goes.NOT;
        Vec3 centre = Vec3.atCenterOf(pos);
        Vec3 world = ship != null ? ship.toWorld(centre) : centre;
        if (world == null || !BlockAlterRules.mayAlter(level, pos, cause)) return Goes.NOT;
        if (!level.removeBlock(pos, false)) return Goes.NOT;
        detonate(level, world, goes, cause, carryMps);
        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] TNT at {}{} hit at {} m/s: {}", pos.toShortString(), ship != null ? " of ship " + ship.id() : "",
                    String.format(Locale.ROOT, "%.1f", speedMps), goes == Goes.AT_ONCE ? "explodes" : "lit, fuse "
                            + SERVER.tntImpactFuseTicks.get() + " ticks");
        }
        return goes;
    }

    /**
     * The bang itself, for TNT already taken from wherever it was (a contraption's block): lit or set off at
     * {@code at} (the block's centre in the world).
     */
    public static void detonate(ServerLevel level, Vec3 at, Goes goes, @Nullable Entity cause, Vec3 carryMps) {
        if (goes == Goes.NOT) return;
        LivingEntity owner = cause instanceof LivingEntity living ? living : null;
        // a primed TNT stands on the bottom of its block
        PrimedTnt primed = new PrimedTnt(level, at.x, at.y - 0.5, at.z, owner);
        if (goes == Goes.AT_ONCE) {
            level.explode(primed, at.x, at.y - 0.5 + 0.0625, at.z, 4.0F, Level.ExplosionInteraction.TNT);
            return;
        }
        primed.setFuse(SERVER.tntImpactFuseTicks.get());
        primed.setDeltaMovement(carryMps.scale(1.0 / 20.0));
        level.addFreshEntity(primed);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.gameEvent(cause, GameEvent.PRIME_FUSE, BlockPos.containing(at));
    }

    /**
     * The explosive block a hull's (or the terrain's) face presents at a contact: the block at {@code hit} itself, or
     * one of the eight around it in the layer across {@code along} (the axis the contact pushes along, in that
     * block's own grid) whose side toward the hit is open. Null when none is there. {@code toward} is the step from
     * the face out toward what hit it.
     */
    @Nullable
    public static BlockPos inFace(ServerLevel level, @Nullable BlockPos hit, Direction toward) {
        if (hit == null) return null;
        if (explosive(ShipTerrainImpacts.loadedState(level, hit))) return hit;
        Direction.Axis axis = toward.getAxis();
        for (int u = -1; u <= 1; u++) {
            for (int v = -1; v <= 1; v++) {
                if (u == 0 && v == 0) continue;
                BlockPos pos = switch (axis) {
                    case X -> hit.offset(0, u, v);
                    case Y -> hit.offset(u, 0, v);
                    case Z -> hit.offset(u, v, 0);
                };
                if (!explosive(ShipTerrainImpacts.loadedState(level, pos))) continue;
                BlockState front = ShipTerrainImpacts.loadedState(level, pos.relative(toward));
                if (front == null || front.getCollisionShape(level, pos.relative(toward)).isEmpty()) return pos;
            }
        }
        return null;
    }

    /**
     * {@link #inFace} along {@code toward} first, then across the other two axes (both ways): a contact's normal can name
     * the wrong face (a hull's edge meeting a block's reads as a vertical contact), and TNT beside the struck block in
     * the face that really met is the one that was hit.
     */
    @Nullable
    public static BlockPos inAnyFace(ServerLevel level, @Nullable BlockPos hit, Direction toward) {
        BlockPos found = inFace(level, hit, toward);
        if (found != null || hit == null) return found;
        for (Direction other : Direction.values()) {
            if (other.getAxis() == toward.getAxis()) continue;
            found = inFace(level, hit, other);
            if (found != null) return found;
        }
        return null;
    }

    /** The grid direction nearest {@code v} (any length). */
    public static Direction nearest(Vec3 v) {
        return Direction.getNearest(v.x, v.y, v.z);
    }

    /** {@link #strike} once per block and tick ({@code struck} holds what this tick already hit). */
    static Goes strikeOnce(ServerLevel level, @Nullable BlockPos pos, @Nullable ShipRef ship, double speedMps, @Nullable Entity cause,
                           Vec3 carryMps, Set<BlockPos> struck) {
        if (pos == null || struck.contains(pos)) return Goes.NOT;
        Goes goes = strike(level, pos, ship, speedMps, cause, carryMps);
        if (goes != Goes.NOT) struck.add(pos.immutable());
        return goes;
    }
}
