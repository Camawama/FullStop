package net.camacraft.fullstop.common.physics.damage;

import net.camacraft.fullstop.common.capabilities.FullStopCapability;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

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
            public Component getLocalizedDeathMessage(LivingEntity victim) {

                Component velocityComponent = Component.literal(" " + velocityToDisplay)
                        .withStyle(Style.EMPTY.withColor(color));

                boolean hasElytra = FullStopCapability.hasElytraEquipped(victim);
                boolean lookingDown = victim.getXRot() > 45;

                if (isMostlyDownward) {
                    Component base = super.getLocalizedDeathMessage(victim);

                    if (hasElytra && lookingDown) {
                        return Component.literal("")
                                .append(victim.getDisplayName())
                                .append(" hit their head")
                                .append(velocityComponent);
                    }

                    Component flyingComponent = hasElytra
                            ? Component.literal(" with Elytra")
                            : Component.empty();

                    return base.copy().append(flyingComponent).append(velocityComponent);

                } else if (isMostlyUpward) {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" hit their head")
                            .append(velocityComponent);

                } else {
                    if (hasElytra && lookingDown) {
                        return Component.literal("")
                                .append(victim.getDisplayName())
                                .append(" hit their head")
                                .append(velocityComponent);
                    }

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
            public Component getLocalizedDeathMessage(LivingEntity victim) {
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
            public Entity getEntity() {
                return base.getEntity();
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
            public Component getLocalizedDeathMessage(LivingEntity v) {
                Component collidedName = collided.getDisplayName();

                Component velocityComponent = Component.literal(" " + velocityToDisplay)
                        .withStyle(Style.EMPTY.withColor(color));

                if (isMostlyDownward) {
                    return Component.literal("")
                            .append(victim.getDisplayName())
                            .append(" fell onto ")
                            .append(collidedName)
                            .append(" too hard")
                            .append(velocityComponent);
                } else if (isMostlyUpward) {
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
