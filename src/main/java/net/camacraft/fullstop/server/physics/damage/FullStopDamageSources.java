package net.camacraft.fullstop.server.physics.damage;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.util.color.Argb;
import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.physics.rules.EquipmentRules;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    public static final ResourceKey<DamageType> SHIP =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "ship"));
    /** A moving Create contraption striking what it runs into (compat/create). */
    public static final ResourceKey<DamageType> CONTRAPTION =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "contraption"));

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

        /**
         * @param direct  the body that physically struck (the boat, the hook, the ship's pilot seat)
         * @param causing who answers for it (see {@link #responsible}); kill credit and the host's
         *                PvP/team rules see this one
         */
        protected KineticSource(Holder<DamageType> type, @Nullable Entity direct, @Nullable Entity causing) {
            super(type, direct, causing);
        }
    }

    /**
     * Who answers for a hit {@code mover} delivers: a projectile's owner, else whoever steers the
     * mover (the player driving the boat, riding the horse), else the mover itself. This is the
     * damage source's causing entity: vanilla's PvP and team checks, kill credit, mob anger and
     * minigame hosts' rules (grace, safe regions, friendly fire) all look at it, so ramming
     * someone with a boat is the driver's attack, not an ownerless accident.
     */
    public static Entity responsible(Entity mover) {
        if (mover instanceof Projectile projectile && projectile.getOwner() != null) {
            return projectile.getOwner();
        }
        Entity controller = mover.getControllingPassenger();
        return controller != null ? controller : mover;
    }

    /** What a message calls a body: a block in flight by its block ("a falling Anvil"), anything else by its name. */
    public static Component bodyName(Entity body) {
        if (body instanceof FallingBlockEntity falling) {
            return Component.translatable("death.fullstop.falling", falling.getBlockState().getBlock().getName());
        }
        return body.getDisplayName();
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
            rgb = Argb.lerpRgb((float) (t / 0.33), green, yellow);
        } else if (t < 0.66) {
            rgb = Argb.lerpRgb((float) ((t - 0.33) / 0.33), yellow, red);
        } else {
            rgb = Argb.lerpRgb((float) ((t - 0.66) / 0.34), red, darkRed);
        }

        // lerpRgb returns an opaque 0xFFRRGGBB; keep the text colour plain RGB as
        // it always was (TextColor would carry the alpha byte into its serialized form).
        return new VelocityDisplay(String.format("(going %.2f m/s)", speedMps), TextColor.fromRgb(rgb & 0xFFFFFF));
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

    /**
     * @param fall whether the hit is a fall (downward, with something pulling): only a fall is
     *             {@code kinetic_fall} (#minecraft:is_fall). A feet-first bump in zero gravity is
     *             plain kinetic damage, though its message still says the ground.
     */
    public static DamageSource kineticSelf(Entity entity,
                                           VelocityDisplay velocity,
                                           boolean mostlyDownward,
                                           boolean mostlyUpward,
                                           boolean fall,
                                           boolean stalagmite,
                                           boolean waterFlop) {
        return kineticSelf(entity, velocity, mostlyDownward, mostlyUpward, fall,
                stalagmite ? net.minecraft.world.damagesource.DamageTypes.STALAGMITE : null, waterFlop);
    }

    /**
     * As above, with the damage type of a pointed block met at its point ({@code PointedBlocks}: the stalagmite for
     * dripstone, Supplementaries' spikes' own), or null for Full Stop's kinetic types. A type the registry hasn't got
     * (its mod is missing) falls back to the kinetic one.
     */
    public static DamageSource kineticSelf(Entity entity,
                                           VelocityDisplay velocity,
                                           boolean mostlyDownward,
                                           boolean mostlyUpward,
                                           boolean fall,
                                           @Nullable ResourceKey<DamageType> pointType,
                                           boolean waterFlop) {
        Holder<DamageType> point = pointType == null ? null
                : entity.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(pointType).orElse(null);
        Holder<DamageType> type = point != null ? point : holder(entity, fall ? KINETIC_FALL : KINETIC);

        return new KineticSource(type) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity victim) {
                Component suffix = velocitySuffix(velocity);

                if (point != null) {
                    return Component.translatable("death.attack." + point.value().msgId(), victim.getDisplayName()).append(suffix);
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

    // --- Ship hits ------------------------------------------------------------

    /**
     * A Valkyrien Skies ship's hull hitting an entity. Carries the side the hull came from, so
     * Kinetic Protection covers the right piece of armor (the helmet for a hull coming down, the
     * boots for one rising from below).
     */
    public abstract static class ShipSource extends KineticSource {
        private final boolean hullAbove;
        private final boolean hullBelow;

        protected ShipSource(Holder<DamageType> type, @Nullable Entity pilot, boolean hullAbove, boolean hullBelow) {
            // No direct entity (a ship isn't one); the pilot is who answers for it.
            super(type, null, pilot);
            this.hullAbove = hullAbove;
            this.hullBelow = hullBelow;
        }

        public boolean hullAbove() {
            return hullAbove;
        }

        public boolean hullBelow() {
            return hullBelow;
        }
    }

    /**
     * Damage a ship's hull deals to what it hits ({@code fullstop:ship}). The pilot, when there
     * is one and creditPilot is on, is the attacker: kill credit, and the server's PvP and team
     * rules apply as for any attack.
     *
     * @param crushed the victim was pinned between the hull and something that didn't give
     */
    public static DamageSource ship(Entity victim, @Nullable Entity pilot, VelocityDisplay velocity, boolean crushed,
                                    boolean hullAbove, boolean hullBelow) {
        return ship(victim, pilot, velocity, crushed, hullAbove, hullBelow, ShipRef.NO_ID);
    }

    /**
     * As above, for the ship {@code shipId}: the death message names the ship when someone renamed it
     * ("was hit by Large Wooden Door", see {@link #shipName}) and says "a ship" while it has the name
     * VS made up for it.
     */
    public static DamageSource ship(Entity victim, @Nullable Entity pilot, VelocityDisplay velocity, boolean crushed,
                                    boolean hullAbove, boolean hullBelow, long shipId) {
        return new ShipSource(holder(victim, SHIP), pilot, hullAbove, hullBelow) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity dead) {
                String key = crushed ? "death.fullstop.ship.crushed" : "death.fullstop.ship.hit";
                Component suffix = velocitySuffix(velocity);
                Component name = shipName(dead, shipId, velocity);
                if (name != null) key += ".named";
                if (pilot != null && pilot != dead) {
                    return (name != null
                            ? Component.translatable(key + ".player", dead.getDisplayName(), name, pilot.getDisplayName())
                            : Component.translatable(key + ".player", dead.getDisplayName(), pilot.getDisplayName()))
                            .append(suffix);
                }
                return (name != null ? Component.translatable(key, dead.getDisplayName(), name)
                        : Component.translatable(key, dead.getDisplayName())).append(suffix);
            }
        };
    }

    /**
     * What a death message calls the ship {@code shipId}: the name someone gave it ({@code /vs rename},
     * Camafy's {@code ShipRef.customName}), in the speed's colour; null while it still has the three
     * random nouns VS made up for it ({@code delivery-doe-leg}: then it is "a ship"), or is gone.
     */
    @Nullable
    private static Component shipName(Entity dead, long shipId, VelocityDisplay velocity) {
        if (shipId == ShipRef.NO_ID) return null;
        ShipRef ship = ShipRef.ofAny(dead.level(), shipId);
        String name = ship == null ? null : ship.customName();
        if (name == null) return null;
        return Component.literal(name).withStyle(s -> s.withColor(velocity.color()));
    }

    /**
     * Damage a moving Create contraption deals to what it strikes ({@code fullstop:contraption}): a bearing's arm,
     * a piston's ram, a train. A {@link ShipSource}, so Kinetic Protection covers the right piece of armour. The
     * driver (a train's conductor), when there is one, answers for it.
     *
     * @param crushed the victim was pinned between the contraption and something that didn't give
     */
    public static DamageSource contraption(Entity victim, @Nullable Entity driver, VelocityDisplay velocity, boolean crushed,
                                           boolean above, boolean below) {
        return contraption(victim, driver, velocity, null, crushed, false, above, below);
    }

    /**
     * As above, for a contraption of a known {@code kind} ({@code spinning}, {@code windmill}, {@code clock},
     * {@code piston}, {@code gantry}, {@code pulley}, {@code elevator}, {@code minecart}, {@code train}; null for one
     * FullStop can't tell): its own damage type ({@code fullstop:contraption_<kind>}) and death message
     * ({@code death.fullstop.contraption.<kind>.hit}, {@code .crushed} when pinned against terrain,
     * {@code .crushed_ship} against a ship; {@code .player} when a driver answers for it).
     */
    public static DamageSource contraption(Entity victim, @Nullable Entity driver, VelocityDisplay velocity, @Nullable String kind,
                                           boolean crushed, boolean crushedAgainstShip, boolean above, boolean below) {
        ResourceKey<DamageType> type = kind == null ? CONTRAPTION
                : ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "contraption_" + kind));
        Holder<DamageType> holder = victim.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(type)
                .map(h -> (Holder<DamageType>) h).orElseGet(() -> holder(victim, CONTRAPTION));
        String base = "death.fullstop.contraption" + (kind == null ? "" : "." + kind);
        String key = base + (crushed ? (crushedAgainstShip ? ".crushed_ship" : ".crushed") : ".hit");
        return new ShipSource(holder, driver, above, below) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity dead) {
                Component suffix = velocitySuffix(velocity);
                if (driver != null && driver != dead) {
                    return Component.translatable(key + ".player", dead.getDisplayName(), driver.getDisplayName()).append(suffix);
                }
                return Component.translatable(key, dead.getDisplayName()).append(suffix);
            }
        };
    }

    /**
     * A rider held in a ship's seat through its ship's crash ({@code fullstop:kinetic}): the seat stopped
     * with the ship, and the rider with it.
     */
    public static DamageSource seatCrash(Entity victim, VelocityDisplay velocity) {
        return seatCrash(victim, velocity, ShipRef.NO_ID);
    }

    /** As above, naming the ship when someone renamed it ("didn't survive the crash of Large Wooden Door"). */
    public static DamageSource seatCrash(Entity victim, VelocityDisplay velocity, long shipId) {
        return new KineticSource(holder(victim, KINETIC)) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity dead) {
                Component name = shipName(dead, shipId, velocity);
                return (name != null ? Component.translatable("death.fullstop.ship.seat.named", dead.getDisplayName(), name)
                        : Component.translatable("death.fullstop.ship.seat", dead.getDisplayName())).append(velocitySuffix(velocity));
            }
        };
    }

    // --- Entity-collision damage -------------------------------------------

    /**
     * Two bodies colliding (Entity Collisions, {@code BodyCollisions}): {@code fullstop:kinetic}, an
     * impact like any other (#camafy:is_impact; FullStop applies the armour). When {@code by} struck
     * the victim, whoever answers for it ({@link #responsible}: its rider, or itself) is the attacker,
     * so PvP, team and minigame rules and kill credit apply; null when the victim ran into
     * {@code other} itself (then, as for a wall, nobody is).
     *
     * @param key which {@code death.fullstop.entity.*} message: hit_by, crushed_by, collided,
     *            slammed, fell_on or head
     */
    public static DamageSource bodyCollision(Entity victim, Entity other, @Nullable Entity by, VelocityDisplay velocity,
                                             String key) {
        Entity answering = by != null ? responsible(by) : null;
        Entity causing = answering == victim ? null : answering;
        return new KineticSource(holder(victim, KINETIC), by, causing) {
            @Override
            @NotNull
            public Component getLocalizedDeathMessage(@NotNull LivingEntity dead) {
                Component otherName = bodyName(other).copy().withStyle(s -> s.withColor(velocity.color()));
                String message = "death.fullstop.entity." + key;
                boolean attack = key.equals("hit_by") || key.equals("crushed_by");
                if (attack && causing != null && causing != other && causing != dead) {
                    // "was hit by Steve's Horse"
                    return Component.translatable(message + ".owned", dead.getDisplayName(), otherName,
                            causing.getDisplayName()).append(velocitySuffix(velocity));
                }
                return Component.translatable(message, dead.getDisplayName(), otherName).append(velocitySuffix(velocity));
            }
        };
    }
}
