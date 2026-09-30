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
     */
    @GameTest(template = FLAT, timeoutTicks = 400, batch = BATCH)
    public static void aSourceJitteringAtTheBarrierBoomsOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SonicBooms.Key key = SonicBooms.Key.ship(-4242L - helper.absolutePos(net.minecraft.core.BlockPos.ZERO).asLong() % 1000L);
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

    private static void phase(List<Double> plan, double share, int ticks) {
        for (int i = 0; i < ticks; i++) plan.add(share);
    }
}
