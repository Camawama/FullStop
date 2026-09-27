package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * FullStop's Valkyrien Skies ship physics, once per level tick after the entity loop (see
 * PhysicsDispatchServer). Order matters and lives only here:
 * <ol>
 *   <li>every loaded ship's motion is recorded ({@link ShipTracker}: last tick's velocity, grace
 *   for new and teleported ships);</li>
 *   <li>ONE entity query per ship feeds both passes below;</li>
 *   <li>ship weight ({@link ShipWeightHandler}): what stands on each deck;</li>
 *   <li>deck momentum ({@link ShipDeckMomentum}): riders keep what a deck that stops or swerves
 *   loses beyond their grip;</li>
 *   <li>ship hits ({@link ShipEntityImpacts}): what each moving hull strikes;</li>
 *   <li>vegetation ({@link ShipVegetationSweep}): the leaves and plants each moving hull passes
 *   through, which VS never reports;</li>
 *   <li>impact sounds and terrain, hull (and opt-in ship) breaking from VS's reported collisions
 *   ({@link ShipTerrainImpacts}).</li>
 * </ol>
 * Everything is off without Valkyrien Skies or with enableValkyrienSkiesCompat off.
 */
public final class ShipPhysicsServer {

    private ShipPhysicsServer() {
    }

    public static void tick(ServerLevel level) {
        if (!ShipCompat.active()) return;
        boolean weight = ShipCompat.weightActive();
        boolean collisions = ShipCompat.collisionsActive();
        boolean deckMomentum = ShipDeckMomentum.enabled();

        long now = level.getGameTime();
        ShipTracker tracker = ShipTracker.of(level);
        int grace = SERVER.newShipGraceTicks.get();

        List<ShipRef> ships = new ArrayList<>();
        Ships.forEachLoaded(level, ships::add);

        Set<Integer> weighed = new HashSet<>();
        ShipEntityImpacts.Budget budget = new ShipEntityImpacts.Budget(SERVER.maxHitChecksPerTick.get());
        ShipVegetationSweep.Budget sweepBudget = new ShipVegetationSweep.Budget(SERVER.sweepMaxBlocksPerTick.get());
        for (ShipRef ship : ships) {
            ShipTracker.State state = tracker.observe(ship, now, grace);
            AABB bounds = ship.worldAabb();
            if (bounds == null || state == null) {
                if (weight) ShipWeightHandler.clear(level, ship);
                continue;
            }

            Pilot pilot = new Pilot(level, ship);
            if (collisions) {
                ShipVegetationSweep.sweep(level, ship, state, bounds, now, sweepBudget, pilot);
            }

            AABB weightBox = weight ? ShipWeightHandler.weightQueryBox(bounds) : null;
            AABB hitBox = collisions ? ShipEntityImpacts.hitBox(ship, state, bounds, now) : null;
            AABB deckBox = deckMomentum ? ShipDeckMomentum.deckBox(state, bounds, now) : null;
            AABB query = union(union(weightBox, hitBox), deckBox);
            if (query == null) continue;

            List<Entity> nearby = level.getEntities((Entity) null, query, e -> e.isAlive() && !e.isSpectator());
            if (weight) {
                ShipWeightHandler.process(level, ship, bounds, nearby, weighed);
            }
            if (deckBox != null && !nearby.isEmpty()) {
                ShipDeckMomentum.process(level, ship, state, deckBox, nearby);
            }
            if (hitBox != null && !nearby.isEmpty()) {
                ShipEntityImpacts.process(level, ship, state, hitBox, nearby, budget, pilot);
            }
        }

        if (collisions) {
            ShipTerrainImpacts.process(level, tracker, now);
        }
        tracker.prune(level, now);
    }

    @Nullable
    private static AABB union(@Nullable AABB a, @Nullable AABB b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.minmax(b);
    }

    /**
     * The player seated on a ship (in a VS seat or helm), looked up only when a hit needs it. The
     * first one found; several seated players share the ship, and it credits one of them.
     */
    static final class Pilot {
        private final ServerLevel level;
        private final ShipRef ship;
        private boolean looked;
        @Nullable
        private Player pilot;

        Pilot(ServerLevel level, ShipRef ship) {
            this.level = level;
            this.ship = ship;
        }

        @Nullable
        Player get() {
            if (!looked) {
                looked = true;
                for (ServerPlayer player : level.players()) {
                    if (!player.isSpectator() && ship.equals(ShipEntities.mountedTo(player))) {
                        pilot = player;
                        break;
                    }
                }
            }
            return pilot;
        }
    }

    /**
     * The loaded ship whose bounds are nearest to {@code player} (within 64 blocks), for the
     * {@code /fullstop ship info} command.
     */
    @Nullable
    public static ShipRef nearest(ServerLevel level, net.minecraft.world.phys.Vec3 at) {
        ShipRef best = null;
        double bestDistance = 64.0 * 64.0;
        for (ShipRef ship : Ships.intersecting(level, new AABB(at, at).inflate(64.0))) {
            AABB bounds = ship.worldAabb();
            if (bounds == null) continue;
            double dx = Math.max(0, Math.max(bounds.minX - at.x, at.x - bounds.maxX));
            double dy = Math.max(0, Math.max(bounds.minY - at.y, at.y - bounds.maxY));
            double dz = Math.max(0, Math.max(bounds.minZ - at.z, at.z - bounds.maxZ));
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = ship;
            }
        }
        return best;
    }
}
