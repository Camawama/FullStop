package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.vs.ShipJoint;
import net.camacraft.camafy.compat.vs.ShipJoints;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Honey glue: a Valkyrien Skies ship stuck to the ground, or to another ship, where honey
 * (block tag {@code fullstop:gluey}) touched it in a crash ({@link ShipSlimeHoney} offers those).
 *
 * <h3>Making it</h3>
 * An offer waits until the two have settled against each other - the hull's point at the contact
 * within {@link #REACH} of where it touched, moving less than {@link #SETTLE_SPEED} against the
 * other body there for {@link #SETTLE_FRAMES} physics frames running (the top of a little hop
 * after the crash is slow too, but only for a moment). Meanwhile honey holds on: whatever parts
 * or slides the two at the point is taken out again (VS's classic backend goes on pushing two
 * hulls that met apart for a while). It waits for up to {@link #OFFER_FRAMES} frames,
 * then glues them at that point, as they are, with Camafy's {@link ShipJoints#glue} (a fixed joint
 * in VS's own physics).
 * One glue per pair within {@link #SAME_GLUE} of an existing one. A pair that was just torn apart
 * isn't glued again for {@link #TORN_COOLDOWN} ticks (it is still scraping over the honey).
 *
 * <h3>Ending it</h3>
 * <ul>
 *   <li>pulled apart harder than {@code honeyGlueStrength} (or that at 1 m of twist), or dragged
 *   0.25 m apart: Camafy tears the joint (see {@link ShipJoints} for how each VS backend knows);</li>
 *   <li>the honey, or the block it glues to, is broken or replaced (checked every tick);</li>
 *   <li>a ship is disassembled or deleted.</li>
 * </ul>
 *
 * <h3>Saving</h3>
 * VS drops a ship's joints when the ship leaves the physics world, and Camafy doesn't save them.
 * So every glue is kept here, in the level's saved data ({@code fullstop_ship_glue}): the two
 * ships, the two anchors and the two blocks. A glue whose joint went (a ship unloaded, the server
 * restarted) is glued again, at the ships' poses then, once both ships are loaded, both blocks are
 * still there and the anchors are still within {@link #REACH} of each other; a glue whose ship no
 * longer exists, or that drifted apart, is forgotten.
 */
public final class ShipGlue {

    /** The slowest crash (m/s into the honey) that glues; ShipTerrainImpacts subscribes at least this low. */
    public static final double MIN_SPEED = 0.5;
    /** How far (m) the hull may be from where it touched the honey for the glue to form, or re-form. */
    static final double REACH = 0.35;
    /** Relative speed (m/s) at the contact below which the two have settled. */
    static final double SETTLE_SPEED = 0.5;
    /** Physics frames running the two must have been settled for (a sixth of a second). */
    static final int SETTLE_FRAMES = 10;
    /** Physics frames an offer waits for the two to settle (about 3 s). */
    static final int OFFER_FRAMES = 200;
    /** Game ticks an offer waits at most, however few physics frames came. */
    static final int OFFER_TICKS = 2400;
    /** A new glue within this distance (m) of an existing one between the same two is the same glue. */
    static final double SAME_GLUE = 3.0;
    /** Ticks after a tear before the same two can be glued again. */
    static final int TORN_COOLDOWN = 40;
    /** Ticks between tries to glue again a glue whose joint went, or that VS refused. */
    static final int RETRY_TICKS = 20;
    /** Refusals by VS before a glue is given up. */
    static final int MAX_FAILURES = 5;

    private static final String DATA_NAME = "fullstop_ship_glue";

    /** A glue waiting for its two bodies to settle. Anchors: ship space of each, world for the ground. */
    private static final class Offer {
        final long ship;
        final long other;
        final Vec3 anchor;
        final Vec3 otherAnchor;
        final BlockPos shipBlock;
        final BlockPos otherBlock;
        final long expires;
        /** The contact's normal, from the other body toward the ship. */
        final Vec3 normal;
        /** Physics frames seen since the offer, and settled ones running. */
        int frames;
        int settled;

        Offer(long ship, long other, Vec3 anchor, Vec3 otherAnchor, BlockPos shipBlock, BlockPos otherBlock, Vec3 normal,
              long expires) {
            this.normal = normal;
            this.ship = ship;
            this.other = other;
            this.anchor = anchor;
            this.otherAnchor = otherAnchor;
            this.shipBlock = shipBlock;
            this.otherBlock = otherBlock;
            this.expires = expires;
        }
    }

    private static final Map<ServerLevel, List<Offer>> OFFERS = new WeakHashMap<>();
    /** Per level: pair key -> tick until which the pair isn't glued again. */
    private static final Map<ServerLevel, Map<Long, Long>> TORN = new WeakHashMap<>();

    private ShipGlue() {
    }

    // ------------------------------------------------------------------
    // Offers (from ShipSlimeHoney)
    // ------------------------------------------------------------------

    /** A ship's hull met honey on the ground, or honey on it met the ground. */
    static void offerTerrain(ServerLevel level, ShipRef ship, List<ShipTerrainImpacts.Strike> honey, long now) {
        // the contact nearest the middle of the honey contacts carries the glue
        Vec3 centre = Vec3.ZERO;
        for (ShipTerrainImpacts.Strike s : honey) centre = centre.add(s.point());
        centre = centre.scale(1.0 / honey.size());
        ShipTerrainImpacts.Strike best = null;
        for (ShipTerrainImpacts.Strike s : honey) {
            if (s.hullPos() == null || s.terrainPos() == null) continue;
            if (best == null || s.point().distanceToSqr(centre) < best.point().distanceToSqr(centre)) best = s;
        }
        if (best == null) return;
        Vec3 anchor = ship.toShip(best.point());
        if (anchor == null) return;
        offer(level, new Offer(ship.id(), ShipJoints.GROUND, anchor, best.point(), best.hullPos(), best.terrainPos(),
                best.normal(), now + OFFER_TICKS), best.point(), now);
    }

    /** Two ships met with honey on either side. {@code points}: {point, normal}; {@code blocks}: {on a, on b}. */
    static void offerShips(ServerLevel level, ShipRef a, ShipRef b, List<Vec3[]> points, List<BlockPos[]> blocks, long now) {
        Vec3 centre = Vec3.ZERO;
        for (Vec3[] p : points) centre = centre.add(p[0]);
        centre = centre.scale(1.0 / points.size());
        int best = -1;
        for (int i = 0; i < points.size(); i++) {
            BlockPos[] pair = blocks.get(i);
            if (pair[0] == null || pair[1] == null) continue;
            if (best < 0 || points.get(i)[0].distanceToSqr(centre) < points.get(best)[0].distanceToSqr(centre)) best = i;
        }
        if (best < 0) return;
        Vec3 point = points.get(best)[0];
        Vec3 anchorA = a.toShip(point);
        Vec3 anchorB = b.toShip(point);
        if (anchorA == null || anchorB == null) return;
        offer(level, new Offer(a.id(), b.id(), anchorA, anchorB, blocks.get(best)[0], blocks.get(best)[1],
                points.get(best)[1], now + OFFER_TICKS), point, now);
    }

    private static void offer(ServerLevel level, Offer offer, Vec3 point, long now) {
        if (tornRecently(level, offer.ship, offer.other, now)) return;
        List<Offer> offers = OFFERS.computeIfAbsent(level, l -> new ArrayList<>());
        for (Offer o : offers) {
            if (samePair(o.ship, o.other, offer.ship, offer.other)) return;
        }
        for (Glue glue : data(level).glues) {
            if (!samePair(glue.ship, glue.other, offer.ship, offer.other)) continue;
            Vec3 at = worldAnchor(level, glue.ship, glue.anchor);
            if (at == null || at.distanceTo(point) < SAME_GLUE) return;
        }
        offers.add(offer);
        ShipSlimeHoney.debug("ship {}: honey against {} at {}: gluing once they settle", offer.ship,
                offer.other == ShipJoints.GROUND ? "the ground" : "ship " + offer.other, ShipSlimeHoney.vec(point));
    }

    // ------------------------------------------------------------------
    // Every tick
    // ------------------------------------------------------------------

    /** Once per level tick: settle offers into glue, check and re-make glue. */
    static void tick(ServerLevel level, ShipTracker tracker, long now) {
        List<Offer> offers = OFFERS.get(level);
        if (offers != null && !offers.isEmpty()) {
            for (Iterator<Offer> it = offers.iterator(); it.hasNext(); ) {
                Offer offer = it.next();
                if (now > offer.expires || offer.frames > OFFER_FRAMES || !SERVER.honeyGlue.get()) {
                    it.remove();
                    continue;
                }
                if (trySettle(level, tracker, offer, now)) it.remove();
            }
        }
        GlueData data = data(level);
        if (data.glues.isEmpty()) return;
        boolean enabled = SERVER.honeyGlue.get() && SERVER.enableShipCollisions.get();
        for (Iterator<Glue> it = data.glues.iterator(); it.hasNext(); ) {
            Glue glue = it.next();
            if (!enabled) {
                if (glue.joint != null) glue.joint.remove();
                it.remove();
                data.setDirty();
                continue;
            }
            if (!keep(level, data, glue, now)) {
                it.remove();
                data.setDirty();
            }
        }
    }

    /** An offer whose two bodies have settled becomes a glue. */
    private static boolean trySettle(ServerLevel level, ShipTracker tracker, Offer offer, long now) {
        ShipRef ship = Ships.byId(level, offer.ship);
        if (ship == null) return true;
        ShipRef other = offer.other == ShipJoints.GROUND ? null : Ships.byId(level, offer.other);
        if (offer.other != ShipJoints.GROUND && other == null) return true;
        if (!blocksHold(level, offer.shipBlock, offer.otherBlock)) return true;
        ShipTracker.State state = tracker.get(offer.ship, now);
        if (state == null || !state.newFrame()) return false; // judged per physics frame
        offer.frames++;
        Vec3 at = ship.toWorld(offer.anchor);
        Vec3 otherAt = other == null ? offer.otherAnchor : other.toWorld(offer.otherAnchor);
        if (at == null || otherAt == null) return true;
        if (at.distanceTo(otherAt) > REACH) {
            offer.settled = 0; // still bouncing off: wait
            return false;
        }
        if (relativeSpeed(ship, other, at) > SETTLE_SPEED) {
            // honey holds on while the glue sets: whatever parts or slides them is taken out again
            offer.settled = 0;
            ShipSlimeHoney.hold(level, offer.ship, offer.other, at, offer.normal, now);
            return false;
        }
        if (++offer.settled < SETTLE_FRAMES) return false;
        Glue glue = new Glue(offer.ship, offer.other, offer.anchor, offer.otherAnchor, offer.shipBlock,
                offer.otherBlock);
        if (!glue(level, glue, ship, other, now)) return false;
        GlueData data = data(level);
        data.glues.add(glue);
        data.setDirty();
        return true;
    }

    /** Joins a glue's two bodies at the ship anchor's world position now; false when VS can't yet. */
    private static boolean glue(ServerLevel level, Glue glue, ShipRef ship, @Nullable ShipRef other, long now) {
        Vec3 at = ship.toWorld(glue.anchor);
        if (at == null) return false;
        ShipJoint joint = ShipJoints.glue(level, glue.ship, glue.other, at, ShipJoints.Settings.breakingAt(SERVER.honeyGlueStrength.get()));
        if (joint == null) return false;
        glue.joint = joint;
        // held as they are now: the other anchor is where the ship's is
        glue.otherAnchor = joint.otherAnchor();
        glue.anchor = joint.anchor();
        ShipSlimeHoney.debug("ship {}: glued to {} at {} ({} N, load read by {})", glue.ship,
                glue.other == ShipJoints.GROUND ? "the ground" : "ship " + glue.other, ShipSlimeHoney.vec(at),
                Math.round(SERVER.honeyGlueStrength.get()), joint.loadMode());
        return true;
    }

    /** Checks one glue; false when it is over and must be forgotten. */
    private static boolean keep(ServerLevel level, GlueData data, Glue glue, long now) {
        ShipJoint joint = glue.joint;
        if (joint != null) {
            switch (joint.state()) {
                case PENDING, ACTIVE -> {
                    if (!blocksHold(level, glue.shipBlock, glue.otherBlock)) {
                        ShipSlimeHoney.debug("ship {}: the honey glue to {} came off: a glued block is gone", glue.ship,
                                glue.other == ShipJoints.GROUND ? "the ground" : "ship " + glue.other);
                        joint.remove();
                        return false;
                    }
                    return true;
                }
                case TORN -> {
                    ShipSlimeHoney.debug("ship {}: the honey glue to {} tore: {}", glue.ship,
                            glue.other == ShipJoints.GROUND ? "the ground" : "ship " + glue.other, joint.endReason());
                    TORN.computeIfAbsent(level, l -> new HashMap<>())
                            .put(ShipSlimeHoney.pairKey(glue.ship, glue.other), now + TORN_COOLDOWN);
                    return false;
                }
                case REMOVED -> {
                    return false;
                }
                case DROPPED, FAILED -> {
                    if (joint.state() == ShipJoint.State.FAILED && ++glue.failures >= MAX_FAILURES) return false;
                    glue.joint = null;
                    glue.retryAt = now + RETRY_TICKS;
                }
            }
        }
        // no joint: a ship unloaded, the server restarted, or VS refused it - glue again when possible
        if (now < glue.retryAt) return true;
        glue.retryAt = now + RETRY_TICKS;
        if (Ships.byIdAny(level, glue.ship) == null) return false; // disassembled or deleted
        if (glue.other != ShipJoints.GROUND && Ships.byIdAny(level, glue.other) == null) return false;
        ShipRef ship = Ships.byId(level, glue.ship);
        ShipRef other = glue.other == ShipJoints.GROUND ? null : Ships.byId(level, glue.other);
        if (ship == null || (glue.other != ShipJoints.GROUND && other == null)) return true; // not loaded yet
        if (!blocksHold(level, glue.shipBlock, glue.otherBlock)) return false;
        Vec3 at = ship.toWorld(glue.anchor);
        Vec3 otherAt = other == null ? glue.otherAnchor : other.toWorld(glue.otherAnchor);
        if (at == null || otherAt == null) return true;
        if (at.distanceTo(otherAt) > REACH) {
            ShipSlimeHoney.debug("ship {}: the honey glue to {} is forgotten: they are {} m apart", glue.ship,
                    glue.other == ShipJoints.GROUND ? "the ground" : "ship " + glue.other,
                    ShipSlimeHoney.fmt(at.distanceTo(otherAt)));
            return false;
        }
        if (relativeSpeed(ship, other, at) > SETTLE_SPEED) return true;
        if (glue(level, glue, ship, other, now)) data.setDirty();
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Both glued blocks still in place, one of them honey (blocks in unloaded chunks count as in
     * place: nothing changes there). Ship blocks are at their shipyard positions, terrain in the world.
     */
    private static boolean blocksHold(ServerLevel level, BlockPos shipBlock, BlockPos otherBlock) {
        BlockState a = level.hasChunkAt(shipBlock) ? level.getBlockState(shipBlock) : null;
        BlockState b = level.hasChunkAt(otherBlock) ? level.getBlockState(otherBlock) : null;
        if (a == null || b == null) return true;
        if (a.isAir() || b.isAir()) return false;
        return ShipSlimeHoney.gluey(a) || ShipSlimeHoney.gluey(b);
    }

    /** How fast the two bodies' material moves against each other at a world point, m/s. */
    private static double relativeSpeed(ShipRef ship, @Nullable ShipRef other, Vec3 at) {
        Vec3 v = ship.velocityAt(at);
        if (v == null) return Double.POSITIVE_INFINITY;
        if (other != null) {
            Vec3 w = other.velocityAt(at);
            if (w == null) return Double.POSITIVE_INFINITY;
            v = v.subtract(w);
        }
        return v.length();
    }

    @Nullable
    private static Vec3 worldAnchor(ServerLevel level, long shipId, Vec3 anchor) {
        ShipRef ship = Ships.byId(level, shipId);
        return ship == null ? null : ship.toWorld(anchor);
    }

    private static boolean samePair(long a, long b, long c, long d) {
        return (a == c && b == d) || (a == d && b == c);
    }

    private static boolean tornRecently(ServerLevel level, long a, long b, long now) {
        Map<Long, Long> map = TORN.get(level);
        if (map == null) return false;
        Long until = map.get(ShipSlimeHoney.pairKey(a, b));
        return until != null && until >= now;
    }

    /** The live glue joints of a ship, for tests and commands. */
    public static List<ShipJoint> joints(ServerLevel level, long shipId) {
        List<ShipJoint> out = new ArrayList<>();
        for (Glue glue : data(level).glues) {
            if ((glue.ship == shipId || glue.other == shipId) && glue.joint != null && glue.joint.isValid()) {
                out.add(glue.joint);
            }
        }
        return out;
    }

    /**
     * Takes a ship's glue joints out as Valkyrien Skies does when the ship unloads, keeping the
     * glues to be made again when possible (for tests and debugging). Returns how many went.
     */
    public static int dropJoints(ServerLevel level, long shipId) {
        int n = 0;
        for (Glue glue : data(level).glues) {
            if ((glue.ship == shipId || glue.other == shipId) && glue.joint != null) {
                glue.joint.remove();
                glue.joint = null;
                glue.retryAt = level.getGameTime() + RETRY_TICKS;
                n++;
            }
        }
        return n;
    }

    /** How many glues (with or without a joint right now) a ship has, for tests and commands. */
    public static int glueCount(ServerLevel level, long shipId) {
        int n = 0;
        for (Glue glue : data(level).glues) {
            if (glue.ship == shipId || glue.other == shipId) n++;
        }
        return n;
    }

    private static GlueData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(GlueData::load, GlueData::new, DATA_NAME);
    }

    // ------------------------------------------------------------------
    // Saved data
    // ------------------------------------------------------------------

    /** One glue: the two bodies, their anchors (ship space; world for the ground) and the two blocks. */
    static final class Glue {
        final long ship;
        final long other;
        Vec3 anchor;
        Vec3 otherAnchor;
        final BlockPos shipBlock;
        final BlockPos otherBlock;
        @Nullable
        ShipJoint joint;
        long retryAt;
        int failures;

        Glue(long ship, long other, Vec3 anchor, Vec3 otherAnchor, BlockPos shipBlock, BlockPos otherBlock) {
            this.ship = ship;
            this.other = other;
            this.anchor = anchor;
            this.otherAnchor = otherAnchor;
            this.shipBlock = shipBlock;
            this.otherBlock = otherBlock;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("ship", ship);
            tag.putLong("other", other);
            putVec(tag, "anchor", anchor);
            putVec(tag, "otherAnchor", otherAnchor);
            tag.putLong("shipBlock", shipBlock.asLong());
            tag.putLong("otherBlock", otherBlock.asLong());
            return tag;
        }

        static Glue load(CompoundTag tag) {
            return new Glue(tag.getLong("ship"), tag.getLong("other"), getVec(tag, "anchor"), getVec(tag, "otherAnchor"),
                    BlockPos.of(tag.getLong("shipBlock")), BlockPos.of(tag.getLong("otherBlock")));
        }

        private static void putVec(CompoundTag tag, String key, Vec3 v) {
            ListTag list = new ListTag();
            list.add(net.minecraft.nbt.DoubleTag.valueOf(v.x));
            list.add(net.minecraft.nbt.DoubleTag.valueOf(v.y));
            list.add(net.minecraft.nbt.DoubleTag.valueOf(v.z));
            tag.put(key, list);
        }

        private static Vec3 getVec(CompoundTag tag, String key) {
            ListTag list = tag.getList(key, Tag.TAG_DOUBLE);
            return new Vec3(list.getDouble(0), list.getDouble(1), list.getDouble(2));
        }
    }

    /** The level's glues, saved with it. */
    static final class GlueData extends SavedData {
        final List<Glue> glues = new ArrayList<>();

        static GlueData load(CompoundTag tag) {
            GlueData data = new GlueData();
            ListTag list = tag.getList("glues", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                data.glues.add(Glue.load(list.getCompound(i)));
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Glue glue : glues) list.add(glue.save());
            tag.put("glues", list);
            return tag;
        }
    }
}
