package net.camacraft.fullstop.server.physics.interaction;

import com.mojang.authlib.GameProfile;
import net.camacraft.camafy.minigame.MatchPolicies;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Who may let FullStop's impacts change the world, asked the way the rest of the pack asks:
 * <ul>
 *   <li>a smash that nobody is steering is mob griefing, so it goes through Forge's
 *   {@code EntityMobGriefingEvent} (per-entity and per-arena rules), not the raw gamerule;</li>
 *   <li>every broken block posts a {@link BlockEvent.BreakEvent} - with the player who caused it,
 *   or the {@code [Full Stop]} fake player - so claim and protection mods can refuse it;</li>
 *   <li>a minigame host's rules ({@link MatchPolicies#mayAlter}: no-break regions, arena resets)
 *   are asked for every change FullStop makes to a block.</li>
 * </ul>
 */
public final class BlockAlterRules {

    /**
     * The fake player that breaks blocks when no real player is responsible (a mob's crash, an
     * unpiloted ship). A fixed profile, so protection mods can allow or deny it by name.
     */
    private static final GameProfile BREAKER_PROFILE =
            new GameProfile(UUID.fromString("5a0f4c1e-8f2b-4d6a-9b3e-46756c6c5374"), "[Full Stop]");

    private BlockAlterRules() {
    }

    /**
     * The player who answers for what {@code cause} does to blocks: the player itself, a
     * projectile's owning player, or the player steering it (a boat, a horse); null when no
     * player is behind it. FullStop treats that as the player acting on the world, exactly like
     * vanilla treats player-lit TNT: mobGriefing=false doesn't veto it.
     */
    @Nullable
    public static Player responsiblePlayer(@Nullable Entity cause) {
        if (cause == null) return null;
        if (cause instanceof Player player) return player;
        if (cause instanceof Projectile projectile && projectile.getOwner() instanceof Player owner) return owner;
        if (cause.getControllingPassenger() instanceof Player driver) return driver;
        return null;
    }

    /**
     * Whether an impact of {@code cause} may grief blocks: always when a player is responsible,
     * else Forge's mob-griefing event for the entity (the gamerule when {@code cause} is null).
     */
    public static boolean mayGrief(ServerLevel level, @Nullable Entity cause) {
        return responsiblePlayer(cause) != null || ForgeEventFactory.getMobGriefingEvent(level, cause);
    }

    /** Whether the minigame host allows any change to the block at {@code pos} by {@code cause}. */
    public static boolean mayAlter(ServerLevel level, BlockPos pos, @Nullable Entity cause) {
        Player player = responsiblePlayer(cause);
        return MatchPolicies.mayAlter(level, pos, player != null ? player : cause);
    }

    /**
     * Whether {@code state} at {@code pos} may be broken: the host allows the change and nobody
     * cancels the {@link BlockEvent.BreakEvent} posted for it (with the responsible player, or the
     * {@code [Full Stop]} fake player standing where {@code cause} is).
     */
    public static boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, @Nullable Entity cause) {
        return mayAlter(level, pos, cause) && breakEventAllows(level, pos, state, cause);
    }

    /**
     * The {@link BlockEvent.BreakEvent} half of {@link #mayBreak}, for callers that already asked
     * {@link #mayAlter} for the position.
     */
    public static boolean breakEventAllows(ServerLevel level, BlockPos pos, BlockState state, @Nullable Entity cause) {
        Player breaker = responsiblePlayer(cause);
        if (breaker == null) {
            breaker = fakeBreaker(level, cause);
        }
        return !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, breaker));
    }

    /** The {@code [Full Stop]} fake player, empty-handed, at {@code cause}'s position when given. */
    public static FakePlayer fakeBreaker(ServerLevel level, @Nullable Entity cause) {
        FakePlayer fake = FakePlayerFactory.get(level, BREAKER_PROFILE);
        if (cause != null) {
            fake.setPos(cause.getX(), cause.getY(), cause.getZ());
        }
        fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return fake;
    }
}
