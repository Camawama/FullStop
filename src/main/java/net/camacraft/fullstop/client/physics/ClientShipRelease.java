package net.camacraft.fullstop.client.physics;

import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.handler.PacketHandler;
import net.camacraft.fullstop.common.network.ShipReleasePacket;
import net.camacraft.fullstop.common.physics.rules.PhysicsClaims;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.Locale;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Valkyrien Skies lets go of the local player the moment it leaves the deck carrying it
 * ({@code releaseLeavingRiders}).
 *
 * <p>VS carries whatever stood on a ship WITH the ship - moving it by the ship's own motion
 * every tick - for 25 ticks after it last stood on the deck. So a player jumping on a spinning
 * rotor was swung round with the deck through the whole jump, and a player a rotor knocked away
 * flew off on a curve, still turning with it. Here, at the end of each of the player's ticks
 * (after its move), a player VS carries is released - VS stops carrying it (Camafy's
 * {@code ShipEntities.letGo}) and hands it the deck's last motion as velocity - when it jumps off
 * the deck, or when it has been off it for {@link #OFF_DECK_TICKS} ticks (walked off the edge,
 * knocked off). A single tick off the deck is left to VS: a rotor on a Clockwork phys bearing
 * lifts its riders off for a tick now and then as it jitters. From there the player flies on the
 * deck's momentum in a straight line.
 *
 * <p>That momentum is kept against air drag while the player is airborne: vanilla takes 9% of a
 * player's horizontal speed every tick in the air (2% vertically), which is right for its own
 * running and jumping but would leave a jumper behind a ship cruising at 20 m/s by five blocks.
 * The deck's motion the player left with (averaged over its last few ticks on the deck: a
 * bearing's rotor jitters from tick to tick) is put back each tick instead, so a jump on a ship
 * cruising steadily still lands where it took off, as it did while VS carried it; a jump on a
 * rotor flies off along the edge; a ship that stops mid-jump flies on without its rider. The kept
 * momentum ends on landing (VS takes the deck's motion back out of a player landing on a deck),
 * in water, climbing, gliding, or into whatever it runs into.
 *
 * <p>Left alone: seated, spectating and creative-flying players, a player another mod moves
 * (MOTION claims: a grappling hook), and a player Gravity Unbound holds (its plating or core on
 * a ship keeps VS carrying it through jumps itself; Surface Cling; a turned gravity frame). In
 * zero gravity only a jump lets go: a player floating off a deck there stays with the ship.
 */
public final class ClientShipRelease {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla's air friction on a player's horizontal speed (LivingEntity.travel, off the ground). */
    private static final double AIR_FRICTION = 0.91;
    /** And on its vertical speed. */
    private static final double AIR_DRAG_Y = 0.98;
    /** Ticks off the deck (without jumping) before VS is made to let go. */
    private static final int OFF_DECK_TICKS = 3;
    /** Ticks of deck motion averaged for the momentum a leaving player keeps. */
    private static final int DECK_SAMPLES = 4;

    /** The deck's motion at the player's feet over its last ticks on the deck (blocks/tick, world). */
    private static final Vec3[] deck = new Vec3[DECK_SAMPLES];
    private static int deckCount;
    private static int deckHead;
    private static long deckShip = ShipRef.NO_ID;
    /** The player jumped this tick (its jump event). */
    private static boolean jumped;
    private static int offDeck;

    /** The deck's motion the player left with (blocks/tick, world), kept against air drag until it lands. */
    private static Vec3 kept = Vec3.ZERO;
    /** Whether the first airborne tick has confirmed that VS handed that motion over. */
    private static boolean handoffChecked = true;
    /**
     * The ship the player was let go of, until it lands: VS registers a body low over a deck as
     * standing on it again (a jumper's first ticks), and would carry it on - and hand it the deck's
     * motion a second time - so while the player is airborne that ship is refused.
     */
    private static long releasedFrom = ShipRef.NO_ID;

    private ClientShipRelease() {
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity().level().isClientSide && event.getEntity() == Minecraft.getInstance().player) jumped = true;
    }

    /** After the local player's move, every tick. */
    public static void afterMove(LocalPlayer player, FullStopCapability cap) {
        boolean jumpedNow = jumped;
        jumped = false;
        if (!FullStopConfig.SERVER_SPEC.isLoaded() || !ShipCompat.active() || !SERVER.releaseLeavingRiders.get()) {
            forget();
            return;
        }
        if (player.isPassenger() || player.isSpectator() || player.getAbilities().flying
                || PhysicsClaims.handsOff(player, PhysicsAspect.MOTION)
                || !cap.getFrame().isDefault() || Gravity.isAnchored(player) || Gravity.ridesShipField(player)) {
            forget();
            return;
        }
        ShipRef carrying = ShipCompat.carryingShip(player);
        if (releasedFrom != ShipRef.NO_ID) {
            if (player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable()) {
                setReleased(ShipRef.NO_ID);
            } else if (carrying != null && carrying.id() == releasedFrom) {
                // re-registered while still in the air: refused, its motion already went with the player
                ShipEntities.letGo(player, releasedFrom, false);
                carrying = null;
            }
        }
        if (carrying != null) {
            // standing on it: VS registered it on this deck this tick (its own record, which the
            // client can read; a block probe needs the ship's bounds, which only the server has)
            if (player.onGround() && ShipEntities.ticksSinceShip(player) <= 1 && !jumpedNow) {
                kept = Vec3.ZERO;
                offDeck = 0;
                sampleDeck(carrying, player.position());
                return;
            }
            if (!jumpedNow && ++offDeck < OFF_DECK_TICKS) return;
            // In zero gravity nothing brings a player floating over a deck back down to it: VS
            // carrying it with the ship is what keeps it there (and near Gravity Unbound's plating,
            // until the plating's field takes it). Only a jump leaves the deck there.
            if (!jumpedNow && Gravity.isWeightless(player)) return;
            Vec3 momentum = deckMomentum(carrying);
            // VS hands over the deck's last motion only to a player that stood on it: one VS had
            // registered without its ever touching the deck (just teleported onto a new ship)
            // could be handed a motion the deck never had
            if (ShipEntities.letGo(player, carrying.id(), deckCount > 0 && deckShip == carrying.id())) {
                kept = momentum;
                handoffChecked = false;
                setReleased(carrying.id());
                if (SERVER.debugShipContactLog.get()) {
                    LOGGER.info("[FullStop] client: you {} ship {}'s deck; Valkyrien Skies let go, you keep its motion ({}) m/s",
                            jumpedNow ? "jumped off" : "left", carrying.id(), fmt(kept.scale(20.0)));
                }
            }
            resetDeck();
            return;
        }
        resetDeck();
        if (kept.lengthSqr() < 1.0e-12) return;
        if (player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable() || player.isFallFlying()) {
            forget();
            return;
        }
        GravityFrame frame = cap.getFrame();
        if (!handoffChecked) {
            handoffChecked = true;
            // VS hands the deck's last motion over on its next pass; if it didn't (it had none to
            // hand over that tick), the player gets it here
            double along = Gravity.getWorldVelocity(player, frame).dot(kept) / kept.lengthSqr();
            if (along < 0.5) {
                Gravity.setWorldVelocity(player, Gravity.getWorldVelocity(player, frame).add(kept.scale(AIR_FRICTION)), frame);
                if (SERVER.debugShipContactLog.get()) {
                    LOGGER.info("[FullStop] client: Valkyrien Skies handed over none of the deck's motion; given here");
                }
            }
        }
        // what ran into something stops along that way
        if (player.horizontalCollision) kept = new Vec3(0.0, kept.y, 0.0);
        if (player.verticalCollision) kept = new Vec3(kept.x, 0.0, kept.z);
        // put back the drag this tick's move took off the deck's motion
        player.setDeltaMovement(player.getDeltaMovement().add(kept.x * (1.0 - AIR_FRICTION), kept.y * (1.0 - AIR_DRAG_Y),
                kept.z * (1.0 - AIR_FRICTION)));
    }

    private static void sampleDeck(ShipRef ship, Vec3 feet) {
        if (ship.id() != deckShip) resetDeck();
        deckShip = ship.id();
        deckHead = (deckHead + 1) % DECK_SAMPLES;
        deck[deckHead] = ClientShipContacts.hullVelocity(ship, feet).scale(0.05);
        deckCount = Math.min(DECK_SAMPLES, deckCount + 1);
    }

    /** The deck's motion at the feet, averaged over the last ticks on it (blocks/tick). */
    private static Vec3 deckMomentum(ShipRef ship) {
        if (ship.id() != deckShip || deckCount == 0) return Vec3.ZERO;
        Vec3 sum = Vec3.ZERO;
        for (int i = 0; i < deckCount; i++) {
            sum = sum.add(deck[(deckHead - i + DECK_SAMPLES) % DECK_SAMPLES]);
        }
        return sum.scale(1.0 / deckCount);
    }

    private static void resetDeck() {
        deckCount = 0;
        deckShip = ShipRef.NO_ID;
        offDeck = 0;
    }

    /** Ends the kept momentum (landed, or FullStop let go of the player some other way). */
    public static void forget() {
        kept = Vec3.ZERO;
        handoffChecked = true;
        resetDeck();
    }

    /**
     * A hull struck the player and VS let go of it: its new velocity is the hit's, and that ship
     * may not carry it on until it lands (see {@link #releasedFrom}).
     */
    public static void struckBy(long shipId) {
        forget();
        setReleased(shipId);
    }

    /** Sets {@link #releasedFrom}, telling the server when it changes (its deck's jolts don't throw a player in the air). */
    private static void setReleased(long shipId) {
        if (shipId == releasedFrom) return;
        releasedFrom = shipId;
        if (Minecraft.getInstance().getConnection() != null) PacketHandler.sendToServer(new ShipReleasePacket(shipId));
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }
}
