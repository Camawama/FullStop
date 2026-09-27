package net.camacraft.fullstop.common.util;

import net.camacraft.camafy.weight.Weights;
import net.minecraft.world.entity.Entity;

public final class EntityStackUtils {
    /**
     * FullStop's mass unit: the mass of one cubic block of player (75 kg over the
     * player's 0.6 × 0.6 × 1.8 hitbox), so a player still weighs 0.648 — the
     * scale every mass threshold and ratio in FullStop was tuned against.
     */
    public static final double KG_PER_UNIT = 75.0 / (0.6 * 0.6 * 1.8);

    /**
     * One FullStop energy unit (1/2 * unit mass * (blocks/tick)^2, what block hardness is weighed
     * against) in joules: about 46 kJ.
     */
    public static final double JOULES_PER_ENERGY_UNIT = KG_PER_UNIT * 20.0 * 20.0;

    private EntityStackUtils() {
    }

    /**
     * The entity's mass in FullStop units, from Camafy's weight model (the same
     * one WeightRS uses; tune it in config/camafy-weight.toml). BODY mass only:
     * what an entity carries doesn't count — with Valkyrien Skies block masses a
     * stack of stone in the inventory can outweigh its carrier a hundred times
     * over. Pose-independent: a crouching, swimming or gliding entity weighs the
     * same as a standing one.
     */
    public static double getEntityMass(Entity entity) {
        return Weights.bodyMassKg(entity) / KG_PER_UNIT;
    }

    /** A mass in kg in FullStop units (a Valkyrien Skies ship's, say). */
    public static double kgToUnits(double kg) {
        return kg / KG_PER_UNIT;
    }
}
