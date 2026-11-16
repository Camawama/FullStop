package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.camacraft.fullstop.common.registry.ModEnchantments;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Rebound (boots, I to III): a hard landing on solid ground springs back up, as slime does, and the energy it gives back
 * is taken off the landing's damage. A landing hard enough to hurt (Full Stop's own landing damage, so only those)
 * bounces at e = {@code reboundBaseRestitution} + {@code reboundRestitutionPerLevel} a level (0.4, 0.5, 0.6) of its
 * speed into the ground, at most {@code reboundMaxSpeed}; the landing's damage is multiplied by one less the energy
 * given back ((bounce over landing speed) squared: 16, 25, 36 percent off at most).
 *
 * <p><b>The landing that ends a bounce</b> is spared the height the bounce gave back: each bounce records where it starts
 * (the place and the body's own up), and the next landing counts only the fall below that start. Back down within about a
 * block of it, the landing costs nothing and doesn't bounce again (no pogo); carried off a ledge and further down, it costs
 * what a fall from the start would (the landing speed scaled to the part of the fall below the start, as Full Stop's damage
 * for that speed over its damage for the real one, so soft blocks still soften it), and bounces from that fall as any
 * landing does.
 *
 * <p>The order on a landing: the follow-up rule above, then Rebound's share off the raw landing (it is the landing's own
 * energy, given back), then the boots and Feather Falling, then Acrobat in the hurt, then Kinetic Protection. A perfect
 * landing (Acrobat) sticks: no damage and no bounce. A roll takes half of what damage is left and the bounce still happens.
 * Sneaking lands normally, as on slime; so does a landing in water, on slime or a bed (their own bounce), a rider, and a
 * body gliding with an elytra.
 *
 * <p>The bounce goes along the body's own up (Gravity Unbound's frame) and keeps most of the speed along the ground. The
 * server gives it ({@link Gravity#setWorldVelocity}, sent to a player's client as any knock), and claims the body's
 * {@link PhysicsAspect#BOUNCE} for the flight under Full Stop's own owner id ({@link PhysicsClaims#SELF}): Full Stop
 * ignores its own claims, while CamaCraft Lookout's movement checks stand down for any claim. A landing's figures are held
 * for a few ticks, so the second tick of a two-tick landing takes the same ones and doesn't bounce again.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber
public final class Rebound {
    /** How long one landing's figures hold (Full Stop's impact top-up window). */
    static final int HOLD_TICKS = 5;
    /** Speed kept along the ground through the bounce. */
    private static final double KEEP_ALONG = 0.8;
    /** Back down within this much of the bounce's start (blocks, along the body's up), its landing costs nothing. */
    static final double SAME_LEVEL = 1.0;
    /**
     * Ticks on the ground after its flight before a bounce's start is let go: a player's ground flag comes from its client,
     * and Full Stop bills that landing from the motion the client reports, a few ticks after the flag.
     */
    static final int GROUNDED_TICKS = 10;
    /** A bounce has flown once the body has been this far above its start (blocks): only then does a landing end it. */
    static final double FLOWN = 0.5;
    /** {@code -Dfullstop.reboundTrace=true}: each landing's figures and each bounce's start to the log. */
    private static final boolean TRACE = Boolean.getBoolean("fullstop.reboundTrace");
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * One landing's figures.
     *
     * @param counted the share of the landing's damage that counts (1, or less for the landing that ends a bounce)
     * @param share   the share Rebound takes off what counts (the energy given back)
     * @param out     the bounce speed (m/s), 0 for none
     */
    public record Landing(long tick, double counted, double share, double out, boolean bounced) {
        static final Landing NONE = new Landing(0L, 1.0, 0.0, 0.0, true);

        Landing bouncedNow() {
            return new Landing(tick, counted, share, out, true);
        }
    }

    /**
     * Where a bounce started: the place, the body's up there, its speed along it (m/s), and until when it counts; dropped
     * once the body has stood on the ground for a few ticks (a soft landing that ended the bounce without hurting).
     */
    private static final class Start {
        final Vec3 at;
        final Vec3 up;
        final double out;
        final long tick;
        final long until;
        int grounded;
        /** The highest it has been above the start, along its up (blocks). */
        double above;

        Start(Vec3 at, Vec3 up, double out, long tick, long until) {
            this.at = at;
            this.up = up;
            this.out = out;
            this.tick = tick;
            this.until = until;
        }
    }

    private static final Map<LivingEntity, Landing> HELD = new WeakHashMap<>();
    private static final Map<LivingEntity, Start> STARTS = new WeakHashMap<>();

    private Rebound() {}

    public static int level(LivingEntity entity) {
        return EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.REBOUND.get(), entity.getItemBySlot(EquipmentSlot.FEET));
    }

    /** The bounce's share of the landing speed for a level. */
    public static double restitution(int level) {
        if (level <= 0) return 0.0;
        return Math.min(0.9, SERVER.reboundBaseRestitution.get() + SERVER.reboundRestitutionPerLevel.get() * level);
    }

    /**
     * A landing at {@code landing} m/s into the ground: {out (m/s), share of the damage the bounce takes}. Pure, for the
     * landing and the gametests.
     */
    public static double[] bounceOf(int level, double landing, double maxSpeed) {
        double e = restitution(level);
        if (!(landing > 0.0) || e <= 0.0) return new double[] {0.0, 0.0};
        double out = Math.min(e * landing, maxSpeed);
        double given = out / landing;
        return new double[] {out, given * given};
    }

    /**
     * The speed that counts for the landing that ends a bounce (m/s): 0 back within {@link #SAME_LEVEL} of its start,
     * otherwise what a fall from the start reaches: the landing speed times the square root of the share of the whole
     * fall (from the bounce's top) that lies below the start. Air drag slows both parts of the fall alike, so it drops out
     * (the bounce's own speed taken off instead left too little: drag had already taken some of it). Pure.
     *
     * @param below  how far below the bounce's start it landed (blocks, along the body's up)
     * @param landed the speed it landed with (m/s)
     * @param above  how high above its start the bounce rose (blocks)
     */
    public static double countedSpeed(double below, double landed, double above) {
        if (below <= SAME_LEVEL) return 0.0;
        return landed * Math.sqrt(below / (below + Math.max(0.0, above)));
    }

    /** Whether a landing of this body on this surface springs at all (boots, no sneaking, solid ground). */
    static boolean springs(LivingEntity entity, Collision collision) {
        if (collision.collisionType != Collision.CollisionType.SOLID) return false;
        if (entity.isShiftKeyDown() || entity.isCrouching()) return false;
        if (entity.isPassenger() || entity.isFallFlying() || entity.isInWaterOrBubble() || entity.isInLava()) return false;
        if (entity instanceof Player player && (player.getAbilities().flying || player.isSpectator())) return false;
        return level(entity) > 0;
    }

    /**
     * This landing's figures: what of its damage counts (the landing that ends a bounce counts only the fall below the
     * bounce's start), the share Rebound takes off that, and the bounce. The first call of a landing works them out and
     * holds them; the landing's top-up tick gets the same.
     */
    public static Landing landing(LivingEntity entity, FullStopCapability fullstop, Collision collision) {
        long now = entity.level().getGameTime();
        Landing held = HELD.get(entity);
        if (held != null && now - held.tick <= HOLD_TICKS) return held;
        Vec3 up = fullstop.getFrame().up();
        double landed = Math.max(0.0, -fullstop.getPreImpactScaledVelocity().dot(up));
        double speed = landed;
        double counted = 1.0;
        Start start = STARTS.get(entity);
        // only a landing after the bounce has flown ends it: a contact read while the body is still leaving the ground (a
        // player's client takes the bounce a tick or two after the server gives it) is not its landing
        if (start != null && now - start.tick > HOLD_TICKS && start.above >= FLOWN) {
            STARTS.remove(entity);
            if (now <= start.until) {
                double below = start.at.subtract(entity.position()).dot(start.up);
                speed = countedSpeed(below, landed, start.above);
                double real = KineticDamageCalculator.damageForSpeedLost(entity, landed, true, List.of());
                double left = speed > 0.0 ? KineticDamageCalculator.damageForSpeedLost(entity, speed, true, List.of()) : 0.0;
                counted = real > 0.0 ? Math.max(0.0, Math.min(1.0, left / real)) : 0.0;
            }
        }
        double share = 0.0;
        double out = 0.0;
        if (counted > 0.0 && springs(entity, collision)) {
            double[] b = bounceOf(level(entity), speed, SERVER.reboundMaxSpeed.get());
            out = b[0];
            share = b[1];
        }
        Landing landing = new Landing(now, counted, share, out, out <= 0.0);
        HELD.put(entity, landing);
        if (TRACE) {
            LOGGER.info("[FullStop] rebound landing {} at {}: landed {} m/s, start {}, counted {}, share {}, out {} m/s",
                    entity.getName().getString(), now, fmt(landed),
                    start == null ? "none" : "from " + start.tick + " above " + fmt(start.above) + " below "
                            + fmt(start.at.subtract(entity.position()).dot(start.up)),
                    fmt(counted), fmt(share), fmt(out));
        }
        return landing;
    }

    /** After the landing's hurt: the bounce, once a landing, unless Acrobat stuck it. */
    public static boolean bounce(LivingEntity entity, FullStopCapability fullstop) {
        long now = entity.level().getGameTime();
        Landing held = HELD.get(entity);
        if (held == null || held.bounced || now - held.tick > HOLD_TICKS || held.out <= 0.0) return false;
        HELD.put(entity, held.bouncedNow());
        if (Acrobat.landedPerfectly(entity)) return false;
        var frame = fullstop.getFrame();
        Vec3 up = frame.up();
        Vec3 pre = fullstop.getPreImpactScaledVelocity();
        Vec3 along = pre.subtract(up.scale(pre.dot(up))).scale(KEEP_ALONG);
        Vec3 out = along.add(up.scale(held.out));
        Gravity.setWorldVelocity(entity, out.scale(0.05), frame);
        entity.hurtMarked = true;
        entity.resetFallDistance();
        // the flight up and back down, a little over: CamaCraft Lookout stands down for any claim
        int ticks = Math.min(100, (int) (1.3 * held.out) + 20);
        PhysicsOwnership.claim(entity, PhysicsClaims.SELF, PhysicsAspect.bits(PhysicsAspect.BOUNCE), ticks);
        // where it started: the landing that ends it counts only the fall below this (a ledge gives it a longer while)
        STARTS.put(entity, new Start(entity.position(), up, held.out, now, now + ticks + 80));
        if (TRACE) {
            LOGGER.info("[FullStop] rebound bounce {} at {}: from y {} at {} m/s", entity.getName().getString(), now,
                    fmt(entity.getY()), fmt(held.out));
        }
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            mob.getBrain().eraseMemory(MemoryModuleType.PATH);
        }
        if (entity.level() instanceof ServerLevel level) {
            double x = entity.getX(), y = entity.getY() + 0.1, z = entity.getZ();
            level.sendParticles(ParticleTypes.POOF, x, y, z, 8, 0.35, 0.05, 0.35, 0.03);
            level.playSound(null, x, y, z, SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 0.8f, 0.8f);
        }
        return true;
    }

    /**
     * A bounce's start lasts until the landing that ends it is billed, or the body has stood on the ground for half a second
     * after its flight (the landing was too soft to bill), or its time is up: a later fall never counts from an old bounce.
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || STARTS.isEmpty()) return;
        STARTS.entrySet().removeIf(e -> {
            LivingEntity body = e.getKey();
            Start start = e.getValue();
            if (body.isRemoved() || body.level().getGameTime() > start.until) return true;
            start.above = Math.max(start.above, body.position().subtract(start.at).dot(start.up));
            // standing on the ground only counts once the bounce has flown (it starts on the ground)
            if (start.above < FLOWN) return false;
            start.grounded = body.onGround() ? start.grounded + 1 : 0;
            if (start.grounded >= GROUNDED_TICKS && TRACE) {
                LOGGER.info("[FullStop] rebound start of {} let go at {} (stood on the ground)", body.getName().getString(),
                        body.level().getGameTime());
            }
            return start.grounded >= GROUNDED_TICKS;
        });
    }

    /** Whether a bounce's start is still held for this body (gametests). */
    public static boolean hasStart(LivingEntity entity) {
        return STARTS.containsKey(entity);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** Gametests only: forget a body's held landing and bounce. */
    public static void forget(LivingEntity entity) {
        HELD.remove(entity);
        STARTS.remove(entity);
    }
}
