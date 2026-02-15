package net.camacraft.fullstop.common.attribute;

import net.camacraft.fullstop.FullStop;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(ForgeRegistries.ATTRIBUTES, FullStop.MODID);

    public static final RegistryObject<Attribute> KINETIC_DAMPENING = ATTRIBUTES.register("kinetic_dampening",
            () -> new RangedAttribute("attribute.name.fullstop.kinetic_dampening", 0.0D, 0.0D, 1.0D).setSyncable(true));

    public static void register(IEventBus eventBus) {
        ATTRIBUTES.register(eventBus);
    }
}
