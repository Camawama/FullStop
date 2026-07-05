package net.camacraft.fullstop.common.physics.rules;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

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
        if (entity == null) return true;

        if (entity instanceof ItemEntity) return true;

        // NoAI mobs (maps, farms, displays) are deliberately static; leave them alone.
        if (entity instanceof Mob mob && mob.isNoAi()) return true;

        if (entity.noPhysics) return true;
        if (entity instanceof LivingEntity livingEntity)
            if (livingEntity.isDeadOrDying())
                return true;
        return entity.isRemoved();
    }
}
