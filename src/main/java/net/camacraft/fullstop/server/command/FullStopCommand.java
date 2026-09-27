package net.camacraft.fullstop.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.weight.EntityMass;
import net.camacraft.camafy.weight.Weights;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.server.physics.ship.ShipPhysicsServer;
import net.camacraft.fullstop.server.physics.ship.ShipTracker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Locale;

/**
 * {@code /fullstop} (permission level 2):
 * <ul>
 *   <li>{@code weigh <targets>} - what the selected entities weigh (body, carried, and which rule
 *   of Camafy's weight model decided the body), with their riders;</li>
 *   <li>{@code weigh held} - the stack in your main hand;</li>
 *   <li>{@code ship info} - the nearest Valkyrien Skies ship's mass, motion and grace.</li>
 * </ul>
 * WeightRS's old {@code /weightrs weigh <targets>} and {@code /weightrs held} stay as aliases
 * while WeightRS itself isn't installed (with it installed, its own command owns the name).
 */
public final class FullStopCommand {

    private FullStopCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fullstop")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("weigh")
                        .then(Commands.literal("held").executes(FullStopCommand::weighHeld))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(FullStopCommand::weighTargets)))
                .then(Commands.literal("ship")
                        .then(Commands.literal("info").executes(FullStopCommand::shipInfo))));

        if (!ShipCompat.WEIGHTRS_PRESENT) {
            LiteralArgumentBuilder<CommandSourceStack> alias = Commands.literal("weightrs")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("weigh")
                            .then(Commands.argument("targets", EntityArgument.entities())
                                    .executes(FullStopCommand::weighTargets)))
                    .then(Commands.literal("held").executes(FullStopCommand::weighHeld));
            dispatcher.register(alias);
        }
    }

    private static int weighTargets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");
        for (Entity entity : targets) {
            EntityMass mass = Weights.measureNow(entity);
            double kg = mass.totalKg();
            double withRiders = Weights.massWithPassengersKg(entity);
            String line = String.format(Locale.ROOT, "%s: %.2f kg (body %.2f + carried %.2f, basis: %s)%s",
                    entity.getName().getString(), kg, mass.bodyKg(), mass.carriedKg(),
                    mass.basis().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    withRiders > kg ? String.format(Locale.ROOT, " (%.2f kg with riders)", withRiders) : "");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return targets.size();
    }

    private static int weighHeld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ItemStack stack = ctx.getSource().getPlayerOrException().getMainHandItem();
        String line = stack.isEmpty()
                ? "Empty hand weighs nothing. Zen."
                : String.format(Locale.ROOT, "%s: %.3f kg each, %.3f kg for the stack of %d",
                stack.getHoverName().getString(), Weights.itemMassKg(stack), Weights.stackMassKg(stack),
                stack.getCount());
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int shipInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!ShipCompat.active()) {
            source.sendFailure(Component.literal("No ships: Valkyrien Skies isn't installed, or enableValkyrienSkiesCompat is off."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        ShipRef ship = ShipPhysicsServer.nearest(level, source.getPosition());
        if (ship == null) {
            source.sendFailure(Component.literal("No loaded ship within 64 blocks."));
            return 0;
        }
        long now = level.getGameTime();
        ShipTracker.State state = ShipTracker.of(level).get(ship.id(), now);
        Vec3 velocity = ship.velocity();
        Vec3 omega = ship.angularVelocity();
        double mass = ship.mass();
        String line = String.format(Locale.ROOT,
                "Ship %d: %s kg, %.2f m/s, spin %.3f rad/s%s%s | weight %s, collisions %s",
                ship.id(),
                Double.isFinite(mass) ? String.format(Locale.ROOT, "%.0f", mass) : "?",
                velocity != null ? velocity.length() : 0.0,
                omega != null ? omega.length() : 0.0,
                ship.isStatic() ? ", static" : "",
                state == null ? ", not tracked yet" : state.inGrace(now) ? ", grace " + state.graceLeft(now) + " ticks" : "",
                ShipCompat.weightActive() ? "on" : ShipCompat.WEIGHTRS_PRESENT ? "off (WeightRS installed)" : "off",
                ShipCompat.collisionsActive() ? "on" : "off");
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }
}
