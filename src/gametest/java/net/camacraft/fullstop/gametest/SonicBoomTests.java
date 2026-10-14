package net.camacraft.fullstop.gametest;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.server.physics.SonicBooms;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The sonic boom bookkeeping ({@link SonicBooms}) fed by hand: how many supersonic stretches (each
 * one boom per listener) a source begins as its speed plays about the barrier. Runs with the ship
 * tests ({@code gradlew runGameTestServer -PshipTests}) but needs no ship.
 *
 * <p>The owner's 256 RPM rotor boomed once, then on and off at random: a stretch ended after four
 * ticks under 95% of the barrier, so the tips' speed jitter, a moment's slowing and FullStop's
 * grace after a hard crash each began a new one. Now a stretch ends only after a second under 90%.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class SonicBoomTests {

    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_sonic_booms";

    private SonicBoomTests() {
    }

    /**
     * Over the barrier (1.2x), jittering to 96% of it for ten ticks, back over, dipping to 85% for
     * 19 ticks, back over: one stretch throughout. Then 85% for 21 ticks and back over: a second.
     * (A source that isn't driven: a driven one, a ship, also waits {@code DRIVEN_SUSTAIN_TICKS} and real
     * time before its stretch begins, {@link #aDrivenSourcePastTheBarrierForAMomentBoomsNothing}.)
     */
    @GameTest(template = FLAT, timeoutTicks = 400, batch = BATCH)
    public static void aSourceJitteringAtTheBarrierBoomsOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SonicBooms.Key key = new SonicBooms.Key(true, -4242L - helper.absolutePos(net.minecraft.core.BlockPos.ZERO).asLong() % 1000L, false);
        Vec3 at = helper.absoluteVec(new Vec3(0.5, 3.0, 0.5));
        double s = SonicBooms.SOUND_MPS;
        List<Double> plan = new ArrayList<>();
        List<Integer> checkpoints = new ArrayList<>();
        int[] expected = {1, 1, 1, 2};
        phase(plan, 1.2, 6);
        checkpoints.add(plan.size());   // one stretch begun
        phase(plan, 0.96, 10);           // jitter under the barrier, above 90%: nothing ends
        phase(plan, 1.2, 6);
        checkpoints.add(plan.size());   // still one
        phase(plan, 0.85, 19);           // a deep dip, but short of a second
        phase(plan, 1.2, 6);
        checkpoints.add(plan.size());   // still one
        phase(plan, 0.85, 21);           // a second under 90%: the stretch is over
        phase(plan, 1.2, 6);
        checkpoints.add(plan.size());   // a second stretch
        int[] tick = {0};
        List<Integer> seen = new ArrayList<>();
        helper.onEachTick(() -> {
            if (tick[0] < plan.size()) {
                SonicBooms.observe(level, key, at, new Vec3(plan.get(tick[0]) * s, 0.0, 0.0), p -> true);
            }
            tick[0]++;
            if (checkpoints.contains(tick[0])) seen.add(SonicBooms.stretchesStarted(level, key));
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(seen.size() >= checkpoints.size(), "feeding the source (" + tick[0] + " ticks)"))
                .thenExecute(() -> {
                    String out = "stretches begun at the checkpoints: " + seen + " (wanted 1, 1, 1, 2)";
                    ShipCollisionTests.report("a source at 1.2x the speed of sound jittering to 96%, dipping to 85% for 19 ticks, then for 21", out);
                    for (int i = 0; i < expected.length; i++) {
                        helper.assertTrue(seen.get(i) == expected[i], "checkpoint " + (i + 1) + ": " + out);
                    }
                    helper.succeed();
                });
    }

    /**
     * The owner's false boom (2026-10-12): a rotor on a phys bearing lagged and, catching up, read past the
     * barrier for a few ticks. A driven source (a ship) over the barrier for 6 ticks begins no stretch; a
     * flung body (not driven) does after 2; the ship held over it for 14 ticks and 0.7 s does (the game-test
     * server runs its ticks back to back, so the run is held until the real time has passed too).
     */
    @GameTest(template = FLAT, timeoutTicks = 3000, batch = BATCH)
    public static void aDrivenSourcePastTheBarrierForAMomentBoomsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = -9100L - helper.absolutePos(net.minecraft.core.BlockPos.ZERO).asLong() % 1000L;
        SonicBooms.Key ship = SonicBooms.Key.ship(base);
        SonicBooms.Key body = new SonicBooms.Key(false, base - 1L, false);
        Vec3 at = helper.absoluteVec(new Vec3(0.5, 3.0, 0.5));
        double s = SonicBooms.SOUND_MPS;
        List<Double> plan = new ArrayList<>();
        phase(plan, 0.8, 4);
        phase(plan, 1.3, 6);             // the catch-up spike
        phase(plan, 0.8, 4);
        int afterSpike = plan.size();
        phase(plan, 0.8, 25);           // under 90% for over a second: anything begun is over
        int runFrom = plan.size();      // then really past the barrier (1.2x), 14 ticks and 0.7 s at least
        int[] tick = {0};
        long[] runStart = {0L};
        int[][] seen = new int[2][];
        helper.onEachTick(() -> {
            if (seen[1] != null) return;
            int t = tick[0]++;
            double share;
            if (t < plan.size()) {
                share = plan.get(t);
            } else {
                if (t == runFrom) runStart[0] = System.nanoTime();
                share = 1.2;
            }
            Vec3 v = new Vec3(share * s, 0.0, 0.0);
            SonicBooms.observe(level, ship, at, v, p -> true);
            SonicBooms.observe(level, body, at, v, p -> true);
            if (t + 1 == afterSpike) seen[0] = new int[]{SonicBooms.stretchesStarted(level, ship), SonicBooms.stretchesStarted(level, body)};
            if (t >= runFrom + 13 && System.nanoTime() - runStart[0] >= 700_000_000L) {
                seen[1] = new int[]{SonicBooms.stretchesStarted(level, ship), SonicBooms.stretchesStarted(level, body)};
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(seen[1] != null, "feeding the sources (" + tick[0] + " ticks)"))
                .thenExecute(() -> {
                    String out = "after the 6-tick spike: ship " + seen[0][0] + ", body " + seen[0][1] + " (wanted 0, 1); after 14 ticks over: ship "
                            + seen[1][0] + ", body " + seen[1][1] + " (wanted 1, 2)";
                    ShipCollisionTests.report("a ship and a flung body past the barrier for 6 ticks, then for 14", out);
                    helper.assertTrue(seen[0][0] == 0 && seen[0][1] == 1 && seen[1][0] == 1 && seen[1][1] == 2, out);
                    helper.succeed();
                });
    }

    private static void phase(List<Double> plan, double share, int ticks) {
        for (int i = 0; i < ticks; i++) plan.add(share);
    }
}
