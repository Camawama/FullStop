package net.camacraft.fullstop.common.compat.create.kinetic;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Create Catastrophes, the kinetic hazards, against the real Create 6.0.8: a creative motor drives a
 * cogwheel (or a waterfall turns a water wheel) beside a pig with 1000 health, and the pig is pulled
 * in, chewed or held under. Run with the ship tests' flat template:
 * {@code gradlew runGameTestServer -PshipTests -Pcreate -PtestOnly=KineticHazardTests -PgameTestDir=run-gametest-kinetics}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class KineticHazardTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_create_kinetic";
    /** With gearHazards off (a batch of its own: a batch's tests run together and share the config). */
    private static final String BATCH_OFF = "fullstop_create_kinetic_off";
    /** Timing, alone in its batch so no other test's pigs are counted. */
    private static final String BATCH_COST = "fullstop_create_kinetic_cost";
    private static final float HEALTH = 1000.0F;

    private KineticHazardTests() {
    }

    // ------------------------------------------------------------------ gears

    /**
     * A single spinning cogwheel never pulls, and bites in proportion to its teeth's speed: a pig pressed against its teeth
     * at 4 and 16 RPM (teeth under 1 m/s) for five seconds loses nothing, at 64 RPM (3.75 m/s) it is bitten about 1 a bite
     * (gearTouchDamage 2 at 7.5 m/s), never more than gearMaxDamage; none is flung (slower than gearFlingSpeed: they push).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void aSingleCogwheelBitesByItsSpeedAndNeverPulls(GameTestHelper helper) {
        int[] rpms = {4, 16, 64};
        Pig[] pigs = new Pig[rpms.length];
        double[] start = new double[rpms.length];
        double[] biggest = new double[rpms.length];
        listenToBites(pigs, biggest);
        for (int i = 0; i < rpms.length; i++) gearRig(helper, 4 + i * 10, 6, rpms[i], false);
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    for (int i = 0; i < rpms.length; i++) motor(helper, new BlockPos(4 + i * 10, 2, 6), rpms[i]);
                })
                .thenExecuteAfter(10, () -> {
                    for (int i = 0; i < rpms.length; i++) {
                        // beside the cogwheel in its plane, against its teeth (the old bite position)
                        pigs[i] = pig(helper, new Vec3(4 + i * 10 + 1.5, 2.0, 7.35));
                        pigs[i].addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 9, false, false));
                        start[i] = gap(helper, pigs[i], 4 + i * 10 + 1);
                    }
                })
                .thenExecuteAfter(100, () -> {
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < rpms.length; i++) {
                        out.append(String.format(Locale.ROOT, "%d RPM: %.1f lost (largest bite %.2f), gap to the teeth %.2f -> %.2f; ", rpms[i],
                                lost(pigs[i]), biggest[i], start[i], gap(helper, pigs[i], 4 + i * 10 + 1)));
                    }
                    report("a single cogwheel's teeth", out.toString());
                    double cap = FullStopConfig.SERVER.gearMaxDamage.get();
                    for (int i = 0; i < rpms.length; i++) {
                        helper.assertTrue(KineticHazards.lastFlingMps(pigs[i]) == 0.0, "a single " + rpms[i] + " RPM cogwheel flung the pig: " + out);
                        helper.assertTrue(biggest[i] <= cap + 1.0E-3, "a single " + rpms[i] + " RPM cogwheel bit more than gearMaxDamage: " + out);
                    }
                    helper.assertTrue(lost(pigs[0]) == 0.0 && lost(pigs[1]) == 0.0, "a crawling single cogwheel hurt the pig: " + out);
                    helper.assertTrue(lost(pigs[2]) > 0.0 && biggest[2] > 0.5 && biggest[2] < 1.5,
                            "a single 64 RPM cogwheel didn't bite the pig about 1 a bite: " + out);
                    for (Pig pig : pigs) pig.discard();
                })
                .thenSucceed();
    }

    /**
     * A very fast single gear flings what touches it (its teeth past gearFlingSpeed): a pig touching a cogwheel on the side
     * where its teeth run up, at 16, 64 and 256 RPM, and a large cogwheel at 256. At 16 it is nudged, at 64 pushed, at 256
     * thrown up and out (a large cogwheel's rim, twice as fast, throws it twice as hard); the gear bites it (from 64 RPM)
     * but never more than gearMaxDamage a bite (a landing may hurt too: Full Stop's, not the gear's).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 300)
    public static void aVeryFastSingleGearFlingsWhatTouchesIt(GameTestHelper helper) {
        int[] rpms = {16, 64, 256, 256};
        boolean[] large = {false, false, false, true};
        int[] xs = {4, 14, 24, 34};
        Pig[] pigs = new Pig[4];
        Vec3[] startAt = new Vec3[4];
        Vec3[] last = new Vec3[4];
        double[] fastest = new double[4];
        double[] highest = new double[4];
        java.util.Map<String, Double>[] hurts = new java.util.Map[]{new java.util.TreeMap<>(), new java.util.TreeMap<>(),
                new java.util.TreeMap<>(), new java.util.TreeMap<>()};
        listenToHurts(pigs, hurts);
        double[] biggest = new double[4];
        listenToBites(pigs, biggest);
        for (int i = 0; i < 4; i++) {
            if (large[i]) largeGearRig(helper, xs[i], 6);
            else gearRig(helper, xs[i], 6, rpms[i], false);
        }
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    for (int i = 0; i < 4; i++) motor(helper, new BlockPos(xs[i], large[i] ? 3 : 2, 6), rpms[i]);
                })
                .thenExecuteAfter(10, () -> {
                    for (int i = 0; i < 4; i++) {
                        int y = large[i] ? 3 : 2;
                        Hazard.Live live = HazardScanner.resolveAny(helper.getLevel(), new Hazard(large[i] ? Hazard.Kind.LARGE_COGWHEEL
                                : Hazard.Kind.COGWHEEL, helper.absolutePos(new BlockPos(xs[i] + 1, y, 6)), Hazard.NO_SHIP));
                        helper.assertTrue(live != null && live.driveRpm() != 0.0, "the gear at x " + xs[i] + " doesn't turn");
                        // the side where the teeth run up: about +x a positive speed runs them up on the -z side
                        int side = live.driveRpm() > 0 ? -1 : 1;
                        double out = large[i] ? 1.4 : 0.85;
                        pigs[i] = pig(helper, new Vec3(xs[i] + 1.5, 2.0, 6.5 + side * out));
                        pigs[i].addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 9, false, false));
                        startAt[i] = pigs[i].position();
                        last[i] = startAt[i];
                    }
                })
                .thenExecuteFor(40, () -> {
                    for (int i = 0; i < 4; i++) {
                        Vec3 now = pigs[i].position();
                        fastest[i] = Math.max(fastest[i], now.distanceTo(last[i]) * 20.0);
                        highest[i] = Math.max(highest[i], now.y - startAt[i].y);
                        last[i] = now;
                    }
                })
                .thenExecute(() -> {
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        out.append(String.format(Locale.ROOT, "%s at %d RPM: flung at %.1f m/s, fastest %.1f m/s, rose %.1f, %.1f away, hurt %s, largest bite %.2f; ",
                                large[i] ? "large cogwheel" : "cogwheel", rpms[i], KineticHazards.lastFlingMps(pigs[i]), fastest[i], highest[i],
                                Math.hypot(pigs[i].getX() - startAt[i].x, pigs[i].getZ() - startAt[i].z), hurts[i], biggest[i]));
                    }
                    report("a single gear's teeth, by speed", out.toString());
                    double cap = FullStopConfig.SERVER.gearMaxDamage.get();
                    for (int i = 0; i < 4; i++) {
                        helper.assertTrue(biggest[i] <= cap + 1.0E-3, "a single gear bit the pig more than gearMaxDamage: " + out);
                    }
                    helper.assertTrue(hurts[0].keySet().stream().noneMatch(t -> t.contains("gear")), "a 16 RPM cogwheel bit the pig: " + out);
                    helper.assertTrue(biggest[2] > 2.0, "a 256 RPM cogwheel didn't bite the pig: " + out);
                    helper.assertTrue(KineticHazards.lastFlingMps(pigs[0]) == 0.0 && fastest[0] < 3.0, "a 16 RPM cogwheel threw the pig: " + out);
                    helper.assertTrue(KineticHazards.lastFlingMps(pigs[1]) == 0.0, "a 64 RPM cogwheel threw the pig: " + out);
                    helper.assertTrue(KineticHazards.lastFlingMps(pigs[2]) > 15.0 && highest[2] > 2.0, "a 256 RPM cogwheel didn't fling the pig: " + out);
                    helper.assertTrue(KineticHazards.lastFlingMps(pigs[3]) > 1.5 * KineticHazards.lastFlingMps(pigs[2]),
                            "a large cogwheel at 256 RPM didn't throw harder than a small one: " + out);
                    for (Pig pig : pigs) pig.discard();
                })
                .thenSucceed();
    }

    /**
     * A pig a large cogwheel at 256 RPM flings into a stone wall 1.65 blocks away is hurt by the wall (Full Stop's impact),
     * and by the gear no more than gearMaxDamage. It touches the side where the teeth run down: they never throw it down
     * into the floor (no slam), they squeeze it out along it.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 200)
    public static void aPigFlungIntoAWallIsHurtByTheWall(GameTestHelper helper) {
        largeGearRig(helper, 4, 6);
        // walls 3.5 blocks out from the axle on both sides (which side the teeth run down on is the network's)
        for (int x = 3; x <= 7; x++) {
            for (int y = 2; y <= 7; y++) {
                helper.setBlock(new BlockPos(x, y, 10), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE.defaultBlockState());
            }
        }
        Pig[] pigs = new Pig[1];
        java.util.Map<String, Double>[] hurts = new java.util.Map[]{new java.util.TreeMap<>()};
        listenToHurts(pigs, hurts);
        double[] biggest = new double[1];
        listenToBites(pigs, biggest);
        int[] side = {0};
        helper.startSequence()
                .thenExecuteAfter(5, () -> motor(helper, new BlockPos(4, 3, 6), 256))
                .thenExecuteAfter(10, () -> {
                    Hazard.Live live = HazardScanner.resolveAny(helper.getLevel(), new Hazard(Hazard.Kind.LARGE_COGWHEEL,
                            helper.absolutePos(new BlockPos(5, 3, 6)), Hazard.NO_SHIP));
                    helper.assertTrue(live != null && live.driveRpm() != 0.0, "the large cogwheel doesn't turn");
                    // the side where the teeth run down: about +x a positive speed runs them down on the +z side
                    side[0] = live.driveRpm() > 0 ? 1 : -1;
                    pigs[0] = pig(helper, new Vec3(5.5, 2.0, 6.5 + side[0] * 1.4));
                })
                .thenExecuteAfter(30, () -> {
                    double flung = KineticHazards.lastFlingMps(pigs[0]);
                    double wall = hurts[0].entrySet().stream().filter(e -> !e.getKey().contains("gear")).mapToDouble(java.util.Map.Entry::getValue).sum();
                    report("flung into a wall by a large cogwheel at 256 RPM", String.format(Locale.ROOT, "flung at %.1f m/s toward %s z, hurt %s",
                            flung, side[0] > 0 ? "+" : "-", hurts[0]));
                    helper.assertTrue(flung > 15.0, "the large cogwheel didn't fling the pig (" + flung + " m/s)");
                    helper.assertTrue(biggest[0] <= FullStopConfig.SERVER.gearMaxDamage.get() + 1.0E-3,
                            "the gear bit the pig more than gearMaxDamage (" + biggest[0] + "): " + hurts[0]);
                    // the wall's hit comes within the bite's damage cooldown: vanilla takes only what it adds over the bite
                    double total = hurts[0].values().stream().mapToDouble(Double::doubleValue).sum();
                    helper.assertTrue(total > 0.0, "the pig flung into the wall wasn't hurt: " + hurts[0]);
                    pigs[0].discard();
                })
                .thenSucceed();
    }

    /** Records the largest single gear bite (a hurt whose message id names a gear) each of {@code pigs} took into {@code biggest}. */
    private static void listenToBites(Pig[] pigs, double[] biggest) {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.living.LivingHurtEvent e) -> {
            if (!e.getSource().getMsgId().contains("gear")) return;
            for (int i = 0; i < pigs.length; i++) {
                if (pigs[i] != null && e.getEntity() == pigs[i]) biggest[i] = Math.max(biggest[i], e.getAmount());
            }
        });
    }

    /** Records every hurt of {@code pigs} by damage type (message id) into {@code hurts}. */
    private static void listenToHurts(Pig[] pigs, java.util.Map<String, Double>[] hurts) {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.living.LivingHurtEvent e) -> {
            for (int i = 0; i < pigs.length; i++) {
                if (pigs[i] != null && e.getEntity() == pigs[i]) hurts[i].merge(e.getSource().getMsgId(), (double) e.getAmount(), Double::sum);
            }
        });
    }

    /** A pig 0.4 blocks from a single cogwheel's teeth (which the old catch pulled in) stays where it is. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 300)
    public static void aSingleCogwheelDoesntPullFromAfar(GameTestHelper helper) {
        gearRig(helper, 4, 6, 64, false);
        Pig[] pig = new Pig[1];
        double[] start = new double[1];
        helper.startSequence()
                .thenExecuteAfter(5, () -> motor(helper, new BlockPos(4, 2, 6), 64))
                .thenExecuteAfter(10, () -> {
                    pig[0] = pig(helper, new Vec3(5.5, 2.0, 7.45 + 0.4));
                    pig[0].addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 9, false, false));
                    start[0] = gap(helper, pig[0], 5);
                })
                .thenExecuteAfter(60, () -> {
                    double gap = gap(helper, pig[0], 5);
                    report("0.4 from a single 64 RPM cogwheel", String.format(Locale.ROOT, "gap %.2f -> %.2f, %.1f lost", start[0], gap, lost(pig[0])));
                    helper.assertTrue(gap > start[0] - 0.1 && lost(pig[0]) == 0.0, String.format(Locale.ROOT,
                            "a pig %.2f from a single cogwheel's teeth was pulled in (gap now %.2f) or hurt", start[0], gap));
                    pig[0].discard();
                })
                .thenSucceed();
    }

    /**
     * Two meshing cogwheels at 16, 64 and 256 RPM, a pig resting in the V of their in-running side: drawn into the nip
     * and crushed, harder the faster they turn; a creative motor never overstresses.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 400)
    public static void aNipCrushesHarderTheFasterItTurns(GameTestHelper helper) {
        int[] rpms = {16, 64, 256};
        Pig[] pigs = new Pig[rpms.length];
        double[] last = new double[rpms.length];
        double[] bite = new double[rpms.length];
        boolean[] overstressed = new boolean[rpms.length];
        for (int i = 0; i < rpms.length; i++) gearRig(helper, 4 + i * 12, 6, rpms[i], false);
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    for (int i = 0; i < rpms.length; i++) motor(helper, new BlockPos(4 + i * 12, 2, 6), rpms[i]);
                })
                .thenExecuteAfter(10, () -> {
                    for (int i = 0; i < rpms.length; i++) partnerOnTheMouthUp(helper, 4 + i * 12 + 1, 2, 6);
                })
                .thenExecuteAfter(10, () -> {
                    for (int i = 0; i < rpms.length; i++) {
                        pigs[i] = pigInTheV(helper, 4 + i * 12 + 1, 2, 6);
                        last[i] = pigs[i].getHealth();
                    }
                })
                .thenExecuteFor(100, () -> {
                    for (int i = 0; i < rpms.length; i++) {
                        double h = pigs[i].getHealth();
                        bite[i] = Math.max(bite[i], last[i] - h);
                        last[i] = h;
                        overstressed[i] |= HazardScanner.overstressed(helper.getLevel(), cog(helper, 4 + i * 12 + 1, 2, 6));
                    }
                })
                .thenExecute(() -> {
                    double[] lost = new double[rpms.length];
                    for (int i = 0; i < rpms.length; i++) lost[i] = lost(pigs[i]);
                    report("crushed in a nip over 5 s", String.format(Locale.ROOT,
                            "16 RPM %.1f lost (largest bite %.2f), 64 RPM %.1f (%.2f), 256 RPM %.1f (%.2f); a creative motor overstressed: %s",
                            lost[0], bite[0], lost[1], bite[1], lost[2], bite[2], java.util.Arrays.toString(overstressed)));
                    for (int i = 0; i < rpms.length; i++) {
                        helper.assertTrue(bite[i] > 0.0, "a pig in the nip of two " + rpms[i] + " RPM cogwheels wasn't crushed");
                        helper.assertTrue(!overstressed[i], "a pig jammed a creative motor's " + rpms[i] + " RPM gears");
                    }
                    helper.assertTrue(bite[1] > bite[0] * 1.5, String.format(Locale.ROOT,
                            "a 64 RPM bite (%.2f) isn't clearly harder than a 16 RPM one (%.2f)", bite[1], bite[0]));
                    helper.assertTrue(bite[2] > bite[1] * 1.5, String.format(Locale.ROOT,
                            "a 256 RPM bite (%.2f) isn't clearly harder than a 64 RPM one (%.2f)", bite[2], bite[1]));
                    for (Pig pig : pigs) pig.discard();
                })
                .thenSucceed();
    }

    /**
     * The same pair turned the other way: on top is now the out-running side, which pushes out and never catches (no
     * jam, no crushing): at most the touch of a single gear's teeth (about 1 a bite at 64 RPM, against the nip's 3).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 300)
    public static void theOutRunningSideOfANipNeverCatches(GameTestHelper helper) {
        gearRig(helper, 4, 6, 64, false);
        Pig[] pig = new Pig[1];
        int[] jammed = {0};
        double[] biggest = new double[1];
        listenToBites(pig, biggest);
        helper.startSequence()
                .thenExecuteAfter(5, () -> motor(helper, new BlockPos(4, 2, 6), 64))
                .thenExecuteAfter(10, () -> partnerOnTheMouthUp(helper, 5, 2, 6))
                .thenExecuteAfter(5, () -> motor(helper, new BlockPos(4, 2, 6), -64))
                .thenExecuteAfter(10, () -> pig[0] = pigInTheV(helper, 5, 2, 6))
                .thenExecuteFor(100, () -> {
                    if (pairImpact(helper, 5, 2, 6) > 0.0) jammed[0]++;
                })
                .thenExecute(() -> {
                    report("on the out-running side of a 64 RPM nip", String.format(Locale.ROOT, "%.1f lost (largest bite %.2f), jammed %d ticks",
                            lost(pig[0]), biggest[0], jammed[0]));
                    helper.assertTrue(biggest[0] < 1.5 && jammed[0] == 0, "a pig on the out-running side of a nip was caught: "
                            + lost(pig[0]) + " lost, largest bite " + biggest[0] + ", jammed " + jammed[0] + " ticks");
                    pig[0].discard();
                })
                .thenSucceed();
    }

    /**
     * Two cogwheels turned by a water wheel (8 RPM, a lone wheel's 256 SU): a pig (150 kg) caught in their nip loads
     * them past what the wheel gives, so the network overstresses and the gears stop, the pig wedged; when it's gone
     * the load goes and they turn again.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 500)
    public static void aPigInTheNipJamsAWeakNetwork(GameTestHelper helper) {
        wheelRig(helper, 4, true);
        Pig[] pig = new Pig[1];
        double[] impact = new double[1];
        float[] lostWhileJammed = new float[2];
        helper.startSequence()
                .thenExecuteAfter(40, () -> {
                    helper.setBlock(new BlockPos(6, 3, 6), AllBlocks.COGWHEEL.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
                })
                .thenExecuteAfter(10, () -> partnerOnTheMouthUp(helper, 6, 3, 6))
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(!HazardScanner.overstressed(helper.getLevel(), cog(helper, 6, 3, 6)), "the wheel's gears are overstressed already");
                    pig[0] = pigInTheV(helper, 6, 3, 6);
                })
                .thenWaitUntil(() -> helper.assertTrue(HazardScanner.overstressed(helper.getLevel(), cog(helper, 6, 3, 6)),
                        "the pig in the nip doesn't jam the water wheel's gears (load " + pairImpact(helper, 6, 3, 6) + " SU/RPM)"))
                .thenExecute(() -> {
                    impact[0] = pairImpact(helper, 6, 3, 6);
                    lostWhileJammed[0] = pig[0].getHealth();
                })
                .thenExecuteFor(60, () -> helper.assertTrue(HazardScanner.overstressed(helper.getLevel(), cog(helper, 6, 3, 6)),
                        "the jammed gears turned again with the pig still in them"))
                .thenExecute(() -> {
                    lostWhileJammed[1] = lostWhileJammed[0] - pig[0].getHealth();
                    pig[0].discard();
                })
                .thenWaitUntil(() -> helper.assertTrue(!HazardScanner.overstressed(helper.getLevel(), cog(helper, 6, 3, 6))
                        && pairImpact(helper, 6, 3, 6) == 0.0, "the gears stay jammed with the pig gone"))
                .thenExecute(() -> report("a pig in the nip of a water wheel's gears", String.format(Locale.ROOT,
                        "jammed: %.1f SU/RPM on the gears, overstressed, %.1f lost in 3 s while jammed; the pig gone, they turn again",
                        impact[0], lostWhileJammed[1])))
                .thenSucceed();
    }

    /**
     * Create's meshing rule and the nip's geometry, pure math: two cogwheels side by side on one axis mesh at the
     * midpoint, a large and a small one diagonally mesh two thirds of the way from the large one; two cogwheels along
     * their axis, or two large ones side by side, don't; a box in the in-running mouth is caught, the same box on the
     * out-running side or a block further out isn't.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 20)
    public static void gearsMeshByCreatesRule(GameTestHelper helper) {
        Hazard.Live a = live(Hazard.Kind.COGWHEEL, new BlockPos(0, 0, 0), 64);
        Hazard.Live b = live(Hazard.Kind.COGWHEEL, new BlockPos(0, 0, 1), -64);
        Hazard.Live alongAxis = live(Hazard.Kind.COGWHEEL, new BlockPos(1, 0, 0), 64);
        Hazard.Live large = live(Hazard.Kind.LARGE_COGWHEEL, new BlockPos(0, 0, 0), 32);
        Hazard.Live diagonal = live(Hazard.Kind.COGWHEEL, new BlockPos(0, 1, 1), -64);
        Hazard.Live large2 = live(Hazard.Kind.LARGE_COGWHEEL, new BlockPos(0, 0, 2), -32);
        helper.assertTrue(KineticGrip.meshes(a, b), "two cogwheels side by side don't mesh");
        helper.assertTrue(!KineticGrip.meshes(a, alongAxis), "two cogwheels along their axis mesh");
        helper.assertTrue(KineticGrip.meshes(large, diagonal), "a large and a small cogwheel diagonally don't mesh");
        helper.assertTrue(!KineticGrip.meshes(large, large2), "two large cogwheels side by side mesh");
        KineticGrip.Nip ab = KineticGrip.nip(a, b);
        helper.assertTrue(ab.mesh().distanceTo(new Vec3(0.5, 0.5, 1.0)) < 1.0E-6, "the small pair meets at " + ab.mesh());
        KineticGrip.Nip ld = KineticGrip.nip(large, diagonal);
        double fromLarge = ld.mesh().distanceTo(large.center());
        helper.assertTrue(Math.abs(fromLarge - Math.sqrt(2.0) * 2.0 / 3.0) < 1.0E-6, "the large pair meets " + fromLarge + " from the large cog");
        // a's teeth (+64 about +x) move down through the mesh at z = 1: the mouth is above it
        helper.assertTrue(ab.along().y < -0.99, "the teeth move " + ab.along() + " through the mesh");
        AABB above = new AABB(0.05, 0.875, 0.55, 0.95, 1.775, 1.45);
        AABB below = new AABB(0.05, -0.775, 0.55, 0.95, 0.125, 1.45);
        AABB higher = above.move(0, 0.5, 0);
        helper.assertTrue(KineticGrip.inMouth(above, ab) != null, "a box resting in the V above the mesh isn't in the mouth");
        helper.assertTrue(KineticGrip.inMouth(below, ab) == null, "a box on the out-running side is in the mouth");
        helper.assertTrue(KineticGrip.inMouth(higher, ab) == null, "a box half a block above the V is in the mouth");
        report("meshing and the nip", "small pair at " + ab.mesh() + ", large pair " + String.format(Locale.ROOT, "%.3f", fromLarge)
                + " from the large cog, teeth through the mesh " + ab.along());
        helper.succeed();
    }

    /**
     * The teeth move the way Create turns the cogwheel (which is the way it draws it, and the way F3+V's arrows
     * point): on each axis and for both directions of the motor, at four points round the rim the teeth's motion
     * is Create's own positive motion there ({@code VecHelper.rotate(radial, +90, axis)}) times the speed's sign.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 200)
    public static void aCogwheelsTeethMoveTheWayCreateTurnsIt(GameTestHelper helper) {
        Direction[] motorFacing = {Direction.EAST, Direction.UP, Direction.SOUTH};
        Direction.Axis[] axes = {Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z};
        BlockPos[] cogs = new BlockPos[3];
        BlockPos[] motors = new BlockPos[3];
        for (int i = 0; i < 3; i++) {
            int x = 4 + i * 8;
            floor(helper, x - 3, x + 3, 30, 36);
            motors[i] = new BlockPos(x, 3, 33);
            cogs[i] = motors[i].relative(motorFacing[i]);
            helper.setBlock(motors[i], AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(DirectionalKineticBlock.FACING, motorFacing[i]));
            helper.setBlock(cogs[i], AllBlocks.COGWHEEL.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, axes[i]));
        }
        int[] rpms = {32, -32};
        StringBuilder out = new StringBuilder();
        var sequence = helper.startSequence();
        for (int rpm : rpms) {
            sequence = sequence.thenExecuteAfter(5, () -> {
                for (BlockPos m : motors) motor(helper, m, rpm);
            }).thenExecuteAfter(10, () -> {
                for (int i = 0; i < 3; i++) {
                    BlockPos cog = helper.absolutePos(cogs[i]);
                    Hazard.Live live = HazardScanner.resolve(helper.getLevel(), new Hazard(Hazard.Kind.COGWHEEL, cog, Hazard.NO_SHIP));
                    helper.assertTrue(live != null, "the cogwheel about " + axes[i] + " doesn't turn at motor " + rpm);
                    for (Direction side : Direction.values()) {
                        if (side.getAxis() == axes[i]) continue;
                        Vec3 radial = Vec3.atLowerCornerOf(side.getNormal());
                        Vec3 expected = net.createmod.catnip.math.VecHelper.rotate(radial, 90, axes[i]).scale(Math.signum(live.rpm()));
                        Vec3 at = Vec3.atCenterOf(cog).add(radial.scale(0.6));
                        KineticGrip.Catch k = KineticGrip.of(new AABB(at, at).inflate(0.02), live);
                        helper.assertTrue(k != null, "no catch at the " + side + " side of the cogwheel about " + axes[i]);
                        helper.assertTrue(k.tangent().dot(expected) > 0.99, "the teeth at the " + side + " side of the cogwheel about "
                                + axes[i] + " move " + k.tangent() + ", Create turns them " + expected + " (" + live.rpm() + " RPM)");
                    }
                    out.append(String.format(Locale.ROOT, "%s at %.0f RPM ok; ", axes[i], live.rpm()));
                }
            });
        }
        sequence.thenExecute(() -> {
            report("cogwheel teeth vs Create's turn", out.toString());
            for (BlockPos m : motors) motor(helper, m, 0);
        }).thenSucceed();
    }

    // ------------------------------------------------------------------ water wheels

    /**
     * The paddles drag the way Create turns the wheel: at each side Create checks for water, the paddles move along
     * Create's own "positive motion" there ({@code VecHelper.rotate(side, +90, axis)}, a right-handed turn, the way
     * its renderers turn a part for a positive speed) times the sign of its speed. Reports the water's flow at each
     * side and the speed it gave.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 200)
    public static void aWaterfallTurnsAWheelTheWayItsPaddlesDrag(GameTestHelper helper) {
        wheelRig(helper, 4, true);
        helper.startSequence()
                .thenExecuteAfter(60, () -> {
                    BlockPos wheel = helper.absolutePos(new BlockPos(5, 3, 6));
                    Hazard.Live live = HazardScanner.resolve(helper.getLevel(), new Hazard(Hazard.Kind.WATER_WHEEL, wheel, Hazard.NO_SHIP));
                    helper.assertTrue(live != null, "the waterfall doesn't turn the wheel");
                    StringBuilder flows = new StringBuilder();
                    for (Direction side : Direction.values()) {
                        if (side.getAxis() == Direction.Axis.X) continue;
                        Vec3 normal = Vec3.atLowerCornerOf(side.getNormal());
                        Vec3 flow = helper.getLevel().getFluidState(wheel.relative(side)).getFlow(helper.getLevel(), wheel.relative(side));
                        Vec3 positive = net.createmod.catnip.math.VecHelper.rotate(normal, 90, Direction.Axis.X);
                        Vec3 expected = positive.scale(Math.signum(live.rpm()));
                        // a small box against the wheel's side, level with its axle
                        Vec3 at = Vec3.atCenterOf(wheel).add(normal.scale(0.95));
                        KineticGrip.Catch k = KineticGrip.of(new AABB(at, at).inflate(0.05), live);
                        flows.append(String.format(Locale.ROOT, "%s: water (%.2f, %.2f, %.2f), Create's positive motion (%.0f, %.0f, %.0f), paddles (%.2f, %.2f, %.2f); ",
                                side, flow.x, flow.y, flow.z, positive.x, positive.y, positive.z, k.tangent().x, k.tangent().y, k.tangent().z));
                        helper.assertTrue(k.tangent().dot(expected) > 0.9, "the paddles on the " + side + " side move (" + k.tangent()
                                + "), not the way Create turns the wheel there (" + expected + ") at " + live.rpm() + " RPM");
                    }
                    report("waterfall wheel", String.format(Locale.ROOT, "%.1f RPM; %s", live.rpm(), flows));
                })
                .thenSucceed();
    }

    /**
     * A water wheel a motor turns at 8 RPM over a one-deep basin: a pig in the open water beside it on
     * the downstroke is pinned under the paddles, its air runs out and it takes drowning damage; the
     * same pig beside the same wheel at rest floats up and breathes.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 700)
    public static void aTurningWaterWheelHoldsAPigUnderUntilItDrowns(GameTestHelper helper) {
        drivenWheelRig(helper, 4);
        drivenWheelRig(helper, 20);
        Pig[] pigs = new Pig[2];
        int[] minAir = {Integer.MAX_VALUE, Integer.MAX_VALUE};
        // every hurt of the two pigs, by damage type
        java.util.Map<String, Double>[] hurts = new java.util.Map[]{new java.util.TreeMap<>(), new java.util.TreeMap<>()};
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.living.LivingHurtEvent e) -> {
            for (int i = 0; i < 2; i++) {
                if (pigs[i] != null && e.getEntity() == pigs[i]) hurts[i].merge(e.getSource().getMsgId(), (double) e.getAmount(), Double::sum);
            }
        });
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    motor(helper, new BlockPos(4, 3, 6), 8);
                    motor(helper, new BlockPos(20, 3, 6), 0);
                })
                .thenExecuteAfter(20, () -> {
                    KineticBlockEntity turning = (KineticBlockEntity) helper.getBlockEntity(new BlockPos(5, 3, 6));
                    helper.assertTrue(turning.getSpeed() != 0.0F, "the motor doesn't turn the water wheel");
                    // the downstroke side: Create's positive speed is a right-handed turn about +x, so +8 RPM brings the south side down
                    double side = turning.getSpeed() > 0 ? 7.5 : 5.5;
                    report("driven water wheel", String.format(Locale.ROOT, "%.1f RPM, the pig on the %s side",
                            turning.getSpeed(), side < 6 ? "north" : "south"));
                    pigs[0] = pig(helper, new Vec3(5.5, 2.0, side));
                    pigs[1] = pig(helper, new Vec3(21.5, 2.0, side));
                })
                .thenExecuteFor(460, () -> {
                    for (int i = 0; i < 2; i++) {
                        if (pigs[i] != null) minAir[i] = Math.min(minAir[i], pigs[i].getAirSupply());
                    }
                })
                .thenExecute(() -> {
                    report("23 s beside a water wheel", String.format(Locale.ROOT,
                            "turning (8 RPM): air down to %d, hurt %s; at rest: air down to %d, hurt %s",
                            minAir[0], hurts[0], minAir[1], hurts[1]));
                    // drowning held under the wheel is the wheel's own (fullstop.water_wheel_drown, still drowning)
                    double drowned = hurts[0].getOrDefault("fullstop.water_wheel_drown", 0.0) + hurts[0].getOrDefault("drown", 0.0);
                    helper.assertTrue(drowned >= 2.0, String.format(Locale.ROOT,
                            "a pig beside a turning water wheel didn't drown (air down to %d, hurt %s)", minAir[0], hurts[0]));
                    helper.assertTrue(minAir[1] > 0 && !hurts[1].containsKey("drown") && !hurts[1].containsKey("fullstop.water_wheel_drown"), String.format(Locale.ROOT,
                            "a pig beside a water wheel at rest ran out of air (air down to %d, hurt %s)", minAir[1], hurts[1]));
                    for (Pig pig : pigs) pig.discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ cost

    /** 60 pigs among 20 spinning cogwheels, and 60 more far from any: the handler's server time per tick. */
    @GameTest(template = FLAT, batch = BATCH_COST, timeoutTicks = 400)
    public static void manyPigsAmongManyGearsCostLittle(GameTestHelper helper) {
        for (int i = 0; i < 20; i++) gearRig(helper, 2 + (i % 10) * 4, 4 + (i / 10) * 8, 64, false);
        Pig[] pigs = new Pig[120];
        long[] ticks = new long[1];
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    for (int i = 0; i < 20; i++) motor(helper, new BlockPos(2 + (i % 10) * 4, 2, 4 + (i / 10) * 8), 64);
                })
                .thenExecuteAfter(10, () -> {
                    for (int i = 0; i < 60; i++) {
                        int g = i % 20;
                        pigs[i] = pig(helper, new Vec3(2 + (g % 10) * 4 + 1.5, 2.0, 4 + (g / 10) * 8 + 1.0 + (i / 20) * 0.6));
                    }
                    for (int i = 60; i < 120; i++) pigs[i] = pig(helper, new Vec3(2 + (i % 20) * 2 + 0.5, 2.0, 30 + (i / 20)));
                    KineticHazards.timedNanos = 0;
                    KineticHazards.timing = true;
                })
                .thenExecuteFor(100, () -> ticks[0]++)
                .thenExecute(() -> {
                    KineticHazards.timing = false;
                    double msPerTick = KineticHazards.timedNanos / 1.0E6 / Math.max(1, ticks[0]);
                    report("cost", String.format(Locale.ROOT, "120 pigs, 20 cogwheels at 64 RPM: %.3f ms a tick", msPerTick));
                    helper.assertTrue(msPerTick < 2.0, String.format(Locale.ROOT, "the kinetic hazards cost %.3f ms a tick", msPerTick));
                    for (Pig pig : pigs) if (pig != null) pig.discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ rigs

    /** A stone pad, and at (x, 2, z) a creative motor facing east into a cogwheel (or an encased one) turning about x. */
    private static void gearRig(GameTestHelper helper, int x, int z, int rpm, boolean encased) {
        floor(helper, x - 2, x + 3, z - 2, z + 3);
        helper.setBlock(new BlockPos(x, 2, z), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.EAST));
        BlockState cog = encased
                ? AllBlocks.ANDESITE_ENCASED_COGWHEEL.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X)
                : AllBlocks.COGWHEEL.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X);
        helper.setBlock(new BlockPos(x + 1, 2, z), cog);
    }

    /** A stone pad, and at (x, 3, z) a creative motor facing east into a large cogwheel turning about x (its rim clears the pad). */
    private static void largeGearRig(GameTestHelper helper, int x, int z) {
        floor(helper, x - 2, x + 3, z - 3, z + 4);
        helper.setBlock(new BlockPos(x, 3, z), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(DirectionalKineticBlock.FACING, Direction.EAST));
        motor(helper, new BlockPos(x, 3, z), 0);
        helper.setBlock(new BlockPos(x + 1, 3, z), AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
    }

    /**
     * A one-deep basin of still water (y 2) walled in stone, a water wheel turning about x at
     * (x+1, 3, 6) over it, and (when {@code turning}) a waterfall falling past its south side
     * against a stone wall, which turns it.
     */
    private static void wheelRig(GameTestHelper helper, int x, boolean turning) {
        floor(helper, x - 2, x + 4, 2, 10);
        for (int bx = x - 1; bx <= x + 3; bx++) {
            for (int bz = 3; bz <= 9; bz++) {
                boolean wall = bx == x - 1 || bx == x + 3 || bz == 3 || bz == 9;
                helper.setBlock(new BlockPos(bx, 2, bz), wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
        }
        helper.setBlock(new BlockPos(x + 1, 3, 6), AllBlocks.WATER_WHEEL.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.EAST));
        if (turning) {
            // the wall behind the fall, and the source above it
            for (int by = 3; by <= 6; by++) helper.setBlock(new BlockPos(x + 1, by, 8), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x + 1, 6, 7), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x + 1, 5, 7), Blocks.WATER.defaultBlockState());
        }
    }

    /**
     * The same one-deep basin (y 2, open to the sky beside the wheel) and a water wheel turning about x at
     * (x+1, 3, 6) over it, driven through its axle by a creative motor at (x, 3, 6): no waterfall, so the
     * water is still and the wheel turns only as fast as the motor says (a wheel without flow generates
     * nothing and is driven like any shaft).
     */
    private static void drivenWheelRig(GameTestHelper helper, int x) {
        wheelRig(helper, x, false);
        helper.setBlock(new BlockPos(x, 3, 6), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.EAST));
    }

    private static void floor(GameTestHelper helper, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
        }
    }

    private static void motor(GameTestHelper helper, BlockPos pos, int rpm) {
        CreativeMotorBlockEntity motor = (CreativeMotorBlockEntity) helper.getBlockEntity(pos);
        motor.generatedSpeed.setValue(rpm);
    }

    private static Pig pig(GameTestHelper helper, Vec3 relative) {
        Pig pig = helper.spawn(EntityType.PIG, relative);
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
        pig.setHealth(HEALTH);
        pig.setPersistenceRequired();
        return pig;
    }

    /** Distance from the pig's box to the teeth of the cogwheel at (cogX, 2, 6) (axis x). */
    private static double gap(GameTestHelper helper, Pig pig, int cogX) {
        Vec3 c = Vec3.atCenterOf(helper.absolutePos(new BlockPos(cogX, 2, 6)));
        AABB box = pig.getBoundingBox();
        double y = Math.max(box.minY, Math.min(c.y, box.maxY));
        double z = Math.max(box.minZ, Math.min(c.z, box.maxZ));
        return Math.hypot(y - c.y, z - c.z) - Hazard.Kind.COGWHEEL.radius;
    }

    /** The cogwheel at (x, y, z) (relative) as a hazard. */
    private static Hazard cog(GameTestHelper helper, int x, int y, int z) {
        return new Hazard(Hazard.Kind.COGWHEEL, helper.absolutePos(new BlockPos(x, y, z)), Hazard.NO_SHIP);
    }

    /**
     * Places a second cogwheel (axis x) beside the turning one at (x, y, z), north or south of it, on the side that puts
     * their nip's in-running mouth on top (its teeth run down into the mesh there); returns the partner's z.
     */
    private static int partnerOnTheMouthUp(GameTestHelper helper, int x, int y, int z) {
        Hazard.Live a = HazardScanner.resolveAny(helper.getLevel(), cog(helper, x, y, z));
        helper.assertTrue(a != null && a.driveRpm() != 0.0, "the cogwheel at " + x + ", " + y + ", " + z + " isn't driven");
        // a's teeth at the mesh move omega x r: about +x, toward +z they move -y for a positive speed
        int dz = a.driveRpm() > 0 ? 1 : -1;
        helper.setBlock(new BlockPos(x, y, z + dz), AllBlocks.COGWHEEL.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
        PARTNER.put(helper.absolutePos(new BlockPos(x, y, z)), z + dz);
        return z + dz;
    }

    private static final java.util.Map<BlockPos, Integer> PARTNER = new java.util.HashMap<>();

    /** The stress bodies jammed in the nip of the pair at (x, y, z) and its partner put on them (SU per RPM; it's held on either). */
    private static double pairImpact(GameTestHelper helper, int x, int y, int z) {
        int pz = PARTNER.getOrDefault(helper.absolutePos(new BlockPos(x, y, z)), z + 1);
        return HazardScanner.jamImpact(helper.getLevel(), cog(helper, x, y, z)) + HazardScanner.jamImpact(helper.getLevel(), cog(helper, x, y, pz));
    }

    /** A pig dropped into the V on top of the pair at (x, y, z) and its partner (it settles on both gears). */
    private static Pig pigInTheV(GameTestHelper helper, int x, int y, int z) {
        int pz = PARTNER.getOrDefault(helper.absolutePos(new BlockPos(x, y, z)), z + 1);
        double midZ = (z + pz) / 2.0 + 0.5;
        Pig pig = pig(helper, new Vec3(x + 0.5, y + 1.0, midZ));
        pig.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 600, 9, false, false));
        return pig;
    }

    /** A gear at {@code pos} (world-free: the meshing math only), turning about x at {@code rpm}. */
    private static Hazard.Live live(Hazard.Kind kind, BlockPos pos, double rpm) {
        return new Hazard.Live(new Hazard(kind, pos, Hazard.NO_SHIP), Vec3.atCenterOf(pos), new Vec3(1, 0, 0), kind.radius, kind.halfWidth,
                rpm * Math.PI * 2.0 / 60.0 / 20.0, rpm, Vec3.ZERO, rpm, false, Direction.Axis.X);
    }

    private static double lost(Pig pig) {
        return pig == null ? 0.0 : pig.getMaxHealth() - pig.getHealth();
    }

    @BeforeBatch(batch = BATCH_OFF)
    public static void gearsOff(ServerLevel level) {
        FullStopConfig.SERVER.gearHazards.set(false);
    }

    @AfterBatch(batch = BATCH_OFF)
    public static void gearsOn(ServerLevel level) {
        FullStopConfig.SERVER.gearHazards.set(true);
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop][kinetic] {}: {}", what, result);
    }
}
