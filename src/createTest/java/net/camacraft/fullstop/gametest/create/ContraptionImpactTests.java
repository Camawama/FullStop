package net.camacraft.fullstop.gametest.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionImpacts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Create contraptions striking bodies like Valkyrien Skies hulls do (FullStop's {@code contraptionImpacts}),
 * with the real Create 6.0.8: a creative motor drives a mechanical bearing facing up, whose glued arm of planks
 * sweeps a horizontal circle at body height; a pig stands in its path (no goals, so it doesn't walk away, but
 * moves when pushed). Opt-in: {@code gradlew runGameTestServer -Pcreate}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ContraptionImpactTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "create_flat_48x8x48";
    private static final String BATCH = "fullstop_create_contraptions";
    private static final int TIMEOUT = 400;
    private static final float HEALTH = 1000.0f;
    /** The arm reaches this many blocks out from the bearing's axis block. */
    private static final int ARM = 4;
    /** Where the pig stands: this far from the bearing's axis (block centres), across the arm's sweep. */
    private static final double PIG_RADIUS = 3.5;

    private ContraptionImpactTests() {
    }

    /** 16 RPM: the arm meets the pig at about 6 m/s, just a strike (over gentlePushSpeed): knocked away, barely hurt. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aBearingArmAt16RpmKnocksAPigAway(GameTestHelper helper) {
        sweep(helper, 0, 16, (pig, struck, start) -> {
            helper.assertTrue(struck.approach() > 4.0 && struck.approach() < 9.0,
                    String.format(Locale.ROOT, "16 RPM at %.1f blocks closed at %.2f m/s", PIG_RADIUS, struck.approach()));
            helper.assertTrue(struck.pushMps().length() > 4.0, "the pig wasn't knocked away: " + struck.pushMps());
        });
    }

    /** 64 RPM: about 23 m/s at the pig: hurt like a hard impact and thrown clear. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aBearingArmAt64RpmHurtsAndFlingsAPig(GameTestHelper helper) {
        sweep(helper, 1, 64, (pig, struck, start) -> {
            helper.assertTrue(struck.approach() > 15.0 && struck.approach() < 32.0,
                    String.format(Locale.ROOT, "64 RPM closed at %.2f m/s", struck.approach()));
            helper.assertTrue(lost(pig) > 1.0, String.format(Locale.ROOT, "the pig lost only %.1f health", lost(pig)));
            helper.assertTrue(struck.pushMps().length() > 20.0, "the pig wasn't flung: " + struck.pushMps());
        });
    }

    /**
     * 256 RPM: the arm turns 77 degrees a tick (4.3 blocks of arc at the pig) and still doesn't pass through it
     * (the swept test): about 94 m/s, a very hard hit.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aBearingArmAt256RpmNeverPassesThroughAPig(GameTestHelper helper) {
        sweep(helper, 2, 256, (pig, struck, start) -> {
            helper.assertTrue(struck.approach() > 60.0,
                    String.format(Locale.ROOT, "256 RPM closed at only %.2f m/s", struck.approach()));
            helper.assertTrue(lost(pig) > 10.0 || !pig.isAlive(),
                    String.format(Locale.ROOT, "the pig lost only %.1f health", lost(pig)));
        });
    }

    /** A pig standing on a plate the bearing turns at 32 RPM rides it: never struck, never hurt. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigStandingOnATurningPlateIsCarriedNotHit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 3);
        BlockPos bearing = rig(level, o);
        BlockPos top = bearing.above();
        // a 5x5 plate on the bearing, glued
        fill(level, top.offset(-2, 0, -2), top.offset(2, 0, 2), Blocks.OAK_PLANKS.defaultBlockState());
        glue(level, top.offset(-2, 0, -2), top.offset(2, 0, 2));
        LivingEntity[] pig = new LivingEntity[1];
        long[] t0 = new long[1];
        whenTicking(helper, o)
                .thenExecute(() -> {
                    pig[0] = pig(level, new Vec3(top.getX() + 1.5, top.getY() + 1.0, top.getZ() + 0.5));
                    speed(level, o, 32);
                })
                .thenWaitUntil(() -> helper.assertTrue(contraption(level, top) != null, "the plate hasn't assembled"))
                .thenExecute(() -> t0[0] = level.getGameTime())
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - t0[0] >= 100, "riding"))
                .thenExecute(() -> {
                    ContraptionImpacts.Struck struck = ContraptionImpacts.lastStruck(pig[0]);
                    report("pig on a 32 RPM plate", struck == null ? "never struck" : struck.toString());
                    helper.assertTrue(struck == null, "the pig riding the plate was struck: " + struck);
                    helper.assertTrue(lost(pig[0]) < 0.5, String.format(Locale.ROOT, "the riding pig lost %.1f health", lost(pig[0])));
                })
                .thenSucceed();
    }

    /**
     * With contraptionImpacts off, a 64 RPM arm only shoves the pig as Create does: no strike, no damage. In a batch
     * of its own: it turns the setting off while it runs.
     */
    @GameTest(template = FLAT, batch = BATCH + "_off", timeoutTicks = TIMEOUT)
    public static void withContraptionImpactsOffCreateOnlyShoves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 4);
        BlockPos bearing = rig(level, o);
        arm(level, bearing.above());
        LivingEntity[] pig = new LivingEntity[1];
        long[] t0 = new long[1];
        boolean was = net.camacraft.fullstop.FullStopConfig.SERVER.contraptionImpacts.get();
        whenTicking(helper, o)
                .thenExecute(() -> {
                    net.camacraft.fullstop.FullStopConfig.SERVER.contraptionImpacts.set(false);
                    pig[0] = pig(level, pigSpot(bearing.above()));
                    speed(level, o, 64);
                })
                .thenWaitUntil(() -> helper.assertTrue(contraption(level, bearing.above()) != null, "the arm hasn't assembled"))
                .thenExecute(() -> t0[0] = level.getGameTime())
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - t0[0] >= 60, "turning"))
                .thenExecute(() -> {
                    net.camacraft.fullstop.FullStopConfig.SERVER.contraptionImpacts.set(was);
                    ContraptionImpacts.Struck struck = ContraptionImpacts.lastStruck(pig[0]);
                    report("64 RPM with contraptionImpacts off", struck == null ? "no strike, lost " + lost(pig[0]) : struck.toString());
                    helper.assertTrue(struck == null, "struck with contraptionImpacts off: " + struck);
                })
                .thenSucceed();
    }

    /**
     * A mechanical piston at full speed (0.49 blocks a tick, 9.8 m/s) rams a pig standing in the open: a strike,
     * the pig knocked on ahead of the ram (Create alone would only shove it along at the ram's speed).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aMechanicalPistonRamKnocksAPigAhead(GameTestHelper helper) {
        ram(helper, 5, false, (pig, struck, start) -> {
            helper.assertTrue(struck.approach() > 7.0 && struck.approach() < 12.0,
                    String.format(Locale.ROOT, "the ram closed at %.2f m/s", struck.approach()));
            helper.assertTrue(!struck.pinned(), "a pig in the open counted as pinned");
            helper.assertTrue(struck.pushMps().x > 9.0, "the pig wasn't knocked on ahead of the ram: " + struck.pushMps());
        });
    }

    /** The same ram with a wall right behind the pig: crushed against it (pinned), no knock-away. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aMechanicalPistonRamCrushesAPigAgainstAWall(GameTestHelper helper) {
        ram(helper, 6, true, (pig, struck, start) -> {
            helper.assertTrue(struck.pinned(), "the pig against the wall wasn't pinned: " + struck);
            helper.assertTrue(struck.pushMps().lengthSqr() == 0.0, "a pinned pig was pushed: " + struck.pushMps());
        });
    }

    /** A mechanical piston (facing east, three poles) driven at full speed toward a pig; a wall behind it if asked. */
    private static void ram(GameTestHelper helper, int lane, boolean wall, Check check) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        fill(level, o.offset(-7, 0, -3), o.offset(7, 4, 3), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-6, 0, -2), o.offset(7, 0, 2), Blocks.STONE.defaultBlockState());
        BlockPos piston = o.above();
        level.setBlock(o, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        speed(level, o, 0);
        level.setBlock(piston, AllBlocks.MECHANICAL_PISTON.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST)
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true),
                Block.UPDATE_ALL);
        for (int i = 1; i <= 3; i++) {
            level.setBlock(piston.west(i), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState()
                    .setValue(BlockStateProperties.FACING, Direction.EAST), Block.UPDATE_ALL);
        }
        level.setBlock(piston.east(), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        if (wall) fill(level, piston.east(3), piston.east(3).above(2), Blocks.STONE.defaultBlockState());
        LivingEntity[] pig = new LivingEntity[1];
        Vec3[] start = new Vec3[1];
        // the pig's back 0.05 from the wall's face when there is one; the ram meets it after a block of travel
        double pigX = wall ? piston.east(3).getX() - 0.5 : piston.east(2).getX() + 0.5;
        whenTicking(helper, o)
                .thenExecute(() -> {
                    start[0] = new Vec3(pigX, piston.getY(), piston.getZ() + 0.5);
                    pig[0] = pig(level, start[0]);
                })
                .thenIdle(5)
                // facing east, a negative speed extends it
                .thenExecute(() -> speed(level, o, -256))
                .thenWaitUntil(() -> {
                    ContraptionImpacts.Struck struck = ContraptionImpacts.lastHit(pig[0]);
                    helper.assertTrue(struck != null, "the ram hasn't struck the pig yet");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    ContraptionImpacts.Struck struck = ContraptionImpacts.lastHit(pig[0]);
                    report("piston ram" + (wall ? " against a wall" : ""), String.format(Locale.ROOT,
                            "%s; lost %.1f health, moved %.2f blocks", struck, lost(pig[0]), pig[0].position().distanceTo(start[0])));
                    check.accept(pig[0], struck, start[0]);
                })
                .thenSucceed();
    }

    /**
     * Create's own train run-over damage stands down while trains are judged as contraption hits: the mixin's handler
     * is merged into Create's collider (its injection is optional, so a Create update that renames the method would
     * otherwise go unnoticed).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 20)
    public static void createsTrainDamageStandsDown(GameTestHelper helper) {
        boolean merged = false;
        for (java.lang.reflect.Method m : com.simibubi.create.content.contraptions.ContraptionCollider.class.getDeclaredMethods()) {
            if (m.getName().contains("trainsAreContraptionHits")) merged = true;
        }
        helper.assertTrue(merged, "TrainDamageMixin isn't applied to Create's ContraptionCollider");
        helper.assertTrue(ContraptionImpacts.replacesTrainDamage(), "trains aren't judged as contraption hits with the defaults");
        helper.succeed();
    }

    // ---- the rig --------------------------------------------------------------------------------------------

    interface Check {
        void accept(LivingEntity pig, ContraptionImpacts.Struck struck, Vec3 start);
    }

    /** A bearing arm at {@code rpm} sweeping through a pig; {@code check} once the pig has been struck. */
    private static void sweep(GameTestHelper helper, int lane, int rpm, Check check) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, lane);
        BlockPos bearing = rig(level, o);
        arm(level, bearing.above());
        LivingEntity[] pig = new LivingEntity[1];
        Vec3[] start = new Vec3[1];
        whenTicking(helper, o)
                .thenExecute(() -> {
                    start[0] = pigSpot(bearing.above());
                    pig[0] = pig(level, start[0]);
                })
                .thenIdle(5)
                .thenExecute(() -> speed(level, o, rpm))
                .thenWaitUntil(() -> {
                    ContraptionImpacts.Struck struck = ContraptionImpacts.lastHit(pig[0]);
                    helper.assertTrue(struck != null, rpm + " RPM: the arm hasn't struck the pig yet");
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    ContraptionImpacts.Struck struck = ContraptionImpacts.lastHit(pig[0]);
                    report(rpm + " RPM arm", String.format(Locale.ROOT,
                            "%s; lost %.1f health, moved %.2f blocks; stats %s", struck, lost(pig[0]),
                            pig[0].position().distanceTo(start[0]), ContraptionImpacts.stats(level)));
                    check.accept(pig[0], struck, start[0]);
                })
                .thenSucceed();
    }

    /** Where the pig stands: {@link #PIG_RADIUS} from the arm's axis along +Z, on the platform. */
    private static Vec3 pigSpot(BlockPos armBase) {
        return new Vec3(armBase.getX() + 0.5, armBase.getY(), armBase.getZ() + 0.5 + PIG_RADIUS);
    }

    /**
     * A creative motor at {@code o} facing up, a mechanical bearing on it facing up, and a stone platform round the
     * bearing at its height (bodies stand on it, at the arm's height). Returns the bearing's position.
     */
    private static BlockPos rig(ServerLevel level, BlockPos o) {
        fill(level, o.offset(-7, 0, -7), o.offset(7, 4, 7), Blocks.AIR.defaultBlockState());
        fill(level, o.offset(-7, 1, -7), o.offset(7, 1, 7), Blocks.STONE.defaultBlockState());
        level.setBlock(o, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        BlockPos bearing = o.above();
        level.setBlock(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP),
                Block.UPDATE_ALL);
        speed(level, o, 0);
        return bearing;
    }

    /** A glued arm of planks from the bearing's front block out along +X. */
    private static void arm(ServerLevel level, BlockPos base) {
        fill(level, base, base.offset(ARM, 0, 0), Blocks.OAK_PLANKS.defaultBlockState());
        glue(level, base, base.offset(ARM, 0, 0));
    }

    private static void glue(ServerLevel level, BlockPos from, BlockPos to) {
        AABB box = new AABB(from).minmax(new AABB(to));
        level.addFreshEntity(new SuperGlueEntity(level, box));
    }

    private static void speed(ServerLevel level, BlockPos motor, int rpm) {
        if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity be) {
            be.generatedSpeed.setValue(rpm);
        }
    }

    /** The contraption the bearing has assembled above it, or null. */
    private static AbstractContraptionEntity contraption(ServerLevel level, BlockPos front) {
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(AbstractContraptionEntity.class, new AABB(front).inflate(2.0))) {
            if (c.isAlive()) return c;
        }
        return null;
    }

    /** A lane of its own for each rig, far from the other suites' lanes. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        ServerLevel level = helper.getLevel();
        BlockPos plot = helper.absolutePos(BlockPos.ZERO);
        int groundTop = level.getMinBuildHeight() + 4; // the flat world: bedrock, dirt, dirt, grass
        // a fixed place per lane, far from every test plot: plots move about as batches are added, and lanes that
        // followed them ran into each other (a gantry lane clearing a cart lane)
        BlockPos o = new BlockPos(-20000 - lane * 96, groundTop, 20000);
        for (int cx = (o.getX() - 16) >> 4; cx <= (o.getX() + 16) >> 4; cx++) {
            for (int cz = (o.getZ() - 16) >> 4; cz <= (o.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        return o;
    }

    private static net.minecraft.gametest.framework.GameTestSequence whenTicking(GameTestHelper helper, BlockPos o) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isPositionEntityTicking(o.above(2)), "the lane doesn't tick entities yet"));
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }
    }

    private static double lost(LivingEntity pig) {
        return pig == null ? 0.0 : pig.getMaxHealth() - pig.getHealth();
    }

    private static LivingEntity pig(ServerLevel level, Vec3 at) {
        Mob pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        pig.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        pig.goalSelector.removeAllGoals(g -> true);
        pig.setPersistenceRequired();
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
        pig.setHealth(HEALTH);
        level.addFreshEntity(pig);
        return pig;
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
