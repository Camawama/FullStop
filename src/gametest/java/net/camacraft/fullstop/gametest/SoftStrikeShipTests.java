package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Locale;

import static net.camacraft.fullstop.gametest.ShipCollisionTests.Rig;
import static net.camacraft.fullstop.gametest.ShipCollisionTests.fillSet;

/**
 * A soft hull strikes softly ({@code softStrikeDamage}): a 3x3x3 ship of wool and one of stone, side by side, pushed at
 * 25 m/s into a hovering pig each (1000 health, so the hit can be read). The wool ship still launches its pig (carried
 * along at the hull's speed, springing back less) but deals a tenth of the damage or less.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class SoftStrikeShipTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = "flat_48x8x48";
    private static final String BATCH = "fullstop_soft_strike";
    private static final float HEALTH = 1000.0f;
    private static final double SPEED = 25.0;

    private SoftStrikeShipTests() {
    }

    @GameTest(template = FLAT, timeoutTicks = 600, batch = BATCH)
    public static void aWoolShipRammingAPigShovesItButBarelyHurtsIt(GameTestHelper helper) {
        Side wool = new Side(helper, 0, Blocks.WHITE_WOOL);
        Side stone = new Side(helper, 1, Blocks.STONE);
        if (wool.rig == null || stone.rig == null) return;
        helper.onEachTick(() -> {
            wool.tick();
            stone.tick();
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    wool.rig.demandReady();
                    stone.rig.demandReady();
                })
                .thenExecute(() -> {
                    wool.spawnPig();
                    stone.spawnPig();
                    wool.rig.release();
                    stone.rig.release();
                })
                .thenWaitUntil(() -> {
                    wool.rig.demandSimulated();
                    stone.rig.demandSimulated();
                })
                .thenExecute(() -> {
                    wool.rig.push(new Vec3(SPEED, 0.0, 0.0));
                    stone.rig.push(new Vec3(SPEED, 0.0, 0.0));
                })
                .thenWaitUntil(() -> helper.assertTrue(!Double.isNaN(wool.launch) && !Double.isNaN(stone.launch),
                        "the ships haven't both hit their pigs yet (wool " + wool.rig.ref.position() + ", stone " + stone.rig.ref.position() + ")"))
                .thenIdle(3)
                .thenExecute(() -> {
                    double woolHurt = HEALTH - wool.pig.getHealth();
                    double stoneHurt = HEALTH - stone.pig.getHealth();
                    wool.rig.pin();
                    stone.rig.pin();
                    String out = String.format(Locale.ROOT, "wool ship %.1f m/s at the hit: pig launched %.1f m/s, damage %.1f; stone ship %.1f m/s: "
                            + "pig launched %.1f m/s, damage %.1f", wool.shipAt, wool.launch, woolHurt, stone.shipAt, stone.launch, stoneHurt);
                    LOGGER.info("[FullStop ship test] soft strike, ships at {} m/s into pigs: {}", SPEED, out);
                    helper.assertTrue(stoneHurt > 5.0, "the stone ship should hurt: " + out);
                    helper.assertTrue(woolHurt <= Math.max(1.0, 0.15 * stoneHurt), "the wool ship hurt too much: " + out);
                    helper.assertTrue(wool.launch > 0.6 * stone.launch, "the wool ship hardly shoved its pig: " + out);
                    helper.succeed();
                });
    }

    /** One ship and its pig, in a lane of its own (lanes 200 and up of the ship tests' range). */
    private static final class Side {
        final ServerLevel level;
        final BlockPos o;
        final Rig rig;
        Mob pig;
        double before;
        double launch = Double.NaN;
        double shipAt = Double.NaN;

        Side(GameTestHelper helper, int lane, Block block) {
            this.level = helper.getLevel();
            this.o = ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (200 + lane) * 256);
            for (int cx = (o.getX() - 16) >> 4; cx <= (o.getX() + 64) >> 4; cx++) {
                for (int cz = (o.getZ() - 16) >> 4; cz <= (o.getZ() + 32) >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
            this.rig = Rig.build(helper, fillSet(level, o.offset(0, 1, 0), o.offset(2, 3, 2), block.defaultBlockState()), block, true);
            if (rig != null) helper.onEachTick(rig::record);
        }

        void spawnPig() {
            Mob p = EntityType.PIG.create(level);
            if (p == null) throw new IllegalStateException("no pig");
            // hovering at the hull's height, nothing behind it
            p.moveTo(o.getX() + 12.5, o.getY() + 2.0, o.getZ() + 1.5, -90.0f, 0.0f);
            p.setNoGravity(true);
            p.setNoAi(true);
            p.setPersistenceRequired();
            p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTH);
            p.setHealth(HEALTH);
            level.addFreshEntity(p);
            pig = p;
        }

        void tick() {
            if (pig == null || !Double.isNaN(launch)) return;
            double vx = pig.getDeltaMovement().x * 20.0;
            Vec3 ship = rig.ref.velocity();
            if (vx > 1.0) {
                launch = vx;
                shipAt = ship == null ? Double.NaN : ship.x;
            } else {
                before = vx;
            }
        }
    }
}
