package net.camacraft.fullstop.server.physics.ship;

import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.scale.EntityScale;
import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.minigame.MatchPolicies;
import net.camacraft.camafy.weight.Weights;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * A fist (or a sword) striking a block of a Valkyrien Skies ship gives the ship a small push at the point hit, along
 * the punch ({@code punchShips}; {@code punchShipImpulse} newton-seconds for a 75 kg player's bare fist): a one-block
 * ship drifts off at about half a metre a second, a 3x3 raft at a few centimetres, a big ship not perceptibly. It goes
 * through Camafy's {@code ShipForces} at the point hit, so a light ship also turns a little.
 *
 * <p>Server side, on the start of a hit (a click: holding the button to mine doesn't push again). The push grows with
 * the puncher's mass and size (a 1/16 player punching a 1/16 ship moves it as a full-size one does a full-size ship)
 * and a little with a sword's attack damage (its square root, at most twice a fist's). None while sneaking, with a
 * tool or any other item in hand (mining a ship's blocks doesn't shove it about), in creative (the click breaks the
 * block), on the ship the puncher stands on or rides (nobody propels a raft by punching its own deck), on a static
 * ship, or where the puncher may not alter blocks (adventure mode, a minigame's protection). At most once per
 * {@value #COOLDOWN_TICKS} ticks per player.
 */
public final class ShipPunches {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks between two pushes from one player. */
    static final int COOLDOWN_TICKS = 8;
    /** A body of this mass punches with {@code punchShipImpulse}. */
    private static final double REFERENCE_KG = 75.0;

    private static final Map<UUID, Long> LAST = new HashMap<>();

    private ShipPunches() {
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide() || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        punch(level, player, event.getPos(), event.getFace());
    }

    /** The push of {@code player}'s hit on the ship block at {@code pos} (shipyard), face {@code face}; whether it pushed. */
    public static boolean punch(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face) {
        if (!SERVER.punchShips.get() || !Ships.available() || !ShipForces.available()) return false;
        ShipRef ship = Ships.managing(level, pos);
        if (ship == null) return false;
        if (player.isCreative() || player.isSpectator()) return refused(player, ship, "creative or spectator");
        if (player.isShiftKeyDown()) return refused(player, ship, "sneaking");
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty() && !(held.getItem() instanceof SwordItem)) return refused(player, ship, "holding " + held.getItem());
        if (ship.isStatic()) return refused(player, ship, "a static ship");
        ShipRef underfoot = ShipEntities.standingOn(player, 2);
        ShipRef mounted = ShipEntities.mountedTo(player);
        if (underfoot != null && underfoot.id() == ship.id() || mounted != null && mounted.id() == ship.id()) {
            return refused(player, ship, "on that ship");
        }
        if (player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())) return refused(player, ship, "may not act on blocks");
        long now = level.getGameTime();
        Long last = LAST.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS && now >= last) return refused(player, ship, "cooling down");

        // where the fist lands: the middle of the face struck, in the ship's own blocks
        Direction side = face == null ? Direction.UP : face;
        Vec3 shipPoint = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getNormal()).scale(0.5));
        Vec3 worldPoint = ship.toWorld(shipPoint);
        if (worldPoint == null) return refused(player, ship, "no world point");
        if (!MatchPolicies.mayAlter(level, BlockPos.containing(worldPoint), player)) return refused(player, ship, "a match forbids it");

        double weapon = Mth.clamp(Math.sqrt(Math.max(1.0, player.getAttributeValue(Attributes.ATTACK_DAMAGE))), 1.0, 2.0);
        double body = Math.max(Weights.bodyMassKg(player), 1.0e-6) / REFERENCE_KG;
        // a shrunk player's punch moves a shrunk ship as a full-size one moves a full-size ship: the impulse also
        // shrinks with the length scale (mass alone would leave the ship moving as fast, in world blocks, as full size)
        double size = EntityScale.heightFactor(player);
        double impulse = SERVER.punchShipImpulse.get() * weapon * body * size;
        if (!(impulse > 0.0)) return false;
        Vec3 along = player.getLookAngle();
        if (!ShipForces.applyImpulseAtShipPos(level, ship.id(), along.scale(impulse), shipPoint)) return refused(player, ship, "VS refused the impulse");
        LAST.put(player.getUUID(), now);
        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] punch: {} pushed ship {} by {} N s along ({}, {}, {}) at {} (face {})", player.getName().getString(),
                    ship.id(), String.format(Locale.ROOT, "%.1f", impulse), String.format(Locale.ROOT, "%.2f", along.x),
                    String.format(Locale.ROOT, "%.2f", along.y), String.format(Locale.ROOT, "%.2f", along.z), worldPoint, side);
        }
        return true;
    }

    private static boolean refused(ServerPlayer player, ShipRef ship, String why) {
        if (SERVER.debugShipCollisionLog.get()) {
            LOGGER.info("[FullStop] punch: {} on ship {} pushes nothing: {}", player.getName().getString(), ship.id(), why);
        }
        return false;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }
}
