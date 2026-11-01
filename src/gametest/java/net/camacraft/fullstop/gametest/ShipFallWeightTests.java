package net.camacraft.fullstop.gametest;

import com.mojang.authlib.GameProfile;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
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
 * A ship in free fall carries none of its crew's weight ({@code crewWeighByApparentGravity}, 2026-10-30): a 3x3 plank
 * raft with a player seated over one corner is let go 60 blocks up and falls. Ship and rider fall together, so the seat
 * presses on nothing and the raft must not tip. With the switch off (the old rule: the rider's whole weight at the seat)
 * it tips toward the corner, which shows the rig sees the torque. Pack harness {@code spacespin_launch} measured it in
 * the owner's pack: 520 N m and 44 degrees in 11.5 s of falling from 1,440, against 11-15 N m without standing weight.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipFallWeightTests {

    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH_ON = "fullstop_fall_weight_on";
    private static final String BATCH_OFF = "fullstop_fall_weight_off";
    /** The test server races ahead of VS's real-time physics: the fall is judged by its speed, not by ticks. */
    private static final int TIMEOUT = 20000;
    /** The fall is watched until the raft falls this fast (m/s): about 1.2 s of free fall. */
    private static final double WATCH_SPEED = 12.0;

    private ShipFallWeightTests() {
    }

    @BeforeBatch(batch = BATCH_OFF)
    public static void apparentGravityOff(ServerLevel level) {
        FullStopConfig.SERVER.crewWeighByApparentGravity.set(false);
    }

    @AfterBatch(batch = BATCH_OFF)
    public static void apparentGravityOn(ServerLevel level) {
        FullStopConfig.SERVER.crewWeighByApparentGravity.set(true);
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH_ON)
    public static void aFallingRaftCarriesNoneOfItsSeatedRidersWeight(GameTestHelper helper) {
        fall(helper, 0, true);
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH_OFF)
    public static void withTheOldRuleAFallingRaftTipsUnderItsSeatedRider(GameTestHelper helper) {
        fall(helper, 1, false);
    }

    private static void fall(GameTestHelper helper, int laneIndex, boolean apparent) {
        ServerLevel level = helper.getLevel();
        BlockPos a = lane(helper, laneIndex);
        Rig raft = Rig.build(helper, fillSet(level, a.offset(-1, 60, -1), a.offset(1, 60, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, false);
        if (raft == null) return;
        helper.onEachTick(raft::record);
        ServerPlayer rider = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("fullstop-fall-rider-" + laneIndex).getBytes()), "[FS fall rider " + laneIndex + "]"));
        double[] maxOmega = {0.0};
        double[] maxTilt = {0.0};
        // the seated weight Full Stop puts on the raft while it falls faster than 3 m/s: the most, in newtons and N m
        double[] maxWeight = {0.0, 0.0};
        double[] sumWeight = {0.0, 0.0}; // newtons summed, samples
        double[] sumAccel = {0.0, 0.0}; // Full Stop's estimate of the raft's downward acceleration summed, samples
        double riderKg = net.camacraft.fullstop.server.physics.ship.ShipContactPhysics.bodyKg(rider);
        helper.startSequence()
                .thenWaitUntil(raft::demandReady)
                .thenExecute(() -> {
                    // a VS seat on the north-west corner block
                    Vec3 at = new Vec3(a.getX() - 0.5, a.getY() + 61.0, a.getZ() - 0.5);
                    Vec3 inShip = raft.ref.toShip(at);
                    helper.assertTrue(inShip != null, "the raft isn't resolved");
                    org.valkyrienskies.mod.common.entity.ShipMountingEntity seat =
                            org.valkyrienskies.mod.common.ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE.create(level);
                    helper.assertTrue(seat != null, "no VS seat");
                    seat.moveTo(inShip.x, inShip.y, inShip.z, 0.0f, 0.0f);
                    level.addFreshEntity(seat);
                    rider.moveTo(at.x, at.y, at.z, 0.0f, 0.0f);
                    List<ServerPlayer> players = ShipCollisionTests.playerList(level);
                    if (!players.contains(rider)) players.add(rider);
                    if (!level.players().contains(rider)) level.players().add(rider);
                    helper.assertTrue(rider.startRiding(seat, true), "the player wouldn't sit in the VS seat");
                    helper.assertTrue(raft.ref.equals(ShipEntities.mountedTo(rider)), "the seat isn't on the raft");
                })
                .thenExecute(raft::release)
                .thenWaitUntil(raft::demandSimulated)
                .thenWaitUntil(() -> {
                    Vec3 w = raft.ref.angularVelocity();
                    if (w != null) maxOmega[0] = Math.max(maxOmega[0], w.length());
                    org.joml.Quaterniond rotation = raft.ref.rotation();
                    if (rotation != null) {
                        org.joml.Vector3d up = rotation.transform(new org.joml.Vector3d(0.0, 1.0, 0.0));
                        maxTilt[0] = Math.max(maxTilt[0], Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, up.y)))));
                    }
                    Vec3 falling = raft.ref.velocity();
                    if (falling != null && -falling.y > 3.0) {
                        double[] weight = net.camacraft.fullstop.server.physics.ship.ShipWeightHandler.lastWeight(raft.ship.getId());
                        double newtons = weight == null ? 0.0 : weight[0];
                        if (weight != null) {
                            maxWeight[0] = Math.max(maxWeight[0], weight[0]);
                            maxWeight[1] = Math.max(maxWeight[1], weight[1]);
                        }
                        sumWeight[0] += newtons;
                        sumWeight[1]++;
                        var tracked = net.camacraft.fullstop.server.physics.ship.ShipTracker.of(level).latest(raft.ship.getId());
                        Vec3 accel = tracked == null ? null : tracked.acceleration();
                        if (accel != null) {
                            sumAccel[0] += -accel.y;
                            sumAccel[1]++;
                        }
                    }
                    helper.assertTrue(falling != null && -falling.y >= WATCH_SPEED, "watching the fall");
                })
                .thenExecute(() -> {
                    Vec3 v = raft.ref.velocity();
                    String out = String.format(Locale.ROOT, "%s: a %.0f kg raft falling with a player seated over a corner: fastest spin %.3f rad/s, "
                                    + "most tilt %.2f degrees until it fell at %.1f m/s; the seat's weight on it from 3 m/s down %.0f N on "
                                    + "average, at most %.0f N and %.0f N m (the rider weighs %.0f N); its acceleration %.2f m/s^2 down on average (VS's gravity %s)", apparent ? "apparent gravity" : "old rule",
                            raft.ref.mass(), maxOmega[0], maxTilt[0], v == null ? Double.NaN : -v.y, average(sumWeight), maxWeight[0],
                            maxWeight[1], riderKg * 9.81, average(sumAccel), raft.gravity());
                    rider.stopRiding();
                    ShipCollisionTests.playerList(level).remove(rider);
                    level.players().remove(rider);
                    raft.pin();
                    report("a raft falling with a seated rider", out);
                    // The test world is low and VS's air is thick there: a falling raft flutters a little in it, with a rider
                    // or without (in the owner's pack above VS's air the seat's weight alone tipped one 44 degrees, harness
                    // spacespin_launch). So the weight Full Stop puts on the falling raft is what is judged.
                    // Judged on average (this test server's ticks race ahead of VS's clock, so single frames are noisy). The
                    // test world is low and VS's air is thick there: the raft falls at about 8 m/s^2, not VS's 10, and the
                    // rider, held back by nothing, does press on its seat with the difference (about a sixth of its weight);
                    // above VS's air it falls at 10 and the rider weighs nothing. So: the seat's weight follows the raft's
                    // fall, and is a small part of the rider's.
                    double pull = FullStopConfig.SERVER.gravity.get();
                    double expectedShare = Math.max(0.0, Math.min(3.0, (pull - average(sumAccel)) / pull));
                    if (apparent) {
                        helper.assertTrue(sumWeight[1] > 0 && sumAccel[1] > 0, "no samples of the fall: " + out);
                        helper.assertTrue(Math.abs(average(sumWeight) - expectedShare * riderKg * 9.81) < 0.1 * riderKg * 9.81,
                                String.format(Locale.ROOT, "the seat's weight doesn't follow the fall (expected %.0f N): ",
                                        expectedShare * riderKg * 9.81) + out);
                        helper.assertTrue(average(sumWeight) < 0.3 * riderKg * 9.81, "the falling raft carried its rider's weight: " + out);
                    } else {
                        helper.assertTrue(average(sumWeight) > 0.9 * riderKg * 9.81, "the old rule didn't put the rider's weight on the raft: " + out);
                    }
                    helper.succeed();
                });
    }

    private static double average(double[] sum) {
        return sum[1] > 0 ? sum[0] / sum[1] : 0.0;
    }

    private static BlockPos lane(GameTestHelper helper, int lane) {
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (80 + lane) * 256);
        ServerLevel level = helper.getLevel();
        for (int cx = (o.getX() - 24) >> 4; cx <= (o.getX() + 24) >> 4; cx++) {
            for (int cz = (o.getZ() - 24) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }
}
