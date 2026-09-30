package net.camacraft.fullstop.gametest;

import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Death messages that name a ship. Valkyrien Skies gives every new ship three random nouns from its list joined by
 * hyphens ({@code delivery-doe-leg}); {@code /vs rename} replaces that with whatever the player typed. Camafy tells
 * the two apart against VS's own list ({@code Ships.isGeneratedName}, {@code ShipRef.customName}), and Full Stop says
 * "was hit by a ship" for the first and "was hit by Large Wooden Door" for the second.
 *
 * <p>Opt-in with the rest of FullStop's game tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class DeathMessageTests {

    private DeathMessageTests() {
    }

    /** Names made the way VS makes them are told from names a player gave, against VS's own noun list. */
    @GameTest(template = ShipMaterialTests.FLAT, batch = "fullstop_names", timeoutTicks = 100)
    public static void aShipsMadeUpNameIsToldFromAGivenOne(GameTestHelper helper) {
        List<String> nouns = nouns();
        helper.assertTrue(nouns.size() > 1000, "VS's noun list wasn't found where Camafy reads it (" + nouns.size() + " nouns)");
        Random random = new Random(42L);
        for (int i = 0; i < 40; i++) {
            String name = nouns.get(random.nextInt(nouns.size())) + "-" + nouns.get(random.nextInt(nouns.size())) + "-"
                    + nouns.get(random.nextInt(nouns.size()));
            helper.assertTrue(Ships.isGeneratedName(name), "a name VS would make up isn't seen as made up: " + name);
        }
        for (String given : new String[]{"Large Wooden Door", "large-wooden-door", "Boaty McBoatface", "door", "USS Enterprise",
                "delivery-doe-leg-and-then-some"}) {
            helper.assertFalse(Ships.isGeneratedName(given), "a name a player gave is taken for a made-up one: " + given);
        }
        helper.succeed();
    }

    /** With no ship to name (or one still carrying its made-up name), the messages say "a ship" as before. */
    @GameTest(template = ShipMaterialTests.FLAT, batch = "fullstop_names", timeoutTicks = 100)
    public static void aShipWithoutAGivenNameIsJustAShip(GameTestHelper helper) {
        BlockPos o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + 120 * 256);
        Pig pig = EntityType.PIG.create(helper.getLevel());
        helper.assertTrue(pig != null, "no pig");
        pig.moveTo(o.getX() + 0.5, o.getY() + 2.0, o.getZ() + 0.5, 0.0f, 0.0f);
        helper.getLevel().addFreshEntity(pig);
        FullStopDamageSources.VelocityDisplay shown = FullStopDamageSources.mutualVelocityDisplay(30.0);
        Component hit = FullStopDamageSources.ship(pig, null, shown, false, true, false).getLocalizedDeathMessage(pig);
        Component crushed = FullStopDamageSources.ship(pig, null, shown, true, true, false, 123456789L).getLocalizedDeathMessage(pig);
        Component seat = FullStopDamageSources.seatCrash(pig, shown, 123456789L).getLocalizedDeathMessage(pig);
        helper.assertTrue(key(hit).equals("death.fullstop.ship.hit"), "hit by no ship in particular: " + key(hit));
        helper.assertTrue(key(crushed).equals("death.fullstop.ship.crushed"), "crushed by a ship that doesn't exist: " + key(crushed));
        helper.assertTrue(key(seat).equals("death.fullstop.ship.seat"), "a seat crash on a ship that doesn't exist: " + key(seat));
        pig.discard();
        helper.succeed();
    }

    private static String key(Component message) {
        return message.getContents() instanceof TranslatableContents t ? t.getKey() : message.getString();
    }

    /** VS's noun list, read the way Camafy reads it. */
    private static List<String> nouns() {
        List<String> nouns = new ArrayList<>();
        ClassLoader loader = DeathMessageTests.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream("nounlist.txt")) {
            if (in == null) return nouns;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) nouns.add(line);
                }
            }
        } catch (IOException e) {
            nouns.clear();
        }
        return nouns;
    }
}
