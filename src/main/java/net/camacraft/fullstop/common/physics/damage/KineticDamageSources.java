package net.camacraft.fullstop.common.physics.damage;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Generates damage sources with custom death messages for kinetic damage events.
 * Messages vary based on direction (up/down/horizontal), collision target (block/entity),
 * equipment (Elytra), and player pitch.
 */
public final class KineticDamageSources {
    private KineticDamageSources() {
    }

    @NotNull
    public static DamageSource makeSelfSource(DamageSource baseSource,
                                              String velocityToDisplay,
                                              TextColor color,
                                              boolean isMostlyDownward,
                                              boolean isMostlyUpward) {
        return new DamageSource(baseSource.typeHolder()) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity victim) {

                Component velocityComponent = Component.literal(" " + velocityToDisplay)
                        .withStyle(Style.EMPTY.withColor(color));

                boolean hasElytra = FullStopCapability.hasElytraEquipped(victim);
                float pitch = victim.getXRot();
                boolean lookingDown = pitch > 45;
                boolean lookingUp = pitch < -45;

                if (isMostlyDownward) {
                    if (hasElytra) {
                        if (lookingDown) {
                            return Component.literal("")
                                    .append(victim.getDisplayName())
                                    .append(" hit their head on the ground with Elytra")
                                    .append(velocityComponent);
                        } else {
                            return Component.literal("")
                                    .append(victim.getDisplayName())
                                    .append(" hit the ground too hard with Elytra")
                                    .append(velocityComponent);
                        }
                    } else {
                        return Component.literal("")
                                .append(victim.getDisplayName())
                                .append(" hit the ground too hard")
                                .append(velocityComponent);
                    }

                } else if (isMostlyUpward) {
                    if (hasElytra) {
                        if (lookingUp) {
                            return Component.literal("")
                                    .append(victim.getDisplayName())
                                    .append(" hit their head on the ceiling with Elytra")
                                    .append(velocityComponent);
                        } else {
                            return Component.literal("")
                                    .append(victim.getDisplayName())
                                    .append(" hit the ceiling too hard with Elytra")
                                    .append(velocityComponent);
                        }
                    } else {
                        return Component.literal("")
                                .append(victim.getDisplayName())
                                .append(" hit their head on the ceiling")
                                .append(velocityComponent);
                    }

                } else {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" experienced kinetic energy")
                            .append(velocityComponent);
                }
            }
        };
    }

    @NotNull
    public static DamageSource makeEntityAttackerSource(DamageSources sources,
                                                        LivingEntity attacker,
                                                        String velocityToDisplay,
                                                        TextColor color,
                                                        boolean isMostlyDownward) {
        DamageSource base = (attacker instanceof Player p)
                ? sources.playerAttack(p)
                : sources.mobAttack(attacker);

        return new DamageSource(base.typeHolder()) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity victim) {
                Component attackerName = attacker.getDisplayName()
                        .copy()
                        .withStyle(s -> s.withColor(color));

                Component velocityComponent = Component.literal(" " + velocityToDisplay)
                        .withStyle(Style.EMPTY.withColor(color));

                if (isMostlyDownward) {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" was crushed by ")
                            .append(attackerName)
                            .append(velocityComponent);
                } else {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" was hit by ")
                            .append(attackerName)
                            .append(velocityComponent);
                }
            }

            @Override
            @NotNull
            public Entity getEntity() {
                return attacker;
            }
        };
    }

    @NotNull
    public static DamageSource makeEntityCollisionSelfSource(DamageSource baseSource,
                                                             LivingEntity victim,
                                                             LivingEntity collided,
                                                             String velocityToDisplay,
                                                             TextColor color,
                                                             boolean isMostlyDownward,
                                                             boolean isMostlyUpward) {
        return new DamageSource(baseSource.typeHolder()) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity v) {
                Component collidedName = collided.getDisplayName();

                Component velocityComponent = Component.literal(" " + velocityToDisplay)
                        .withStyle(Style.EMPTY.withColor(color));

                boolean hasElytra = FullStopCapability.hasElytraEquipped(victim);
                float pitch = victim.getXRot();
                boolean lookingDown = pitch > 45;
                boolean lookingUp = pitch < -45;

                if (isMostlyDownward) {
                    if (hasElytra && lookingDown) {
                        return Component.literal("")
                                .append(victim.getDisplayName())
                                .append(" hit their head on ")
                                .append(collidedName)
                                .append(velocityComponent);
                    }
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" fell onto ")
                            .append(collidedName)
                            .append(" too hard")
                            .append(velocityComponent);
                } else if (isMostlyUpward) {
                    if (hasElytra && !lookingUp) {
                        return Component.literal("")
                                .append(victim.getDisplayName())
                                .append(" collided with ")
                                .append(collidedName)
                                .append(velocityComponent);
                    }
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" hit their head on ")
                            .append(collidedName)
                            .append(velocityComponent);
                } else {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" slammed into ")
                            .append(collidedName)
                            .append(velocityComponent);
                }
            }
        };
    }
}
