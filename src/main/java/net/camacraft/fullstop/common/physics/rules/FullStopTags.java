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
     * Projectile-type entities that get FullStop's collision physics (slime
     * bounces above all). Projectiles are normally excluded — vanilla ones own
     * their impacts (arrows stick, tridents return) — but modded projectiles
     * like grappling hooks can opt in here: the one-tick-lookahead bounce
     * redirects them BEFORE their own onHit fires. Empty by default.
     */
    public static final TagKey<EntityType<?>> BOUNCING_PROJECTILES = entityTag("bouncing_projectiles");

    private static TagKey<Block> blockTag(String name) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation(FullStop.MODID, name));
    }

    private static TagKey<EntityType<?>> entityTag(String name) {
        return TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(FullStop.MODID, name));
    }
}
