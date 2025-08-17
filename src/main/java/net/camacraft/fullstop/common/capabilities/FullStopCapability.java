package net.camacraft.fullstop.common.capabilities;

import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.Physics;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
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
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.Provider.DELTAV_CAP;

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

    private double targetAngle = Double.NaN;
    private double stoppingForce = 0.0;
    private double runningAverageDelta = 0.0;
    private Collision.CollisionType impact = Collision.CollisionType.NONE;
    private Vec3 currentPosition = Vec3.ZERO;
    private Vec3 previousPosition = Vec3.ZERO;
    private double scalarHorizontalAcceleration = 0;
    private boolean isDamageImmune = false;
    private boolean hasTeleported = false;
    private double teleportCooldown = 0;
    private final Entity entity;

    public FullStopCapability(Entity entity) {
        this.entity = entity;
    }

    public static boolean hasDolphinsGrace(LivingEntity entity) {
        return entity instanceof Player player && player.hasEffect(MobEffects.DOLPHINS_GRACE);
    }

//    public static boolean hasDepthStrider(LivingEntity entity) {
//        if (entity instanceof Player player) {
//            ItemStack boots = player.getInventory().getArmor(3); // Index 3 corresponds to boots
//
//            return !boots.isEmpty() && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.DEPTH_STRIDER, boots) > 0; //OLD DEPTH STRIDER CHECK
//        }
//        return false;
//    }

    public static boolean hasDepthStrider(LivingEntity entity) {
        ItemStack boots = entity.getItemBySlot(EquipmentSlot.FEET); // works for any LivingEntity

        return !boots.isEmpty() &&
                EnchantmentHelper.getItemEnchantmentLevel(Enchantments.DEPTH_STRIDER, boots) > 0;
    }

    public double getStoppingForce() {
      return stoppingForce;
    }

    public boolean getIsDamageImmune() {
        return isDamageImmune;
    }

    public double getRunningAverageDelta() {
        return runningAverageDelta;
    }

    public void setCurrentVelocity(Vec3 currentVelocity) {
        this.clientVelocity = currentVelocity.scale(20);
    }

    public void setTargetAngle(double targetAngle) {
        this.targetAngle = targetAngle;
    }

    public void setHasTeleported(boolean value) {
        this.hasTeleported = value;
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
        tickTeleport();

        if (Double.isNaN(runningAverageDelta))
            runningAverageDelta = 0;
        runningAverageDelta = (runningAverageDelta * 19 + scalarHorizontalAcceleration) / 20;
    }

//    private void tickBounced() {
//        if (justBounced())
//            bounced += 1;
//    }

    private void tickTeleport() {

        if (hasTeleported) {
            teleportCooldown = 20;
            hasTeleported = false;
        }

        if (teleportCooldown > 0) {
            teleportCooldown--;
            isDamageImmune = true;
        } else {
            isDamageImmune = false;
        }
    }

    private void tickSpeed() {
        Vec3 acc_prev = oldVelocity.subtract(olderVelocity);
        Vec3 vel_expe = oldVelocity.add(acc_prev);
        Vec3 vel_real = currentPosition.subtract(previousPosition);
        double ratio = Math.min(1, vel_real.length() / vel_expe.length());
        Vec3 instantVelocity = currentVelocity.add(acc_prev.scale(ratio));

        // Stopping force initialized to 0
        double stoppingForceX = calculateStoppingForceComponent(instantVelocity.x, oldVelocity.x);
        double stoppingForceY = calculateStoppingForceComponent(instantVelocity.y, oldVelocity.y);
        double stoppingForceZ = calculateStoppingForceComponent(instantVelocity.z, oldVelocity.z);

        stoppingForce = Math.sqrt(
                stoppingForceX * stoppingForceX +
                stoppingForceY * stoppingForceY +
                stoppingForceZ * stoppingForceZ
        );
        scalarHorizontalAcceleration = instantVelocity.subtract(oldVelocity).multiply(1, 0, 1).length();
    }

    private void tickRotation(Entity entity) {
        if (entity instanceof Player || entity.isControlledByLocalInstance()) return;
        double rot = entity.getYRot();
        float newYRot = (float) (rotationCorrection(1) + rot);

        entity.setYRot(newYRot);
        entity.setYHeadRot(newYRot);
        entity.setYBodyRot(newYRot);

        entity.yRotO = newYRot;
    }

    public double rotationCorrection(double delta) {
        if (Double.isNaN(targetAngle)) {
            return 0;
        }

        double correction = Physics.angleWrap(targetAngle - entity.getYRot());

        if (Math.abs(correction) < 0.5) {
            targetAngle = Double.NaN;
        }

        return correction * 0.005 * delta;
    }

    // Helper method to calculate the stopping force for an individual component
    private double calculateStoppingForceComponent(double current, double old) {
        // If the current component real smaller in magnitude or it changed direction (sign), we calculate stopping force
        if (Math.abs(current) < Math.abs(old) && !isBounce(current, old)) {
            return Math.abs(old - current);  // Return the absolute difference (stopping force)
        } else {
            return 0.0;  // No stopping force if speed increased or stayed the same in this direction
        }
    }

    private void tickVelocity(Entity entity) {
        olderVelocity = oldVelocity;
        oldVelocity = currentVelocity;

        previousPosition = currentPosition;
        currentPosition = entity.position();

        if (clientVelocity != null) {
            currentVelocity = clientVelocity;
        } else {
            currentVelocity = entity.getDeltaMovement().scale(20);
        }
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesEvent(AttachCapabilitiesEvent<Entity> event) {
        if ((event.getObject().getCapability(Provider.DELTAV_CAP).isPresent())) return;

        event.addCapability(DELTA_VELOCITY, new Provider(event.getObject()));
    }

    public Vec3 getCurrentVelocity() {
        return currentVelocity;
    }

    public Vec3 getPreviousVelocity() {
        return oldVelocity;
    }

//    public Collision.CollisionType actualImpact(Collision.CollisionType impactType) {
//        boolean same = this.impact == impactType;
//        this.impact = impactType;
//
//        if (same) {
//            return Collision.CollisionType.NONE;
//        } else {
//            return impactType;
//        }
//    }

    public static class Provider implements ICapabilityProvider {
        public static Capability<FullStopCapability> DELTAV_CAP = CapabilityManager.get(new CapabilityToken<>() {});
        private final Entity entity;

        public FullStopCapability capability = null;
        private final LazyOptional<FullStopCapability> lazyHandler = LazyOptional.of(this::createCapability);

        public Provider(Entity entity) {
            this.entity = entity;
        }

        public FullStopCapability createCapability() {
            if (this.capability == null) {
                this.capability = new FullStopCapability(entity);
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

    public static FullStopCapability grabCapability(Entity entity) {
        return entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new);
    }
}
