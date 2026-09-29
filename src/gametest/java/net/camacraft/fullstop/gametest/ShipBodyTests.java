package net.camacraft.fullstop.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.vs.PointForce;
import net.camacraft.camafy.compat.vs.ShipContact;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.weight.Weights;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.SpinDizziness;
import net.camacraft.fullstop.server.physics.ship.ShipContactPhysics;
import net.camacraft.fullstop.server.physics.ship.ShipEntityHits;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.valkyrienskies.mod.mixinducks.feature.tickets.PlayerKnownShipsDuck;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fill;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fmt;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * Entities against Valkyrien Skies hulls, with real ships (the {@link Rig} of
 * {@link ShipCollisionTests}; same opt-in: {@code gradlew runGameTestServer -PshipTests}).
 *
 * <p><b>Solid hulls.</b> VS 2.4.11's entity-vs-ship resolver let anything moving more than about a
 * block a tick once it touched a hull go straight through (it nudges instead of stopping; see
 * {@code ShipHullSweep}). Mobs are simulated on the server, so a thrown cow is the real thing; a
 * player is simulated on its own client, which can't run here, so the player cases move a fake
 * player with {@code Entity.move} - the very call the client makes for a flying player - on the
 * server. A NoAI cow moved the same way shows the fix without FullStop's own impact handling.
 *
 * <p><b>Two bodies.</b> A heavy golem against a light raft, a villager against a heavy ship and
 * against a world wall of the same block (the damage must match), a heavy rider dropped on the
 * edge of a small raft and one standing off-centre on it (the raft must list, never spin up).
 *
 * <p><b>No ships.</b> Spin nausea's build-up on scripted turning, the thrown-rider feedback guard,
 * and what counts as one body for a ship to hit (a modded vehicle with a rider).
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipBodyTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_ship_bodies";
    private static final int TIMEOUT = 1200;
    private static final ResourceLocation PONTOON = new ResourceLocation(FullStop.MODID, "gametest_pontoon");

    private ShipBodyTests() {
    }

    /** A lane of its own for each test, 256 blocks apart, 64 blocks of it force-loaded along +x. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (16 + lane) * 256);
        ServerLevel level = helper.getLevel();
        for (int cx = (o.getX() - 16) >> 4; cx <= (o.getX() + 64) >> 4; cx++) {
            for (int cz = (o.getZ() - 16) >> 4; cz <= (o.getZ() + 32) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    /** A 1x4x5 wall of {@code block} at x+8, facing -x (obsidian: 100 t, nothing thrown here can smash it). */
    @Nullable
    private static Rig wall(GameTestHelper helper, BlockPos o, Block block, boolean floating) {
        return Rig.build(helper, fillSet(helper.getLevel(), o.offset(8, 1, -2), o.offset(8, 4, 2), block.defaultBlockState()),
                block, floating);
    }

    // ------------------------------------------------------------------
    // Solid hulls
    // ------------------------------------------------------------------

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aCowThrownAt20MpsStopsAtAHull(GameTestHelper helper) {
        throwAtHull(helper, lane(helper, 0), 20.0, Blocks.OBSIDIAN, false, false, "static obsidian hull, cow at 20 m/s");
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aCowThrownAt40MpsStopsAtAHull(GameTestHelper helper) {
        throwAtHull(helper, lane(helper, 1), 40.0, Blocks.OBSIDIAN, false, true, "static obsidian hull, cow at 40 m/s");
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aCowThrownAt80MpsStopsAtAHull(GameTestHelper helper) {
        throwAtHull(helper, lane(helper, 2), 80.0, Blocks.OBSIDIAN, false, true, "static obsidian hull, cow at 80 m/s");
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aCowThrownAt40MpsStopsAtAFloatingHull(GameTestHelper helper) {
        throwAtHull(helper, lane(helper, 3), 40.0, Blocks.OBSIDIAN, true, true, "floating obsidian hull, cow at 40 m/s");
    }

    /**
     * Not a tunnel: a 650 kg cow at 40 m/s carries enough energy to smash oak planks, on a ship as
     * in the world (FullStop's kinetic block breaking). It punches through, slowed by what the
     * planks cost, and the ship takes the momentum it lost. Reported, not required.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH, required = false)
    public static void aCowThrownAt40MpsSmashesThroughAPlankHull(GameTestHelper helper) {
        throwAtHull(helper, lane(helper, 4), 40.0, Blocks.OAK_PLANKS, true, false, "floating plank hull, cow at 40 m/s");
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aFlyingPlayerStopsAtAHullAt22Mps(GameTestHelper helper) {
        flyAtHull(helper, lane(helper, 5), 22.0, false, "static plank hull, flying player moving 22 m/s");
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aFlyingPlayerStopsAtAHullAt60Mps(GameTestHelper helper) {
        flyAtHull(helper, lane(helper, 6), 60.0, false, "static plank hull, flying player moving 60 m/s");
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aFlyingPlayerStopsAtAFloatingHullAt22Mps(GameTestHelper helper) {
        flyAtHull(helper, lane(helper, 7), 22.0, true, "floating plank hull, flying player moving 22 m/s");
    }

    /** A NoAI cow (FullStop leaves it alone) moved 80 m/s a tick by Entity.move: VS's resolver plus the fix only. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void anEntityMovedAt80MpsStopsAtAHullWithFullStopOutOfTheWay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 8);
        Rig rig = wall(helper, o, Blocks.OAK_PLANKS, false);
        if (rig == null) return;
        Watch watch = new Watch(rig);
        helper.onEachTick(rig::record);
        helper.onEachTick(watch::record);
        helper.startSequence().thenWaitUntil(rig::demandReady).thenExecute(() -> {
                    Mob cow = cow(level, new Vec3(o.getX() + 2.5, o.getY() + 2.0, o.getZ() + 0.5));
                    cow.setNoAi(true);
                    watch.start(cow, 80.0);
                })
                .thenWaitUntil(() -> watch.demandWatched(30))
                .thenExecute(() -> {
                    report("static plank hull, NoAI cow moved 80 m/s by Entity.move", watch.summary() + " | planks "
                            + rig.count(Blocks.OAK_PLANKS));
                    rig.pin();
                    watch.demandHeld();
                    helper.succeed();
                });
    }

    private static void throwAtHull(GameTestHelper helper, BlockPos o, double speed, Block block, boolean floating,
                                    boolean expectDamage, String what) {
        ServerLevel level = helper.getLevel();
        Rig rig = wall(helper, o, block, floating);
        if (rig == null) return;
        Watch watch = new Watch(rig);
        helper.onEachTick(rig::record);
        helper.onEachTick(watch::record);
        var seq = helper.startSequence().thenWaitUntil(rig::demandReady);
        if (floating) {
            seq = seq.thenExecute(rig::release).thenWaitUntil(rig::demandSimulated).thenExecute(rig::hover).thenIdle(10);
        }
        seq.thenExecute(() -> {
                    Mob cow = cow(level, new Vec3(o.getX() + 2.5, o.getY() + 2.0, o.getZ() + 0.5));
                    cow.setDeltaMovement(speed / 20.0, 0.0, 0.0);
                    cow.hasImpulse = true;
                    watch.start(cow, Double.NaN);
                })
                .thenWaitUntil(() -> watch.demandWatched(30))
                .thenExecute(() -> {
                    int left = rig.count(block);
                    ShipEntityHits.Hit hit = ShipEntityHits.lastHit(watch.body);
                    report(what, watch.summary() + String.format(Locale.ROOT, " | hull %d/%d blocks left, cow health %.1f -> %.1f, "
                                    + "collision %s", left, rig.blocks, watch.healthBefore, ((LivingEntity) watch.body).getHealth(),
                            describe(hit)));
                    rig.pin();
                    if (block == Blocks.OBSIDIAN) {
                        watch.demandHeld();
                        helper.assertTrue(left == rig.blocks, "the obsidian hull lost blocks");
                        if (expectDamage) {
                            helper.assertTrue(((LivingEntity) watch.body).getHealth() < watch.healthBefore,
                                    "the cow slammed into the hull at " + speed + " m/s and took no damage");
                        }
                    }
                    helper.succeed();
                });
    }

    private static void flyAtHull(GameTestHelper helper, BlockPos o, double speed, boolean floating, String what) {
        ServerLevel level = helper.getLevel();
        Rig rig = wall(helper, o, Blocks.OAK_PLANKS, floating);
        if (rig == null) return;
        Watch watch = new Watch(rig);
        helper.onEachTick(rig::record);
        helper.onEachTick(watch::record);
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(("fs-fly-" + o).getBytes()), "[FS flyer]"));
        var seq = helper.startSequence().thenWaitUntil(rig::demandReady);
        if (floating) {
            seq = seq.thenExecute(rig::release).thenWaitUntil(rig::demandSimulated).thenExecute(rig::hover).thenIdle(10);
        }
        seq.thenExecute(() -> {
                    player.moveTo(o.getX() + 2.5, o.getY() + 2.0, o.getZ() + 0.5, -90.0f, 0.0f);
                    player.getAbilities().flying = true;
                    player.getAbilities().mayfly = true;
                    // VS freezes a player next to a ship it hasn't been told about (its client would know it)
                    if (player instanceof PlayerKnownShipsDuck duck) duck.vs_addKnownShip(rig.ship.getId());
                    watch.start(player, speed);
                })
                .thenWaitUntil(() -> watch.demandWatched(30))
                .thenExecute(() -> {
                    report(what, watch.summary());
                    rig.pin();
                    watch.demandHeld();
                    helper.succeed();
                });
    }

    /** A cow that stays where it is put: AI on (it moves under its own physics), no goals, no gravity. */
    private static Mob cow(ServerLevel level, Vec3 at) {
        Mob cow = EntityType.COW.create(level);
        if (cow == null) throw new IllegalStateException("no cow");
        cow.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        cow.goalSelector.removeAllGoals(g -> true);
        cow.setNoGravity(true);
        cow.setPersistenceRequired();
        level.addFreshEntity(cow);
        return cow;
    }

    /** What one body did around a 1-block-thick hull, tick by tick, in the hull's own space. */
    private static final class Watch {
        final Rig rig;
        Entity body;
        long start = -1;
        double moveSpeed = Double.NaN;
        double deepest;
        double farthestPast = Double.NEGATIVE_INFINITY;
        float healthBefore;
        final StringBuilder log = new StringBuilder();
        int lines;

        Watch(Rig rig) {
            this.rig = rig;
        }

        /** Starts watching {@code body}; with a {@code moveSpeed} (m/s), moves it along +x with Entity.move for 20 ticks. */
        void start(Entity body, double moveSpeed) {
            this.body = body;
            this.moveSpeed = moveSpeed;
            this.start = body.level().getGameTime();
            this.healthBefore = body instanceof LivingEntity living ? living.getHealth() : 0.0f;
        }

        void record() {
            if (body == null) return;
            if (!Double.isNaN(moveSpeed) && body.level().getGameTime() - start < 20) {
                body.move(MoverType.SELF, new Vec3(moveSpeed / 20.0, 0.0, 0.0));
            }
            ShipRef ref = rig.ref;
            Vec3 local = ref.toShip(body.position());
            BoundingBox blocks = ref.shipyardBlocks();
            if (local == null || blocks == null) return;
            double depth = 0.0;
            for (ShipContact c : ref.contacts(body.getBoundingBox())) depth = Math.max(depth, c.depth());
            deepest = Math.max(deepest, depth);
            farthestPast = Math.max(farthestPast, local.x - (blocks.maxX() + 1.0));
            if (lines++ < 12) {
                log.append(String.format(Locale.ROOT, " t%d front %.2f from the hull, depth %.3f, v %.2f |",
                        body.level().getGameTime() - start, local.x + body.getBbWidth() * 0.5 - blocks.minX(), depth,
                        VelocityMath.entityVelocity(body).x));
            }
        }

        void demandWatched(int ticks) {
            rig.helper.assertTrue(start >= 0 && body.level().getGameTime() - start >= ticks, "watching");
        }

        /** Never through, never in: the centre stayed in front of the far face and the box never sank into the hull. */
        void demandHeld() {
            rig.helper.assertTrue(farthestPast < 0.0, "went THROUGH the hull: its centre ended " + fmt(farthestPast)
                    + " past the far face (" + log + ")");
            rig.helper.assertTrue(deepest <= 0.05, "sank " + fmt(deepest) + " into the hull (" + log + ")");
        }

        String summary() {
            return String.format(Locale.ROOT, "deepest %.3f, centre past the far face by %.2f (%s) ::%s", deepest, farthestPast,
                    farthestPast > 0 ? "WENT THROUGH" : "held", log);
        }
    }

    // ------------------------------------------------------------------
    // Two bodies
    // ------------------------------------------------------------------

    /**
     * An iron golem (3000 kg) thrown at 10 m/s into a floating 1x2x3 purpur raft (6 blocks of
     * 100 kg): the raft is shoved off at about (1 + e) m/(m + M) of the golem's speed, the golem
     * keeps most of its own, and a collision that costs it barely 2 m/s doesn't hurt it.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aGolemBarelySlowsAgainstALightRaftAndShovesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 9);
        Rig rig = Rig.build(helper, fillSet(level, o.offset(8, 1, 0), o.offset(8, 2, 2), Blocks.PURPUR_BLOCK.defaultBlockState()),
                Blocks.PURPUR_BLOCK, true);
        if (rig == null) return;
        double[] raftTop = {0.0};
        Mob[] golem = new Mob[1];
        float[] health = new float[1];
        helper.onEachTick(rig::record);
        helper.onEachTick(() -> {
            Vec3 v = rig.ref.velocity();
            if (golem[0] != null && v != null) raftTop[0] = Math.max(raftTop[0], v.x);
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(rig::hover)
                .thenIdle(10)
                .thenExecute(() -> {
                    Mob g = EntityType.IRON_GOLEM.create(level);
                    helper.assertTrue(g != null, "no golem");
                    g.moveTo(o.getX() + 4.5, o.getY() + 1.0, o.getZ() + 1.5, -90.0f, 0.0f);
                    g.goalSelector.removeAllGoals(x -> true);
                    g.targetSelector.removeAllGoals(x -> true);
                    g.setNoGravity(true);
                    g.setPersistenceRequired();
                    level.addFreshEntity(g);
                    g.setDeltaMovement(0.5, 0.0, 0.0);
                    g.hasImpulse = true;
                    golem[0] = g;
                    health[0] = g.getHealth();
                })
                .thenWaitUntil(() -> helper.assertTrue(ShipEntityHits.lastHit(golem[0]) != null, "the golem hasn't hit the raft yet"))
                .thenIdle(30)
                .thenExecute(() -> {
                    ShipEntityHits.Hit hit = ShipEntityHits.lastHit(golem[0]);
                    ShipContactPhysics.Exchange ex = hit.exchange();
                    double kept = -hit.normalSpeedAfter(); // the normal points back at the golem (-x)
                    double expectedRaft = ex.impulse() / rig.ref.mass();
                    rig.pin();
                    report("iron golem (3000 kg) at 10 m/s into a 600 kg floating raft", String.format(Locale.ROOT,
                            "%s; golem kept %.2f of %.2f m/s along the push (%.0f%%), health %.1f -> %.1f; raft reached %.2f m/s "
                                    + "(impulse / mass %.2f)", describe(hit), kept, ex.approach(), 100.0 * kept / ex.approach(),
                            health[0], golem[0].getHealth(), raftTop[0], expectedRaft));
                    helper.assertTrue(!hit.landing() && ex.shipKg() < 1000.0, "not judged as a hit on the light raft: " + describe(hit));
                    helper.assertTrue(kept >= 0.6 * ex.approach(), "the golem kept only " + fmt(kept) + " of " + fmt(ex.approach()) + " m/s");
                    helper.assertTrue(hit.pushed() > 0.0, "the raft wasn't pushed");
                    helper.assertTrue(raftTop[0] >= 0.5 * expectedRaft, "the raft only reached " + fmt(raftTop[0]) + " m/s of "
                            + fmt(expectedRaft));
                    helper.assertTrue(golem[0].getHealth() >= health[0], "a " + fmt(ex.speedLost()) + " m/s slowdown hurt the golem");
                    helper.succeed();
                });
    }

    /**
     * Two villagers thrown at 20 m/s: one into a floating 100 t obsidian ship, one into an
     * obsidian wall in the world. Against a ship that heavy the villager loses its whole closing
     * speed, as against the wall, and takes the same damage (within a quarter); off the ship it
     * rebounds at the restitution.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aVillagerHittingAHeavyShipIsHurtAsByAWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 10);
        BlockPos w = o.offset(0, 0, 12);
        fill(level, w.offset(8, 1, -2), w.offset(8, 4, 2), Blocks.OBSIDIAN.defaultBlockState());
        Rig rig = wall(helper, o, Blocks.OBSIDIAN, true);
        if (rig == null) return;
        Villager[] v = new Villager[2];
        float[] before = new float[2];
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(rig::hover)
                .thenIdle(10)
                .thenExecute(() -> {
                    v[0] = villager(level, new Vec3(o.getX() + 3.5, o.getY() + 1.5, o.getZ() + 0.5), 20.0);
                    v[1] = villager(level, new Vec3(w.getX() + 3.5, w.getY() + 1.5, w.getZ() + 0.5), 20.0);
                    before[0] = v[0].getHealth();
                    before[1] = v[1].getHealth();
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    ShipEntityHits.Hit hit = ShipEntityHits.lastHit(v[0]);
                    double shipDamage = before[0] - v[0].getHealth();
                    double wallDamage = before[1] - v[1].getHealth();
                    rig.pin();
                    report("villager at 20 m/s into a 100 t obsidian ship vs into an obsidian wall", String.format(Locale.ROOT,
                            "%s; damage off the ship %.1f, off the wall %.1f", describe(hit), shipDamage, wallDamage));
                    helper.assertTrue(hit != null, "the villager's hit on the ship wasn't judged as a collision");
                    helper.assertTrue(hit.exchange().speedLost() >= 0.95 * hit.exchange().approach(),
                            "against a 100 t ship the villager lost only " + fmt(hit.exchange().speedLost()) + " of "
                                    + fmt(hit.exchange().approach()) + " m/s");
                    helper.assertTrue(wallDamage > 0.0 && shipDamage > 0.0, "no damage (ship " + fmt(shipDamage) + ", wall "
                            + fmt(wallDamage) + ")");
                    helper.assertTrue(v[0].isAlive() && v[1].isAlive(), "a villager died: compare survivable hits");
                    helper.assertTrue(Math.abs(shipDamage - wallDamage) <= 0.25 * wallDamage + 1.0, "the ship hurt "
                            + fmt(shipDamage) + ", the same wall in the world " + fmt(wallDamage));
                    helper.succeed();
                });
    }

    private static Villager villager(ServerLevel level, Vec3 at, double speed) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) throw new IllegalStateException("no villager");
        villager.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        villager.setNoGravity(true);
        villager.setPersistenceRequired();
        level.addFreshEntity(villager);
        villager.setDeltaMovement(speed / 20.0, 0.0, 0.0);
        villager.hasImpulse = true;
        return villager;
    }

    // ------------------------------------------------------------------
    // A heavy rider on a small raft (the capsize cascade)
    // ------------------------------------------------------------------

    /**
     * An iron golem (3000 kg, what a player with a full inventory of heavy blocks weighs on a deck)
     * dropped two blocks (10 m/s) onto the edge of a 3x1x3 plank raft (4.5 t) held up by four
     * spring "pontoons": the landing tips the raft, which must list and settle - never spin up,
     * never flip, never kill the golem. Before the fix a landing pushed the raft as if it were as
     * stiff as its whole mass (the golem's whole momentum at the edge): from four blocks, 10 rad/s,
     * flipped over, the golem hurled into the ground. (From four blocks, 13 m/s into the deck, the
     * golem now smashes the edge planks it lands on - FullStop's block breaking, with the
     * reduced-mass energy of the collision - and falls through; two blocks keep this a landing.)
     */
    @GameTest(template = FLAT, timeoutTicks = 2400, batch = BATCH)
    public static void aHeavyRiderLandingOnARaftEdgeDoesNotSpinItUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 11);
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 4, 0), o.offset(2, 4, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Raft raft = new Raft(rig);
        helper.onEachTick(rig::record);
        helper.onEachTick(raft::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(raft::start)
                .thenIdle(20)
                .thenExecute(() -> {
                    BoundingBox b = rig.ref.shipyardBlocks();
                    Vec3 above = rig.ref.toWorld(new Vec3(b.maxX() + 0.8, b.maxY() + 3.0, b.minZ() + 1.5));
                    raft.rider = golem(level, above.add(-0.5, 0.0, 0.0));
                })
                .thenWaitUntil(() -> helper.assertTrue(raft.ticks >= 420, "watching the raft"))
                .thenExecute(() -> {
                    report("iron golem (3000 kg) dropped 2 blocks onto the edge of a 3x1x3 plank raft (4.5 t) on springs",
                            raft.summary() + " | landing " + describe(ShipEntityHits.lastHit(raft.rider)));
                    rig.pin();
                    ShipForces.clearContinuous(level, rig.ship.getId(), PONTOON);
                    raft.demandCalm(3.5);
                    helper.succeed();
                });
    }

    /** The same golem put on the deck 0.7 m off-centre, standing: the raft lists to a steady angle. */
    @GameTest(template = FLAT, timeoutTicks = 2400, batch = BATCH)
    public static void aHeavyRiderStandingOffCentreListsARaftSteadily(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 12);
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 4, 0), o.offset(2, 4, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Raft raft = new Raft(rig);
        helper.onEachTick(rig::record);
        helper.onEachTick(raft::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> raft.rider = golem(level, new Vec3(o.getX() + 2.2, o.getY() + 5.0, o.getZ() + 1.5)))
                .thenWaitUntil(() -> helper.assertTrue(rig.ref.equals(ShipEntities.standingOn(raft.rider, 1)), "golem not aboard yet"))
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(raft::start)
                .thenWaitUntil(() -> helper.assertTrue(raft.ticks >= 400, "watching the raft"))
                .thenExecute(() -> {
                    report("iron golem (3000 kg) standing 0.7 m off-centre on a 3x1x3 plank raft (4.5 t) on springs", raft.summary());
                    rig.pin();
                    ShipForces.clearContinuous(level, rig.ship.getId(), PONTOON);
                    raft.demandCalm(1.5);
                    helper.succeed();
                });
    }

    /**
     * An iron golem (3000 kg) standing weightless (the no-gravity flag, as VS Genesis puts on
     * everything in its space) on a floating 3x1x3 plank raft (4.5 t), held against the deck (a
     * small push into it each tick: VS only carries what presses on its deck). The raft floats on
     * its own weight's worth of lift plus a gentle height hold (at most 0.5 m/s^2, to take out the
     * drift of letting it go; the golem's Earth weight would pull at 6.5). For 3 s of physics the
     * raft keeps its height: nothing weighs anything in zero gravity (the golem used to press it
     * down with its full Earth weight). With its gravity back on, the same golem sinks the raft.
     */
    @GameTest(template = FLAT, timeoutTicks = 2400, batch = BATCH)
    public static void aWeightlessRiderDoesNotWeighDownAShip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 15);
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 4, 0), o.offset(2, 4, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Mob[] rider = new Mob[1];
        // the raft's height: when the watch started, lowest while the golem was weightless, when its
        // gravity came back, lowest after
        double[] y = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        long[] since = new long[2];
        boolean[] holding = new boolean[1];
        helper.onEachTick(rig::record);
        helper.onEachTick(() -> {
            if (holding[0]) rig.cruise(Vec3.ZERO, 0.5);
            Mob golem = rider[0];
            if (golem != null && golem.isNoGravity()) {
                Vec3 v = golem.getDeltaMovement();
                golem.setDeltaMovement(v.x, -0.02, v.z);
            }
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> {
                    rider[0] = golem(level, new Vec3(o.getX() + 1.5, o.getY() + 5.0, o.getZ() + 1.5));
                    rider[0].setNoGravity(true);
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.ref.equals(ShipEntities.standingOn(rider[0], 1)), "golem not aboard yet"))
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> {
                    rig.push(Vec3.ZERO);
                    holding[0] = true;
                    since[0] = System.currentTimeMillis();
                })
                // a second for the hold to take the drift out
                .thenWaitUntil(() -> helper.assertTrue(System.currentTimeMillis() - since[0] >= 1000, "settling"))
                .thenExecute(() -> {
                    y[0] = rig.y();
                    y[1] = y[0];
                    since[0] = System.currentTimeMillis();
                    since[1] = level.getGameTime();
                })
                .thenWaitUntil(() -> {
                    y[1] = Math.min(y[1], rig.y());
                    helper.assertTrue(System.currentTimeMillis() - since[0] >= 3000 && level.getGameTime() - since[1] >= 60,
                            "watching the raft");
                })
                .thenExecute(() -> {
                    helper.assertTrue(rig.ref.equals(ShipEntities.standingOn(rider[0], 5)),
                            "the weightless golem isn't on the raft any more");
                    rider[0].setNoGravity(false);
                    y[2] = rig.y();
                    y[3] = y[2];
                    since[0] = System.currentTimeMillis();
                })
                .thenWaitUntil(() -> {
                    y[3] = Math.min(y[3], rig.y());
                    helper.assertTrue(y[2] - y[3] >= 1.0, "the raft hasn't sunk under the golem's weight yet ("
                            + fmt(y[2] - y[3]) + " blocks)");
                })
                .thenExecute(() -> {
                    report("iron golem (3000 kg) standing weightless on a floating 3x1x3 plank raft (4.5 t)", String.format(Locale.ROOT,
                            "sank %.3f blocks in 3 s weightless; with its gravity back, %.2f blocks in %d ms | %s", y[0] - y[1],
                            y[2] - y[3], System.currentTimeMillis() - since[0], rig.summary()));
                    rig.pin();
                    helper.assertTrue(y[0] - y[1] < 0.3, "the weightless golem pressed the raft down " + fmt(y[0] - y[1]) + " blocks");
                    helper.succeed();
                });
    }

    /**
     * A floating 3x1x3 plank raft with a slime block hanging under its middle and a honey block
     * under a corner, pushed to 3 m/s; then the planks they hang from are broken off the moving
     * ship. FullStop's falling slime and honey ({@code fullstop:gravity_affected}) never act on a
     * ship's blocks: the ship holds them as one rigid body, and a falling block at a shipyard
     * position would appear far out in the world. Neither falls, and no falling slime or honey
     * appears anywhere but at the control: the same slime block hanging under a plank in the
     * world, which falls when its plank is broken.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void slimeAndHoneyOnAShipNeverFall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 16);
        Set<BlockPos> hull = fillSet(level, o.offset(0, 4, 0), o.offset(2, 4, 2), Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos slime = o.offset(1, 3, 1);
        BlockPos honey = o.offset(0, 3, 0);
        level.setBlock(slime, Blocks.SLIME_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(honey, Blocks.HONEY_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        hull.add(slime);
        hull.add(honey);
        // the control, in the world, clear of the raft's path
        BlockPos worldPlank = o.offset(1, 4, -6);
        BlockPos worldSlime = worldPlank.below();
        level.setBlock(worldPlank, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(worldSlime, Blocks.SLIME_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        Rig rig = Rig.build(helper, hull, Blocks.OAK_PLANKS, true, 9);
        if (rig == null) return;
        List<String> fallers = new ArrayList<>();
        boolean[] controlFell = new boolean[1];
        long[] since = new long[2];
        helper.onEachTick(rig::record);
        helper.onEachTick(() -> {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof FallingBlockEntity falling)) continue;
                BlockState state = falling.getBlockState();
                if (!state.is(Blocks.SLIME_BLOCK) && !state.is(Blocks.HONEY_BLOCK)) continue;
                if (entity.position().distanceTo(Vec3.atCenterOf(worldSlime)) < 4.0) {
                    controlFell[0] = true;
                } else {
                    String what = state.getBlock().getName().getString() + " at " + entity.blockPosition()
                            + (Ships.inShipyard(level, entity.blockPosition()) ? " (shipyard)" : "");
                    if (!fallers.contains(what)) fallers.add(what);
                }
            }
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> {
                    helper.assertTrue(rig.count(Blocks.SLIME_BLOCK) == 1 && rig.count(Blocks.HONEY_BLOCK) == 1,
                            "the ship didn't get its slime and honey blocks");
                    rig.release();
                })
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> rig.push(new Vec3(3.0, 0.0, 0.0)))
                .thenIdle(10)
                .thenExecute(() -> {
                    List<BlockPos> sticky = new ArrayList<>();
                    rig.ref.forEachBlock(state -> state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK),
                            (pos, state) -> sticky.add(pos.immutable()));
                    for (BlockPos pos : sticky) level.destroyBlock(pos.above(), false);
                    level.destroyBlock(worldPlank, false);
                    since[0] = System.currentTimeMillis();
                    since[1] = level.getGameTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(System.currentTimeMillis() - since[0] >= 2000
                        && level.getGameTime() - since[1] >= 40, "watching the raft"))
                .thenExecute(() -> {
                    int slimes = rig.count(Blocks.SLIME_BLOCK);
                    int honeys = rig.count(Blocks.HONEY_BLOCK);
                    int planks = rig.count(Blocks.OAK_PLANKS);
                    Vec3 v = rig.ref.velocity();
                    boolean controlGone = !level.getBlockState(worldSlime).is(Blocks.SLIME_BLOCK);
                    rig.pin();
                    report("slime and honey hanging under a moving raft, their planks broken", String.format(Locale.ROOT,
                            "raft at %s m/s, %d planks left; on the ship: %d slime, %d honey; falling slime or honey elsewhere: %s; "
                                    + "the world's slime fell %s (falling entity seen %s) | %s",
                            v == null ? "?" : fmt(v.length()), planks, slimes, honeys, fallers, controlGone, controlFell[0],
                            rig.summary()));
                    helper.assertTrue(planks == 7, "the planks weren't broken off the ship (" + planks + " left)");
                    helper.assertTrue(slimes == 1 && honeys == 1, "a ship's slime or honey block fell: " + slimes + " slime, "
                            + honeys + " honey left on it");
                    helper.assertTrue(fallers.isEmpty(), "falling slime or honey away from the control: " + fallers);
                    helper.assertTrue(controlGone && controlFell[0], "the control slime block in the world didn't fall");
                    helper.succeed();
                });
    }

    private static Mob golem(ServerLevel level, Vec3 at) {
        Mob golem = EntityType.IRON_GOLEM.create(level);
        if (golem == null) throw new IllegalStateException("no golem");
        golem.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        golem.goalSelector.removeAllGoals(g -> true);
        golem.targetSelector.removeAllGoals(g -> true);
        golem.setPersistenceRequired();
        level.addFreshEntity(golem);
        return golem;
    }

    /**
     * A raft held up at its centre of mass (its own weight) and by four springs at its bottom
     * corners, each pulling back toward where it hung when the watch started: a restoring torque,
     * so an off-centre load lists it to a steady angle, as water would.
     */
    private static final class Raft {
        static final double K = 12_600.0;
        static final double C = 6_000.0;
        final Rig rig;
        Mob rider;
        boolean started;
        Vec3[] corners;
        double[] restY;
        double maxSpin;
        double maxTilt;
        double lastTilt;
        double lateSpin;
        int ticks;
        final StringBuilder log = new StringBuilder();

        Raft(Rig rig) {
            this.rig = rig;
        }

        void start() {
            rig.hover();
            BoundingBox b = rig.ref.shipyardBlocks();
            corners = new Vec3[]{new Vec3(b.minX(), b.minY(), b.minZ()), new Vec3(b.minX(), b.minY(), b.maxZ() + 1.0),
                    new Vec3(b.maxX() + 1.0, b.minY(), b.minZ()), new Vec3(b.maxX() + 1.0, b.minY(), b.maxZ() + 1.0)};
            restY = new double[4];
            for (int i = 0; i < 4; i++) restY[i] = rig.ref.toWorld(corners[i]).y;
            started = true;
        }

        void record() {
            if (!started) return;
            List<PointForce> forces = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Vec3 w = rig.ref.toWorld(corners[i]);
                Vec3 v = w == null ? null : rig.ref.velocityAt(w);
                if (w == null || v == null) return;
                forces.add(PointForce.at(new Vec3(0.0, K * (restY[i] - w.y) - C * v.y, 0.0), w));
            }
            ShipForces.setContinuous(rig.level, rig.ship.getId(), PONTOON, forces, ShipForces.UNTIL_CLEARED);
            Vec3 w = rig.ref.angularVelocity();
            Vec3 up = rig.ref.dirToWorld(new Vec3(0, 1, 0));
            if (w == null || up == null) return;
            double tilt = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, up.y))));
            maxSpin = Math.max(maxSpin, w.length());
            maxTilt = Math.max(maxTilt, tilt);
            lastTilt = tilt;
            if (ticks >= 300) lateSpin = Math.max(lateSpin, w.length());
            if (ticks++ % 20 == 0) {
                log.append(String.format(Locale.ROOT, " t%d tilt %.1f spin %.2f rider %s hp %.0f |", ticks, tilt, w.length(),
                        rider != null && rig.ref.equals(ShipEntities.standingOn(rider, 5)) ? "aboard" : "off",
                        rider == null ? -1.0 : rider.getHealth()));
            }
        }

        /** Never faster than {@code maxSpinAllowed} rad/s, calm (under 0.3 rad/s) by the end, upright-ish, rider alive and unhurt. */
        void demandCalm(double maxSpinAllowed) {
            rig.helper.assertTrue(maxSpin <= maxSpinAllowed, "the raft spun up to " + fmt(maxSpin) + " rad/s (" + log + ")");
            rig.helper.assertTrue(lateSpin <= 0.3, "the raft was still turning at " + fmt(lateSpin) + " rad/s after 15 s");
            rig.helper.assertTrue(lastTilt <= 60.0, "the raft ended " + fmt(lastTilt) + " degrees over (" + log + ")");
            rig.helper.assertTrue(rider != null && rider.isAlive() && rider.getHealth() >= 0.9f * rider.getMaxHealth(),
                    "the rider was hurt or killed (" + (rider == null ? "?" : fmt(rider.getHealth())) + ")");
        }

        String summary() {
            return String.format(Locale.ROOT, "max spin %.2f rad/s, spin after 15 s %.2f, max tilt %.1f deg, final tilt %.1f deg, "
                            + "rider alive %s hp %.1f ::%s", maxSpin, lateSpin, maxTilt, lastTilt, rider != null && rider.isAlive(),
                    rider == null ? -1.0 : rider.getHealth(), log);
        }
    }

    // ------------------------------------------------------------------
    // No ships needed
    // ------------------------------------------------------------------

    /**
     * Spin nausea on scripted turning (degrees per tick), with the defaults: quick turns and busy
     * mouse-look never make a player dizzy, sustained spinning does, after seconds, and the dizziness
     * drains within a couple of seconds of stopping.
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void spinNauseaNeedsSustainedSpinning(GameTestHelper helper) {
        double threshold = FullStopConfig.SERVER.spinNauseaThreshold.get();
        double turns = FullStopConfig.SERVER.spinNauseaTurns.get();
        helper.assertTrue(threshold == 15.0 && turns == 2.0, "defaults are " + threshold + " / " + turns);
        // never: a quick 180, one fast 360, a busy minute of 90-degree flicks, looking around hard, circling
        int quick180 = dizzyAfter(tick -> tick < 3 ? 60.0 : 0.0, 400, threshold, turns);
        int fast360 = dizzyAfter(tick -> tick < 6 ? 60.0 : 0.0, 400, threshold, turns);
        int flicks = dizzyAfter(tick -> tick % 10 < 2 ? 45.0 : 0.0, 1200, threshold, turns);
        int lookAround = dizzyAfter(tick -> (tick / 20) % 2 == 0 ? 20.0 : 0.0, 1200, threshold, turns);
        int slowCircles = dizzyAfter(tick -> 12.0, 1200, threshold, turns);
        // spinning: 1.7 turns a second, one turn a second
        int spin30 = dizzyAfter(tick -> 30.0, 1200, threshold, turns);
        int spin20 = dizzyAfter(tick -> 20.0, 1200, threshold, turns);
        // recovery: dizzy from 10 s at 30/tick, then still - how long until it lifts
        double rate = 0.0, dose = 0.0;
        for (int t = 0; t < 200; t++) {
            rate = SpinDizziness.rate(rate, SpinDizziness.input(30.0));
            dose = SpinDizziness.dose(dose, rate, threshold, turns);
        }
        int recovered = -1;
        for (int t = 0; t < 400; t++) {
            rate = SpinDizziness.rate(rate, SpinDizziness.input(0.0));
            dose = SpinDizziness.dose(dose, rate, threshold, turns);
            if (!SpinDizziness.dizzy(dose, rate, threshold, turns)) {
                recovered = t;
                break;
            }
        }
        String out = String.format(Locale.ROOT, "quick 180 %s, fast 360 %s, 90-degree flicks for a minute %s, looking around "
                        + "hard for a minute %s, circling at 12/tick %s; spinning at 30/tick dizzy after %s, at 20/tick after %s; "
                        + "recovered %s after stopping", never(quick180), never(fast360), never(flicks), never(lookAround),
                never(slowCircles), ticks(spin30), ticks(spin20), ticks(recovered));
        report("spin nausea", out);
        helper.assertTrue(quick180 < 0 && fast360 < 0 && flicks < 0 && lookAround < 0 && slowCircles < 0,
                "ordinary turning made a player dizzy: " + out);
        helper.assertTrue(spin30 >= 40 && spin30 <= 120, "spinning at 30/tick: dizzy after " + spin30 + " ticks");
        helper.assertTrue(spin20 >= 100 && spin20 <= 400, "spinning at 20/tick: dizzy after " + spin20 + " ticks");
        helper.assertTrue(recovered >= 0 && recovered <= 60, "still dizzy " + recovered + " ticks after stopping");
        helper.succeed();
    }

    private interface Turning {
        double at(int tick);
    }

    /** The tick at which {@code turning} first makes a player dizzy, or -1 within {@code ticks}. */
    private static int dizzyAfter(Turning turning, int ticks, double threshold, double turns) {
        double rate = 0.0, dose = 0.0;
        for (int t = 0; t < ticks; t++) {
            rate = SpinDizziness.rate(rate, SpinDizziness.input(turning.at(t)));
            dose = SpinDizziness.dose(dose, rate, threshold, turns);
            if (SpinDizziness.dizzy(dose, rate, threshold, turns)) return t;
        }
        return -1;
    }

    private static String never(int tick) {
        return tick < 0 ? "never" : "DIZZY at t" + tick;
    }

    private static String ticks(int tick) {
        return tick < 0 ? "never" : tick + " ticks (" + fmt(tick / 20.0) + " s)";
    }

    /**
     * What a ship's own throw leaves behind: the thrown rider pushes that ship (not another)
     * no more until it has stood on the ground for half a second, three seconds at most.
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void aThrownRiderDoesNotPushTheShipThatThrewIt(GameTestHelper helper) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel());
        helper.assertTrue(villager != null, "no villager");
        FullStopCapability cap = new FullStopCapability(villager);
        cap.markThrownBy(7L, 100L);
        boolean right = cap.isShipFeedbackBlocked(7L, 101L) && cap.isShipFeedbackBlocked(7L, 150L)
                && !cap.isShipFeedbackBlocked(8L, 101L) && !cap.isShipFeedbackBlocked(7L, 161L) && !cap.isShipFeedbackBlocked(7L, 99L);
        helper.assertTrue(right, "the thrown-rider guard doesn't hold the thrower (and only it) for its window");
        helper.succeed();
    }

    /**
     * What a ship strikes as one body: a plain-Entity vehicle (an Automobility/MKW kart is one)
     * carrying a living rider, with the rider; the bare vehicle is not a body.
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void aVehicleWithARiderIsOneBodyForShips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Entity vehicle = EntityType.ITEM_DISPLAY.create(level);
        Villager rider = EntityType.VILLAGER.create(level);
        helper.assertTrue(vehicle != null && rider != null, "no entities");
        boolean bare = ShipContactPhysics.isBody(vehicle);
        helper.assertTrue(rider.startRiding(vehicle, true), "the villager wouldn't ride");
        boolean ridden = ShipContactPhysics.isBody(vehicle);
        double kg = ShipContactPhysics.bodyKg(vehicle);
        report("vehicle bodies", "bare plain-entity vehicle is a body: " + bare + "; with a villager on it: " + ridden
                + ", weighing " + fmt(kg) + " kg (villager " + fmt(Weights.bodyMassKg(rider)) + ")");
        helper.assertTrue(!bare && ridden, "a plain-entity vehicle with a rider must be a body, without one not");
        helper.assertTrue(kg >= Weights.bodyMassKg(rider), "the rider's mass isn't part of the body");
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // Vehicles and their riders (a boat stands in for a kart: MKW isn't on this runtime)
    // ------------------------------------------------------------------

    /**
     * A 3x3x3 plank ship (13.5 t) pushed at 12 m/s into a boat with a villager in it (slow enough
     * that the boat survives - harder, it breaks and throws the villager out): the ride
     * stack is one body - the boat is launched with the villager's mass counted in, by the launch
     * rule, and the villager takes its share of the hit.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aShipHitsABoatAndItsRiderAsOneBody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 13);
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 1, 0), o.offset(2, 3, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Entity[] boat = new Entity[1];
        Villager[] rider = new Villager[1];
        double[] before = {0.0};
        double[] launch = {Double.NaN};
        double[] shipAt = {0.0};
        float[] health = new float[1];
        helper.onEachTick(rig::record);
        helper.onEachTick(() -> {
            if (boat[0] == null || !Double.isNaN(launch[0])) return;
            double vx = boat[0].getDeltaMovement().x * 20.0;
            Vec3 ship = rig.ref.velocity();
            if (vx > 1.0) {
                launch[0] = vx;
                shipAt[0] = ship == null ? Double.NaN : ship.x;
            } else {
                before[0] = vx;
            }
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> {
                    Entity b = EntityType.BOAT.create(level);
                    Villager v = EntityType.VILLAGER.create(level);
                    helper.assertTrue(b != null && v != null, "no boat or villager");
                    // hovering (no gravity) at the hull's height, nothing behind it
                    b.moveTo(o.getX() + 12.5, o.getY() + 2.2, o.getZ() + 1.5, -90.0f, 0.0f);
                    b.setNoGravity(true);
                    level.addFreshEntity(b);
                    v.moveTo(b.getX(), b.getY(), b.getZ(), -90.0f, 0.0f);
                    v.setPersistenceRequired();
                    level.addFreshEntity(v);
                    helper.assertTrue(v.startRiding(b, true), "the villager wouldn't get in the boat");
                    boat[0] = b;
                    rider[0] = v;
                    health[0] = v.getHealth();
                })
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> rig.push(new Vec3(12.0, 0.0, 0.0)))
                .thenWaitUntil(() -> helper.assertTrue(!Double.isNaN(launch[0]), "the boat hasn't been hit yet (ship at "
                        + rig.ref.position() + ")"))
                .thenIdle(3)
                .thenExecute(() -> {
                    double stackKg = ShipContactPhysics.bodyKg(boat[0]);
                    double approach = shipAt[0] - before[0];
                    double share = rig.ref.mass() / (rig.ref.mass() + stackKg);
                    double expected = before[0] + (1.0 + FullStopConfig.SERVER.entityRestitution.get()) * approach * share;
                    rig.pin();
                    report("ship at 12 m/s into a boat with a villager in it", String.format(Locale.ROOT,
                            "ship %.2f m/s, boat + villager %.1f kg (villager %.1f), boat launched %.2f m/s (rule %.2f, %.1f%% off), "
                                    + "villager health %.1f -> %.1f, still aboard %s", shipAt[0], stackKg,
                            Weights.bodyMassKg(rider[0]), launch[0], expected, 100.0 * Math.abs(launch[0] - expected) / expected,
                            health[0], rider[0].getHealth(), rider[0].isPassenger()));
                    helper.assertTrue(stackKg > Weights.bodyMassKg(boat[0]), "the rider's mass isn't part of the struck body");
                    helper.assertTrue(Math.abs(launch[0] - expected) <= 0.1 * expected, "the boat was launched at "
                            + fmt(launch[0]) + " m/s, the rule says " + fmt(expected));
                    helper.assertTrue(rider[0].getHealth() < health[0], "the villager in the boat took no share of the hit");
                    helper.succeed();
                });
    }

    /**
     * A boat with a villager in it thrown at 40 m/s into a stone wall in the world: the boat
     * takes the crash (it breaks) and the villager, whose own box never touched the wall, its
     * share - before, a vehicle's riders shared only its landings.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aBoatCrashingIntoAWallHurtsItsRider(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 14);
        fill(level, o.offset(8, 0, -2), o.offset(8, 4, 3), Blocks.STONE.defaultBlockState());
        Villager[] rider = new Villager[1];
        float[] health = new float[1];
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    Entity b = EntityType.BOAT.create(level);
                    Villager v = EntityType.VILLAGER.create(level);
                    helper.assertTrue(b != null && v != null, "no boat or villager");
                    b.moveTo(o.getX() + 4.5, o.getY() + 1.0, o.getZ() + 0.5, -90.0f, 0.0f);
                    level.addFreshEntity(b);
                    v.moveTo(b.getX(), b.getY(), b.getZ(), -90.0f, 0.0f);
                    v.setPersistenceRequired();
                    level.addFreshEntity(v);
                    helper.assertTrue(v.startRiding(b, true), "the villager wouldn't get in the boat");
                    b.setDeltaMovement(2.0, 0.05, 0.0);
                    b.hasImpulse = true;
                    rider[0] = v;
                    health[0] = v.getHealth();
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    report("boat with a villager thrown at 40 m/s into a stone wall", String.format(Locale.ROOT,
                            "villager health %.1f -> %.1f, still in the boat %s", health[0], rider[0].getHealth(), rider[0].isPassenger()));
                    helper.assertTrue(rider[0].getHealth() < health[0], "the villager took nothing from its boat's crash");
                    helper.succeed();
                });
    }

    // ------------------------------------------------------------------

    private static String describe(@Nullable ShipEntityHits.Hit hit) {
        if (hit == null) return "no ship collision recorded";
        ShipContactPhysics.Exchange ex = hit.exchange();
        return String.format(Locale.ROOT, "%s with ship %d at t%d: approach %.2f m/s, entity %.0f kg vs ship %s kg (effective), "
                        + "e %.2f, speed lost %.2f m/s, kick %.2f m/s, ship pushed %.0f N s", hit.landing() ? "landing" : "hit",
                hit.shipId(), hit.tick(), ex.approach(), ex.entityKg(), Double.isInfinite(ex.shipKg()) ? "inf" : fmt(ex.shipKg()),
                ex.restitution(), ex.speedLost(), ex.kick(), hit.pushed());
    }
}
