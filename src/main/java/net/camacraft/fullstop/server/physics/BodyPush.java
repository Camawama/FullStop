package net.camacraft.fullstop.server.physics;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.CamafyImpulses;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;

/**
 * A velocity change FullStop gives a body (the root of a ride stack) from the server - a ship
 * launching it, a deck throwing it, a hull it hit sending it back - delivered to whichever side
 * simulates that body, in the form that side keeps:
 * <ul>
 *   <li>a vehicle a player drives (a boat, a cart, an MKW kart): its driver's client moves it,
 *   so the CHANGE goes to that client ({@code VehicleMotionPacket} in delta mode), which adds it
 *   through Camafy's {@code Impulses} - a kart's own speed fields, a boat's deltaMovement. A write
 *   on the server would be overwritten by the next move packet, and a plain write on the client by
 *   the kart's own motion script;</li>
 *   <li>a player on foot: its client moves it, so the CHANGE goes to that client, which adds it to
 *   the player's motion there (no velocity is built on the server: its reading of a player's
 *   motion comes from the client's move reports, a tick late, and near a moving ship it held the
 *   deck's carry and hull shoves as the server's ship poses saw them, not as the client drew them -
 *   a deck's small throw once sent a standing rider off a rotor at the rotor's speed). A player
 *   without a client (a fake player) gets its (measured) velocity plus the change, sent past
 *   vanilla's 3.9 blocks-a-tick clamp when faster;</li>
 *   <li>everything else is simulated here: the change goes through {@code Impulses} (a mod that
 *   scripts the entity takes it) and reaches watching clients with {@code hurtMarked}.</li>
 * </ul>
 * Another mod owning the body's motion without taking impulses (a MOTION claim, nobody scripting
 * it through Camafy) means the body is left alone.
 */
public final class BodyPush {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private BodyPush() {
    }

    /** Whether a player is simulated by a FullStop client of its own (not a fake player). */
    public static boolean clientSimulated(ServerPlayer player) {
        if (player instanceof FakePlayer) return false;
        FullStopCapability cap = FullStopCapability.grabCapability(player);
        return cap != null && cap.isClientSampled();
    }

    /**
     * Adds {@code deltaBpt} (world axes, blocks per tick) to the body's velocity.
     *
     * @param frame          the body's gravity frame (for the player path)
     * @param playerOwnMps   a server-side player's own velocity as FullStop measured it (m/s):
     *                       its {@code deltaMovement} means nothing on the server
     * @return false when nothing may take it (another mod owns the motion)
     */
    /**
     * A body a ship just struck flies: Valkyrien Skies carrying it on with that ship (for up to 25
     * ticks after it last touched the deck, turning with a rotor) would bend its flight. Where the
     * server simulates the body (a mob, an item, a riderless boat), VS lets go of it now, dropping
     * the ship's motion it would otherwise hand over on top of the hit's; a player's own client
     * does the same for itself.
     */
    public static void releaseStruck(Entity root, long shipId) {
        if (root instanceof Player || root.getControllingPassenger() instanceof Player) return;
        net.camacraft.camafy.compat.vs.ShipEntities.letGo(root, shipId, false);
    }

    public static boolean push(Entity root, Vec3 deltaBpt, GravityFrame frame, Vec3 playerOwnMps) {
        if (!(deltaBpt.lengthSqr() > 0.0) || !Double.isFinite(deltaBpt.lengthSqr())) return false;
        boolean scripted = CamafyImpulses.scripted(root);
        if (!scripted && PhysicsClaims.handsOff(root, PhysicsAspect.MOTION)) return false;

        if (scripted) {
            // The scripting mod takes the change on every side that simulates the body: here (an
            // empty or NPC-driven kart moves; a player-driven kart's server copy ignores it) and on
            // its driver's client. Nothing else is written: its own script sets deltaMovement.
            CamafyImpulses.add(root, deltaBpt);
            if (root.getControllingPassenger() instanceof ServerPlayer driver) {
                PacketHandler.sendVehicleImpulse(driver, root, deltaBpt);
            }
            return true;
        }
        if (root.getControllingPassenger() instanceof ServerPlayer driver) {
            // its driver's client moves it: a server-side write would be overwritten
            PacketHandler.sendVehicleImpulse(driver, root, deltaBpt);
            return true;
        }
        if (root instanceof ServerPlayer player && clientSimulated(player)) {
            PacketHandler.sendPlayerImpulse(player, deltaBpt);
            if (net.camacraft.fullstop.FullStopConfig.SERVER.debugShipContactLog.get()) {
                LOGGER.info("[FullStop] velocity change ({}) m/s sent to {}'s client", String.format(java.util.Locale.ROOT,
                        "%.2f, %.2f, %.2f", deltaBpt.x * 20.0, deltaBpt.y * 20.0, deltaBpt.z * 20.0), player.getName().getString());
            }
            return true;
        }
        if (root instanceof Player) {
            // Only the player's own motion is kept (a ship's carry excluded): a server-side
            // player's deltaMovement is meaningless, so the measured velocity stands in for it -
            // unless something already pushed it this tick.
            Vec3 ownBpt = root.hasImpulse ? Gravity.getWorldVelocity(root, frame) : playerOwnMps.scale(0.05);
            Gravity.setWorldVelocity(root, ownBpt.add(deltaBpt), frame);
            root.hasImpulse = true;
            root.hurtMarked = true;
            PacketHandler.syncLaunchToPlayer(root);
            return true;
        }
        CamafyImpulses.add(root, deltaBpt);
        root.hurtMarked = true;
        return true;
    }
}
