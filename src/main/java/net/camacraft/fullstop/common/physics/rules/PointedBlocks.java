package net.camacraft.fullstop.common.physics.rules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.VanillaFall;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.server.physics.damage.DamageMitigation;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Pointed blocks, from data: running, falling or being driven into a block's point hurts more the faster it is met
 * ({@code pointedBlocks} in the server config). The owner, 2026-10-24: "FullStop should have another data driven thing
 * ... for pointed things like drip-stone etc, but also the bamboo spikes from supplementaries. Moving faster into the
 * spikes should deal more damage, same as a ship with spikes moving faster when hitting an entity."
 *
 * <p><b>Data:</b> {@code data/<namespace>/fullstop/pointed_blocks/<name>.json}, one rule per file:
 * <pre>{@code
 * {
 *   "blocks": ["minecraft:pointed_dripstone", "#mymod:spikes"],  // ids and tags; unknown ids are skipped
 *   "point": "vertical_direction",  // a block-state property holding the point's direction (dripstone's
 *                                   // vertical_direction, the spikes' facing), a fixed
 *                                   // direction ("up", "north", ...), or "all" (pointed on every side)
 *   "passable": false,              // true: the point is inside the block's space (a thin base plate):
 *                                   // met from entityInside, as Supplementaries' bamboo spikes
 *   "multiplier": 2.0,              // optional: damage per m/s over the threshold; default the server
 *                                   // config's dripstoneDamageMultiplier
 *   "threshold": 5.0,               // optional, m/s: where the speed starts to add; default Full Stop's
 *                                   // own damage threshold along the point (vertical or horizontal)
 *   "min_speed": 4.0,               // below this into the point nothing happens (default 4.0)
 *   "side_damage": 2.0,             // optional: a flat hit for ramming its side (default none)
 *   "damage_type": "minecraft:stalagmite"  // optional: the damage type of a hit into the point
 * }
 * }</pre>
 * A later file naming the same block replaces an earlier one. Into the point means moving against the point's
 * direction: the speed into it is the velocity (relative to the block; a ship block's own motion and rotation counted)
 * along the opposite of the point, and it must be most of the motion (within 60 degrees). Such a hit deals
 * {@code (1 + max(speed - threshold, 0) * multiplier) * kineticDamageMultiplier}; in VANILLA_PARITY mode vanilla's
 * stalagmite rule ({@code (fall distance - 1) * multiplier}) for the fall distance of that speed. Pointed dripstone keeps
 * exactly the numbers its old hardcoded rule had (the upward point a fall's stalagmite, any other ram of it 2).
 *
 * <p><b>Where:</b> a block impact ({@link KineticDamageCalculator}, {@link #collisionHit}), a passable point met from
 * inside its block ({@link #entityInside}, mixin {@code PointedEntityInsideMixin}), and a ship's hull: a body running
 * into a ship's point, or a ship's point struck into a body ({@link #hullDamage}), the point turned with the ship.
 */
@Mod.EventBusSubscriber(modid = FullStop.MODID)
public final class PointedBlocks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    /** Into the point means most of the motion: within 60 degrees of straight at it. */
    private static final double INTO_COS = 0.5;
    /** A ship's point aims at a body when it is within 45 degrees of the contact normal. */
    private static final double AIM_COS = 0.7;
    /** A side ram's least stopping force (m/s): about walking pace, as dripstone's sideways rule had it. */
    private static final double SIDE_MIN_STOPPING = 2.0;

    /** One rule from data. {@code point} null with {@code all}: every side is a point. */
    public record Rule(ResourceLocation id, List<Block> blocks, List<TagKey<Block>> tags, @Nullable String property,
                       @Nullable Direction fixed, boolean all, boolean passable, double multiplier, double threshold,
                       double minSpeed, double sideDamage, @Nullable ResourceKey<DamageType> damageType) {

        /** The point's direction in the block's own frame, or null when the state doesn't say (or every side is one). */
        @Nullable
        public Direction tip(BlockState state) {
            if (fixed != null) return fixed;
            if (property == null) return null;
            for (Property<?> p : state.getProperties()) {
                if (p.getName().equals(property) && state.getValue(p) instanceof Direction d) return d;
            }
            return null;
        }

        public double multiplierOrDefault() {
            return Double.isNaN(multiplier) ? SERVER.dripstoneDamageMultiplier.get() : multiplier;
        }
    }

    /** A hit into a point: the rule, the struck state and the speed into the point (m/s). */
    public record Hit(Rule rule, BlockState state, double speed, Vec3 tip) {
    }

    private static volatile Map<Block, Rule> byBlock = Map.of();
    private static volatile List<Rule> byTag = List.of();
    private static volatile int epoch;

    private PointedBlocks() {
    }

    /** Bumped whenever the rules or the tags change: the per-state caches read the rules again. */
    public static int epoch() {
        return epoch;
    }

    public static boolean enabled() {
        return SERVER.pointedBlocks.get();
    }

    /** The rule for a state, or null when it isn't pointed. */
    @Nullable
    public static Rule ruleFor(BlockState state) {
        Map<Block, Rule> blocks = byBlock;
        Rule rule = blocks.get(state.getBlock());
        if (rule != null) return rule;
        for (Rule r : byTag) {
            for (TagKey<Block> tag : r.tags()) {
                if (state.is(tag)) return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Loader());
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        epoch++;
    }

    private static final class Loader extends SimpleJsonResourceReloadListener {
        Loader() {
            super(GSON, "fullstop/pointed_blocks");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<Block, Rule> blocks = new IdentityHashMap<>();
            List<Rule> tagged = new ArrayList<>();
            files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                try {
                    Rule rule = parse(e.getKey(), e.getValue().getAsJsonObject());
                    if (rule == null) return;
                    for (Block b : rule.blocks()) blocks.put(b, rule);
                    if (!rule.tags().isEmpty()) tagged.add(0, rule); // a later file's tags are asked first
                } catch (RuntimeException ex) {
                    LOGGER.warn("[FullStop] pointed block rule {} can't be read: {}", e.getKey(), ex.toString());
                }
            });
            byBlock = blocks;
            byTag = List.copyOf(tagged);
            epoch++;
            LOGGER.info("[FullStop] {} pointed blocks from data ({} rules by tag)", blocks.size(), tagged.size());
        }
    }

    @Nullable
    static Rule parse(ResourceLocation id, JsonObject o) {
        List<Block> blocks = new ArrayList<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        JsonArray list = o.getAsJsonArray("blocks");
        if (list != null) {
            for (JsonElement el : list) {
                String s = el.getAsString();
                if (s.startsWith("#")) {
                    tags.add(TagKey.create(Registries.BLOCK, new ResourceLocation(s.substring(1))));
                } else {
                    ResourceLocation key = new ResourceLocation(s);
                    // a rule for another mod's block loads fine without that mod: its id is skipped
                    if (ForgeRegistries.BLOCKS.containsKey(key)) blocks.add(ForgeRegistries.BLOCKS.getValue(key));
                }
            }
        }
        if (blocks.isEmpty() && tags.isEmpty()) {
            LOGGER.debug("[FullStop] pointed block rule {} names no block that is here; skipped", id);
            return null;
        }
        String point = o.has("point") ? o.get("point").getAsString().toLowerCase(Locale.ROOT) : "all";
        Direction fixed = Direction.byName(point);
        boolean all = point.equals("all");
        String property = fixed == null && !all ? point : null;
        ResourceKey<DamageType> type = o.has("damage_type")
                ? ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(o.get("damage_type").getAsString())) : null;
        return new Rule(id, List.copyOf(blocks), List.copyOf(tags), property, fixed, all,
                o.has("passable") && o.get("passable").getAsBoolean(),
                o.has("multiplier") ? o.get("multiplier").getAsDouble() : Double.NaN,
                o.has("threshold") ? o.get("threshold").getAsDouble() : Double.NaN,
                o.has("min_speed") ? o.get("min_speed").getAsDouble() : 4.0,
                o.has("side_damage") ? o.get("side_damage").getAsDouble() : 0.0,
                type);
    }

    // ------------------------------------------------------------------
    // The damage of a hit into a point
    // ------------------------------------------------------------------

    /**
     * The damage of meeting a point at {@code speed} m/s, kineticDamageMultiplier included.
     *
     * @param vertical   the point stands along the body's up (the vertical threshold, unless weightless)
     * @param jumpBonus  vanilla's Jump Boost allowance (a fall onto an upward point), else 0
     */
    public static double damage(Rule rule, double speed, boolean vertical, boolean weightless, double jumpBonus) {
        double multiplier = rule.multiplierOrDefault();
        if (SERVER.fallDamageMode.get() == FullStopConfig.FallDamageMode.VANILLA_PARITY) {
            // vanilla's stalagmite: causeFallDamage(distance + 2, 2.0) -> (d + 2 - 3 - jump) points, times the multiplier
            double dist = VanillaFall.equivalentFallDistance(speed);
            return Math.max(dist - 1.0 - jumpBonus, 0.5) * multiplier * SERVER.kineticDamageMultiplier.get();
        }
        double threshold = !Double.isNaN(rule.threshold()) ? rule.threshold()
                : vertical && !weightless ? KineticDamageCalculator.verticalThresholdMps()
                : SERVER.velocityDamageThresholdHorizontal.get();
        return (1.0 + Math.max(speed - threshold, 0.0) * multiplier) * SERVER.kineticDamageMultiplier.get();
    }

    /** A ram of a pointed block's side: its flat side damage (0 when it has none), kineticDamageMultiplier included. */
    public static double sideDamage(Rule rule) {
        return rule.sideDamage() * SERVER.kineticDamageMultiplier.get();
    }

    // ------------------------------------------------------------------
    // Block impacts (KineticDamageCalculator)
    // ------------------------------------------------------------------

    /**
     * The first struck block of {@code collision} whose point the body ran into, or null. {@code lost} is the speed the
     * body lost this tick (world frame, m/s), {@code before} its velocity before the impact.
     */
    @Nullable
    public static Hit collisionHit(Entity entity, Collision collision, Vec3 before, Vec3 lost) {
        if (!enabled()) return null;
        Level level = entity.level();
        for (int i = 0; i < collision.blockStates.size(); i++) {
            BlockState state = collision.blockStates.get(i);
            Rule rule = ruleFor(state);
            if (rule == null) continue;
            BlockPos pos = i < collision.impactedPositions.size() ? collision.impactedPositions.get(i) : null;
            Vec3 tip = worldTip(level, pos, rule, state, before);
            if (tip == null) continue;
            double into = -before.dot(tip);
            if (!(into > 0.0) || into < INTO_COS * before.length()) continue;
            double stopped = -lost.dot(tip);
            return new Hit(rule, state, stopped, tip);
        }
        return null;
    }

    /** The first struck pointed block of {@code collision} (whatever side was met), or null. */
    @Nullable
    public static Rule anyStruck(Collision collision) {
        if (!enabled()) return null;
        for (BlockState state : collision.blockStates) {
            Rule rule = ruleFor(state);
            if (rule != null) return rule;
        }
        return null;
    }

    /**
     * The point's direction in the world for a state at {@code pos} (a ship block's turned with its ship); for a block
     * pointed on every side the direction straight back at the mover ({@code motion}). Null when it has none.
     */
    @Nullable
    public static Vec3 worldTip(Level level, @Nullable BlockPos pos, Rule rule, BlockState state, Vec3 motion) {
        if (rule.all()) {
            double len = motion.length();
            return len < 1.0e-9 ? null : motion.scale(-1.0 / len);
        }
        Direction d = rule.tip(state);
        if (d == null) return null;
        Vec3 tip = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
        if (pos != null && Ships.inShipyard(level, pos)) {
            ShipRef ship = Ships.managing(level, pos);
            Quaterniond rotation = ship == null ? null : ship.rotation();
            if (rotation != null) {
                Vector3d v = rotation.transform(new Vector3d(tip.x, tip.y, tip.z));
                tip = new Vec3(v.x, v.y, v.z);
            }
        }
        return tip;
    }

    // ------------------------------------------------------------------
    // Ship hulls
    // ------------------------------------------------------------------

    /**
     * A ship's pointed hull block and a body meeting it: the point's damage when it aims at the body (within 45 degrees
     * of {@code normal}, the contact normal from the hull toward the body) and {@code speed} (m/s, the closing speed lost)
     * reaches the rule's least speed; else 0.
     */
    public static double hullDamage(Entity body, ShipRef ship, BlockState hull, Vec3 normal, double speed, GravityFrame frame,
                                    boolean weightless) {
        if (!enabled() || ship == null) return 0.0;
        Rule rule = ruleFor(hull);
        if (rule == null || speed < rule.minSpeed()) return 0.0;
        Vec3 tip;
        if (rule.all()) {
            tip = normal;
        } else {
            Direction d = rule.tip(hull);
            Quaterniond rotation = ship.rotation();
            if (d == null || rotation == null) return 0.0;
            Vector3d v = rotation.transform(new Vector3d(d.getStepX(), d.getStepY(), d.getStepZ()));
            tip = new Vec3(v.x, v.y, v.z);
        }
        if (tip.dot(normal) < AIM_COS) return 0.0;
        return damage(rule, speed, Math.abs(frame.vertical(tip)) > 0.7, weightless, 0.0);
    }

    // ------------------------------------------------------------------
    // Passable points (entityInside)
    // ------------------------------------------------------------------

    /**
     * A body inside a passable pointed block's space ({@code BlockStateBase.entityInside}, before the block's own
     * handling): the point's damage when it moves into it at the rule's least speed or more. The block's own damage
     * (Supplementaries' spikes: 3 above the base) stays; vanilla's hurt cooldown keeps the larger of the two.
     */
    public static void entityInside(Rule rule, BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide || !rule.passable() || !enabled()) return;
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return;
        if (entity instanceof Player p && (p.isCreative() || p.isSpectator())) return;
        if (DamageImmunityRules.isDamageImmune(living)) return;
        FullStopCapability cap = FullStopCapability.grabCapability(entity);
        if (cap == null) return;
        Vec3 v = cap.getPreImpactWorldVelocity();
        if (Ships.inShipyard(level, pos)) {
            ShipRef ship = Ships.managing(level, pos);
            Vec3 world = ship == null ? null : ship.toWorld(Vec3.atCenterOf(pos));
            Vec3 hullV = ship == null || world == null ? null : ship.velocityAt(world);
            if (hullV != null) v = v.subtract(hullV);
        }
        Vec3 tip = worldTip(level, pos, rule, state, v);
        if (tip == null) return;
        double speed = -v.dot(tip);
        if (speed < rule.minSpeed() || speed < INTO_COS * v.length()) return;
        GravityFrame frame = cap.getFrame();
        boolean vertical = Math.abs(frame.vertical(tip)) > 0.7;
        boolean fall = vertical && frame.vertical(tip) > 0.0 && !cap.isWeightless();
        double amount = damage(rule, speed, vertical, cap.isWeightless(), 0.0);
        boolean upward = vertical && frame.vertical(tip) < 0.0;
        float dealt = DamageMitigation.applyArmorReduction(living, (float) amount, fall, upward, fall);
        if (dealt < 1.0f) return;
        living.hurt(source(entity, rule, speed, fall), dealt);
    }

    /** The damage source of a hit into a point: the rule's damage type, else Full Stop's kinetic one. */
    public static DamageSource source(Entity entity, Rule rule, double speed, boolean fall) {
        ResourceKey<DamageType> key = rule.damageType();
        if (key != null) {
            Holder<DamageType> type = entity.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(key).orElse(null);
            if (type != null) return new DamageSource(type);
        }
        return FullStopDamageSources.kineticSelf(entity, FullStopDamageSources.velocityDisplay(speed), fall, false, fall, null, false);
    }
}
