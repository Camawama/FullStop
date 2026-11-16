package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.camacraft.fullstop.server.physics.damage.Acrobat;
import net.camacraft.fullstop.server.physics.damage.Rebound;
import net.camacraft.fullstop.server.physics.damage.Stomp;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Stomp and Rebound (boots, I to III): their numbers, who may be stomped, and real landings under Full Stop beside
 * villagers in plain boots: a stomp onto a penned pig (it takes part of the stomper's landing, the stomper is spared that
 * part), a Rebound landing (it springs back up, takes the damage less the energy given back, and its bounce is claimed
 * with Camafy), a sneaking one (lands as plain boots do), and Rebound beside a forced Acrobat outcome (a perfect landing
 * doesn't bounce, a roll still does). Opt-in with the rest: {@code gradlew runGameTestServer -PshipTests -PtestOnly=BootsTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class BootsTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;

    private BootsTests() {
    }

    /** Stomp passes 20/40/60 percent; Rebound springs at 0.4/0.5/0.6 and is spared that squared, held under its cap. */
    @GameTest(template = FLAT, batch = "fullstop_boots", timeoutTicks = 20)
    public static void theNumbers(GameTestHelper helper) {
        String out = String.format(Locale.ROOT, "stomp %.2f/%.2f/%.2f; rebound at 20 m/s: %s / %s / %s; at 50 m/s III: %s; level 0: %s",
                Stomp.share(1), Stomp.share(2), Stomp.share(3), pair(Rebound.bounceOf(1, 20, 25)), pair(Rebound.bounceOf(2, 20, 25)),
                pair(Rebound.bounceOf(3, 20, 25)), pair(Rebound.bounceOf(3, 50, 25)), pair(Rebound.bounceOf(0, 20, 25)));
        report("Stomp's and Rebound's numbers", out);
        helper.assertTrue(near(Stomp.share(1), 0.2) && near(Stomp.share(2), 0.4) && near(Stomp.share(3), 0.6), "Stomp's shares: " + out);
        double[] r3 = Rebound.bounceOf(3, 20, 25);
        helper.assertTrue(near(r3[0], 12.0) && near(r3[1], 0.36), "Rebound III at 20 m/s: " + out);
        double[] capped = Rebound.bounceOf(3, 50, 25);
        helper.assertTrue(near(capped[0], 25.0) && near(capped[1], 0.25), "Rebound's cap: " + out);
        helper.assertTrue(Rebound.bounceOf(0, 20, 25)[0] == 0.0, "level 0 bounced: " + out);
        helper.succeed();
    }

    /** Never one's own pet, a stack one rides, or itself; another's pet as any body; a stomp only from a landing that hurts. */
    @GameTest(template = FLAT, batch = "fullstop_boots", timeoutTicks = 20)
    public static void whoMayBeStomped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 0);
        Villager stomper = villager(level, Vec3.atBottomCenterOf(o.above()), boots(ModEnchantments.STOMP.get(), 3));
        Villager stranger = villager(level, Vec3.atBottomCenterOf(o.above().east(3)), new ItemStack(Items.IRON_BOOTS));
        Wolf own = EntityType.WOLF.create(level);
        Wolf theirs = EntityType.WOLF.create(level);
        Pig mount = EntityType.PIG.create(level);
        if (own == null || theirs == null || mount == null) throw new IllegalStateException("no wolf or pig");
        for (LivingEntity e : new LivingEntity[] {own, theirs, mount}) {
            e.moveTo(o.getX() + 0.5, o.getY() + 1.0, o.getZ() + 4.5, 0.0f, 0.0f);
            level.addFreshEntity(e);
        }
        own.setTame(true);
        own.setOwnerUUID(stomper.getUUID());
        theirs.setTame(true);
        theirs.setOwnerUUID(stranger.getUUID());
        String out = String.format(Locale.ROOT, "own pet %s, another's pet %s, a stranger %s, itself %s; stomps instead of riding at 20 m/s %s, "
                        + "at 8 m/s %s",
                Stomp.mayStomp(stomper, own), Stomp.mayStomp(stomper, theirs), Stomp.mayStomp(stomper, stranger),
                Stomp.mayStomp(stomper, stomper), Stomp.stompsInsteadOfRiding(stomper, 20.0), Stomp.stompsInsteadOfRiding(stomper, 8.0));
        helper.assertFalse(Stomp.mayStomp(stomper, own), "its own pet: " + out);
        helper.assertTrue(Stomp.mayStomp(stomper, theirs), "another's pet: " + out);
        helper.assertTrue(Stomp.mayStomp(stomper, stranger), "a stranger: " + out);
        helper.assertFalse(Stomp.mayStomp(stomper, stomper), "itself: " + out);
        helper.assertTrue(Stomp.stompsInsteadOfRiding(stomper, 20.0), "a hard landing rides: " + out);
        helper.assertFalse(Stomp.stompsInsteadOfRiding(stomper, 8.0), "a hop stomps: " + out);
        stomper.startRiding(mount, true);
        boolean onMount = Stomp.mayStomp(stomper, mount);
        out += ", the pig it rides " + onMount;
        report("who may be stomped", out);
        helper.assertFalse(onMount, "the body it rides: " + out);
        for (LivingEntity e : new LivingEntity[] {stomper, stranger, own, theirs, mount}) e.discard();
        helper.succeed();
    }

    /**
     * A villager in Stomp III iron boots and one in plain iron boots dropped 16 blocks onto a penned standing pig each (40
     * health): the stomped pig loses more, the stomper less, the stomper's saving and the pig's extra each about 60
     * percent of the plain villager's landing.
     */
    @GameTest(template = FLAT, batch = "fullstop_boots_stomp", timeoutTicks = 220)
    public static void aStompPassesPartOfTheLandingToThePig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 1);
        floor(level, o);
        Body[] pigs = new Body[2];
        Body[] landers = new Body[2];
        for (int i = 0; i < 2; i++) {
            BlockPos cell = o.offset(2 + i * 5, 1, 2);
            for (BlockPos p : new BlockPos[] {cell.north(), cell.south(), cell.east(), cell.west()}) {
                level.setBlock(p, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(p.above(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            }
            Pig pig = EntityType.PIG.create(level);
            if (pig == null) throw new IllegalStateException("no pig");
            pig.moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0.0f, 0.0f);
            pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40.0);
            pig.setHealth(40.0f);
            pig.setPersistenceRequired();
            level.addFreshEntity(pig);
            pigs[i] = new Body(pig);
            ItemStack b = i == 0 ? boots(ModEnchantments.STOMP.get(), 3) : new ItemStack(Items.IRON_BOOTS);
            landers[i] = new Body(villager(level, new Vec3(cell.getX() + 0.5, cell.getY() + 16.0, cell.getZ() + 0.5), b));
        }
        helper.onEachTick(() -> {
            for (Body b : pigs) b.record();
            for (Body b : landers) b.record();
        });
        helper.runAfterDelay(140, () -> {
            String out = String.format(Locale.ROOT, "Stomp III: pig lost %.2f, villager lost %.2f; plain: pig lost %.2f, villager lost %.2f",
                    pigs[0].lost, landers[0].lost, pigs[1].lost, landers[1].lost);
            report("16-block drop onto a penned pig, Stomp III beside plain boots", out);
            double plainLanding = landers[1].lost;
            helper.assertTrue(plainLanding > 2.0, "the plain villager's landing too small to compare: " + out);
            double extra = pigs[0].lost - pigs[1].lost;
            double spared = landers[1].lost - landers[0].lost;
            helper.assertTrue(extra > 0.35 * plainLanding && extra < 0.85 * plainLanding, "the stomped pig's extra: " + out);
            helper.assertTrue(spared > 0.35 * plainLanding && spared < 0.85 * plainLanding, "the stomper's saving: " + out);
            for (Body b : pigs) b.body.discard();
            for (Body b : landers) b.body.discard();
            helper.succeed();
        });
    }

    /**
     * Three villagers dropped 12 blocks onto stone: Rebound III, Rebound III sneaking, plain boots. The Rebound one rises
     * clear of the ground (over 0.8 of a block) after landing, takes about 64 percent of the plain one's damage, and its bounce is claimed
     * (Camafy, BOUNCE); the sneaking one lands as the plain one does and doesn't rise.
     */
    @GameTest(template = FLAT, batch = "fullstop_boots_rebound", timeoutTicks = 220)
    public static void aReboundLandingSpringsBackUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 2);
        floor(level, o);
        Acrobat.forceOutcomeForTests(null);
        Body rebound = new Body(villager(level, new Vec3(o.getX() + 2.5, o.getY() + 13.0, o.getZ() + 2.5), boots(ModEnchantments.REBOUND.get(), 3)));
        Body sneaking = new Body(villager(level, new Vec3(o.getX() + 5.5, o.getY() + 13.0, o.getZ() + 2.5), boots(ModEnchantments.REBOUND.get(), 3)));
        Body plain = new Body(villager(level, new Vec3(o.getX() + 8.5, o.getY() + 13.0, o.getZ() + 2.5), new ItemStack(Items.IRON_BOOTS)));
        sneaking.body.setShiftKeyDown(true);
        double ground = o.getY() + 1.0;
        helper.onEachTick(() -> {
            sneaking.body.setShiftKeyDown(true);
            for (Body b : new Body[] {rebound, sneaking, plain}) b.record(ground);
        });
        helper.runAfterDelay(160, () -> {
            String out = String.format(Locale.ROOT, "Rebound III: %s; sneaking: %s; plain: %s", rebound.describe(), sneaking.describe(), plain.describe());
            report("12-block drop onto stone, Rebound III, Rebound III sneaking, plain boots", out);
            helper.assertTrue(plain.lost > 2.0, "the plain landing too small to compare: " + out);
            helper.assertTrue(rebound.riseAfterLanding > 0.8, "the Rebound landing didn't spring up: " + out);
            double share = rebound.lost / plain.lost;
            helper.assertTrue(share > 0.5 && share < 0.78, "the Rebound landing's damage isn't about 64 percent: " + out);
            helper.assertTrue(rebound.claimedBounce, "the bounce wasn't claimed with Camafy: " + out);
            helper.assertTrue(sneaking.riseAfterLanding < 0.3, "the sneaking landing sprang up: " + out);
            helper.assertTrue(Math.abs(sneaking.lost - plain.lost) < 0.15 * plain.lost, "the sneaking landing wasn't a plain one: " + out);
            for (Body b : new Body[] {rebound, sneaking, plain}) b.body.discard();
            helper.succeed();
        });
    }

    /** Rebound III with Acrobat III, every landing stuck perfectly: no damage and no bounce. */
    @GameTest(template = FLAT, batch = "fullstop_boots_perfect", timeoutTicks = 220)
    public static void aPerfectLandingDoesNotBounce(GameTestHelper helper) {
        acrobatWithRebound(helper, 3, Acrobat.Outcome.PERFECT);
    }

    /** Rebound III with Acrobat III, every landing rolled: about a third of the plain damage (0.64 x 0.5), and it bounces. */
    @GameTest(template = FLAT, batch = "fullstop_boots_roll", timeoutTicks = 220)
    public static void aRolledLandingStillBounces(GameTestHelper helper) {
        acrobatWithRebound(helper, 4, Acrobat.Outcome.ROLL);
    }

    /**
     * From 16 blocks the bounce rises some three blocks; the landing that ends it is spared that height: Rebound III and
     * plain villagers dropped 16 blocks onto stone, the Rebound one hurt once (the landing, about 64 percent of the plain
     * one) and never by coming back down, and its bounce's start let go after.
     */
    @GameTest(template = FLAT, batch = "fullstop_boots_spared", timeoutTicks = 260)
    public static void theLandingThatEndsABounceIsSpared(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 5);
        floor(level, o);
        Acrobat.forceOutcomeForTests(null);
        Body rebound = new Body(villager(level, new Vec3(o.getX() + 2.5, o.getY() + 17.0, o.getZ() + 2.5), boots(ModEnchantments.REBOUND.get(), 3)));
        Body plain = new Body(villager(level, new Vec3(o.getX() + 7.5, o.getY() + 17.0, o.getZ() + 2.5), new ItemStack(Items.IRON_BOOTS)));
        double ground = o.getY() + 1.0;
        helper.onEachTick(() -> {
            rebound.record(ground);
            plain.record(ground);
        });
        helper.runAfterDelay(200, () -> {
            boolean startLeft = Rebound.hasStart(rebound.body);
            String out = String.format(Locale.ROOT, "Rebound III: %s, a start still held %s; plain: %s", rebound.describe(), startLeft, plain.describe());
            report("16-block drop, the bounce's own landing", out);
            helper.assertTrue(plain.lost > 2.0, "the plain landing too small to compare: " + out);
            helper.assertTrue(rebound.riseAfterLanding > 1.5, "it didn't bounce high enough to come down hard: " + out);
            helper.assertTrue(rebound.hits.size() == 1, "coming back down from its own bounce hurt: " + out);
            double share = rebound.lost / plain.lost;
            helper.assertTrue(share > 0.58 && share < 0.70, "the landing isn't about 64 percent: " + out);
            helper.assertFalse(startLeft, "the bounce's start was never let go: " + out);
            rebound.body.discard();
            plain.body.discard();
            helper.succeed();
        });
    }

    /**
     * A bounce that carries the body off a ledge: a Rebound III villager dropped 16 blocks onto a 3x3 platform 12 blocks
     * above the floor, steered sideways in the air after its bounce, falls the 12 blocks to the floor; that landing counts the fall
     * below the bounce's start, about what a Rebound III villager dropped 12 blocks from rest takes.
     */
    @GameTest(template = FLAT, batch = "fullstop_boots_ledge", timeoutTicks = 320)
    public static void aBounceOffALedgeIsHurtByTheDropBelowItsStart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 6);
        floor(level, o);
        Acrobat.forceOutcomeForTests(null);
        BlockPos top = o.offset(2, 12, 2);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-1, 0, -1), top.offset(1, 0, 1))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        Body ledge = new Body(villager(level, new Vec3(top.getX() + 0.5, top.getY() + 17.0, top.getZ() + 0.5), boots(ModEnchantments.REBOUND.get(), 3)));
        Body fresh = new Body(villager(level, new Vec3(o.getX() + 9.5, o.getY() + 13.0, o.getZ() + 2.5), boots(ModEnchantments.REBOUND.get(), 3)));
        boolean[] pushed = {false};
        double platform = top.getY() + 1.0;
        helper.onEachTick(() -> {
            ledge.record(platform);
            fresh.record(o.getY() + 1.0);
            // carried off the edge: steered sideways each tick while it is in the air after the bounce, as a player
            // steers, until it is past the platform's edge
            if (ledge.landed && !ledge.body.onGround() && ledge.body.getX() < top.getX() + 2.6) {
                Vec3 v = ledge.body.getDeltaMovement();
                ledge.body.setDeltaMovement(0.3, v.y, 0.0);
                pushed[0] = true;
            }
        });
        helper.runAfterDelay(260, () -> {
            String out = String.format(Locale.ROOT, "off the ledge: %s, pushed %s, at y %.1f (floor %d); from rest 12 blocks: %s",
                    ledge.describe(), pushed[0], ledge.body.getY(), o.getY() + 1, fresh.describe());
            report("a bounce off a ledge into a 12-block drop", out);
            helper.assertTrue(pushed[0] && ledge.body.getY() < o.getY() + 2.0, "the villager never left the platform: " + out);
            helper.assertTrue(ledge.hits.size() >= 2, "the drop off the ledge didn't hurt: " + out);
            helper.assertTrue(!fresh.hits.isEmpty(), "the fresh 12-block landing didn't hurt: " + out);
            // the floor landing (its two ticks, when Full Stop tops it up) against the fresh fall's
            double floor = 0.0;
            for (int i = 1; i < ledge.hits.size(); i++) floor += ledge.hits.get(i);
            double ratio = floor / fresh.lost;
            helper.assertTrue(ratio > 0.6 && ratio < 1.4, "the drop off the ledge isn't about a 12-block fall: " + out);
            ledge.body.discard();
            fresh.body.discard();
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------

    private static void acrobatWithRebound(GameTestHelper helper, int laneNo, Acrobat.Outcome outcome) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, laneNo);
        floor(level, o);
        Acrobat.forceOutcomeForTests(outcome);
        ItemStack both = boots(ModEnchantments.REBOUND.get(), 3);
        both.enchant(ModEnchantments.ACROBAT.get(), 3);
        Body body = new Body(villager(level, new Vec3(o.getX() + 2.5, o.getY() + 13.0, o.getZ() + 2.5), both));
        Body plain = new Body(villager(level, new Vec3(o.getX() + 6.5, o.getY() + 13.0, o.getZ() + 2.5), new ItemStack(Items.IRON_BOOTS)));
        double ground = o.getY() + 1.0;
        helper.onEachTick(() -> {
            body.record(ground);
            plain.record(ground);
        });
        helper.runAfterDelay(160, () -> {
            Acrobat.forceOutcomeForTests(null);
            String out = String.format(Locale.ROOT, "Rebound III + Acrobat III forced %s: %s; plain: %s", outcome, body.describe(), plain.describe());
            report("12-block drop, Rebound with Acrobat " + outcome, out);
            helper.assertTrue(plain.lost > 2.0, "the plain landing too small to compare: " + out);
            if (outcome == Acrobat.Outcome.PERFECT) {
                helper.assertTrue(body.lost == 0.0, "the perfect landing hurt: " + out);
                helper.assertTrue(body.riseAfterLanding < 0.3, "the perfect landing bounced: " + out);
            } else {
                double share = body.lost / plain.lost;
                helper.assertTrue(share > 0.2 && share < 0.45, "the rolled Rebound landing isn't about a third: " + out);
                helper.assertTrue(body.riseAfterLanding > 0.8, "the rolled landing didn't bounce: " + out);
            }
            body.body.discard();
            plain.body.discard();
            helper.succeed();
        });
    }

    /** A body: health lost, the highest it rose above the ground after first touching it, whether a bounce was claimed. */
    private static final class Body {
        final LivingEntity body;
        final float health;
        double lost;
        float lastHp;
        final java.util.List<Double> hits = new java.util.ArrayList<>();
        boolean landed;
        double riseAfterLanding;
        boolean claimedBounce;

        Body(LivingEntity body) {
            this.body = body;
            this.health = body.getHealth();
            this.lastHp = this.health;
        }

        void record() {
            float hp = body.isAlive() ? body.getHealth() : 0.0f;
            lost = Math.max(lost, health - hp);
            if (hp < lastHp - 1.0e-4f) hits.add((double) (lastHp - hp));
            lastHp = hp;
        }

        void record(double ground) {
            record();
            if (body.onGround() || body.getY() < ground + 0.05) landed = true;
            if (landed) riseAfterLanding = Math.max(riseAfterLanding, body.getY() - ground);
            if (PhysicsAspect.BOUNCE.in(PhysicsOwnership.claims(body))) claimedBounce = true;
        }

        String describe() {
            StringBuilder h = new StringBuilder();
            for (double d : hits) h.append(String.format(Locale.ROOT, "%.2f ", d));
            return String.format(Locale.ROOT, "lost %.2f (hits %s), rose %.2f after landing, bounce claimed %s", lost, h.toString().trim(),
                    riseAfterLanding, claimedBounce);
        }
    }

    private static void floor(ServerLevel level, BlockPos o) {
        for (BlockPos pos : BlockPos.betweenClosed(o, o.offset(10, 0, 4))) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static ItemStack boots(Enchantment e, int lvl) {
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        boots.enchant(e, lvl);
        return boots;
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
        return ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (260 + lane) * 256);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }

    private static String pair(double[] p) {
        return String.format(Locale.ROOT, "%.2f m/s (%.2f off)", p[0], p[1]);
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
