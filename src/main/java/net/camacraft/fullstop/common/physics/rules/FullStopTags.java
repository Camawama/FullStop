package net.camacraft.fullstop.common.physics.rules;

import net.camacraft.fullstop.FullStop;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/**
 * Data-driven rule tags so packs and other mods can extend FullStop's block/entity
 * lists without code changes. Definitions live under data/fullstop/tags/.
 */
public final class FullStopTags {
    private FullStopTags() {
    }

    /**
     * Blocks that shatter easily and count as nearly zero hardness on impact
     * (glass, snow). The solid ice family is deliberately NOT here — it is
     * {@link #CRACKABLE} instead: real hardness, with persistent impact cracks.
     */
    public static final TagKey<Block> FRAGILE = blockTag("fragile");

    /**
     * Blocks that accumulate PERSISTENT impact damage (the ice family): each
     * sub-break hit adds a visible mining-crack overlay on the block and lowers
     * the energy needed to finish it, so pristine ice is tough while cracked
     * ice shatters from the next good hit. Cracks heal after ~30 s without
     * hits. See KineticBlockInteractions.
     */
    public static final TagKey<Block> CRACKABLE = blockTag("crackable");

    /** Blocks that absorb most of an impact (hay). Damage is multiplied by 0.3. */
    public static final TagKey<Block> SOFT_LANDING = blockTag("soft_landing");

    /** Blocks that soften an impact (wool, leaves, moss...). Damage is multiplied by 0.7. */
    public static final TagKey<Block> CUSHIONING = blockTag("cushioning");

    /**
     * Blocks that living entities moving faster than the configured
     * phaseMinimumSpeed pass through instead of colliding with (leaves, plus
     * everything in {@link #ENGULFING}). No impact damage, no breaking.
     */
    public static final TagKey<Block> PHASEABLE = blockTag("phaseable");

    /**
     * Phaseable blocks that swallow fast movers (sand, gravel, snow): heavy drag
     * bleeds their speed off within a few blocks, and once below phase speed they
     * are embedded and must dig their way out. Included in {@link #PHASEABLE}.
     */
    public static final TagKey<Block> ENGULFING = blockTag("engulfing");

    /**
     * Blocks that fall like sand when nothing supports them. Non-sticky entries
     * need a block below, exactly like sand; entries that are also in
     * {@link #STICKY} count a touching block on any face as support.
     */
    public static final TagKey<Block> GRAVITY_AFFECTED = blockTag("gravity_affected");

    /**
     * Gravity-affected blocks that cling to any neighboring block (slime, honey),
     * so they only fall when floating completely free. Included in
     * {@link #GRAVITY_AFFECTED}.
     */
    public static final TagKey<Block> STICKY = blockTag("sticky");

    /**
     * Blocks that slow entities moving BESIDE them (honey, the soul sand
     * family): a viscous/clinging field applied by BlockPhasing when the
     * entity's box brushes such a block at body height. Standing on top is
     * unaffected (that's the block's own walk-speed factor).
     */
    public static final TagKey<Block> SLOWING = blockTag("slowing");

    /** Entities that never take kinetic damage (bosses, flying/agile/soft-bodied mobs). */
    public static final TagKey<EntityType<?>> KINETIC_IMMUNE = entityTag("kinetic_immune");

    /**
     * Entities FullStop ignores COMPLETELY — no velocity tracking, no
     * collisions, no bounces, no damage. Empty by default; a blacklist for
     * modded entities that manage their own physics and fight FullStop's.
     */
    public static final TagKey<EntityType<?>> PHYSICS_BLACKLIST = entityTag("physics_blacklist");

    /**
     * Projectile-type entities that get FullStop's FULL physics: slime/bed
     * bounces, water skips, kinetic block cracking and breaking, and entity
     * collision damage. Projectiles are normally excluded — vanilla ones own
     * their impacts (arrows stick, tridents return) — but modded projectiles
     * like grappling hooks can opt in here: the one-tick-lookahead reactions
     * fire BEFORE the projectile's own onHit does. How hard a light projectile
     * hits is tuned via its weight in Camafy's config/camafy-weight.toml (mass
     * drives block breaking).
     * Empty by default.
     */
    public static final TagKey<EntityType<?>> PHYSICS_PROJECTILES = entityTag("physics_projectiles");

    /**
     * Projectiles that never collide with other projectiles in the air (ProjectileCollisions): fishing
     * bobbers, firework rockets, grappling hooks - their hits are their own business. Every other
     * projectile, vanilla or modded, collides.
     */
    public static final TagKey<EntityType<?>> NO_PROJECTILE_COLLISION = entityTag("no_projectile_collision");

