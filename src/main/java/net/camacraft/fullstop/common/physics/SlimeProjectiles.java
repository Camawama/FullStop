package net.camacraft.fullstop.common.physics;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.physics.rules.FullStopTags;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Projectiles and dropped items on slime (the owner, 2026-10-10: "firework rockets don't bounce on slime blocks but they
 * should", arrows skip off slime at a shallow angle and stick when shot straight in, damped "so a player can't infinitely
 * skip an arrow across slime"; "Dropping slime block or slime ball items should be bouncy").
 *
 * <p>Vanilla gives slime's bounce only to what lands on it through {@code Entity.move}, and only straight up: an arrow
 * never moves that way (it ray-casts its own path and sticks into whatever it meets, slime too), and a firework rocket
 * puts its velocity back after its move, so a bounce written there is lost. Here:</p>
 * <ul>
 *   <li>an arrow or trident ({@code #fullstop:bounces_on_slime}) meeting a slime face (in the world or on a Valkyrien
 *   Skies ship, against the face's own motion) at no more than {@code projectileSlimeSkipAngle} above it skips: the
 *   speed into the face comes back at {@code projectileSlimeRestitution}, the speed along it is kept at
 *   {@code projectileSlimeFriction}, at most {@code projectileSlimeMaxSkips} times; shot more steeply, slower than
 *   {@link #MIN_SKIP_MPS}, or past its last skip it sticks as on any block. Both sides decide the same way (an arrow
 *   is simulated on its watchers' clients too, and whether it stuck is never sent);</li>
 *   <li>a thrown snowball, egg, potion or ender pearl, and a fire charge or a ghast's fireball (a dragon's too, and
 *   Amendments' thrown fire and dragon charges: the owner, 2026-10-13, "fireballs are not bouncing on the slime and
 *   instead are exploding on contact"), in the tag bounce off
 *   slime at ANY angle (the owner, 2026-10-13: the shallow angle is for "pointy" things, which stick in slime when
 *   thrown straight in; "Ender pearls, eggs, etc should bounce at any angle", "Fireballs projectiles (fire charge)
 *   should bounce off slime too"), with the same restitution, friction and last skip. Only what is pointy
 *   ({@link #pointy}: an arrow-like, {@code #fullstop:pointy_projectiles}) needs the shallow angle. A bounce is no
 *   hit: nothing shatters, splashes, hatches, teleports, sets fire or explodes until it really lands (too slow, or
 *   past its last skip), where it does what it always does. A fireball's thrust is turned with it, mirrored in the
 *   face, or it would fly straight back in;</li>
 *   <li>a firework rocket in the tag bounces off slime at any angle, with the same restitution and friction (its
 *   block hit is already Full Stop's: it skims instead of exploding on contact). A rocket held by a gliding player
 *   (attached to them) is the player's: it never bounces or sounds itself (the owner heard two bounces when flying
 *   into slime on a rocket);</li>
 *   <li>a dropped item in {@code #fullstop:bouncy_items} (slime balls, slime blocks) bounces off whatever it lands on
 *   or hits, giving back {@code bouncyItemRestitution} of its speed into it, until it is too slow to.</li>
 * </ul>
 * A projectile tagged {@code #fullstop:physics_projectiles} has Full Stop's full physics, slime included, and is left to
 * them.
 */
public final class SlimeProjectiles {

    /** A projectile slower than this into and along the face (m/s) just sticks. */
    public static final double MIN_SKIP_MPS = 3.0;
    /** A dropped item hitting something slower than this (blocks per tick, 1.6 m/s) just lands. */
    private static final double ITEM_MIN_BOUNCE_BPT = 0.08;
    /** Lifted off the face by this much (blocks) after a skip, so the next tick's ray doesn't start inside it. */
    private static final double LIFT = 0.02;

    /** Skips so far, by projectile (both sides keep their own). */
    private static final Map<Entity, Integer> SKIPS = Collections.synchronizedMap(new WeakHashMap<>());
    /** The last bounce a projectile or item made, for the harness and the game tests. */
    private static final Map<Entity, Bounce> LAST = Collections.synchronizedMap(new WeakHashMap<>());

    /** One bounce off slime: the velocity going in and coming out (blocks per tick), the face's normal and the angle above it. */
    public record Bounce(long tick, Vec3 in, Vec3 out, Vec3 normal, double angleDeg, int skips) {
    }

    private SlimeProjectiles() {
    }

    /** The last bounce of {@code entity}, or null. */
    @Nullable
    public static Bounce last(Entity entity) {
        return LAST.get(entity);
    }

    /** How many times {@code entity} has skipped off slime. */
    public static int skips(Entity entity) {
        Integer n = SKIPS.get(entity);
        return n == null ? 0 : n;
    }

    private static boolean enabled() {
        return FullStopConfig.SERVER_SPEC.isLoaded() && FullStopConfig.SERVER.projectilesBounceOnSlime.get();
    }

    /** Whether a block is slime to a projectile: a slime block (a modded one too) or anything in #fullstop:bouncy. */
    public static boolean isSlime(BlockState state) {
        return state.getBlock() instanceof SlimeBlock || state.is(FullStopTags.BOUNCY);
    }

    /**
     * Whether a projectile is pointy (an arrow, a trident: {@link AbstractArrow}, or {@code #fullstop:pointy_projectiles}):
     * such a thing only skips off slime at a shallow angle and sticks in it when it comes in steeply; anything else
     * bounces at any angle.
     */
    public static boolean pointy(Projectile projectile) {
        return projectile instanceof AbstractArrow || projectile.getType().is(FullStopTags.POINTY_PROJECTILES);
    }

    /** Whether Full Stop bounces this projectile off slime here (not one with Full Stop's full physics: those bounce there). */
    public static boolean bounces(Projectile projectile) {
        return enabled() && projectile.getType().is(FullStopTags.BOUNCES_ON_SLIME)
                && !projectile.getType().is(FullStopTags.PHYSICS_PROJECTILES);
    }

    /**
     * An arrow or trident's block hit ({@code AbstractArrow.onHitBlock}, both sides): true when it skipped off slime (the
     * caller then skips the hit: no sticking), with its position and velocity set so this tick's own move carries it on
     * from the face; false when it sticks as usual.
     */
    public static boolean skipArrow(AbstractArrow arrow, BlockHitResult hit) {
        if (arrow.noPhysics || !skip(arrow, hit)) return false;
        arrow.setCritArrow(false);
        return true;
    }

    /**
     * A thrown item's block hit ({@code ThrowableProjectile}: a snowball, an egg, a potion, an ender pearl; its impact
     * event, both sides, as the thrown item is simulated on its watchers' clients too): true when it skipped off slime
     * (the caller cancels the impact, so it neither breaks nor does what it does on landing), with its position and
     * velocity set so this tick's own move carries it on from the face; false when it lands as usual.
     */
    public static boolean skipThrown(ThrowableProjectile thrown, BlockHitResult hit) {
        return skip(thrown, hit);
    }

    /**
     * A fire charge's or a fireball's block hit (its impact event, both sides: it is simulated on its watchers' clients
     * too): true when it bounced off slime (the caller cancels the impact: no fire, no explosion), its velocity and its
     * thrust ({@code xPower}..., which accelerate it every tick) mirrored in the face, so it flies off rather than being
     * pushed straight back in.
     */
    public static boolean skipFireball(AbstractHurtingProjectile fireball, BlockHitResult hit) {
        if (!skip(fireball, hit)) return false;
        Bounce bounce = LAST.get(fireball);
        if (bounce != null) {
            Vec3 n = bounce.normal();
            double into = fireball.xPower * n.x + fireball.yPower * n.y + fireball.zPower * n.z;
            if (into < 0.0) {
                fireball.xPower -= 2.0 * into * n.x;
                fireball.yPower -= 2.0 * into * n.y;
                fireball.zPower -= 2.0 * into * n.z;
            }
        }
        return true;
    }

    /**
     * The skip itself, for anything that moves by its velocity right after its hit is handled (an arrow's and a thrown
     * item's tick both do): true when it skipped.
     */
    private static boolean skip(Projectile projectile, BlockHitResult hit) {
        if (!bounces(projectile)) return false;
        Level level = projectile.level();
        BlockPos pos = hit.getBlockPos();
        if (!isSlime(level.getBlockState(pos))) return false;
        Vec3 v = projectile.getDeltaMovement();
        Face face = face(level, hit);
        Vec3 rel = v.subtract(face.motion);
        double into = -rel.dot(face.normal);
        double speed = rel.length();
        if (!(into > 0.0) || speed * 20.0 < MIN_SKIP_MPS) return false;
        double angle = Math.toDegrees(Math.asin(Mth.clamp(into / speed, 0.0, 1.0)));
        int skips = skips(projectile);
        // only a pointy thing sticks in slime when it comes in steeply; anything else bounces at any angle
        if ((pointy(projectile) && angle > FullStopConfig.SERVER.projectileSlimeSkipAngle.get())
                || skips >= FullStopConfig.SERVER.projectileSlimeMaxSkips.get()) {
            return false;
        }
        Vec3 out = reflect(rel, face.normal, FullStopConfig.SERVER.projectileSlimeRestitution.get(),
                FullStopConfig.SERVER.projectileSlimeFriction.get()).add(face.motion);
        if (out.subtract(face.motion).length() * 20.0 < MIN_SKIP_MPS) return false;

        // This tick's own move adds the velocity to the position after the hit (AbstractArrow.tick, ThrowableProjectile.tick,
        // AbstractHurtingProjectile.tick): placed so that it ends where the rest of the tick takes it from the face, lifted
        // just off it. One that moved to the face before reporting its hit (Moonlight's ImprovedProjectileEntity: Amendments'
        // thrown fire charges) has travelled nothing past it and moves on from the face next tick; setPos, not setPosRaw,
        // so its box (which it sweeps from) goes with it.
        Vec3 from = projectile.position();
        double travelled = v.lengthSqr() > 1.0E-12 ? Mth.clamp(face.at.subtract(from).length() / v.length(), 0.0, 1.0) : 1.0;
        Vec3 end = face.at.add(face.normal.scale(LIFT)).add(out.scale(1.0 - travelled));
        projectile.setPos(end.x - out.x, end.y - out.y, end.z - out.z);
        projectile.setDeltaMovement(out);
        projectile.hasImpulse = true;
        SKIPS.put(projectile, skips + 1);
        LAST.put(projectile, new Bounce(level.getGameTime(), v, out, face.normal, angle, skips + 1));
        squish(level, face.at, into * 20.0);
        return true;
    }

    /**
     * A firework rocket's block hit (its impact event, both sides): true when it bounced off slime, its velocity turned
     * away from the face for its next move.
     */
    public static boolean bounceFirework(Projectile rocket, BlockHitResult hit) {
        if (!bounces(rocket)) return false;
        // held by a gliding player: it goes where they go, and they bounce (and sound) themselves
        if (rocket instanceof FireworkRocketEntity firework
                && ((net.camacraft.fullstop.common.mixin.FireworkRocketAccessor) firework).fullstop$isAttachedToEntity()) {
            return false;
        }
        Level level = rocket.level();
        if (!isSlime(level.getBlockState(hit.getBlockPos()))) return false;
        Vec3 v = rocket.getDeltaMovement();
        Face face = face(level, hit);
        Vec3 rel = v.subtract(face.motion);
        double into = -rel.dot(face.normal);
        if (!(into > 1.0E-4)) return false;
        Vec3 out = reflect(rel, face.normal, Math.max(0.8, FullStopConfig.SERVER.projectileSlimeRestitution.get()),
                FullStopConfig.SERVER.projectileSlimeFriction.get()).add(face.motion);
        rocket.setDeltaMovement(out);
        rocket.hasImpulse = true;
        rocket.hurtMarked = true;
        LAST.put(rocket, new Bounce(level.getGameTime(), v, out, face.normal,
                Math.toDegrees(Math.asin(Mth.clamp(into / Math.max(rel.length(), 1.0E-9), 0.0, 1.0))), 0));
        squish(level, face.at, into * 20.0);
        return true;
    }

    /** Whether a dropped item bounces (#fullstop:bouncy_items, with a restitution over 0). */
    public static boolean bouncyItem(ItemEntity item) {
        return FullStopConfig.SERVER_SPEC.isLoaded() && FullStopConfig.SERVER.bouncyItemRestitution.get() > 0.0
                && item.getItem().is(FullStopTags.BOUNCY_ITEMS);
    }

    /**
     * After a bouncy dropped item's move ({@code Entity.move}, both sides): each axis the move stopped it on (it landed,
     * hit a ceiling or a wall) gives back {@code bouncyItemRestitution} of the speed it had into it, the other way.
     *
     * @param intended the motion the move was asked for (blocks per tick)
     */
    public static void bounceItem(ItemEntity item, Vec3 intended, boolean wasOnGround) {
        double e = FullStopConfig.SERVER.bouncyItemRestitution.get();
        Vec3 now = item.getDeltaMovement();
        double x = now.x, y = now.y, z = now.z;
        boolean bounced = false;
        double hardest = 0.0;
        // (an item lying on the ground moves only every fourth tick, its fall speed gathering in between: that is
        // resting, not a landing)
        boolean landing = !(wasOnGround && intended.y < 0.0);
        if (item.verticalCollision && landing && Math.abs(intended.y) > ITEM_MIN_BOUNCE_BPT && Math.abs(y) < Math.abs(intended.y) * 0.5) {
            y = -intended.y * e;
            hardest = Math.max(hardest, Math.abs(intended.y));
            bounced = true;
        }
        if (item.horizontalCollision) {
            if (Math.abs(intended.x) > ITEM_MIN_BOUNCE_BPT && Math.abs(x) < 1.0E-6) {
                x = -intended.x * e;
                hardest = Math.max(hardest, Math.abs(intended.x));
                bounced = true;
            }
            if (Math.abs(intended.z) > ITEM_MIN_BOUNCE_BPT && Math.abs(z) < 1.0E-6) {
                z = -intended.z * e;
                hardest = Math.max(hardest, Math.abs(intended.z));
                bounced = true;
            }
        }
        if (!bounced) return;
        Vec3 out = new Vec3(x, y, z);
        item.setDeltaMovement(out);
        // off the ground again: the item's own landing bounce (-0.5 when on the ground and falling) and its skipped
        // moves while resting must not catch it
        if (y > 0.0) item.setOnGround(false);
        item.hasImpulse = true;
        LAST.put(item, new Bounce(item.level().getGameTime(), intended, out, Vec3.ZERO, 90.0, 0));
        squish(item.level(), item.position(), hardest * 20.0);
    }

    /** The slime's face the hit met: its world normal, where (world), and how the face itself moves there (blocks per tick). */
    private record Face(Vec3 normal, Vec3 at, Vec3 motion) {
    }

    private static Face face(Level level, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        Vec3 normal = ShipCompat.worldFaceNormal(level, pos, hit.getDirection());
        Vec3 at = hit.getLocation();
        Vec3 motion = Vec3.ZERO;
        if (ShipCompat.active() && ShipCompat.isShipBlock(level, pos)) {
            ShipRef ship = Ships.managing(level, pos);
            if (ship != null) {
                // a hit reported in the shipyard (far off in the world) is taken to the world
                Vec3 world = at.distanceToSqr(Vec3.atCenterOf(pos)) < 4.0 ? ship.toWorld(at) : null;
                if (world != null) at = world;
                Vec3 v = ship.velocityAt(at);
                if (v != null) motion = v.scale(0.05);
            }
        }
        return new Face(normal, at, motion);
    }

    /** {@code v} off a face of normal {@code n}: the part into it turned back at {@code e}, the part along it kept at {@code keep}. */
    private static Vec3 reflect(Vec3 v, Vec3 n, double e, double keep) {
        Vec3 normalPart = n.scale(v.dot(n));
        Vec3 along = v.subtract(normalPart);
        return along.scale(keep).subtract(normalPart.scale(e));
    }

    private static void squish(Level level, Vec3 at, double mps) {
        if (level.isClientSide || mps < 2.0) return;
        float volume = (float) Mth.clamp(mps / 30.0, 0.15, 0.8);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SLIME_BLOCK_FALL, SoundSource.NEUTRAL, volume,
                0.9f + level.random.nextFloat() * 0.3f);
    }
}
