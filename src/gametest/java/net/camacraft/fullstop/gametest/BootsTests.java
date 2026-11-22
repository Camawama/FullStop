package net.camacraft.fullstop.gametest;

import com.mojang.logging.LogUtils;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.camacraft.fullstop.server.physics.damage.Stomp;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Map;

/**
 * Stomp (boots, I to III): its numbers, who may be stomped, and a real landing under Full Stop beside a villager in plain
 * boots: a stomp onto a penned pig (it takes part of the stomper's landing, the stomper is spared that part). Also that
 * the Rebound enchantment of 2.0.2 is gone (2.0.3) and boots that still hold it in their data read their other
 * enchantments as before. Opt-in with the rest: {@code gradlew runGameTestServer -PshipTests -PtestOnly=BootsTests}.
 */
@GameTestHolder(FullStop.MODID)
@PrefixGameTestTemplate(false)
public final class BootsTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLAT = ShipMaterialTests.FLAT;

    private BootsTests() {
    }

    /** Stomp passes 20/40/60 percent. */
    @GameTest(template = FLAT, batch = "fullstop_boots", timeoutTicks = 20)
    public static void theNumbers(GameTestHelper helper) {
        String out = String.format(Locale.ROOT, "stomp %.2f/%.2f/%.2f", Stomp.share(1), Stomp.share(2), Stomp.share(3));
        report("Stomp's numbers", out);
        helper.assertTrue(near(Stomp.share(1), 0.2) && near(Stomp.share(2), 0.4) && near(Stomp.share(3), 0.6), "Stomp's shares: " + out);
        helper.succeed();
    }

    /**
     * Rebound (2.0.2) is no longer registered, and boots enchanted with it before keep their other enchantments: vanilla
     * reads an item's enchantments by id and skips one no mod registers, leaving the entry in the item's data.
     */
    @GameTest(template = FLAT, batch = "fullstop_boots", timeoutTicks = 20)
    public static void bootsThatStillHoldReboundKeepTheirOtherEnchantments(GameTestHelper helper) {
        ResourceLocation rebound = new ResourceLocation(FullStop.MODID + ":rebound");
        boolean registered = ForgeRegistries.ENCHANTMENTS.containsKey(rebound);
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        ListTag list = new ListTag();
        list.add(entry(rebound, 3));
        list.add(entry(ForgeRegistries.ENCHANTMENTS.getKey(ModEnchantments.STOMP.get()), 2));
        list.add(entry(new ResourceLocation("minecraft:feather_falling"), 4));
        boots.getOrCreateTag().put(ItemStack.TAG_ENCH, list);
        Map<Enchantment, Integer> read = EnchantmentHelper.getEnchantments(boots);
        int stomp = EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.STOMP.get(), boots);
        int featherFalling = EnchantmentHelper.getItemEnchantmentLevel(net.minecraft.world.item.enchantment.Enchantments.FALL_PROTECTION, boots);
        int kept = boots.getEnchantmentTags().size();
        String out = String.format(Locale.ROOT, "rebound registered %s; read %d enchantments (Stomp %d, Feather Falling %d) of %d entries kept in the data",
                registered, read.size(), stomp, featherFalling, kept);
        report("boots that still hold Rebound", out);
        helper.assertFalse(registered, "Rebound is still registered: " + out);
        helper.assertTrue(read.size() == 2 && stomp == 2 && featherFalling == 4, "the other enchantments weren't read: " + out);
        helper.assertTrue(kept == 3, "the item's data was changed by reading it: " + out);
        helper.succeed();
    }

    /** Never one's own pet, a stack one rides, or itself; another's pet as any body; a stomp only from a landing that hurts. */
    @GameTest(template = FLAT, batch = "fullstop_boots", timeoutTicks = 20)
    public static void whoMayBeStomped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 0);
        Villager stomper = villager(level, Vec3.atBottomCenterOf(o.above()), boots(ModEnchantments.STOMP.get(), 3));
        Villager stranger = villager(level, Vec3.atBottomCenterOf(o.above().east(3)), new ItemStack(Items.IRON_BOOTS));
        Wolf own = EntityType.WOLF.create(level);
        Wolf theirs = EntityType.WOLF.create(level);
        Pig mount = EntityType.PIG.create(level);
        if (own == null || theirs == null || mount == null) throw new IllegalStateException("no wolf or pig");
        for (LivingEntity e : new LivingEntity[] {own, theirs, mount}) {
            e.moveTo(o.getX() + 0.5, o.getY() + 1.0, o.getZ() + 4.5, 0.0f, 0.0f);
            level.addFreshEntity(e);
        }
        own.setTame(true);
        own.setOwnerUUID(stomper.getUUID());
        theirs.setTame(true);
        theirs.setOwnerUUID(stranger.getUUID());
        String out = String.format(Locale.ROOT, "own pet %s, another's pet %s, a stranger %s, itself %s; stomps instead of riding at 20 m/s %s, "
                        + "at 8 m/s %s",
                Stomp.mayStomp(stomper, own), Stomp.mayStomp(stomper, theirs), Stomp.mayStomp(stomper, stranger),
                Stomp.mayStomp(stomper, stomper), Stomp.stompsInsteadOfRiding(stomper, 20.0), Stomp.stompsInsteadOfRiding(stomper, 8.0));
        helper.assertFalse(Stomp.mayStomp(stomper, own), "its own pet: " + out);
        helper.assertTrue(Stomp.mayStomp(stomper, theirs), "another's pet: " + out);
        helper.assertTrue(Stomp.mayStomp(stomper, stranger), "a stranger: " + out);
        helper.assertFalse(Stomp.mayStomp(stomper, stomper), "itself: " + out);
        helper.assertTrue(Stomp.stompsInsteadOfRiding(stomper, 20.0), "a hard landing rides: " + out);
        helper.assertFalse(Stomp.stompsInsteadOfRiding(stomper, 8.0), "a hop stomps: " + out);
        stomper.startRiding(mount, true);
        boolean onMount = Stomp.mayStomp(stomper, mount);
        out += ", the pig it rides " + onMount;
        report("who may be stomped", out);
        helper.assertFalse(onMount, "the body it rides: " + out);
        for (LivingEntity e : new LivingEntity[] {stomper, stranger, own, theirs, mount}) e.discard();
        helper.succeed();
    }

    /**
     * A villager in Stomp III iron boots and one in plain iron boots dropped 16 blocks onto a penned standing pig each (40
     * health): the stomped pig loses more, the stomper less, the stomper's saving and the pig's extra each about 60
     * percent of the plain villager's landing.
     */
    @GameTest(template = FLAT, batch = "fullstop_boots_stomp", timeoutTicks = 220)
    public static void aStompPassesPartOfTheLandingToThePig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane(helper, 1);
        floor(level, o);
        Body[] pigs = new Body[2];
        Body[] landers = new Body[2];
        for (int i = 0; i < 2; i++) {
            BlockPos cell = o.offset(2 + i * 5, 1, 2);
            for (BlockPos p : new BlockPos[] {cell.north(), cell.south(), cell.east(), cell.west()}) {
                level.setBlock(p, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(p.above(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            }
            Pig pig = EntityType.PIG.create(level);
            if (pig == null) throw new IllegalStateException("no pig");
            pig.moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0.0f, 0.0f);
            pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40.0);
            pig.setHealth(40.0f);
            pig.setPersistenceRequired();
            level.addFreshEntity(pig);
            pigs[i] = new Body(pig);
            ItemStack b = i == 0 ? boots(ModEnchantments.STOMP.get(), 3) : new ItemStack(Items.IRON_BOOTS);
            landers[i] = new Body(villager(level, new Vec3(cell.getX() + 0.5, cell.getY() + 16.0, cell.getZ() + 0.5), b));
        }
        helper.onEachTick(() -> {
            for (Body b : pigs) b.record();
            for (Body b : landers) b.record();
        });
        helper.runAfterDelay(140, () -> {
            String out = String.format(Locale.ROOT, "Stomp III: pig lost %.2f, villager lost %.2f; plain: pig lost %.2f, villager lost %.2f",
                    pigs[0].lost, landers[0].lost, pigs[1].lost, landers[1].lost);
            report("16-block drop onto a penned pig, Stomp III beside plain boots", out);
            double plainLanding = landers[1].lost;
            helper.assertTrue(plainLanding > 2.0, "the plain villager's landing too small to compare: " + out);
            double extra = pigs[0].lost - pigs[1].lost;
            double spared = landers[1].lost - landers[0].lost;
            helper.assertTrue(extra > 0.35 * plainLanding && extra < 0.85 * plainLanding, "the stomped pig's extra: " + out);
            helper.assertTrue(spared > 0.35 * plainLanding && spared < 0.85 * plainLanding, "the stomper's saving: " + out);
            for (Body b : pigs) b.body.discard();
            for (Body b : landers) b.body.discard();
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------

    /** A body: health lost, hit by hit. */
    private static final class Body {
        final LivingEntity body;
        final float health;
        double lost;
        float lastHp;
        final java.util.List<Double> hits = new java.util.ArrayList<>();

        Body(LivingEntity body) {
            this.body = body;
            this.health = body.getHealth();
            this.lastHp = this.health;
        }

        void record() {
            float hp = body.isAlive() ? body.getHealth() : 0.0f;
            lost = Math.max(lost, health - hp);
            if (hp < lastHp - 1.0e-4f) hits.add((double) (lastHp - hp));
            lastHp = hp;
        }
    }

    private static CompoundTag entry(ResourceLocation id, int level) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id.toString());
        tag.putShort("lvl", (short) level);
        return tag;
    }

    private static void floor(ServerLevel level, BlockPos o) {
        for (BlockPos pos : BlockPos.betweenClosed(o, o.offset(10, 0, 4))) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static ItemStack boots(Enchantment e, int lvl) {
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        boots.enchant(e, lvl);
        return boots;
    }

    private static Villager villager(ServerLevel level, Vec3 at, ItemStack boots) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) throw new IllegalStateException("no villager");
        villager.moveTo(at.x, at.y, at.z, -90.0f, 0.0f);
        villager.setPersistenceRequired();
        villager.setItemSlot(EquipmentSlot.FEET, boots);
        level.addFreshEntity(villager);
        return villager;
    }

    private static BlockPos lane(GameTestHelper helper, int lane) {
        return ShipCollisionTests.origin(helper, ShipCollisionTests.AWAY + 3072 + (260 + lane) * 256);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }

    private static void report(String what, String result) {
        LOGGER.info("[FullStop gametest] {}: {}", what, result);
    }
}
