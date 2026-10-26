package net.camacraft.fullstop.common.compat.irons;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Iron's Spells 'n Spellbooks' slam damage stands down for Full Stop's (the owner, 2026-10-26: Telekinesis "allows you
 * to pick up entities and smack them around which can cause kinetic damage, but it stacks with fullstop. I think fullstop
 * should take over"). Telekinesis and Gust give their target the mod's {@code irons_spellbooks:airborne} effect, whose
 * tick hurts an airborne entity that hits something with vanilla's {@code fly_into_wall} damage (4 plus half a point a
 * level, in 3.16.3) and takes the effect off. Full Stop already charges that body for the speed it loses at the wall,
 * so while {@code ironsSpellbooksSlamDamage} is off that one hit is turned away: a {@code fly_into_wall} hurt on an
 * entity carrying the effect. The effect still goes, its sound still plays, and vanilla's own elytra crash (never on an
 * airborne body) is untouched. Found by registry name: nothing of Iron's is a build dependency.
 */
@Mod.EventBusSubscriber
public final class IronsAirborneSlam {
    public static final boolean LOADED = ModList.get().isLoaded("irons_spellbooks");
    private static final ResourceLocation AIRBORNE = new ResourceLocation("irons_spellbooks", "airborne");
    @Nullable private static MobEffect airborne;
    private static boolean lookedUp;

    private IronsAirborneSlam() {}

    /** Whether {@code entity} carries Iron's Airborne effect (false without the mod). */
    public static boolean isAirborne(LivingEntity entity) {
        if (!LOADED) return false;
        if (!lookedUp) {
            airborne = ForgeRegistries.MOB_EFFECTS.getValue(AIRBORNE);
            lookedUp = true;
        }
        return airborne != null && entity.hasEffect(airborne);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!LOADED || event.getEntity().level().isClientSide) return;
        if (!event.getSource().is(DamageTypes.FLY_INTO_WALL)) return;
        if (SERVER.ironsSpellbooksSlamDamage.get()) return;
        if (isAirborne(event.getEntity())) event.setCanceled(true);
    }
}
