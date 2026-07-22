package net.camacraft.fullstop.common.registry;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.potion.BetterBrewingRecipe;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModPotions {
    public static final DeferredRegister<Potion> POTIONS =
            DeferredRegister.create(ForgeRegistries.POTIONS, FullStop.MODID);

    // The shared base name ("clarity"/"vertigo") makes all duration/strength variants
    // use one display-name lang key: item.minecraft.potion.effect.clarity etc.

    public static final RegistryObject<Potion> CLARITY_POTION = POTIONS.register("clarity_potion",
            () -> new Potion("clarity", new MobEffectInstance(ModEffects.CLARITY.get(), 3600, 0))); // 3:00

    public static final RegistryObject<Potion> LONG_CLARITY_POTION = POTIONS.register("long_clarity_potion",
            () -> new Potion("clarity", new MobEffectInstance(ModEffects.CLARITY.get(), 9600, 0))); // 8:00

    public static final RegistryObject<Potion> STRONG_CLARITY_POTION = POTIONS.register("strong_clarity_potion",
            () -> new Potion("clarity", new MobEffectInstance(ModEffects.CLARITY.get(), 1800, 1))); // 1:30, Level II

    public static final RegistryObject<Potion> EXTREME_CLARITY_POTION = POTIONS.register("extreme_clarity_potion",
            () -> new Potion("clarity", new MobEffectInstance(ModEffects.CLARITY.get(), 900, 2))); // 0:45, Level III

    public static final RegistryObject<Potion> VERTIGO_POTION = POTIONS.register("vertigo_potion",
            () -> new Potion("vertigo", new MobEffectInstance(ModEffects.VERTIGO.get(), 3600, 0))); // 3:00

    public static final RegistryObject<Potion> LONG_VERTIGO_POTION = POTIONS.register("long_vertigo_potion",
            () -> new Potion("vertigo", new MobEffectInstance(ModEffects.VERTIGO.get(), 9600, 0))); // 8:00

    public static final RegistryObject<Potion> STRONG_VERTIGO_POTION = POTIONS.register("strong_vertigo_potion",
            () -> new Potion("vertigo", new MobEffectInstance(ModEffects.VERTIGO.get(), 1800, 1))); // 1:30, Level II

    public static final RegistryObject<Potion> EXTREME_VERTIGO_POTION = POTIONS.register("extreme_vertigo_potion",
            () -> new Potion("vertigo", new MobEffectInstance(ModEffects.VERTIGO.get(), 900, 2))); // 0:45, Level III

    public static final RegistryObject<Potion> ACCLIMATION_POTION = POTIONS.register("acclimation_potion",
            () -> new Potion("acclimation", new MobEffectInstance(ModEffects.ACCLIMATION.get(), 3600, 0))); // 3:00

    public static final RegistryObject<Potion> LONG_ACCLIMATION_POTION = POTIONS.register("long_acclimation_potion",
            () -> new Potion("acclimation", new MobEffectInstance(ModEffects.ACCLIMATION.get(), 9600, 0))); // 8:00

    public static void register(IEventBus eventBus) {
        POTIONS.register(eventBus);
    }

    public static void registerBrewingRecipes() {
        // Clarity I: Awkward + Echo Shard
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(Potions.AWKWARD, Items.ECHO_SHARD, CLARITY_POTION.get()));

        // Clarity II: Clarity I + Echo Shard
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(CLARITY_POTION.get(), Items.ECHO_SHARD, STRONG_CLARITY_POTION.get()));

        // Clarity III: Clarity II + Echo Shard
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(STRONG_CLARITY_POTION.get(), Items.ECHO_SHARD, EXTREME_CLARITY_POTION.get()));

        // Vertigo I: Clarity I + Fermented Spider Eye
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(CLARITY_POTION.get(), Items.FERMENTED_SPIDER_EYE, VERTIGO_POTION.get()));

        // Vertigo II: Clarity II + Fermented Spider Eye
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(STRONG_CLARITY_POTION.get(), Items.FERMENTED_SPIDER_EYE, STRONG_VERTIGO_POTION.get()));

        // Vertigo III: Clarity III + Fermented Spider Eye
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(EXTREME_CLARITY_POTION.get(), Items.FERMENTED_SPIDER_EYE, EXTREME_VERTIGO_POTION.get()));

        // Long Clarity (Redstone)
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(CLARITY_POTION.get(), Items.REDSTONE, LONG_CLARITY_POTION.get()));

        // Long Vertigo (Redstone)
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(VERTIGO_POTION.get(), Items.REDSTONE, LONG_VERTIGO_POTION.get()));

        // Long Clarity corrupts to Long Vertigo, matching vanilla's long↔long pattern
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(LONG_CLARITY_POTION.get(), Items.FERMENTED_SPIDER_EYE, LONG_VERTIGO_POTION.get()));

        // Acclimation: Awkward + Phantom Membrane (the sky-dweller's drop —
        // Water Breathing's high-altitude sibling)
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(Potions.AWKWARD, Items.PHANTOM_MEMBRANE, ACCLIMATION_POTION.get()));

        // Long Acclimation (Redstone)
        BrewingRecipeRegistry.addRecipe(new BetterBrewingRecipe(ACCLIMATION_POTION.get(), Items.REDSTONE, LONG_ACCLIMATION_POTION.get()));
    }
}
