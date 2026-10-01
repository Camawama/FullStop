package net.camacraft.fullstop.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.vs.ShipCollision;
import net.camacraft.camafy.compat.vs.ShipCollisions;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.server.physics.ship.ShipDeckMomentum;
import net.camacraft.fullstop.server.physics.ship.ShipTracker;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.weight.Weights;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.properties.ChunkClaim;
import org.valkyrienskies.core.api.events.CollisionEvent;
import org.valkyrienskies.core.api.physics.ContactPoint;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Real Valkyrien Skies ships crashed into real terrain, with FullStop's whole pipeline running:
 * Camafy's collision bridge, the toughness rule, hull damage, momentum, drops, the vegetation
 * sweep. And the check nobody could make from source: what VS's collision events actually carry
 * (Camafy's {@code ShipCollisions} is built on VS beta API) - every contact this test's ship
 * reports is logged, and its points must lie in WORLD space at the ship, with normals pointing
 * from the terrain toward the ship. VS's own events are counted too ({@code VS raw events} in
 * each summary), to tell "VS reports nothing" from "Camafy publishes nothing".
 *
 * <p><b>Names VS types</b> ({@link ShipAssembler}, {@link ServerShip}, VS's collision events) to
 * build and watch ships, so it is compiled only with {@code -PshipTests}:
 * {@code gradlew runGameTestServer -PshipTests [-PvsBackend=classic|physx|jolt]}. That run seeds
 * the test world's VS config with the backend asked for (PhysX by default; build.gradle). VS
 * 2.4.11 fires collision events only on PhysX and Jolt; its default, KRUNCH_CLASSIC, fires none at
 * all - ships land on and bounce off terrain, and VS reports nothing - so there Camafy's collision
 * fallback must report the crashes. Every test checks that Camafy used the source the backend
 * calls for, and nothing else, for the whole run ({@code source} in each summary): on PhysX and
 * Jolt the fallback must never run, so no crash can be reported twice. Three tests are about
 * Camafy's contacts rather than FullStop's rules: a ship resting on the ground and one sliding
 * and turning slowly on it must produce no fallback contact, and a ship dropped on another (static)
 * ship must be reported as a ship-to-ship collision on every backend.
 *
 * <p>How a scenario runs: terrain and ship blocks are placed, the blocks assembled into a ship,
 * held static through FullStop's new-ship grace, then let go - to fall, or (floating ships, held
 * up by a continuous force equal to their weight) pushed with Camafy's impulse once VS is
 * simulating them; an impulse queued in the tick a ship stops being static is lost. The tests
 * wait on what the ship does, never on a fixed number of ticks: the test server runs ahead of
 * real time while VS's physics thread keeps it. Afterwards the ship is pinned where it is.
 *
 * <p><b>Terrain first, then VS.</b> VS hands a chunk's terrain to its physics when the chunk
 * gets a ticket ({@code MixinServerLevel} at the end of each level tick), and on this test server
 * blocks placed after that did not collide at all: test ships fell through platforms and flew
 * through walls built on an already-loaded plot, and landed on the plot's own floor, which was
 * there first. So each scenario is built in chunks nobody has loaded, force-loaded and filled
 * in the same tick.
 *
 * <p><b>A watcher per ship.</b> VS only simulates ships near a player: it loads a ship within
 * 128 blocks of someone in the server's player list and unloads it beyond 196 (its
 * {@code shipLoadDistance} / {@code shipUnloadDistance}), and it loads a ship's shipyard chunks
 * for the players watching it. A game test server has no players, so a freshly assembled ship
 * never gets its blocks (VS times out waiting for the shipyard chunk to tick) and unloads the
 * moment it is let go. Each rig therefore puts a fake player next to its ship in the player
 * list and force-loads the ship's chunks - standing in for the player who would be watching the
 * crash in a real game - and takes it out again at the end.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipCollisionTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    /** Held static a little longer than FullStop's new-ship grace (newShipGraceTicks = 40). */
    private static final int GRACE = 45;
    /**
     * Generous: the batch starts behind (the server catches up dozens of ticks as fast as it can)
     * while VS's physics thread keeps real time, so the tests wait on what the ship does, never on
     * a fixed number of ticks.
     */
    static final int TIMEOUT = 1200;
    static final ResourceLocation FLOAT = new ResourceLocation(FullStop.MODID, "gametest_float");
    /** A test's own engine: {@link Rig#cruise} holds a ship at a set velocity with it. */
    static final ResourceLocation THRUST = new ResourceLocation(FullStop.MODID, "gametest_thrust");
    /** The VS backend this run is on ({@code -PvsBackend}, handed over by build.gradle). */
    static final String BACKEND = System.getProperty("fullstop.test.vsBackend", "physx");
    /** Where Camafy must take this run's collisions from. */
    private static final ShipCollisions.Source EXPECTED_SOURCE = expectedSource();
    /**
     * {@code -PhideBackend}: Camafy can't tell the backend, so its fallback runs until VS's first
     * collision event (on PhysX and Jolt, the first ship settling on the ground) and VS's events
     * after that.
     */
    private static final boolean HIDDEN_BACKEND = "unknown".equalsIgnoreCase(System.getProperty("camafy.vsPhysicsBackend", ""));
    /** Game ticks the quiet tests watch a ship resting or sliding on the ground. */
    private static final int QUIET_TICKS = 300;

    private static ShipCollisions.Source expectedSource() {
        String fallback = System.getProperty("camafy.collisionFallback", "auto").trim().toLowerCase(Locale.ROOT);
        if (fallback.equals("force")) return ShipCollisions.Source.FALLBACK;
        if (fallback.equals("off")) return ShipCollisions.Source.NATIVE;
        return BACKEND.equals("classic") ? ShipCollisions.Source.FALLBACK : ShipCollisions.Source.NATIVE;
    }

    private ShipCollisionTests() {
    }

    /**
     * VS's own collision events, counted straight off the physics thread per ship id - to tell
     * "VS reports nothing" from "Camafy publishes nothing". Registered by the first rig.
     */
    private static final Map<Long, AtomicInteger> RAW_EVENTS = new ConcurrentHashMap<>();
    private static final AtomicInteger RAW_LOGGED = new AtomicInteger();
    private static boolean rawListening;

    private static synchronized void listenRaw() {
        if (rawListening) return;
        rawListening = true;
        Consumer<CollisionEvent> start = e -> raw(e, "start");
        Consumer<CollisionEvent> persist = e -> raw(e, "persist");
        ValkyrienSkies.api().getCollisionStartEvent().on(start);
        ValkyrienSkies.api().getCollisionPersistEvent().on(persist);
        LOGGER.info("[FullStop ship test] listening to VS collision events directly");
    }

    private static void raw(CollisionEvent e, String kind) {
        RAW_EVENTS.computeIfAbsent(e.getShipIdA(), id -> new AtomicInteger()).incrementAndGet();
        RAW_EVENTS.computeIfAbsent(e.getShipIdB(), id -> new AtomicInteger()).incrementAndGet();
        if (RAW_LOGGED.incrementAndGet() <= 40) {
            StringBuilder points = new StringBuilder();
            for (ContactPoint c : e.getContactPoints()) {
                if (points.length() > 400) break;
                points.append(String.format(Locale.ROOT, " [p(%.2f,%.2f,%.2f) n(%.2f,%.2f,%.2f) v(%.2f,%.2f,%.2f) sep %.3f]",
                        c.getPosition().x(), c.getPosition().y(), c.getPosition().z(), c.getNormal().x(), c.getNormal().y(),
                        c.getNormal().z(), c.getVelocity().x(), c.getVelocity().y(), c.getVelocity().z(), c.getSeparation()));
            }
            LOGGER.info("[FullStop ship test] VS {} event {}: A {} B {}, {} point(s){}", kind, e.getDimensionId(),
                    e.getShipIdA(), e.getShipIdB(), e.getContactPoints().size(), points);
        }
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    /**
     * A 5x5x5 oak-plank ship (62.5 t) flown into a stone wall at 12 m/s: the wall holds, the hull
     * loses planks where it hit, and they drop into the world - not into the shipyard.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aPlankShipRammedIntoStoneLosesPlanksAndTheWallHolds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper);
        List<BlockPos> wall = fill(level, o.offset(8, 0, -2), o.offset(9, 8, 6), Blocks.STONE.defaultBlockState());
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 1, 0), o.offset(4, 5, 4), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Vec3 wallFace = Vec3.atLowerCornerOf(o.offset(8, 3, 2));

        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> rig.push(new Vec3(12.0, 0.0, 0.0)))
                .thenWaitUntil(() -> rig.demandCrashedAndSettled(20))
                .thenExecute(() -> {
                    int planksAfter = rig.count(Blocks.OAK_PLANKS);
                    int wallBroken = broken(level, wall, Blocks.STONE);
                    // Drops fall: look well below the crash too.
                    int worldDrops = items(level, new AABB(wallFace, wallFace).inflate(16.0), Blocks.OAK_PLANKS.asItem());
                    int shipyardDrops = rig.shipyardItems(Blocks.OAK_PLANKS.asItem());
                    rig.pin();
                    report("plank ship into stone wall", "planks " + rig.blocks + " -> " + planksAfter
                            + ", wall blocks broken " + wallBroken + ", plank drops in world " + worldDrops
                            + ", in shipyard " + shipyardDrops + " | " + rig.summary());
                    rig.demandContacts(new Vec3(-1, 0, 0));
                    helper.assertTrue(wallBroken == 0, wallBroken + " stone blocks of the wall broke under a wooden hull");
                    helper.assertTrue(planksAfter < rig.blocks, "the plank hull lost nothing slamming into stone at 12 m/s");
                    helper.assertTrue(shipyardDrops == 0, "hull drops landed in the shipyard, not the world");
                    helper.assertTrue(worldDrops > 0, "the broken planks dropped nothing into the world");
                    helper.succeed();
                });
    }

    /** A 3x3x3 stone ship (67.5 t) dropped ten blocks onto grass: grass (and dirt) break, the stone floor holds. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aStoneShipDroppedOnGrassCrushesItButNotTheStoneBelow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper);
        List<BlockPos> floor = fill(level, o.offset(-2, 0, -2), o.offset(4, 0, 4), Blocks.STONE.defaultBlockState());
        fill(level, o.offset(-2, 1, -2), o.offset(4, 2, 4), Blocks.DIRT.defaultBlockState());
        List<BlockPos> grass = fill(level, o.offset(-2, 3, -2), o.offset(4, 3, 4), Blocks.GRASS_BLOCK.defaultBlockState());
        List<BlockPos> under = new ArrayList<>();
        for (BlockPos pos : grass) {
            int dx = pos.getX() - o.getX(), dz = pos.getZ() - o.getZ();
            if (dx >= 0 && dx <= 2 && dz >= 0 && dz <= 2) under.add(pos);
        }
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 13, 0), o.offset(2, 15, 2), Blocks.STONE.defaultBlockState()),
                Blocks.STONE, false);
        if (rig == null) return;

        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(() -> rig.demandCrashedAndSettled(30))
                .thenExecute(() -> {
                    int grassBroken = broken(level, under, Blocks.GRASS_BLOCK);
                    int floorBroken = broken(level, floor, Blocks.STONE);
                    double fell = rig.start.y - rig.y();
                    rig.pin();
                    report("stone ship dropped on grass", "fell " + fmt(fell) + " blocks, grass under it broken "
                            + grassBroken + "/" + under.size() + ", stone floor broken " + floorBroken + " | " + rig.summary());
                    rig.demandContacts(new Vec3(0, 1, 0));
                    helper.assertTrue(fell > 8.0, "the ship fell only " + fmt(fell) + " blocks");
                    helper.assertTrue(grassBroken > 0, "a 67.5 t stone ship dropped ten blocks crushed no grass");
                    helper.assertTrue(floorBroken == 0, "the stone floor under the dirt broke");
                    helper.succeed();
                });
    }

    /** A 2x2x2 obsidian ship (40 t) dropped ten blocks onto obsidian: nothing breaks on either side. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void obsidianDroppedOnObsidianBreaksNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper);
        List<BlockPos> floor = fill(level, o.offset(-1, 0, -1), o.offset(2, 0, 2), Blocks.OBSIDIAN.defaultBlockState());
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 10, 0), o.offset(1, 11, 1), Blocks.OBSIDIAN.defaultBlockState()),
                Blocks.OBSIDIAN, false);
        if (rig == null) return;

        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(() -> rig.demandCrashedAndSettled(30))
                .thenExecute(() -> {
                    int floorBroken = broken(level, floor, Blocks.OBSIDIAN);
                    int hullAfter = rig.count(Blocks.OBSIDIAN);
                    double fell = rig.start.y - rig.y();
                    rig.pin();
                    report("obsidian on obsidian", "fell " + fmt(fell) + " blocks, floor broken " + floorBroken
                            + ", hull " + rig.blocks + " -> " + hullAfter + " | " + rig.summary());
                    rig.demandContacts(new Vec3(0, 1, 0));
                    helper.assertTrue(fell > 8.0, "the ship fell only " + fmt(fell) + " blocks");
                    helper.assertTrue(floorBroken == 0, "obsidian terrain broke under an obsidian ship");
                    helper.assertTrue(hullAfter == rig.blocks, "the obsidian hull broke on obsidian");
                    helper.succeed();
                });
    }

    /** A plank ship flown through a leaf canopy at 10 m/s clears the leaves in its path. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aFastShipPloughsThroughLeaves(GameTestHelper helper) {
        leafRun(helper, 10.0, 5, 7.0, true);
    }

    /** The same ship drifting through at 2 m/s leaves every leaf where it was. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aDriftingShipSlidesThroughLeavesUntouched(GameTestHelper helper) {
        leafRun(helper, 2.0, 3, 3.5, false);
    }

    /**
     * A 3x3x3 plank ship (13.5 t, front face at x+3) floated through a 3-deep block of natural oak
     * leaves starting at {@code leavesFromX}, until it has moved {@code distance} blocks.
     */
    private static void leafRun(GameTestHelper helper, double speed, int leavesFromX, double distance, boolean expectCleared) {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper);
        // Natural leaves (not player-placed), set without neighbour updates so they don't start
        // decaying for want of a log.
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1);
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(o.offset(leavesFromX, 0, -1), o.offset(leavesFromX + 2, 4, 3))) {
            level.setBlock(pos, leaf, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            int dy = pos.getY() - o.getY(), dz = pos.getZ() - o.getZ();
            if (dy >= 1 && dy <= 3 && dz >= 0 && dz <= 2) path.add(pos.immutable());
        }
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 1, 0), o.offset(2, 3, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;

        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> rig.push(new Vec3(speed, 0.0, 0.0)))
                .thenWaitUntil(() -> helper.assertTrue(rig.x() - rig.start.x >= distance,
                        "the ship has moved " + fmt(rig.x() - rig.start.x) + " of " + distance + " blocks"))
                .thenExecute(() -> {
                    double moved = rig.x() - rig.start.x;
                    int cleared = broken(level, path, Blocks.OAK_LEAVES);
                    rig.pin();
                    report("plank ship through leaves at " + speed + " m/s", "moved " + fmt(moved)
                            + " blocks, leaves in its path cleared " + cleared + "/" + path.size() + " | " + rig.summary());
                    rig.demandSource();
                    if (expectCleared) {
                        helper.assertTrue(cleared >= path.size() / 2,
                                "a ship at " + speed + " m/s cleared only " + cleared + " of " + path.size() + " leaves in its path");
                    } else {
                        helper.assertTrue(cleared == 0, "a ship drifting at " + speed + " m/s broke " + cleared + " leaves");
                    }
                    helper.succeed();
                });
    }

    // ------------------------------------------------------------------
    // Camafy's contacts: quiet ships, ship against ship
    // ------------------------------------------------------------------

    /**
     * A 3x3x3 stone ship let go while standing on the ground: no crash, so Camafy's collision
     * fallback must report nothing for it over {@link #QUIET_TICKS} ticks. (VS's own events may
     * report a few slow contact starts as it settles; those are logged, not failed.)
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void camafyReportsNothingForAShipRestingOnTheGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper);
        Rig rig = Rig.build(helper, fillSet(level, o, o.offset(2, 2, 2), Blocks.STONE.defaultBlockState()), Blocks.STONE, false);
        if (rig == null) return;
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenIdle(40)
                .thenExecute(rig::startWatching)
                .thenWaitUntil(() -> rig.demandWatched(QUIET_TICKS))
                .thenExecute(() -> {
                    double moved = rig.ref.position().distanceTo(rig.start);
                    rig.pin();
                    report("stone ship resting on the ground", rig.windowSummary() + ", moved " + fmt(moved)
                            + " blocks | " + rig.summary());
                    rig.demandSource();
                    rig.demandQuiet();
                    helper.succeed();
                });
    }

    /**
     * A 3x1x3 plank raft standing on the ground, pushed to 2 m/s along it and set turning at 0.5
     * rad/s: it slides and turns until friction stops it. No crash, so again no fallback contact.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void camafyReportsNothingForAShipSlidingAndTurningSlowlyOnTheGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper);
        Rig rig = Rig.build(helper, fillSet(level, o, o.offset(2, 0, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, false);
        if (rig == null) return;
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenIdle(40)
                .thenExecute(() -> {
                    rig.push(new Vec3(2.0, 0.0, 0.0));
                    // a 3x3 raft turns about y with I = m (3^2 + 3^2) / 12
                    ShipForces.applyAngularImpulse(level, rig.ship.getId(), new Vec3(0.0, 1.5 * rig.ref.mass() * 0.5, 0.0));
                    rig.startWatching();
                })
                .thenWaitUntil(() -> rig.demandWatched(QUIET_TICKS))
                .thenExecute(() -> {
                    double moved = rig.ref.position().distanceTo(rig.start);
                    rig.pin();
                    report("plank raft sliding and turning on the ground", rig.windowSummary() + ", slid " + fmt(moved)
                            + " blocks, fastest seen " + fmt(rig.fastestSeen) + " m/s | " + rig.summary());
                    rig.demandSource();
                    helper.assertTrue(moved > 0.2, "the raft never slid (moved " + fmt(moved) + ")");
                    rig.demandQuiet();
                    helper.succeed();
                });
    }

    /**
     * A 2x2x2 plank ship dropped six blocks onto a static 5x1x5 stone ship: a ship-to-ship
     * collision, reported from both sides with normals pointing from the other ship toward "its"
     * ship, and no terrain contact for the falling ship.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void camafyReportsAShipDroppedOnAnotherShip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper);
        Rig deck = Rig.build(helper, fillSet(level, o.offset(-1, 3, -1), o.offset(3, 3, 3), Blocks.STONE.defaultBlockState()),
                Blocks.STONE, false);
        if (deck == null) return;
        Rig cube = Rig.build(helper, fillSet(level, o.offset(0, 10, 0), o.offset(1, 11, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, false);
        if (cube == null) return;
        helper.onEachTick(deck::record);
        helper.onEachTick(cube::record);
        helper.startSequence()
                .thenWaitUntil(deck::demandReady)
                .thenWaitUntil(cube::demandReady)
                .thenExecute(cube::release)
                .thenWaitUntil(() -> cube.demandShipHitAndSettled(deck, 30))
                .thenExecute(() -> {
                    double fell = cube.start.y - cube.y();
                    cube.pin();
                    deck.pin();
                    report("plank ship dropped on a static stone ship", "fell " + fmt(fell) + " blocks | cube: "
                            + cube.summary() + " | deck: " + deck.summary());
                    cube.demandSource();
                    helper.assertTrue(fell > 3.0, "the plank ship fell only " + fmt(fell) + " blocks");
                    cube.demandShipContacts(deck, new Vec3(0, 1, 0));
                    deck.demandShipContacts(cube, new Vec3(0, -1, 0));
                    helper.assertTrue(cube.terrainHits.isEmpty(), "the plank ship landed on the other ship but "
                            + cube.terrainHits.size() + " terrain collision(s) were reported for it");
                    helper.succeed();
                });
    }

    /**
     * A 16x2x16 plank raft (512 blocks) dropped three blocks flat onto stone: a big flat landing,
     * reported with normals pointing up. On KRUNCH_CLASSIC this is the fallback's worst case in
     * these tests (256 hull blocks face the ground); Camafy logs what probing it cost.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void camafyReportsABigRaftLandingFlat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper);
        fill(level, o.offset(-6, 0, -6), o.offset(13, 0, 13), Blocks.STONE.defaultBlockState());
        Rig rig = Rig.build(helper, fillSet(level, o.offset(-4, 4, -4), o.offset(11, 5, 11), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, false);
        if (rig == null) return;
        helper.onEachTick(rig::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(() -> rig.demandCrashedAndSettled(20))
                .thenExecute(() -> {
                    double fell = rig.start.y - rig.y();
                    int planksAfter = rig.count(Blocks.OAK_PLANKS);
                    rig.pin();
                    report("16x2x16 plank raft dropped flat on stone", "fell " + fmt(fell) + " blocks, planks " + rig.blocks
                            + " -> " + planksAfter + " | " + rig.summary());
                    rig.demandContacts(new Vec3(0, 1, 0));
                    helper.succeed();
                });
    }

    /**
     * A 12x1x12 stone raft (144 blocks) dropped one block onto a stone floor, then thrown up at 4
     * m/s four more times: every landing must be reported, as one burst of records, and nothing
     * while it flies or rests (with the fallback; VS's own events may add slow starts, which are
     * logged). Stone on stone: nothing breaks, so the hull, and the fallback's cached surface of
     * it, stay the same - later landings show what probing costs once the code is warm.
     */
    @GameTest(template = FLAT, timeoutTicks = 2400)
    public static void camafyReportsEveryLandingOfAHoppingRaft(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper);
        fill(level, o.offset(-4, 0, -4), o.offset(15, 0, 15), Blocks.STONE.defaultBlockState());
        Rig rig = Rig.build(helper, fillSet(level, o.offset(-2, 2, -2), o.offset(9, 2, 9), Blocks.STONE.defaultBlockState()),
                Blocks.STONE, false);
        if (rig == null) return;
        int hops = 4;
        helper.onEachTick(rig::record);
        var sequence = helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(() -> rig.demandLandedAndResting(1));
        for (int hop = 1; hop <= hops; hop++) {
            int landings = hop + 1;
            sequence = sequence
                    .thenExecute(() -> ShipForces.applyImpulse(level, rig.ship.getId(), new Vec3(0.0, 4.0 * rig.ref.mass(), 0.0), null))
                    .thenWaitUntil(() -> rig.demandLandedAndResting(landings));
        }
        sequence.thenExecute(() -> {
            rig.pin();
            report("stone raft hopping on stone", hops + 1 + " landings, " + rig.bursts + " bursts of hard contacts "
                    + rig.burstLog + " | " + rig.summary());
            rig.demandSource();
            rig.demandContacts(new Vec3(0, 1, 0));
            if (EXPECTED_SOURCE == ShipCollisions.Source.FALLBACK) {
                helper.assertTrue(rig.bursts == hops + 1, "the fallback reported " + rig.bursts + " bursts for "
                        + (hops + 1) + " landings: " + rig.burstLog);
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------
    // Ships hitting entities (no collision events involved: every backend)
    // ------------------------------------------------------------------

    /** Batch whose tests run with {@code damageEntities = false} (set and restored around it). */
    private static final String NO_DAMAGE_BATCH = "fullstop_ship_hits_no_damage";

    /**
     * A 3x3x3 plank ship (13.5 t) floated into a standing villager at 36 m/s, open ground behind it:
     * the villager dies, and is still launched along the hull's normal at
     * {@code (1 + entityRestitution) * approach * m_ship / (m_ship + m_villager)}.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aShipAt36MpsKillsAVillagerAndStillLaunchesIt(GameTestHelper helper) {
        shipHit(helper, hitLane(helper, 0), 36.0, true, false, "ship at 36 m/s into a villager");
    }

    /**
     * The same at 60 m/s, the ship held at speed against VS's air drag (which took a pushed 60 m/s
     * down to 48 by the villager). This server ticks faster than VS's 60 Hz physics, so most ticks
     * bring the ship no new physics frame: ShipTracker used to take the missing travel for a
     * teleport above 40 m/s and keep the ship in jump grace, where it hit nothing. The summary
     * logs how many ticks of grace there were (must be none).
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aShipAt60MpsKillsAVillagerAndStillLaunchesIt(GameTestHelper helper) {
        shipHit(helper, hitLane(helper, 1), 60.0, true, true, "ship at 60 m/s into a villager");
    }

    /** 80 m/s, held at speed: the villager is killed and still launched at the rule's speed (~104 m/s). */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aShipAt80MpsKillsAVillagerAndStillLaunchesIt(GameTestHelper helper) {
        shipHit(helper, hitLane(helper, 4), 80.0, true, true, "ship at 80 m/s into a villager");
    }

    /** 120 m/s, held at speed. Optional: how fast VS will fly a ship and FullStop still hit with it. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, required = false)
    public static void aShipAt120MpsKillsAVillagerAndStillLaunchesIt(GameTestHelper helper) {
        shipHit(helper, hitLane(helper, 5), 120.0, true, true, "ship at 120 m/s into a villager");
    }

    /** 200 m/s, held at speed. Optional, as above. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, required = false)
    public static void aShipAt200MpsKillsAVillagerAndStillLaunchesIt(GameTestHelper helper) {
        shipHit(helper, hitLane(helper, 6), 200.0, true, true, "ship at 200 m/s into a villager");
    }

    /** Control: the same hit at 8 m/s; the villager survives and gets the same launch rule. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT)
    public static void aShipAt8MpsLaunchesAVillagerItDoesNotKill(GameTestHelper helper) {
        shipHit(helper, hitLane(helper, 2), 8.0, false, false, "ship at 8 m/s into a villager");
    }

    /** Control: 36 m/s with {@code damageEntities = false}; no damage, the same launch as the kill. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = NO_DAMAGE_BATCH)
    public static void aShipAt36MpsLaunchesAVillagerWithDamageOff(GameTestHelper helper) {
        shipHit(helper, hitLane(helper, 3), 36.0, false, false, "ship at 36 m/s into a villager, damageEntities = false");
    }

    /**
     * FullStop's pin check for a villager standing on open ground, pushed along a normal that
     * points a hair downward, (1, -1e-9, 0), or a few degrees down: nothing but air is in the way,
     * so it must not count as pinned. It used to: {@code isPinned} probed
     * {@code box.move(normal * 0.25)}, which sank the box into the block it stands on, and a hull
     * whose rotation carries float noise produces exactly such normals - the villager was crushed
     * instead of launched. Real pins must still count: the same pushes with a wall right in front
     * of it, and a hull coming down on it (pressed into the ground).
     */
    @GameTest(template = FLAT)
    public static void aVillagerStandingOnOpenGroundIsNotPinned(GameTestHelper helper) throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos o = origin(helper, AWAY + 2048);
        Villager open = standingVillager(helper, level, o);
        // a second one with a two-block stone wall right in front of it (+x)
        BlockPos walled = o.offset(0, 0, 4);
        fill(level, walled.offset(1, 0, 0), walled.offset(1, 1, 0), Blocks.STONE.defaultBlockState());
        Villager backed = standingVillager(helper, level, walled);
        java.lang.reflect.Method isPinned = net.camacraft.fullstop.server.physics.ship.ShipEntityImpacts.class.getDeclaredMethod(
                "isPinned", ServerLevel.class, ShipRef.class, Entity.class, AABB.class, Vec3.class, GravityFrame.class);
        isPinned.setAccessible(true);
        ShipRef noShip = ShipRef.of(level, ShipRef.NO_ID);
        Vec3 flat = new Vec3(1.0, 0.0, 0.0);
        Vec3 hair = new Vec3(1.0, -1.0e-9, 0.0);
        Vec3 twoDown = new Vec3(Math.cos(Math.toRadians(2.0)), -Math.sin(Math.toRadians(2.0)), 0.0);
        Vec3 thirtyDown = new Vec3(Math.cos(Math.toRadians(30.0)), -Math.sin(Math.toRadians(30.0)), 0.0);
        Vec3 sixtyDown = new Vec3(Math.cos(Math.toRadians(60.0)), -Math.sin(Math.toRadians(60.0)), 0.0);
        Vec3 down = new Vec3(0.0, -1.0, 0.0);
        Vec3[] pushes = {flat, hair, twoDown, thirtyDown, sixtyDown, down};
        String[] names = {"(1,0,0)", "(1,-1e-9,0)", "2 deg down", "30 deg down", "60 deg down", "straight down"};
        // open ground: only a push mostly INTO the ground pins (crushed against it)
        boolean[] openExpected = {false, false, false, false, true, true};
        boolean[] wallExpected = {true, true, true, true, true, true};
        StringBuilder detail = new StringBuilder();
        boolean ok = true;
        for (int i = 0; i < pushes.length; i++) {
            boolean a = (boolean) isPinned.invoke(null, level, noShip, open, open.getBoundingBox(), pushes[i], GravityFrame.DEFAULT);
            boolean b = (boolean) isPinned.invoke(null, level, noShip, backed, backed.getBoundingBox(), pushes[i], GravityFrame.DEFAULT);
            detail.append(names[i]).append(": open ").append(a).append(", wall ahead ").append(b).append("; ");
            ok &= a == openExpected[i] && b == wallExpected[i];
        }
        report("pin check, villagers standing on the ground", detail.toString());
        helper.assertTrue(ok, "FullStop's pin check (expected open: pinned only 60 deg down and straight down; wall ahead: "
                + "always): " + detail);
        helper.succeed();
    }

    /** A villager standing on the ground at {@code at} (not added to the level: the pin check only reads it). */
    private static Villager standingVillager(GameTestHelper helper, ServerLevel level, BlockPos at) {
        Villager villager = EntityType.VILLAGER.create(level);
        helper.assertTrue(villager != null, "no villager");
        villager.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        villager.setOnGround(true);
        return villager;
    }

    @BeforeBatch(batch = NO_DAMAGE_BATCH)
    public static void damageOff(ServerLevel level) {
        FullStopConfig.SERVER.damageEntities.set(false);
    }

    @AfterBatch(batch = NO_DAMAGE_BATCH)
    public static void damageOn(ServerLevel level) {
        FullStopConfig.SERVER.damageEntities.set(true);
    }

    /**
     * {@code PacketHandler.syncLaunchToPlayer} leaves non-players and slow players alone (a mock
     * player has no client, so the packet itself can't be checked here): it only takes over, and
     * clears {@code hurtMarked}, for a player launched faster than vanilla's 3.9 blocks/tick clamp.
     */
    @GameTest(template = FLAT)
    public static void syncLaunchToPlayerOnlyTakesOverFastPlayerLaunches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = EntityType.VILLAGER.create(level);
        helper.assertTrue(villager != null, "no villager");
        villager.setDeltaMovement(6.0, 0.0, 0.0);
        villager.hurtMarked = true;
        PacketHandler.syncLaunchToPlayer(villager);
        helper.assertTrue(villager.hurtMarked && villager.getDeltaMovement().x == 6.0, "a villager's launch was touched");

        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("fullstop-launch-sync".getBytes()),
                "[FS launch test]"));
        player.setDeltaMovement(3.0, 1.0, -3.8);
        player.hurtMarked = true;
        PacketHandler.syncLaunchToPlayer(player);
        helper.assertTrue(player.hurtMarked, "a launch under 3.9 blocks/tick on every axis was taken over from vanilla");
        player.setDeltaMovement(5.0, 0.5, 0.0);
        player.hurtMarked = true;
        PacketHandler.syncLaunchToPlayer(player);
        helper.assertTrue(!player.hurtMarked && player.getDeltaMovement().x == 5.0,
                "a 5 blocks/tick launch should go by FullStop's packet (hurtMarked cleared, motion kept)");
        report("syncLaunchToPlayer", "villager untouched, slow player left to vanilla, fast player taken over");
        helper.succeed();
    }

    /**
     * A floating 3x3x3 plank ship pushed along +x at {@code speed} into a villager (no AI) standing
     * nine blocks ahead on open ground. Right after the hit (the test ticks after FullStop's ship
     * pass, before the villager moves) its velocity must match the launch rule within 5%.
     */
    /**
     * Where a ship-hit test builds: a lane of its own, 256 blocks apart and far from every other
     * test, in chunks nothing has used. A fast ship that misses (or that pinning doesn't stop: on
     * Jolt a ship made static kept flying at 48 m/s) only crosses empty land, never another test's
     * plot; a later batch, which gets the same plots as the first, still builds somewhere new.
     */
    private static BlockPos hitLane(GameTestHelper helper, int lane) {
        return origin(helper, AWAY + 3072 + lane * 256);
    }

    private static void shipHit(GameTestHelper helper, BlockPos o, double speed, boolean expectDeath, boolean cruise, String what) {
        ServerLevel level = helper.getLevel();
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 1, 0), o.offset(2, 3, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) {
            helper.fail("no villager");
            return;
        }
        // 0.1 m above the ground, gravity off: nothing at all behind or under it. Standing on the
        // ground, a contact normal with the faintest downward component (float noise of the ship's
        // rotation) makes FullStop's pin check probe into the ground and call it pinned - see
        // aVillagerStandingOnOpenGroundIsNotPinned.
        villager.moveTo(o.getX() + 12.5, o.getY() + 0.1, o.getZ() + 1.5, 90.0f, 0.0f);
        villager.setNoAi(true);
        villager.setNoGravity(true);
        villager.setPersistenceRequired();
        level.addFreshEntity(villager);
        HitWatch watch = new HitWatch(villager, rig);
        watch.cruise = cruise ? speed : Double.NaN;
        helper.onEachTick(rig::record);
        helper.onEachTick(watch::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> {
                    watch.armed = true;
                    rig.push(new Vec3(speed, 0.0, 0.0));
                })
                .thenWaitUntil(watch::demandLaunched)
                .thenIdle(3)
                .thenExecute(() -> {
                    rig.pin();
                    double e = FullStopConfig.SERVER.entityRestitution.get();
                    double approach = watch.shipV.x - watch.before.x;
                    double share = watch.shipKg / (watch.shipKg + watch.mobKg);
                    double law = watch.before.x + (1.0 + e) * approach * share;
                    // no faster than maxHitLaunchSpeed (the damage still follows the whole hit)
                    double limit = FullStopConfig.SERVER.maxHitLaunchSpeed.get();
                    double expected = limit > 0.0 ? Math.min(law, Math.max(limit, Math.abs(watch.before.x))) : law;
                    double error = Math.abs(watch.launch.x - expected) / Math.max(1.0, Math.abs(expected));
                    report(what, String.format(Locale.ROOT, "tick %d: ship %.2f m/s (%.0f kg), villager %.1f kg moving %.2f m/s "
                                    + "before; approach %.2f m/s, restitution %.2f -> expected launch %.2f m/s along +x (capped), measured "
                                    + "(%.2f, %.2f, %.2f) m/s (%.1f%% off); villager health %.1f -> %.1f, %s; damageEntities=%s; "
                                    + "ship in FullStop's grace %d of %d ticks after the push; %d new physics frames in them, "
                                    + "fastest seen %.2f m/s",
                            watch.launchTick, watch.shipV.x, watch.shipKg, watch.mobKg, watch.before.x, approach, e, expected,
                            watch.launch.x, watch.launch.y, watch.launch.z, error * 100.0, watch.healthBefore, watch.healthAfter,
                            villager.isAlive() ? "alive" : "dead", FullStopConfig.SERVER.damageEntities.get(), watch.graceTicks,
                            watch.watchedTicks, watch.frames, rig.fastestSeen));
                    helper.assertTrue(watch.shipV.x > 0.8 * speed, "the ship reached only " + fmt(watch.shipV.x) + " of " + speed + " m/s");
                    helper.assertTrue(watch.graceTicks == 0, "the ship spent " + watch.graceTicks + " ticks in FullStop's jump grace "
                            + "after the push");
                    helper.assertTrue(error <= 0.05, "launch " + fmt(watch.launch.x) + " m/s vs expected " + fmt(expected) + " m/s");
                    helper.assertTrue(Math.abs(watch.launch.z) < 0.1 * watch.launch.x, "the launch isn't along the hull's normal");
                    if (expectDeath) {
                        helper.assertTrue(!villager.isAlive(), "a " + speed + " m/s ship didn't kill the villager (health "
                                + fmt(villager.getHealth()) + ")");
                    } else {
                        helper.assertTrue(villager.isAlive(), "the villager died (health " + fmt(villager.getHealth()) + ")");
                        if (!FullStopConfig.SERVER.damageEntities.get()) {
                            helper.assertTrue(villager.getHealth() == watch.healthBefore, "damageEntities=false but the villager was hurt");
                        }
                    }
                    helper.succeed();
                });
    }

    /** The villager of a {@link #shipHit} run, and what the ship was doing the tick it was launched. */
    private static final class HitWatch {
        final Villager mob;
        final Rig rig;
        boolean armed;
        long launchTick = -1;
        Vec3 before = Vec3.ZERO;
        Vec3 launch;
        Vec3 shipV;
        double shipKg;
        double mobKg;
        float healthBefore;
        float healthAfter;

        HitWatch(Villager mob, Rig rig) {
            this.mob = mob;
            this.rig = rig;
        }

        int watchedTicks;
        int graceTicks;
        int diagnostics;
        int frames;
        boolean pinnedAfterMiss;
        /** Speed (m/s, +x) the ship is held at until the hit, or NaN to let it coast. */
        double cruise = Double.NaN;

        /** Each tick, after FullStop's ship pass: the first tick the villager is thrown along +x. */
        void record() {
            if (launchTick >= 0 || mob.isRemoved()) return;
            if (armed) {
                long now = mob.level().getGameTime();
                ShipTracker.State state = ShipTracker.of(rig.level).get(rig.ship.getId(), now);
                watchedTicks++;
                if (state != null && state.inGrace(now)) graceTicks++;
                if (state != null && state.newFrame()) frames++;
                // hold the speed against drag once the push has taken (not while it is still queued)
                Vec3 shipNow = rig.ref.velocity();
                if (!Double.isNaN(cruise) && !pinnedAfterMiss && shipNow != null && shipNow.x > 0.5 * cruise) {
                    rig.cruise(new Vec3(cruise, 0.0, 0.0), 150.0);
                }
                AABB hull = rig.ref.worldAabb();
                if (hull != null && diagnostics < 12 && hull.inflate(1.0).intersects(mob.getBoundingBox())) {
                    // why a hull sweeping over the villager might not count as a hit
                    diagnostics++;
                    ShipRef under = ShipEntities.standingOn(mob, 5);
                    LOGGER.info("[FullStop ship test] ship {} t{} over the villager: hull x {}..{} y {}..{}, villager at {} v {} m/s, "
                                    + "VS carry {} m/s (carried {}), standing on {}, ship underfoot {}, hull contacts {}, grace {}",
                            rig.ship.getId(), now, fmt(hull.minX), fmt(hull.maxX), fmt(hull.minY), fmt(hull.maxY), mob.position(),
                            mob.getDeltaMovement().scale(20.0), ShipEntities.carryMps(mob), ShipEntities.isCarried(mob),
                            under == null ? "-" : under.id(), rig.ref.isUnderfoot(mob), rig.ref.contacts(mob.getBoundingBox()).size(),
                            state != null && state.inGrace(now));
                }
                Vec3 at = rig.ref.position();
                if (at != null && rig.start != null && at.x - rig.start.x > 20.0 && !pinnedAfterMiss) {
                    // missed: stop before it flies into the next test's plot
                    pinnedAfterMiss = true;
                    rig.pin();
                }
            }
            Vec3 v = mob.getDeltaMovement().scale(20.0);
            if (armed && v.x > 1.0) {
                rig.stopThrust();
                launchTick = mob.level().getGameTime();
                launch = v;
                shipV = rig.ref.velocity();
                shipKg = rig.ref.mass();
                mobKg = Weights.bodyMassKg(mob);
                healthAfter = mob.getHealth();
                return;
            }
            before = v;
            healthBefore = mob.getHealth();
        }

        void demandLaunched() {
            rig.helper.assertTrue(launchTick >= 0, "the villager hasn't been hit yet (ship at " + rig.ref.position() + ", v "
                    + rig.ref.velocity() + "; in FullStop's grace " + graceTicks + " of " + watchedTicks + " ticks since the push)");
        }
    }

    // ------------------------------------------------------------------
    // Deck momentum: riders keep what a deck loses beyond their grip
    // ------------------------------------------------------------------

    /**
     * Batch of the deck-momentum tests: ship weight is off for it (set and restored around it),
     * so the cows on a floating raft don't weigh it down or tip it while it flies.
     */
    private static final String DECK_BATCH = "fullstop_deck_momentum";
    /** The deck tests' waits span seconds of VS's real-time physics while this server races ahead. */
    private static final int DECK_TIMEOUT = 2400;
    /** Gentle: half a g, well under the default 1 g grip. */
    private static final double GENTLE = 4.9;

    @BeforeBatch(batch = DECK_BATCH)
    public static void deckWeightOff(ServerLevel level) {
        FullStopConfig.SERVER.enableShipWeight.set(false);
    }

    @AfterBatch(batch = DECK_BATCH)
    public static void deckWeightOn(ServerLevel level) {
        FullStopConfig.SERVER.enableShipWeight.set(true);
    }

    /**
     * A 7x1x5 plank raft floating four blocks up carries three cows: one on the bare deck 1.5
     * blocks from the bow, one on a honey block ({@code fullstop:sticky}), one seated in a Valkyrien
     * Skies seat. It speeds up gently (half a g) to 24 m/s, then stops dead (its whole momentum
     * taken out in one physics step). The cow on the bare deck must keep going: thrown forward at
     * its old speed less the grip allowance ({@code deckGrip * dt}), and off the bow. (How far a
     * thrown mob flies is vanilla's business: 9% of its horizontal speed goes to air drag every
     * tick, so 24 m/s carries it about four blocks.) The cow on the honey and the seated one stay
     * where they are, and nobody was thrown while the raft sped up.
     */
    @GameTest(template = FLAT, timeoutTicks = DECK_TIMEOUT, batch = DECK_BATCH)
    public static void aShipThatStopsDeadThrowsItsDeckCrewForward(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = deckLane(helper, 0);
        Set<BlockPos> hull = fillSet(level, o.offset(0, 4, 0), o.offset(6, 4, 4), Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos honey = o.offset(3, 5, 3);
        level.setBlock(honey, Blocks.HONEY_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        hull.add(honey);
        Rig rig = Rig.build(helper, hull, Blocks.OAK_PLANKS, true, 35);
        if (rig == null) return;
        DeckRun run = new DeckRun(rig);
        helper.onEachTick(rig::record);
        helper.onEachTick(run::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> {
                    run.plain = deckCow(helper, level, new Vec3(o.getX() + 5.5, o.getY() + 5.0, o.getZ() + 1.5));
                    run.sticky = deckCow(helper, level, new Vec3(o.getX() + 3.5, o.getY() + 5.9375, o.getZ() + 3.5));
                    run.seated = seatedCow(helper, level, rig, new Vec3(o.getX() + 1.5, o.getY() + 5.0, o.getZ() + 2.5));
                })
                .thenWaitUntil(() -> run.demandAboard(run.plain, run.sticky))
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> {
                    rig.hover();
                    run.target = new Vec3(24.0, 0.0, 0.0);
                    run.accel = GENTLE;
                })
                .thenWaitUntil(() -> run.demandSpeed(23.0))
                .thenExecute(run::stopDead)
                .thenWaitUntil(() -> run.demandAfterStop(30))
                .thenExecute(() -> {
                    AABB deck = rig.ref.worldAabb();
                    ShipDeckMomentum.Throw plainThrow = ShipDeckMomentum.lastThrow(run.plain);
                    ShipDeckMomentum.Throw stickyThrow = ShipDeckMomentum.lastThrow(run.sticky);
                    ShipDeckMomentum.Throw seatedThrow = ShipDeckMomentum.lastThrow(run.seated);
                    double grip = FullStopConfig.SERVER.deckGrip.get();
                    rig.pin();
                    report("ship that stops dead, crew on deck", String.format(Locale.ROOT,
                            "deck at the plain cow %.2f m/s before the stop, %s; plain cow thrown %s, flew (%.2f, %.2f, %.2f) m/s "
                                    + "the tick after, now %.2f blocks past the bow (at x %.2f, bow %.2f); honey cow thrown %s, "
                                    + "on the ship %s, %.2f blocks from where it stood; seated cow (in a seat of ship %s) thrown %s, still "
                                    + "seated %s; thrown before the stop: %s | %s",
                            run.deckSpeedBefore, run.stopNote, describe(plainThrow), run.flownAfter.x, run.flownAfter.y,
                            run.flownAfter.z, deck == null ? Double.NaN : run.plain.getX() - deck.maxX, run.plain.getX(),
                            deck == null ? Double.NaN : deck.maxX, describe(stickyThrow),
                            rig.ref.equals(ShipEntities.standingOn(run.sticky, 5)), run.stickyDrift(),
                            ShipEntities.mountedTo(run.seated) == null ? "-" : ShipEntities.mountedTo(run.seated).id(), describe(seatedThrow),
                            run.seated.isPassenger(), run.thrownBeforeStop, rig.summary()));
                    helper.assertTrue(run.thrownBeforeStop.isEmpty(), "riders were thrown while the raft sped up gently: "
                            + run.thrownBeforeStop);
                    helper.assertTrue(plainThrow != null && plainThrow.tick() >= run.stopTick, "the cow on the bare deck wasn't "
                            + "thrown when the raft stopped dead");
                    double allowance = grip * 0.25 + 0.5;
                    helper.assertTrue(plainThrow.kept().x >= run.deckSpeedBefore - allowance
                                    && plainThrow.kept().x <= run.deckSpeedBefore + 0.5,
                            "the thrown cow kept " + fmt(plainThrow.kept().x) + " m/s of the deck's " + fmt(run.deckSpeedBefore));
                    helper.assertTrue(run.flownAfter.x >= 0.8 * plainThrow.kept().x, "the thrown cow only moved "
                            + fmt(run.flownAfter.x) + " m/s the tick after the throw (kept " + fmt(plainThrow.kept().x) + ")");
                    helper.assertTrue(deck != null && run.plain.getX() > deck.maxX, "the thrown cow didn't leave the deck "
                            + "over the bow (x " + fmt(run.plain.getX()) + ")");
                    helper.assertTrue(stickyThrow == null, "the cow on the honey block was thrown: " + describe(stickyThrow));
                    helper.assertTrue(rig.ref.equals(ShipEntities.standingOn(run.sticky, 5)) && run.stickyDrift() < 1.0,
                            "the cow on the honey block didn't stay on it (moved " + fmt(run.stickyDrift()) + " blocks)");
                    helper.assertTrue(seatedThrow == null && run.seated.isPassenger(), "the seated cow was thrown out of its seat");
                    helper.assertTrue(rig.ref.equals(ShipEntities.mountedTo(run.seated)), "the seated cow's seat isn't part of the ship");
                    helper.succeed();
                });
    }

    /**
     * The same raft with one cow on its bare deck, flown up to 8 m/s and braked back to rest, both
     * at half a g: under the grip, so the cow is never thrown and ends where it stood.
     */
    @GameTest(template = FLAT, timeoutTicks = DECK_TIMEOUT, batch = DECK_BATCH)
    public static void aShipBrakingGentlyThrowsNobody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = deckLane(helper, 1);
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 4, 0), o.offset(6, 4, 4), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        DeckRun run = new DeckRun(rig);
        helper.onEachTick(rig::record);
        helper.onEachTick(run::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> run.plain = deckCow(helper, level, new Vec3(o.getX() + 3.5, o.getY() + 5.0, o.getZ() + 2.5)))
                .thenWaitUntil(() -> run.demandAboard(run.plain))
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> {
                    rig.hover();
                    run.target = new Vec3(8.0, 0.0, 0.0);
                    run.accel = GENTLE;
                })
                .thenWaitUntil(() -> run.demandSpeed(7.8))
                .thenExecute(() -> {
                    run.markStop();
                    run.target = Vec3.ZERO;
                })
                .thenWaitUntil(run::demandAtRest)
                .thenExecute(() -> {
                    ShipDeckMomentum.Throw thrown = ShipDeckMomentum.lastThrow(run.plain);
                    double drift = run.plainDrift();
                    rig.pin();
                    report("ship braking gently", String.format(Locale.ROOT, "top speed %.2f m/s, braked at %.1f m/s^2 to rest; "
                                    + "cow thrown %s, on the ship %s, %.2f blocks from where it stood | %s", rig.fastestSeen, GENTLE,
                            describe(thrown), rig.ref.equals(ShipEntities.standingOn(run.plain, 5)), drift, rig.summary()));
                    helper.assertTrue(thrown == null, "a cow was thrown off a gently braking raft: " + describe(thrown));
                    helper.assertTrue(rig.ref.equals(ShipEntities.standingOn(run.plain, 5)) && drift < 1.0,
                            "the cow didn't stay where it stood (moved " + fmt(drift) + " blocks)");
                    helper.succeed();
                });
    }

    /** A 5x1x5 plank raft let go on the ground with a cow on it: over 300 ticks nobody is thrown. */
    @GameTest(template = FLAT, timeoutTicks = DECK_TIMEOUT, batch = DECK_BATCH)
    public static void aRestingShipThrowsNobody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = deckLane(helper, 2);
        Rig rig = Rig.build(helper, fillSet(level, o, o.offset(4, 0, 4), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, false);
        if (rig == null) return;
        DeckRun run = new DeckRun(rig);
        helper.onEachTick(rig::record);
        helper.onEachTick(run::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> run.plain = deckCow(helper, level, new Vec3(o.getX() + 4.2, o.getY() + 1.0, o.getZ() + 4.2)))
                .thenWaitUntil(() -> run.demandAboard(run.plain))
                .thenExecute(() -> {
                    rig.release();
                    rig.startWatching();
                })
                .thenWaitUntil(() -> rig.demandWatched(QUIET_TICKS))
                .thenExecute(() -> {
                    ShipDeckMomentum.Throw thrown = ShipDeckMomentum.lastThrow(run.plain);
                    rig.pin();
                    report("resting ship", rig.windowSummary() + "; cow thrown " + describe(thrown) + ", "
                            + run.deckFrames + " new frames with a deck change over the grip | " + rig.summary());
                    helper.assertTrue(thrown == null, "a resting ship threw its cow: " + describe(thrown));
                    helper.succeed();
                });
    }

    /**
     * A floating 5x1x5 plank raft spun up about its middle to 0.5 rad/s (at most 0.5 rad/s^2) and
     * held there, a cow near a corner (2.8 blocks out: 0.7 m/s^2 of centripetal pull): a gentle
     * turn throws nobody.
     */
    @GameTest(template = FLAT, timeoutTicks = DECK_TIMEOUT, batch = DECK_BATCH)
    public static void aGentlyTurningShipThrowsNobody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = deckLane(helper, 3);
        Rig rig = Rig.build(helper, fillSet(level, o.offset(0, 4, 0), o.offset(4, 4, 4), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        DeckRun run = new DeckRun(rig);
        helper.onEachTick(rig::record);
        helper.onEachTick(run::record);
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> run.plain = deckCow(helper, level, new Vec3(o.getX() + 4.5, o.getY() + 5.0, o.getZ() + 4.5)))
                .thenWaitUntil(() -> run.demandAboard(run.plain))
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> {
                    rig.hover();
                    run.target = Vec3.ZERO;
                    run.accel = GENTLE;
                    // a 5x5 raft about y: I = m (5^2 + 5^2) / 12
                    run.spin = 0.5;
                    run.inertia = rig.ref.mass() * 50.0 / 12.0;
                })
                .thenWaitUntil(() -> run.demandSpin(0.45))
                .thenExecute(rig::startWatching)
                .thenWaitUntil(() -> rig.demandWatched(200))
                .thenExecute(() -> {
                    ShipDeckMomentum.Throw thrown = ShipDeckMomentum.lastThrow(run.plain);
                    Vec3 w = rig.ref.angularVelocity();
                    rig.pin();
                    report("gently turning ship", String.format(Locale.ROOT, "spin %.3f rad/s after 200 ticks, cow thrown %s, "
                                    + "on the ship %s | %s", w == null ? Double.NaN : w.y, describe(thrown),
                            rig.ref.equals(ShipEntities.standingOn(run.plain, 5)), rig.summary()));
                    helper.assertTrue(thrown == null, "a gently turning ship threw its cow: " + describe(thrown));
                    helper.assertTrue(rig.ref.equals(ShipEntities.standingOn(run.plain, 5)), "the cow isn't on the turning raft");
                    helper.succeed();
                });
    }

    /**
     * The dead stop of {@link #aShipThatStopsDeadThrowsItsDeckCrewForward} in low and zero gravity.
     * On the bare deck, a Moon cow (gravity attribute at a sixth, as on Ad Astra's Moon) and a
     * weightless one (the no-gravity flag, as VS Genesis puts on everything in space); a second
     * weightless cow stands on the honey block. The weightless ones are held against the deck (a
     * small push into it each tick: VS only carries what presses on its deck), the one on the
     * bare deck until the stop.
     * <ul>
     *   <li>The weightless cow keeps the deck's whole speed (no grip without weight) and is thrown
     *   level: no lift (the lift only gets a rider clear of ground friction, and in zero gravity
     *   it carried it off upward for good). It never rises.</li>
     *   <li>The Moon cow is thrown with a sixth of the lift.</li>
     *   <li>The weightless cow on the honey block is anchored: never thrown, still on the ship.</li>
     * </ul>
     */
    @GameTest(template = FLAT, timeoutTicks = DECK_TIMEOUT, batch = DECK_BATCH)
    public static void aShipThatStopsDeadThrowsLowAndZeroGravityCrewWithoutLift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = deckLane(helper, 4);
        Set<BlockPos> hull = fillSet(level, o.offset(0, 4, 0), o.offset(6, 4, 4), Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos honey = o.offset(3, 5, 3);
        level.setBlock(honey, Blocks.HONEY_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        hull.add(honey);
        Rig rig = Rig.build(helper, hull, Blocks.OAK_PLANKS, true, 35);
        if (rig == null) return;
        DeckRun run = new DeckRun(rig);
        Cow[] floating = new Cow[1];
        // the weightless cow's height when thrown at or after the stop, and the highest it got after
        double[] floatY = {Double.NaN, Double.NEGATIVE_INFINITY};
        helper.onEachTick(rig::record);
        helper.onEachTick(run::record);
        helper.onEachTick(() -> {
            long now = level.getGameTime();
            if (run.sticky != null) holdToDeck(run.sticky);
            Cow cow = floating[0];
            if (cow == null) return;
            if (now < run.stopTick) holdToDeck(cow);
            ShipDeckMomentum.Throw thrown = ShipDeckMomentum.lastThrow(cow);
            if (thrown != null && thrown.tick() >= run.stopTick) {
                if (Double.isNaN(floatY[0])) floatY[0] = thrown.at().y;
                floatY[1] = Math.max(floatY[1], cow.getY());
            }
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> {
                    run.plain = deckCow(helper, level, new Vec3(o.getX() + 5.5, o.getY() + 5.0, o.getZ() + 1.5));
                    ZeroGravityTests.moonGravity(run.plain);
                    run.sticky = deckCow(helper, level, new Vec3(o.getX() + 3.5, o.getY() + 5.9375, o.getZ() + 3.5));
                    run.sticky.setNoGravity(true);
                    floating[0] = deckCow(helper, level, new Vec3(o.getX() + 5.5, o.getY() + 5.0, o.getZ() + 3.5));
                    floating[0].setNoGravity(true);
                })
                .thenWaitUntil(() -> run.demandAboard(run.plain, run.sticky, floating[0]))
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> {
                    rig.hover();
                    run.target = new Vec3(24.0, 0.0, 0.0);
                    run.accel = GENTLE;
                })
                .thenWaitUntil(() -> run.demandSpeed(23.0))
                .thenExecute(run::stopDead)
                .thenWaitUntil(() -> run.demandAfterStop(30))
                .thenExecute(() -> {
                    ShipDeckMomentum.Throw moonThrow = ShipDeckMomentum.lastThrow(run.plain);
                    ShipDeckMomentum.Throw floatThrow = ShipDeckMomentum.lastThrow(floating[0]);
                    ShipDeckMomentum.Throw stickyThrow = ShipDeckMomentum.lastThrow(run.sticky);
                    double lift = FullStopConfig.SERVER.deckThrowLift.get();
                    boolean stickyAboard = rig.ref.equals(ShipEntities.standingOn(run.sticky, 5));
                    double rise = floatY[1] - floatY[0];
                    rig.pin();
                    report("ship that stops dead, Moon and weightless crew", String.format(Locale.ROOT,
                            "deck at the bow %.2f m/s before the stop, %s; Moon cow thrown %s; weightless cow thrown %s, rose %.2f "
                                    + "blocks after; weightless cow on the honey thrown %s, on the ship %s, %.2f blocks from where "
                                    + "it stood | %s", run.deckSpeedBefore, run.stopNote, describe(moonThrow), describe(floatThrow),
                            rise, describe(stickyThrow), stickyAboard, run.stickyDrift(), rig.summary()));
                    helper.assertTrue(floatThrow != null && floatThrow.tick() >= run.stopTick,
                            "the weightless cow on the bare deck wasn't thrown when the raft stopped dead");
                    helper.assertTrue(floatThrow.kept().x >= run.deckSpeedBefore - 0.5, "the weightless cow kept only "
                            + fmt(floatThrow.kept().x) + " m/s of the deck's " + fmt(run.deckSpeedBefore) + " (it has no grip)");
                    helper.assertTrue(Math.abs(floatThrow.kept().y) < 0.3, "the weightless cow was thrown with "
                            + fmt(floatThrow.kept().y) + " m/s of lift");
                    helper.assertTrue(rise < 0.3, "the weightless cow rose " + fmt(rise) + " blocks after the throw");
                    helper.assertTrue(moonThrow != null && moonThrow.tick() >= run.stopTick,
                            "the Moon cow wasn't thrown when the raft stopped dead");
                    helper.assertTrue(moonThrow.kept().y > 0.0 && moonThrow.kept().y < 0.3 * lift * moonThrow.kept().x,
                            "the Moon cow's lift was " + fmt(moonThrow.kept().y) + " m/s (a sixth of " + fmt(lift * moonThrow.kept().x)
                                    + " expected)");
                    helper.assertTrue(stickyThrow == null, "the weightless cow on the honey block was thrown: " + describe(stickyThrow));
                    helper.assertTrue(stickyAboard && run.stickyDrift() < 1.0, "the weightless cow on the honey block didn't stay "
                            + "on it (moved " + fmt(run.stickyDrift()) + " blocks)");
                    helper.succeed();
                });
    }

    /**
     * A small push into the deck for a weightless rider (0.4 m/s, under every impact threshold):
     * with nothing pressing it on the deck, VS stops carrying it.
     */
    private static void holdToDeck(Entity rider) {
        Vec3 v = rider.getDeltaMovement();
        rider.setDeltaMovement(v.x, -0.02, v.z);
    }

    /**
     * Where a deck test builds: lanes of their own, clear of the ship-hit lanes, with 128 blocks
     * of flight path along +x force-loaded (VS stops a ship dead at the edge of loaded terrain).
     */
    private static BlockPos deckLane(GameTestHelper helper, int lane) {
        BlockPos o = origin(helper, AWAY + 3072 + (8 + lane) * 256);
        ServerLevel level = helper.getLevel();
        for (int cx = (o.getX() - 8) >> 4; cx <= (o.getX() + 128) >> 4; cx++) {
            for (int cz = (o.getZ() - 8) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    /** A cow that stands where it is put: AI on (it moves under its own physics), but no goals. */
    private static Cow deckCow(GameTestHelper helper, ServerLevel level, Vec3 at) {
        Cow cow = EntityType.COW.create(level);
        helper.assertTrue(cow != null, "no cow");
        cow.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        cow.goalSelector.removeAllGoals(goal -> true);
        cow.targetSelector.removeAllGoals(goal -> true);
        cow.setPersistenceRequired();
        level.addFreshEntity(cow);
        return cow;
    }

    /** A cow seated in a Valkyrien Skies seat on the rig's ship, at the deck spot {@code at} (world). */
    private static Cow seatedCow(GameTestHelper helper, ServerLevel level, Rig rig, Vec3 at) {
        Vec3 inShip = rig.ref.toShip(at);
        helper.assertTrue(inShip != null, "the ship isn't resolved");
        ShipMountingEntity seat = ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE.create(level);
        helper.assertTrue(seat != null, "no VS seat");
        seat.moveTo(inShip.x, inShip.y, inShip.z, 0.0f, 0.0f);
        level.addFreshEntity(seat);
        Cow cow = deckCow(helper, level, at);
        helper.assertTrue(cow.startRiding(seat, true), "the cow wouldn't sit in the VS seat");
        return cow;
    }

    private static String describe(@Nullable ShipDeckMomentum.Throw thrown) {
        return thrown == null ? "no" : String.format(Locale.ROOT, "yes (t%d, deck lost (%.2f, %.2f, %.2f), kept (%.2f, %.2f, %.2f) m/s%s)",
                thrown.tick(), thrown.lost().x, thrown.lost().y, thrown.lost().z, thrown.kept().x, thrown.kept().y,
                thrown.kept().z, thrown.seated() ? ", out of its seat" : "");
    }

    /** One deck test's riders, its engine and what the cows did. */
    private static final class DeckRun {
        final Rig rig;
        Cow plain;
        Cow sticky;
        Cow seated;
        /** The engine: target velocity (m/s) and top acceleration, null target = off. */
        @Nullable
        Vec3 target;
        double accel;
        /** Spin-up about y (rad/s, NaN = off) and the moment used for it. */
        double spin = Double.NaN;
        double inertia;
        long stopTick = Long.MAX_VALUE;
        String stopNote = "";
        double deckSpeedBefore;
        /** Riders thrown before the stop (they must not be). */
        final List<String> thrownBeforeStop = new ArrayList<>();
        /** The thrown plain cow's world velocity the tick after its throw. */
        Vec3 flownAfter = Vec3.ZERO;
        boolean flownMeasured;
        /** Where the riders stood relative to the ship (ship space) when the stop came. */
        @Nullable
        Vec3 plainOnShip;
        @Nullable
        Vec3 stickyOnShip;
        int deckFrames;

        DeckRun(Rig rig) {
            this.rig = rig;
        }

        void record() {
            if (target != null) rig.cruise(target, accel);
            if (!Double.isNaN(spin)) rig.spin(spin, 0.5, inertia);
            long now = rig.level.getGameTime();
            ShipTracker.State state = ShipTracker.of(rig.level).get(rig.ship.getId(), now);
            if (state != null && state.newFrame() && plain != null) {
                Vec3 at = plain.position();
                if (state.deckChangeAt(at).length()
                        > FullStopConfig.SERVER.deckGrip.get() * state.frameSeconds()) {
                    deckFrames++;
                }
            }
            if (now < stopTick) {
                for (Cow cow : new Cow[]{plain, sticky, seated}) {
                    if (cow != null && ShipDeckMomentum.lastThrow(cow) != null && !thrownBeforeStop.contains(cow.getStringUUID())) {
                        thrownBeforeStop.add(cow.getStringUUID());
                        LOGGER.info("[FullStop ship test] thrown before the stop: {} {}", cow.getStringUUID(),
                                describe(ShipDeckMomentum.lastThrow(cow)));
                    }
                }
            }
            if (plain != null && !flownMeasured) {
                // the first tick of flight: the first position sampled after the throw that
                // differs from where it was thrown (this runs once per server tick, the cow moves
                // once per server tick)
                ShipDeckMomentum.Throw thrown = ShipDeckMomentum.lastThrow(plain);
                if (thrown != null && thrown.tick() >= stopTick && !plain.position().equals(thrown.at())) {
                    flownAfter = plain.position().subtract(thrown.at()).scale(20.0);
                    flownMeasured = true;
                }
            }
        }

        void demandAboard(Cow... cows) {
            for (Cow cow : cows) {
                rig.helper.assertTrue(cow != null && rig.ref.equals(ShipEntities.standingOn(cow, 1)),
                        "VS hasn't registered the cow as standing on ship " + rig.ship.getId() + " yet");
            }
        }

        void demandSpeed(double speed) {
            Vec3 v = rig.ref.velocity();
            rig.helper.assertTrue(v != null && v.x >= speed, "the raft is at " + (v == null ? "?" : fmt(v.x)) + " of " + speed + " m/s");
        }

        void demandSpin(double omega) {
            Vec3 w = rig.ref.angularVelocity();
            rig.helper.assertTrue(w != null && w.y >= omega, "the raft spins at " + (w == null ? "?" : fmt(w.y)) + " of " + omega + " rad/s");
        }

        void markStop() {
            stopTick = rig.level.getGameTime();
            plainOnShip = plain == null ? null : rig.ref.toShip(plain.position());
            stickyOnShip = sticky == null ? null : rig.ref.toShip(sticky.position());
        }

        /** Takes the raft's whole momentum out in one physics step (it keeps hovering). */
        void stopDead() {
            target = null;
            rig.stopThrust();
            Vec3 v = rig.ref.velocity();
            Vec3 at = plain.position();
            Vec3 deck = rig.ref.velocityAt(at);
            deckSpeedBefore = deck == null ? Double.NaN : deck.x;
            markStop();
            ShipForces.applyImpulse(rig.level, rig.ship.getId(), v.scale(-rig.ref.mass()), null);
            Vec3 w = rig.ref.angularVelocity();
            stopNote = "stopped from " + fmt(v.length()) + " m/s (spin " + (w == null ? "?" : fmt(w.length())) + " rad/s) at t" + stopTick;
            LOGGER.info("[FullStop ship test] ship {} {}", rig.ship.getId(), stopNote);
        }

        void demandAfterStop(int ticks) {
            Vec3 v = rig.ref.velocity();
            rig.helper.assertTrue(rig.level.getGameTime() - stopTick >= ticks && v != null && v.length() < 1.0,
                    "waiting after the stop (raft at " + (v == null ? "?" : fmt(v.length())) + " m/s)");
        }

        void demandAtRest() {
            Vec3 v = rig.ref.velocity();
            rig.helper.assertTrue(v != null && v.length() < 0.2 && rig.level.getGameTime() - stopTick > 20,
                    "the raft is still braking (" + (v == null ? "?" : fmt(v.length())) + " m/s)");
        }

        /** How far (blocks) the cow on the honey moved over the ship since the stop. */
        double stickyDrift() {
            return drift(sticky, stickyOnShip);
        }

        double plainDrift() {
            return drift(plain, plainOnShip);
        }

        private double drift(@Nullable Cow cow, @Nullable Vec3 before) {
            if (cow == null || before == null) return Double.NaN;
            Vec3 now = rig.ref.toShip(cow.position());
            return now == null ? Double.NaN : now.distanceTo(before);
        }
    }

    // ------------------------------------------------------------------
    // Rigging
    // ------------------------------------------------------------------

    /** The rigs whose engine is on, by ship id (read on VS's physics thread). */
    private static final Map<Long, Rig> CRUISING = new ConcurrentHashMap<>();
    private static final ResourceLocation CRUISE = new ResourceLocation(FullStop.MODID, "gametest_cruise");
    private static boolean cruiseHooked;

    private static synchronized void ensureCruise() {
        if (cruiseHooked) return;
        ShipForces.registerHook(CRUISE, ShipCollisionTests::cruiseStep);
        cruiseHooked = true;
    }

    /**
     * VS's physics thread, every step, every ship: the test engine of a cruising rig ({@link Rig#cruise}): a force
     * of the ship's physical mass times the acceleration toward its target velocity (gain 20/s, the same as the old
     * server-tick engine's), at most the rig's acceleration across and along the vertical.
     */
    private static void cruiseStep(net.camacraft.camafy.compat.vs.PhysBody body, double dt) {
        Rig rig = CRUISING.get(body.id());
        if (rig == null || body.isStatic()) return;
        Vec3 target = rig.cruiseTarget;
        double max = rig.cruiseAccel;
        if (target == null) return;
        org.joml.Vector3d v = body.velocity(new org.joml.Vector3d());
        double scale = body.scale();
        double mass = body.mass() * (scale > 0.0 && Double.isFinite(scale) ? scale * scale * scale : 1.0);
        if (!(mass > 0.0) || !Double.isFinite(mass)) return;
        double ex = (target.x - v.x) * 20.0, ey = (target.y - v.y) * 20.0, ez = (target.z - v.z) * 20.0;
        double across = Math.sqrt(ex * ex + ez * ez);
        if (across > max) {
            ex *= max / across;
            ez *= max / across;
        }
        ey = Math.max(-max, Math.min(max, ey));
        org.joml.Vector3d force = new org.joml.Vector3d(ex * mass, ey * mass, ez * mass);
        if (Double.isFinite(force.x) && Double.isFinite(force.y) && Double.isFinite(force.z)) body.applyForce(force);
    }

    /** One test's ship, and everything VS reported about it. */
    static final class Rig {
        final GameTestHelper helper;
        final ServerLevel level;
        final ServerShip ship;
        final ShipRef ref;
        final boolean floats;
        final Block material;
        final int expected;
        final FakePlayer watcher;
        /** Hull blocks once VS finished assembling; where the ship was when it was let go. */
        int blocks;
        Vec3 start;
        long loadedSince = -1;
        long firstContact = -1;
        long firstShipContact = -1;
        final List<String> contactLog = new ArrayList<>();
        final List<ShipCollision> terrainHits = new ArrayList<>();
        final List<ShipCollision> shipHits = new ArrayList<>();
        /** Every collision source Camafy used while this rig existed. */
        final Set<ShipCollisions.Source> sources = EnumSet.noneOf(ShipCollisions.Source.class);
        boolean wasLoaded;
        int outsideBounds;
        double farthestOutside;
        double fastestSeen;
        /** The quiet tests' window: what Camafy published for this ship while watched. */
        boolean watching;
        long watchFrom;
        int windowRecords;
        int windowContacts;
        int windowFrames;
        double windowFastest;
        @Nullable
        Vec3 lastVelocity;
        /**
         * Hard terrain contacts (1.5 m/s or more, by VS's approach or the ship's speed into the
         * contact the tick before, as FullStop judges them), grouped into bursts: records more than
         * 10 ticks apart start a new one.
         */
        int bursts;
        long lastHardTick = -1000;
        /** Consecutive ticks the ship has been still: under 0.05 m/s and 0.05 rad/s. */
        int stillTicks;
        final List<String> burstLog = new ArrayList<>();

        private Rig(GameTestHelper helper, ServerShip ship, Block material, int expected, boolean floats, FakePlayer watcher) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.ship = ship;
            this.material = material;
            this.expected = expected;
            this.watcher = watcher;
            // Resolved when used: VS loads a new ship a tick after assembling it.
            this.ref = ShipRef.of(level, ship.getId());
            this.floats = floats;
        }

        /** Assembles {@code blocks} into a ship, pinned for now; null (and the test failed) if VS couldn't. */
        static Rig build(GameTestHelper helper, Set<BlockPos> blocks, Block material, boolean floats) {
            return build(helper, blocks, material, floats, blocks.size());
        }

        /** As above, for a hull of mixed blocks: {@code materialCount} of them are {@code material}. */
        static Rig build(GameTestHelper helper, Set<BlockPos> blocks, Block material, boolean floats, int materialCount) {
            FullStopConfig.SERVER.debugShipCollisionLog.set(true);
            listenRaw();
            if (!Ships.available()) {
                helper.fail("Valkyrien Skies isn't running");
                return null;
            }
            ServerLevel level = helper.getLevel();
            // The watcher first, so VS sees a player by the ship from its very first tick.
            BlockPos at = blocks.iterator().next();
            FakePlayer watcher = FakePlayerFactory.get(level,
                    new GameProfile(UUID.nameUUIDFromBytes(("fullstop-ship-test-" + at).getBytes()), "[FS ship test]"));
            watcher.setPos(at.getX() + 0.5, at.getY() + 16.0, at.getZ() + 0.5);
            List<ServerPlayer> players = playerList(level);
            if (!players.contains(watcher)) players.add(watcher);
            ServerShip ship = ShipAssembler.assembleToShip(level, blocks, 1.0);
            if (ship == null) {
                players.remove(watcher);
                helper.fail("VS couldn't assemble a ship");
                return null;
            }
            ship.setStatic(true);
            // Without a player the assembly also times out waiting for the shipyard chunk to tick
            // ("Sandwich Factory: Timed out waiting for chunks to start ticking after assembly")
            // and the ship ends up without its blocks.
            ChunkClaim claim = ship.getChunkClaim();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setChunkForced(claim.getXMiddle() + dx, claim.getZMiddle() + dz, true);
                }
            }
            return new Rig(helper, ship, material, materialCount, floats, watcher);
        }

        /** Game-test condition: VS has the ship loaded with all its blocks, past FullStop's new-ship grace. */
        void demandReady() {
            helper.assertTrue(ref.isLoaded() && Double.isFinite(ref.mass()), "VS hasn't loaded ship " + ship.getId() + " yet");
            blocks = count(material);
            helper.assertTrue(blocks == expected, "ship " + ship.getId() + " has " + blocks + " of its " + expected + " blocks so far");
            helper.assertTrue(loadedSince >= 0 && level.getGameTime() - loadedSince >= GRACE, "ship " + ship.getId()
                    + " is still in FullStop's new-ship grace");
        }

        /** Game-test condition: the ship hit {@code other}'s ship at least {@code ticks} ticks ago. */
        void demandShipHitAndSettled(Rig other, int ticks) {
            long first = -1;
            for (ShipCollision hit : shipHits) {
                if (hit.other() != null && hit.other().id() == other.ship.getId()) {
                    first = firstShipContact;
                    break;
                }
            }
            helper.assertTrue(first >= 0, "ship " + ship.getId() + " hasn't touched ship " + other.ship.getId() + " yet (at "
                    + ref.position() + ", v " + ref.velocity() + "; " + summary() + ")");
            helper.assertTrue(level.getGameTime() - first >= ticks, "waiting after the crash");
        }

        /**
         * Game-test condition: {@code landings} bursts so far, and the ship still (no speed, no spin)
         * for 30 ticks, so the next hop starts level.
         */
        void demandLandedAndResting(int landings) {
            helper.assertTrue(bursts >= landings, "ship " + ship.getId() + " has landed " + bursts + " of " + landings
                    + " times (at " + ref.position() + ", v " + ref.velocity() + ")");
            helper.assertTrue(stillTicks >= 30, "ship " + ship.getId() + " is still moving");
        }

        /** Starts the quiet tests' window. */
        void startWatching() {
            watching = true;
            watchFrom = level.getGameTime();
            start = ref.position();
            windowRecords = windowContacts = windowFrames = 0;
            windowFastest = 0.0;
        }

        void demandWatched(int ticks) {
            helper.assertTrue(watching && level.getGameTime() - watchFrom >= ticks, "watching ship " + ship.getId());
        }

        String windowSummary() {
            return "over " + (level.getGameTime() - watchFrom) + " ticks (" + windowFrames + " new physics frames) Camafy "
                    + "published " + windowRecords + " collision records / " + windowContacts + " contacts for it (fastest "
                    + "approach " + fmt(windowFastest) + " m/s, source " + ShipCollisions.source() + ")";
        }

        /** No record for this ship in the window, when Camafy's fallback is the source. */
        void demandQuiet() {
            if (EXPECTED_SOURCE != ShipCollisions.Source.FALLBACK) return; // VS's own events: logged above
            helper.assertTrue(windowRecords == 0, "Camafy's collision fallback reported " + windowRecords + " collision(s) ("
                    + windowContacts + " contacts, fastest " + fmt(windowFastest) + " m/s) for a ship that didn't crash");
        }

        /** Camafy took this run's collisions from the source the backend calls for, and only from it. */
        void demandSource() {
            if (HIDDEN_BACKEND && EXPECTED_SOURCE == ShipCollisions.Source.NATIVE) {
                // the fallback until VS's first event, then VS's events for good
                helper.assertTrue(ShipCollisions.source() == ShipCollisions.Source.NATIVE && !sources.contains(ShipCollisions.Source.NONE),
                        "Camafy's collision source was " + sources + " during the test and is " + ShipCollisions.source()
                                + " now; with the backend hidden it must end up NATIVE on " + BACKEND);
                return;
            }
            helper.assertTrue(sources.equals(EnumSet.of(EXPECTED_SOURCE)), "Camafy's collision source was " + sources
                    + " during the test; on VS backend " + BACKEND + " it must be " + EXPECTED_SOURCE + " only");
        }

        /**
         * Ship-to-ship records against {@code other} must exist, lie at this ship in world space,
         * and point from {@code other} toward this ship ({@code expected}).
         */
        void demandShipContacts(Rig other, Vec3 expected) {
            ShipCollision first = null;
            int records = 0;
            for (ShipCollision hit : shipHits) {
                if (hit.other() == null || hit.other().id() != other.ship.getId()) continue;
                records++;
                if (first == null && hit.started()) first = hit;
            }
            helper.assertTrue(records > 0, "no collision of ship " + ship.getId() + " with ship " + other.ship.getId()
                    + " was reported");
            helper.assertTrue(outsideBounds == 0, outsideBounds + " contact point(s) of ship " + ship.getId() + " lay more "
                    + "than 1 block outside its world bounds (farthest " + fmt(farthestOutside) + ")");
            helper.assertTrue(first != null, "no collision of ship " + ship.getId() + " with ship " + other.ship.getId()
                    + " was marked as started");
            Vec3 normal = first.contacts().get(0).normal();
            helper.assertTrue(normal.dot(expected) > 0.7, "ship " + ship.getId() + "'s first contact normal " + normal
                    + " doesn't point from ship " + other.ship.getId() + " toward it (" + expected + ")");
        }

        /** Game-test condition: the ship hit terrain at least {@code ticks} ticks ago. */
        void demandCrashedAndSettled(int ticks) {
            helper.assertTrue(firstContact >= 0, "ship " + ship.getId() + " hasn't touched terrain yet (at " + ref.position()
                    + ", v " + ref.velocity() + "; " + summary() + ")");
            helper.assertTrue(level.getGameTime() - firstContact >= ticks, "waiting after the crash");
        }

        /** Lets go of the ship: it falls. */
        void release() {
            start = ref.position();
            ship.setStatic(false);
            LOGGER.info("[FullStop ship test] ship {} ({} kg) released at {}", ship.getId(), Math.round(ref.mass()), ref.position());
        }

        /**
         * Game-test condition: VS's physics has taken the ship over (it has begun to fall). An
         * impulse queued before that - in the tick it stops being static - was simply lost.
         */
        void demandSimulated() {
            Vec3 v = ref.velocity();
            helper.assertTrue(v != null && v.y < -0.02, "VS isn't simulating ship " + ship.getId() + " yet (v " + v + ")");
        }

        /** Holds a floating ship up (a force equal to its weight) and pushes it to {@code velocity} (m/s). */
        void push(Vec3 velocity) {
            double mass = ref.mass();
            Vec3 now = ref.velocity();
            Vec3 gravity = gravity();
            if (floats && gravity != null) {
                ShipForces.setContinuous(level, ship.getId(), FLOAT, gravity.scale(-mass), null, ShipForces.UNTIL_CLEARED);
            }
            ShipForces.applyImpulse(level, ship.getId(), velocity.subtract(now).scale(mass), null);
            start = ref.position();
            LOGGER.info("[FullStop ship test] ship {} ({} kg) pushed to {} m/s from {} (was {})", ship.getId(),
                    Math.round(mass), velocity, start, now);
        }

        /**
         * The gravity on this ship (m/s^2): its own (VMod, VLib, zero in VS Genesis's space), else
         * the dimension's.
         */
        @Nullable
        Vec3 gravity() {
            Vec3 own = ref.gravity();
            return own != null ? own : Ships.dimensionGravity(level);
        }

        /**
         * Pins the ship where it is, stops its float and sends the watcher away (VS unloads the
         * ship at its next pass, after this tick's checks).
         */
        void pin() {
            ship.setStatic(true);
            ShipForces.clearContinuous(level, ship.getId(), FLOAT);
            stopThrust();
            playerList(level).remove(watcher);
        }

        /** Holds a floating ship up: a continuous force equal to its weight, nothing else. */
        void hover() {
            Vec3 gravity = gravity();
            if (gravity != null) {
                ShipForces.setContinuous(level, ship.getId(), FLOAT, gravity.scale(-ref.mass()), null, ShipForces.UNTIL_CLEARED);
            }
            start = ref.position();
        }

        /** The cruise this rig's engine holds (null: off), read on VS's physics thread by {@link #cruiseStep}. */
        volatile Vec3 cruiseTarget;
        volatile double cruiseAccel;

        /**
         * The test's engine: drives the ship's velocity toward {@code target} (m/s), at most {@code maxAccel} m/s^2
         * across and, separately, along the vertical - against VS's air drag, or to speed up and slow down gently
         * while holding height (on Jolt a floating raft otherwise sinks while it speeds up). Tests call it every tick
         * (the target can change); the force itself is worked out on VS's physics thread every physics step
         * ({@link #cruiseStep}), from the velocity that step has. It used to be a continuous force set from the
         * server tick: whenever physics frames came slower than server ticks (a busy test batch) it kept pushing on
         * a stale velocity, and a striker meant to bump a raft at 6 m/s hit it at 24 and tipped over.
         */
        void cruise(Vec3 target, double maxAccel) {
            cruiseTarget = target;
            cruiseAccel = maxAccel;
            ensureCruise();
            CRUISING.put(ship.getId(), this);
        }

        void stopThrust() {
            cruiseTarget = null;
            CRUISING.remove(ship.getId());
            ShipForces.clearContinuous(level, ship.getId(), THRUST);
        }

        /**
         * One tick of spinning the ship up about world y toward {@code omega} (rad/s), at most
         * {@code maxAlpha} rad/s^2, with {@code inertia} (kg m^2) as the ship's moment about y.
         */
        void spin(double omega, double maxAlpha, double inertia) {
            // once per physics frame (1/60 s): this server ticks faster than physics, and impulses
            // queued on the ticks between two frames would all land on the same step
            ShipTracker.State state = ShipTracker.of(level).get(ship.getId(), level.getGameTime());
            Vec3 w = ref.angularVelocity();
            if (w == null || state == null || !state.newFrame()) return;
            double step = Math.max(-maxAlpha / 60.0, Math.min(maxAlpha / 60.0, omega - w.y));
            ShipForces.applyAngularImpulse(level, ship.getId(), new Vec3(0.0, inertia * step, 0.0));
        }

        double x() {
            Vec3 p = ref.position();
            return p == null ? Double.NaN : p.x;
        }

        double y() {
            Vec3 p = ref.position();
            return p == null ? Double.NaN : p.y;
        }

        int count(Block block) {
            AtomicInteger n = new AtomicInteger();
            ref.forEachBlock(state -> state.is(block), (pos, state) -> n.incrementAndGet());
            return n.get();
        }

        /**
         * Item entities of {@code item} anywhere in the shipyard. Not an entity query over the
         * shipyard box: VS answers those with the entities around the ship in the world.
         */
        int shipyardItems(Item item) {
            int n = 0;
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof ItemEntity drop && drop.getItem().is(item)
                        && Ships.inShipyard(level, drop.getX(), drop.getY(), drop.getZ())) {
                    n += drop.getItem().getCount();
                }
            }
            return n;
        }

        /**
         * Each tick: this ship's contacts as Camafy published them (terrain and ship), with a sanity
         * check of each, and which source Camafy used.
         */
        void record() {
            AABB bounds = ref.worldAabb();
            Vec3 velocity = ref.velocity();
            Vec3 before = lastVelocity;
            boolean loaded = ref.isLoaded();
            if (loaded && loadedSince < 0) loadedSince = level.getGameTime();
            if (loaded) sources.add(ShipCollisions.source());
            if (velocity != null) {
                fastestSeen = Math.max(fastestSeen, velocity.length());
                if (watching && !velocity.equals(lastVelocity)) windowFrames++;
            }
            lastVelocity = velocity;
            Vec3 spin = ref.angularVelocity();
            stillTicks = velocity != null && spin != null && velocity.length() < 0.05 && spin.length() < 0.05 ? stillTicks + 1 : 0;
            if (loaded != wasLoaded || (start != null && level.getGameTime() % 40 == 0)) {
                LOGGER.info("[FullStop ship test] ship {} t{}: loaded {} static {} at {} v {}", ship.getId(),
                        level.getGameTime(), loaded, ship.isStatic(), ref.position(), velocity);
                wasLoaded = loaded;
            }
            for (ShipCollision hit : ShipCollisions.thisTick(level)) {
                if (hit.ship().id() != ship.getId()) continue;
                if (hit.isTerrain()) {
                    terrainHits.add(hit);
                    if (firstContact < 0 && start != null) firstContact = level.getGameTime();
                    double hardest = 0.0;
                    for (ShipCollision.Contact c : hit.contacts()) {
                        double into = before == null ? 0.0 : -before.dot(c.normal());
                        hardest = Math.max(hardest, Math.max(c.approachMps(), into));
                    }
                    if (hit.started() && hardest >= 1.5) {
                        long now = level.getGameTime();
                        if (now - lastHardTick > 10) {
                            bursts++;
                            burstLog.add("t" + now + " " + fmt(hardest) + " m/s");
                        } else {
                            burstLog.set(burstLog.size() - 1, burstLog.get(burstLog.size() - 1) + " +t" + now);
                        }
                        lastHardTick = now;
                    }
                } else {
                    shipHits.add(hit);
                    if (firstShipContact < 0) firstShipContact = level.getGameTime();
                }
                if (watching) {
                    windowRecords++;
                    windowContacts += hit.contacts().size();
                }
                for (ShipCollision.Contact c : hit.contacts()) {
                    if (watching) windowFastest = Math.max(windowFastest, c.approachMps());
                    double outside = bounds == null ? Double.NaN : outside(bounds, c.point());
                    if (!(outside <= 1.0)) outsideBounds++;
                    if (outside > farthestOutside) farthestOutside = outside;
                    if (contactLog.size() < 40) {
                        String line = String.format(Locale.ROOT,
                                "t%d %s %s %s point(%.2f,%.2f,%.2f) normal(%.2f,%.2f,%.2f) approach %.2f sep %.3f | %.2f outside bounds, ship v %s",
                                level.getGameTime(), ShipCollisions.source(), hit.isTerrain() ? "terrain" : "ship " + hit.other().id(),
                                hit.started() ? "START" : "cont", c.point().x, c.point().y, c.point().z,
                                c.normal().x, c.normal().y, c.normal().z, c.approachMps(), c.separation(), outside,
                                velocity == null ? "?" : String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)", velocity.x, velocity.y, velocity.z));
                        contactLog.add(line);
                        LOGGER.info("[FullStop ship test] ship {} contact {}", ship.getId(), line);
                    }
                }
            }
        }

        /**
         * The contacts must exist, lie at the ship in WORLD space, and point from the terrain
         * toward the ship ({@code expected} is that direction for this scenario).
         */
        void demandContacts(Vec3 expected) {
            demandSource();
            helper.assertTrue(!terrainHits.isEmpty(), "Camafy reported no terrain contact for the ship at all");
            helper.assertTrue(outsideBounds == 0, outsideBounds + " contact point(s) lay more than 1 block outside the ship's"
                    + " world bounds (farthest " + fmt(farthestOutside) + "): not world space?");
            ShipCollision first = null;
            for (ShipCollision hit : terrainHits) {
                if (hit.started()) {
                    first = hit;
                    break;
                }
            }
            helper.assertTrue(first != null, "no terrain contact of the ship was marked as started");
            Vec3 normal = first.contacts().get(0).normal();
            helper.assertTrue(normal.dot(expected) > 0.7, "the first contact's normal " + normal
                    + " doesn't point from the terrain toward the ship (" + expected + ")");
        }

        String summary() {
            int started = 0;
            double fastest = 0.0;
            for (ShipCollision hit : terrainHits) {
                if (hit.started()) started++;
                for (ShipCollision.Contact c : hit.contacts()) fastest = Math.max(fastest, c.approachMps());
            }
            for (ShipCollision hit : shipHits) {
                for (ShipCollision.Contact c : hit.contacts()) fastest = Math.max(fastest, c.approachMps());
            }
            AtomicInteger raw = RAW_EVENTS.get(ship.getId());
            return "backend " + BACKEND + ", source " + sources + ", VS raw events " + (raw == null ? 0 : raw.get())
                    + ", Camafy " + terrainHits.size() + " terrain records (" + started + " started), " + shipHits.size()
                    + " ship records, fastest approach " + fmt(fastest) + " m/s, " + outsideBounds
                    + " points off the hull (farthest " + fmt(farthestOutside) + ")";
        }
    }

    /**
     * The server's own player list (what VS reads), not the read-only view {@code getPlayers()}
     * hands out.
     */
    @SuppressWarnings("unchecked")
    static List<ServerPlayer> playerList(ServerLevel level) {
        try {
            return (List<ServerPlayer>) ObfuscationReflectionHelper.findField(PlayerList.class, "f_11196_")
                    .get(level.getServer().getPlayerList());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("can't reach the server's player list", e);
        }
    }

    /** How far from its plot (along z) each test builds, in a chunk nothing has loaded yet. */
    static final int AWAY = 4096;

    /**
     * Where a test builds: {@link #AWAY} blocks from its plot, on the flat world's ground, in
     * chunks this call loads and force-loads itself - so the scenario's terrain is placed in the
     * same tick, before VS's end-of-tick pass hands those chunks to its physics.
     */
    private static BlockPos origin(GameTestHelper helper) {
        return origin(helper, AWAY);
    }

    static BlockPos origin(GameTestHelper helper, int away) {
        ServerLevel level = helper.getLevel();
        BlockPos plot = helper.absolutePos(BlockPos.ZERO);
        int groundTop = level.getMinBuildHeight() + 4; // the flat world: bedrock, dirt, dirt, grass
        BlockPos o = new BlockPos(plot.getX() + 8, groundTop, plot.getZ() + away);
        for (int cx = (o.getX() - 8) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 8) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    static List<BlockPos> fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
        List<BlockPos> placed = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, state, Block.UPDATE_ALL);
            placed.add(pos.immutable());
        }
        return placed;
    }

    static Set<BlockPos> fillSet(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
        return new HashSet<>(fill(level, from, to, state));
    }

    /** How many of {@code positions} no longer hold {@code block}. */
    private static int broken(ServerLevel level, List<BlockPos> positions, Block block) {
        int n = 0;
        for (BlockPos pos : positions) {
            if (!level.getBlockState(pos).is(block)) n++;
        }
        return n;
    }

    private static int items(ServerLevel level, AABB box, Item item) {
        int n = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (entity.getItem().is(item)) n += entity.getItem().getCount();
        }
        return n;
    }

    private static double outside(AABB box, Vec3 p) {
        double dx = Math.max(0.0, Math.max(box.minX - p.x, p.x - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - p.y, p.y - box.maxY));
        double dz = Math.max(0.0, Math.max(box.minZ - p.z, p.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static void report(String what, String detail) {
        LOGGER.info("[FullStop ship test] {}: {}", what, detail);
    }

    static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
