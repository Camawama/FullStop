package net.camacraft.fullstop.capabilities;

import net.camacraft.fullstop.FullStop;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
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

public class FullStopCapability {

    public static final ResourceLocation DELTA_VELOCITY = new ResourceLocation(MOD_ID, "delta_velocity");
    public static final double BOUNCE_THRESHOLD = 0.6;

    @NotNull
    private Vec3 olderVelocity = Vec3.ZERO;

    @NotNull
    private Vec3 oldVelocity = Vec3.ZERO;

    @NotNull
    private Vec3 currentVelocity = Vec3.ZERO;

    private Vec3 clientVelocity = null;

    private double rotationVelocity = 0.0;
    private double stoppingForce = 0.0;
    private double runningAverageDelta = 0.0;
    private FullStop.HorizontalImpactType impact = FullStop.HorizontalImpactType.NONE;
//    private int bounced = 0;

    public static boolean hasDolphinsGrace(LivingEntity entity) {
        return entity instanceof Player player && player.hasEffect(MobEffects.DOLPHINS_GRACE);
    }

    public static boolean hasDepthStrider(LivingEntity entity) {
        if (entity instanceof Player player) {
            ItemStack boots = player.getInventory().getArmor(3); // Index 3 corresponds to boots

            return !boots.isEmpty() && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.DEPTH_STRIDER, boots) > 0;
        }
        return false;
    }

    public double getStoppingForce() {
      return stoppingForce;
    }

    public double getRunningAverageDelta() {
        return runningAverageDelta;
    }

    public void setCurrentVelocity(Vec3 currentVelocity) {
        this.clientVelocity = currentVelocity.scale(20);
    }

    public void setCurrentRotVelocity(double rotationVelocity) {
        this.rotationVelocity = rotationVelocity;
    }

    public boolean isMostlyDownward() {
        Vec3 v = olderVelocity;
        // Uses the Pythagorean theorem to find the sideways velocity and compares it to the downward velocity
        return (- v.y) > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    /*private static double differenceOfVelocities(double v1, double v2) {
        double ratio = (v1 + 5) / (v2 + 5);
        return ratio < 1.0 ? ratio : 1 / ratio;
    }*/

    private static boolean isBounce(double v1, double v2) {
        if (v1 == 0.0 || v2 == 0.0) {
            return false;
        }

        return Math.signum(v1) != Math.signum(v2);
    }

    public void tick(Entity entity) {
        tickVelocity(entity);
        tickSpeed();
        tickRotation(entity);

        runningAverageDelta = (runningAverageDelta * 19 + stoppingForce) / 20;
    }

//    private void tickBounced() {
//        if (justBounced())
//            bounced += 1;
//    }



    private void tickSpeed() {
        // Stopping force initialized to 0
        double stoppingForceX = calculateStoppingForceComponent(currentVelocity.x, oldVelocity.x);
        double stoppingForceY = calculateStoppingForceComponent(currentVelocity.y, oldVelocity.y);
        double stoppingForceZ = calculateStoppingForceComponent(currentVelocity.z, oldVelocity.z);

        stoppingForce = Math.sqrt(
                stoppingForceX * stoppingForceX +
                stoppingForceY * stoppingForceY +
                stoppingForceZ * stoppingForceZ
        );
    }

    private void tickRotation(Entity entity) {
        double rot = entity.getYRot();

    }

    // Helper method to calculate the stopping force for an individual component
    private double calculateStoppingForceComponent(double current, double old) {
        // If the current component is smaller in magnitude or it changed direction (sign), we calculate stopping force
        if (Math.abs(current) < Math.abs(old) && !isBounce(current, old)) {
            return Math.abs(old - current);  // Return the absolute difference (stopping force)
        } else {
            return 0.0;  // No stopping force if speed increased or stayed the same in this direction
        }
    }

    private void tickVelocity(Entity entity) {
        olderVelocity = oldVelocity;
        oldVelocity = currentVelocity;


        if (clientVelocity != null) {
            currentVelocity = clientVelocity;
        } else {
            currentVelocity = entity.getDeltaMovement().scale(20);
        }
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesEvent(AttachCapabilitiesEvent<Entity> event) {
        if ((event.getObject().getCapability(Provider.DELTAV_CAP).isPresent())) return;

        event.addCapability(DELTA_VELOCITY, new Provider());
    }

    public Vec3 getCurrentVelocity() {
        return currentVelocity;
    }

    public Vec3 getPreviousVelocity() {
        return oldVelocity;
    }

    public FullStop.HorizontalImpactType actualImpact(FullStop.HorizontalImpactType impactType) {
        boolean same = this.impact == impactType;
        this.impact = impactType;

        if (same) {
            return FullStop.HorizontalImpactType.NONE;
        } else {
            return impactType;
        }
    }

//    public void setBounced() {
//        bounced = 0;
//    }
//
//    public boolean justBounced() {
//        return bounced < 0;
//    }

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
