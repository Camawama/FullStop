package net.camacraft.fullstop.gametest;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.registry.ModEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.fill;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fmt;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.report;

/**
 * Zero and low gravity, without ships (the ship cases are in ShipCollisionTests and
 * ShipBodyTests). VS Genesis puts every entity in its space dimensions on the no-gravity flag,
 * so that flag is the stand-in for space here; Ad Astra's Moon pulls a sixth, stood in for by
 * the forge:entity_gravity attribute at 0.08 / 6 (Camafy reads both).
 *
 * <ul>
 *   <li>A weightless body bumping into a floor is no fall: plain kinetic damage over the
 *   horizontal (wall) threshold, no Feather Falling, no sprain.</li>
 *   <li>A Moon landing is as gentle as its speed: the same drop lands far slower and hurts
 *   nothing.</li>
 *   <li>The g-force a weightless body feels is its acceleration alone (no 1 g baseline), measured
 *   and seated.</li>
 * </ul>
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ZeroGravityTests {

    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_zero_gravity";
    /** FullStopCapability.GFORCE_NOISE_FLOOR: felt acceleration below this (m/s per tick) reads as none. */
    private static final double NOISE_FLOOR = 2.0;
    private static final double MOON = 1.0 / 6.0;

    private ZeroGravityTests() {
    }

    /** A lane of its own for each test, clear of the ship tests' lanes. */
    private static BlockPos lane(GameTestHelper helper, int lane) {
        return ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (64 + lane) * 256);
    }

    /**
     * Two villagers in Feather Falling IV diamond boots driven feet first into a stone floor at 20
     * m/s from two blocks up: one weightless, one under normal gravity. The weightless one takes
     * plain kinetic damage (not #minecraft:is_fall), over the 10.5 m/s wall threshold rather than
     * the 12.77 fall one, without Feather Falling (well over twice what the faller takes), and no
     * sprain. The faller lands as before: fall damage, Feather Falling, a sprain.
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void aWeightlessBodyBumpingAFloorIsNotAFall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 0);
        fill(level, o, o.offset(8, 0, 4), Blocks.STONE.defaultBlockState());
        Body floating = new Body(bootedVillager(level, new Vec3(o.getX() + 2.5, o.getY() + 3.0, o.getZ() + 2.5)));
        Body falling = new Body(bootedVillager(level, new Vec3(o.getX() + 6.5, o.getY() + 3.0, o.getZ() + 2.5)));
        floating.villager.setNoGravity(true);
        for (Body body : new Body[]{floating, falling}) {
            body.villager.setDeltaMovement(0.0, -1.0, 0.0);
            body.villager.hasImpulse = true;
        }
        helper.onEachTick(() -> {
            floating.record();
            falling.record();
        });
        helper.runAfterDelay(30, () -> {
            boolean floatingSprained = floating.sprained;
            boolean fallingSprained = falling.sprained;
            String out = String.format(Locale.ROOT, "weightless villager: %s, sprained %s, %.2f m/s at most; falling villager: %s, "
                            + "sprained %s, %.2f m/s at most", floating.describe(), floatingSprained, floating.fastest,
                    falling.describe(), fallingSprained, falling.fastest);
            report("feet first into stone at 20 m/s, Feather Falling IV boots, weightless vs falling", out);
            helper.assertTrue(floating.lost > 0.0 && floating.source != null, "the weightless villager wasn't hurt: " + out);
            helper.assertFalse(floating.source.is(DamageTypeTags.IS_FALL), "a weightless bump was fall damage: " + out);
            helper.assertFalse(floatingSprained, "a weightless bump sprained the villager: " + out);
            helper.assertTrue(falling.lost > 0.0 && falling.source != null && falling.source.is(DamageTypeTags.IS_FALL),
                    "the falling villager didn't take fall damage: " + out);
            helper.assertTrue(fallingSprained, "the falling villager wasn't sprained: " + out);
            helper.assertTrue(floating.lost > 2.0 * falling.lost, "Feather Falling still softened the weightless bump: " + out);
            floating.villager.discard();
            falling.villager.discard();
            helper.succeed();
        });
    }

    /**
     * Two villagers dropped 12 blocks onto stone: one under normal gravity, one under the Moon's (a
     * sixth). The Moon villager lands at about a third of the speed (under 0.6 of it) and takes
     * nothing; the other one takes its fall damage. Camafy reads the Moon one's gravity as a sixth.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 200)
    public static void moonGravityLandingsAreGentler(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 1);
        fill(level, o, o.offset(8, 0, 4), Blocks.STONE.defaultBlockState());
        Body earth = new Body(villager(level, new Vec3(o.getX() + 2.5, o.getY() + 13.0, o.getZ() + 2.5)));
        Body moon = new Body(villager(level, new Vec3(o.getX() + 6.5, o.getY() + 13.0, o.getZ() + 2.5)));
        moonGravity(moon.villager);
        helper.onEachTick(() -> {
            earth.record();
            moon.record();
        });
        helper.runAfterDelay(120, () -> {
            FullStopCapability cap = FullStopCapability.grabCapability(moon.villager);
            double strength = cap == null ? Double.NaN : cap.getGravityStrength();
            String out = String.format(Locale.ROOT, "earth: %s, landed at %.2f m/s; moon (gravity read %.4f): %s, landed at %.2f m/s",
                    earth.describe(), earth.fastest, strength, moon.describe(), moon.fastest);
            report("12-block drop onto stone, normal vs Moon gravity", out);
            helper.assertTrue(Math.abs(strength - MOON) < 0.005, "Camafy doesn't read the Moon villager's gravity: " + out);
            helper.assertTrue(earth.lost > 0.0 && earth.source != null && earth.source.is(DamageTypeTags.IS_FALL),
                    "the earth villager took no fall damage: " + out);
            helper.assertTrue(moon.fastest > 2.0 && moon.fastest < 0.6 * earth.fastest, "the Moon villager didn't land gently: " + out);
            helper.assertTrue(moon.lost == 0.0, "the Moon landing hurt: " + out);
            earth.villager.discard();
            moon.villager.discard();
            helper.succeed();
        });
    }

    /**
     * The g-force of weightless, Moon and normal-gravity villagers (not in the world: their
     * FullStop state is ticked by hand, 300 ticks each), from their measured motion (a circle
     * at 10 m/s, 3 m/s per tick toward its centre) and seated on a ship whose seat accelerates at
     * 60 m/s^2 sideways. Weightless, the reading is the acceleration alone, less the noise floor;
     * under gravity the 1 g everyone feels at rest is taken out of the felt acceleration, as
     * before (a body held up on a level circle feels the circle and its weight).
     */
    @GameTest(template = FLAT, batch = BATCH)
    public static void gForceInZeroGravityIsThePureAcceleration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 2);
        Vec3 centre = new Vec3(o.getX() + 0.5, o.getY() + 20.0, o.getZ() + 0.5);
        double r = 5.0 / 3.0;
        double w = 0.3;
        // the circle's acceleration as the positions give it, m/s per tick
        double circle = 2.0 * r * (1.0 - Math.cos(w)) * 20.0;
        double seat = 60.0 * 0.05;
        double earthG = 0.08 * 20.0;
        double earthSeatG = 9.81 * 0.05;

        double[] measured = new double[3];
        double[] seated = new double[3];
        for (int kind = 0; kind < 3; kind++) {
            Villager moving = offWorldVillager(level, centre, kind);
            FullStopCapability cap = FullStopCapability.grabCapability(moving);
            helper.assertTrue(cap != null, "no FullStop state on a villager");
            for (int i = 0; i < 300; i++) {
                moving.tickCount++;
                moving.setPos(centre.x + r * Math.cos(w * i), centre.y, centre.z + r * Math.sin(w * i));
                cap.tick(moving);
            }
            measured[kind] = cap.getRunningAverageDelta();

            Villager sitting = offWorldVillager(level, centre, kind);
            FullStopCapability seatCap = FullStopCapability.grabCapability(sitting);
            helper.assertTrue(seatCap != null, "no FullStop state on a villager");
            for (int i = 0; i < 300; i++) {
                sitting.tickCount++;
                seatCap.setSeatAcceleration(sitting, new Vec3(60.0, 0.0, 0.0));
                seatCap.tick(sitting);
            }
            seated[kind] = seatCap.getRunningAverageDelta();
        }
        double[] expectMeasured = {
                circle - NOISE_FLOOR,
                Math.max(Math.hypot(circle, earthG * MOON) - earthG * MOON - NOISE_FLOOR, 0.0),
                Math.max(Math.hypot(circle, earthG) - earthG - NOISE_FLOOR, 0.0)};
        double[] expectSeated = {
                seat - NOISE_FLOOR,
                Math.hypot(seat, earthSeatG * MOON) - earthSeatG * MOON - NOISE_FLOOR,
                Math.hypot(seat, earthSeatG) - earthSeatG - NOISE_FLOOR};
        String out = String.format(Locale.ROOT, "circle (%.2f m/s per tick): weightless %.3f (expected %.3f), Moon %.3f (%.3f), "
                        + "normal %.3f (%.3f); seat at 60 m/s^2: weightless %.3f (%.3f), Moon %.3f (%.3f), normal %.3f (%.3f)",
                circle, measured[0], expectMeasured[0], measured[1], expectMeasured[1], measured[2], expectMeasured[2],
                seated[0], expectSeated[0], seated[1], expectSeated[1], seated[2], expectSeated[2]);
        report("g-force in zero, Moon and normal gravity", out);
        String[] names = {"weightless", "Moon", "normal"};
        for (int kind = 0; kind < 3; kind++) {
            helper.assertTrue(Math.abs(measured[kind] - expectMeasured[kind]) < 0.1, "the " + names[kind]
                    + " villager's measured g-force: " + out);
            helper.assertTrue(Math.abs(seated[kind] - expectSeated[kind]) < 0.02, "the " + names[kind]
                    + " villager's seated g-force: " + out);
        }
        helper.assertTrue(measured[0] > measured[1] && seated[0] > seated[1] && seated[1] > seated[2],
                "less gravity should leave more of the acceleration felt: " + out);
        helper.succeed();
    }

    // ------------------------------------------------------------------

    /** A villager and what happened to it: health lost, the first damage source, top speed (m/s). */
    private static final class Body {
        final Villager villager;
        final float health;
        double lost;
        @Nullable
        DamageSource source;
        double fastest;
        /** Ever sprained (Feather Falling IV keeps a sprain to a few ticks). */
        boolean sprained;
        @Nullable
        Vec3 last;

        Body(Villager villager) {
            this.villager = villager;
            this.health = villager.getHealth();
        }

        void record() {
            Vec3 now = villager.position();
            if (last != null) fastest = Math.max(fastest, now.distanceTo(last) * 20.0);
            last = now;
            sprained |= villager.hasEffect(ModEffects.SPRAIN.get());
            float hp = villager.isAlive() ? villager.getHealth() : 0.0f;
            if (hp < health - lost) {
                lost = health - hp;
                if (source == null) source = villager.getLastDamageSource();
            }
        }

        String describe() {
            return lost == 0.0 ? "unhurt" : fmt(lost) + " damage (" + (source == null ? "?" : source.getMsgId()) + ")";
        }
    }

    private static Villager villager(ServerLevel level, Vec3 at) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) throw new IllegalStateException("no villager");
        villager.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        villager.setPersistenceRequired();
        level.addFreshEntity(villager);
        return villager;
    }

    private static Villager bootedVillager(ServerLevel level, Vec3 at) {
        Villager villager = villager(level, at);
        ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);
        boots.enchant(Enchantments.FALL_PROTECTION, 4);
        villager.setItemSlot(EquipmentSlot.FEET, boots);
        return villager;
    }

    /** kind 0: weightless (no-gravity flag), 1: Moon gravity, 2: normal. Never added to the world. */
    private static Villager offWorldVillager(ServerLevel level, Vec3 at, int kind) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) throw new IllegalStateException("no villager");
        villager.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        if (kind == 0) villager.setNoGravity(true);
        if (kind == 1) moonGravity(villager);
        return villager;
    }

    /** The Moon's gravity, a sixth of normal, through the forge:entity_gravity attribute. */
    static void moonGravity(net.minecraft.world.entity.LivingEntity living) {
        AttributeInstance gravity = living.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
        if (gravity == null) throw new IllegalStateException("no gravity attribute");
        gravity.setBaseValue(0.08 * MOON);
    }
}
