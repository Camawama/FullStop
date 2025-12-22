package net.camacraft.fullstop.client.render;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Simple, reliable debug ray "renderer" using particles.
 * Call spawnRay(...) from client-side only (level.isClientSide()).
 */
public final class RaycastParticleRenderer {

    private RaycastParticleRenderer() {}

    /**
     * Spawn particles along the ray from start -> end.
     *
     * @param level client level (must be client-side)
     * @param start world-space start Vec3
     * @param end   world-space end Vec3
     * @param hit   if true, draw a "hit" marker at the end
     * @param steps how many particles along the line (8-16 is usually fine)
     */
    public static void spawnRay(Level level, Vec3 start, Vec3 end, boolean hit, int steps) {
        if (!level.isClientSide) return; // safety - only client

        Vec3 dir = end.subtract(start);

        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0.0 : (double) i / steps;
            Vec3 p = start.add(dir.scale(t));

            // color and alpha — bright green for visibility
            DustParticleOptions dust = new DustParticleOptions(new Vector3f(0.1f, 1.0f, 0.1f), 1.0f);
            level.addParticle(dust, p.x, p.y, p.z, 0.0, 0.0, 0.0);
        }

        // Hit marker (flame) vs miss marker (cloud)
        if (hit) {
            level.addParticle(ParticleTypes.FLAME, end.x, end.y, end.z, 0.0, 0.0, 0.0);
        } else {
            level.addParticle(ParticleTypes.CLOUD, end.x, end.y, end.z, 0.0, 0.0, 0.0);
        }
    }

    /** Convenience overload: default steps = 8 */
    public static void spawnRay(Level level, Vec3 start, Vec3 end, boolean hit) {
        spawnRay(level, start, end, hit, 8);
    }
}