package net.camacraft.fullstop.capabilities;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static net.camacraft.fullstop.FullStop.MOD_ID;

public class PositionCapability {

    public static final ResourceLocation POSITION_CAPABILITY_ID = new ResourceLocation(MOD_ID, "delta_position");
    @NotNull
    public Vec3 oldPosition = Vec3.ZERO;
    @NotNull
    public Vec3 currentPosition = Vec3.ZERO;

    public void tickPosition(ServerPlayer player) {
        oldPosition = currentPosition;
        currentPosition = player.position();
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesEvent(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof ServerPlayer player)) return;
        if ((player.getCapability(Provider.POSITION_CAP).isPresent())) return;

        event.addCapability(POSITION_CAPABILITY_ID, new Provider());
    }

    public static class Provider implements ICapabilityProvider {
        public static Capability<PositionCapability> POSITION_CAP = CapabilityManager.get(new CapabilityToken<>() {});

        public PositionCapability capability = null;
        private final LazyOptional<PositionCapability> lazyHandler = LazyOptional.of(this::createCapability);

        public PositionCapability createCapability() {
            if (this.capability == null) {
                this.capability = new PositionCapability();
            }
            return this.capability;
        }

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            if (cap == POSITION_CAP) {
                return lazyHandler.cast();
            }

            return LazyOptional.empty();
        }
    }
}
