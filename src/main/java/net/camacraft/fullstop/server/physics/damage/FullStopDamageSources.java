package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.physics.rules.EquipmentRules;
import net.camacraft.fullstop.common.util.MathUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * FullStop damage sources. The damage types themselves are datapack-defined
 * (data/fullstop/damage_type/) so tags like bypasses_armor/is_fall and the
 * default death messages are data-driven; the anonymous subclasses below only
 * exist to append the velocity readout to death messages.
 */
public final class FullStopDamageSources {

    public static final ResourceKey<DamageType> KINETIC =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "kinetic"));
    public static final ResourceKey<DamageType> KINETIC_FALL =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "kinetic_fall"));
    public static final ResourceKey<DamageType> PRESSURE =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "pressure"));
    public static final ResourceKey<DamageType> ATMOSPHERE =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "atmosphere"));

    private FullStopDamageSources() {
    }

    /**
     * Base class for FullStop's collision damage. The LivingHurtEvent mitigation
     * layer (Kinetic Dampening, Kinetic Protection, Reflective) keys on this type:
     * these damages are already velocity-derived, so unlike vanilla attacks they
     * must NOT be re-scaled by approach velocity.
     */
    public static abstract class KineticSource extends DamageSource {
        protected KineticSource(Holder<DamageType> type) {
            super(type);
        }

        protected KineticSource(Holder<DamageType> type, Entity attacker) {
            super(type, attacker);
        }
    }

    private static Holder<DamageType> holder(Entity entity, ResourceKey<DamageType> key) {
        return entity.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key);
    }

    public static DamageSource pressure(LivingEntity entity) {
        return new DamageSource(holder(entity, PRESSURE));
    }

    public static DamageSource atmosphere(LivingEntity entity) {
        return new DamageSource(holder(entity, ATMOSPHERE));
    }

    // --- Velocity readout -------------------------------------------------

    /** Speed at which the readout color reaches dark red. */
    private static final double MAX_DISPLAY_VELOCITY = 78.40;

    public record VelocityDisplay(String text, TextColor color) {
    }

    public static VelocityDisplay velocityDisplay(double speedMps) {
        double t = Math.min(speedMps / MAX_DISPLAY_VELOCITY, 1.0);

        int green = 0x00FF00;
        int yellow = 0xFFFF00;
        int red = 0xFF0000;
        int darkRed = 0x800000;

        int rgb;
        if (t < 0.33) {
            rgb = MathUtils.lerpColor(green, yellow, t / 0.33);
        } else if (t < 0.66) {
            rgb = MathUtils.lerpColor(yellow, red, (t - 0.33) / 0.33);
        } else {
            rgb = MathUtils.lerpColor(red, darkRed, (t - 0.66) / 0.34);
        }

        return new VelocityDisplay(String.format("(going %.2f m/s)", speedMps), TextColor.fromRgb(rgb));
    }

    /** Variant for mutual collisions: "(at X m/s)" reads better than "(going X m/s)". */
    public static VelocityDisplay mutualVelocityDisplay(double impactSpeedMps) {
        VelocityDisplay base = velocityDisplay(impactSpeedMps);
        return new VelocityDisplay(String.format("(at %.2f m/s)", impactSpeedMps), base.color());
    }

    /** Empty when the deathMessageAppend config is off. */
    private static Component velocitySuffix(VelocityDisplay display) {
        if (!SERVER.deathMessageAppend.get()) {
            return Component.empty();
        }
        return Component.literal(" " + display.text()).withStyle(Style.EMPTY.withColor(display.color()));
    }

    // --- Self-inflicted kinetic damage (blocks) ----------------------------

    public static DamageSource kineticSelf(Entity entity,
                                           VelocityDisplay velocity,
                                           boolean mostlyDownward,
                                           boolean mostlyUpward,
                                           boolean stalagmite,
                                           boolean waterFlop) {
        Holder<DamageType> type = stalagmite
                ? entity.damageSources().stalagmite().typeHolder()
                : holder(entity, mostlyDownward ? KINETIC_FALL : KINETIC);

        return new KineticSource(type) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity victim) {
                Component suffix = velocitySuffix(velocity);

                if (stalagmite) {
                    return Component.translatable("death.attack.stalagmite", victim.getDisplayName()).append(suffix);
                }

                if (waterFlop) {
                    return Component.translatable("death.fullstop.water_flop", victim.getDisplayName()).append(suffix);
                }

                boolean hasElytra = EquipmentRules.hasElytraEquipped(victim);
                float pitch = victim.getXRot();
                boolean lookingDown = pitch > 45;
                boolean lookingUp = pitch < -45;

                String key;
                if (mostlyDownward) {
                    key = hasElytra
                            ? (lookingDown ? "death.fullstop.ground.elytra_head" : "death.fullstop.ground.elytra")
                            : "death.fullstop.ground";
                } else if (mostlyUpward) {
                    key = hasElytra
                            ? (lookingUp ? "death.fullstop.ceiling.elytra_head" : "death.fullstop.ceiling.elytra")
                            : "death.fullstop.ceiling";
                } else {
                    key = "death.fullstop.wall";
                }

                return Component.translatable(key, victim.getDisplayName()).append(suffix);
            }
        };
    }

    // --- Entity-collision damage -------------------------------------------

    /** The moving entity is treated as the attacker (kill credit goes to it). */
    public static DamageSource entityAttacker(DamageSources sources,
                                              LivingEntity attacker,
                                              VelocityDisplay velocity,
                                              boolean mostlyDownward) {
        DamageSource base = (attacker instanceof Player p)
                ? sources.playerAttack(p)
                : sources.mobAttack(attacker);

        // Passing the attacker through the real constructor (not a getEntity()
        // override) sets the direct entity, so kill credit, knockback attribution
        // and the mitigation event all see the attacker.
        return new KineticSource(base.typeHolder(), attacker) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity victim) {
                Component attackerName = attacker.getDisplayName()
                        .copy()
                        .withStyle(s -> s.withColor(velocity.color()));

                String key = mostlyDownward ? "death.fullstop.entity.crushed_by" : "death.fullstop.entity.hit_by";
                return Component.translatable(key, victim.getDisplayName(), attackerName)
                        .append(velocitySuffix(velocity));
            }
        };
    }

    /** Damage the moving entity does to itself by ramming another entity. */
    public static DamageSource entityCollisionSelf(Entity entity,
                                                   LivingEntity collided,
                                                   VelocityDisplay velocity,
                                                   boolean mostlyDownward,
                                                   boolean mostlyUpward) {
        return new KineticSource(holder(entity, KINETIC)) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity victim) {
                boolean hasElytra = EquipmentRules.hasElytraEquipped(victim);
                float pitch = victim.getXRot();
                boolean lookingDown = pitch > 45;
                boolean lookingUp = pitch < -45;

                String key;
                if (mostlyDownward) {
                    key = (hasElytra && lookingDown) ? "death.fullstop.entity.head" : "death.fullstop.entity.fell_on";
                } else if (mostlyUpward) {
                    key = (hasElytra && !lookingUp) ? "death.fullstop.entity.collided" : "death.fullstop.entity.head";
                } else {
                    key = "death.fullstop.entity.slammed";
                }

                return Component.translatable(key, victim.getDisplayName(), collided.getDisplayName())
                        .append(velocitySuffix(velocity));
            }
        };
    }

    /** Two fast movers hitting each other. */
    public static DamageSource entityMutualCollision(Entity entity,
                                                     LivingEntity collided,
                                                     VelocityDisplay velocity) {
        return new KineticSource(holder(entity, KINETIC)) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity victim) {
                return Component.translatable("death.fullstop.entity.collided",
                                victim.getDisplayName(), collided.getDisplayName())
                        .append(velocitySuffix(velocity));
            }
        };
    }
}
