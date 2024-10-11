package net.camacraft.velocitydamage.capabilities;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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

import static net.camacraft.velocitydamage.VelocityDamage.MOD_ID;

public class FullStopCapability {

    public static final ResourceLocation DELTA_VELOCITY = new ResourceLocation(MOD_ID, "delta_velocity");
    @NotNull
    private Vec3 olderVelocity = Vec3.ZERO;
    @NotNull
    private Vec3 oldVelocity = Vec3.ZERO;
    @NotNull
    private Vec3 currentVelocity = Vec3.ZERO;
    private int lastSeenRiptiding = Integer.MAX_VALUE;
    private double deltaSpeed = 0;
    private double runningAverageDelta = 0;

    public boolean recentlyRiptiding() {
        return lastSeenRiptiding < 10;
    }

    public void seenRiptiding() {
        lastSeenRiptiding = 0;
    }

    public double getDeltaSpeed() {
      return deltaSpeed;
    }

    public double getRunningAverageDelta() {
        return runningAverageDelta;
    }

    public void setCurrentVelocity(Vec3 currentVelocity) {
        this.currentVelocity = currentVelocity.scale(20);
    }

    private static double difference(double x, double y) {
        double ratio = (x + 5) / (y + 5);
        if (ratio < 1.0){
            return ratio;
        } else {
            return 1.0 / ratio;
        }
    }

    public void tick(Entity entity) {
        if (!(entity instanceof Player)) {
            currentVelocity = entity.getDeltaMovement();
        }
        double  cX = Math.abs(currentVelocity.x), oX = Math.abs(oldVelocity.x),
                cY = Math.abs(currentVelocity.y), oY = Math.abs(oldVelocity.y),
                cZ = Math.abs(currentVelocity.z), oZ = Math.abs(oldVelocity.z);
        double rX = difference(cX, oX), rY = difference(cY, oY), rZ = difference(cZ, oZ);
        double threshold = 0.6;
        if (rX > threshold && rY > threshold && rZ > threshold){
            deltaSpeed = 0.0;
        } else {
            deltaSpeed = currentVelocity.subtract(oldVelocity).length();
        }
        runningAverageDelta = (runningAverageDelta * 19 + deltaSpeed) / 20;
        olderVelocity = oldVelocity;
        oldVelocity = currentVelocity;
        if (lastSeenRiptiding < 100) {
            lastSeenRiptiding ++;
        }
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesEvent(AttachCapabilitiesEvent<Entity> event) {
        if ((event.getObject().getCapability(Provider.DELTAV_CAP).isPresent())) return;

        event.addCapability(DELTA_VELOCITY, new Provider());
    }

    public boolean isMostlyDownward() {
        Vec3 v = olderVelocity;
        return (- v.y) > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    public static class Provider implements ICapabilityProvider {
        public static Capability<FullStopCapability> DELTAV_CAP = CapabilityManager.get(new CapabilityToken<>() {});

        public FullStopCapability capability = null;
        private final LazyOptional<FullStopCapability> lazyHandler = LazyOptional.of(this::createCapability);

        public FullStopCapability createCapability() {
            if (this.capability == null) {
                this.capability = new FullStopCapability();
            }
            return this.capability;
        }

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            if (cap == DELTAV_CAP) {
                return lazyHandler.cast();
            }

            return LazyOptional.empty();
        }
    }
}
