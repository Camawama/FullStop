package net.camacraft.fullstop.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.advancement.AdvancementEvents;
import net.camacraft.fullstop.advancement.FullStopTriggers;
import net.camacraft.fullstop.server.physics.SonicBooms;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * FullStop's advancement triggers. Forge never lets a fake player be hurt, die or earn an advancement,
 * so the damage-driven ones are checked by calling the same logic the events call, and every test asks
 * the trigger whether it fired ({@code PlainTrigger.firedFor}) rather than the advancement.
 *
 * <p>Opt-in with the rest of FullStop's game tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class AdvancementTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_advancements";

    private AdvancementTests() {
    }

    /** Kinetic damage earns "Ouch, that hurts!", a ship's hit survived earns "Hit by a ship", dying to one "Flattened". */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 40)
    public static void kineticDamageAShipHitAndAShipKillEarnTheirAdvancements(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = fake(level, "damage");
        FullStopTriggers.OUCH.forget(player);
        FullStopTriggers.HIT_BY_A_SHIP.forget(player);
        FullStopTriggers.FLATTENED.forget(player);

        AdvancementEvents.damaged(player, level.damageSources().cactus(), 2.0f);
        helper.assertFalse(FullStopTriggers.OUCH.firedFor(player), "a cactus counted as kinetic damage");

        DamageSource fall = FullStopDamageSources.kineticSelf(player, FullStopDamageSources.velocityDisplay(30.0),
                true, false, true, false, false);
        AdvancementEvents.damaged(player, fall, 6.0f);
        helper.assertTrue(FullStopTriggers.OUCH.firedFor(player), "a landing's kinetic damage didn't earn Ouch");
        helper.assertFalse(FullStopTriggers.HIT_BY_A_SHIP.firedFor(player), "a landing counted as a ship's hit");

        DamageSource ship = FullStopDamageSources.ship(player, null, FullStopDamageSources.velocityDisplay(40.0), false, false, false);
        AdvancementEvents.damaged(player, ship, 4.0f);
        helper.assertTrue(FullStopTriggers.HIT_BY_A_SHIP.firedFor(player), "a ship's hit survived didn't earn Hit by a ship");
        helper.assertFalse(FullStopTriggers.FLATTENED.firedFor(player), "a survived hit counted as a death");

        AdvancementEvents.died(player, ship);
        helper.assertTrue(FullStopTriggers.FLATTENED.firedFor(player), "dying to a ship didn't earn Flattened");
        report("kinetic damage, a ship's hit, a ship's kill", "Ouch, Hit by a ship and Flattened fired in turn");
        helper.succeed();
    }

    /** Ten seconds beside a shock train, without a break longer than two seconds, earns "Thunderscreech". */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 40)
    public static void tenSecondsBesideAShockTrainEarnThunderscreech(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = fake(level, "train");
        FullStopTriggers.THUNDERSCREECH.forget(player);
        FullStopTriggers.forgetTrain(player);
        long t0 = 1000L;
        for (int i = 0; i < 150; i++) FullStopTriggers.trainNear(player, t0 + i);
        helper.assertFalse(FullStopTriggers.THUNDERSCREECH.firedFor(player), "150 ticks beside a train already counted");
        // a break of 100 ticks: the count starts over
        FullStopTriggers.trainNear(player, t0 + 250);
        for (int i = 1; i < 190; i++) FullStopTriggers.trainNear(player, t0 + 250 + i);
        helper.assertFalse(FullStopTriggers.THUNDERSCREECH.firedFor(player), "the count didn't start over after a break");
        for (int i = 190; i <= 200; i++) FullStopTriggers.trainNear(player, t0 + 250 + i);
        helper.assertTrue(FullStopTriggers.THUNDERSCREECH.firedFor(player), "200 ticks beside a train didn't earn Thunderscreech");
        report("ten seconds beside a shock train", "fired after 200 ticks, not after 150 nor after a break");
        helper.succeed();
    }

    /** Two of a player's own arrows meeting in the air earn "Mid-air collision". */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 300)
    public static void twoOfYourOwnArrowsMeetingEarnMidAirCollision(GameTestHelper helper) {
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 170 * 256);
        ServerLevel level = helper.getLevel();
        FakePlayer player = fake(level, "archer");
        FullStopTriggers.MID_AIR_COLLISION.forget(player);
        Entity[] arrows = new Entity[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(o.above(10)), "the lane doesn't tick entities yet"))
                .thenExecute(() -> {
                    arrows[0] = arrow(level, player, new Vec3(o.getX() - 2.5, o.getY() + 10.0, o.getZ() + 0.5), new Vec3(1.5, 0.0, 0.0));
                    arrows[1] = arrow(level, player, new Vec3(o.getX() + 3.5, o.getY() + 10.0, o.getZ() + 0.5), new Vec3(-1.5, 0.0, 0.0));
                })
                .thenWaitUntil(() -> helper.assertTrue(FullStopTriggers.MID_AIR_COLLISION.firedFor(player),
                        "the archer's two arrows haven't met yet"))
                .thenExecute(() -> {
                    report("two of one player's arrows head-on", "Mid-air collision fired");
                    for (Entity arrow : arrows) if (arrow != null) arrow.discard();
                })
                .thenSucceed();
    }

    /** A sonic boom reaching a player earns "Boom". */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 300)
    public static void aSonicBoomReachingYouEarnsBoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = fake(level, "listener");
        FullStopTriggers.BOOM.forget(player);
        Vec3 at = helper.absoluteVec(new Vec3(0.5, 3.0, 0.5));
        player.moveTo(at.x + 10.0, at.y, at.z, 0.0f, 0.0f);
        // in the level's player list, so the boom is delivered to it like to anyone
        level.addNewPlayer(player);
        SonicBooms.Key key = SonicBooms.Key.ship(-5252L - helper.absolutePos(BlockPos.ZERO).asLong() % 1000L);
        double s = SonicBooms.SOUND_MPS;
        int[] tick = {0};
        helper.onEachTick(() -> {
            if (tick[0] < 80) SonicBooms.observe(level, key, at, new Vec3(1.3 * s, 0.0, 0.0), p -> false);
            tick[0]++;
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(FullStopTriggers.BOOM.firedFor(player), "no boom reached the player yet (" + tick[0] + " ticks)"))
                .thenExecute(() -> {
                    report("a source at 1.3x the speed of sound ten blocks from a player", "Boom fired after " + tick[0] + " ticks");
                    player.discard();
                })
                .thenSucceed();
    }

    private static FakePlayer fake(ServerLevel level, String name) {
        return FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(("fullstop-advancement-" + name).getBytes()),
                "[FS " + name + "]"));
    }

    /** An arrow with no gravity, flying at {@code velocityBpt}, shot by {@code owner}, old enough not to be a volley. */
    private static Arrow arrow(ServerLevel level, Entity owner, Vec3 at, Vec3 velocityBpt) {
        Arrow arrow = new Arrow(level, at.x, at.y, at.z);
        arrow.setOwner(owner);
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(velocityBpt);
        arrow.tickCount = 50;
        level.addFreshEntity(arrow);
        return arrow;
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
