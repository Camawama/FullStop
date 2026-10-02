package net.camacraft.fullstop.server.physics;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.particle.CollisionParticleSpawner;
import net.camacraft.fullstop.common.physics.rules.DamageImmunityRules;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.sound.FSSoundPlayer;
import net.camacraft.fullstop.server.physics.collision.ServerCollisionDetector;
import net.camacraft.fullstop.server.physics.damage.KineticDamageApplier;
import net.camacraft.fullstop.server.physics.damage.KineticDamageCalculator;
import net.camacraft.fullstop.server.physics.effects.StatusEffectApplier;
import net.camacraft.fullstop.server.physics.interaction.BounceHandler;
import net.camacraft.fullstop.server.physics.interaction.EntityCollisionHandler;
import net.camacraft.fullstop.server.physics.interaction.KineticBlockInteractions;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.server.physics.ship.ShipEntityHits;
import net.camacraft.fullstop.server.physics.ship.ShipImpulses;
import net.camacraft.fullstop.server.physics.ship.ShipPhysicsServer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

import static net.camacraft.fullstop.FullStopConfig.SERVER;
import static net.camacraft.fullstop.common.capability.FullStopCapability.grabCapability;

/**
 * Server physics orchestrator: ticks capabilities, detects collisions, and hands
 * the results to the specialized handlers. Order matters and lives only here.
 */
public class PhysicsDispatchServer {

    /**
     * Entities slower than this (m/s, squared) skip collision processing entirely.
     * 3 m/s is the smallest speed any downstream system cares about
     * (KineticBlockInteractions.MIN_INTERACTION_VELOCITY).
     */
    private static final double MIN_PROCESS_SPEED_SQR = 3.0 * 3.0;


    /** A collision only makes noise/particles when this much speed (m/s) was lost. */
    private static final double MIN_AESTHETIC_STOPPING_FORCE = 2.0;

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
            // Keeps persistent ice-crack overlays alive (clients purge unrefreshed
            // destruction progress) and heals expired ones.
            KineticBlockInteractions.tickCracks(level);

            // Bodies and projectiles meeting in mid-air (Entity / Projectile Collisions). Before each
            // entity's own measurement below: a body stopped where it met another is measured from there.
            net.camacraft.fullstop.server.physics.interaction.BodyCollisions.tick(level);
            net.camacraft.fullstop.server.physics.interaction.ProjectileCollisions.tick(level);

