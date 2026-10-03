package net.camacraft.fullstop.common.compat.create.kinetic;

import net.camacraft.fullstop.FullStop;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

/**
 * Being chewed by Create's gears (or a fast water wheel's paddles): {@code fullstop:gear} for a cogwheel,
 * {@code fullstop:large_gear} for a large one, {@code fullstop:water_wheel} for the paddles, each with its own death
 * message; drowning held under by a wheel is {@code fullstop:water_wheel_drown}. Defined in the datapack
 * ({@code data/fullstop/damage_type/}). Armour helps; it isn't a fall or an
 * impact (no {@code #minecraft:is_fall}, no {@code #camafy:is_impact}), so Kinetic Protection and
 * impact-only rules leave it alone.
 */
public final class GearDamage {

    public static final ResourceKey<DamageType> GEAR =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "gear"));

    private GearDamage() {
    }

    /** A large cogwheel's bite. */
    public static final ResourceKey<DamageType> LARGE_GEAR =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "large_gear"));
    /** A fast water wheel's paddles out of the water. */
    public static final ResourceKey<DamageType> WATER_WHEEL =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "water_wheel"));
    /**
     * Drowning while a water wheel holds the body under: vanilla's drowning hits re-applied as this type
     * ({@code #minecraft:is_drowning} and {@code bypasses_armor}, as vanilla's drowning).
     */
    public static final ResourceKey<DamageType> WATER_WHEEL_DROWN =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(FullStop.MODID, "water_wheel_drown"));

    static DamageSource source(LivingEntity body) {
        return source(body, GEAR);
    }

    /** The bite of a part of {@code kind}: a cogwheel's, a large cogwheel's, or a water wheel's paddles. */
    static DamageSource source(LivingEntity body, Hazard.Kind kind) {
        return source(body, switch (kind) {
            case COGWHEEL -> GEAR;
            case LARGE_COGWHEEL -> LARGE_GEAR;
            case WATER_WHEEL, LARGE_WATER_WHEEL -> WATER_WHEEL;
        });
    }

    /** Drowning under a water wheel. */
    static DamageSource drowned(LivingEntity body) {
        return source(body, WATER_WHEEL_DROWN);
    }

    private static DamageSource source(LivingEntity body, ResourceKey<DamageType> type) {
        return new DamageSource(body.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type));
    }
}
