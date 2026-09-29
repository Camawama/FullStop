package net.camacraft.fullstop.common.compat;

import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * FullStop's way to change the velocity of an entity another mod may script: Camafy 0.1.5's
 * {@code net.camacraft.camafy.physics.Impulses}. An Automobility/MKW kart rewrites its own
 * {@code deltaMovement} every tick from its own speed fields, so a plain write (a ship launching
 * the kart, a deck throwing it) was gone a tick later; through {@code Impulses.add} the mod that
 * scripts it turns the change into its own state.
 *
 * <p>Looked up once, at runtime: FullStop still builds on Camafy 0.1.3, and with an older Camafy
 * (no {@code Impulses}) the change is added to {@code deltaMovement} in world axes, which is what
 * {@code Impulses.add} does itself for an entity nobody scripts. Once FullStop's
 * {@code camafy_version} is 0.1.5 or later this can become a direct call.
 */
public final class CamafyImpulses {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String IMPULSES = "net.camacraft.camafy.physics.Impulses";

    @Nullable
    private static final MethodHandle ADD;
    @Nullable
    private static final MethodHandle SCRIPTED;

    static {
        MethodHandle add = null, scripted = null;
        try {
            Class<?> impulses = Class.forName(IMPULSES);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            add = lookup.findStatic(impulses, "add", MethodType.methodType(boolean.class, Entity.class, Vec3.class));
            scripted = lookup.findStatic(impulses, "scripted", MethodType.methodType(boolean.class, Entity.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.info("[FullStop] Camafy has no Impulses (needs 0.1.5): velocity changes go straight into deltaMovement");
        }
        ADD = add;
        SCRIPTED = scripted;
    }

    private CamafyImpulses() {
    }

    /** Whether Camafy's Impulses is there. */
    public static boolean available() {
        return ADD != null;
    }

    /**
     * Gives {@code entity} a velocity change of {@code worldDeltaBpt} (world axes, blocks per tick)
     * on this side: to the mod scripting it if one does, else into {@code deltaMovement} (world
     * axes, Gravity Unbound frames included) with {@code hasImpulse} set. Call it on the side that
     * simulates the entity.
     *
     * @return true when a scripting mod took it
     */
    public static boolean add(Entity entity, Vec3 worldDeltaBpt) {
        if (worldDeltaBpt.lengthSqr() == 0.0) return false;
        if (ADD != null) {
            try {
                return (boolean) ADD.invokeExact(entity, worldDeltaBpt);
            } catch (Throwable t) {
                LOGGER.warn("[FullStop] Camafy Impulses.add failed; adding to deltaMovement instead", t);
            }
        }
        var frame = FullStopCapability.currentFrame(entity);
        Gravity.setWorldVelocity(entity, Gravity.getWorldVelocity(entity, frame).add(worldDeltaBpt), frame);
        entity.hasImpulse = true;
        return false;
    }

    /** Whether another mod scripts this entity's motion (its velocity changes must go through {@link #add}). */
    public static boolean scripted(Entity entity) {
        if (SCRIPTED == null) return false;
        try {
            return (boolean) SCRIPTED.invokeExact(entity);
        } catch (Throwable t) {
            return false;
        }
    }
}
