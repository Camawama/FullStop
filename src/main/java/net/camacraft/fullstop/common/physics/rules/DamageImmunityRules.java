package net.camacraft.fullstop.common.physics.rules;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;

public class DamageImmunityRules {

    public static boolean isDamageImmune(LivingEntity living) {
        if (living instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return true;
        }

        // Bosses
        if (living instanceof WitherBoss) return true;
        if (living instanceof EnderDragon) return true;

        // Flying mobs
        if (living instanceof Ghast) return true;
        if (living instanceof Vex) return true;
        if (living instanceof Allay) return true;

        // Agile mobs (immune to fall damage)
        if (living instanceof Cat) return true;
        if (living instanceof Ocelot) return true;

        // Soft bodies
        if (living instanceof Slime) return true; // Includes MagmaCube

        return false;
    }

    public static boolean unphysable(Entity entity) {
        if (entity == null) return true;

        if (entity instanceof Mob mob && mob.isNoAi()) return true;

        if (entity.noPhysics) return true;
        if (entity instanceof LivingEntity livingEntity)
            if (livingEntity.isDeadOrDying())
                return true;
        return entity.isRemoved();
    }
}
