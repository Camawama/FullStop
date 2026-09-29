package net.camacraft.fullstop.client.physics;

import net.camacraft.fullstop.common.compat.CamafyImpulses;
import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side receiver for {@link net.camacraft.fullstop.common.network.VehicleMotionPacket}:
 * applies a server-decided motion change (slime bounce, water skip) to the
 * vehicle this client is actually driving, or a launch faster than vanilla's
 * motion packet can carry to the local player itself. See the packet class for
 * why the vanilla hurtMarked sync can't do this for client-authoritative vehicles.
 */
public final class ClientVehicleMotion {
    private ClientVehicleMotion() {
    }

    public static void apply(int entityId, Vec3 motion) {
        apply(entityId, motion, false);
    }

    /**
     * @param delta {@code motion} is a velocity change (world axes, blocks per tick) to add through
     *              Camafy's Impulses, not the new velocity
     */
    public static void apply(int entityId, Vec3 motion, boolean delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;

        Entity entity = minecraft.level.getEntity(entityId);
        if (entity == null) return;

        // Only what this client controls: the local player (a launch past vanilla's
        // 3.9 blocks/tick packet clamp) or the vehicle it drives. For anything else
        // the regular vanilla motion sync already applies, and a stale/mismatched
        // packet (dismount race) must not shove an unrelated entity.
        if (entity != minecraft.player && entity.getControllingPassenger() != minecraft.player) return;

        if (delta) {
            if (entity == minecraft.player) {
                // A velocity change for the local player itself (a ship's hit, a deck's throw):
                // added to the motion this client is simulating, in world axes.
                GravityFrame frame = FullStopCapability.currentFrame(entity);
                Gravity.setWorldVelocity(entity, Gravity.getWorldVelocity(entity, frame).add(motion), frame);
                entity.hasImpulse = true;
                return;
            }
            // A velocity change for the vehicle this client drives. A mod scripting its motion (an
            // MKW kart) takes it into its own state and does the rest itself; any other vehicle
            // gets it added to its deltaMovement (Camafy's default) and leaves the ground below.
            if (entity.getControllingPassenger() != minecraft.player) return;
            boolean scripted = CamafyImpulses.scripted(entity);
            CamafyImpulses.add(entity, motion);
            if (!scripted) entity.setOnGround(false);
            return;
        }
        entity.setDeltaMovement(motion);
        entity.hasImpulse = true;
        // The vehicle was resting on the surface it is now bouncing off; leaving
        // onGround set would let its ground handling eat the fresh vertical motion.
        entity.setOnGround(false);
    }
}
