package net.camacraft.fullstop.common.physics.rules;

import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

public class DamageImmunityRules {

    public static boolean isDamageImmune(LivingEntity living) {
        if (living instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return true;
        }

        if (living.isSleeping()) {
            return true;
        }

        if (living.isInvulnerable()) {
            return true;
        }

        // Bosses, flying mobs, agile mobs, soft bodies — data-driven so packs can
        // extend it (data/fullstop/tags/entity_types/kinetic_immune.json).
        return living.getType().is(FullStopTags.KINETIC_IMMUNE);
    }

    public static boolean unphysable(Entity entity) {
        return unphysable(entity, null);
    }

    /**
     * Runs for every entity every tick, so it is ordered cheapest-first: plain
     * field/type checks, then the SynchedEntityData-backed ones (health, NoAI).
     * Pass the entity's capability when available so the NoAI flag comes from
     * its once-a-second cache instead of a locked datawatcher read.
     */
    public static boolean unphysable(Entity entity, @Nullable FullStopCapability fullstop) {
        if (unphysableIgnoringAi(entity)) return true;

        // NoAI mobs (maps, farms, displays) are deliberately static; leave them alone.
        if (entity instanceof Mob mob) {
            return fullstop != null ? fullstop.isNoAiCached(mob) : mob.isNoAi();
        }
        return false;
    }

    /**
     * The type/field checks alone, without the NoAI datawatcher read — cheap
     * enough to run before a capability is even resolved, so drop-like entities
     * (items, orbs) never allocate one.
     */
    public static boolean unphysableIgnoringAi(Entity entity) {
        if (entity == null) return true;

        // Inert drop-like entities can never deal or take kinetic damage.
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof AreaEffectCloud) {
            return true;
        }

        if (entity.noPhysics || entity.isRemoved()) return true;

        // Data-driven full opt-out for modded entities that manage their own
        // physics (data/fullstop/tags/entity_types/physics_blacklist.json).
        if (entity.getType().is(FullStopTags.PHYSICS_BLACKLIST)) return true;

        return entity instanceof LivingEntity livingEntity && livingEntity.isDeadOrDying();
    }
}