            // Copy: handlers may mount/dismount entities, which mutates the entity list.
            // Every vehicle is processed before its passengers: a passenger of a
            // client-driven vehicle replays the vehicle's per-client-tick steps
            // (FullStopCapability.tick), which must already be known.
            List<Entity> entities = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity.isPassenger()) continue;
                if (entity.isVehicle()) {
                    entity.getSelfAndPassengers().forEach(entities::add);
                } else {
                    entities.add(entity);
                }
            }
            for (Entity entity : entities) {
                onEntityTick(entity);
            }

            // Valkyrien Skies ships: weight on decks, hulls hitting entities and
            // terrain. After the entity loop, so every capability is current.
            ShipPhysicsServer.tick(level);
        }
    }

    @SubscribeEvent
    public static void onEntityChangeDimension(EntityTravelToDimensionEvent event) {
        markTeleported(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityTeleport(EntityTeleportEvent event) {
        markTeleported(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // Only an entity with an already-RUNNING capability needs the teleport
        // shield on a rejoin (mod remove/re-add position jumps). A freshly
        // joining entity's capability seeds itself from its spawn state —
        // blanket-marking gave every new spawn 5 ticks of damage immunity plus
        // velocity resets, so a mob summoned WITH Motion crossed its first
        // ~10 blocks untracked and slammed into walls for free (falls still
        // billed only because they outlast the window).
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null && cap.getLastTick() != -1) {
                cap.setHasTeleported(true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        markTeleported(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        markTeleported(event.getEntity());
    }

    /** Clears any lingering block-crack overlay when an entity dies, unloads, or changes dimension. */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            KineticBlockInteractions.onEntityLeave(event.getEntity(), serverLevel);
        }
    }

    /** Runs on both logical sides so the dismount grace period also works on dedicated servers. */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.justDismounted();
                // While the dismount grace runs, this rider and this vehicle
                // must not exchange collision momentum (see ServerCollisionDetector).
                cap.markVehicleExchange(event.getEntityBeingMounted().getId());
                // Mounting/dismounting snaps the rider's position (right-clicking
                // a minecart from several blocks away teleports the player onto
                // it) — far below the 60 m/s discontinuity guard, so unmarked it
                // read as a violent move that smashed the blocks around the cart
                // and killed the rider.
                cap.setHasTeleported(true);
            }
        }

        // Momentum transfer at the mount boundary, in BOTH directions. The
        // velocity source is the vehicle's observed per-tick movement (position
        // minus old position) — valid on either logical side for any ticked
        // entity, with no capability or packet involved. Crucially, this
        // handler also runs on the DRIVER'S CLIENT, the side that actually owns
        // a driven vehicle's motion: applying the value there at the exact
        // takeover/release moment cannot race anything. (The earlier
        // server→client packet approach lost exactly that race — the boat
        // dead-stopped on entry no matter how the packet was timed.)
        Entity vehicle = event.getEntityBeingMounted();
        Vec3 vehicleMotion = observedVehicleMotion(vehicle);

        // Each velocity write below is a MOTION write on that entity: skipped for
        // blacklisted entities and for anything another mod owns the motion of
        // (MKW's karts; a racer's own motion while in a race).
        if (event.isMounting()) {
            // Boarding a MOVING boat keeps it moving: the new driver's client
            // otherwise starts simulating from the stale synced motion (~0).
            // First passenger only — a second rider must not reset the boat.
            if (vehicle instanceof Boat && vehicle.getFirstPassenger() == null
                    && vehicleMotion.lengthSqr() > 1.0e-6
                    && !PhysicsClaims.handsOff(vehicle, PhysicsAspect.MOTION)) {
                Gravity.setWorldVelocity(vehicle, vehicleMotion, FullStopCapability.currentFrame(vehicle));
            }
        } else {
            // Dismounting — including being thrown off a destroyed vehicle
            // (ejecting passengers fires this event too): the passenger keeps
            // the vehicle's momentum instead of freezing in place.
            if (vehicleMotion.lengthSqr() > 1.0e-4) {
                Entity rider = event.getEntityMounting();
                if (!PhysicsClaims.handsOff(rider, PhysicsAspect.MOTION)) {
                    // observed motion is world-space; deltaMovement may be frame-local
                    Gravity.setWorldVelocity(rider, vehicleMotion, FullStopCapability.currentFrame(rider));
                    rider.hasImpulse = true;
                }

                // ...and the VEHICLE keeps its momentum too. While ridden, the
                // server-side boat zeroed its own deltaMovement every tick, so
                // when the dismount hands simulation back to the server it
                // resumed from zero — the boat froze the instant the driver
                // left. Reseeding here lets it glide on under server physics.
                if (vehicle.isAlive() && !PhysicsClaims.handsOff(vehicle, PhysicsAspect.MOTION)) {
                    Gravity.setWorldVelocity(vehicle, vehicleMotion, FullStopCapability.currentFrame(vehicle));
                }
            }
        }
    }

    /**
     * Vanilla already uses the falling_stalactite damage type for falling dripstone;
     * FullStop only scales the damage. (Replaces the old reflection-into-event-source hack.)
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getSource().is(DamageTypes.FALLING_STALACTITE)) {
            event.setAmount(event.getAmount() * SERVER.dripstoneDamageMultiplier.get().floatValue());
        }
    }

    /**
     * The vehicle's per-tick movement, robust to WHEN in the tick it is read.
     * pos − xOld is only nonzero between a position update and the entity's
     * next tick (which copies pos into xOld) — a shift-dismount is processed in
     * the connection tick, AFTER the ridden boat's level tick, where that
     * difference reads exactly zero and the momentum seed silently failed its
     * threshold ("the boat just stops anyway"). The capability's measured
     * velocity (one full completed tick, valid at any phase) fills that hole;
     * the larger of the two wins so whichever source is live this phase is used.
     */
    public static Vec3 observedVehicleMotion(Entity vehicle) {
        Vec3 motion = new Vec3(vehicle.getX() - vehicle.xOld,
                vehicle.getY() - vehicle.yOld,
                vehicle.getZ() - vehicle.zOld);
        FullStopCapability cap = grabCapability(vehicle);
        // FRESH measurements only. A boat's client-side capability is ticked
        // only while the local player drives it; after a dismount it freezes at
        // the last driven (fast) velocity while the real boat coasts to a stop
        // — and re-boarding then "restored" that stale speed to a standing
        // boat. Server-side caps tick every tick, so they always pass.
        if (cap != null && vehicle.tickCount - cap.getLastTick() <= 2) {
            Vec3 measured = cap.getCurrentNativeVelocity();
            if (measured.lengthSqr() > motion.lengthSqr()) {
                motion = measured;
            }
        }
        return motion;
    }

    private static void markTeleported(Entity entity) {
        if (entity instanceof LivingEntity living) {
            FullStopCapability cap = grabCapability(living);
            if (cap != null) {
                cap.setHasTeleported(true);
            }
        }
    }

    private static void onEntityTick(Entity entity) {
        // Cheap type/field exclusions first, so drop-like entities never even
        // resolve (and on first touch, allocate) a capability. The NoAI check
        // runs after the grab so it can use the capability's once-a-second cache
        // instead of a locked SynchedEntityData read per mob per tick.
        if (DamageImmunityRules.unphysableIgnoringAi(entity)) return;

        FullStopCapability fullstop = grabCapability(entity);
        if (fullstop == null) return;

        if (entity instanceof Mob mob && fullstop.isNoAiCached(mob)) return;

        // Which parts of this entity's physics other mods own this tick (MKW's
        // karts and their drivers, a grapple swing...): read once and cached on
        // the capability for every system below and for the mixins. An entity
        // another mod owns outright is not simulated at all.
        int claims = fullstop.refreshClaims(entity);
        if (claims == PhysicsAspect.ALL) {
            fullstop.resetVelocity();
            return;
        }

        if (entity.tickCount != fullstop.getLastTick()) {
            // Seated on a Valkyrien Skies ship: the rider feels its seat's acceleration, worked out
            // from the ship's physics frames (VS just sets a seated rider's position from the ship
            // every tick - nothing a g-force can be read from).
            seatAcceleration(entity, fullstop, claims);
            fullstop.tick(entity);
            fullstop.setLastTick(entity.tickCount);
        }

        // Which ship it stands on this tick: a contact with a ship it was already on is a
        // rider's (ShipEntityHits), not a newcomer's.
        if (ShipCompat.active()) {
            ShipRef standingOn = ShipEntities.standingOn(entity, 1);
            fullstop.noteStandingOn(standingOn != null ? standingOn.id() : ShipRef.NO_ID, entity.level().getGameTime());
        }

        if (fullstop.getIsDamageImmune()) {
            fullstop.resetVelocity();
            return;
        }

        // G-force status effects depend on the smoothed average, not current speed,
        // so they must run before the speed gate (a hard stop leaves speed ~0).
        if (!PhysicsAspect.G_FORCE.in(claims)) {
            StatusEffectApplier.applyForceEffects(entity, fullstop);
            // a seated player's client shows the g its seat gives it (vignette, blackout, muffle)
            if (entity instanceof net.minecraft.server.level.ServerPlayer player && fullstop.hasSeatAcceleration(entity)) {
                net.camacraft.fullstop.common.handler.PacketHandler.sendToPlayer(player,
                        new net.camacraft.fullstop.common.network.GForcePacket(fullstop.getRunningAverageDelta(),
                                fullstop.getRawRunningAverageDelta()));
            }
        }

        // A client-driven mover whose client ticks did not reach the server
        // this tick has not moved: there is no impact to look for (and its
        // zero position delta must not read as one).
        if (!fullstop.hasFreshSample()) return;

        sonicBoom(entity, fullstop);

        // Pre-impact speed, not last tick's: the damage tick of a two-tick impact
        // arrives with only the small post-contact velocity remaining.
        if (fullstop.getPreImpactScaledVelocity().lengthSqr() < MIN_PROCESS_SPEED_SQR) {
            return;
        }

        boolean blocksFree = !PhysicsAspect.BLOCKS.in(claims);
        boolean impactFree = !PhysicsAspect.IMPACT.in(claims);

        if (blocksFree) {
            // Doors ahead open before they ever become collisions (no speed scrub).
            KineticBlockInteractions.openDoorsAhead(entity, fullstop.getCurrentNativeVelocity());

            // Buttons are shapeless, invisible to the collision rays — swept separately.
            KineticBlockInteractions.pressButtonsInPath(entity, fullstop.getCurrentNativeVelocity());
        }

        Collision collision = ServerCollisionDetector.detect(entity, fullstop);

        if (!PhysicsAspect.CONTACT.in(claims)) {
            EntityCollisionHandler.handle(entity, fullstop, collision);
        }

        boolean significantImpact = impactFree && !collision.fake()
                && fullstop.getStoppingForce() > MIN_AESTHETIC_STOPPING_FORCE;
        if (significantImpact) {
            impactAesthetic(entity, collision);
            impactSound(entity, fullstop, collision);
        }

        double damage = impactFree && !billedThroughVehicle(entity, fullstop)
                ? KineticDamageCalculator.calculateDamage(entity, fullstop, collision) : 0;
        boolean debugLog = SERVER.debugImpactLog.get()
                && (!collision.fake() || fullstop.getStoppingForce() > DEBUG_LOG_MIN_STOPPING_FORCE);
        if (debugLog) {
            debugImpact(entity, fullstop, collision, damage);
        }

        boolean brokeBlock = false;
        double smashDamage = 0.0;
        if (blocksFree && !collision.impactedPositions.isEmpty()) {
            KineticBlockInteractions.ImpactResult impactResult = KineticBlockInteractions.handleBlockImpacts(
                    entity, fullstop.getPreviousNativeVelocity(), collision.impactedPositions, collision.impactedHits);
            brokeBlock = impactResult.brokeBlock();
            smashDamage = impactResult.smashDamage();
        } else if (entity.level() instanceof ServerLevel serverLevel) {
            KineticBlockInteractions.clearCrack(entity, serverLevel);
        }

        // Running, flying or falling into a Valkyrien Skies ship is a collision between two bodies,
        // not a wall impact: momentum both ways, damage from the speed really lost.
        Collision damageCollision = collision;
        ShipEntityHits.Outcome shipHit = impactFree
                ? ShipEntityHits.onEntityImpact(entity, fullstop, collision, brokeBlock) : null;
        if (shipHit != null && shipHit.handled()) {
            // Only world blocks struck in the same tick still count as a wall.
            damage = ShipCompat.onlyShipBlocks(entity.level(), collision.impactedPositions)
                    ? shipHit.damage() : Math.max(damage, shipHit.damage());
            if (!shipHit.collision().fake()) damageCollision = shipHit.collision();
        } else if (shipHit == null || shipHit.legacy()) {
            // A landing on a ship's deck pushes the ship: a force ON the ship, so it
            // applies whoever owns this entity's own impact physics.
            ShipImpulses.onEntityImpact(entity, fullstop, collision);
        }
        if (impactFree) {
            damage += smashDamage;
        }

        if (!PhysicsAspect.BOUNCE.in(claims)) {
            BounceHandler.apply(entity, fullstop, collision, brokeBlock);
        }

        if (damage >= 1) {
            // Sound and sprain only when the applier actually hurt something —
            // it can bail on its cooldown or mitigate everything to zero, and a
            // big-fall thud with no damage reads as a bug.
            boolean hurt = KineticDamageApplier.apply(entity, fullstop, damageCollision, damage);
            if (debugLog) {
                LOGGER.info("[FullStop] impact applied={} ({} damage) to {}", hurt, String.format(java.util.Locale.ROOT, "%.2f", damage), entity.getName().getString());
            }
            if (hurt) {
                impactDamageSound(entity, damage, damageCollision);
                StatusEffectApplier.applyDamageEffects(entity, fullstop, damageCollision, damage);
            }
        }
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * A living rider seated on a Valkyrien Skies ship (a helm, a VS, Create or Clockwork seat: its
     * vehicle is mounted on the ship) feels the acceleration of the ship's material at its seat -
     * the ship speeding up or braking, turning, spinning up, and the centripetal pull of a turn or
     * spin (which a seated rider does feel: the seat holds it on the curve) - from the ship's
     * physics frames (ShipTracker: burst-proof, a frame's time span measured). Only while no other
     * mod owns its g-force (G_FORCE claims); riders standing on a deck are left as they are.
     */
    private static void seatAcceleration(Entity entity, FullStopCapability fullstop, int claims) {
        if (!entity.isPassenger() || !(entity instanceof LivingEntity) || PhysicsAspect.G_FORCE.in(claims)) return;
        if (!ShipCompat.active() || !(entity.level() instanceof ServerLevel level)) return;
        ShipRef ship = ShipEntities.mountedTo(entity);
        if (ship == null) return;
        net.camacraft.fullstop.server.physics.ship.ShipTracker.State state =
                net.camacraft.fullstop.server.physics.ship.ShipTracker.of(level).latest(ship.id());
        if (state == null || state.inGrace(level.getGameTime())) return;
        // VS's physics catching up after a stall lurches a Clockwork rotor round for a frame or two:
        // not something the seat did to its rider (see ShipTracker.STALL_QUIET_SECONDS)
        if (state.deckQuiet(level.getGameTime())) return;
        fullstop.setSeatAcceleration(entity, state.accelerationAt(entity.position()));
    }

    /**
     * Whether this rider's impact is its vehicle's to bill: FullStop measures the vehicle's crash
     * (it isn't blacklisted, and no other mod owns its impacts) and hands its riders their share
     * of it - of a landing always, of a wall crash when the vehicle isn't alive itself (a boat, a
     * cart, an MKW kart; see KineticDamageApplier). The rider's own reading of the same crash
     * would bill it twice, and a seated player's server-side onGround is stale anyway (vanilla
     * never updates it for passengers), so its own evidence can't be trusted.
     */
    private static boolean billedThroughVehicle(Entity entity, FullStopCapability fullstop) {
        if (!entity.isPassenger()) return false;
        Entity vehicle = entity.getRootVehicle();
        if (vehicle == entity || DamageImmunityRules.unphysable(vehicle)) return false;
        if (PhysicsClaims.handsOff(vehicle, PhysicsAspect.IMPACT) || grabCapability(vehicle) == null) return false;
        return !(vehicle instanceof LivingEntity) || fullstop.isMostlyDownward();
    }

    /** Below this speed lost (m/s) a tick without a raycast hit is not worth a debug line. */
    private static final double DEBUG_LOG_MIN_STOPPING_FORCE = 4.0;

    /**
     * One line per significant impact tick (config debugImpactLog): everything
     * the damage gate looked at, so an impact that should have hurt but did
     * not can be diagnosed from the log alone.
     */
    private static void debugImpact(Entity entity, FullStopCapability fullstop, Collision collision, double damage) {
        net.camacraft.camafy.compat.gravity.GravityFrame frame = fullstop.getFrame();
        Vec3 up = frame.up();
        Vec3 pre = fullstop.getPreImpactScaledVelocity();
        StringBuilder faces = new StringBuilder();
        for (int i = 0; i < collision.impactedHits.size(); i++) {
            if (i > 0) faces.append(',');
            faces.append(collision.impactedHits.get(i).getDirection().getName());
            if (i < collision.blockStates.size()) {
                faces.append(':').append(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(collision.blockStates.get(i).getBlock()));
            }
        }
        LOGGER.info("[FullStop] impact {} frame={} up=({}) pre={} m/s (v={} h={}) lost total={} vertical={} horizontal={} world=({},{},{}) "
                        + "evidence[recentV={} recentH={} onGround={} ship={}] immune={} claims={} collision={} faces=[{}] entities={} damage={}",
                entity.getName().getString(),
                frame.isDefault() ? "default" : "rotated",
                String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", up.x, up.y, up.z),
                String.format(java.util.Locale.ROOT, "%.1f", pre.length()),
                String.format(java.util.Locale.ROOT, "%.1f", frame.vertical(pre)),
                String.format(java.util.Locale.ROOT, "%.1f", frame.horizontalDistance(pre)),
                String.format(java.util.Locale.ROOT, "%.1f", fullstop.getStoppingForce()),
                String.format(java.util.Locale.ROOT, "%.1f", fullstop.getVerticalStoppingForce()),
                String.format(java.util.Locale.ROOT, "%.1f", fullstop.getHorizontalStoppingForce()),
                String.format(java.util.Locale.ROOT, "%.1f", fullstop.getAxisStoppingForce(net.minecraft.core.Direction.Axis.X)),
                String.format(java.util.Locale.ROOT, "%.1f", fullstop.getAxisStoppingForce(net.minecraft.core.Direction.Axis.Y)),
                String.format(java.util.Locale.ROOT, "%.1f", fullstop.getAxisStoppingForce(net.minecraft.core.Direction.Axis.Z)),
                fullstop.hadRecentVerticalHit(), fullstop.hadRecentHorizontalHit(), entity.onGround(),
                net.camacraft.fullstop.common.compat.ShipCompat.anyShipBlock(entity.level(), collision.impactedPositions),
                fullstop.getIsDamageImmune(),
                Integer.toBinaryString(fullstop.getClaims()),
                collision.collisionType, faces, collision.collidingEntities.size(),
                String.format(java.util.Locale.ROOT, "%.2f", damage));
    }

    /** The speed of sound squared: anything slower that isn't being followed is skipped at once. */
    private static final double SOUND_SPEED_SQR = SonicBooms.SOUND_MPS * SonicBooms.SOUND_MPS;

    /**
     * An entity past the speed of sound booms for those it passes ({@link SonicBooms}: once per supersonic stretch, for
     * each listener when the shock reaches them, from where it came). A ride stack booms once, from its bottom: its
     * riders are aboard it.
     */
    private static void sonicBoom(Entity entity, FullStopCapability fullstop) {
        if (!(entity.level() instanceof ServerLevel serverLevel) || entity.isPassenger()) return;
        Vec3 velocity = fullstop.getCurrentScaledVelocity();
        if (!fullstop.isSupersonic() && velocity.lengthSqr() < SOUND_SPEED_SQR) return;
        Entity root = entity.getRootVehicle();
        fullstop.setSupersonic(SonicBooms.observe(serverLevel, SonicBooms.Key.entity(entity), entity.getBoundingBox().getCenter(),
                velocity, player -> player.getRootVehicle() == root));
    }

    private static void impactAesthetic(Entity entity, Collision collision) {
        // Particles spawn AT the impact point (the ray hit location on the block
        // face), not at the entity's feet — blockStates and impactedHits are
        // built pairwise by the detector, so index i of one matches the other.
        double size = net.camacraft.camafy.compat.scale.EntityScale.heightFactor(entity);
        for (int i = 0; i < collision.blockStates.size(); i++) {
            Vec3 at = i < collision.impactedHits.size()
                    ? collision.impactedHits.get(i).getLocation()
                    : entity.position();
            // a hit on a ship block can come back addressed in the ship's yard, far from where it was struck
            if (at.distanceToSqr(entity.position()) > 16.0 * 16.0) at = entity.position();
            CollisionParticleSpawner.spawnParticle(entity.level(), at, collision.collisionType, collision.blockStates.get(i), size);
        }
    }

    private static void impactSound(Entity entity, FullStopCapability fullstop, Collision collision) {
        if (!fullstop.canPlaySound()) return;

        float volume = ((float) (fullstop.getStoppingForce() * 0.05f));
        int hitCount = collision.blockStates.size() + collision.collidingEntities.size();
        if (hitCount > 1) volume /= (float) (hitCount * 0.6);
        volume = Mth.clamp(volume, 0.0f, 1.0f);

        float minPitch = 0.9f;
        float maxPitch = 1.7f;
        float pitch = (float) Mth.clamp(minPitch + (fullstop.getStoppingForce() / 100f) * (maxPitch - minPitch), minPitch, maxPitch);

        List<SoundEvent> playedSounds = new ArrayList<>();
        for (Entity collidedEntity : collision.collidingEntities) {
            SoundEvent hitSound = SoundEvents.BOOK_PUT;
            if (!playedSounds.contains(hitSound)) {
                FSSoundPlayer.playSoundServer(entity.level(), collidedEntity.blockPosition(), hitSound, SoundSource.PLAYERS, volume, pitch);
                playedSounds.add(hitSound);
            }
        }
        for (BlockState blockState : collision.blockStates) {
            SoundType soundType = blockState.getSoundType();
            // a liquid's own block is silent; one met as a solid (oobleck struck hard) thuds like mud
            if (soundType == SoundType.EMPTY
                    && net.camacraft.fullstop.common.physics.collision.CommonCollisionDetector.isLiquidBlock(blockState)) {
                soundType = SoundType.MUD;
            }
            SoundEvent sound = soundType.getFallSound();
            if (!playedSounds.contains(sound)) {
                FSSoundPlayer.playSoundServer(entity.level(), entity.blockPosition(), sound, SoundSource.PLAYERS, volume, pitch);
                playedSounds.add(sound);
            }
            if (entity instanceof Minecart) {
                FSSoundPlayer.playSoundServer(entity.level(), entity.blockPosition(), SoundEvents.IRON_GOLEM_HURT, SoundSource.PLAYERS, volume / 2, pitch * (float) Math.random());
                FSSoundPlayer.playSoundServer(entity.level(), entity.blockPosition(), SoundEvents.ANVIL_FALL, SoundSource.PLAYERS, volume / 2, pitch * (float) Math.random());
            }
        }
        fullstop.setSoundCooldown(4);
    }

    private static void impactDamageSound(Entity entity, double damage, Collision collision) {
        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) return;
        if (!(entity instanceof LivingEntity)) return;

        float volume = Math.min(0.1F * (float) damage, 1.0F);
        float pitch = 0.5F;
        if (collision.collisionType == Collision.CollisionType.SOLID) {
            FSSoundPlayer.playSoundServer(entity, SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, volume, pitch);
        }
    }
}
