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

    /**
     * An entity tag (a scoreboard tag, as {@code /tag} gives) that makes a mob without AI a body all the same (2.0.6).
     * Vanilla never moves a NoAI mob, so FullStop leaves NoAI mobs out (a map's statues, a farm's held mobs); a mod that
     * moves such a mob itself gives it this tag, and FullStop simulates it as any mob: its landings and side impacts hurt
     * it, vanilla's fall damage gives way to FullStop's, and it takes part in body collisions when it is pushable. Camafy
     * NPCs tags its NPCs that can be hurt or pushed (it walks, drops and knocks them back with vanilla's collisions
     * though they have no AI); before, such an NPC fell with vanilla's fall damage and a body flung into it passed through.
     */
    public static final String BODY_WITHOUT_AI_TAG = "fullstop_body";

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

        // NoAI mobs (maps, farms, displays) are deliberately static; leave them alone,
        // unless a mod that moves them itself says they are bodies (BODY_WITHOUT_AI_TAG)
        if (entity instanceof Mob mob) {
            return fullstop != null ? fullstop.isNoAiCached(mob) : staticWithoutAi(mob);
        }
        return false;
    }

    /**
     * Whether {@code mob} is one FullStop leaves alone for having no AI: NoAI, and without {@link #BODY_WITHOUT_AI_TAG}.
     * Read once a second per mob through {@link FullStopCapability#isNoAiCached} (a tag set or taken off counts within a second).
     */
    public static boolean staticWithoutAi(Mob mob) {
        return mob.isNoAi() && !mob.getTags().contains(BODY_WITHOUT_AI_TAG);
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
        if (FullStopTags.physicsBlacklisted(entity.getType())) return true;

        return entity instanceof LivingEntity livingEntity && deadOrDying(livingEntity);
    }

    /**
     * {@link LivingEntity#isDeadOrDying()}, which reads the health through the synched data's lock, read once a second
     * per body through its capability ({@code idleFastPaths}); a body whose death has begun reads dead at once.
     */
    public static boolean deadOrDying(LivingEntity living) {
        if (living.deathTime > 0) return true;
        if (net.camacraft.fullstop.common.physics.PerfSwitches.idleFastPaths()) {
            FullStopCapability cap = FullStopCapability.grabCapability(living);
            if (cap != null) return cap.isDeadCached(living);
        }
        return living.isDeadOrDying();
    }
}
