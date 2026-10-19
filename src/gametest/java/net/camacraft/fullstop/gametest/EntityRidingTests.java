package net.camacraft.fullstop.gametest;

import com.mojang.authlib.GameProfile;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.server.physics.interaction.BodyCollisions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Landing on a mob, a boat or a minecart rides it (Entity Riding, {@code EntityRiding}; the owner, 2026-10-17: "The
 * old one had the ability to jump onto an entity to ride it ... I'd like the entity riding to be re-implemented for
 * the new one"). A fake player is dropped straight down at a steady speed, moved each tick the way its client would
 * move it (a fake player never moves by itself): onto a cow it rides it and the cow panics; crouching it doesn't;
 * faster than {@code entityRidingMaxSpeed} it crushes the cow instead; onto a chicken (lighter than the player) it
 * crushes it; onto a boat it rides it. A cow dropped onto a standing player never rides it (only players ride by
 * default), nor does a pig dropped onto a cow; with {@code entityRidingMobRiders} on the pig does, and after getting
 * off it mounts nothing for the dismount cooldown.
 *
 * <p>Opt-in with the rest of FullStop's game tests: {@code gradlew runGameTestServer -PshipTests -PtestOnly=EntityRidingTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class EntityRidingTests {

    private static final String FLAT = ShipMaterialTests.FLAT;
    private static final String BATCH = "fullstop_riding";
    private static final String BATCH_MOB_RIDERS = "fullstop_riding_mobs";
    private static final int TIMEOUT = 400;

    private EntityRidingTests() {
    }

    // ------------------------------------------------------------------
    // Players
    // ------------------------------------------------------------------

    /** A player coming down on a cow at 8 m/s (a jump's landing) rides it, unhurt, and the cow panics. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPlayerLandingOnACowRidesIt(GameTestHelper helper) {
        Rig rig = new Rig(helper, 0);
        Mob cow = rig.standing(EntityType.COW, 0.0);
        FakePlayer player = rig.player("rides", 0.0, 6.0);
        rig.whenReady()
                .thenExecute(() -> rig.drop(player, 8.0))
                .thenWaitUntil(() -> helper.assertTrue(player.getVehicle() == cow, "the player isn't riding the cow: " + rig.describe(player, cow)))
                .thenExecuteAfter(5, () -> {
                    String out = rig.describe(player, cow);
                    report("a player dropped onto a cow at 8 m/s", out);
                    BodyCollisions.Record record = BodyCollisions.last(player);
                    helper.assertTrue(record != null && record.mounted() && record.other() == cow, "no mount record: " + out);
                    helper.assertTrue(cow.getHealth() >= cow.getMaxHealth() - 0.01f, "the cow was hurt by the mount: " + out);
                    helper.assertTrue(cow.getLastHurtByMob() == player, "the cow doesn't panic at its rider: " + out);
                    helper.assertTrue(player.getVehicle() == cow, "the player got off again: " + out);
                    rig.discard();
                })
                .thenSucceed();
    }

    /** The same landing while crouching: no mount, and at 8 m/s (under the collision speed) nothing at all. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aCrouchingPlayerLandingOnACowDoesNotRideIt(GameTestHelper helper) {
        Rig rig = new Rig(helper, 1);
        Mob cow = rig.standing(EntityType.COW, 0.0);
        FakePlayer player = rig.player("crouches", 0.0, 6.0);
        player.setShiftKeyDown(true);
        player.setPose(Pose.CROUCHING);
        rig.whenReady()
                .thenExecute(() -> rig.drop(player, 8.0))
                .thenWaitUntil(() -> helper.assertTrue(rig.landed(player), "still falling"))
                .thenExecuteAfter(5, () -> {
                    String out = rig.describe(player, cow);
                    report("a crouching player dropped onto a cow at 8 m/s", out);
                    helper.assertTrue(player.getVehicle() == null, "the crouching player rides the cow: " + out);
                    helper.assertTrue(BodyCollisions.last(player) == null && BodyCollisions.last(cow) == null,
                            "a landing under the collision speed did something: " + out);
                    helper.assertTrue(cow.getHealth() >= cow.getMaxHealth() - 0.01f, "the cow was hurt: " + out);
                    rig.discard();
                })
                .thenSucceed();
    }

    /**
     * Faster than entityRidingMaxSpeed (40 m/s): the crush it always was, no mount. (A 75 kg player crushing a 650 kg cow
     * at 40 m/s takes 4.6 m/s off it, under the damage threshold: the cow is crushed but not hurt; a chicken is, below.)
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPlayerLandingTooFastOnACowCrushesItInstead(GameTestHelper helper) {
        Rig rig = new Rig(helper, 2);
        Mob cow = rig.standing(EntityType.COW, 0.0);
        FakePlayer player = rig.player("crushes", 0.0, 8.0);
        double max = FullStopConfig.SERVER.entityRidingMaxSpeed.get();
        rig.whenReady()
                .thenExecute(() -> rig.drop(player, max + 10.0))
                .thenWaitUntil(() -> helper.assertTrue(rig.landed(player), "still falling"))
                .thenExecuteAfter(5, () -> {
                    String out = rig.describe(player, cow);
                    report("a player dropped onto a cow at " + (max + 10.0) + " m/s", out);
                    BodyCollisions.Record record = BodyCollisions.last(cow);
                    helper.assertTrue(player.getVehicle() == null, "the player rides the cow it fell on too fast: " + out);
                    helper.assertTrue(record != null && record.crushed() && !record.mounted() && record.other() == player,
                            "the cow wasn't crushed: " + out);
                    helper.assertTrue(record.speedLost() > 0.0, "the crush took no speed off the cow: " + out);
                    rig.discard();
                })
                .thenSucceed();
    }

    /** A player (75 kg) coming down on a chicken (3 kg) at 12 m/s: too heavy to ride it, so it crushes it. */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPlayerLandingOnAChickenIsTooHeavyToRideItAndCrushesIt(GameTestHelper helper) {
        Rig rig = new Rig(helper, 3);
        Mob chicken = rig.standing(EntityType.CHICKEN, 0.0);
        FakePlayer player = rig.player("squashes", 0.0, 6.0);
        rig.whenReady()
                .thenExecute(() -> rig.drop(player, 12.0))
                .thenWaitUntil(() -> helper.assertTrue(rig.landed(player), "still falling"))
                .thenExecuteAfter(5, () -> {
                    String out = rig.describe(player, chicken);
                    report("a player dropped onto a chicken at 12 m/s", out);
                    BodyCollisions.Record record = BodyCollisions.last(player);
                    helper.assertTrue(player.getVehicle() == null, "the player rides the chicken: " + out);
                    helper.assertTrue(record != null && !record.mounted() && record.other() == chicken, "no collision with the chicken: " + out);
                    helper.assertTrue(!chicken.isAlive() || chicken.getHealth() < chicken.getMaxHealth(), "the chicken wasn't hurt: " + out);
                    rig.discard();
                })
                .thenSucceed();
    }

    /** A player coming down on an empty boat on the ground at 8 m/s rides it (a boat takes anyone, whatever it weighs). */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPlayerLandingOnABoatRidesIt(GameTestHelper helper) {
        Rig rig = new Rig(helper, 4);
        Boat boat = rig.boat(0.0);
        FakePlayer player = rig.player("boats", 0.0, 6.0);
        rig.whenReady()
                .thenExecute(() -> rig.drop(player, 8.0))
                .thenWaitUntil(() -> helper.assertTrue(player.getVehicle() == boat, "the player isn't in the boat: " + rig.describe(player, boat)))
                .thenExecuteAfter(5, () -> {
                    String out = rig.describe(player, boat);
                    report("a player dropped onto a boat at 8 m/s", out);
                    BodyCollisions.Record record = BodyCollisions.last(player);
                    helper.assertTrue(record != null && record.mounted() && record.other() == boat, "no mount record: " + out);
                    helper.assertTrue(player.getVehicle() == boat, "the player got out again: " + out);
                    rig.discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // Mobs
    // ------------------------------------------------------------------

    /** A cow dropped onto a standing player at 12 m/s never rides it: it is a crush (only players ride by default). */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aCowLandingOnAPlayerDoesNotRideIt(GameTestHelper helper) {
        Rig rig = new Rig(helper, 5);
        FakePlayer player = rig.player("stands", 0.0, 0.0);
        player.setOnGround(true);
        Mob cow = rig.falling(EntityType.COW, 0.0, 4.0, 12.0);
        rig.whenReady()
                .thenWaitUntil(() -> helper.assertTrue(cow.onGround() || cow.getY() <= rig.floor + 1.05, "the cow is still falling"))
                .thenExecuteAfter(5, () -> {
                    String out = rig.describe(cow, player);
                    report("a cow dropped onto a standing player at 12 m/s", out);
                    helper.assertTrue(cow.getVehicle() == null && player.getVehicle() == null, "someone is riding: " + out);
                    BodyCollisions.Record record = BodyCollisions.last(player);
                    helper.assertTrue(record != null && record.crushed() && record.other() == cow, "the player wasn't crushed: " + out);
                    rig.discard();
                })
                .thenSucceed();
    }

    /**
     * A pig dropped onto a cow (it comes down under its own gravity, 13.5 m/s at the cow's back) doesn't ride it by
     * default (entityRidingMobRiders off): it is the crush it always was, which at that speed takes 3 m/s off the cow.
     */
    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void aPigLandingOnACowDoesNotRideItByDefault(GameTestHelper helper) {
        Rig rig = new Rig(helper, 6);
        Mob cow = rig.standing(EntityType.COW, 0.0);
        Mob pig = rig.falling(EntityType.PIG, 0.0, 4.0, 8.0);
        rig.whenReady()
                .thenWaitUntil(() -> helper.assertTrue(pig.onGround(), "the pig is still falling"))
                .thenExecuteAfter(5, () -> {
                    String out = rig.describe(pig, cow);
                    report("a pig dropped onto a cow, mob riders off", out);
                    helper.assertTrue(pig.getVehicle() == null, "the pig rides the cow: " + out);
                    BodyCollisions.Record record = BodyCollisions.last(pig);
                    helper.assertTrue(record == null || !record.mounted(), "a mob mounted with mob riders off: " + out);
                    rig.discard();
                })
                .thenSucceed();
    }

    /**
     * With entityRidingMobRiders on, the pig rides the cow; put off it, it mounts nothing for the dismount cooldown
     * (entityRidingDismountCooldownTicks) and rides again after. Its own batch: the switch is flipped for it.
     */
    @GameTest(template = FLAT, batch = BATCH_MOB_RIDERS, timeoutTicks = 600)
    public static void aPigRidesACowWithMobRidersOnAndNotWithinTheDismountCooldown(GameTestHelper helper) {
        Rig rig = new Rig(helper, 7);
        boolean was = FullStopConfig.SERVER.entityRidingMobRiders.get();
        FullStopConfig.SERVER.entityRidingMobRiders.set(true);
        int cooldown = FullStopConfig.SERVER.entityRidingDismountCooldownTicks.get();
        Mob cow = rig.standing(EntityType.COW, 0.0);
        Mob pig = rig.falling(EntityType.PIG, 0.0, 4.0, 8.0);
        rig.whenReady()
                .thenWaitUntil(() -> helper.assertTrue(pig.getVehicle() == cow, "the pig isn't riding the cow: " + rig.describe(pig, cow)))
                .thenExecuteAfter(5, () -> {
                    report("a pig dropped onto a cow at 8 m/s, mob riders on", rig.describe(pig, cow));
                    pig.stopRiding();
                })
                // straight back up and down within the cooldown: no mount
                .thenExecuteAfter(3, () -> rig.place(pig, 0.0, 4.0, 8.0))
                .thenWaitUntil(() -> helper.assertTrue(pig.onGround() || pig.isPassenger(), "the pig is still falling"))
                .thenExecuteAfter(5, () -> {
                    String out = rig.describe(pig, cow);
                    report("the pig dropped again within the dismount cooldown", out);
                    helper.assertTrue(pig.getVehicle() == null, "the pig mounted again within the dismount cooldown: " + out);
                })
                // after the cooldown: it rides again
                .thenExecuteAfter(cooldown + 10, () -> rig.place(pig, 0.0, 4.0, 8.0))
                .thenWaitUntil(() -> helper.assertTrue(pig.getVehicle() == cow, "the pig isn't riding the cow after the cooldown: " + rig.describe(pig, cow)))
                .thenExecuteAfter(5, () -> {
                    report("the pig dropped again after the dismount cooldown", rig.describe(pig, cow));
                    FullStopConfig.SERVER.entityRidingMobRiders.set(was);
                    rig.discard();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // Rig
    // ------------------------------------------------------------------

    /** A stone floor in a lane of its own, the bodies on it, and the fake players this test drops by hand. */
    private static final class Rig {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos o;
        /** The y of the top of the stone floor. */
        final double floor;
        final List<Entity> entities = new ArrayList<>();
        final List<Drop> drops = new ArrayList<>();

        Rig(GameTestHelper helper, int lane) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (120 + lane) * 256);
            ShipCollisionTests.fill(level, o.offset(-6, 0, -6), o.offset(6, 0, 6), Blocks.STONE.defaultBlockState());
            this.floor = o.getY() + 1.0;
            helper.onEachTick(() -> {
                for (Drop drop : drops) drop.tick();
            });
        }

        /** A sequence that starts once the lane ticks entities and the bodies have settled. */
        GameTestSequence whenReady() {
            return helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(o.above(4)), "the lane doesn't tick entities yet"))
                    .thenExecuteAfter(10, () -> {
                    });
        }

        Vec3 at(double dx, double dy) {
            return new Vec3(o.getX() + 0.5 + dx, floor + dy, o.getZ() + 0.5);
        }

        /** A mob with no goals (it goes nowhere by itself), standing on the floor. */
        Mob standing(EntityType<? extends Mob> type, double dx) {
            Mob mob = type.create(level);
            if (mob == null) throw new IllegalStateException("no " + type);
            Vec3 at = at(dx, 0.0);
            mob.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
            mob.goalSelector.removeAllGoals(g -> true);
            mob.setPersistenceRequired();
            level.addFreshEntity(mob);
            entities.add(mob);
            return mob;
        }

        /** A mob with no goals, {@code dy} above the floor, falling at {@code speed} m/s under its own gravity. */
        Mob falling(EntityType<? extends Mob> type, double dx, double dy, double speed) {
            Mob mob = standing(type, dx);
            place(mob, dx, dy, speed);
            return mob;
        }

        /** Puts a mob {@code dy} above the floor, falling at {@code speed} m/s. */
        void place(Mob mob, double dx, double dy, double speed) {
            Vec3 at = at(dx, dy);
            mob.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
            mob.setDeltaMovement(0.0, -speed / 20.0, 0.0);
            mob.hasImpulse = true;
            mob.setOnGround(false);
        }

        Boat boat(double dx) {
            Boat boat = EntityType.BOAT.create(level);
            if (boat == null) throw new IllegalStateException("no boat");
            Vec3 at = at(dx, 0.0);
            boat.moveTo(at.x, at.y, at.z, 0.0f, 0.0f);
            level.addFreshEntity(boat);
            entities.add(boat);
            return boat;
        }

        /** A fake player {@code dy} above the floor; it never moves by itself. */
        FakePlayer player(String name, double dx, double dy) {
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(("fullstop-riding-" + name + o).getBytes()),
                    "[FS " + name + "]"));
            Vec3 at = at(dx, dy);
            player.moveTo(at.x, at.y, at.z, 0.0f, 0.0f);
            player.setDeltaMovement(Vec3.ZERO);
            player.setOnGround(false);
            level.addFreshEntity(player);
            entities.add(player);
            return player;
        }

        /** Moves the player straight down at a steady {@code speed} m/s each tick, as its client would, until it lands or rides. */
        void drop(FakePlayer player, double speed) {
            drops.add(new Drop(player, speed));
        }

        boolean landed(FakePlayer player) {
            for (Drop drop : drops) {
                if (drop.player == player) return drop.done;
            }
            return false;
        }

        String describe(Entity upper, Entity lower) {
            return String.format(Locale.ROOT, "%s at y %.2f (riding %s), %s at y %.2f (riding %s, hp %s, lastHurtBy %s), records %s / %s",
                    upper.getName().getString(), upper.getY(), name(upper.getVehicle()), lower.getName().getString(), lower.getY(),
                    name(lower.getVehicle()), lower instanceof LivingEntity living ? String.format(Locale.ROOT, "%.1f/%.1f", living.getHealth(), living.getMaxHealth()) : "-",
                    lower instanceof Mob mob ? name(mob.getLastHurtByMob()) : "-", record(BodyCollisions.last(upper)), record(BodyCollisions.last(lower)));
        }

        void discard() {
            drops.clear();
            for (Entity entity : entities) entity.discard();
        }

        private final class Drop {
            final FakePlayer player;
            final double speed;
            boolean done;

            Drop(FakePlayer player, double speed) {
                this.player = player;
                this.speed = speed;
            }

            void tick() {
                if (done) return;
                if (player.isPassenger() || player.isRemoved()) {
                    done = true;
                    return;
                }
                Vec3 p = player.position();
                double y = p.y - speed / 20.0;
                if (y <= floor) {
                    y = floor;
                    done = true;
                    player.setOnGround(true);
                }
                player.setPos(p.x, y, p.z);
                player.setDeltaMovement(0.0, -speed / 20.0, 0.0);
            }
        }
    }

    private static String name(@Nullable Entity entity) {
        return entity == null ? "nothing" : entity.getName().getString();
    }

    private static String record(@Nullable BodyCollisions.Record r) {
        if (r == null) return "none";
        return String.format(Locale.ROOT, "{with %s, closing %.1f m/s, lost %.1f, damage %.1f%s%s}", name(r.other()), r.closing(), r.speedLost(),
                r.damage(), r.crushed() ? ", crushed" : "", r.mounted() ? ", mounted" : "");
    }

    private static void report(String what, String detail) {
        ShipCollisionTests.report(what, detail);
    }
}
