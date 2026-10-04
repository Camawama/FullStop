package net.camacraft.fullstop.common.physics;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.BounceMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Landings on slime, where vanilla bounces them ({@code SlimeBlock.updateEntityAfterFallOn}, which FullStop otherwise
 * cancels, see {@code SlimeBlockNoBounceMixin}).
 *
 * <p>A player landing on slime bounces on its own client at the moment it lands ({@code playerSlimeLandingsOnClient}),
 * with FullStop's slime restitution ({@link BounceMath#bounceVelocity}) and relative to the surface: the slime's face as
 * the world sees it (a Valkyrien Skies deck tilts it, a ship rolled over turns its underside up) and the deck's own motion
 * there. A player's client moves the player; the server's bounce ({@code BounceHandler}) sees a landing only through the
 * positions the client sends, a tick or two after it happened, and later still on a ship (Valkyrien Skies relays them
 * relative to the ship). Mobs and every other body stay with the server, which moves them itself.
 *
 * <p>{@link #underFeet} finds the slime a body stands on itself, along the body's own down: vanilla asks the block
 * "on" which a body stands, and Valkyrien Skies answers that by looking down the WORLD's way, so under a Gravity Unbound
 * field pulling a body onto a ship's underside it named the wrong block (and the landing just stopped), and after a hit
 * on a ship's underside it named the slime above the body (a ceiling, not a floor).
 */
public final class PlayerLandingBounce {

    /** How far below the feet, along the body's down, the block it stands on is looked for (blocks). */
    private static final double BELOW_FEET = 0.08;

    private PlayerLandingBounce() {
    }

    /** The slime face a body stands on: its world normal (out of the block) and the surface's own motion there (blocks per tick). */
    public record Surface(Vec3 normal, Vec3 motion) {}

    /** Whether players' slime landings are their own client's (both sides read the same server setting). */
    public static boolean onClient() {
        return FullStopConfig.SERVER_SPEC.isLoaded() && FullStopConfig.SERVER.playerSlimeLandingsOnClient.get();
    }

    /** Whether {@code entity} is the local player whose slime landings its own client bounces. */
    public static boolean ownsLanding(Entity entity) {
        return entity instanceof Player player && player.isLocalPlayer() && entity.level().isClientSide() && onClient();
    }

    /**
     * The slime {@code entity} stands on, looked for just past its feet along its own down (its gravity frame), in the
     * world and in any Valkyrien Skies ship there; null when what it stands on (if anything) isn't slime.
     */
    @Nullable
    public static Surface underFeet(Entity entity) {
        GravityFrame frame = FullStopCapability.currentFrame(entity);
        Level level = entity.level();
        Vec3 up = frame.up();
        Vec3 feet = entity.position();
        // the feet's centre and four points across the footprint, just below it
        Vec3 side = Math.abs(up.y) < 0.9 ? up.cross(new Vec3(0, 1, 0)).normalize() : up.cross(new Vec3(1, 0, 0)).normalize();
        Vec3 side2 = up.cross(side).normalize();
        double r = entity.getBbWidth() * 0.35;
        Vec3[] offsets = {Vec3.ZERO, side.scale(r), side.scale(-r), side2.scale(r), side2.scale(-r)};
        for (Vec3 o : offsets) {
            Vec3 p = feet.add(o).subtract(up.scale(BELOW_FEET));
            BlockPos cell = BlockPos.containing(p);
            if (level.getBlockState(cell).getBlock() instanceof SlimeBlock) {
                Direction face = Direction.getNearest(up.x, up.y, up.z);
                return new Surface(Vec3.atLowerCornerOf(face.getNormal()), Vec3.ZERO);
            }
            if (!ShipCompat.active()) continue;
            for (ShipRef ship : Ships.intersecting(level, new AABB(p, p).inflate(0.05))) {
                Vec3 local = ship.toShip(p);
                if (local == null) continue;
                BlockPos shipCell = BlockPos.containing(local);
                if (!(level.getBlockState(shipCell).getBlock() instanceof SlimeBlock)) continue;
                // the ship block's face that turns toward the body's up (a rolled ship's underside, a tilted deck's top)
                Vec3 normal = up;
                double best = -2.0;
                for (Direction d : Direction.values()) {
                    Vec3 n = ShipCompat.worldFaceNormal(level, shipCell, d);
                    double dot = n.dot(up);
                    if (dot > best) {
                        best = dot;
                        normal = n;
                    }
                }
                Vec3 at = ship.velocityAt(feet);
                return new Surface(normal, at == null ? Vec3.ZERO : at.scale(0.05));
            }
        }
        return null;
    }

    /**
     * The local player's landing on {@code surface}, bounced on its own client. True when bounced; false leaves the
     * landing to the caller (not the local player, the setting off, sneaking, or not moving into the face).
     */
    public static boolean bounce(Entity entity, Surface surface) {
        if (!ownsLanding(entity)) return false;
        if (entity.isSuppressingBounce()) return false; // sneaking: a plain landing, as in vanilla
        GravityFrame frame = FullStopCapability.currentFrame(entity);
        Vec3 world = Gravity.getWorldVelocity(entity, frame);
        Vec3 relative = world.subtract(surface.motion());
        // blocks per tick in, m/s through the shared bounce law, blocks per tick out
        Vec3 out = BounceMath.bounceVelocity(relative.scale(20.0), surface.normal(), Collision.CollisionType.SLIME);
        if (out == null) return false;
        Gravity.setWorldVelocity(entity, surface.motion().add(out.scale(0.05)), frame);
        return true;
    }
}
