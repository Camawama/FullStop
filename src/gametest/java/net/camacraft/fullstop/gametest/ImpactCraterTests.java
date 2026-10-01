package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.interaction.KineticBlockInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Impact craters ({@code impactCraters}, {@link KineticBlockInteractions}): a body wider than a block
 * that crashes into the ground breaks the blocks under its whole footprint, not only the handful its
 * collision rays hit. Measured before (the owner's report of 2026-09-30): a giant dropped onto the
 * ground broke five scattered dirt blocks, one per ray, and stood on the rest.
 *
 * <p>The bodies fall under gravity onto a pad of dirt five layers deep (the flat world's own ground
 * is only three blocks over bedrock). Opt-in with the rest of FullStop's game tests:
 * {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ImpactCraterTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_craters";
    private static final int TIMEOUT = 600;
    /** The pad's half-width and its layers (below and above the lane's ground level). */
    private static final int PAD = 6;
    private static final int PAD_BOTTOM = -3;
    private static final int PAD_TOP = 1;
    /** How high above the pad the bodies are dropped from. */
    private static final double DROP = 30.0;

    private ImpactCraterTests() {
    }

    /**
     * A giant (3.6 blocks wide, 1200 kg by Camafy's weights) dropped 30 blocks onto dirt: the whole
     * surface layer under its feet goes (a 4x4 at least, 5x5 when it straddles the block grid), and
     * the crater record says so; with the energy such a fall carries a narrower layer or two below
     * it go too.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aGiantDroppedThirtyBlocksLeavesAHoleTheWidthOfItsFeet(GameTestHelper helper) {
        BlockPos o = lane(helper, 0);
        ServerLevel level = helper.getLevel();
        Pad pad = new Pad(level, o);
        LivingEntity[] body = new LivingEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> body[0] = giant(level, air(o, 0.0, PAD_TOP + 1 + DROP, 0.0)))
                .thenWaitUntil(() -> helper.assertTrue(landed(body[0], o), "the giant is still falling"))
                .thenExecuteAfter(20, () -> {
                    LivingEntity giant = body[0];
                    KineticBlockInteractions.Crater crater = KineticBlockInteractions.lastCrater(giant);
                    int surface = pad.missingInSurface(giant);
                    int footprint = pad.footprintColumns(giant);
                    report("a giant dropped 30 blocks onto dirt", String.format(Locale.ROOT,
                            "mass %.1f units, footprint %d columns, %d of them opened at the surface, %d dirt blocks gone in all; crater: %s",
                            EntityStackUtils.getEntityMass(giant), footprint, surface, pad.missing(), describe(crater)));
                    helper.assertTrue(crater != null && crater.blocks() > 0,
                            "the giant dug no crater (" + describe(crater) + ")");
                    helper.assertTrue(surface >= Math.min(16, footprint),
                            "only " + surface + " of the " + footprint + " surface blocks under the giant's feet are gone");
                    helper.assertTrue(crater.blocks() >= footprint,
                            "the crater broke " + crater.blocks() + " blocks, fewer than the " + footprint + "-block footprint");
                    giant.discard();
                })
                .thenSucceed();
    }

    /**
     * A pig (0.9 blocks wide) dropped the same way digs no crater: it is narrower than a block, so its
     * rays' hits are its whole footprint and the per-ray breaking is as before.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigDroppedThirtyBlocksDigsNoCrater(GameTestHelper helper) {
        BlockPos o = lane(helper, 1);
        ServerLevel level = helper.getLevel();
        Pad pad = new Pad(level, o);
        LivingEntity[] body = new LivingEntity[1];
        whenTicking(helper, o)
                .thenExecute(() -> body[0] = pig(level, air(o, 0.0, PAD_TOP + 1 + DROP, 0.0)))
                .thenWaitUntil(() -> helper.assertTrue(landed(body[0], o), "the pig is still falling"))
                .thenExecuteAfter(20, () -> {
                    LivingEntity pig = body[0];
                    KineticBlockInteractions.Crater crater = KineticBlockInteractions.lastCrater(pig);
                    report("a pig dropped 30 blocks onto dirt", String.format(Locale.ROOT,
                            "mass %.2f units, %d dirt blocks gone (the rays' own breaking); crater: %s",
                            EntityStackUtils.getEntityMass(pig), pad.missing(), describe(crater)));
                    helper.assertTrue(crater == null, "a pig dug a crater: " + describe(crater));
                    pig.discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // Rigs
    // ------------------------------------------------------------------

    /** A lane of its own for each test, clear of the other tests' lanes. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        return ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (140 + lane) * 256);
    }

    private static Vec3 air(BlockPos o, double dx, double dy, double dz) {
        return new Vec3(o.getX() + 0.5 + dx, o.getY() + dy, o.getZ() + 0.5 + dz);
    }

    /** A sequence that starts once the lane ticks entities (the test sees nothing move before that). */
    private static GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(o.above(10)) && helper.getLevel().isPositionEntityTicking(o.above(40)),
                "the lane doesn't tick entities yet"));
    }

    /** The body's feet reached the pad (or it is gone). */
    private static boolean landed(LivingEntity body, BlockPos o) {
        return body == null || !body.isAlive() || body.onGround() || body.getY() <= o.getY() + PAD_TOP + 1.01;
    }

    /** A giant that survives the landing (its health is what stops a crater: an impact only breaks blocks for a live body). */
    private static LivingEntity giant(ServerLevel level, Vec3 at) {
        Mob giant = EntityType.GIANT.create(level);
        if (giant == null) throw new IllegalStateException("no giant");
        return drop(level, giant, at);
    }

    private static LivingEntity pig(ServerLevel level, Vec3 at) {
        Mob pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        return drop(level, pig, at);
    }

    private static LivingEntity drop(ServerLevel level, Mob mob, Vec3 at) {
        mob.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        mob.goalSelector.removeAllGoals(g -> true);
        mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10000.0);
        mob.setHealth(10000.0f);
        level.addFreshEntity(mob);
        return mob;
    }

    private static String describe(KineticBlockInteractions.Crater crater) {
        if (crater == null) return "none";
        return String.format(Locale.ROOT, "%d blocks in %d layer(s) under a %d-block footprint, %.2f energy left, %.2f graze",
                crater.blocks(), crater.layers(), crater.footprint(), crater.energyLeft(), crater.smashDamage());
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }

    /** A pad of dirt around the lane's origin, and what is missing from it after the landing. */
    private static final class Pad {
        final ServerLevel level;
        final BlockPos min;
        final BlockPos max;
        final int surfaceY;
        final int before;

        Pad(ServerLevel level, BlockPos o) {
            this.level = level;
            this.min = o.offset(-PAD, PAD_BOTTOM, -PAD);
            this.max = o.offset(PAD, PAD_TOP, PAD);
            this.surfaceY = o.getY() + PAD_TOP;
            ShipCollisionTests.fill(level, min, max, Blocks.DIRT.defaultBlockState());
            this.before = count(Blocks.DIRT);
        }

        int count(Block block) {
            int n = 0;
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (level.getBlockState(pos).is(block)) n++;
            }
            return n;
        }

        int missing() {
            return before - count(Blocks.DIRT);
        }

        /** The surface blocks under the body's box (the columns its feet cover) that are gone. */
        int missingInSurface(LivingEntity body) {
            int n = 0;
            for (BlockPos pos : footprint(body)) {
                if (!level.getBlockState(pos).is(Blocks.DIRT)) n++;
            }
            return n;
        }

        int footprintColumns(LivingEntity body) {
            return footprint(body).size();
        }

        private java.util.List<BlockPos> footprint(LivingEntity body) {
            var box = body.getBoundingBox();
            int x0 = (int) Math.floor(box.minX + 1.0e-4), x1 = (int) Math.floor(box.maxX - 1.0e-4);
            int z0 = (int) Math.floor(box.minZ + 1.0e-4), z1 = (int) Math.floor(box.maxZ - 1.0e-4);
            java.util.List<BlockPos> columns = new java.util.ArrayList<>();
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    columns.add(new BlockPos(x, surfaceY, z));
                }
            }
            return columns;
        }
    }
}
