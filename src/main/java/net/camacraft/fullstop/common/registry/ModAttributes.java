package net.camacraft.fullstop.common.registry;

import net.camacraft.fullstop.FullStop;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(ForgeRegistries.ATTRIBUTES, FullStop.MODID);

    public static final RegistryObject<Attribute> KINETIC_DAMPENING = ATTRIBUTES.register("kinetic_dampening",
            () -> new RangedAttribute("attribute.name.fullstop.kinetic_dampening", 0.0D, 0.0D, 1.0D).setSyncable(true));

    /**
     * How well the entity holds its breath in thin air: the high-altitude air
     * drain is divided by this. 1.0 = baseline; higher values pop bubbles slower
     * (PressureHandler.handleAltitude).
     */
    public static final RegistryObject<Attribute> LUNG_CAPACITY = ATTRIBUTES.register("lung_capacity",
            () -> new RangedAttribute("attribute.name.fullstop.lung_capacity", 1.0D, 0.1D, 10.0D).setSyncable(true));

    public static void register(IEventBus eventBus) {
        ATTRIBUTES.register(eventBus);
        eventBus.addListener(ModAttributes::onModifyEntityAttributes);
    }

    /** Without this the attribute is registered but no entity HAS it — getAttribute() returns null. */
    private static void onModifyEntityAttributes(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            if (!event.has(type, KINETIC_DAMPENING.get())) {
                event.add(type, KINETIC_DAMPENING.get());
            }
            if (!event.has(type, LUNG_CAPACITY.get())) {
                event.add(type, LUNG_CAPACITY.get());
            }
        }
    }
}
