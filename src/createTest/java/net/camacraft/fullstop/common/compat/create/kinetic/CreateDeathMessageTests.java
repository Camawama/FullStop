package net.camacraft.fullstop.common.compat.create.kinetic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Every way to die through Full Stop's Create compat has its own damage type and death message: a pig is killed
 * each way and the message its death would print is checked (the translation key, and that en_us has it). The
 * water wheel's drowning goes the real way: vanilla's drowning hit on a body the wheel holds under is turned into
 * the wheel's own. Opt-in: {@code gradlew runGameTestServer -Pcreate}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class CreateDeathMessageTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "create_flat_48x8x48";
    private static final String BATCH = "fullstop_create_deaths";
    private static final String[] KINDS = {"spinning", "windmill", "clock", "piston", "gantry", "pulley", "elevator", "minecart", "train"};

    private CreateDeathMessageTests() {
    }

    @GameTest(template = FLAT, batch = BATCH, timeoutTicks = 100)
    public static void everyCreateWayToDieHasItsOwnMessage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        JsonObject lang = lang();
        List<String> failures = new ArrayList<>();
        List<String> report = new ArrayList<>();
        int[] slot = {0};
        // the gears and the paddles
        check(helper, level, lang, slot, failures, report, "death.attack.fullstop.gear", pig -> GearDamage.source(pig, Hazard.Kind.COGWHEEL));
        check(helper, level, lang, slot, failures, report, "death.attack.fullstop.large_gear", pig -> GearDamage.source(pig, Hazard.Kind.LARGE_COGWHEEL));
        check(helper, level, lang, slot, failures, report, "death.attack.fullstop.water_wheel", pig -> GearDamage.source(pig, Hazard.Kind.WATER_WHEEL));
        check(helper, level, lang, slot, failures, report, "death.attack.fullstop.water_wheel", pig -> GearDamage.source(pig, Hazard.Kind.LARGE_WATER_WHEEL));
        // contraptions by kind: struck, crushed against a wall, crushed against a ship, and with a driver
        FullStopDamageSources.VelocityDisplay fast = FullStopDamageSources.mutualVelocityDisplay(20.0);
        for (String kind : KINDS) {
            String base = "death.fullstop.contraption." + kind;
            check(helper, level, lang, slot, failures, report, base + ".hit",
                    pig -> FullStopDamageSources.contraption(pig, null, fast, kind, false, false, false, false));
            check(helper, level, lang, slot, failures, report, base + ".crushed",
                    pig -> FullStopDamageSources.contraption(pig, null, fast, kind, true, false, false, false));
            check(helper, level, lang, slot, failures, report, base + ".crushed_ship",
                    pig -> FullStopDamageSources.contraption(pig, null, fast, kind, true, true, false, false));
            LivingEntity driver = pig(level, helper, 40 + slot[0]);
            check(helper, level, lang, slot, failures, report, base + ".hit.player",
                    pig -> FullStopDamageSources.contraption(pig, driver, fast, kind, false, false, false, false));
            check(helper, level, lang, slot, failures, report, base + ".crushed.player",
                    pig -> FullStopDamageSources.contraption(pig, driver, fast, kind, true, false, false, false));
            driver.discard();
            // the damage type's own message is there too, for anything that asks the type
            if (!lang.has("death.attack.fullstop.contraption_" + kind)) failures.add("en_us lacks death.attack.fullstop.contraption_" + kind);
        }
        check(helper, level, lang, slot, failures, report, "death.fullstop.contraption.hit",
                pig -> FullStopDamageSources.contraption(pig, null, fast, null, false, false, false, false));
        check(helper, level, lang, slot, failures, report, "death.fullstop.contraption.crushed_ship",
                pig -> FullStopDamageSources.contraption(pig, null, fast, null, true, true, false, false));

        // the water wheel's drowning, the real way: vanilla's drowning hit on a body the wheel holds under
        LivingEntity held = pig(level, helper, slot[0]++);
        KineticHazards.markHeldUnderForTest(held);
        held.hurt(held.damageSources().drown(), 1000.0f);
        String key = key(held);
        DamageSource last = held.getLastDamageSource();
        boolean typed = last != null && last.is(GearDamage.WATER_WHEEL_DROWN);
        boolean stillDrowning = last != null && last.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING);
        report.add("water wheel drowning: " + key + (typed ? "" : " (type " + (last == null ? "none" : last.typeHolder()) + ")"));
        if (!held.isDeadOrDying()) failures.add("the pig held under didn't drown");
        if (!"death.attack.fullstop.water_wheel_drown".equals(key) || !typed) failures.add("drowning under a wheel died as " + key);
        if (!stillDrowning) failures.add("the wheel's drowning isn't in #minecraft:is_drowning");
        if (!lang.has("death.attack.fullstop.water_wheel_drown")) failures.add("en_us lacks death.attack.fullstop.water_wheel_drown");
        // and a pig no wheel holds drowns as vanilla says
        LivingEntity free = pig(level, helper, slot[0]++);
        free.hurt(free.damageSources().drown(), 1000.0f);
        if (!"death.attack.drown".equals(key(free))) failures.add("a pig no wheel holds died as " + key(free));

        LOGGER.info("[FullStop gametest] Create death messages: {}", String.join("; ", report));
        helper.assertTrue(failures.isEmpty(), String.join("; ", failures));
        helper.succeed();
    }

    /** Kills a fresh pig with {@code source} and checks its death message is {@code expected} (and en_us has it). */
    private static void check(GameTestHelper helper, ServerLevel level, JsonObject lang, int[] slot, List<String> failures, List<String> report,
                              String expected, Function<LivingEntity, DamageSource> source) {
        LivingEntity pig = pig(level, helper, slot[0]++);
        pig.hurt(source.apply(pig), 1000.0f);
        String key = key(pig);
        report.add(expected.substring(expected.lastIndexOf('.', expected.length() - 1) + 1) + "=" + key);
        if (!pig.isDeadOrDying()) failures.add(expected + ": the pig survived");
        if (!expected.equals(key)) failures.add(expected + " expected, died as " + key);
        if (!lang.has(expected)) failures.add("en_us lacks " + expected);
        pig.discard();
    }

    /**
     * The translation key of the death message {@code dead} died with: its last damage source's, which is what the
     * combat tracker prints for a death that wasn't a fall (vanilla clears the tracker once the mob is dead).
     */
    private static String key(LivingEntity dead) {
        DamageSource last = dead.getLastDamageSource();
        if (last == null) return "no damage";
        Component message = last.getLocalizedDeathMessage(dead);
        return message.getContents() instanceof TranslatableContents t ? t.getKey() : String.valueOf(message);
    }

    private static LivingEntity pig(ServerLevel level, GameTestHelper helper, int i) {
        Mob pig = EntityType.PIG.create(level);
        if (pig == null) throw new IllegalStateException("no pig");
        BlockPos at = helper.absolutePos(new BlockPos(2 + (i % 20) * 2, 2, 2 + (i / 20) * 2));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        return pig;
    }

    private static JsonObject lang() {
        try (InputStream in = FullStop.class.getResourceAsStream("/assets/fullstop/lang/en_us.json")) {
            if (in == null) throw new IllegalStateException("no en_us.json");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
