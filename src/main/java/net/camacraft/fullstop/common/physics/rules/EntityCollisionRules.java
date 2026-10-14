package net.camacraft.fullstop.common.physics.rules;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;

public class EntityCollisionRules {

    private static final Set<BiPredicate<Entity, Entity>> IGNORE_RULES = new HashSet<>();

    static {
        // Player and FireworkRocketEntity
        addRule((e1, e2) -> (e1 instanceof Player && e2 instanceof FireworkRocketEntity) || (e2 instanceof Player && e1 instanceof FireworkRocketEntity));
        // Two water animals swimming (fish, squid, dolphins): a school packs its fish close and they brush past each
        // other all the time (the owner, 2026-10-13: Full Stop made Nice Catch's boiding fish collide). Vanilla's own
        // push keeps them apart; a fish hitting anything else, or a body hitting a fish, still counts.
        addRule((e1, e2) -> e1 instanceof WaterAnimal && e2 instanceof WaterAnimal && e1.isInWater() && e2.isInWater());
    }

    private static void addRule(BiPredicate<Entity, Entity> rule) {
        IGNORE_RULES.add(rule);
    }

    public static boolean shouldIgnoreCollision(Entity entity1, Entity entity2) {
        for (BiPredicate<Entity, Entity> rule : IGNORE_RULES) {
            if (rule.test(entity1, entity2)) {
                return true;
            }
        }
        return false;
    }
}
