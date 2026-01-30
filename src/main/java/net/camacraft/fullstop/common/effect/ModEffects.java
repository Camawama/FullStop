package net.camacraft.fullstop.common.effect;

import net.camacraft.fullstop.FullStop;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModEffects {
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, FullStop.MODID);

    public static final RegistryObject<MobEffect> SPRAIN =
            MOB_EFFECTS.register("sprain", SprainEffect::new);
}