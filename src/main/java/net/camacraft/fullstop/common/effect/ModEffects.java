package net.camacraft.fullstop.common.effect;

import net.camacraft.fullstop.FullStop;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEffects {
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, FullStop.MODID);

    public static final RegistryObject<MobEffect> SPRAIN =
            MOB_EFFECTS.register("sprain", SprainEffect::new);

    public static final RegistryObject<MobEffect> CLARITY =
            MOB_EFFECTS.register("clarity", ClarityEffect::new);

    public static final RegistryObject<MobEffect> VERTIGO =
            MOB_EFFECTS.register("vertigo", VertigoEffect::new);

    public static void register(IEventBus eventBus) {
        MOB_EFFECTS.register(eventBus);
    }
}
