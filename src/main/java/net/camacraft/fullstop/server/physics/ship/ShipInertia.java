package net.camacraft.fullstop.server.physics.ship;

import net.camacraft.camafy.compat.vs.PhysBody;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.fullstop.FullStop;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How hard a ship is to push at a given point: its EFFECTIVE mass there, which is what a
 * two-body collision at that point really exchanges momentum with. Hit through the centre of
 * mass a ship answers with its whole mass; hit at the tip of a long hull, or on the edge of a
 * small raft, it mostly turns, and answers with much less:
 * <pre>
 *   1 / M_eff = 1 / M + (r x n) . I^-1 (r x n)      r: centre of mass to the point, n: the push
 * </pre>
 * Without this, a heavy rider landing on the edge of a light raft was billed as if the raft were
 * as stiff as its total mass, and the raft got spun many times faster than the rider was moving.
 *
 * <p>VS keeps each ship's inertia tensor on its physics thread only, so a {@link ShipForces}
 * hook reads it there (through Camafy's {@link PhysBody#inertiaAbout}, along six axes, which
 * rebuilds the whole symmetric tensor in ship axes) whenever the ship's mass changes, and at most
 * every {@link #REFRESH_STEPS} physics steps otherwise. The server thread only reads the cache.
 * Until a ship has been seen, a uniform box over its blocks stands in.
 */
public final class ShipInertia {

    private static final ResourceLocation HOOK = new ResourceLocation(FullStop.MODID, "inertia");
    /** Re-read a ship's tensor at least this often (physics steps, 60 a second) even if its mass is unchanged. */
    private static final int REFRESH_STEPS = 120;

    /** Ship id -> {Ixx, Iyy, Izz, Ixy, Iyz, Ixz, mass, steps since read}, ship axes, kg m^2. */
    private static final Map<Long, double[]> TENSORS = new ConcurrentHashMap<>();

    private static boolean registered;

    private ShipInertia() {
    }

    /** Registers the physics hook (common setup; a no-op without Valkyrien Skies). */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ShipForces.registerHook(HOOK, ShipInertia::read);
    }

    private static void read(PhysBody ship, double dt) {
        double mass = ship.mass();
        double[] known = TENSORS.get(ship.id());
        if (known != null && known[6] == mass && known[7]++ < REFRESH_STEPS) return;
        if (!(mass > 0.0) || !Double.isFinite(mass)) return;
        double[] t = new double[8];
        t[0] = about(ship, 1, 0, 0);
        t[1] = about(ship, 0, 1, 0);
        t[2] = about(ship, 0, 0, 1);
        // a^T I a along (x+y)/sqrt2 is (Ixx + Iyy)/2 + Ixy, and so on
        t[3] = about(ship, 1, 1, 0) - 0.5 * (t[0] + t[1]);
        t[4] = about(ship, 0, 1, 1) - 0.5 * (t[1] + t[2]);
        t[5] = about(ship, 1, 0, 1) - 0.5 * (t[0] + t[2]);
        t[6] = mass;
        t[7] = 0;
        for (int i = 0; i < 6; i++) {
            if (!Double.isFinite(t[i])) return;
        }
        TENSORS.put(ship.id(), t);
    }

    /** The moment of inertia about a ship-axes direction (made unit here). */
    private static double about(PhysBody ship, double x, double y, double z) {
        Vector3d axis = new Vector3d(x, y, z).normalize();
        ship.dirToWorld(axis);
        return ship.inertiaAbout(axis);
    }

    /** Forgets a ship (unloaded). */
    static void forget(long shipId) {
        TENSORS.remove(shipId);
    }

    /**
     * The ship's effective mass (kg) for a push along the world direction {@code normal} at the
     * world point {@code at}: at most {@code massKg}, less the farther the push line passes from
     * the centre of mass. {@code massKg} when the ship can't be resolved; +infinity stays
     * +infinity (an unresolved or weightless ship is an immovable wall).
     */
    public static double effectiveMassKg(ShipRef ship, double massKg, Vec3 at, Vec3 normal) {
        if (!(massKg > 0.0) || Double.isInfinite(massKg)) return massKg;
        Vec3 centre = ship.position();
        if (centre == null) return massKg;
        Vec3 rShip = ship.dirToShip(at.subtract(centre));
        Vec3 nShip = ship.dirToShip(normal);
        if (rShip == null || nShip == null) return massKg;
        Matrix3d inertia = tensor(ship, massKg);
        if (inertia == null) return massKg;
        Vector3d c = new Vector3d(rShip.x, rShip.y, rShip.z).cross(nShip.x, nShip.y, nShip.z);
        double det = inertia.determinant();
        if (!(Math.abs(det) > 1.0e-9) || !Double.isFinite(det)) return massKg;
        Vector3d w = inertia.invert(new Matrix3d()).transform(new Vector3d(c));
        double turn = c.dot(w);
        if (!(turn >= 0.0) || !Double.isFinite(turn)) return massKg;
        return 1.0 / (1.0 / massKg + turn);
    }

    /** The ship's inertia tensor in ship axes: VS's own (hook), else a uniform box over its blocks. */
    @Nullable
    private static Matrix3d tensor(ShipRef ship, double massKg) {
        double[] t = TENSORS.get(ship.id());
        if (t != null) {
            return new Matrix3d(t[0], t[3], t[5], t[3], t[1], t[4], t[5], t[4], t[2]);
        }
        BoundingBox blocks = ship.shipyardBlocks();
        if (blocks == null) return null;
        double a = blocks.getXSpan(), b = blocks.getYSpan(), c = blocks.getZSpan();
        double k = massKg / 12.0;
        return new Matrix3d(k * (b * b + c * c), 0, 0, 0, k * (a * a + c * c), 0, 0, 0, k * (a * a + b * b));
    }
}
