package net.camacraft.fullstop.client.physics;

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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;

        Entity entity = minecraft.level.getEntity(entityId);
        if (entity == null) return;

        // Only what this client controls: the local player (a launch past vanilla's
        // 3.9 blocks/tick packet clamp) or the vehicle it drives. For anything else
        // the regular vanilla motion sync already applies, and a stale/mismatched
        // packet (dismount race) must not shove an unrelated entity.
        if (entity != minecraft.player && entity.getControllingPassenger() != minecraft.player) return;

        entity.setDeltaMovement(motion);
        entity.hasImpulse = true;
        // The vehicle was resting on the surface it is now bouncing off; leaving
        // onGround set would let its ground handling eat the fresh vertical motion.
        entity.setOnGround(false);
    }
}
