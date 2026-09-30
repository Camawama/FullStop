package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.server.physics.ship.ShipDeckMomentum;
import net.camacraft.fullstop.server.physics.ship.ShipEntityImpacts;
import net.camacraft.fullstop.server.physics.ship.ShipTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Set;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fmt;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * Spinning ships (what a Clockwork rotor or creative motor does), with real ships (the {@link Rig}
 * of {@link ShipCollisionTests}; same opt-in: {@code gradlew runGameTestServer -PshipTests}), and
 * ships breaking each other's blocks.
 *
 * <p><b>Riders.</b> Cows standing on a floating raft that spins steadily about its middle, at 24
 * and at 60 RPM, at three distances from the axis: VS carries them round with the deck, and so
 * must FullStop (no deck-momentum throw, no drift on the deck). Before the fix the steady turn of
 * the deck's velocity (its centripetal acceleration) counted as a jolt against the rider's 1 g
 * grip, and every rider more than 1.5 blocks out at 24 RPM was thrown every physics frame.
 *
 * <p><b>Rotors.</b> A plus-shaped rotor spun at 256 RPM with villagers 20, 8 and 3 blocks outside
 * the circle its tips sweep: none may be touched (the hit check used to take the hull's velocity
 * at the ENTITY, hundreds of m/s twenty blocks out, and swept that across the gap); one placed
 * inside the circle must be hit. A paddle at 24 RPM sweeping into a cow on the ground must fling
 * it clear, not shove it round along the ground.
 *
 * <p><b>Ship against ship.</b> An obsidian block rammed into a plank raft breaks planks and never
 * obsidian; two plank ships bumping break nothing.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipSpinTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    /** The deck-momentum batch of {@link ShipCollisionTests}: ship weight is off for it, so riders don't tip the raft. */
    private static final String DECK_BATCH = "fullstop_deck_momentum";
    private static final String BATCH = "fullstop_ship_spin";
    private static final int TIMEOUT = 2400;
    /** rad/s per RPM. */
    private static final double RPM = Math.PI / 30.0;

    private ShipSpinTests() {
    }

    /** A lane of its own for each test, 256 blocks apart, 40 blocks around it force-loaded. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (40 + lane) * 256);
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
    // Riders on a spinning deck
    // ------------------------------------------------------------------

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = DECK_BATCH)
    public static void aDeckSpinningAt24RpmKeepsItsRiders(GameTestHelper helper) {
        spinningDeck(helper, lane(helper, 0), 24.0, "raft spinning steadily at 24 RPM, cows 1.0, 2.5 and 3.8 blocks out");
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = DECK_BATCH)
    public static void aDeckSpinningAt60RpmKeepsItsRiders(GameTestHelper helper) {
        spinningDeck(helper, lane(helper, 1), 60.0, "raft spinning steadily at 60 RPM, cows 1.0, 2.5 and 3.8 blocks out");
    }

    /**
     * A floating 9x1x9 plank raft, three cows on it, spun up about its middle (gently: the spin-up
     * itself stays under the grip everywhere) to {@code rpm} and held there for 200 ticks.
     */
    private static void spinningDeck(GameTestHelper helper, BlockPos o, double rpm, String what) {
        ServerLevel level = helper.getLevel();
        Rig rig = Rig.build(helper, fillSet(level, o.offset(-4, 4, -4), o.offset(4, 4, 4), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Deck deck = new Deck(rig, rpm * RPM);
        helper.onEachTick(rig::record);
        helper.onEachTick(deck::record);
        double cx = o.getX() + 0.5;
        double cz = o.getZ() + 0.5;
        double y = o.getY() + 5.0;
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> deck.riders = new Mob[]{cow(level, cx + 1.0, y, cz), cow(level, cx, y, cz + 2.5),
                        cow(level, cx - 3.8, y, cz)})
                .thenWaitUntil(deck::demandAboard)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(deck::startSpinning)
                .thenWaitUntil(deck::demandAtSpeed)
                .thenExecute(deck::startWatch)
                .thenWaitUntil(() -> helper.assertTrue(deck.watched >= 200, "watching the riders"))
                .thenExecute(() -> {
                    String summary = deck.summary();
                    rig.pin();
                    report(what, summary);
                    deck.demandKept(summary);
                    helper.succeed();
                });
    }

    /** A cow that stands where it is put: AI on (it moves under its own physics), but no goals. */
    private static Mob cow(ServerLevel level, double x, double y, double z) {
        Mob cow = EntityType.COW.create(level);
        if (cow == null) throw new IllegalStateException("no cow");
        cow.moveTo(x, y, z, 0.0f, 0.0f);
        cow.goalSelector.removeAllGoals(g -> true);
        cow.targetSelector.removeAllGoals(g -> true);
        cow.setPersistenceRequired();
        level.addFreshEntity(cow);
        return cow;
    }

    /** The spinning raft and its riders. */
    private static final class Deck {
        final Rig rig;
        final double omega;
        Mob[] riders;
        boolean spinning;
        long spinFrom = -1;
        boolean watching;
        int watched;
        long watchFrom;
        Vec3[] startLocal;
        double[] maxDrift;
        float[] healthBefore;
        /** Per rider, the worst deck change in one frame: the old rule (fixed deck point) and the new (turned with the ship). */
        double[] worstOld;
        double[] worstNew;
        double allowance = Double.NaN;
        int frames;
        double spinAtStart;

        Deck(Rig rig, double omega) {
            this.rig = rig;
            this.omega = omega;
        }

        void startSpinning() {
            rig.hover();
            spinning = true;
            spinFrom = rig.level.getGameTime();
        }

        void record() {
            if (!spinning) return;
            // hold it where it is, and spin it up about y: at most 1.5 rad/s^2 (under 0.6 g of
            // spin-up at the farthest cow)
            rig.cruise(Vec3.ZERO, 5.0);
            rig.spin(omega, 1.5, rig.ref.mass() * (81.0 + 81.0) / 12.0);
            if (!watching) return;
            watched++;
            long now = rig.level.getGameTime();
            ShipTracker.State state = ShipTracker.of(rig.level).get(rig.ship.getId(), now);
            boolean frame = state != null && state.newFrame();
            if (frame) {
                frames++;
                allowance = FullStopConfig.SERVER.deckGrip.get() * Math.min(state.frameSeconds(), 0.25);
            }
            for (int i = 0; i < riders.length; i++) {
                Mob rider = riders[i];
                Vec3 local = rig.ref.toShip(rider.position());
                if (local != null && startLocal[i] != null) {
                    double dx = local.x - startLocal[i].x;
                    double dz = local.z - startLocal[i].z;
                    maxDrift[i] = Math.max(maxDrift[i], Math.sqrt(dx * dx + dz * dz));
                }
                if (frame) {
                    Vec3 at = rider.position();
                    worstOld[i] = Math.max(worstOld[i], state.prevMaterialVelocityAt(at).subtract(state.velocityAt(at)).length());
                    worstNew[i] = Math.max(worstNew[i], state.deckChangeAt(at).length());
                }
            }
        }

        void demandAboard() {
            for (Mob rider : riders) {
                rig.helper.assertTrue(rig.ref.equals(ShipEntities.standingOn(rider, 1)), "a cow isn't aboard yet");
            }
        }

        void demandAtSpeed() {
            Vec3 w = rig.ref.angularVelocity();
            rig.helper.assertTrue(w != null && w.y >= 0.97 * omega, "spinning up: " + (w == null ? "?" : fmt(w.y)) + " of "
                    + fmt(omega) + " rad/s");
        }

        void startWatch() {
            watching = true;
            watchFrom = rig.level.getGameTime();
            Vec3 w = rig.ref.angularVelocity();
            spinAtStart = w == null ? Double.NaN : w.y;
            startLocal = new Vec3[riders.length];
            maxDrift = new double[riders.length];
            worstOld = new double[riders.length];
            worstNew = new double[riders.length];
            healthBefore = new float[riders.length];
            for (int i = 0; i < riders.length; i++) {
                startLocal[i] = rig.ref.toShip(riders[i].position());
                healthBefore[i] = riders[i].getHealth();
            }
        }

        /** Throws any rider got since the spin-up began. */
        int throwsSinceSpin(Mob rider) {
            ShipDeckMomentum.Throw thrown = ShipDeckMomentum.lastThrow(rider);
            return thrown != null && thrown.tick() >= spinFrom ? 1 : 0;
        }

        String summary() {
            Vec3 w = rig.ref.angularVelocity();
            StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                    "spin %.2f rad/s at the start of the watch, %.2f after %d ticks (%d new frames, grip allowance per frame %.2f m/s)",
                    spinAtStart, w == null ? Double.NaN : w.y, watched, frames, allowance));
            double[] radii = {1.0, 2.5, 3.8};
            for (int i = 0; i < riders.length; i++) {
                Mob rider = riders[i];
                out.append(String.format(Locale.ROOT, "; cow %.1f out: thrown %s, drifted %.2f blocks on the deck, aboard %s, "
                                + "hp %.1f -> %.1f, deck change per frame: old rule up to %.2f m/s, new %.2f m/s", radii[i],
                        throwsSinceSpin(rider) > 0 ? "YES" : "no", maxDrift[i], rig.ref.equals(ShipEntities.standingOn(rider, 5)),
                        healthBefore[i], rider.getHealth(), worstOld[i], worstNew[i]));
            }
            return out.toString();
        }

        void demandKept(String summary) {
            for (Mob rider : riders) {
                rig.helper.assertTrue(throwsSinceSpin(rider) == 0, "a rider was thrown off the steadily spinning deck: " + summary);
            }
            for (int i = 0; i < riders.length; i++) {
                rig.helper.assertTrue(maxDrift[i] < 0.5, "a rider slid " + fmt(maxDrift[i]) + " blocks on the deck: " + summary);
                rig.helper.assertTrue(rig.ref.equals(ShipEntities.standingOn(riders[i], 5)), "a rider left the deck: " + summary);
                rig.helper.assertTrue(riders[i].getHealth() >= healthBefore[i], "a rider was hurt: " + summary);
            }
        }
    }

    // ------------------------------------------------------------------
    // Riders seated on a spinning ship feel it
    // ------------------------------------------------------------------

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = DECK_BATCH)
    public static void aSeatOnARaftSpinningAt120RpmBlacksItsRiderOut(GameTestHelper helper) {
        seatedOnSpinningRaft(helper, lane(helper, 6), 120.0, true,
                "cow in a VS seat 3 blocks out on a raft spinning at 120 RPM (474 m/s^2 at the seat)");
    }

    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = DECK_BATCH)
    public static void aSeatOnARaftSpinningAt24RpmIsComfortable(GameTestHelper helper) {
        seatedOnSpinningRaft(helper, lane(helper, 7), 24.0, false,
                "cow in a VS seat 3 blocks out on a raft spinning at 24 RPM (19 m/s^2 at the seat)");
    }

    /**
     * A floating 9x1x9 plank raft with a cow in a Valkyrien Skies seat 3 blocks from its middle,
     * spun up about its middle to {@code rpm} and held there for 120 ticks: the cow's g-force is
     * its seat's centripetal pull, so at 120 RPM it goes past the blackout (blindness and nausea,
     * the server-side g-force effects) and at 24 RPM it feels nothing.
     */
    private static void seatedOnSpinningRaft(GameTestHelper helper, BlockPos o, double rpm, boolean expectEffects, String what) {
        ServerLevel level = helper.getLevel();
        Rig rig = Rig.build(helper, fillSet(level, o.offset(-4, 4, -4), o.offset(4, 4, 4), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        double omega = rpm * RPM;
        Mob[] cow = new Mob[1];
        boolean[] spinning = {false};
        long[] watchFrom = {Long.MAX_VALUE};
        double[] worst = {0.0, 0.0};
        helper.onEachTick(rig::record);
        helper.onEachTick(() -> {
            if (!spinning[0]) return;
            rig.cruise(Vec3.ZERO, 5.0);
            rig.spin(omega, 6.0, rig.ref.mass() * (81.0 + 81.0) / 12.0);
            if (cow[0] != null && level.getGameTime() >= watchFrom[0]) {
                net.camacraft.fullstop.common.capability.FullStopCapability cap =
                        net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability(cow[0]);
                if (cap != null) worst[0] = Math.max(worst[0], cap.getRunningAverageDelta());
                Vec3 w = rig.ref.angularVelocity();
                if (w != null) worst[1] = Math.max(worst[1], w.y);
            }
        });
        double cx = o.getX() + 0.5;
        double cz = o.getZ() + 0.5;
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(() -> cow[0] = seatedCow(helper, level, rig, new Vec3(cx + 3.0, o.getY() + 5.0, cz)))
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(() -> {
                    rig.hover();
                    spinning[0] = true;
                })
                .thenWaitUntil(() -> {
                    Vec3 w = rig.ref.angularVelocity();
                    helper.assertTrue(w != null && w.y >= 0.97 * omega, "spinning up");
                })
                .thenExecute(() -> {
                    cow[0].removeAllEffects();
                    watchFrom[0] = level.getGameTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - watchFrom[0] >= 120, "watching the seated cow"))
                .thenExecute(() -> {
                    boolean blind = cow[0].hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
                    boolean dizzy = cow[0].hasEffect(net.minecraft.world.effect.MobEffects.CONFUSION);
                    boolean seated = cow[0].isPassenger();
                    String out = String.format(Locale.ROOT, "spin %.2f rad/s; the cow's g-force (smoothed, m/s per tick above 1 g; "
                                    + "vignette from 2.5, nausea 4.75, blackout 7.0) peaked at %.2f; blindness %s, nausea %s; still "
                                    + "seated %s", worst[1], worst[0], blind, dizzy, seated);
                    rig.pin();
                    report(what, out);
                    helper.assertTrue(seated, "the cow left its seat: " + out);
                    if (expectEffects) {
                        helper.assertTrue(blind && dizzy && worst[0] >= 7.0, "the seated cow didn't feel the spin: " + out);
                    } else {
                        helper.assertTrue(!blind && !dizzy && worst[0] < 0.5, "a gentle spin gave the seated cow g effects: " + out);
                    }
                    helper.succeed();
                });
    }

    /** A cow (no goals) seated in a Valkyrien Skies seat on the ship at world point {@code at}. */
    private static Mob seatedCow(GameTestHelper helper, ServerLevel level, Rig rig, Vec3 at) {
        Vec3 inShip = rig.ref.toShip(at);
        helper.assertTrue(inShip != null, "the ship isn't resolved");
        org.valkyrienskies.mod.common.entity.ShipMountingEntity seat =
                org.valkyrienskies.mod.common.ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE.create(level);
        helper.assertTrue(seat != null, "no VS seat");
        seat.moveTo(inShip.x, inShip.y, inShip.z, 0.0f, 0.0f);
        level.addFreshEntity(seat);
        Mob cow = cow(level, at.x, at.y, at.z);
        helper.assertTrue(cow.startRiding(seat, true), "the cow wouldn't sit in the VS seat");
        return cow;
    }

    // ------------------------------------------------------------------
    // Rotors: what a spinning hull strikes
    // ------------------------------------------------------------------

    /**
     * A plus-shaped rotor of planks (two 7-block arms crossing, {@code height} blocks tall, lowest
     * layer at {@code y}): spun about the vertical it turns about its largest moment of inertia, a
     * stable spin (a single bar's spin axis wanders - its two largest moments are equal - and a
     * flat paddle's tumbles).
     */
    private static Set<BlockPos> plus(ServerLevel level, BlockPos o, int y, int height) {
        Set<BlockPos> blocks = fillSet(level, o.offset(-3, y, 0), o.offset(3, y + height - 1, 0), Blocks.OAK_PLANKS.defaultBlockState());
        blocks.addAll(fillSet(level, o.offset(0, y, -3), o.offset(0, y + height - 1, 3), Blocks.OAK_PLANKS.defaultBlockState()));
        return blocks;
    }

    /** A plus-shaped rotor's moment of inertia about its axis per kg: (sum of x^2 + z^2 + 1/6) / 13 blocks. */
    private static final double PLUS_INERTIA_PER_KG = (56.0 + 13.0 / 6.0) / 13.0;
    /** ... and about a horizontal axis through its middle (one arm's sum of squares, plus 1/6 a block). */
    private static final double HORIZONTAL_INERTIA_PER_KG = (28.0 + 13.0 / 6.0) / 13.0;

    /**
     * A floating plus-shaped plank rotor four blocks up, spun about its middle to 256 RPM
     * (26.8 rad/s: its tips at 95 m/s). Villagers hovering at its height 20, 8 and 3 blocks
     * outside the circle its tips sweep are watched for 100 ticks: never struck, never hurt (the
     * hit check used to take the hull's velocity at the entity - 640 m/s twenty blocks out - and
     * sweep that across the gap). Then one is put inside the circle, 2 blocks from the axis:
     * struck at once.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aRotorAt256RpmStrikesOnlyWhatItSweeps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 2);
        Rig rig = Rig.build(helper, plus(level, o, 4, 1), Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Rotor rotor = new Rotor(rig, 256.0 * RPM, PLUS_INERTIA_PER_KG);
        helper.onEachTick(rig::record);
        helper.onEachTick(rotor::record);
        double cx = o.getX() + 0.5;
        double cz = o.getZ() + 0.5;
        double y = o.getY() + 3.5; // feet half a block under the rotor: it spans y+4..y+5
        double reach = Math.sqrt(3.5 * 3.5 + 0.5 * 0.5); // the circle the tips sweep
        double half = 0.3; // a villager's half width
        Villager[] outside = new Villager[3];
        Villager[] inside = new Villager[1];
        float[] health = new float[3];
        long[] insideFrom = {-1};
        double[] spinWatched = {Double.POSITIVE_INFINITY, 0.0};
        helper.onEachTick(() -> {
            if (outside[0] == null || inside[0] != null) return;
            double w = rotor.spinNow();
            spinWatched[0] = Math.min(spinWatched[0], w);
            spinWatched[1] = Math.max(spinWatched[1], w);
        });
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(rotor::start)
                .thenWaitUntil(() -> rotor.demandAtSpeed(0.97))
                .thenExecute(() -> {
                    outside[0] = hovering(level, cx, y, cz + reach + 20.0 + half);
                    outside[1] = hovering(level, cx, y, cz - reach - 8.0 - half);
                    outside[2] = hovering(level, cx + reach + 3.0 + half, y, cz);
                    for (int i = 0; i < 3; i++) health[i] = outside[i].getHealth();
                    rotor.watchFrom = level.getGameTime();
                    rotor.watching = true;
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - rotor.watchFrom >= 100, "watching the rotor"))
                .thenExecute(() -> {
                    rotor.watching = false;
                    inside[0] = hovering(level, cx - 2.0, y, cz + 0.8);
                    insideFrom[0] = level.getGameTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(ShipEntityImpacts.lastStruck(inside[0]) != null
                        || level.getGameTime() - insideFrom[0] > 40, "waiting for the rotor to strike the villager inside"))
                .thenExecute(() -> {
                    String[] names = {"20 blocks out", "8 blocks out", "3 blocks out"};
                    StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "spin %.2f to %.2f rad/s while watched (target "
                                    + "%.2f), tips at up to %.1f m/s, axis tipped at most %.1f deg while watched (%.1f in all)", spinWatched[0],
                            spinWatched[1], rotor.omega, spinWatched[1] * reach, rotor.watchTilt, rotor.tilt));
                    boolean clean = true;
                    for (int i = 0; i < 3; i++) {
                        ShipEntityImpacts.Struck struck = ShipEntityImpacts.lastStruck(outside[i]);
                        out.append(String.format(Locale.ROOT, "; villager %s: struck %s, hp %.1f -> %.1f", names[i],
                                describe(struck), health[i], outside[i].getHealth()));
                        clean &= struck == null && outside[i].getHealth() >= health[i];
                    }
                    ShipEntityImpacts.Struck struck = ShipEntityImpacts.lastStruck(inside[0]);
                    out.append("; villager inside the circle, 2.2 blocks from the axis: struck ").append(describe(struck))
                            .append(" after ").append(struck == null ? "-" : String.valueOf(struck.tick() - insideFrom[0]))
                            .append(" ticks, hp now ").append(fmt(inside[0].getHealth()));
                    rig.pin();
                    report("rotor at 256 RPM", out.toString());
                    helper.assertTrue(spinWatched[0] >= 0.85 * rotor.omega, "the rotor slowed to " + fmt(spinWatched[0]) + " rad/s: " + out);
                    helper.assertTrue(clean, "the rotor struck a villager outside the circle its tips sweep: " + out);
                    helper.assertTrue(struck != null && struck.hit(), "the rotor didn't strike the villager inside its circle: " + out);
                    helper.succeed();
                });
    }

    /**
     * A floating plus-shaped plank paddle, two blocks tall with its lowest layer one block above
     * the ground, spinning at 24 RPM; a cow on the ground 2.5 blocks from the axis is in its sweep
     * (the paddle meets its back). The paddle must strike it and fling it clear of the circle it
     * sweeps within two seconds, and never carry it round with it.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aPaddleAt24RpmFlingsWhatItSweepsInto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 3);
        Rig rig = Rig.build(helper, plus(level, o, 1, 2), Blocks.OAK_PLANKS, true);
        if (rig == null) return;
        Rotor rotor = new Rotor(rig, 24.0 * RPM, PLUS_INERTIA_PER_KG);
        helper.onEachTick(rig::record);
        helper.onEachTick(rotor::record);
        double cx = o.getX() + 0.5;
        double cz = o.getZ() + 0.5;
        double reach = Math.sqrt(3.5 * 3.5 + 0.5 * 0.5);
        Mob[] cow = new Mob[1];
        Fling fling = new Fling(cx, cz, reach);
        helper.onEachTick(() -> fling.record(cow[0], level.getGameTime()));
        helper.startSequence()
                .thenWaitUntil(rig::demandReady)
                .thenExecute(rig::release)
                .thenWaitUntil(rig::demandSimulated)
                .thenExecute(rotor::start)
                .thenWaitUntil(() -> rotor.demandAtSpeed(0.97))
                .thenExecute(() -> cow[0] = cow(level, cx + 1.8, o.getY(), cz + 1.8))
                .thenWaitUntil(() -> helper.assertTrue(fling.struckAt >= 0 && level.getGameTime() - fling.struckAt >= 60
                        || fling.watched > 200, "waiting for the paddle to strike the cow, then 60 ticks"))
                .thenExecute(() -> {
                    String out = String.format(Locale.ROOT, "spin %.2f rad/s (axis tipped at most %.1f deg); cow struck %s, pushed (%s) m/s; 40 ticks later "
                                    + "%.2f blocks from the axis (the paddle reaches %.2f), farthest %.2f; carried round %.0f degrees "
                                    + "while in the paddle's reach; hull strikes on it %d (hits %d); hp %.1f", rotor.spinNow(), rotor.tilt,
                            fling.first == null ? "never" : "at t" + fling.first.tick(), fling.first == null ? "-" : vec(fling.first.pushMps()),
                            fling.radiusAfter40, reach, fling.farthest, Math.toDegrees(fling.carried), fling.strikes, fling.hits,
                            cow[0].getHealth());
                    rig.pin();
                    report("paddle at 24 RPM into a cow on the ground", out);
                    helper.assertTrue(fling.first != null, "the paddle never struck the cow: " + out);
                    helper.assertTrue(fling.radiusAfter40 > reach + 0.5, "the cow wasn't flung clear of the paddle: " + out);
                    helper.assertTrue(fling.carried < Math.toRadians(120.0), "the paddle carried the cow round: " + out);
                    helper.succeed();
                });
    }

    /** A villager hovering where it is put (no gravity, no AI: it can't move, so it stays in the rotor's plane). */
    private static Villager hovering(ServerLevel level, double x, double y, double z) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) throw new IllegalStateException("no villager");
        villager.moveTo(x, y, z, 0.0f, 0.0f);
        villager.setNoAi(true);
        villager.setNoGravity(true);
        villager.setPersistenceRequired();
        level.addFreshEntity(villager);
        return villager;
    }

    /** A floating ship spun up about y to {@code omega} and held there, its centre held still. */
    private static final class Rotor {
        final Rig rig;
        final double omega;
        /** Moment of inertia about y per kg of mass (m^2). */
        final double inertiaPerKg;
        boolean spinning;
        long watchFrom = Long.MAX_VALUE;

        Rotor(Rig rig, double omega, double inertiaPerKg) {
            this(rig, omega, inertiaPerKg, HORIZONTAL_INERTIA_PER_KG);
        }

        /** Moment of inertia about a horizontal axis per kg (m^2): what the righting torque works against. */
        final double horizontalInertiaPerKg;

        Rotor(Rig rig, double omega, double inertiaPerKg, double horizontalInertiaPerKg) {
            this.rig = rig;
            this.omega = omega;
            this.inertiaPerKg = inertiaPerKg;
            this.horizontalInertiaPerKg = horizontalInertiaPerKg;
        }

        void start() {
            rig.hover();
            spinning = true;
        }

        /**
         * One tick of the test's motor: the centre held still, and once per physics frame an
         * angular impulse that drives the spin about the vertical toward {@code omega} (at most
         * 60 rad/s^2) and rights the axis (the reaction of a hit, or VS's integration at speed,
         * can tip a free rotor; a real one sits on a bearing).
         */
        void record() {
            if (!spinning) return;
            rig.cruise(Vec3.ZERO, 20.0);
            long now = rig.level.getGameTime();
            ShipTracker.State state = ShipTracker.of(rig.level).get(rig.ship.getId(), now);
            Vec3 w = rig.ref.angularVelocity();
            Vec3 up = rig.ref.dirToWorld(new Vec3(0.0, 1.0, 0.0));
            double mass = rig.ref.mass();
            if (w == null || up == null || state == null || !state.newFrame() || !Double.isFinite(mass)) return;
            // Spin about the vertical toward omega; any spin about a horizontal axis (the axis
            // tipping) is damped out.
            double spinStep = Math.max(-1.0, Math.min(1.0, omega - w.y));
            double across = 0.2 * mass * horizontalInertiaPerKg;
            ShipForces.applyAngularImpulse(rig.level, rig.ship.getId(), new Vec3(-w.x * across, spinStep * mass * inertiaPerKg,
                    -w.z * across));
            double tipped = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, up.y))));
            tilt = Math.max(tilt, tipped);
            if (watching) watchTilt = Math.max(watchTilt, tipped);
        }

        /** The most the axis has tipped so far, and while the watch was on (degrees). */
        double tilt;
        double watchTilt;
        boolean watching;

        double spinNow() {
            Vec3 w = rig.ref.angularVelocity();
            return w == null ? Double.NaN : w.y;
        }

        void demandAtSpeed(double share) {
            rig.helper.assertTrue(spinNow() >= share * omega, "spinning up: " + fmt(spinNow()) + " of " + fmt(omega) + " rad/s");
        }
    }

    /** Follows a cow round a paddle's axis: when struck, how far out it gets, how far round it is carried. */
    private static final class Fling {
        final double cx;
        final double cz;
        final double reach;
        @Nullable
        ShipEntityImpacts.Struck first;
        long struckAt = -1;
        long lastStruckTick = -1;
        int strikes;
        int hits;
        int watched;
        double farthest;
        double radiusAfter40 = Double.NaN;
        double lastAngle = Double.NaN;
        double carried;

        Fling(double cx, double cz, double reach) {
            this.cx = cx;
            this.cz = cz;
            this.reach = reach;
        }

        void record(@Nullable Mob cow, long now) {
            if (cow == null) return;
            watched++;
            ShipEntityImpacts.Struck struck = ShipEntityImpacts.lastStruck(cow);
            if (struck != null && struck.tick() != lastStruckTick) {
                lastStruckTick = struck.tick();
                strikes++;
                if (struck.hit()) hits++;
                if (first == null) {
                    first = struck;
                    struckAt = struck.tick();
                }
            }
            double dx = cow.getX() - cx;
            double dz = cow.getZ() - cz;
            double radius = Math.sqrt(dx * dx + dz * dz);
            double angle = Math.atan2(dz, dx);
            if (struckAt >= 0) {
                farthest = Math.max(farthest, radius);
                if (now - struckAt == 40) radiusAfter40 = radius;
                if (!Double.isNaN(lastAngle) && radius <= reach + 0.5) {
                    double step = angle - lastAngle;
                    while (step > Math.PI) step -= 2.0 * Math.PI;
                    while (step < -Math.PI) step += 2.0 * Math.PI;
                    carried += Math.abs(step);
                }
                lastAngle = angle;
            }
        }
    }

    // ------------------------------------------------------------------
    // Ship against ship
    // ------------------------------------------------------------------

    /**
     * A 3x3x3 obsidian ship (135 t) driven at 20 m/s into a floating 2x4x5 plank raft (20 t):
     * planks break (the weaker block at every contact), obsidian never; both ships slowed.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void anObsidianShipRammedIntoAPlankRaftBreaksPlanks(GameTestHelper helper) {
        ram(helper, lane(helper, 4), Blocks.OBSIDIAN, 20.0, true, "obsidian ship (135 t) at 20 m/s into a floating plank raft (20 t)");
    }

    /** A 3x3x3 plank ship at 6 m/s into the same raft: equal blocks, nothing breaks on either. */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aPlankShipBumpingAPlankRaftBreaksNothing(GameTestHelper helper) {
        ram(helper, lane(helper, 5), Blocks.OAK_PLANKS, 6.0, false, "plank ship (13.5 t) at 6 m/s into a floating plank raft (20 t)");
    }

    private static void ram(GameTestHelper helper, BlockPos o, Block ram, double speed, boolean expectBreak, String what) {
        ServerLevel level = helper.getLevel();
        Set<BlockPos> ramBlocks = fillSet(level, o.offset(-12, 1, -1), o.offset(-10, 3, 1), ram.defaultBlockState());
        Rig striker = Rig.build(helper, ramBlocks, ram, true);
        if (striker == null) return;
        Rig raft = Rig.build(helper, fillSet(level, o.offset(0, 1, -2), o.offset(1, 4, 2), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (raft == null) return;
        helper.onEachTick(striker::record);
        helper.onEachTick(raft::record);
        double[] before = new double[2];
        Vec3[] after = new Vec3[2];
        int[] counts = new int[2];
        boolean[] driving = {false};
        helper.onEachTick(() -> {
            if (driving[0] && striker.firstShipContact < 0) striker.cruise(new Vec3(speed, 0.0, 0.0), 150.0);
            if (driving[0] && striker.firstShipContact >= 0) {
                striker.stopThrust();
                driving[0] = false;
            }
        });
        helper.startSequence()
                .thenWaitUntil(striker::demandReady)
                .thenWaitUntil(raft::demandReady)
                .thenExecute(() -> {
                    counts[0] = striker.count(ram);
                    counts[1] = raft.count(Blocks.OAK_PLANKS);
                    striker.release();
                    raft.release();
                })
                .thenWaitUntil(striker::demandSimulated)
                .thenExecute(() -> {
                    raft.hover();
                    striker.push(new Vec3(speed, 0.0, 0.0));
                    driving[0] = true;
                })
                .thenWaitUntil(() -> {
                    Vec3 v = striker.ref.velocity();
                    if (striker.firstShipContact < 0 && v != null) before[0] = v.x;
                    striker.demandShipHitAndSettled(raft, 40);
                })
                .thenExecute(() -> {
                    after[0] = striker.ref.velocity();
                    after[1] = raft.ref.velocity();
                    int ramLeft = striker.count(ram);
                    int planksLeft = raft.count(Blocks.OAK_PLANKS);
                    striker.pin();
                    raft.pin();
                    String out = String.format(Locale.ROOT, "striker %s %d -> %d, raft planks %d -> %d; striker at %.2f m/s before the "
                                    + "hit, %s after, raft %s after | striker: %s | raft: %s", ram == Blocks.OBSIDIAN ? "obsidian" : "planks",
                            counts[0], ramLeft, counts[1], planksLeft, before[0], vec(after[0]), vec(after[1]), striker.summary(),
                            raft.summary());
                    report(what, out);
                    helper.assertTrue(ramLeft == counts[0], "the striker lost blocks: " + out);
                    if (expectBreak) {
                        helper.assertTrue(planksLeft < counts[1], "the obsidian ship broke no planks: " + out);
                    } else {
                        helper.assertTrue(planksLeft == counts[1], "equal blocks broke: " + out);
                    }
                    helper.succeed();
                });
    }

    // ------------------------------------------------------------------
    // Hulls crossing hulls between frames (ShipHullClash)
    // ------------------------------------------------------------------

    /**
     * A plus-shaped plank rotor of two crossed 3x3x17 arms (symmetric about its axis, which the
     * rig can hold steady; a single arm precesses and climbs) spun to 256 RPM about its middle
     * (its tips at 214 m/s: ten blocks a tick) and a floating 3x3x16 plank arm that drifts into
     * its sweep at 1 m/s until their tips overlap by three blocks. Valkyrien Skies' solver never
     * sees the tips cross between two frames (the owner's two rotors passed through each other, a
     * plank or two lost); the swept clash must break what crosses, on both: the drifting arm its
     * three nearest columns (27 blocks, then maybe the corners of the next), the rotor the outer
     * blocks of the tips that swept them - and nothing further along the drifting arm.
     */
    @GameTest(template = FLAT, timeoutTicks = TIMEOUT, batch = BATCH)
    public static void aPlankArmAt256RpmSweepingThroughAnotherPlankArmBreaksBothTips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 6);
        // (both well clear of the ground: a spinning arm scraping it climbs and tumbles)
        java.util.Set<BlockPos> plusArms = fillSet(level, o.offset(-1, 5, -8), o.offset(1, 7, 8), Blocks.OAK_PLANKS.defaultBlockState());
        plusArms.addAll(fillSet(level, o.offset(-8, 5, -1), o.offset(8, 7, 1), Blocks.OAK_PLANKS.defaultBlockState()));
        Rig spinner = Rig.build(helper, plusArms, Blocks.OAK_PLANKS, true);
        if (spinner == null) return;
        // along x, out of the sweep (radius 8.5): its near end 10 blocks out, its middle 18
        Rig drifter = Rig.build(helper, fillSet(level, o.offset(10, 5, -1), o.offset(25, 7, 1), Blocks.OAK_PLANKS.defaultBlockState()),
                Blocks.OAK_PLANKS, true);
        if (drifter == null) return;
        double omega = 256.0 * RPM;
        double stopAt = o.getX() + 0.5 + 13.0; // the drifter's middle: its tip then reaches three blocks into the sweep
        // two 3x3x17 arms crossed: about the axis (17^2 + 3^2) / 12 per kg, about a horizontal axis about half that
        Rotor rotor = new Rotor(spinner, omega, (17.0 * 17.0 + 3.0 * 3.0) / 12.0, 0.5 * (17.0 * 17.0 + 3.0 * 3.0) / 12.0);
        boolean[] drifting = {false};
        long[] watchFrom = {-1};
        int[] before = new int[2];
        double[] topSpin = {0.0};
        double[] nearest = {Double.POSITIVE_INFINITY};
        helper.onEachTick(spinner::record);
        helper.onEachTick(drifter::record);
        helper.onEachTick(rotor::record);
        helper.onEachTick(() -> {
            if (rotor.spinning) {
                Vec3 w = spinner.ref.angularVelocity();
                if (w != null) topSpin[0] = Math.max(topSpin[0], Math.abs(w.y));
            }
            if (drifting[0]) {
                double x = drifter.x();
                nearest[0] = Math.min(nearest[0], x);
                drifter.cruise(x > stopAt ? new Vec3(-1.0, 0.0, 0.0) : Vec3.ZERO, 5.0);
                // kept level: every spin the rotor's touches give it is damped out, once per physics frame
                ShipTracker.State state = ShipTracker.of(level).get(drifter.ship.getId(), level.getGameTime());
                Vec3 w = drifter.ref.angularVelocity();
                double mass = drifter.ref.mass();
                if (state != null && state.newFrame() && w != null && Double.isFinite(mass)) {
                    ShipForces.applyAngularImpulse(level, drifter.ship.getId(), w.scale(-0.2 * mass * (16.0 * 16.0 + 3.0 * 3.0) / 12.0));
                }
            }
        });
        String what = "plus rotor of 3x3x17 plank arms at 256 RPM, a floating 3x3x16 plank arm drifting into its sweep until their tips overlap by 3";
        helper.startSequence()
                .thenWaitUntil(spinner::demandReady)
                .thenWaitUntil(drifter::demandReady)
                .thenExecute(() -> {
                    before[0] = spinner.count(Blocks.OAK_PLANKS);
                    before[1] = drifter.count(Blocks.OAK_PLANKS);
                    spinner.release();
                    drifter.release();
                })
                .thenWaitUntil(spinner::demandSimulated)
                .thenExecute(() -> {
                    rotor.start();
                    drifter.hover();
                })
                .thenWaitUntil(() -> rotor.demandAtSpeed(0.85))
                .thenExecute(() -> {
                    drifting[0] = true;
                    watchFrom[0] = level.getGameTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - watchFrom[0] >= 260, "the arm drifts in and the tips cross"))
                .thenExecute(() -> {
                    int spinnerLeft = spinner.count(Blocks.OAK_PLANKS);
                    int drifterLeft = drifter.count(Blocks.OAK_PLANKS);
                    spinner.pin();
                    drifter.pin();
                    String out = String.format(Locale.ROOT, "rotor %d -> %d blocks, drifting arm %d -> %d (its middle got to %.1f blocks "
                                    + "from the hub; aimed at %.1f); spin reached %.1f of %.1f rad/s, axis tipped at most %.1f deg | spinner: %s "
                                    + "| drifter: %s", before[0], spinnerLeft, before[1], drifterLeft, nearest[0] - (o.getX() + 0.5),
                            stopAt - (o.getX() + 0.5), topSpin[0], omega, rotor.tilt, spinner.summary(), drifter.summary());
                    report(what, out);
                    helper.assertTrue(drifterLeft < before[1], "the drifting arm lost nothing: " + out);
                    helper.assertTrue(spinnerLeft < before[0], "the spinning arm lost nothing: " + out);
                    helper.assertTrue(before[1] - drifterLeft >= 18, "the drifting arm lost too little (its three nearest columns are 27 blocks): " + out);
                    helper.assertTrue(before[1] - drifterLeft <= 54, "the drifting arm lost blocks the sweep never reached: " + out);
                    helper.assertTrue(before[0] - spinnerLeft >= 9, "the spinning arm lost too little (a tip's outermost column is 9 blocks): " + out);
                    helper.succeed();
                });
    }

    private static String describe(@Nullable ShipEntityImpacts.Struck struck) {
        if (struck == null) return "never";
        return String.format(Locale.ROOT, "%s at t%d by ship %d (approach %.2f m/s, met %.3f s %s, push %s m/s)",
                struck.hit() ? "hit" : "pushed on", struck.tick(), struck.shipId(), struck.approach(), Math.abs(struck.atSeconds()),
                struck.atSeconds() >= 0 ? "ahead" : "back", vec(struck.pushMps()));
    }

    private static String vec(@Nullable Vec3 v) {
        return v == null ? "?" : String.format(Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }
}
