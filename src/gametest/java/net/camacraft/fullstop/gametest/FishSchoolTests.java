package net.camacraft.fullstop.gametest;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.physics.rules.EntityCollisionRules;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Schooling fish (the owner, 2026-10-13: "FullStop is causing collisions between fish when there is a bunch of them
 * boiding together from Nice Catch"): two fish swimming side by side are never a Full Stop entity collision; fish out of
 * the water, or a fish and a pig, still are. Runs with the ship tests ({@code -PshipTests -PtestOnly=FishSchoolTests}).
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class FishSchoolTests {

    private static final String FLAT = "flat_48x8x48";

    private FishSchoolTests() {
    }

    @GameTest(template = FLAT, batch = "fullstop_fish_school", timeoutTicks = 40)
    public static void twoFishSwimmingTogetherNeverCollide(GameTestHelper helper) {
        for (int x = 20; x <= 24; x++) {
            for (int z = 20; z <= 24; z++) {
                for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
            }
        }
        Cod a = helper.spawn(EntityType.COD, new BlockPos(22, 2, 22));
        Cod b = helper.spawn(EntityType.COD, new BlockPos(22, 2, 22));
        Cod dryA = helper.spawn(EntityType.COD, new BlockPos(5, 2, 5));
        Cod dryB = helper.spawn(EntityType.COD, new BlockPos(5, 2, 5));
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(22, 2, 22));
        helper.runAfterDelay(2, () -> {
            String out = "in water " + EntityCollisionRules.shouldIgnoreCollision(a, b) + " (" + a.isInWater() + "/" + b.isInWater()
                    + "), on land " + EntityCollisionRules.shouldIgnoreCollision(dryA, dryB) + ", fish and pig "
                    + EntityCollisionRules.shouldIgnoreCollision(a, pig);
            ShipCollisionTests.report("fish brushing past each other", out);
            helper.assertTrue(a.isInWater() && b.isInWater(), "the fish aren't in the water: " + out);
            helper.assertTrue(EntityCollisionRules.shouldIgnoreCollision(a, b), "two swimming fish still collide: " + out);
            helper.assertTrue(!EntityCollisionRules.shouldIgnoreCollision(dryA, dryB), "fish out of the water no longer collide: " + out);
            helper.assertTrue(!EntityCollisionRules.shouldIgnoreCollision(a, pig), "a fish and a pig no longer collide: " + out);
            helper.succeed();
        });
    }
}
