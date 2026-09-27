package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipContact;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.Collisions;
import net.camacraft.camafy.physics.EntityStack;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.weight.Weights;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.particle.CollisionParticleSpawner;
import net.camacraft.fullstop.common.physics.math.VelocityMath;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.camacraft.fullstop.server.physics.damage.FullStopDamageSources;
import net.camacraft.fullstop.server.physics.damage.KineticDamageApplier;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

/**
 * A moving Valkyrien Skies ship hitting entities. VS settles ships against entities on the game
 * thread without any event (it only keeps entities out of the hull), so this scans, once per
 * level tick, the entities around each ship that moves fast enough to matter.
 *
 * <p>For each body in reach (a ride stack counts as one body, from its bottom): the hull contact
 * the ship drives into hardest; the hit only counts if the SHIP supplies most of the closing
 * speed (an entity running into a hull is FullStop's ordinary wall impact). Then, in SI units:
 * the entity's share of the approach speed {@code a} is {@code dv = a * m_ship / (m_ship + m)};
 * it is knocked away along the contact normal by {@code (1 + restitution) * dv} and takes the
 * kinetic damage of losing {@code dv} against the hull block (same thresholds, hardness and
 * floor as a wall impact); the ship gets the opposite impulse. The push happens even when the hit
 * kills, so the body flies off the hull instead of dropping at it, and a player's launch reaches
 * their client unclamped (vanilla's motion packet caps each axis at 3.9 blocks a tick, 78 m/s).
 * Pinned against something that doesn't give, it isn't knocked away but crushed
 * ({@code pinnedDamageMultiplier}), again for as long as the hull presses (standing on the ground,
 * the ground only counts when the hull presses it down into it: {@link #pinProbe}).
 *
 * <p>At any speed: the check looks ahead as far as the next physics frame can carry the hull, and
 * back through what the hull crossed in the latest frame, so a fast hull whose frames come late
 * or in bursts never passes through an entity (measured to 200 m/s); a hull block met only in
 * that swept part strikes with its leading face.
 *
 * <p>Never hit: entities riding this ship (seated, standing on its deck), spectators, creative
 * players, entities tagged {@code fullstop:ship_immune} or {@code fullstop:physics_blacklist},
 * and bodies whose contacts or impacts another mod owns (MKW Mobility's karts and their drivers).
 */
public final class ShipEntityImpacts {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** One hit per ship per entity per this many ticks (knockback, damage, sound). */
    private static final int HIT_COOLDOWN_TICKS = 10;
    /** A non-player sunk deeper than this into the hull is set back outside it. */
    private static final double EMBED_PUSH_OUT_DEPTH = 0.2;
    /** How far past the entity, along the push, something solid means it is pinned. */
    private static final double PIN_PROBE = 0.25;
    /**
     * A push pointing down into the ground by more than this (the cosine against the entity's
     * up; about 45 degrees) presses a grounded entity into it: that pin is real (crushed against
     * the ground). A shallower one only slides it along the ground.
     */
    private static final double PRESSED_INTO_GROUND = 0.7;
    /** How far below its feet solid ground makes an entity grounded for the pin check. */
    private static final double GROUND_PROBE = 0.05;

    private ShipEntityImpacts() {
    }

    /** The per-level cap on entities checked against hulls this tick (maxHitChecksPerTick). */
    static final class Budget {
        private int left;

        Budget(int checks) {
            this.left = checks;
        }

        boolean take() {
            return left-- > 0;
        }
    }

    /**
     * Where to look for entities this ship could hit this tick, or null when it can't hit any:
     * in grace, static, or no part of the hull moving at pushMinSpeed.
     */
    @Nullable
    static AABB hitBox(ShipRef ship, ShipTracker.State state, AABB bounds, long now) {
        if (state.inGrace(now) || ship.isStatic()) return null;
        double halfDiagonal = 0.5 * Math.sqrt(bounds.getXsize() * bounds.getXsize()
                + bounds.getYsize() * bounds.getYsize() + bounds.getZsize() * bounds.getZsize());
        double fastest = state.velocity().length() + state.angularVelocity().length() * halfDiagonal;
        if (fastest < SERVER.pushMinSpeed.get()) return null;
        return bounds.inflate(1.0 + fastest * (state.sweepSeconds(now) + sinceLastFrame(state)));
    }

