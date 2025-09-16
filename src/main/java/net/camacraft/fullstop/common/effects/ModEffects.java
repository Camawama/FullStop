package net.camacraft.fullstop.common.effects;

import net.camacraft.fullstop.FullStop;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModEffects {
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, FullStop.MOD_ID);

    public static final RegistryObject<MobEffect> SPRAIN =
            MOB_EFFECTS.register("sprain", SprainEffect::new);
}