package net.camacraft.fullstop.gametest;

import com.mojang.authlib.GameProfile;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * A player seated on a ship (a VS seat, as a helm, a Create or Clockwork seat is) weighs it down
 * like one standing on its deck. Two identical 3x3 plank rafts float, each held up by its own
 * weight; a player sits on one of them, which must sink faster than the other. Before the fix
 * seated riders were skipped (only entities standing on a deck weighed), so both sank alike.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipSeatWeightTests {

    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_seat_weight";
    private static final int TIMEOUT = 2400;

    private ShipSeatWeightTests() {
    }

    private static BlockPos lane(GameTestHelper helper, int lane) {
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (70 + lane) * 256);
        ServerLevel level = helper.getLevel();
        for (int cx = (o.getX() - 24) >> 4; cx <= (o.getX() + 24) >> 4; cx++) {
            for (int cz = (o.getZ() - 24) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aSeatedPlayerWeighsItsRaftDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = lane(helper, 0);
        BlockPos b = lane(helper, 1);
        Rig seatedRaft = Rig.build(helper, fillSet(level, a.offset(-1, 4, -1), a.offset(1, 4, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        Rig emptyRaft = Rig.build(helper, fillSet(level, b.offset(-1, 4, -1), b.offset(1, 4, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (seatedRaft == null || emptyRaft == null) return;
        helper.onEachTick(seatedRaft::record);
        helper.onEachTick(emptyRaft::record);
        ServerPlayer rider = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("fullstop-seat-rider".getBytes()),
                "[FS seat rider]"));
        double[] start = new double[2];
        long[] from = {Long.MAX_VALUE};
        helper.startSequence()
                .thenWaitUntil(seatedRaft::demandReady)
                .thenWaitUntil(emptyRaft::demandReady)
                .thenExecute(() -> {
                    // a VS seat on top of the raft's middle
                    Vec3 at = new Vec3(a.getX() + 0.5, a.getY() + 5.0, a.getZ() + 0.5);
                    Vec3 inShip = seatedRaft.ref.toShip(at);
                    helper.assertTrue(inShip != null, "the raft isn't resolved");
                    org.valkyrienskies.mod.common.entity.ShipMountingEntity seat =
                            org.valkyrienskies.mod.common.ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE.create(level);
                    helper.assertTrue(seat != null, "no VS seat");
                    seat.moveTo(inShip.x, inShip.y, inShip.z, 0.0f, 0.0f);
                    level.addFreshEntity(seat);
                    rider.moveTo(at.x, at.y, at.z, 0.0f, 0.0f);
                    List<ServerPlayer> players = ShipCollisionTests.playerList(level);
                    if (!players.contains(rider)) players.add(rider);
                    // and the level's own list, where a real player is (the weight reads it)
                    if (!level.players().contains(rider)) level.players().add(rider);
                    helper.assertTrue(rider.startRiding(seat, true), "the player wouldn't sit in the VS seat");
                    helper.assertTrue(seatedRaft.ref.equals(ShipEntities.mountedTo(rider)), "the seat isn't on the raft");
                })
                .thenExecute(seatedRaft::release)
                .thenExecute(emptyRaft::release)
                .thenWaitUntil(seatedRaft::demandSimulated)
                .thenWaitUntil(emptyRaft::demandSimulated)
                .thenExecute(() -> {
                    // each held up by exactly its own weight
                    seatedRaft.hover();
                    emptyRaft.hover();
                    stopFalling(seatedRaft);
                    stopFalling(emptyRaft);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    start[0] = seatedRaft.y();
                    start[1] = emptyRaft.y();
                    from[0] = level.getGameTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - from[0] >= 40, "watching the rafts"))
                .thenExecute(() -> {
                    Vec3 vs = seatedRaft.ref.velocity();
                    Vec3 ve = emptyRaft.ref.velocity();
                    double dropSeated = start[0] - seatedRaft.y();
                    double dropEmpty = start[1] - emptyRaft.y();
                    String out = String.format(Locale.ROOT, "over 40 ticks the raft with a seated player (%.0f kg) sank %.3f blocks "
                                    + "(vy %.3f m/s), the empty one (%.0f kg) %.3f blocks (vy %.3f m/s)", seatedRaft.ref.mass(),
                            dropSeated, vs == null ? Double.NaN : vs.y, emptyRaft.ref.mass(), dropEmpty, ve == null ? Double.NaN : ve.y);
                    rider.stopRiding();
                    ShipCollisionTests.playerList(level).remove(rider);
                    level.players().remove(rider);
                    seatedRaft.pin();
                    emptyRaft.pin();
                    report("a player seated on a floating raft", out);
                    // The float holds each raft up by about its own weight (VS's gravity and damping make
                    // it inexact, the same for both); the seated player's weight on top, about 80 kg on
                    // 4.5 t, must sink its raft faster: some 0.17 m/s^2 more, 0.35 m/s after 2 s.
                    helper.assertTrue(vs != null && ve != null && vs.y - ve.y < -0.15 && dropSeated - dropEmpty > 0.1,
                            "the seated player didn't weigh the raft down: " + out);
                    helper.succeed();
                });
    }

    /** Stops whatever the raft was doing when the float took over (the fall it began), so both start still. */
    private static void stopFalling(Rig rig) {
        Vec3 v = rig.ref.velocity();
        double mass = rig.ref.mass();
        if (v != null && Double.isFinite(mass)) {
            net.camacraft.camafy.compat.vs.ShipForces.applyImpulse(rig.level, rig.ship.getId(), v.scale(-mass), null);
        }
    }
}
