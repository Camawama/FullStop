package net.camacraft.fullstop.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.mixin.AbstractArrowAccessor;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.camacraft.fullstop.server.physics.interaction.BodyCollisions;
import net.camacraft.fullstop.server.physics.interaction.ProjectileCollisions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bodies and projectiles meeting in mid-air (Entity Collisions: {@link BodyCollisions}; Projectile
 * Collisions: {@link ProjectileCollisions}). Measured before these existed (default config): a pig
 * flung at 30 m/s flew straight through a hovering one (which got vanilla's 1 m/s nudge, nobody hurt),
 * two arrows or two eggs thrown at each other passed through each other untouched; with the old
 * experimental {@code entityCollisionDamage} on, two 10-hp pigs meeting head-on at 15 m/s each both
 * died (each billed from both sides).
 *
 * <p>The bodies float (no gravity, no AI goals) so that a collision is the only thing that changes
 * their motion; each test waits until its lane ticks entities before starting (a lane loaded in the
 * same batch took up to 14 ticks to start ticking, which left pigs hanging where they were put).
 * Velocities are measured from positions each tick; a body moves in the tick after a collision by
 * exactly the velocity it was left with, so the two-body law is checked against real motion.
 *
 * <p>Opt-in with the rest of FullStop's game tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class MidAirCollisionTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_midair";
    private static final int TIMEOUT = 400;
    /** Camafy's weights (config/camafy-weight.toml defaults). */
    private static final double PIG_KG = 150.0;
    private static final double PLAYER_KG = 75.0;
    /** Vanilla's push between two overlapping entities: at most 0.05 blocks a tick (m/s, and a little). */
    private static final double VANILLA_PUSH = 1.05;

    private MidAirCollisionTests() {
    }

    // ------------------------------------------------------------------
    // Bodies
    // ------------------------------------------------------------------

    /**
     * Two floating pigs flung at each other, one at 30 m/s and one at 20 m/s the other way (so the
     * pair carries momentum). They meet, stop each other along the line between them and part at
     * {@code entityCollisionRestitution} times the speed they were closing at: momentum is kept (as
     * measured from their motion in the next tick), neither ends up past the other, and each takes the
     * kinetic damage of the speed it lost (half the closing speed each: same mass), as a wall impact
     * at that speed would deal.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void twoPigsMeetingInMidAirExchangeMomentumByTheTwoBodyLaw(GameTestHelper helper) {
        BlockPos o = lane(helper, 0);
        Scene scene = new Scene(helper);
        whenTicking(helper, o)
                .thenExecute(() -> {
                    scene.a = scene.track(floatingPig(helper.getLevel(), air(o, 0.0, 10.0, 0.0), 30.0));
                    scene.b = scene.track(floatingPig(helper.getLevel(), air(o, 8.0, 10.0, 0.0), -20.0));
                })
                .thenWaitUntil(() -> scene.demandCollided(2))
                .thenExecute(() -> {
                    Exchange x = scene.exchange();
                    report("two floating pigs, 30 m/s and 20 m/s head-on", x.describe());
                    x.demandTwoBodyLaw();
                    x.demandMeasuredMotion();
                    x.demandNeverPassed();
                    x.demandDamageForSpeedLost();
                    helper.assertTrue(x.a.hurt() > 0.0 && x.b.hurt() > 0.0, "a 40 m/s collision hurt neither pig: " + x.describe());
                    scene.discard();
                })
                .thenSucceed();
    }

    /**
     * A floating pig flung at 40 m/s into a hovering one (the case that flew straight through): it
     * stops against it, the hovering one flies off faster than the first follows, they never pass
     * each other, and both are hurt.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aFastPigHitsAHoveringOneInsteadOfPassingThrough(GameTestHelper helper) {
        BlockPos o = lane(helper, 1);
        Scene scene = new Scene(helper);
        whenTicking(helper, o)
                .thenExecute(() -> {
                    scene.a = scene.track(floatingPig(helper.getLevel(), air(o, 0.0, 10.0, 0.0), 40.0));
                    scene.b = scene.track(floatingPig(helper.getLevel(), air(o, 6.0, 10.0, 0.0), 0.0));
                })
                .thenWaitUntil(() -> scene.demandCollided(2))
                .thenExecute(() -> {
                    Exchange x = scene.exchange();
                    report("floating pig at 40 m/s into a hovering pig", x.describe());
                    x.demandTwoBodyLaw();
                    x.demandMeasuredMotion();
                    x.demandNeverPassed();
                    x.demandDamageForSpeedLost();
                    Vec3 va = x.a.velocityAfter(), vb = x.b.velocityAfter();
                    helper.assertTrue(vb.x > va.x + 1.0 && vb.x > 10.0, "the hovering pig wasn't knocked away ahead of the fast one: "
                            + x.describe());
                    helper.assertTrue(x.a.hurt() > 0.0 && x.b.hurt() > 0.0, "the collision hurt neither pig: " + x.describe());
                    scene.discard();
                })
                .thenSucceed();
    }

    /**
     * Two floating pigs drifting into each other at 3 m/s each (6 m/s closing, under
     * entityCollisionMinSpeed): only vanilla's gentle pushing, no collision, nobody hurt.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void slowContactsAreLeftToVanillaPushing(GameTestHelper helper) {
        BlockPos o = lane(helper, 2);
        Scene scene = new Scene(helper);
        long[] started = {-1};
        whenTicking(helper, o)
                .thenExecute(() -> {
                    scene.a = scene.track(floatingPig(helper.getLevel(), air(o, 0.0, 10.0, 0.0), 3.0));
                    scene.b = scene.track(floatingPig(helper.getLevel(), air(o, 2.5, 10.0, 0.0), -3.0));
                    started[0] = helper.getLevel().getGameTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(helper.getLevel().getGameTime() - started[0] >= 40, "waiting"))
                .thenExecute(() -> {
                    String out = "pig a " + scene.a.describe() + " | pig b " + scene.b.describe();
                    report("two floating pigs drifting together at 3 m/s each", out);
                    helper.assertTrue(BodyCollisions.last(scene.a.e) == null && BodyCollisions.last(scene.b.e) == null,
                            "a 6 m/s contact counted as a collision: " + out);
                    helper.assertTrue(scene.a.hurt() == 0.0 && scene.b.hurt() == 0.0, "a slow contact hurt: " + out);
                    scene.discard();
                })
                .thenSucceed();
    }

    /**
     * A pig dropped nine blocks onto a pig standing on the ground: the ground holds the lower one up,
     * so it takes the lander's whole momentum (the speed it "loses" is the closing speed times the
     * lander's mass over its own) and is hurt by it; nothing is pushed, and the lander - mobs aren't
     * solid - falls on past it to the ground.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigLandingOnAStandingPigCrushesIt(GameTestHelper helper) {
        BlockPos o = lane(helper, 3);
        Scene scene = new Scene(helper);
        whenTicking(helper, o)
                .thenExecute(() -> scene.b = scene.track(pig(helper.getLevel(), air(o, 0.0, 0.0, 0.0), false)))
                .thenWaitUntil(() -> helper.assertTrue(scene.b.e.onGround(), "the lower pig isn't standing yet"))
                .thenExecute(() -> scene.a = scene.track(pig(helper.getLevel(), air(o, 0.0, 9.0, 0.0), false)))
                .thenWaitUntil(() -> {
                    scene.demandCollided(1);
                    helper.assertTrue(scene.a.e.onGround(), "the falling pig hasn't landed yet");
                })
                .thenExecute(() -> {
                    BodyCollisions.Record lower = BodyCollisions.last(scene.b.e);
                    BodyCollisions.Record upper = BodyCollisions.last(scene.a.e);
                    String out = String.format(Locale.ROOT, "closing %.2f m/s; lower pig %s, record lost %.2f m/s, damage %.2f, "
                                    + "crushed %s, pushed %s; upper pig %s, pushed %s, ended at y %.2f (ground %d)", lower.closing(),
                            scene.b.describe(), lower.speedLost(), lower.damage(), lower.crushed(), fmt(lower.change()),
                            scene.a.describe(), fmt(upper.change()), scene.a.e.getY(), o.getY());
                    report("a pig dropped nine blocks onto a standing pig", out);
                    helper.assertTrue(lower.crushed() && lower.closing() > 15.0, "not a crush: " + out);
                    helper.assertTrue(Math.abs(lower.speedLost() - lower.closing() * lower.otherKg() / lower.kg()) < 1.0e-6,
                            "the crushed pig's lost speed isn't the closing speed times the lander's mass over its own: " + out);
                    helper.assertTrue(lower.damage() > 0.0 && Math.abs(scene.b.hurt() - lower.damage()) < 0.05,
                            "the crushed pig didn't take its damage: " + out);
                    helper.assertTrue(lower.change().lengthSqr() == 0.0 && upper.change().lengthSqr() == 0.0,
                            "a crush pushed something: " + out);
                    // on the ground, not standing on the other pig's back (0.9 up); a 20 m/s landing may smash the grass
                    helper.assertTrue(scene.a.e.onGround() && scene.a.e.getY() < o.getY() + 0.5,
                            "the lander didn't fall on past the lower pig to the ground: " + out);
                    scene.discard();
                })
                .thenSucceed();
    }

    /**
     * A floating pig flung at 25 m/s into a hovering (fake) player - a player the server simulates,
     * with no client of its own: the player (75 kg) is knocked away with its two-body share of the
     * pig's (150 kg) momentum, given to it as FullStop gives such a player any push (BodyPush), and
     * the pig keeps the rest. (A fake player is never hurt, and a real player's share goes to its
     * client, which a game test can't have.)
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigFlyingIntoAPlayerShovesItByTheTwoBodyLaw(GameTestHelper helper) {
        BlockPos o = lane(helper, 4);
        Scene scene = new Scene(helper);
        FakePlayer[] player = {null};
        whenTicking(helper, o)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    player[0] = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(("fullstop-midair-" + o).getBytes()),
                            "[FS midair]"));
                    Vec3 at = air(o, 6.0, 10.0, 0.0);
                    player[0].moveTo(at.x, at.y - 0.45, at.z, 90.0f, 0.0f);
                    player[0].setDeltaMovement(Vec3.ZERO);
                    level.addFreshEntity(player[0]);
                    scene.b = scene.track(player[0]);
                    scene.a = scene.track(floatingPig(level, air(o, 0.0, 10.0, 0.0), 25.0));
                })
                .thenWaitUntil(() -> scene.demandCollided(1))
                .thenExecute(() -> {
                    BodyCollisions.Record pig = BodyCollisions.last(scene.a.e);
                    BodyCollisions.Record man = BodyCollisions.last(scene.b.e);
                    // (a fake player never moves by itself: its motion as the collision left it, that tick)
                    Vec3 given = scene.b.motion.get(pig.tick());
                    double closing = -(pig.before().subtract(man.before())).dot(pig.normal());
                    double share = (1.0 + pig.restitution()) * closing * PIG_KG / (PIG_KG + PLAYER_KG);
                    String out = String.format(Locale.ROOT, "masses %.1f / %.1f kg, carried closing %.2f m/s, e %.2f: player given (%s) "
                                    + "m/s (its share %.2f), now moving (%s); pig changed by (%s), then moved at (%s) m/s", pig.kg(), man.kg(),
                            closing, pig.restitution(), fmt(man.change()), share, fmt(given), fmt(pig.change()),
                            fmt(scene.a.velocityAt(pig.tick() + 1)));
                    report("a pig at 25 m/s into a hovering player", out);
                    helper.assertTrue(Math.abs(pig.kg() - PIG_KG) < 1.0 && Math.abs(man.kg() - PLAYER_KG) < 1.0, "unexpected masses: " + out);
                    // (vanilla's own nudge as the pig came into it is part of what it carried)
                    helper.assertTrue(Math.abs(man.change().x - share) < 0.05 && given != null
                                    && given.distanceTo(man.before().add(man.change())) < 0.05,
                            "the player wasn't given its two-body share: " + out);
                    helper.assertTrue(Math.abs(pig.kg() * pig.change().x + man.kg() * man.change().x) < 1.0e-3,
                            "momentum wasn't kept: " + out);
                    // (the fake player doesn't move off, so the pig runs back into it: vanilla's nudge again)
                    Vec3 pigAfter = scene.a.velocityAt(pig.tick() + 1);
                    helper.assertTrue(pigAfter != null && Math.abs(pigAfter.x - (pig.before().x + pig.change().x)) < VANILLA_PUSH,
                            "the pig didn't move on with what it was left with: " + out);
                    player[0].discard();
                    scene.discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // Projectiles
    // ------------------------------------------------------------------

    /**
     * Two arrows shot at each other at 60 m/s: they strike each other, bounce back at about a tenth
     * of their speed and tumble down, each landing on its own side of where they met (neither flies on
     * through the other), stuck in the ground like any arrow.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void twoArrowsMeetingHeadOnStopAndFall(GameTestHelper helper) {
        BlockPos o = lane(helper, 5);
        Scene scene = new Scene(helper);
        whenTicking(helper, o)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    scene.a = scene.track(arrow(level, air(o, 0.0, 12.0, 0.0), new Vec3(3.0, 0.0, 0.0), null));
                    scene.b = scene.track(arrow(level, air(o, 12.0, 12.0, 0.0), new Vec3(-3.0, 0.0, 0.0), null));
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(ProjectileCollisions.last(scene.a.e) != null, "the arrows haven't met");
                    helper.assertTrue(inGround(scene.a.e) && inGround(scene.b.e), "the arrows haven't landed");
                })
                .thenExecute(() -> {
                    ProjectileCollisions.Record ra = ProjectileCollisions.last(scene.a.e);
                    ProjectileCollisions.Record rb = ProjectileCollisions.last(scene.b.e);
                    Vec3 va = ra.before().add(ra.change()), vb = rb.before().add(rb.change());
                    double met = scene.a.at(ra.tick()).x + (scene.b.at(ra.tick()).x - scene.a.at(ra.tick()).x) * 0.5;
                    String out = String.format(Locale.ROOT, "closing %.1f m/s, e %.2f; arrow a left at (%s) m/s, landed at x %.2f; "
                                    + "arrow b left at (%s) m/s, landed at x %.2f; met at x %.2f", ra.closing(), ra.restitution(), fmt(va),
                            scene.a.e.getX(), fmt(vb), scene.b.e.getX(), met);
                    report("two arrows head-on at 60 m/s", out);
                    helper.assertTrue(ra.closing() > 100.0, "the arrows didn't meet head-on: " + out);
                    helper.assertTrue(va.x < 0.0 && vb.x > 0.0 && Math.abs(va.x) < 0.15 * 60.0 && Math.abs(vb.x) < 0.15 * 60.0,
                            "the arrows didn't stop and bounce back a little: " + out);
                    helper.assertTrue(Math.abs(ra.kg() * ra.change().x + rb.kg() * rb.change().x) < 1.0e-6, "momentum wasn't kept: " + out);
                    helper.assertTrue(scene.a.e.getX() < met && scene.b.e.getX() > met, "an arrow flew on through the other: " + out);
                    helper.assertTrue(scene.a.e.isAlive() && scene.b.e.isAlive(), "an arrow vanished: " + out);
                    scene.discard();
                })
                .thenSucceed();
    }

    /**
     * Two arrows crossing paths at right angles, meeting at the crossing: a glancing hit. Neither
     * stops; both are turned away from each other and fly on, momentum kept.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void arrowsMeetingAtAnAngleDeflectEachOther(GameTestHelper helper) {
        BlockPos o = lane(helper, 9);
        Scene scene = new Scene(helper);
        whenTicking(helper, o)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    scene.a = scene.track(arrow(level, air(o, 0.0, 12.0, 0.0), new Vec3(3.0, 0.0, 0.0), null));
                    scene.b = scene.track(arrow(level, air(o, 6.0, 12.0, -6.0), new Vec3(0.0, 0.0, 3.0), null));
                })
                .thenWaitUntil(() -> helper.assertTrue(ProjectileCollisions.last(scene.a.e) != null
                        && helper.getLevel().getGameTime() > ProjectileCollisions.last(scene.a.e).tick() + 1, "the arrows haven't met"))
                .thenExecute(() -> {
                    ProjectileCollisions.Record ra = ProjectileCollisions.last(scene.a.e);
                    ProjectileCollisions.Record rb = ProjectileCollisions.last(scene.b.e);
                    Vec3 va = ra.before().add(ra.change()), vb = rb.before().add(rb.change());
                    double turnA = Math.toDegrees(Math.acos(Mth.clamp(ra.before().normalize().dot(va.normalize()), -1.0, 1.0)));
                    double turnB = Math.toDegrees(Math.acos(Mth.clamp(rb.before().normalize().dot(vb.normalize()), -1.0, 1.0)));
                    Vec3 momentum = ra.change().scale(ra.kg()).add(rb.change().scale(rb.kg()));
                    String out = String.format(Locale.ROOT, "normal (%s), closing %.1f m/s; arrow a (%s) -> (%s) m/s, turned %.0f deg; "
                                    + "arrow b (%s) -> (%s) m/s, turned %.0f deg", fmt(ra.normal()), ra.closing(), fmt(ra.before()), fmt(va),
                            turnA, fmt(rb.before()), fmt(vb), turnB);
                    report("two arrows crossing at right angles", out);
                    helper.assertTrue(turnA > 20.0 && turnB > 20.0, "a glancing hit didn't deflect both arrows: " + out);
                    helper.assertTrue(va.length() > 0.3 * ra.before().length() && vb.length() > 0.3 * rb.before().length(),
                            "a glancing hit stopped an arrow: " + out);
                    helper.assertTrue(momentum.length() < 1.0e-6, "momentum wasn't kept: " + out);
                    scene.discard();
                })
                .thenSucceed();
    }

    /** Two eggs thrown at each other break where they meet, in mid-air. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void twoEggsMeetingInTheAirBreak(GameTestHelper helper) {
        BlockPos o = lane(helper, 6);
        Scene scene = new Scene(helper);
        whenTicking(helper, o)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    scene.a = scene.track(egg(level, air(o, 0.0, 12.0, 0.0), new Vec3(1.5, 0.0, 0.0)));
                    scene.b = scene.track(egg(level, air(o, 8.0, 12.0, 0.0), new Vec3(-1.5, 0.0, 0.0)));
                })
                .thenWaitUntil(() -> helper.assertTrue(scene.a.e.isRemoved() && scene.b.e.isRemoved(), "the eggs haven't broken"))
                .thenExecute(() -> {
                    ProjectileCollisions.Record ra = ProjectileCollisions.last(scene.a.e);
                    ProjectileCollisions.Record rb = ProjectileCollisions.last(scene.b.e);
                    double mid = o.getX() + 0.5 + 4.0;
                    String out = String.format(Locale.ROOT, "egg a broke=%s at (%s), egg b broke=%s at (%s); midpoint x %.2f, ground y %d",
                            ra != null && ra.broke(), fmt(scene.a.e.position()), rb != null && rb.broke(), fmt(scene.b.e.position()), mid,
                            o.getY());
                    report("two eggs thrown at each other at 30 m/s", out);
                    helper.assertTrue(ra != null && ra.broke() && rb != null && rb.broke(), "the eggs didn't break on each other: " + out);
                    helper.assertTrue(Math.abs(scene.a.e.getX() - mid) < 0.5 && Math.abs(scene.b.e.getX() - mid) < 0.5
                            && scene.a.e.getY() > o.getY() + 8.0, "the eggs didn't break where they met: " + out);
                })
                .thenSucceed();
    }

    /**
     * An arrow at 60 m/s through an egg thrown at 30 m/s the other way: the egg breaks there, and the
     * arrow flies on, slowed by the egg's momentum (the inelastic two-body share: about 11 m/s left).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void anArrowThroughAnEggBreaksItAndIsSlowed(GameTestHelper helper) {
        BlockPos o = lane(helper, 7);
        Scene scene = new Scene(helper);
        whenTicking(helper, o)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    // weightless, so that they fly level and meet exactly head-on (an arrow falls faster than an egg)
                    scene.a = scene.track(arrow(level, air(o, 0.0, 12.0, 0.0), new Vec3(3.0, 0.0, 0.0), null));
                    scene.b = scene.track(egg(level, air(o, 10.0, 12.0, 0.0), new Vec3(-1.5, 0.0, 0.0)));
                    scene.a.e.setNoGravity(true);
                    scene.b.e.setNoGravity(true);
                })
                .thenWaitUntil(() -> helper.assertTrue(scene.b.e.isRemoved(), "the egg hasn't broken"))
                .thenIdle(2)
                .thenExecute(() -> {
                    ProjectileCollisions.Record arrow = ProjectileCollisions.last(scene.a.e);
                    ProjectileCollisions.Record egg = ProjectileCollisions.last(scene.b.e);
                    Vec3 after = arrow != null ? arrow.before().add(arrow.change()) : Vec3.ZERO;
                    Vec3 moving = scene.a.velocityAt(arrow != null ? arrow.tick() + 1 : 0);
                    double common = arrow != null ? (arrow.kg() * arrow.before().x + arrow.otherKg() * egg.before().x)
                            / (arrow.kg() + arrow.otherKg()) : Double.NaN;
                    String out = String.format(Locale.ROOT, "egg broke=%s; arrow left at (%s) m/s, then moved at (%s) m/s; the pair's "
                                    + "common velocity %.2f m/s", egg != null && egg.broke(), fmt(after), fmt(moving), common);
                    report("an arrow at 60 m/s through an egg at 30 m/s", out);
                    helper.assertTrue(arrow != null && egg != null && egg.broke() && !arrow.broke(), "the egg didn't break on the arrow: " + out);
                    helper.assertTrue(Math.abs(after.x - common) < 0.1 && after.x > 5.0 && after.x < 20.0,
                            "the arrow wasn't slowed by the egg's momentum: " + out);
                    helper.assertTrue(moving != null && Math.abs(moving.x - after.x) < 0.5 && scene.a.e.isAlive(),
                            "the arrow didn't fly on: " + out);
                    scene.discard();
                })
                .thenSucceed();
    }

    /**
     * A multishot-style volley: three arrows of one owner leaving the same spot together, 10 degrees
     * apart. They start overlapping, and must not knock each other about.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aVolleysArrowsDoNotCollideWithEachOther(GameTestHelper helper) {
        BlockPos o = lane(helper, 8);
        Scene scene = new Scene(helper);
        List<Arrow> volley = new ArrayList<>();
        Mob[] owner = {null};
        long[] started = {-1};
        whenTicking(helper, o)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    owner[0] = floatingPig(level, air(o, -2.0, 12.0, 0.0), 0.0);
                    for (int i = -1; i <= 1; i++) {
                        double yaw = Math.toRadians(10.0 * i);
                        Arrow arrow = arrow(level, air(o, 0.0, 12.0, 0.0), new Vec3(3.0 * Math.cos(yaw), 0.0, 3.0 * Math.sin(yaw)), owner[0]);
                        volley.add(arrow);
                        scene.track(arrow);
                    }
                    started[0] = level.getGameTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(helper.getLevel().getGameTime() - started[0] >= 6, "waiting"))
                .thenExecute(() -> {
                    StringBuilder out = new StringBuilder();
                    boolean any = false;
                    for (Arrow arrow : volley) {
                        ProjectileCollisions.Record r = ProjectileCollisions.last(arrow);
                        any |= r != null;
                        out.append(String.format(Locale.ROOT, "arrow moving (%s) m/s%s; ", fmt(arrow.getDeltaMovement().scale(20.0)),
                                r != null ? " COLLIDED" : ""));
                    }
                    report("a volley of three arrows from one owner", out.toString());
                    helper.assertTrue(!any, "a volley's arrows collided with each other: " + out);
                    for (Arrow arrow : volley) {
                        helper.assertTrue(arrow.getDeltaMovement().horizontalDistance() > 2.5, "a volley arrow was slowed: " + out);
                    }
                    owner[0].discard();
                    scene.discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    /** A lane of its own for each test, clear of the other tests' lanes. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        return ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (96 + lane) * 256);
    }

    private static Vec3 air(BlockPos o, double dx, double dy, double dz) {
        return new Vec3(o.getX() + 0.5 + dx, o.getY() + dy, o.getZ() + 0.5 + dz);
    }

    /** A sequence that starts once the lane ticks entities (the test sees nothing move before that). */
    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(helper.getLevel().isPositionEntityTicking(o.above(10))
                && helper.getLevel().isPositionEntityTicking(o.offset(12, 10, 0)), "the lane doesn't tick entities yet"));
    }

    /** A pig with no AI goals (it moves only under its own physics), with or without gravity, at rest. */
    private static Mob pig(ServerLevel level, Vec3 at, boolean floating) {
        Mob pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        pig.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        pig.goalSelector.removeAllGoals(g -> true);
        pig.setNoGravity(floating);
        pig.setPersistenceRequired();
        level.addFreshEntity(pig);
        return pig;
    }

    /** A floating pig flung along +x at {@code vx} m/s. */
    private static Mob floatingPig(ServerLevel level, Vec3 at, double vx) {
        Mob pig = pig(level, at, true);
        pig.setDeltaMovement(vx / 20.0, 0.0, 0.0);
        pig.hasImpulse = true;
        return pig;
    }

    private static Arrow arrow(ServerLevel level, Vec3 at, Vec3 velocityBpt, @Nullable Entity owner) {
        Arrow arrow = new Arrow(level, at.x, at.y, at.z);
        if (owner != null) arrow.setOwner(owner);
        arrow.setDeltaMovement(velocityBpt);
        level.addFreshEntity(arrow);
        return arrow;
    }

    private static ThrownEgg egg(ServerLevel level, Vec3 at, Vec3 velocityBpt) {
        ThrownEgg egg = new ThrownEgg(level, at.x, at.y, at.z);
        egg.setDeltaMovement(velocityBpt);
        level.addFreshEntity(egg);
        return egg;
    }

    private static boolean inGround(Entity arrow) {
        return arrow instanceof AbstractArrow a && ((AbstractArrowAccessor) a).fullstop$inGround();
    }

    /** What a test watches: its bodies' positions by game time, and their health. */
    private static final class Scene {
        final GameTestHelper helper;
        final List<Track> tracks = new ArrayList<>();
        Track a;
        Track b;

        Scene(GameTestHelper helper) {
            this.helper = helper;
            helper.onEachTick(() -> {
                long now = helper.getLevel().getGameTime();
                for (Track track : tracks) track.record(now);
            });
        }

        Track track(Entity entity) {
            Track track = new Track(entity);
            tracks.add(track);
            track.record(entity.level().getGameTime());
            return track;
        }

        /** Both bodies have collided with each other, at least {@code ticks} ticks ago. */
        void demandCollided(int ticks) {
            helper.assertTrue(a != null && b != null, "not started");
            BodyCollisions.Record ra = BodyCollisions.last(a.e);
            BodyCollisions.Record rb = BodyCollisions.last(b.e);
            helper.assertTrue(ra != null && rb != null && ra.other() == b.e && rb.other() == a.e, "they haven't collided");
            helper.assertTrue(helper.getLevel().getGameTime() - ra.tick() >= ticks, "waiting after the collision");
        }

        Exchange exchange() {
            return new Exchange(helper, new Side(a, BodyCollisions.last(a.e)), new Side(b, BodyCollisions.last(b.e)));
        }

        void discard() {
            for (Track track : tracks) track.e.discard();
        }
    }

    private static final class Track {
        final Entity e;
        final float health0;
        final Long2ObjectOpenHashMap<Vec3> at = new Long2ObjectOpenHashMap<>();
        /** Its deltaMovement at the end of each tick (m/s). */
        final Long2ObjectOpenHashMap<Vec3> motion = new Long2ObjectOpenHashMap<>();

        Track(Entity e) {
            this.e = e;
            this.health0 = e instanceof LivingEntity living ? living.getHealth() : 0.0f;
        }

        void record(long time) {
            if (e.isRemoved()) return;
            at.put(time, e.position());
            motion.put(time, e.getDeltaMovement().scale(20.0));
        }

        @Nullable
        Vec3 at(long time) {
            return at.get(time);
        }

        /** How fast it moved during tick {@code time} (m/s), from where it was at the end of it and the one before. */
        @Nullable
        Vec3 velocityAt(long time) {
            Vec3 now = at.get(time), then = at.get(time - 1);
            return now == null || then == null ? null : now.subtract(then).scale(20.0);
        }

        double hurt() {
            float now = e instanceof LivingEntity living ? (living.isAlive() ? living.getHealth() : 0.0f) : 0.0f;
            return health0 - now;
        }

        String describe() {
            return String.format(Locale.ROOT, "%s, hurt %.2f", e.getName().getString(), hurt());
        }
    }

    /** One body's side of a measured collision. */
    private record Side(Track track, BodyCollisions.Record r) {
        /** What it was left moving with (m/s): what it carried plus its change. */
        Vec3 after() {
            return r.before().add(r.change());
        }

        /** How it actually moved in the tick after the collision (m/s). */
        Vec3 velocityAfter() {
            Vec3 v = track.velocityAt(r.tick() + 1);
            return v != null ? v : new Vec3(Double.NaN, Double.NaN, Double.NaN);
        }

        double hurt() {
            return track.hurt();
        }
    }

    /** Two bodies' collision with each other, and the checks on it. */
    private record Exchange(GameTestHelper helper, Side a, Side b) {
        /** How fast they were still closing along the normal in the velocities they carried on with (m/s). */
        double carriedClosing() {
            return -a.r.before().subtract(b.r.before()).dot(a.r.normal());
        }

        String describe() {
            return String.format(Locale.ROOT, "tick %d, normal (%s), met closing at %.2f m/s, still closing at %.2f, e %.2f, masses "
                            + "%.1f / %.1f kg | a carried (%s) + (%s), moved on at (%s), lost %.2f m/s, dealt %.2f, %s | b carried (%s) + "
                            + "(%s), moved on at (%s), lost %.2f m/s, dealt %.2f, %s", a.r.tick(), fmt(a.r.normal()), a.r.closing(),
                    carriedClosing(), a.r.restitution(), a.r.kg(), b.r.kg(), fmt(a.r.before()), fmt(a.r.change()),
                    fmt(a.velocityAfter()), a.r.speedLost(), a.r.damage(), a.track.describe(), fmt(b.r.before()), fmt(b.r.change()),
                    fmt(b.velocityAfter()), b.r.speedLost(), b.r.damage(), b.track.describe());
        }

        /** As dealt: momentum kept, and they part at e times the speed they were still closing at. */
        void demandTwoBodyLaw() {
            Vec3 before = a.r.before().scale(a.r.kg()).add(b.r.before().scale(b.r.kg()));
            Vec3 after = a.after().scale(a.r.kg()).add(b.after().scale(b.r.kg()));
            helper.assertTrue(after.distanceTo(before) < 1.0e-3 * Math.max(1.0, before.length()), "momentum wasn't kept: " + describe());
            double parting = a.after().subtract(b.after()).dot(a.r.normal());
            helper.assertTrue(Math.abs(parting - a.r.restitution() * carriedClosing()) < 1.0e-3,
                    "they didn't part at e times the closing speed: " + describe());
        }

        /**
         * As measured: in the next tick each moved by what it was left with, and momentum held. Give or
         * take vanilla's own push (at most 0.05 blocks a tick each way): entities tick one after another,
         * so the one behind can move into the other before that one moves off, and vanilla nudges both.
         */
        void demandMeasuredMotion() {
            Vec3 va = a.velocityAfter(), vb = b.velocityAfter();
            helper.assertTrue(va.distanceTo(a.after()) < VANILLA_PUSH && vb.distanceTo(b.after()) < VANILLA_PUSH,
                    "they didn't move on as the collision left them: " + describe());
            // (a nudge shows in the next tick's motion of whichever of the two moves after it, the other's a tick later)
            Vec3 before = a.r.before().scale(a.r.kg()).add(b.r.before().scale(b.r.kg()));
            Vec3 measured = va.scale(a.r.kg()).add(vb.scale(b.r.kg()));
            helper.assertTrue(measured.distanceTo(before) < 0.01 * before.length() + VANILLA_PUSH * Math.max(a.r.kg(), b.r.kg()),
                    "their measured momentum changed: " + describe());
            double parting = va.subtract(vb).dot(a.r.normal());
            helper.assertTrue(Math.abs(parting - a.r.restitution() * carriedClosing()) < 2.0 * VANILLA_PUSH,
                    "they didn't measurably part at e times the closing speed: " + describe());
        }

        /** Body a started on the -x side of b and stayed there, every tick. */
        void demandNeverPassed() {
            for (var entry : a.track.at.long2ObjectEntrySet()) {
                Vec3 other = b.track.at(entry.getLongKey());
                if (other != null) {
                    helper.assertTrue(entry.getValue().x < other.x, "they passed through each other at tick " + entry.getLongKey()
                            + ": " + describe());
                }
            }
        }

        /** Each was dealt the kinetic damage of the speed it lost (half the closing speed each for equal masses), and took it. */
        void demandDamageForSpeedLost() {
            for (Side side : new Side[]{a, b}) {
                double lost = a.r.closing() * a.r.kg() * b.r.kg() / (a.r.kg() + b.r.kg()) / side.r.kg();
                helper.assertTrue(Math.abs(side.r.speedLost() - lost) < 1.0e-6, "the speed lost isn't the two-body share: " + describe());
                double expected = KineticDamageCalculator.damageForSpeedLost(side.track.e, lost, false, List.of())
                        * FullStopConfig.SERVER.entityCollisionDamageMultiplier.get();
                helper.assertTrue(Math.abs(side.r.damage() - (expected >= 1.0 ? expected : 0.0)) < 1.0e-6,
                        "the damage isn't a wall impact's at the speed lost (" + String.format(Locale.ROOT, "%.2f", expected) + "): "
                                + describe());
                helper.assertTrue(Math.abs(side.hurt() - side.r.damage()) < 0.05, "the damage wasn't taken: " + describe());
            }
        }
    }

    private static void report(String what, String detail) {
        LOGGER.info("[FullStop collision test] {}: {}", what, detail);
    }

    private static String fmt(@Nullable Vec3 v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }
}