    static void process(ServerLevel level, ShipRef ship, ShipTracker.State state, AABB hitBox,
                        List<Entity> nearby, Budget budget, ShipPhysicsServer.Pilot pilot) {
        Set<Entity> bodies = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entity entity : nearby) {
            if (!entity.getBoundingBox().intersects(hitBox)) continue;
            Entity root = entity.getRootVehicle();
            if (!bodies.add(root)) continue;
            if (!isBody(root) || excluded(ship, root)) continue;
            if (!budget.take()) return;
            hit(level, ship, state, root, pilot);
        }
    }

    /** What FullStop treats as a body that can be struck (its entity-collision set). */
    private static boolean isBody(Entity entity) {
        return entity instanceof LivingEntity || entity instanceof Boat || entity instanceof AbstractMinecart
                || entity instanceof FallingBlockEntity;
    }

    private static boolean excluded(ShipRef ship, Entity root) {
        if (DamageImmunityRules.unphysableIgnoringAi(root)) return true; // items, orbs, blacklist, dead
        if (root.isSpectator()) return true;
        if (root instanceof Player player && player.isCreative()) return true;
        if (root.getType().is(FullStopTags.SHIP_IMMUNE)) return true;
        // A seat that lives on this ship (its root sits in the ship's shipyard).
        if (ship.equals(Ships.managing(root))) return true;
        // Riding this ship: standing on its deck, or seated on it.
        if (ship.equals(ShipEntities.standingOn(root, 5)) || ship.isUnderfoot(root)) return true;
        int contactOrImpact = PhysicsAspect.CONTACT.bit | PhysicsAspect.IMPACT.bit;
        for (Entity member : EntityStack.selfAndPassengers(root)) {
            if (ship.equals(ShipEntities.mountedTo(member))) return true;
            // Another mod owns this body's contacts/impacts (an MKW kart and its driver).
            if ((PhysicsClaims.live(member) & contactOrImpact) != 0) return true;
        }
        return false;
    }

    private static void hit(ServerLevel level, ShipRef ship, ShipTracker.State state, Entity root,
                            ShipPhysicsServer.Pilot pilot) {
        long now = level.getGameTime();
        FullStopCapability cap = grabCapability(root);
        if (cap != null && cap.isShipHitOnCooldown(ship.id(), now)) return;

        AABB box = root.getBoundingBox();
        GravityFrame frame = cap != null ? cap.getFrame() : FullStopCapability.currentFrame(root);
        // The entity's own velocity (m/s) plus any carry a ship gives it: world motion.
        Vec3 own = VelocityMath.entityVelocity(root);
        Vec3 entityV = own.add(ShipCompat.shipDragVelocityMps(root));
        Vec3 hullAtCentre = ship.velocityAt(box.getCenter());
        if (hullAtCentre == null) return;

        // Everything of the hull within reach before the next physics frame (a tick, or more
        // while frames are late) of travel toward the entity - and, on a tick that brought a new
        // frame, everything that passed THROUGH it during that frame: a fast hull whose frame
        // came late, or in a burst, or straight from rest, can have crossed a small entity whole.
        Vec3 relative = hullAtCentre.subtract(entityV);
        Vec3 hullStep = relative.scale(state.sweepSeconds(now));
        Vec3 hullPast = relative.scale(sinceLastFrame(state));
        List<ShipContact> contacts = ship.contacts(box.expandTowards(hullStep.scale(-1)).expandTowards(hullPast).inflate(0.05));
        if (contacts.isEmpty()) return;

        // A block the entity actually touches meets it through the face it came in by (the
        // contact's least-penetration face). A block only in the swept part - hull still on its
        // way, perhaps metres off at speed - meets it with its LEADING face, the one facing along
        // the hull's motion: least penetration there is meaningless (a hull reaching 3 m into the
        // sweep but only 0.6 m across the entity read as a side-on graze, and an 80 m/s ship flew
        // straight through a villager).
        Set<BlockPos> touching = null;
        Direction leading = null;
        Vec3 leadingNormal = null;

        BlockPos bestPos = null;
        Vec3 normal = null; // from the hull toward the entity: the way it is pushed out
        Vec3 bestPoint = null;
        Vec3 bestHullV = null;
        double approach = 0.0;
        for (ShipContact contact : contacts) {
            Direction face = contact.face();
            Vec3 faceNormal = contact.normal();
            if (hullStep.lengthSqr() > 1.0e-6) {
                if (touching == null) {
                    touching = new HashSet<>();
                    for (ShipContact touched : ship.contacts(box.inflate(0.05))) touching.add(touched.pos());
                }
                if (!touching.contains(contact.pos())) {
                    if (leading == null) {
                        Vec3 inShip = ship.dirToShip(relative);
                        if (inShip == null) continue;
                        leading = Direction.getNearest(inShip.x, inShip.y, inShip.z);
                        leadingNormal = ship.faceNormal(leading);
                    }
                    if (leadingNormal == null) continue;
                    face = leading;
                    faceNormal = leadingNormal;
                }
            }
            Vec3 step = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            Vec3 point = ship.toWorld(Vec3.atCenterOf(contact.pos()).add(step.scale(0.5)));
            if (point == null) continue;
            Vec3 hullV = ship.velocityAt(point);
            if (hullV == null) continue;
            double closing = hullV.subtract(entityV).dot(faceNormal);
            if (closing > approach) {
                approach = closing;
                bestPos = contact.pos();
                normal = faceNormal;
                bestPoint = point;
                bestHullV = hullV;
            }
        }
        if (bestPos == null) return;

        // The ship must be doing the hitting: its own speed into the entity at least what the
        // entity brings (running into a hull is an ordinary FullStop wall impact).
        double hullIn = bestHullV.dot(normal);
        if (hullIn < Math.max(SERVER.pushMinSpeed.get(), -entityV.dot(normal))) return;

        double entityKg = Math.max(EntityStack.sum(root, Weights::bodyMassKg), 1.0e-3);
        double shipKg = state.mass();
        double speedLost = Double.isInfinite(shipKg) ? approach : approach * shipKg / (shipKg + entityKg);
        double kick = (1.0 + SERVER.entityRestitution.get()) * speedLost;

        boolean pinned = isPinned(level, ship, root, box, normal, frame);
        BlockState hull = level.getBlockState(bestPos);
        double up = frame.vertical(normal);
        boolean hullAbove = up < -0.7;
        boolean hullBelow = up > 0.7;

        // Damage first: vanilla's own on-hurt knockback must not overwrite the push below.
        boolean hurt = false;
        if (SERVER.damageEntities.get()) {
            double damage = KineticDamageCalculator.damageForSpeedLost(root, speedLost, hullAbove || hullBelow, List.of(hull))
                    * SERVER.entityDamageMultiplier.get();
            if (pinned) damage *= SERVER.pinnedDamageMultiplier.get();
            if (damage >= 1) {
                Entity credited = SERVER.creditPilot.get() ? pilot.get() : null;
                DamageSource source = FullStopDamageSources.ship(root, credited,
                        FullStopDamageSources.mutualVelocityDisplay(approach), pinned, hullAbove, hullBelow);
                hurt = KineticDamageApplier.applyShipHit(root, cap, source, damage, hullAbove, hullBelow);
            }
        }

        Vec3 pushed = Vec3.ZERO;
        // Not isAlive(): a hit that kills still throws the body (it keeps moving through its
        // death animation), and a dead body is never hit again (unphysable).
        if (!pinned && !root.isRemoved()) {
            // Only the entity's OWN motion is kept (carry excluded): a server-side player's
            // deltaMovement is meaningless, so the measured velocity stands in for it.
            Vec3 ownBpt = root.hasImpulse ? Gravity.getWorldVelocity(root, frame) : own.scale(0.05);
            pushed = normal.scale(kick * 0.05);
            Gravity.setWorldVelocity(root, ownBpt.add(pushed), frame);
            root.hasImpulse = true;
            root.hurtMarked = true;
            PacketHandler.syncMotionToControllingDriver(root);
            PacketHandler.syncLaunchToPlayer(root);
            pushOutIfEmbedded(level, ship, root, normal);

            if (SERVER.reactionImpulses.get()) {
                ShipImpulses.applyCapped(level, ship, normal.scale(-entityKg * kick), bestPoint);
            }
        }

        CollisionParticleSpawner.spawnParticle(level, bestPoint, Collision.CollisionType.SOLID, hull);
        float volume = Mth.clamp((float) (approach * 0.05), 0.2f, 1.0f);
        float pitch = Mth.clamp(0.9f + (float) (approach / 100.0) * 0.8f, 0.9f, 1.7f);
        FSSoundPlayer.playSoundServer(root, hull.getSoundType().getFallSound(), SoundSource.BLOCKS, volume, pitch);

        if (cap != null) {
            cap.markShipHit(ship.id(), now, HIT_COOLDOWN_TICKS);
        }

        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] ship {} hit {}: approach {} m/s, lost {} m/s, kick {} m/s, pinned={} hull={} "
                            + "normal=({}) entity {} kg vs ship {} kg, hurt={}",
                    ship.id(), root.getName().getString(), fmt(approach), fmt(speedLost), fmt(kick), pinned,
                    net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(hull.getBlock()),
                    String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", normal.x, normal.y, normal.z),
                    fmt(entityKg), Double.isInfinite(shipKg) ? "?" : fmt(shipKg), hurt);
        }
    }

    /** Seconds of hull travel the latest frame brought, on the tick it arrived; 0 otherwise. */
    private static double sinceLastFrame(ShipTracker.State state) {
        return state.newFrame() ? Math.min(state.frameSeconds(), 0.5) : 0.0;
    }

    /**
     * Whether something solid right behind the entity (along the push) keeps it from going
     * anywhere: terrain, or another ship. See {@link #pinProbe} for which way "behind" is.
     */
    private static boolean isPinned(ServerLevel level, ShipRef ship, Entity root, AABB box, Vec3 normal, GravityFrame frame) {
        AABB beyond = box.move(pinProbe(level, root, box, normal, frame));
        if (Collisions.blockedByBlocks(level, root, beyond)) return true;
        for (ShipRef other : Ships.intersecting(level, beyond)) {
            if (!other.equals(ship) && other.touchesBlock(beyond)) return true;
        }
        return false;
    }

    /**
     * Where the pin check looks: {@link #PIN_PROBE} along the push, except for an entity standing
     * on the ground (in its own gravity frame) pushed along or slightly into it. The ground
     * stops only the part of the push that goes into it, so the probe drops that part and looks
     * the full distance along the ground: a hull whose normal dips a hair downward (rotation
     * noise, a ship pitched a little nose-down) no longer "pins" a mob standing in the open, and
     * a wall behind it still does. A push mostly into the ground ({@link #PRESSED_INTO_GROUND}, a
     * hull coming down on it) is left as it is: crushed against the ground is a real pin.
     */
    static Vec3 pinProbe(ServerLevel level, Entity root, AABB box, Vec3 normal, GravityFrame frame) {
        Vec3 up = frame.up();
        double along = normal.dot(up);
        if (along < 0.0 && along > -PRESSED_INTO_GROUND
                && (root.onGround() || Collisions.blockedByBlocks(level, root, box.move(up.scale(-GROUND_PROBE))))) {
            Vec3 across = normal.subtract(up.scale(along));
            double length = across.length();
            if (length > 1.0e-9) return across.scale(PIN_PROBE / length);
        }
        return normal.scale(PIN_PROBE);
    }

    /**
     * A non-player the hull swallowed deeper than {@link #EMBED_PUSH_OUT_DEPTH} is set back out
     * along the push, if there is room. Never a player: the server sends no position packet for
     * a plain setPos, and the next move packet would rubber-band them.
     */
    private static void pushOutIfEmbedded(ServerLevel level, ShipRef ship, Entity root, Vec3 normal) {
        if (root instanceof Player) return;
        double depth = 0.0;
        for (ShipContact contact : ship.contacts(root.getBoundingBox())) {
            depth = Math.max(depth, contact.depth());
        }
        if (depth <= EMBED_PUSH_OUT_DEPTH) return;
        Vec3 out = normal.scale(depth * ship.scale() + 0.01);
        AABB moved = root.getBoundingBox().move(out);
        if (level.noCollision(root, moved) && !ship.touchesBlock(moved)) {
            root.setPos(root.position().add(out));
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
