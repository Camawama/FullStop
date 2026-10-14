package net.camacraft.fullstop.common.compat.create.kinetic;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Create's flywheel and bare shafts in Create Catastrophes (the owner, 2026-10-12: "it appears the flywheel from create
 * isn't dangerous at all. Additionally, standing on a spinning shaft should push the player or entity in the direction it's
 * turning"), against the real Create 6.0.8, with pigs of 1000 health. A flywheel's rim (1.37 from its axis) acts as a
 * single gear's teeth by its spin; a bare shaft drags what stands on it along its turning surface. Run:
 * {@code gradlew runGameTestServer -PshipTests -Pcreate -PtestOnly=FlywheelShaftHazardTests -PgameTestDir=run-gametest-create}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class FlywheelShaftHazardTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_create_flywheel_shaft";
    private static final float HEALTH = 1000.0F;

    private FlywheelShaftHazardTests() {
    }

    /**
     * A pig pressed against a flywheel's rim where it runs up, the wheel at 16, 64 and 256 RPM (rim 2.3, 9.2 and 36.7
     * m/s): 16 neither bites hard nor throws, 64 bites (about gearTouchDamage 2 x 9.2 / 7.5) without throwing, 256 flings
     * it; the bites are the flywheel's own ({@code fullstop.flywheel}).
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 500)
    public static void aSpinningFlywheelsRimBitesAndFlings(GameTestHelper helper) {
        int[] rpms = {16, 64, 256};
        int[] xs = {4, 16, 28};
        Pig[] pigs = new Pig[3];
        Vec3[] startAt = new Vec3[3];
        double[] flywheelHurt = new double[3];
        double[] rose = new double[3];
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.living.LivingHurtEvent e) -> {
            if (!e.getSource().getMsgId().equals("fullstop.flywheel")) return;
            for (int i = 0; i < pigs.length; i++) if (pigs[i] != null && e.getEntity() == pigs[i]) flywheelHurt[i] += e.getAmount();
        });
        for (int x : xs) flywheelRig(helper, x, 8);
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    for (int i = 0; i < 3; i++) motor(helper, new BlockPos(xs[i], 3, 8), rpms[i]);
                })
                // Camafy's real flywheel spins up over a few seconds (a plain Create one turns with its shaft at once)
                .thenExecuteAfter(140, () -> {
                    for (int i = 0; i < 3; i++) {
                        Hazard.Live live = HazardScanner.resolveAny(helper.getLevel(), new Hazard(Hazard.Kind.FLYWHEEL,
                                helper.absolutePos(new BlockPos(xs[i] + 1, 3, 8)), Hazard.NO_SHIP));
                        helper.assertTrue(live != null && Math.abs(live.rpm()) > 0.9 * rpms[i], "the flywheel at x " + xs[i]
                                + " doesn't turn at " + rpms[i] + ": " + (live == null ? "none" : live.rpm()));
                        // the side where the rim runs up: about +x a positive speed runs it up on the -z side
                        int side = live.rpm() > 0 ? -1 : 1;
                        pigs[i] = pig(helper, new Vec3(xs[i] + 1.5, 2.0, 8.5 + side * 1.25));
                        pigs[i].addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 9, false, false));
                        startAt[i] = pigs[i].position();
                    }
                })
                .thenExecuteFor(40, () -> {
                    for (int i = 0; i < 3; i++) rose[i] = Math.max(rose[i], pigs[i].getY() - startAt[i].y);
                })
                .thenExecute(() -> {
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < 3; i++) {
                        out.append(String.format(Locale.ROOT, "%d RPM: flung at %.1f m/s, rose %.1f, moved %.1f, flywheel bites %.1f; ",
                                rpms[i], KineticHazards.lastFlingMps(pigs[i]), rose[i],
                                Math.hypot(pigs[i].getX() - startAt[i].x, pigs[i].getZ() - startAt[i].z), flywheelHurt[i]));
                    }
                    report("a pig against a flywheel's rim", out.toString());
                    helper.assertTrue(KineticHazards.lastFlingMps(pigs[0]) == 0.0, "a 16 RPM flywheel threw the pig: " + out);
                    helper.assertTrue(KineticHazards.lastFlingMps(pigs[1]) == 0.0 && flywheelHurt[1] > 1.0,
                            "a 64 RPM flywheel didn't bite (or threw) the pig: " + out);
                    helper.assertTrue(KineticHazards.lastFlingMps(pigs[2]) > 20.0, "a 256 RPM flywheel didn't fling the pig: " + out);
                    for (Pig pig : pigs) pig.discard();
                })
                .thenSucceed();
    }

    /**
     * A pig standing on a bare shaft (axis x) turning at 64 and 256 RPM is carried off it across the shaft the way its top
     * surface moves (+z for a positive speed about +x), faster on the faster shaft; one on a still shaft stays on it.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 300)
    public static void aSpinningShaftCarriesWhatStandsOnIt(GameTestHelper helper) {
        int[] rpms = {0, 64, 256};
        int[] xs = {4, 16, 28};
        Pig[] pigs = new Pig[3];
        Vec3[] startAt = new Vec3[3];
        double[] expectedSign = new double[3];
        for (int x : xs) shaftRig(helper, x, 20);
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    for (int i = 0; i < 3; i++) motor(helper, new BlockPos(xs[i], 2, 20), rpms[i]);
                })
                .thenExecuteAfter(10, () -> {
                    for (int i = 0; i < 3; i++) {
                        Hazard.Live live = HazardScanner.resolveAny(helper.getLevel(), new Hazard(Hazard.Kind.SHAFT,
                                helper.absolutePos(new BlockPos(xs[i] + 2, 2, 20)), Hazard.NO_SHIP));
                        helper.assertTrue(live != null, "no shaft at x " + (xs[i] + 2));
                        expectedSign[i] = Math.signum(live.rpm());
                        pigs[i] = pig(helper, new Vec3(xs[i] + 2.5, 2.7, 20.5));
                        pigs[i].addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 9, false, false));
                        pigs[i].setYRot(90.0F);
                        startAt[i] = pigs[i].position();
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    double[] dz = new double[3];
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < 3; i++) {
                        dz[i] = pigs[i].getZ() - startAt[i].z;
                        out.append(String.format(Locale.ROOT, "%d RPM (sign %+.0f): moved %.2f across, %.2f along, fell %.2f; ",
                                rpms[i], expectedSign[i], dz[i], pigs[i].getX() - startAt[i].x, startAt[i].y - pigs[i].getY()));
                    }
                    report("a pig standing on a shaft", out.toString());
                    helper.assertTrue(Math.abs(dz[0]) < 0.1, "a still shaft moved the pig: " + out);
                    helper.assertTrue(dz[1] * expectedSign[1] > 0.3, "a 64 RPM shaft didn't carry the pig its way: " + out);
                    helper.assertTrue(dz[2] * expectedSign[2] > dz[1] * expectedSign[1], "a 256 RPM shaft didn't carry it further: " + out);
                    for (Pig pig : pigs) pig.discard();
                })
                .thenSucceed();
    }

    /** A stone pad, a creative motor at (x, 3, z) facing east into a flywheel (axis x) at (x+1, 3, z): its rim clears the pad. */
    private static void flywheelRig(GameTestHelper helper, int x, int z) {
        floor(helper, x - 2, x + 4, z - 3, z + 4);
        helper.setBlock(new BlockPos(x, 3, z), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(DirectionalKineticBlock.FACING, Direction.EAST));
        motor(helper, new BlockPos(x, 3, z), 0);
        helper.setBlock(new BlockPos(x + 1, 3, z), AllBlocks.FLYWHEEL.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
    }

    /** A stone pad, a creative motor at (x, 2, z) facing east into four shafts (axis x) lying on it. */
    private static void shaftRig(GameTestHelper helper, int x, int z) {
        floor(helper, x - 2, x + 6, z - 3, z + 3);
        helper.setBlock(new BlockPos(x, 2, z), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(DirectionalKineticBlock.FACING, Direction.EAST));
        motor(helper, new BlockPos(x, 2, z), 0);
        for (int i = 1; i <= 4; i++) {
            helper.setBlock(new BlockPos(x + i, 2, z), AllBlocks.SHAFT.getDefaultState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
        }
    }

    private static void floor(GameTestHelper helper, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
        }
    }

    private static void motor(GameTestHelper helper, BlockPos pos, int rpm) {
        ((CreativeMotorBlockEntity) helper.getBlockEntity(pos)).generatedSpeed.setValue(rpm);
    }

    private static Pig pig(GameTestHelper helper, Vec3 relative) {
        Pig pig = helper.spawn(EntityType.PIG, relative);
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
        pig.setHealth(HEALTH);
        pig.setPersistenceRequired();
        return pig;
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop][kinetic] {}: {}", what, result);
    }
}
