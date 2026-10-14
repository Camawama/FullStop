package net.camacraft.fullstop.gametest;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.physics.SlimeProjectiles;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/**
 * Thrown snowballs, eggs, potions and ender pearls, fire charges, arrows and firework rockets on slime. The owner,
 * 2026-10-13: only pointy things (arrows, tridents) need a shallow angle, "Ender pearls, eggs, etc should bounce at any
 * angle", fire charges bounce too, and a rocket held by a glider must not bounce itself. Thrown along a slime floor each
 * bounces off it and is still flying the tick after (a bounce is no hit: nothing breaks, splashes, hatches, teleports or
 * burns there), at 12 degrees and at 60 and 80; an arrow skips at 12 and sticks at 60. Runs with the ship tests
 * ({@code gradlew runGameTestServer -PshipTests -PtestOnly=SlimeThrownTests}); no ship needed.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class SlimeThrownTests {

    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_slime_thrown";
    /** 30 m/s, about a thrown snowball's 1.5 blocks a tick. */
    private static final double SPEED_BPT = 1.5;

    private SlimeThrownTests() {
    }

    /**
     * {@code type} thrown along +x over a slime floor, meeting it at about {@code angleDeg}: a skip is expected when
     * {@code skips}; checked 40 ticks on.
     */
    private static void thrown(GameTestHelper helper, EntityType<? extends Projectile> type, double angleDeg, boolean skips) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 46; x++) {
            for (int z = 22; z <= 26; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.SLIME_BLOCK);
        }
        Projectile p = type.create(level);
        if (p == null) throw new IllegalStateException("no " + type);
        double a = Math.toRadians(angleDeg);
        // started just above the floor (its top at y 2), so it meets it a block or two on at nearly its own angle
        Vec3 start = helper.absoluteVec(new Vec3(3.5, 2.0 + 1.5 * Math.tan(a) + 0.1, 24.5));
        p.moveTo(start.x, start.y, start.z, 0.0F, 0.0F);
        Vec3 dir = new Vec3(Math.cos(a), -Math.sin(a), 0.0);
        p.setDeltaMovement(dir.scale(SPEED_BPT));
        if (p instanceof AbstractArrow arrow) arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        if (p instanceof AbstractHurtingProjectile fireball) {
            // a fire charge's thrust, as a dispenser gives it
            fireball.xPower = dir.x * 0.1;
            fireball.yPower = dir.y * 0.1;
            fireball.zPower = dir.z * 0.1;
        }
        level.addFreshEntity(p);
        int[] tick = {0};
        int[] firstSkip = {-1};
        boolean[] aliveAfterSkip = {false};
        int[] removedAt = {-1};
        helper.onEachTick(() -> {
            int t = tick[0]++;
            if (removedAt[0] < 0 && p.isRemoved()) removedAt[0] = t;
            if (firstSkip[0] < 0 && SlimeProjectiles.skips(p) > 0) firstSkip[0] = t;
            else if (firstSkip[0] >= 0 && t == firstSkip[0] + 1) aliveAfterSkip[0] = !p.isRemoved();
        });
        helper.runAfterDelay(40, () -> {
            SlimeProjectiles.Bounce first = SlimeProjectiles.last(p);
            String out = String.format(Locale.ROOT, "%s at %.0f degrees: %d skips (first at tick %d, met at %s degrees), "
                            + "alive the tick after: %s, gone at tick %d, now %.1f blocks on", type.getDescriptionId(), angleDeg,
                    SlimeProjectiles.skips(p), firstSkip[0], first == null ? "-" : String.format(Locale.ROOT, "%.1f", first.angleDeg()),
                    aliveAfterSkip[0], removedAt[0], p.position().x - start.x);
            ShipCollisionTests.report("a thrown item on slime", out);
            if (p instanceof AbstractHurtingProjectile fireball && skips) {
                helper.assertTrue(firstSkip[0] < 0 || fireball.yPower >= 0.0 || SlimeProjectiles.skips(p) > 1,
                        "its thrust still points into the slime: " + out);
            }
            if (p instanceof AbstractArrow && !skips) {
                helper.assertTrue(SlimeProjectiles.skips(p) == 0, "skipped though shot steeply: " + out);
                helper.succeed();
                return;
            }
            if (skips) {
                helper.assertTrue(SlimeProjectiles.skips(p) >= 1, "no skip: " + out);
                helper.assertTrue(aliveAfterSkip[0], "it broke at its skip: " + out);
                helper.assertTrue(removedAt[0] < 0 || removedAt[0] > firstSkip[0] + 1, "gone at its skip: " + out);
            } else {
                helper.assertTrue(SlimeProjectiles.skips(p) == 0, "skipped though thrown steeply: " + out);
                helper.assertTrue(removedAt[0] >= 0 && removedAt[0] < 10, "didn't land at once: " + out);
            }
            helper.succeed();
        });
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aSnowballThrownShallowSkipsOffSlime(GameTestHelper helper) {
        thrown(helper, EntityType.SNOWBALL, 12.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void anEggThrownShallowSkipsOffSlimeWithoutHatching(GameTestHelper helper) {
        thrown(helper, EntityType.EGG, 12.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aPotionThrownShallowSkipsOffSlimeWithoutSplashing(GameTestHelper helper) {
        thrown(helper, EntityType.POTION, 12.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aPearlThrownShallowSkipsOffSlimeWithoutTeleporting(GameTestHelper helper) {
        thrown(helper, EntityType.ENDER_PEARL, 12.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aSnowballThrownSteeplyIntoSlimeBouncesToo(GameTestHelper helper) {
        thrown(helper, EntityType.SNOWBALL, 60.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aPotionThrownSteeplyIntoSlimeBouncesWithoutSplashing(GameTestHelper helper) {
        thrown(helper, EntityType.POTION, 60.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aPearlThrownAlmostStraightDownIntoSlimeBounces(GameTestHelper helper) {
        thrown(helper, EntityType.ENDER_PEARL, 80.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void anEggThrownAlmostStraightDownIntoSlimeBouncesWithoutHatching(GameTestHelper helper) {
        thrown(helper, EntityType.EGG, 80.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aFireChargeBouncesOffSlimeWithoutBurning(GameTestHelper helper) {
        thrown(helper, EntityType.SMALL_FIREBALL, 45.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void anArrowShotShallowSkipsOffSlime(GameTestHelper helper) {
        thrown(helper, EntityType.ARROW, 12.0, true);
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void anArrowShotSteeplyStillSticksInSlime(GameTestHelper helper) {
        thrown(helper, EntityType.ARROW, 60.0, false);
    }

    /**
     * A firework rocket held by a body falling onto slime (as by a glider flying into it) never bounces or sounds itself:
     * the owner heard two bounces flying into slime on a rocket. A loose rocket fired at the same floor does bounce.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 80)
    public static void aRocketHeldByAFallingBodyNeverBouncesItself(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 46; x++) {
            for (int z = 20; z <= 28; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.SLIME_BLOCK);
        }
        Zombie body = EntityType.ZOMBIE.create(level);
        if (body == null) throw new IllegalStateException("no zombie");
        Vec3 at = helper.absoluteVec(new Vec3(10.5, 6.0, 24.5));
        body.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        // (with its AI: a mob without one doesn't move at all, it never falls)
        body.setDeltaMovement(0.0, -1.2, 0.0);
        level.addFreshEntity(body);
        FireworkRocketEntity held = new FireworkRocketEntity(level, new ItemStack(Items.FIREWORK_ROCKET), body);
        level.addFreshEntity(held);
        Vec3 free = helper.absoluteVec(new Vec3(30.5, 5.0, 24.5));
        FireworkRocketEntity loose = new FireworkRocketEntity(level, new ItemStack(Items.FIREWORK_ROCKET), free.x, free.y, free.z, false);
        loose.setDeltaMovement(0.3, -1.2, 0.0);
        level.addFreshEntity(loose);
        double[] lowest = {Double.MAX_VALUE};
        helper.onEachTick(() -> lowest[0] = Math.min(lowest[0], body.getY() - helper.absoluteVec(Vec3.ZERO).y));
        helper.runAfterDelay(14, () -> {
            String out = "held rocket's bounce: " + SlimeProjectiles.last(held) + ", the body came down to y "
                    + String.format(Locale.ROOT, "%.2f", lowest[0]) + " (the slime's top: 2), loose rocket's: " + SlimeProjectiles.last(loose);
            helper.assertTrue(lowest[0] < 2.5, "the body never reached the slime: " + out);
            ShipCollisionTests.report("a rocket held by a falling body and a loose one onto slime", out);
            helper.assertTrue(SlimeProjectiles.last(held) == null, "the held rocket bounced itself: " + out);
            helper.assertTrue(SlimeProjectiles.last(loose) != null, "the loose rocket never bounced (the test saw no bounce at all): " + out);
            helper.succeed();
        });
    }
}
