package net.camacraft.fullstop.client.render.debug;

import net.camacraft.fullstop.server.physics.interaction.ProjectileCollisions;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * FullStop's F3+V view for projectiles. FullStop casts no collision rays for a projectile (its hits
 * are its own: arrows stick, tridents return; only {@code fullstop:physics_projectiles} get rays), so
 * the ray view showed nothing on them. What FullStop does judge for a projectile is its flight
 * against the other projectiles (Projectile Collisions), and that is what this draws:
 * <ul>
 *   <li>the path it swept this tick, as thick as its own body (the volume the collision pass tests);</li>
 *   <li>a thinner line ahead of it: where its velocity takes it next tick.</li>
 * </ul>
 * Cyan: an arrow or trident, which survives a mid-air hit and trades momentum. Orange: everything
 * else, which breaks, bursts or explodes on what it meets. Grey: a projectile FullStop leaves alone
 * (stuck in a block, tagged {@code fullstop:no_projectile_collision}, a passenger, another mod's, or
 * projectile collisions switched off).
 */
public final class ProjectileDebug {

    private static final int LINE_LIFE_TICKS = 5;
    private static final float RIGID_R = 0.1f, RIGID_G = 0.9f, RIGID_B = 1.0f;
    private static final float BREAKS_R = 1.0f, BREAKS_G = 0.6f, BREAKS_B = 0.1f;
    private static final float NONE_R = 0.6f, NONE_G = 0.6f, NONE_B = 0.6f;
    private static final float SWEEP_A = 0.75f;
    private static final float AHEAD_A = 0.45f;
    /** A projectile's sweep is drawn no thinner than this (a snowball is an eighth of a block wide). */
    private static final float MIN_THICKNESS = 0.02f;

    private ProjectileDebug() {}

    /** Queues this projectile's lines for the frame. */
    public static void update(Projectile projectile, float partialTick) {
        ProjectileCollisions.Kind kind = ProjectileCollisions.enabled() ? ProjectileCollisions.kind(projectile) : null;
        float r, g, b;
        if (kind == ProjectileCollisions.Kind.RIGID) {
            r = RIGID_R; g = RIGID_G; b = RIGID_B;
        } else if (kind == ProjectileCollisions.Kind.BREAKS) {
            r = BREAKS_R; g = BREAKS_G; b = BREAKS_B;
        } else {
            r = NONE_R; g = NONE_G; b = NONE_B;
        }

        Vec3 now = projectile.getPosition(partialTick);
        // The move the collision pass swept it along: this tick's, as the client saw it.
        Vec3 move = new Vec3(projectile.getX() - projectile.xOld, projectile.getY() - projectile.yOld,
                projectile.getZ() - projectile.zOld);
        String id = "projectile_" + projectile.getId();
        // Half the box width: the renderer draws a quad that wide on each side of the line.
        float thickness = Math.max(MIN_THICKNESS, projectile.getBbWidth() * 0.5f);
        if (move.lengthSqr() > 1.0e-8) {
            RaycastLineRenderer.queueLine(id + "_sweep", now.subtract(move), now, r, g, b, SWEEP_A, LINE_LIFE_TICKS,
                    false, thickness);
        }
        Vec3 velocity = projectile.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0e-8) {
            RaycastLineRenderer.queueLine(id + "_ahead", now, now.add(velocity), r, g, b, AHEAD_A, LINE_LIFE_TICKS,
                    false, RaycastLineRenderer.DEFAULT_THICKNESS);
        }
    }
}
