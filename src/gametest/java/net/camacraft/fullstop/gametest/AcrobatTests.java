package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.registry.ModEffects;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.camacraft.fullstop.server.physics.damage.Acrobat;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Acrobat (boots, I to III): its rolls against their chances (seeded), what each outcome does to a fall and to other
 * damage, the hold of one landing's outcome, and two real 12-block landings under Full Stop, one stuck perfectly and one
 * rolled out of, beside a villager in plain boots. Each batch sets Acrobat's test random or forced outcome, which are
 * global, so the batches with a landing run on their own. Opt-in with the rest:
 * {@code gradlew runGameTestServer -PshipTests -PtestOnly=AcrobatTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class AcrobatTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;

    private AcrobatTests() {
    }

    /** 30,000 seeded rolls a level: perfect 2/4/6 percent, rolled 10/20/30 percent of the rest; level 0 never. */
    @GameTest(template = FLAT, batch = "fullstop_acrobat", timeoutTicks = 20)
    public static void acrobatsRollsMatchTheirChances(GameTestHelper helper) {
        StringBuilder out = new StringBuilder();
        for (int level = 0; level <= 3; level++) {
            RandomSource random = RandomSource.create(9001L + level);
            int n = 30000;
            int perfect = 0;
            int rolled = 0;
            for (int i = 0; i < n; i++) {
                Acrobat.Outcome o = Acrobat.roll(level, 0.02, 0.10, random);
                if (o == Acrobat.Outcome.PERFECT) perfect++;
                if (o == Acrobat.Outcome.ROLL) rolled++;
            }
            double p = perfect / (double) n;
            double r = rolled / (double) n;
            double wantP = 0.02 * level;
            double wantR = (1.0 - wantP) * 0.10 * level;
            out.append(String.format(Locale.ROOT, "level %d: perfect %.4f (%.4f), rolled %.4f (%.4f); ", level, p, wantP, r, wantR));
            helper.assertTrue(Math.abs(p - wantP) < 0.005, "level " + level + "'s perfect landings: " + out);
            helper.assertTrue(Math.abs(r - wantR) < 0.01, "level " + level + "'s rolls: " + out);
        }
        report("Acrobat's rolls, 30,000 a level", out.toString());
        helper.succeed();
    }

    /**
     * A pig in Acrobat III iron boots hurt 400 times by an 8-point fall (a fresh landing each time): it loses 0, 4 or 8
     * and nothing else, about 6 percent none and 28 percent half; the same with Full Stop's own landing damage; a pig in
     * plain boots always 8; magic and Full Stop's side impact (not falls) always 8 with Acrobat. A landing's outcome
     * holds: a forced perfect landing hurt again in the same tick with the rolls back on still deals nothing, and after
     * the hold a new roll is made.
     */
    @GameTest(template = FLAT, batch = "fullstop_acrobat", timeoutTicks = 60)
    public static void acrobatTakesFallsOnlyAndHoldsALandingsOutcome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 0);
        Pig acrobat = pig(level, o, 2, acrobatBoots(3));
        Pig plain = pig(level, o, 5, new ItemStack(Items.IRON_BOOTS));
        Acrobat.forceOutcomeForTests(null);
        Acrobat.useRandomForTests(RandomSource.create(4242L));
        try {
            int[] vanilla = tally(acrobat, level.damageSources().fall(), 400);
            int[] kinetic = tally(acrobat, FullStopDamageSources.kineticSelf(acrobat, FullStopDamageSources.velocityDisplay(20.0),
                    true, false, true, false, false), 400);
            int[] control = tally(plain, level.damageSources().fall(), 100);
            int[] magic = tally(acrobat, level.damageSources().magic(), 100);
            DamageSource side = FullStopDamageSources.kineticSelf(acrobat, FullStopDamageSources.velocityDisplay(20.0),
                    false, false, false, false, false);
            helper.assertFalse(side.is(DamageTypeTags.IS_FALL), "Full Stop's side impact is a fall");
            int[] wall = tally(acrobat, side, 100);
            String out = String.format(Locale.ROOT, "vanilla fall none/half/full/other %s, Full Stop landing %s, plain boots %s, "
                    + "magic %s, side impact %s", str(vanilla), str(kinetic), str(control), str(magic), str(wall));
            report("8-point hurts on a pig in Acrobat III boots", out);
            for (int[] t : new int[][] {vanilla, kinetic}) {
                helper.assertTrue(t[3] == 0, "a fall came to something other than none, half or all: " + out);
                helper.assertTrue(t[0] >= 10 && t[0] <= 45, "perfect landings far from 6 percent of 400: " + out);
                helper.assertTrue(t[1] >= 80 && t[1] <= 150, "rolls far from 28 percent of 400: " + out);
            }
            helper.assertTrue(control[2] == 100, "plain boots changed a fall: " + out);
            helper.assertTrue(magic[2] == 100, "Acrobat changed magic damage: " + out);
            helper.assertTrue(wall[2] == 100, "Acrobat changed a side impact: " + out);

            // the hold: one landing, one outcome
            Acrobat.forget(acrobat);
            Acrobat.forceOutcomeForTests(Acrobat.Outcome.PERFECT);
            float first = hurtOnce(acrobat, level.damageSources().fall(), false);
            Acrobat.forceOutcomeForTests(Acrobat.Outcome.NONE);
            float again = hurtOnce(acrobat, level.damageSources().fall(), false);
            helper.assertTrue(first == 0.0f && again == 0.0f,
                    "a landing's second hurt in the same tick got a new outcome: " + first + ", " + again);
        } catch (RuntimeException e) {
            reset();
            throw e;
        }
        helper.runAfterDelay(8, () -> {
            float later = hurtOnce(acrobat, level.damageSources().fall(), false);
            reset();
            report("Acrobat's hold", "perfect, the same tick again: nothing; " + 8 + " ticks later, forced none: " + later);
            helper.assertTrue(later == 8.0f, "after the hold a landing still took the old outcome: " + later);
            acrobat.discard();
            plain.discard();
            helper.succeed();
        });
    }

    /** A villager in Acrobat III boots, every landing stuck perfectly, and one in plain boots dropped 12 blocks onto stone. */
    @GameTest(template = FLAT, batch = "fullstop_acrobat_perfect", timeoutTicks = 200)
    public static void aPerfectLandingDealsNothingAndSprainsNothing(GameTestHelper helper) {
        drop(helper, 1, Acrobat.Outcome.PERFECT, (acrobat, plain, out) -> {
            helper.assertTrue(plain.lost > 0.0 && plain.source != null && plain.source.is(DamageTypeTags.IS_FALL),
                    "the villager in plain boots took no fall damage: " + out);
            helper.assertTrue(plain.sprained, "the villager in plain boots wasn't sprained: " + out);
            helper.assertTrue(acrobat.lost == 0.0, "the perfect landing hurt: " + out);
            helper.assertFalse(acrobat.sprained, "the perfect landing sprained: " + out);
        });
    }

    /** As above, every landing rolled out of: about half the plain villager's damage. */
    @GameTest(template = FLAT, batch = "fullstop_acrobat_roll", timeoutTicks = 200)
    public static void aRolledLandingDealsAboutHalf(GameTestHelper helper) {
        drop(helper, 2, Acrobat.Outcome.ROLL, (acrobat, plain, out) -> {
            helper.assertTrue(plain.lost > 2.0, "the villager in plain boots took too little to compare: " + out);
            double share = acrobat.lost / plain.lost;
            helper.assertTrue(share > 0.35 && share < 0.65, "a rolled landing didn't deal about half: " + out);
        });
    }

    // ------------------------------------------------------------------

    private interface Check {
        void check(Body acrobat, Body plain, String out);
    }

    private static void drop(GameTestHelper helper, int laneNo, Acrobat.Outcome outcome, Check check) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, laneNo);
        for (BlockPos pos : BlockPos.betweenClosed(o, o.offset(8, 0, 4))) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        Acrobat.useRandomForTests(null);
        Acrobat.forceOutcomeForTests(outcome);
        Body acrobat = new Body(villager(level, new Vec3(o.getX() + 2.5, o.getY() + 13.0, o.getZ() + 2.5), acrobatBoots(3)));
        Body plain = new Body(villager(level, new Vec3(o.getX() + 6.5, o.getY() + 13.0, o.getZ() + 2.5), new ItemStack(Items.IRON_BOOTS)));
        helper.onEachTick(() -> {
            acrobat.record();
            plain.record();
        });
        helper.runAfterDelay(120, () -> {
            reset();
            String out = String.format(Locale.ROOT, "Acrobat III (%s): %s, sprained %s; plain boots: %s, sprained %s",
                    outcome, acrobat.describe(), acrobat.sprained, plain.describe(), plain.sprained);
            report("12-block drop onto stone, Acrobat III forced " + outcome + " beside plain boots", out);
            check.check(acrobat, plain, out);
            acrobat.body.discard();
            plain.body.discard();
            helper.succeed();
        });
    }

    private static void reset() {
        Acrobat.useRandomForTests(null);
        Acrobat.forceOutcomeForTests(null);
    }

    /** {none, half, all, other} of n fresh landings of 8 points. */
    private static int[] tally(LivingEntity body, DamageSource source, int n) {
        int[] t = new int[4];
        for (int i = 0; i < n; i++) {
            float lost = hurtOnce(body, source, true);
            if (lost == 0.0f) t[0]++;
            else if (Math.abs(lost - 4.0f) < 0.01f) t[1]++;
            else if (Math.abs(lost - 8.0f) < 0.01f) t[2]++;
            else t[3]++;
        }
        return t;
    }

    private static float hurtOnce(LivingEntity body, DamageSource source, boolean freshLanding) {
        if (freshLanding) Acrobat.forget(body);
        body.invulnerableTime = 0;
        body.setHealth(body.getMaxHealth());
        float before = body.getHealth();
        body.hurt(source, 8.0f);
        float lost = before - body.getHealth();
        body.setHealth(body.getMaxHealth());
        return lost;
    }

    private static String str(int[] t) {
        return t[0] + "/" + t[1] + "/" + t[2] + "/" + t[3];
    }

    private static ItemStack acrobatBoots(int lvl) {
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        boots.enchant(ModEnchantments.ACROBAT.get(), lvl);
        return boots;
    }

    private static Pig pig(ServerLevel level, BlockPos o, int dx, ItemStack boots) {
        Pig pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        pig.moveTo(o.getX() + dx + 0.5, o.getY() + 1.0, o.getZ() + 2.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        pig.setPersistenceRequired();
        pig.setItemSlot(EquipmentSlot.FEET, boots);
        pig.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20.0);
        pig.setHealth(20.0f);
        level.addFreshEntity(pig);
        return pig;
    }

    private static Villager villager(ServerLevel level, Vec3 at, ItemStack boots) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) throw new IllegalStateException("no villager");
        villager.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        villager.setPersistenceRequired();
        villager.setItemSlot(EquipmentSlot.FEET, boots);
        level.addFreshEntity(villager);
        return villager;
    }

    private static BlockPos lane(GameTestHelper helper, int lane) {
        return ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (250 + lane) * 256);
    }

    /** A body and what happened to it: health lost, the first damage source, whether it was ever sprained. */
    private static final class Body {
        final LivingEntity body;
        final float health;
        double lost;
        @Nullable
        DamageSource source;
        boolean sprained;

        Body(LivingEntity body) {
            this.body = body;
            this.health = body.getHealth();
        }

        void record() {
            sprained |= body.hasEffect(ModEffects.SPRAIN.get());
            float hp = body.isAlive() ? body.getHealth() : 0.0f;
            if (hp < health - lost) {
                lost = health - hp;
                if (source == null) source = body.getLastDamageSource();
            }
        }

        String describe() {
            return lost == 0.0 ? "unhurt" : ShipCollisionTests.fmt(lost) + " damage (" + (source == null ? "?" : source.getMsgId()) + ")";
        }
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
