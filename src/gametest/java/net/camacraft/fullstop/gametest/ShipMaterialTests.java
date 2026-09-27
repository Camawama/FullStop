package net.camacraft.fullstop.gametest;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.ship.ShipImpactSounds;
import net.camacraft.fullstop.server.physics.ship.ShipMaterials;
import net.camacraft.fullstop.server.physics.ship.ShipMaterials.Gives;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * The rules that decide what a crashing ship breaks, checked against Valkyrien Skies' REAL block
 * masses (read through Camafy, as the game does) - no ship needed. What the user asked for,
 * as assertions: a wooden hull breaks on stone and the stone holds; wood never breaks stone,
 * cobblestone or deepslate; stone crushes grass; obsidian never breaks obsidian but crushes
 * grass; any hull clears leaves. Plus the calibration the defaults were tuned to, and the impact
 * sound mapping.
 *
 * <p>Opt-in with the rest of FullStop's game tests: {@code gradlew runGameTestServer -PshipTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class ShipMaterialTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String FLAT = "flat_48x8x48";

    private ShipMaterialTests() {
    }

    /** Toughness of a block's default state as the game computes it (mass from VS). */
    private static double tough(GameTestHelper helper, Block block) {
        return tough(helper, block.defaultBlockState());
    }

    private static double tough(GameTestHelper helper, BlockState state) {
        return ShipMaterials.toughness(helper.getLevel(), BlockPos.ZERO, state);
    }

    private static String name(BlockState state) {
        return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()).getPath();
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }

    // ------------------------------------------------------------------
    // VS's numbers
    // ------------------------------------------------------------------

    @GameTest(template = FLAT)
    public static void vsBlockMassesAreTheOnesTheModelWasTunedOn(GameTestHelper helper) {
        helper.assertTrue(Ships.available(), "Valkyrien Skies isn't running: these tests need its block masses");
        Object[][] expected = {
                {Blocks.STONE, 2500.0}, {Blocks.COBBLESTONE, 2400.0}, {Blocks.DEEPSLATE, 2900.0},
                {Blocks.GRASS_BLOCK, 1250.0}, {Blocks.DIRT, 1250.0}, {Blocks.OAK_PLANKS, 500.0},
                {Blocks.OAK_LOG, 800.0}, {Blocks.OBSIDIAN, 5000.0}, {Blocks.OAK_LEAVES, 200.0},
                {Blocks.IRON_BLOCK, 7850.0}, {Blocks.GLASS, 2000.0}, {Blocks.GRASS, 20.0},
        };
        StringBuilder log = new StringBuilder();
        for (Object[] row : expected) {
            Block block = (Block) row[0];
            OptionalDouble mass = Ships.blockMassKg(block.defaultBlockState());
            log.append(name(block.defaultBlockState())).append('=').append(mass.isPresent() ? f(mass.getAsDouble()) : "empty")
                    .append(' ');
            helper.assertTrue(mass.isPresent() && Math.abs(mass.getAsDouble() - (double) row[1]) < 1.0e-6,
                    name(block.defaultBlockState()) + ": VS mass " + mass + ", expected " + row[1]);
        }
        // A block VS's vs_mass data doesn't list: what does it answer? (Its defaultBlockMass.)
        BlockState unlisted = Blocks.SCULK_SENSOR.defaultBlockState();
        OptionalDouble mass = Ships.blockMassKg(unlisted);
        LOGGER.info("[FullStop test] VS block masses: {}| unlisted {} -> {} (FullStop uses {} kg)", log, name(unlisted),
                mass.isPresent() ? f(mass.getAsDouble()) : "empty", f(ShipMaterials.massKg(unlisted)));
        helper.assertTrue(Math.abs(ShipMaterials.massKg(unlisted) - ShipMaterials.DEFAULT_BLOCK_MASS_KG) < 1.0e-6,
                "an unlisted block should weigh VS's default 1000 kg, got " + mass);
        helper.succeed();
    }

    @GameTest(template = FLAT)
    public static void toughnessTable(GameTestHelper helper) {
        Block[] blocks = {Blocks.OAK_LEAVES, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.SAND, Blocks.GRAVEL,
                Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.GLASS, Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE,
                Blocks.END_STONE, Blocks.ICE, Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.OBSIDIAN, Blocks.BEDROCK};
        StringBuilder log = new StringBuilder();
        for (Block block : blocks) {
            log.append(name(block.defaultBlockState())).append('=').append(f(tough(helper, block))).append(' ');
        }
        LOGGER.info("[FullStop test] toughness (massInfluence {}): {}", SERVER.massInfluence.get(), log);
        near(helper, "leaves", tough(helper, Blocks.OAK_LEAVES), 0.04);
        near(helper, "grass block", tough(helper, Blocks.GRASS_BLOCK), 0.75);
        near(helper, "oak planks", tough(helper, Blocks.OAK_PLANKS), 1.0);
        near(helper, "stone", tough(helper, Blocks.STONE), 3.75);
        near(helper, "obsidian", tough(helper, Blocks.OBSIDIAN), 250.0);
        helper.assertTrue(Double.isInfinite(tough(helper, Blocks.BEDROCK)), "bedrock must be infinitely tough");
        helper.succeed();
    }

    private static void near(GameTestHelper helper, String what, double actual, double expected) {
        helper.assertTrue(Math.abs(actual - expected) < 1.0e-6 * Math.max(1.0, expected),
                what + ": toughness " + actual + ", expected " + expected);
    }

    // ------------------------------------------------------------------
    // Which block gives
    // ------------------------------------------------------------------

    @GameTest(template = FLAT)
    public static void aWoodenHullBreaksOnStoneAndTheStoneHolds(GameTestHelper helper) {
        // first = terrain, second = hull (as ShipTerrainImpacts asks)
        helper.assertTrue(ShipMaterials.whichGives(tough(helper, Blocks.STONE), tough(helper, Blocks.OAK_PLANKS)) == Gives.SECOND,
                "a plank hull against stone: the planks must give, not the stone");
        double cost = ShipMaterials.breakCost(tough(helper, Blocks.OAK_PLANKS), tough(helper, Blocks.STONE),
                SERVER.breakCostMultiplier.get());
        helper.assertTrue(Double.isFinite(cost) && cost > 0, "a plank against stone must be breakable at some energy: " + cost);
        helper.succeed();
    }

    @GameTest(template = FLAT)
    public static void woodNeverBreaksStoneCobblestoneOrDeepslate(GameTestHelper helper) {
        List<BlockState> wood = new ArrayList<>();
        for (Block block : new Block[]{Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS,
                Blocks.CRIMSON_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.CHERRY_PLANKS, Blocks.OAK_LOG, Blocks.DARK_OAK_LOG,
                Blocks.STRIPPED_OAK_LOG, Blocks.OAK_WOOD, Blocks.CRIMSON_STEM, Blocks.OAK_STAIRS, Blocks.OAK_SLAB,
                Blocks.OAK_FENCE, Blocks.OAK_DOOR}) {
            wood.add(block.defaultBlockState());
        }
        Block[] rock = {Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.ANDESITE,
                Blocks.GRANITE, Blocks.DIORITE, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.SMOOTH_STONE};
        for (BlockState hull : wood) {
            for (Block terrain : rock) {
                Gives gives = ShipMaterials.whichGives(tough(helper, terrain), tough(helper, hull));
                helper.assertTrue(gives != Gives.FIRST, "a " + name(hull) + " hull (toughness " + f(tough(helper, hull))
                        + ") would break " + name(terrain.defaultBlockState()) + " (" + f(tough(helper, terrain)) + ")");
            }
        }
        helper.succeed();
    }

    @GameTest(template = FLAT)
    public static void stoneCrushesGrassAndDirt(GameTestHelper helper) {
        helper.assertTrue(ShipMaterials.whichGives(tough(helper, Blocks.GRASS_BLOCK), tough(helper, Blocks.STONE)) == Gives.FIRST,
                "stone landing on grass: the grass must give");
        helper.assertTrue(ShipMaterials.whichGives(tough(helper, Blocks.DIRT), tough(helper, Blocks.STONE)) == Gives.FIRST,
                "stone landing on dirt: the dirt must give");
        helper.assertTrue(ShipMaterials.whichGives(tough(helper, Blocks.STONE), tough(helper, Blocks.STONE)) == Gives.NEITHER,
                "stone on stone: nothing gives");
        helper.succeed();
    }

    @GameTest(template = FLAT)
    public static void obsidianNeverBreaksObsidianButCrushesGrass(GameTestHelper helper) {
        double obsidian = tough(helper, Blocks.OBSIDIAN);
        helper.assertTrue(ShipMaterials.whichGives(obsidian, obsidian) == Gives.NEITHER, "obsidian on obsidian must hold");
        helper.assertTrue(Double.isInfinite(ShipMaterials.breakCost(obsidian, obsidian, SERVER.breakCostMultiplier.get())),
                "obsidian on obsidian must cost an infinite crash");
        helper.assertTrue(ShipMaterials.whichGives(tough(helper, Blocks.GRASS_BLOCK), obsidian) == Gives.FIRST,
                "obsidian into grass: the grass must give");
        double cost = ShipMaterials.breakCost(tough(helper, Blocks.GRASS_BLOCK), obsidian, SERVER.breakCostMultiplier.get());
        helper.assertTrue(Math.abs(cost - 0.75 * SERVER.breakCostMultiplier.get()) < 0.05 * SERVER.breakCostMultiplier.get(),
                "grass under obsidian should cost about its toughness x the multiplier, got " + cost);
        helper.succeed();
    }

    @GameTest(template = FLAT)
    public static void everyHullClearsNaturalLeavesButNotPlacedOnes(GameTestHelper helper) {
        BlockState natural = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false);
        BlockState placed = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockState waterlogged = natural.setValue(LeavesBlock.WATERLOGGED, true);
        helper.assertTrue(ShipMaterials.sweepable(natural), "natural leaves must be swept");
        helper.assertTrue(ShipMaterials.sweepable(placed) == SERVER.sweepPlacedLeaves.get(),
                "player-placed leaves are swept exactly when sweepPlacedLeaves is on");
        helper.assertTrue(!ShipMaterials.sweepable(waterlogged), "waterlogged leaves must never be swept (no water let loose)");
        helper.assertTrue(ShipMaterials.sweepable(Blocks.TALL_GRASS.defaultBlockState()), "tall grass must be swept");
        helper.assertTrue(!ShipMaterials.sweepable(Blocks.WHEAT.defaultBlockState()), "crops must not be swept");
        helper.assertTrue(!ShipMaterials.sweepable(Blocks.KELP.defaultBlockState()), "water plants must not be swept");
        // Even the flimsiest solid hull block is tougher than leaves (and sweeping skips the contest anyway).
        helper.assertTrue(tough(helper, natural) < tough(helper, Blocks.OAK_SLAB.defaultBlockState()),
                "leaves should be weaker than a plank slab");
        double leaf = tough(helper, natural) * SERVER.breakCostMultiplier.get();
        helper.assertTrue(leaf < 1.0, "a leaf should cost under one energy unit to sweep, got " + leaf);
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // Calibration: the defaults against the scenarios they were tuned to
    // ------------------------------------------------------------------

    /** Energy units a ship of {@code kg} has for breaking at {@code mps} into the terrain. */
    private static double budget(double kg, double mps) {
        return 0.5 * EntityStackUtils.kgToUnits(kg) * (mps / 20.0) * (mps / 20.0) * SERVER.terrainEnergyFraction.get();
    }

    @GameTest(template = FLAT)
    public static void calibration(GameTestHelper helper) {
        double c = SERVER.breakCostMultiplier.get();
        double plank = ShipMaterials.breakCost(tough(helper, Blocks.OAK_PLANKS), tough(helper, Blocks.STONE), c);
        double grassUnderStone = ShipMaterials.breakCost(tough(helper, Blocks.GRASS_BLOCK), tough(helper, Blocks.STONE), c);
        double dirtUnderStone = ShipMaterials.breakCost(tough(helper, Blocks.DIRT), tough(helper, Blocks.STONE), c);
        double grassUnderObsidian = ShipMaterials.breakCost(tough(helper, Blocks.GRASS_BLOCK), tough(helper, Blocks.OBSIDIAN), c);
        double grassUnderPlanks = ShipMaterials.breakCost(tough(helper, Blocks.GRASS_BLOCK), tough(helper, Blocks.OAK_PLANKS), c);

        double woodSlow = budget(150_000, 3.0);
        double woodHard = budget(150_000, 8.0);
        double fall = Math.sqrt(2.0 * 10.0 * 10.0); // ten blocks under VS's gravity of 10
        double stoneCube = budget(27 * 2500.0, fall);
        double oneStone = budget(2500.0, fall);
        double obsidianCube = budget(8 * 5000.0, fall);

        LOGGER.info("[FullStop test] calibration (breakCostMultiplier {}, terrainEnergyFraction {}, breakMinSpeed {}):"
                        + " plank-on-stone {} units, grass under stone {}, dirt under stone {}, grass under obsidian {},"
                        + " grass under planks {} | 150 t wood @3 m/s {} units ({} planks), @8 m/s {} ({} planks)"
                        + " | 3x3x3 stone cube falling 10 blocks ({} m/s) {} ({} grass) | one stone block {} | 2x2x2 obsidian {}",
                c, SERVER.terrainEnergyFraction.get(), SERVER.breakMinSpeed.get(), f(plank), f(grassUnderStone),
                f(dirtUnderStone), f(grassUnderObsidian), f(grassUnderPlanks), f(woodSlow), (int) (woodSlow / plank),
                f(woodHard), (int) (woodHard / plank), f(fall), f(stoneCube), (int) (stoneCube / grassUnderStone),
                f(oneStone), f(obsidianCube));

        // A 150 t wooden ship bumping stone at 3 m/s: nothing breaks (below breakMinSpeed anyway,
        // and short of a single plank even if it weren't).
        helper.assertTrue(3.0 < SERVER.breakMinSpeed.get(), "3 m/s must be below breakMinSpeed");
        helper.assertTrue(woodSlow < plank, "a 150 t wooden ship at 3 m/s could break a plank: " + woodSlow + " >= " + plank);
        // ...at 8 m/s head-on: a few planks.
        int planks = (int) (woodHard / plank);
        helper.assertTrue(8.0 >= SERVER.breakMinSpeed.get(), "8 m/s must reach breakMinSpeed");
        helper.assertTrue(planks >= 2 && planks <= 6, "a 150 t wooden ship at 8 m/s should lose a few planks, not " + planks);
        // A 3x3x3 stone cube dropped ten blocks crushes the grass under it (all nine would need 9 x cost).
        helper.assertTrue(stoneCube >= 8 * grassUnderStone, "a stone cube falling 10 blocks should crush a layer of grass");
        // 2x2x2 obsidian dropped ten blocks onto grass: crushes it.
        helper.assertTrue(obsidianCube >= 4 * grassUnderObsidian, "an obsidian cube falling 10 blocks should crush grass");
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // Sounds
    // ------------------------------------------------------------------

    @GameTest(template = FLAT)
    public static void impactSoundsGetLouderAndDeeperWithSpeedAndMass(GameTestHelper helper) {
        double[] masses = {100, 1_000, 13_500, 150_000, 10_000_000};
        for (double mass : masses) {
            double lastVolume = -1, lastPitch = Double.MAX_VALUE;
            for (double speed = 0.0; speed <= 40.0; speed += 0.25) {
                double volume = ShipImpactSounds.volume(speed, mass);
                double pitch = ShipImpactSounds.pitch(speed, mass);
                helper.assertTrue(volume >= lastVolume - 1.0e-12, "volume fell with speed at " + speed + " m/s, " + mass + " kg");
                helper.assertTrue(pitch <= lastPitch + 1.0e-12, "pitch rose with speed at " + speed + " m/s, " + mass + " kg");
                helper.assertTrue(volume > 0 && pitch > 0, "volume and pitch must stay positive");
                lastVolume = volume;
                lastPitch = pitch;
            }
        }
        for (double speed = 1.0; speed <= 30.0; speed += 1.0) {
            double lastVolume = -1, lastPitch = Double.MAX_VALUE;
            for (double mass : masses) {
                helper.assertTrue(ShipImpactSounds.volume(speed, mass) >= lastVolume, "volume fell with mass at " + speed);
                helper.assertTrue(ShipImpactSounds.pitch(speed, mass) <= lastPitch, "pitch rose with mass at " + speed);
                lastVolume = ShipImpactSounds.volume(speed, mass);
                lastPitch = ShipImpactSounds.pitch(speed, mass);
            }
        }
        helper.assertTrue(ShipImpactSounds.pitch(20, 150_000) < ShipImpactSounds.pitch(2, 150_000) - 0.3,
                "a hard hit must sound clearly deeper than a tap");
        LOGGER.info("[FullStop test] impact sound (150 t): 2 m/s vol {} pitch {} | 8 m/s vol {} pitch {} | 20 m/s vol {} pitch {}",
                f(ShipImpactSounds.volume(2, 150_000)), f(ShipImpactSounds.pitch(2, 150_000)),
                f(ShipImpactSounds.volume(8, 150_000)), f(ShipImpactSounds.pitch(8, 150_000)),
                f(ShipImpactSounds.volume(20, 150_000)), f(ShipImpactSounds.pitch(20, 150_000)));
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // Tags: Vanilla Backport's blocks and mobs are optional entries
    // ------------------------------------------------------------------

    /** A missing REQUIRED entry would drop the whole tag; these must load without Vanilla Backport. */
    @GameTest(template = FLAT)
    public static void tagsWithOptionalVanillaBackportEntriesStillLoad(GameTestHelper helper) {
        helper.assertTrue(EntityType.SLIME.is(FullStopTags.KINETIC_IMMUNE), "fullstop:kinetic_immune didn't load");
        helper.assertTrue(Blocks.MOSS_BLOCK.defaultBlockState().is(FullStopTags.CUSHIONING), "fullstop:cushioning didn't load");
        helper.assertTrue(Blocks.TALL_GRASS.defaultBlockState().is(FullStopTags.SHIP_SWEEPABLE), "fullstop:ship_sweepable didn't load");
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // Config: the retired keys in an old world's file
    // ------------------------------------------------------------------

    @GameTest(template = FLAT)
    public static void anOldConfigWithTheRetiredKeysLoadsClean(GameTestHelper helper) {
        String section = SERVER.hullDamage.getPath().get(0);
        CommentedConfig old = CommentedConfig.inMemory();
        old.set(List.of(section, "hullHardnessAdvantage"), 1.5);
        old.set(List.of(section, "hullBlocksCanBreak"), false);
        old.set(List.of(section, "terrainBreakCostMultiplier"), 1.0);
        old.set(List.of(section, "terrainMinSpeed"), 8.0);
        int corrections = FullStopConfig.SERVER_SPEC.correct(old);
        helper.assertTrue(!old.contains(List.of(section, "hullHardnessAdvantage")), "hullHardnessAdvantage should be dropped");
        helper.assertTrue(!old.contains(List.of(section, "terrainMinSpeed")), "terrainMinSpeed should be dropped");
        helper.assertTrue(Boolean.TRUE.equals(old.get(List.of(section, "hullDamage"))), "hullDamage should arrive at its default");
        Object multiplier = old.get(List.of(section, "breakCostMultiplier"));
        helper.assertTrue(multiplier instanceof Number n && n.doubleValue() == 8.0,
                "breakCostMultiplier should arrive at its default 8.0, got " + multiplier);
        LOGGER.info("[FullStop test] old ship-collision keys corrected cleanly ({} corrections)", corrections);
        helper.succeed();
    }
}
