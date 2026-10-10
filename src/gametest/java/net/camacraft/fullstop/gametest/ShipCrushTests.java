package net.camacraft.fullstop.gametest;

import com.mojang.authlib.GameProfile;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.camacraft.fullstop.server.physics.ship.ShipPunches;
import net.camacraft.fullstop.server.physics.ship.ShipTerrainImpacts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fill;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fmt;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * The owner's three reports of 2026-10-04 about ships:
 * <ul>
 *   <li>"I can't break blocks on valkyrien skies ships using other heavier ships": a 3x3x3 obsidian ship (135 t)
 *       against a one-plank ship (500 kg). Lying on the ground under the cube, or pinned against a wall, the plank
 *       has nowhere to go and breaks ({@code crushPinnedShips}); free in the air it breaks when the cube comes too fast
 *       for it to be shoved aside ({@code shipShoveLimit}), and is only shoved by a slow one;</li>
 *   <li>"TNT that is on a vs ship or is a vs ship should explode on impact" ({@code tntImpacts}): a ship that is one
 *       TNT block dropped from high (at once) and from low (lit, a short fuse), TNT on the ground under a falling
 *       hull, a TNT ship rammed by another ship;</li>
 *   <li>hits on ships ({@code punchShips}): a fist, any item, Knockback, Pullback, sneaking, the old rule.</li>
 * </ul>
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipCrushTests {

    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_ship_crush";
    private static final String TNT_OFF_BATCH = "fullstop_ship_tnt_off";
    /** Long: a fresh test server ticks unthrottled at first, many times faster than VS's physics steps. */
    private static final int TIMEOUT = 3600;
    private static final String OLD_PUNCH_BATCH = "fullstop_ship_punch_old_rule";

    private ShipCrushTests() {
    }

    /** Every explosion since the tests began: TNT going off in the air leaves nothing else to see. */
    private record Bang(Vec3 at, long tick) {}

    private static final List<Bang> BANGS = new CopyOnWriteArrayList<>();
    private static boolean listening;

    private static synchronized void listen() {
        if (listening) return;
        MinecraftForge.EVENT_BUS.addListener((ExplosionEvent.Detonate e) -> {
            if (e.getLevel() instanceof ServerLevel level) BANGS.add(new Bang(e.getExplosion().getPosition(), level.getGameTime()));
        });
        listening = true;
    }

    private static int bangs(Vec3 near, double within, long since) {
        int n = 0;
        for (Bang bang : BANGS) {
            if (bang.tick() >= since && bang.at().distanceTo(near) <= within) n++;
        }
        return n;
    }

    /** A lane of its own for each test, 256 blocks apart, 40 blocks around it force-loaded. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        listen();
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (210 + lane) * 256);
        ServerLevel level = helper.getLevel();
        for (int cx = (o.getX() - 40) >> 4; cx <= (o.getX() + 40) >> 4; cx++) {
            for (int cz = (o.getZ() - 40) >> 4; cz <= (o.getZ() + 40) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    // ------------------------------------------------------------------
    // A heavy ship against a one-plank ship
    // ------------------------------------------------------------------

    /** The plank lies on a stone floor; the cube comes down on it at 8 m/s. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void anObsidianShipComingDownOnAPlankShipOnTheGroundBreaksIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 0);
        fill(level, o.offset(-3, 0, -3), o.offset(5, 0, 5), Blocks.STONE.defaultBlockState());
        Rig plank = Rig.build(helper, fillSet(level, o.offset(1, 1, 1), o.offset(1, 1, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, false);
        if (plank == null) return;
        Rig cube = Rig.build(helper, fillSet(level, o.offset(0, 7, 0), o.offset(2, 9, 2), Blocks.OBSIDIAN.defaultBlockState()),
                Blocks.OBSIDIAN, false);
        if (cube == null) return;
        strike(helper, plank, cube, new Vec3(0.0, -8.0, 0.0), true, "obsidian cube down at 8 m/s on a plank ship lying on stone");
    }

    /** The plank lies on the floor against a stone wall; the cube slides into it at 10 m/s. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void anObsidianShipPinningAPlankShipAgainstAWallBreaksIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 1);
        fill(level, o.offset(-14, 0, -3), o.offset(6, 0, 5), Blocks.STONE.defaultBlockState());
        fill(level, o.offset(2, 1, -1), o.offset(3, 3, 3), Blocks.STONE.defaultBlockState());
        Rig plank = Rig.build(helper, fillSet(level, o.offset(1, 1, 1), o.offset(1, 1, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, false);
        if (plank == null) return;
        Rig cube = Rig.build(helper, fillSet(level, o.offset(-11, 1, 0), o.offset(-9, 3, 2), Blocks.OBSIDIAN.defaultBlockState()),
                Blocks.OBSIDIAN, true);
        if (cube == null) return;
        strike(helper, plank, cube, new Vec3(10.0, 0.0, 0.0), true, "obsidian cube at 10 m/s into a plank ship pinned against a stone wall");
    }

    /** The plank floats free in the air; the cube is swung into it at 25 m/s: too fast for the plank to be shoved aside. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void anObsidianShipSwungFastIntoAFreePlankShipBreaksIt(GameTestHelper helper) {
        swing(helper, 2, 25.0, true, "obsidian cube at 25 m/s into a free plank ship");
    }

    /** The same at 7 m/s: the plank is shoved away, whole (two free ships crash with their reduced mass). */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void anObsidianShipBumpingAFreePlankShipOnlyShovesIt(GameTestHelper helper) {
        swing(helper, 3, 7.0, false, "obsidian cube at 7 m/s into a free plank ship");
    }

    private static void swing(GameTestHelper helper, int lane, double speed, boolean expectBroken, String what) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        Rig plank = Rig.build(helper, fillSet(level, o.offset(1, 6, 1), o.offset(1, 6, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (plank == null) return;
        Rig cube = Rig.build(helper, fillSet(level, o.offset(-11, 5, 0), o.offset(-9, 7, 2), Blocks.OBSIDIAN.defaultBlockState()),
                Blocks.OBSIDIAN, true);
        if (cube == null) return;
        strike(helper, plank, cube, new Vec3(speed, 0.0, 0.0), expectBroken, what);
    }

    /** How much of a ship counts as held, by the speed a crash would shove it by ({@code shipShoveLimit} 15). */
    @GameTest(template = FLAT, batch = BATCH)
    public static void aShipCountsAsHeldFromHalfTheShoveLimitUp(GameTestHelper helper) {
        near(helper, "at 5 m/s", ShipTerrainImpacts.stuck(5.0, 15.0), 0.0);
        near(helper, "at half the limit", ShipTerrainImpacts.stuck(7.5, 15.0), 0.0);
        near(helper, "three quarters", ShipTerrainImpacts.stuck(11.25, 15.0), 0.5);
        near(helper, "at the limit", ShipTerrainImpacts.stuck(15.0, 15.0), 1.0);
        near(helper, "past it", ShipTerrainImpacts.stuck(60.0, 15.0), 1.0);
        near(helper, "switched off", ShipTerrainImpacts.stuck(60.0, 0.0), 0.0);
        helper.succeed();
    }

    private static void near(GameTestHelper helper, String what, double got, double want) {
        helper.assertTrue(Math.abs(got - want) < 1.0e-9, what + ": " + got + ", expected " + want);
    }

    /**
     * {@code striker} (the obsidian cube) driven at {@code velocity} into {@code target} (the plank), which was let go
     * first and has settled (hovering when the rig floats).
     */
    private static void strike(GameTestHelper helper, Rig target, Rig striker, Vec3 velocity, boolean expectBroken, String what) {
        helper.onEachTick(target::record);
        helper.onEachTick(striker::record);
        boolean[] driving = {false};
        double[] met = {0.0};
        double[] targetPeak = {0.0};
        long[] shovedAt = {-1};
        helper.onEachTick(() -> {
            if (!driving[0]) return;
            Vec3 tv = target.ref.velocity();
            if (tv != null) targetPeak[0] = Math.max(targetPeak[0], tv.length());
            if (shovedAt[0] < 0 && targetPeak[0] > 2.0) shovedAt[0] = helper.getLevel().getGameTime();
            Vec3 v = striker.ref.velocity();
            if (striker.firstShipContact < 0 && v != null) met[0] = v.length();
            striker.cruise(velocity, 150.0);
        });
        helper.startSequence()
                .thenWaitUntil(target::demandReady)
                .thenWaitUntil(striker::demandReady)
                .thenExecute(() -> {
                    target.release();
                    if (target.floats) target.hover();
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    striker.release();
                    if (striker.floats) striker.hover();
                    driving[0] = true;
                })
                .thenWaitUntil(() -> {
                    if (expectBroken) {
                        helper.assertTrue(target.count(Blocks.OAK_PLANKS) == 0, "the plank is still there (striker at "
                                + striker.ref.position() + ", v " + striker.ref.velocity() + "; plank at " + target.ref.position() + ")");
                    } else {
                        // shoved and given time to break (on Camafy's fallback no collision record comes for this hit)
                        helper.assertTrue(shovedAt[0] >= 0 && helper.getLevel().getGameTime() - shovedAt[0] >= 30,
                                "the plank hasn't been shoved yet (peak " + fmt(targetPeak[0]) + " m/s)");
                    }
                })
                .thenExecute(() -> {
                    driving[0] = false;
                    int obsidian = striker.count(Blocks.OBSIDIAN);
                    int planks = target.count(Blocks.OAK_PLANKS);
                    striker.pin();
                    target.pin();
                    String out = "plank " + (planks == 0 ? "broken" : "whole") + ", obsidian " + obsidian + " of 27, striker at "
                            + fmt(met[0]) + " m/s when they met, the plank's peak speed " + fmt(targetPeak[0]) + " m/s";
                    report(what, out);
                    helper.assertTrue(obsidian == 27, "the obsidian lost blocks: " + out);
                    if (expectBroken) {
                        helper.assertTrue(planks == 0, "the plank held: " + out);
                    } else {
                        helper.assertTrue(planks == 1, "the plank broke: " + out);
                        helper.assertTrue(targetPeak[0] > 2.0, "the plank was never shoved: " + out);
                    }
                    helper.succeed();
                });
    }

    // ------------------------------------------------------------------
    // TNT
    // ------------------------------------------------------------------

    /** A ship that is one TNT block, dropped 14 blocks onto stone (about 16 m/s): it explodes where it lands, that tick. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aTntShipDroppedFromHighExplodesWhereItLands(GameTestHelper helper) {
        tntDrop(helper, 4, 14, true, true);
    }

    /** The same dropped 4 blocks (about 9 m/s): lit, and it goes off half a second later. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aTntShipDroppedFromLowIsLitAndGoesOffSoonAfter(GameTestHelper helper) {
        tntDrop(helper, 5, 4, false, true);
    }

    /** With {@code tntImpacts} off the dropped TNT ship just lands (and breaks as any hull block would). */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = TNT_OFF_BATCH)
    public static void withTntImpactsOffADroppedTntShipNeverGoesOff(GameTestHelper helper) {
        tntDrop(helper, 6, 14, true, false);
    }

    @BeforeBatch(batch = TNT_OFF_BATCH)
    public static void tntOff(ServerLevel level) {
        SERVER.tntImpacts.set(false);
    }

    @AfterBatch(batch = TNT_OFF_BATCH)
    public static void tntOn(ServerLevel level) {
        SERVER.tntImpacts.set(true);
    }

    private static void tntDrop(GameTestHelper helper, int lane, int height, boolean atOnce, boolean enabled) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        List<BlockPos> floor = fill(level, o.offset(-3, 0, -3), o.offset(5, 0, 5), Blocks.STONE.defaultBlockState());
        Rig tnt = Rig.build(helper, fillSet(level, o.offset(1, 1 + height, 1), o.offset(1, 1 + height, 1), Blocks.TNT.defaultBlockState()),
                Blocks.TNT, false);
        if (tnt == null) return;
        helper.onEachTick(tnt::record);
        Vec3 landing = Vec3.atCenterOf(o.offset(1, 1, 1));
        AABB around = new AABB(o.offset(1, 1, 1)).inflate(8.0);
        long[] at = new long[3]; // let go, the block gone, the bang
        int[] primedSeen = new int[2]; // lit TNT seen at all; its fuse when first seen
        helper.onEachTick(() -> {
            List<PrimedTnt> primed = level.getEntitiesOfClass(PrimedTnt.class, around);
            if (!primed.isEmpty() && primedSeen[0]++ == 0) primedSeen[1] = primed.get(0).getFuse();
            if (at[0] > 0 && at[2] == 0 && bangs(landing, 4.0, at[0]) > 0) at[2] = level.getGameTime();
        });
        var sequence = helper.startSequence()
                .thenWaitUntil(tnt::demandReady)
                .thenExecute(() -> {
                    at[0] = level.getGameTime();
                    tnt.release();
                });
        if (!enabled) {
            sequence.thenWaitUntil(() -> tnt.demandCrashedAndSettled(60))
                    .thenExecute(() -> {
                        tnt.pin();
                        String out = "bangs " + bangs(landing, 6.0, at[0]) + ", lit TNT seen " + primedSeen[0] + " ticks, fastest "
                                + fmt(tnt.fastestSeen) + " m/s";
                        report("TNT ship dropped " + height + " blocks, tntImpacts off", out);
                        helper.assertTrue(bangs(landing, 6.0, at[0]) == 0 && primedSeen[0] == 0, "it went off all the same: " + out);
                        helper.succeed();
                    });
            return;
        }
        sequence.thenWaitUntil(() -> helper.assertTrue(tnt.count(Blocks.TNT) == 0, "the TNT is still on its ship (at "
                        + tnt.ref.position() + ", v " + tnt.ref.velocity() + ")"))
                .thenExecute(() -> at[1] = level.getGameTime())
                .thenWaitUntil(() -> helper.assertTrue(at[2] > 0, "no explosion yet"))
                .thenIdle(5)
                .thenExecute(() -> {
                    int stoneLost = 0;
                    for (BlockPos pos : floor) if (!level.getBlockState(pos).is(Blocks.STONE)) stoneLost++;
                    tnt.pin();
                    String out = "fell at up to " + fmt(tnt.fastestSeen) + " m/s; the block went " + (at[1] - at[0]) + " ticks after it was "
                            + "let go, the bang " + (at[2] - at[1]) + " ticks after that; lit TNT seen for " + primedSeen[0]
                            + " ticks (fuse " + primedSeen[1] + " when first seen); floor stone lost " + stoneLost;
                    report("TNT ship dropped " + height + " blocks", out);
                    helper.assertTrue(stoneLost > 0, "the explosion left the floor whole: " + out);
                    helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, around, i -> i.getItem().is(Items.TNT)).isEmpty(),
                            "the TNT dropped as an item: " + out);
                    if (atOnce) {
                        helper.assertTrue(at[2] - at[1] <= 1 && primedSeen[0] == 0, "it should have gone off at once: " + out);
                    } else {
                        int fuse = SERVER.tntImpactFuseTicks.get();
                        helper.assertTrue(primedSeen[0] > 0 && primedSeen[1] <= fuse, "it should have been lit with the short fuse: " + out);
                        helper.assertTrue(at[2] - at[1] >= fuse - 2 && at[2] - at[1] <= fuse + 3, "the fuse wasn't " + fuse + " ticks: " + out);
                    }
                    helper.succeed();
                });
    }

    /** TNT standing on the ground, an obsidian cube dropped 12 blocks onto it: it explodes under the hull, nothing drops. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void tntOnTheGroundGoesOffUnderAFallingHull(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 7);
        fill(level, o.offset(-3, 0, -3), o.offset(5, 0, 5), Blocks.STONE.defaultBlockState());
        BlockPos tnt = o.offset(1, 1, 1);
        level.setBlock(tnt, Blocks.TNT.defaultBlockState(), Block.UPDATE_ALL);
        Rig cube = Rig.build(helper, fillSet(level, o.offset(0, 14, 0), o.offset(2, 16, 2), Blocks.OBSIDIAN.defaultBlockState()),
                Blocks.OBSIDIAN, false);
        if (cube == null) return;
        helper.onEachTick(cube::record);
        long[] from = new long[1];
        AABB around = new AABB(tnt).inflate(8.0);
        helper.startSequence()
                .thenWaitUntil(cube::demandReady)
                .thenExecute(() -> {
                    from[0] = level.getGameTime();
                    cube.release();
                })
                .thenWaitUntil(() -> helper.assertTrue(!level.getBlockState(tnt).is(Blocks.TNT), "the TNT still stands (cube at "
                        + cube.ref.position() + ")"))
                .thenIdle(3)
                .thenExecute(() -> {
                    int bangs = bangs(Vec3.atCenterOf(tnt), 3.0, from[0]);
                    int obsidian = cube.count(Blocks.OBSIDIAN);
                    cube.pin();
                    String out = "bangs " + bangs + ", obsidian " + obsidian + " of 27, cube at up to " + fmt(cube.fastestSeen) + " m/s";
                    report("obsidian cube dropped 12 blocks onto TNT on the ground", out);
                    helper.assertTrue(bangs >= 1, "the TNT was crushed without going off: " + out);
                    helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, around, i -> i.getItem().is(Items.TNT)).isEmpty(),
                            "the TNT dropped as an item: " + out);
                    helper.assertTrue(obsidian == 27, "the obsidian lost blocks: " + out);
                    helper.succeed();
                });
    }

    /** A hovering ship that is one TNT block, rammed by the obsidian cube at 14 m/s: it explodes on the cube's bow. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aTntShipRammedByAnotherShipExplodes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 8);
        Rig tnt = Rig.build(helper, fillSet(level, o.offset(1, 6, 1), o.offset(1, 6, 1), Blocks.TNT.defaultBlockState()), Blocks.TNT, true);
        if (tnt == null) return;
        Rig cube = Rig.build(helper, fillSet(level, o.offset(-11, 5, 0), o.offset(-9, 7, 2), Blocks.OBSIDIAN.defaultBlockState()),
                Blocks.OBSIDIAN, true);
        if (cube == null) return;
        helper.onEachTick(tnt::record);
        helper.onEachTick(cube::record);
        boolean[] driving = {false};
        long[] from = new long[1];
        Vec3[] where = new Vec3[1];
        helper.onEachTick(() -> {
            if (!driving[0]) return;
            Vec3 p = tnt.ref.position();
            if (p != null && tnt.count(Blocks.TNT) > 0) where[0] = p;
            cube.cruise(new Vec3(14.0, 0.0, 0.0), 150.0);
        });
        helper.startSequence()
                .thenWaitUntil(tnt::demandReady)
                .thenWaitUntil(cube::demandReady)
                .thenExecute(() -> {
                    tnt.release();
                    tnt.hover();
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    from[0] = level.getGameTime();
                    cube.release();
                    cube.hover();
                    driving[0] = true;
                })
                .thenWaitUntil(() -> helper.assertTrue(tnt.count(Blocks.TNT) == 0, "the TNT is still on its ship (cube at "
                        + cube.ref.position() + ", v " + cube.ref.velocity() + ")"))
                .thenIdle(3)
                .thenExecute(() -> {
                    driving[0] = false;
                    int bangs = where[0] == null ? 0 : bangs(where[0], 6.0, from[0]);
                    int obsidian = cube.count(Blocks.OBSIDIAN);
                    cube.pin();
                    tnt.pin();
                    String out = "bangs " + bangs + " near " + where[0] + ", obsidian " + obsidian + " of 27, cube at up to "
                            + fmt(cube.fastestSeen) + " m/s";
                    report("obsidian cube at 14 m/s into a hovering TNT ship", out);
                    helper.assertTrue(bangs >= 1, "the TNT went without an explosion: " + out);
                    helper.assertTrue(obsidian == 27, "the obsidian lost blocks: " + out);
                    helper.succeed();
                });
    }

    /** TNT in the middle of the obsidian cube's bow, driven at 15 m/s into a free plank ship: the TNT goes off on the bow. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void tntOnAShipsBowGoesOffAgainstAnotherShip(GameTestHelper helper) {
        tntBow(helper, 10, false);
    }

    /** The same with the TNT low on the bow and the plank lying on a stone floor (the pack harness' rig). */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void tntLowOnAShipsBowGoesOffAgainstAShipOnTheGround(GameTestHelper helper) {
        tntBow(helper, 11, true);
    }

    private static void tntBow(GameTestHelper helper, int lane, boolean onTheGround) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        int y = onTheGround ? 1 : 5;
        if (onTheGround) fill(level, o.offset(-16, 0, -3), o.offset(8, 0, 5), Blocks.STONE.defaultBlockState());
        BlockPos plankAt = onTheGround ? o.offset(1, 1, 1) : o.offset(1, y + 1, 1);
        Rig plank = Rig.build(helper, fillSet(level, plankAt, plankAt, Blocks.OAK_PLANKS.defaultBlockState()), Blocks.OAK_PLANKS, !onTheGround);
        if (plank == null) return;
        java.util.Set<BlockPos> hull = fillSet(level, o.offset(-13, y, 0), o.offset(-11, y + 2, 2), Blocks.OBSIDIAN.defaultBlockState());
        BlockPos tntAt = onTheGround ? o.offset(-11, y, 1) : o.offset(-11, y + 1, 1);
        level.setBlock(tntAt, Blocks.TNT.defaultBlockState(), Block.UPDATE_ALL);
        Rig cube = Rig.build(helper, hull, Blocks.OBSIDIAN, true, 26);
        if (cube == null) return;
        helper.onEachTick(plank::record);
        helper.onEachTick(cube::record);
        boolean[] driving = {false};
        long[] from = new long[1];
        Vec3[] bow = new Vec3[1];
        helper.onEachTick(() -> {
            if (!driving[0]) return;
            Vec3 p = cube.ref.position();
            if (p != null && cube.count(Blocks.TNT) > 0) bow[0] = p;
            cube.cruise(new Vec3(15.0, 0.0, 0.0), 150.0);
        });
        helper.startSequence()
                .thenWaitUntil(plank::demandReady)
                .thenWaitUntil(cube::demandReady)
                .thenExecute(() -> {
                    plank.release();
                    if (plank.floats) plank.hover();
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    from[0] = level.getGameTime();
                    cube.release();
                    cube.hover();
                    driving[0] = true;
                })
                .thenWaitUntil(() -> helper.assertTrue(cube.count(Blocks.TNT) == 0, "the TNT is still on the bow (cube at "
                        + cube.ref.position() + ", v " + cube.ref.velocity() + "; plank at " + plank.ref.position() + ", v "
                        + plank.ref.velocity() + ")"))
                .thenIdle(3)
                .thenExecute(() -> {
                    driving[0] = false;
                    int bangs = bow[0] == null ? 0 : bangs(bow[0], 6.0, from[0]);
                    int obsidian = cube.count(Blocks.OBSIDIAN);
                    cube.pin();
                    plank.pin();
                    String out = "bangs " + bangs + " near " + bow[0] + ", obsidian " + obsidian + " of 26, cube at up to "
                            + fmt(cube.fastestSeen) + " m/s";
                    report("obsidian cube with TNT " + (onTheGround ? "low " : "") + "on its bow at 15 m/s into a plank ship"
                            + (onTheGround ? " lying on stone" : ""), out);
                    helper.assertTrue(bangs >= 1, "the TNT went without an explosion: " + out);
                    helper.assertTrue(obsidian == 26, "the obsidian lost blocks: " + out);
                    helper.succeed();
                });
    }

    // ------------------------------------------------------------------
    // Hits
    // ------------------------------------------------------------------

    private record Hit(String what, ItemStack held, boolean sneak, boolean justBroke, double expected) {}

    /**
     * Seven one-plank ships hovering in a row, each hit once on its west face by a player of its own looking east:
     * a fist pushes it away; a stick as hard; a stick with Knockback II three times as hard; a sword with Pullback II
     * pulls it three times as hard toward the player; sneaking and a hit right after the player broke a block (the held
     * button mining on) push nothing. (The fake players' attack damage stays a fist's: their items' modifiers are never
     * applied, so the item's own weight in the push isn't measured here; the pack harness' {@code fss_punch} does.)
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void hitsPushShipsByWhatIsInHand(GameTestHelper helper) {
        ItemStack knock = new ItemStack(Items.STICK);
        knock.enchant(Enchantments.KNOCKBACK, 2);
        ItemStack pull = new ItemStack(Items.IRON_SWORD);
        pull.enchant(ModEnchantments.PULLBACK.get(), 2);
        hitRow(helper, 9, "hits on hovering one-plank ships", List.of(
                new Hit("fist", ItemStack.EMPTY, false, false, 1.0),
                new Hit("stick", new ItemStack(Items.STICK), false, false, 1.0),
                new Hit("stick, Knockback II", knock, false, false, 3.0),
                new Hit("sword, Pullback II", pull, false, false, -3.0),
                new Hit("fist, sneaking", ItemStack.EMPTY, true, false, 0.0),
                new Hit("fist, right after breaking a block", ItemStack.EMPTY, false, true, 0.0)));
    }

    /** {@code punchShipsAnyItem} off (the rule before 2026-10-04): a fist and a sword push, a pickaxe doesn't. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = OLD_PUNCH_BATCH)
    public static void withAnyItemOffOnlyAFistOrASwordPushes(GameTestHelper helper) {
        hitRow(helper, 12, "hits with punchShipsAnyItem off", List.of(
                new Hit("fist", ItemStack.EMPTY, false, false, 1.0),
                new Hit("sword", new ItemStack(Items.IRON_SWORD), false, false, 1.0),
                new Hit("pickaxe", new ItemStack(Items.IRON_PICKAXE), false, false, 0.0)));
    }

    @BeforeBatch(batch = OLD_PUNCH_BATCH)
    public static void anyItemOff(ServerLevel level) {
        SERVER.punchShipsAnyItem.set(false);
    }

    @AfterBatch(batch = OLD_PUNCH_BATCH)
    public static void anyItemOn(ServerLevel level) {
        SERVER.punchShipsAnyItem.set(true);
    }

    /**
     * One hovering one-plank ship per hit, in a row, each hit once on its west face by a player of its own looking east;
     * the first hit is the reference every other's {@code expected} multiplies (0: no push at all).
     */
    private static void hitRow(GameTestHelper helper, int lane, String what, List<Hit> hits) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        List<Rig> rigs = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            BlockPos at = o.offset(0, 6, (i - 3) * 6);
            Rig rig = Rig.build(helper, fillSet(level, at, at, Blocks.OAK_PLANKS.defaultBlockState()), Blocks.OAK_PLANKS, true);
            if (rig == null) return;
            rigs.add(rig);
            helper.onEachTick(rig::record);
        }
        boolean[] pushed = new boolean[hits.size()];
        double[] peak = new double[hits.size()];
        boolean[] watching = {false};
        helper.onEachTick(() -> {
            if (!watching[0]) return;
            for (int i = 0; i < rigs.size(); i++) {
                Vec3 v = rigs.get(i).ref.velocity();
                if (v != null && Math.abs(v.x) > Math.abs(peak[i])) peak[i] = v.x;
            }
        });
        var sequence = helper.startSequence();
        for (Rig rig : rigs) sequence.thenWaitUntil(rig::demandReady);
        sequence.thenExecute(() -> {
                    for (Rig rig : rigs) {
                        rig.release();
                        rig.hover();
                    }
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    for (int i = 0; i < rigs.size(); i++) {
                        Rig rig = rigs.get(i);
                        Hit hit = hits.get(i);
                        BlockPos[] block = new BlockPos[1];
                        rig.ref.forEachBlock(state -> state.is(Blocks.OAK_PLANKS), (pos, state) -> block[0] = pos.immutable());
                        Vec3 centre = rig.ref.position();
                        if (block[0] == null || centre == null) throw new IllegalStateException("ship " + i + " has no block");
                        FakePlayer player = FakePlayerFactory.get(level,
                                new GameProfile(UUID.nameUUIDFromBytes(("fullstop-punch-test-" + i).getBytes()), "[FS punch " + i + "]"));
                        // two blocks west of the face, the eyes level with the block's middle, looking east
                        player.setPos(centre.x - 2.5, centre.y - player.getEyeHeight(), centre.z);
                        player.setYRot(-90.0f);
                        player.setXRot(0.0f);
                        player.setShiftKeyDown(hit.sneak());
                        player.setItemInHand(InteractionHand.MAIN_HAND, hit.held().copy());
                        if (hit.justBroke()) {
                            ShipPunches.onBreak(new BlockEvent.BreakEvent(level, player.blockPosition(), Blocks.STONE.defaultBlockState(), player));
                        }
                        pushed[i] = ShipPunches.punch(level, player, block[0], Direction.WEST);
                        player.setShiftKeyDown(false);
                    }
                    watching[0] = true;
                })
                .thenIdle(12)
                .thenExecute(() -> {
                    watching[0] = false;
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < rigs.size(); i++) {
                        out.append(hits.get(i).what()).append(": ").append(pushed[i] ? "pushed, " : "refused, ")
                                .append(String.format(Locale.ROOT, "%.3f", peak[i])).append(" m/s; ");
                        rigs.get(i).pin();
                    }
                    report(what + " (east is away from the player)", out.toString());
                    double fist = peak[0];
                    helper.assertTrue(pushed[0] && fist > 0.2, "a fist pushed nothing: " + out);
                    for (int i = 1; i < hits.size(); i++) {
                        double expected = hits.get(i).expected() * fist;
                        if (hits.get(i).expected() == 0.0) {
                            helper.assertTrue(!pushed[i] && Math.abs(peak[i]) < 0.05, hits.get(i).what() + " should push nothing: " + out);
                        } else {
                            helper.assertTrue(pushed[i] && Math.abs(peak[i] - expected) < 0.15 * Math.abs(expected),
                                    hits.get(i).what() + " should give " + fmt(expected) + " m/s: " + out);
                        }
                    }
                    helper.succeed();
                });
    }
}
