package net.camacraft.fullstop.common.compat.create.contraption;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionHandler;
import com.simibubi.create.content.trains.track.ITrackBlock;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.compat.create.CreateCompat;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.util.EntityStackUtils;
import net.camacraft.fullstop.server.physics.interaction.BlockAlterRules;
import net.camacraft.fullstop.server.physics.interaction.KineticBlockInteractions;
import net.camacraft.fullstop.server.physics.ship.ShipImpactSounds;
import net.camacraft.fullstop.server.physics.ship.ShipImpulses;
import net.camacraft.fullstop.server.physics.ship.ShipInertia;
import net.camacraft.fullstop.server.physics.ship.ShipMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * A moving Create contraption meeting the terrain and Valkyrien Skies ships, both sides giving
 * ({@code contraptionTerrainImpacts}, {@code contraptionBlocksBreak}, {@code contraptionShipImpacts}).
 *
 * <p><b>Where.</b> Once per server level tick, after the contraptions have moved: each fast contraption's outer
 * blocks (solid blocks with a face not covered by another of its blocks) are followed through the tick's motion
 * and the coming one in Create's own pose (the same interpolation and look-ahead as its hits on bodies,
 * {@link ContraptionImpacts}: Create stops a sliding contraption before it enters a solid block, so what it is
 * about to run into is the contact), in steps of under half a block. A block whose centre enters a solid world cell, or a cell of a ship's block, at
 * {@code contraptionTerrainMinSpeed} or faster has met it. Slower contraptions pass through blocks as they do
 * in Create. Rails and Create's tracks are never hit (trains and carts ride them), nor a block a contraption
 * block already met within {@value #CONTACT_COOLDOWN_TICKS} ticks, nor the block the contraption is driven from (a
 * retracting piston's poles slide back into the piston).
 *
 * <p><b>Which side gives</b> is FullStop's material rule for ships ({@link ShipMaterials}): toughness =
 * hardness x (block mass / 1000 kg)^massInfluence, and the weaker block gives way - the terrain's (a stone wall
 * against an obsidian arm), or the contraption's own (a wool sail against stone: knocked off the contraption,
 * {@link ContraptionBlocks}). Blocks within {@code mutualBreakRatio} of each other both give, as when two ships
 * collide. The hit's energy is the striking block's kinetic energy at its speed, times
 * {@code terrainEnergyFraction} and {@code contraptionImpactStrength} (it is driven: its motor keeps pushing);
 * breaking costs what it costs a ship ({@code breakCostMultiplier}). A hit short of breaking cracks the block,
 * and the cracks add up, so a contraption grinds through in a few passes what one pass can't break.
 *
 * <p><b>What the contraption feels</b> ({@link ContraptionStress}): every hit loads the network driving it with
 * stress; a hit that broke nothing in its way - bedrock, a block too tough for this hit (it cracks), a protected
 * block, a ship far heavier than the contraption - jams it: it is stalled with its block's face against the obstacle
 * and its network held overstressed until the drive stops or turns away or the obstacle goes, as such a contact
 * would stop a real machine. A held contraption doesn't move, so it doesn't grind on.
 *
 * <p><b>Ships</b>: a contraption block meeting a ship's block pushes the ship at that point, as an immovable,
 * driven body would ({@code m_eff (1 + e) v}, the ship's effective mass at the point; capped as every FullStop
 * push on a ship), and the weaker block breaks as above (the ship's by the {@code hullDamage} rules). A
 * contraption on a ship is left to the ship.
 *
 * <p>Who answers for the breaking: the player driving the contraption (a train's conductor), otherwise
 * FullStop's own fake player, so protection mods can refuse it ({@code BreakEvent}); minigame hosts are asked
 * first ({@code MatchPolicies.mayAlter}). Never loads a chunk.
 */
public final class ContraptionTerrainImpacts {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean DEBUG_EVERY_TICK = Boolean.getBoolean("fullstop.debug.contraptionTerrain");

    /** One contraption block meets one cell again only after this many ticks. */
    static final int CONTACT_COOLDOWN_TICKS = 8;
    /** At most this many contacts judged per contraption per tick. */
    private static final int MAX_CONTACTS_PER_CONTRAPTION = 12;
    /** Longest step of a block's sweep (blocks), and at most this many steps. */
    private static final double MAX_STEP = 0.45;
    private static final int MAX_STEPS = 48;
    /** How long a block's grinding damage is remembered between hits, ticks. */
    private static final int GRIND_MEMORY_TICKS = 200;
    /** A ship whose effective mass at the contact is over this many times the contraption's jams it. */
    static final double HEAVY_SHIP_RATIO = 10.0;
    /** How much of the closing speed a ship rebounds with off a driven contraption. */
    private static final double SHIP_RESTITUTION = 0.2;
    /** A hit cracks a block it can't break only from this share of the cost (as an entity's impact does). */
    private static final double CRACK_SHARE = 0.15;

    /** Each contraption's outer blocks and mass, rebuilt when its block count changes. */
    private static final Map<Contraption, Shape> SHAPES = Collections.synchronizedMap(new WeakHashMap<>());
    /** Per level: contact (contraption, block, cell) -> the tick it was last judged. */
    private static final Map<Level, Long2LongOpenHashMap> CONTACTS = new WeakHashMap<>();
    /** Per level: grinding damage on world and ship blocks that don't keep cracks themselves. */
    private static final Map<Level, Long2ObjectOpenHashMap<Grind>> GRIND = new WeakHashMap<>();
    /** Per contraption: grinding damage on its own blocks. */
    private static final Map<AbstractContraptionEntity, Map<BlockPos, Grind>> OWN_GRIND = new WeakHashMap<>();
    /** Per contraption: the tick a held jam let go of it (contacts judged before then are forgotten). */
    private static final Map<AbstractContraptionEntity, Long> RELEASED = Collections.synchronizedMap(new WeakHashMap<>());
    /** The last contacts judged, per contraption id (for the game tests and the harness). */
    private static final Map<Integer, List<Contact>> RECENT = Collections.synchronizedMap(new HashMap<>());
    private static final Map<Level, Stats> STATS = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Its outer blocks, its mass (kg), the box its blocks fill (local), and its moment of inertia about an axis through its
     * pivot (kg m^2, per axis, when asked).
     */
    private static final class Shape {
        final int blockCount;
        final List<BlockPos> outer;
        final double massKg;
        final Map<BlockPos, StructureBlockInfo> blocks;
        /** The box its blocks fill, in its own frame (null without a block). Create's {@code bounds} is not that box. */
        @Nullable
        final AABB blockBounds;
        private final double[] inertia = {Double.NaN, Double.NaN, Double.NaN};

        Shape(int blockCount, List<BlockPos> outer, double massKg, Map<BlockPos, StructureBlockInfo> blocks, @Nullable AABB blockBounds) {
            this.blockCount = blockCount;
            this.outer = outer;
            this.massKg = massKg;
            this.blocks = blocks;
            this.blockBounds = blockBounds;
        }

        int blockCount() {
            return blockCount;
        }

        List<BlockPos> outer() {
            return outer;
        }

        double massKg() {
            return massKg;
        }

        /** Its moment of inertia (kg m^2) about the {@code axis} line through its pivot (the anchor block's centre). */
        double inertia(Direction.Axis axis) {
            int i = axis.ordinal();
            if (Double.isNaN(inertia[i])) {
                double sum = 0.0;
                for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
                    BlockState state = entry.getValue().state();
                    if (state.isAir()) continue;
                    sum += ShipMaterials.massKg(state) * (squaredOffAxis(entry.getKey(), axis) + 1.0 / 6.0);
                }
                inertia[i] = sum;
            }
            return inertia[i];
        }
    }

    /** A block's squared distance from the {@code axis} line through the anchor block's centre (block units). */
    private static double squaredOffAxis(BlockPos local, Direction.Axis axis) {
        double x = local.getX(), y = local.getY(), z = local.getZ();
        return switch (axis) {
            case X -> y * y + z * z;
            case Y -> x * x + z * z;
            case Z -> x * x + y * y;
        };
    }

    private record Grind(double fraction, long tick, net.minecraft.world.level.block.Block block) {}

    /**
     * A contraption block's motion up to a contact: its centre (in the contraption's frame) and the fraction of the
     * tick's motion it had reached when its centre entered the cell (as {@link ContraptionImpacts#toGlobal} counts it).
     */
    private record Sweep(Vec3 centre, double at) {}

    /** What one contact did. */
    public enum Outcome { BROKE, CRACKED, KNOCKED_OFF, BOTH_GAVE, PUSHED_SHIP, CUSHIONED, JAMMED }

    /**
     * One contact judged: when, which contraption block met which cell (a ship's cell when {@code shipId} is
     * set), how fast (m/s), the hit's energy (FullStop units), what it did, and the stress it put on the
     * contraption's drive (FullStop units; for a jam, NaN).
     */
    public record Contact(long tick, int contraptionId, BlockPos local, BlockPos cell, @Nullable Long shipId, String block,
                          String striker, double speed, double energy, Outcome outcome, double load) {}

    /** One level tick's cost: contraptions looked at, fast enough to test, positions checked, contacts, nanoseconds. */
    public record Stats(long tick, int contraptions, int fast, int checks, int contacts, int broken, long nanos) {}

    private ContraptionTerrainImpacts() {
    }

    /** Whether contraptions meet terrain or ships at all: Create and either switch. */
    public static boolean enabled() {
        return CreateCompat.active() && (SERVER.contraptionTerrainImpacts.get() || SERVER.contraptionShipImpacts.get());
    }

    /** The contacts the contraption {@code id} made most recently (oldest first), or an empty list. */
    public static List<Contact> recent(int contraptionId) {
        List<Contact> list = RECENT.get(contraptionId);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    /** What the latest level tick's terrain checks cost in {@code level}, or null before any. */
    @Nullable
    public static Stats stats(Level level) {
        return STATS.get(level);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || !enabled()) return;
        long start = System.nanoTime();
        Map<Integer, WeakReference<AbstractContraptionEntity>> loaded = ContraptionHandler.loadedContraptions.get(level);
        if (loaded == null || loaded.isEmpty()) return;
        List<AbstractContraptionEntity> contraptions = new ArrayList<>(loaded.size());
        for (WeakReference<AbstractContraptionEntity> ref : loaded.values()) {
            AbstractContraptionEntity c = ref.get();
            if (c != null && c.isAlive()) contraptions.add(c);
        }
        Budget budget = new Budget(SERVER.maxContraptionTerrainChecksPerTick.get(), SERVER.terrainMaxBlocksPerTick.get());
        long now = level.getGameTime();
        int fast = 0;
        for (AbstractContraptionEntity c : contraptions) {
            if (budget.checks <= 0) break;
            try {
                if (process(level, c, budget, now)) fast++;
            } catch (RuntimeException e) {
                LOGGER.warn("[FullStop] contraption {} terrain check failed: {}", c.getId(), e.toString());
            }
        }
        if ((now & 255) == 0) forgetOld(level, now);
        STATS.put(level, new Stats(now, contraptions.size(), fast, budget.checked, budget.contacts, budget.brokenTotal,
                System.nanoTime() - start));
    }

    /** What one level tick may still spend: positions to check, blocks to break. */
    private static final class Budget {
        int checks;
        int breaks;
        int checked;
        int contacts;
        int brokenTotal;

        Budget(int checks, int breaks) {
            this.checks = checks;
            this.breaks = breaks;
        }
    }

    /** One contraption's tick. Returns whether it was fast enough to check. */
    private static boolean process(ServerLevel level, AbstractContraptionEntity c, Budget budget, long now) {
        Contraption contraption = c.getContraption();
        if (contraption == null || contraption.bounds == null) return false;
        if (Ships.available() && Ships.managing(c) != null) return false; // a ship's contraption: the ship's hits
        double min = SERVER.contraptionTerrainMinSpeed.get();

        // its fastest point this tick (a corner of its bounds): too slow anywhere, nothing to check
        AABB bounds = contraption.bounds;
        double reach = 0.0;
        AABB swept = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 p = corner(bounds, corner);
            Vec3 from = ContraptionImpacts.toGlobal(c, p, 0.0);
            Vec3 mid = ContraptionImpacts.toGlobal(c, p, 0.5);
            Vec3 to = ContraptionImpacts.toGlobal(c, p, 1.0);
            reach = Math.max(reach, from.distanceTo(to));
            for (Vec3 at : new Vec3[]{from, mid, to}) swept = swept == null ? new AABB(at, at) : swept.minmax(new AABB(at, at));
        }
        if (SERVER.debugContraptionLog.get() && (now % 20) == (c.getId() % 20 + 0) || DEBUG_EVERY_TICK && !(c instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity)) {
            LOGGER.info("[FullStop] contraption {} ({}) terrain sweep: fastest point {} m/s, {} blocks, anchor {} prev {}", c.getId(),
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(c.getType()), fmt(reach * 20.0),
                    contraption.getBlocks().size(), c.getAnchorVec(), c.getPrevAnchorVec());
        }
        if (swept == null || reach * 20.0 < min) return false;

        boolean terrain = SERVER.contraptionTerrainImpacts.get();
        List<ShipRef> ships = List.of();
        if (SERVER.contraptionShipImpacts.get() && Ships.available()) {
            ships = Ships.intersecting(level, swept.inflate(1.0 + reach * 0.25));
        }
        if (!terrain && ships.isEmpty()) return true;

        Shape shape = shape(contraption);
        Context ctx = new Context(level, c, contraption, shape, now, budget);
        // the block it is driven from: a retracting piston's poles slide back into the piston, a pulley's platform up to it
        BlockPos controller = ContraptionStress.controllerPosOf(c);
        for (BlockPos local : shape.outer()) {
            if (budget.checks <= 0 || ctx.contacts >= MAX_CONTACTS_PER_CONTRAPTION || ctx.jammed) break;
            StructureBlockInfo info = contraption.getBlocks().get(local);
            if (info == null || contraption.isHiddenInPortal(local)) continue;
            Vec3 centre = Vec3.atCenterOf(local);
            Vec3 from = ContraptionImpacts.toGlobal(c, centre, 0.0);
            Vec3 to = ContraptionImpacts.toGlobal(c, centre, 1.0);
            double dist = from.distanceTo(to);
            if (dist * 20.0 < min) continue;
            // this tick and the coming one: Create stops a sliding contraption (a gantry, piston or pulley) before it
            // enters a solid block, so it never overlaps one; what it is about to run into is the contact, broken
            // now so Create finds the way clear (a bearing's arm, which Create lets pass through, is met a tick early)
            double span = 1.0 + ContraptionImpacts.LOOKAHEAD;
            int steps = Mth.clamp((int) Math.ceil(span * dist / MAX_STEP), 1, MAX_STEPS);
            BlockPos lastCell = BlockPos.containing(from);
            BlockPos lastShipCell = null;
            sweep:
            for (int s = 1; s <= steps; s++) {
                if (budget.checks-- <= 0) break;
                budget.checked++;
                double f = span * s / steps;
                Vec3 at = ContraptionImpacts.toGlobal(c, centre, f);
                BlockPos cell = BlockPos.containing(at);
                if (terrain && !cell.equals(lastCell)) {
                    lastCell = cell;
                    if (cell.equals(controller)) continue;
                    BlockState state = loadedState(level, cell);
                    if (state == null) break; // its sweep left the loaded world
                    if (solid(level, cell, state)) {
                        Vec3 v = ContraptionImpacts.velocityAt(c, centre, f);
                        double speed = v.length();
                        if (speed >= min && fresh(level, c, local, cell, null, now)) {
                            Outcome outcome = terrainContact(ctx, local, info, cell, state, at, v, speed, new Sweep(centre, f));
                            if (outcome == Outcome.KNOCKED_OFF || outcome == Outcome.BOTH_GAVE || outcome == Outcome.JAMMED) break sweep;
                        }
                    }
                }
                for (ShipRef ship : ships) {
                    Vec3 inShip = ship.toShip(at);
                    if (inShip == null) continue;
                    BlockPos shipCell = BlockPos.containing(inShip);
                    if (shipCell.equals(lastShipCell)) continue;
                    BlockState state = loadedState(level, shipCell);
                    if (state == null || !solid(level, shipCell, state)) continue;
                    lastShipCell = shipCell;
                    Vec3 v = ContraptionImpacts.velocityAt(c, centre, f);
                    Vec3 shipV = ship.velocityAt(at);
                    Vec3 relative = shipV == null ? v : v.subtract(shipV);
                    double closing = relative.length();
                    if (closing < min || !fresh(level, c, local, shipCell, ship.id(), now)) continue;
                    Outcome outcome = shipContact(ctx, local, info, ship, shipCell, state, at, v, relative, closing, new Sweep(centre, f));
                    if (outcome == Outcome.KNOCKED_OFF || outcome == Outcome.BOTH_GAVE || outcome == Outcome.JAMMED) break sweep;
                }
            }
        }
        return true;
    }

    /** One contraption's tick in progress. */
    private static final class Context {
        final ServerLevel level;
        final AbstractContraptionEntity c;
        final Contraption contraption;
        final Shape shape;
        final long now;
        final Budget budget;
        int contacts;
        boolean jammed;
        private boolean driveFound;
        @Nullable
        private ContraptionStress.Drive drive;

        Context(ServerLevel level, AbstractContraptionEntity c, Contraption contraption, Shape shape, long now, Budget budget) {
            this.level = level;
            this.c = c;
            this.contraption = contraption;
            this.shape = shape;
            this.now = now;
            this.budget = budget;
        }

        @Nullable
        ContraptionStress.Drive drive() {
            if (!driveFound) {
                driveFound = true;
                drive = ContraptionStress.driveOf(level, c);
            }
            return drive;
        }

        /** Who answers for what it breaks: the player driving it (a train's conductor), or nobody (FullStop's fake player). */
        @Nullable
        Entity cause() {
            Optional<UUID> id = c.getControllingPlayer();
            return id.isPresent() ? level.getPlayerByUUID(id.get()) : null;
        }

        void load(double units) {
            ContraptionStress.hit(level, drive(), units, shape.massKg());
        }

        void jam(ContraptionStress.Obstacle obstacle, double contactFraction) {
            if (jammed) return;
            jammed = true;
            ContraptionStress.jam(level, drive(), c, obstacle, contactFraction);
        }
    }

    // ---- against the terrain ---------------------------------------------------------------------------------

    private static Outcome terrainContact(Context ctx, BlockPos local, StructureBlockInfo info, BlockPos cell, BlockState state,
                                          Vec3 at, Vec3 velocity, double speed, Sweep sweep) {
        ServerLevel level = ctx.level;
        BlockState striker = info.state();
        ctx.contacts++;
        ctx.budget.contacts++;
        ShipImpactSounds.play(level, soundKey(ctx.c), ctx.shape.massKg(), true, List.of(new ShipImpactSounds.Hit(at, speed, state, striker)), ctx.now);

        double energy = hitEnergy(ctx, local, speed);
        if (state.isStickyBlock()) {
            // slime and honey give: they soak the hit up and let the contraption through
            ctx.load(energy * 0.5);
            return record(ctx, local, cell, null, state, striker, speed, energy, Outcome.CUSHIONED, energy * 0.5);
        }
        boolean soft = ShipMaterials.sweepCandidate(state);
        if (soft && !ShipMaterials.sweepableAt(state, speed)) {
            // leaves a player placed, at a sweeping speed: the contraption passes them as Create would
            return record(ctx, local, cell, null, state, striker, speed, energy, Outcome.CUSHIONED, 0.0);
        }

        double terrainTough = ShipMaterials.toughness(level, cell, state);
        double strikerTough = ShipMaterials.toughness(ctx.contraption.getContraptionWorld(), local, striker);
        boolean terrainMay = SERVER.kineticBlockBreaking.get() && breakable(level, cell, state)
                && BlockAlterRules.mayAlter(level, cell, ctx.cause());
        boolean strikerMay = SERVER.contraptionBlocksBreak.get() && ContraptionBlocks.canKnockOff(ctx.contraption, local, info)
                && Double.isFinite(strikerTough);
        double multiplier = SERVER.breakCostMultiplier.get();
        ShipMaterials.Gives gives = soft ? ShipMaterials.Gives.FIRST
                : ShipMaterials.whichGives(terrainTough, strikerTough, SERVER.mutualBreakRatio.get());

        switch (gives) {
            case FIRST -> {
                if (!terrainMay || ctx.budget.breaks <= 0) return jammed(ctx, local, cell, null, state, striker, speed, energy, sweep, null);
                double cost = soft ? terrainTough * multiplier : ShipMaterials.breakCost(terrainTough, strikerTough, multiplier);
                Smash smash = smash(level, cell, state, cost, energy, ctx.cause(), ctx.now);
                if (smash.broken()) {
                    ctx.budget.breaks--;
                    ctx.budget.brokenTotal++;
                    ctx.load(smash.spent());
                    return record(ctx, local, cell, null, state, striker, speed, energy, Outcome.BROKE, smash.spent());
                }
                // it held this time (cracked or not): the contraption is stopped against it
                return jammed(ctx, local, cell, null, state, striker, speed, energy, sweep, null);
            }
            case SECOND -> {
                if (!strikerMay) return jammed(ctx, local, cell, null, state, striker, speed, energy, sweep, null);
                double cost = ShipMaterials.breakCost(strikerTough, terrainTough, multiplier);
                double spent = knock(ctx, local, info, cost, energy, at, velocity);
                if (spent >= 0.0) {
                    ctx.load(spent);
                    return record(ctx, local, cell, null, state, striker, speed, energy, Outcome.KNOCKED_OFF, spent);
                }
                return jammed(ctx, local, cell, null, state, striker, speed, energy, sweep, null);
            }
            case BOTH -> {
                double ratio = SERVER.mutualBreakRatio.get();
                boolean terrainGave = false;
                double spent = 0.0;
                if (terrainMay && ctx.budget.breaks > 0) {
                    Smash smash = smash(level, cell, state, ShipMaterials.mutualCost(terrainTough, multiplier, ratio), energy, ctx.cause(), ctx.now);
                    if (smash.broken()) {
                        terrainGave = true;
                        spent += smash.spent();
                        ctx.budget.breaks--;
                        ctx.budget.brokenTotal++;
                    }
                }
                double knocked = strikerMay ? knock(ctx, local, info, ShipMaterials.mutualCost(strikerTough, multiplier, ratio), energy, at, velocity) : -1.0;
                if (knocked >= 0.0) spent += knocked;
                if (!terrainGave && knocked < 0.0) return jammed(ctx, local, cell, null, state, striker, speed, energy, sweep, null);
                ctx.load(spent);
                Outcome outcome = knocked >= 0.0 ? (terrainGave ? Outcome.BOTH_GAVE : Outcome.KNOCKED_OFF) : Outcome.BROKE;
                return record(ctx, local, cell, null, state, striker, speed, energy, outcome, spent);
            }
            default -> {
                return jammed(ctx, local, cell, null, state, striker, speed, energy, sweep, null);
            }
        }
    }

    // ---- against a ship --------------------------------------------------------------------------------------

    private static Outcome shipContact(Context ctx, BlockPos local, StructureBlockInfo info, ShipRef ship, BlockPos shipCell,
                                       BlockState state, Vec3 at, Vec3 velocity, Vec3 relative, double closing, Sweep sweep) {
        ServerLevel level = ctx.level;
        BlockState striker = info.state();
        ctx.contacts++;
        ctx.budget.contacts++;
        ShipImpactSounds.play(level, soundKey(ctx.c), ctx.shape.massKg(), true, List.of(new ShipImpactSounds.Hit(at, closing, state, striker)), ctx.now);

        Vec3 dir = relative.scale(1.0 / closing);
        double shipMass = ship.isStatic() ? Double.POSITIVE_INFINITY : ship.physicalMass();
        double effective = Double.isFinite(shipMass) && shipMass > 0.0
                ? ShipInertia.effectiveMassKg(ship, shipMass, at, dir) : Double.POSITIVE_INFINITY;
        boolean heavy = !Double.isFinite(effective) || !(effective > 0.0) || effective > HEAVY_SHIP_RATIO * ctx.shape.massKg();

        double load = 0.0;
        Outcome outcome = Outcome.PUSHED_SHIP;
        if (!heavy) {
            // the driven contraption shoves the ship out of its way at the contact (an immovable body against it)
            Vec3 impulse = dir.scale(effective * closing * (1.0 + SHIP_RESTITUTION));
            Vec3 applied = ShipImpulses.applyCapped(level, ship, impulse, at, closing);
            double dv = applied.length() / effective;
            load += 0.5 * EntityStackUtils.kgToUnits(effective) * (dv / 20.0) * (dv / 20.0);
        }

        // the weaker of the two blocks gives, as against terrain (the ship's by the hull rules)
        double energy = hitEnergy(ctx, local, closing);
        double shipTough = ShipMaterials.toughness(level, shipCell, state);
        double strikerTough = ShipMaterials.toughness(ctx.contraption.getContraptionWorld(), local, striker);
        boolean shipMay = SERVER.hullDamage.get() && breakable(level, shipCell, state) && BlockAlterRules.mayAlter(level, shipCell, ctx.cause());
        boolean strikerMay = SERVER.contraptionBlocksBreak.get() && ContraptionBlocks.canKnockOff(ctx.contraption, local, info)
                && Double.isFinite(strikerTough);
        double multiplier = SERVER.breakCostMultiplier.get();
        double ratio = SERVER.mutualBreakRatio.get();
        boolean gave = false;
        boolean knocked = false;
        switch (ShipMaterials.whichGives(shipTough, strikerTough, ratio)) {
            case FIRST -> {
                if (shipMay && ctx.budget.breaks > 0) {
                    Smash smash = smash(level, shipCell, state, ShipMaterials.breakCost(shipTough, strikerTough, multiplier), energy, ctx.cause(), ctx.now);
                    if (smash.broken()) {
                        gave = true;
                        load += smash.spent();
                        ctx.budget.breaks--;
                        ctx.budget.brokenTotal++;
                    }
                }
            }
            case SECOND -> {
                if (strikerMay) {
                    double spent = knock(ctx, local, info, ShipMaterials.breakCost(strikerTough, shipTough, multiplier), energy, at, velocity);
                    if (spent >= 0.0) {
                        knocked = true;
                        load += spent;
                    }
                }
            }
            case BOTH -> {
                if (shipMay && ctx.budget.breaks > 0) {
                    Smash smash = smash(level, shipCell, state, ShipMaterials.mutualCost(shipTough, multiplier, ratio), energy, ctx.cause(), ctx.now);
                    if (smash.broken()) {
                        gave = true;
                        load += smash.spent();
                        ctx.budget.breaks--;
                        ctx.budget.brokenTotal++;
                    }
                }
                if (strikerMay) {
                    double spent = knock(ctx, local, info, ShipMaterials.mutualCost(strikerTough, multiplier, ratio), energy, at, velocity);
                    if (spent >= 0.0) {
                        knocked = true;
                        load += spent;
                    }
                }
            }
            default -> {
            }
        }
        if (heavy && !gave && !knocked) {
            return jammed(ctx, local, shipCell, ship.id(), state, striker, closing, energy, sweep, ship);
        }
        if (knocked) outcome = gave ? Outcome.BOTH_GAVE : Outcome.KNOCKED_OFF;
        else if (gave) outcome = Outcome.BROKE;
        ctx.load(load);
        return record(ctx, local, shipCell, ship.id(), state, striker, closing, energy, outcome, load);
    }

    // ---- the rules ---------------------------------------------------------------------------------------------

    /**
     * A hit's breaking energy (FullStop units): the contraption's kinetic energy behind its block at {@code local}
     * moving at {@code speed}, driven ({@code contraptionImpactStrength}) and as much of it as a crash may break with
     * ({@code terrainEnergyFraction}). What stands behind the block is the contraption's effective mass there: all of
     * it for one that slides or rides (a piston, a gantry, a cart, a train), its moment of inertia about the axis over
     * the block's distance from it squared for one turning on a bearing (a block at an arm's tip has less behind it
     * than one near the axis).
     */
    private static double hitEnergy(Context ctx, BlockPos local, double speedMps) {
        double v = speedMps / 20.0;
        return 0.5 * EntityStackUtils.kgToUnits(effectiveMassKg(ctx, local)) * v * v
                * SERVER.terrainEnergyFraction.get() * SERVER.contraptionImpactStrength.get();
    }

    /** The contraption's mass (kg) behind its block at {@code local}, for a push along that block's motion. */
    static double effectiveMassKg(Context ctx, BlockPos local) {
        Direction.Axis axis = ContraptionStress.rotationAxis(ctx.c);
        if (axis == null) return ctx.shape.massKg();
        double r2 = squaredOffAxis(local, axis);
        // never more than the whole contraption, never less than the block itself
        double block = ShipMaterials.massKg(ctx.shape.blocks.get(local) != null ? ctx.shape.blocks.get(local).state()
                : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        return Mth.clamp(ctx.shape.inertia(axis) / Math.max(r2, 0.25), block, ctx.shape.massKg());
    }

    /** Whether a world (or ship) cell holds something a contraption block meets: a collision shape, not a rail or track. */
    static boolean solid(Level level, BlockPos cell, BlockState state) {
        if (state.isAir() || state.getBlock() instanceof BaseRailBlock || state.getBlock() instanceof ITrackBlock) return false;
        return !state.getCollisionShape(level, cell, CollisionContext.empty()).isEmpty();
    }

    /** Whether FullStop may break this block at all (as for ships): not air, unbreakable, ship_unbreakable, slime or honey, or (by default) a block entity. */
    private static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0) return false;
        if (state.is(FullStopTags.SHIP_UNBREAKABLE)) return false;
        if (state.isStickyBlock()) return false;
        return !state.hasBlockEntity() || SERVER.terrainBreakBlockEntities.get();
    }

    private record Smash(boolean broken, double spent) {}

    /**
     * Breaks a world or ship block with {@code energy}, or cracks it: FullStop's own cracks for
     * {@code fullstop:crackable} blocks, and for every other block grinding damage remembered here (a crack overlay
     * that deepens with each hit), so a contraption grinds through in a few passes what one pass can't break.
     */
    private static Smash smash(ServerLevel level, BlockPos pos, BlockState state, double cost, double energy, @Nullable Entity cause, long now) {
        if (!(cost >= 0.0) || !Double.isFinite(cost)) return new Smash(false, 0.0);
        boolean drops = SERVER.terrainBlockDrops.get();
        if (state.is(FullStopTags.CRACKABLE)) {
            KineticBlockInteractions.SmashOutcome outcome = KineticBlockInteractions.smashBlock(level, pos, state, cost, energy, drops, cause);
            return new Smash(outcome.broken(), outcome.spent());
        }
        Long2ObjectOpenHashMap<Grind> grind = GRIND.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        long key = pos.asLong();
        Grind prior = grind.get(key);
        double before = prior != null && prior.block() == state.getBlock() && now - prior.tick() < GRIND_MEMORY_TICKS ? prior.fraction() : 0.0;
        double left = cost * (1.0 - before);
        if (energy >= left) {
            KineticBlockInteractions.SmashOutcome outcome = KineticBlockInteractions.smashBlock(level, pos, state, left, left, drops, cause);
            if (outcome.broken()) {
                grind.remove(key);
                level.destroyBlockProgress(overlayId(pos), pos, -1);
                return new Smash(true, left);
            }
            return new Smash(false, 0.0);
        }
        double share = cost > 0.0 ? energy / cost : 0.0;
        if (share < CRACK_SHARE) return new Smash(false, 0.0);
        double after = Math.min(0.95, before + share);
        grind.put(key, new Grind(after, now, state.getBlock()));
        level.destroyBlockProgress(overlayId(pos), pos, Mth.clamp((int) (after * 10.0), 1, 9));
        level.playSound(null, pos, state.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0f,
                0.8f + level.random.nextFloat() * 0.3f);
        return new Smash(false, 0.0);
    }

    /**
     * Grinds the contraption's own block at {@code local} with {@code energy}: knocked off once its damage covers
     * {@code cost}. Returns the energy spent knocking it off, or -1 when it stays on (damaged).
     */
    private static double knock(Context ctx, BlockPos local, StructureBlockInfo info, double cost, double energy, Vec3 at, Vec3 velocity) {
        if (!(cost >= 0.0) || !Double.isFinite(cost)) return -1.0;
        Map<BlockPos, Grind> own = OWN_GRIND.computeIfAbsent(ctx.c, k -> new HashMap<>());
        Grind prior = own.get(local);
        double before = prior != null && prior.block() == info.state().getBlock() && ctx.now - prior.tick() < GRIND_MEMORY_TICKS
                ? prior.fraction() : 0.0;
        double left = cost * (1.0 - before);
        if (energy >= left) {
            if (ContraptionBlocks.knockOff(ctx.level, ctx.c, ctx.contraption, local, info, at, velocity)) {
                own.remove(local);
                ctx.budget.brokenTotal++;
                return left;
            }
            return -1.0;
        }
        double share = cost > 0.0 ? energy / cost : 0.0;
        if (share >= CRACK_SHARE) own.put(local, new Grind(Math.min(0.95, before + share), ctx.now, info.state().getBlock()));
        return -1.0;
    }

    private static Outcome jammed(Context ctx, BlockPos local, BlockPos cell, @Nullable Long shipId, BlockState state, BlockState striker,
                                  double speed, double energy, Sweep sweep, @Nullable ShipRef ship) {
        Vec3 shipCellWorld = ship != null ? ship.toWorld(Vec3.atCenterOf(cell)) : null;
        // held only because this tick's break budget is spent: judged again shortly, not held until the motor stops
        boolean retry = ctx.budget.breaks <= 0;
        ctx.jam(new ContraptionStress.Obstacle(cell.immutable(), shipId, state.getBlock(), shipCellWorld, retry),
                contactFraction(ctx.c, sweep, cell, ship));
        return record(ctx, local, cell, shipId, state, striker, speed, energy, Outcome.JAMMED, Double.NaN);
    }

    /**
     * Where on the tick's motion (the fraction {@link ContraptionImpacts#toGlobal} takes) the block whose centre is
     * {@code sweep.centre()} first touches the cell it ran into ({@code ship}'s cell when set): the last pose of the
     * tick's motion where the block's cube is still clear of the cell. 0 when it was already against it as the tick
     * began. The block is taken as an axis-aligned cube in the cell's frame, which a turned block's corners overhang
     * by at most a fifth of a block.
     */
    private static double contactFraction(AbstractContraptionEntity c, Sweep sweep, BlockPos cell, @Nullable ShipRef ship) {
        double lo = 0.0;
        double hi = sweep.at();
        if (overlaps(c, sweep.centre(), lo, cell, ship)) return lo;
        for (int i = 0; i < 16; i++) {
            double mid = 0.5 * (lo + hi);
            if (overlaps(c, sweep.centre(), mid, cell, ship)) hi = mid;
            else lo = mid;
        }
        return lo;
    }

    /** Whether the contraption block with centre {@code centre} overlaps {@code cell} (a world cell, or {@code ship}'s) at fraction {@code f}. */
    private static boolean overlaps(AbstractContraptionEntity c, Vec3 centre, double f, BlockPos cell, @Nullable ShipRef ship) {
        Vec3 p = ContraptionImpacts.toGlobal(c, centre, f);
        if (ship != null) {
            p = ship.toShip(p);
            if (p == null) return false;
        }
        double reach = 1.0 - 0.02;
        return Math.abs(p.x - (cell.getX() + 0.5)) < reach && Math.abs(p.y - (cell.getY() + 0.5)) < reach
                && Math.abs(p.z - (cell.getZ() + 0.5)) < reach;
    }

    private static Outcome record(Context ctx, BlockPos local, BlockPos cell, @Nullable Long shipId, BlockState state, BlockState striker,
                                  double speed, double energy, Outcome outcome, double load) {
        Contact contact = new Contact(ctx.now, ctx.c.getId(), local.immutable(), cell.immutable(), shipId, name(state), name(striker),
                speed, energy, outcome, load);
        List<Contact> list = RECENT.computeIfAbsent(ctx.c.getId(), k -> Collections.synchronizedList(new ArrayList<>()));
        list.add(contact);
        while (list.size() > 32) list.remove(0);
        if (SERVER.debugContraptionLog.get()) {
            LOGGER.info("[FullStop] contraption {} {} {} at {} {}: {} m/s, {} units -> {} (load {}, drive {})", ctx.c.getId(), name(striker),
                    shipId != null ? "met ship " + shipId + "'s" : "met", name(state), cell.toShortString(), fmt(speed), fmt(energy),
                    outcome, Double.isNaN(load) ? "jam" : fmt(load), ContraptionStress.describe(ctx.drive()));
        }
        return outcome;
    }

    // ---- bookkeeping ---------------------------------------------------------------------------------------------

    /** Whether a contraption block meets a cell for the first time in {@value #CONTACT_COOLDOWN_TICKS} ticks (and marks it). */
    private static boolean fresh(ServerLevel level, AbstractContraptionEntity c, BlockPos local, BlockPos cell, @Nullable Long shipId, long now) {
        Long2LongOpenHashMap map = CONTACTS.computeIfAbsent(level, l -> new Long2LongOpenHashMap());
        long key = (local.asLong() * 0x9E3779B97F4A7C15L) ^ (cell.asLong() * 0xC2B2AE3D27D4EB4FL) ^ ((long) c.getId() << 20)
                ^ (shipId != null ? shipId * 0x165667B19E3779F9L : 0L);
        long last = map.getOrDefault(key, Long.MIN_VALUE);
        Long released = RELEASED.get(c);
        boolean forgotten = released != null && last < released;
        if (!forgotten && last != Long.MIN_VALUE && now - last < CONTACT_COOLDOWN_TICKS && now >= last) return false;
        map.put(key, now);
        return true;
    }

    /**
     * A held jam let go of {@code c} at {@code now}: the contacts it made before are forgotten, so if its drive starts it
     * again into the same obstacle, that hit is judged at once.
     */
    static void forgetContacts(AbstractContraptionEntity c, long now) {
        RELEASED.put(c, now);
    }

    private static void forgetOld(ServerLevel level, long now) {
        Long2LongOpenHashMap contacts = CONTACTS.get(level);
        if (contacts != null) contacts.values().removeIf(t -> now - t >= CONTACT_COOLDOWN_TICKS || now < t);
        Long2ObjectOpenHashMap<Grind> grind = GRIND.get(level);
        if (grind != null) grind.values().removeIf(g -> now - g.tick() >= GRIND_MEMORY_TICKS || now < g.tick());
        synchronized (RECENT) {
            RECENT.values().removeIf(list -> list.isEmpty() || now - list.get(list.size() - 1).tick() > 1200);
        }
    }

    /** The contraption's outer blocks (local positions): solid, with a face another of its solid blocks doesn't cover. Cached. */
    static List<BlockPos> outerBlocks(Contraption contraption) {
        return shape(contraption).outer();
    }

    /**
     * The box the contraption's blocks fill, in its own frame (where {@link ContraptionImpacts#toGlobal} takes points
     * from), or null when it has none. Not Create's {@code Contraption.bounds}: Create widens that to the whole
     * circle a bearing's, a cart's or a train's blocks sweep round their axis ({@code expandBoundsAroundAxis}), so
     * its corners lie far out from any block. Cached with the shape.
     */
    @Nullable
    public static AABB blockBounds(Contraption contraption) {
        return shape(contraption).blockBounds;
    }

    /** Its outer blocks (solid, with a face another of its solid blocks doesn't cover), its mass and its blocks' box, cached. */
    private static Shape shape(Contraption contraption) {
        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        Shape cached = SHAPES.get(contraption);
        if (cached != null && cached.blockCount() == blocks.size()) return cached;
        Level world = contraption.getContraptionWorld();
        List<BlockPos> outer = new ArrayList<>();
        double mass = 0.0;
        AABB box = null;
        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            BlockState state = entry.getValue().state();
            if (state.isAir()) continue;
            AABB cell = new AABB(entry.getKey());
            box = box == null ? cell : box.minmax(cell);
            mass += ShipMaterials.massKg(state);
            if (state.getCollisionShape(world, entry.getKey(), CollisionContext.empty()).isEmpty()) continue;
            for (Direction d : Direction.values()) {
                StructureBlockInfo next = blocks.get(entry.getKey().relative(d));
                if (next == null || next.state().isAir() || !next.state().isCollisionShapeFullBlock(world, entry.getKey().relative(d))) {
                    outer.add(entry.getKey().immutable());
                    break;
                }
            }
        }
        Shape shape = new Shape(blocks.size(), outer, Math.max(mass, 1.0), blocks, box);
        SHAPES.put(contraption, shape);
        return shape;
    }

    /** The block at {@code pos}, or null when its chunk isn't loaded (never loads one). */
    @Nullable
    private static BlockState loadedState(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return null;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    /** Each contraption's impact sounds keep their own cooldown (ShipImpactSounds keys ships by id; contraptions sit far below them). */
    private static long soundKey(AbstractContraptionEntity c) {
        return Long.MIN_VALUE + c.getId();
    }

    /** The crack overlay's id for a ground block: its own, so a player's mining overlay there isn't disturbed. */
    private static int overlayId(BlockPos pos) {
        return -0x2C0FFEE ^ (int) (pos.asLong() ^ (pos.asLong() >>> 32));
    }

    private static Vec3 corner(AABB box, int corner) {
        return new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
                (corner & 4) == 0 ? box.minZ : box.maxZ);
    }

    private static String name(BlockState state) {
        return String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
