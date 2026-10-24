package net.camacraft.fullstop.common.physics.rules;

import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * FullStop's side of Camafy's physics-ownership contract ({@link PhysicsOwnership}): another mod
 * that scripts an entity's motion (MKW Mobility's karts and their drivers, a grapple swing, a
 * meteor's blast) claims the {@link PhysicsAspect aspects} it takes over, and FullStop leaves
 * exactly those alone - no crash damage for a claimed IMPACT, no vignette for a claimed G_FORCE,
 * no smashed blocks for a claimed BLOCKS, and so on.
 *
 * <p>Three ways to ask, by where the caller runs:
 * <ul>
 *   <li>the physics dispatch reads the entity's own claims from its capability, refreshed once
 *   per tick ({@link FullStopCapability#refreshClaims});</li>
 *   <li>hot or possibly off-thread paths (block-shape mixins, water travel) read that same
 *   {@link #cached cached} value and never call out to other mods;</li>
 *   <li>server/client-thread code acting on SOME OTHER entity (a passenger, a ramming target, a
 *   mount's vehicle) asks {@link #handsOff live}, since that entity's cache may be a tick old.</li>
 * </ul>
 */
public final class PhysicsClaims {

    /**
     * FullStop's owner id in the contract. FullStop claims nothing itself today; its queries
     * exclude this id so it could never end up yielding to itself.
     */
    public static final ResourceLocation SELF = new ResourceLocation(FullStop.MODID, "physics");

    private PhysicsClaims() {
    }

    /**
     * Live: the aspects other mods own of this entity right now. Game threads only (the claim
     * functions of other mods are not required to be thread-safe).
     */
    public static int live(Entity entity) {
        return PhysicsOwnership.claimsExcept(entity, SELF);
    }

    /**
     * The claims the physics dispatch cached on the entity's capability this tick; 0 for an
     * entity FullStop doesn't simulate. Safe from any thread.
     */
    public static int cached(Entity entity) {
        FullStopCapability cap = FullStopCapability.grabCapability(entity);
        return cap != null ? cap.getClaims() : 0;
    }

    /** {@link #cached} for one aspect. */
    public static boolean cachedClaimed(Entity entity, PhysicsAspect aspect) {
        return aspect.in(cached(entity));
    }

    /**
     * {@link #handsOff} from the cache: blacklisted, or claimed at the last physics tick. For
     * per-entity hooks that run every tick (travel, living tick) and must not call out.
     */
    public static boolean cachedHandsOff(Entity entity, PhysicsAspect aspect) {
        return FullStopTags.physicsBlacklisted(entity.getType()) || cachedClaimed(entity, aspect);
    }

    /**
     * Whether FullStop must keep its hands off {@code aspect} of {@code entity}: the entity is in
     * {@code fullstop:physics_blacklist} (FullStop ignores it completely), or another mod claims
     * the aspect right now. Game threads only.
     */
    public static boolean handsOff(Entity entity, PhysicsAspect aspect) {
        return FullStopTags.physicsBlacklisted(entity.getType()) || aspect.in(live(entity));
    }
}