    /**
     * Blocks a Valkyrien Skies ship never breaks, however hard it hits them, and that never break
     * off a hull either: infinitely tough. Contains #minecraft:wither_immune.
     */
    public static final TagKey<Block> SHIP_UNBREAKABLE = blockTag("ship_unbreakable");

    /**
     * Soft vegetation a moving Valkyrien Skies ship ploughs straight through, breaking it (leaves,
     * saplings, flowers, tall grass, vines, sugar cane, berry bushes...). VS lets ships pass
     * through leaves without a collision, so FullStop checks the hull against these itself. No
     * water plants: breaking those would leave water where a hull just was.
     */
    public static final TagKey<Block> SHIP_SWEEPABLE = blockTag("ship_sweepable");

    /**
     * Blocks a Valkyrien Skies ship bounces off, or with (slime): a hull meeting terrain or another
     * hull where either block is one springs back at slimeRestitution; bouncy on both sides
     * springs back the most. Unless honey ({@link #GLUEY}) is in the same crash: honey wins.
     */
    public static final TagKey<Block> BOUNCY = blockTag("bouncy");

    /**
     * Blocks a Valkyrien Skies ship sticks to, or with (honey): a hull meeting terrain or another
     * hull where either block is one doesn't bounce, softens the crash, and is glued there until
     * pulled apart hard enough (honeyGlueStrength).
     */
    public static final TagKey<Block> GLUEY = blockTag("gluey");

    /**
     * Projectiles that bounce off slime (SlimeProjectiles): pointy ones (arrows and tridents) skip off it at a shallow
     * angle and stick when shot steeply in; the rest (thrown snowballs, eggs, potions, ender pearls, fire charges and
     * fireballs, firework rockets) bounce at any angle.
     */
    public static final TagKey<EntityType<?>> BOUNCES_ON_SLIME = entityTag("bounces_on_slime");

    /**
     * Pointy projectiles besides every arrow-like ({@code AbstractArrow}: arrows, tridents): they stick in slime when
     * they come in steeply, and skip off it only at a shallow angle (SlimeProjectiles). Arrows and the trident.
     */
    public static final TagKey<EntityType<?>> POINTY_PROJECTILES = entityTag("pointy_projectiles");

    /** Dropped items that bounce off what they land on or hit (SlimeProjectiles): slime balls and slime blocks. */
    public static final TagKey<net.minecraft.world.item.Item> BOUNCY_ITEMS = TagKey.create(Registries.ITEM,
            new ResourceLocation(FullStop.MODID, "bouncy_items"));

    /** Blocks that are foliage whatever they are made of (Foliage): running into them never hurts. Empty by default. */
    public static final TagKey<Block> FOLIAGE = blockTag("foliage");

    /** Blocks that are never foliage, whatever they look like (Foliage). Empty by default. */
    public static final TagKey<Block> NOT_FOLIAGE = blockTag("not_foliage");

    /** Entities a moving Valkyrien Skies ship never hits (no push, no damage). Empty by default. */
    public static final TagKey<EntityType<?>> SHIP_IMMUNE = entityTag("ship_immune");

    // ---- cached tag answers (2026-10-23) --------------------------------------------------------------------------
    //
    // A tag test hashes the TagKey (a record: its hash is worked out again on every call) into the holder's tag set.
    // The phaseable test ran for every block shape query near every moving body (PhaseableBlockMixin) and the
    // blacklist test for every body every tick from several hooks: about 330 ms of every 30 s with 400 mobs walking
    // about (pack harness perf_pack, spark). Their answers only change when tags (re)load.

    private static volatile int epoch;
    /** Per entity type: (epoch << 1) | blacklisted, for the epoch it was read in. */
    private static final java.util.Map<EntityType<?>, Integer> BLACKLISTED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Bumped whenever tags load or reload, on either side: every cached tag answer is read again after it. */
    public static int epoch() {
        return epoch;
    }

    /** Forge bus, both sides: data packs (re)loaded on the server, tags received on the client. */
    public static void onTagsUpdated(net.minecraftforge.event.TagsUpdatedEvent event) {
        epoch++;
    }

    /** {@code type.is(PHYSICS_BLACKLIST)}, read once per entity type until tags reload. Any thread. */
    public static boolean physicsBlacklisted(EntityType<?> type) {
        int now = epoch;
        Integer cached = BLACKLISTED.get(type);
        if (cached != null && (cached >>> 1) == now) return (cached & 1) != 0;
        boolean value = type.is(PHYSICS_BLACKLIST);
        BLACKLISTED.put(type, (now << 1) | (value ? 1 : 0));
        return value;
    }

    private static TagKey<Block> blockTag(String name) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation(FullStop.MODID, name));
    }

    private static TagKey<EntityType<?>> entityTag(String name) {
        return TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(FullStop.MODID, name));
    }
}
