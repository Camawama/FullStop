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
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.util.INBTSerializable;

import java.util.Objects;

import static net.camacraft.fullstop.FullStop.MOD_ID;
import static net.camacraft.fullstop.common.capabilities.FullStopCapability.Provider.DELTAV_CAP;

public class FullStopCapability implements INBTSerializable<CompoundTag> {

    public static final ResourceLocation DELTA_VELOCITY = new ResourceLocation(MOD_ID, "delta_velocity");
    public static final double BOUNCE_THRESHOLD = 0.6;

    @NotNull
    private Vec3 olderVelocity = Vec3.ZERO;

    @NotNull
    private Vec3 oldScaledVelocity = Vec3.ZERO;

    @NotNull
    private Vec3 currentScaledVelocity = Vec3.ZERO;

    private Vec3 clientScaledVelocity = null;

    private double targetAngle = Double.NaN;
    private double stoppingForce = 0.0;
    private double runningAverageDelta = 0.0;
    private Collision.CollisionType impact = Collision.CollisionType.NONE;
    private Vec3 currentPosition = Vec3.ZERO;
    private Vec3 previousPosition = Vec3.ZERO;
    private double scalarHorizontalAcceleration = 0;
    private boolean isDamageImmune = false;
    private boolean hasTeleported = false;
    private boolean hasDismounted = false;
    private double teleportCooldown = 0;
    private double dismountCooldown = 0;
    private boolean joinedForFirstTime = false;
    private final Entity entity;
    private Vec3 acceleration;

    public FullStopCapability(Entity entity) {
        this.entity = entity;
    }

    public static boolean hasDolphinsGrace(LivingEntity entity) {
        return entity instanceof Player player && player.hasEffect(MobEffects.DOLPHINS_GRACE);
    }

    public static boolean hasElytraEquipped(LivingEntity entity) {
        ItemStack chestStack = entity.getItemBySlot(EquipmentSlot.CHEST);

        if (chestStack.getItem() instanceof ElytraItem) {
            // Calculate remaining durability
            int remainingDurability = chestStack.getMaxDamage() - chestStack.getDamageValue();
            return remainingDurability > 1; // Returns false if 1 or less
        }

        return false; // Not wearing Elytra
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

    public Vec3 getAcceleration() {
        return acceleration;
    }

    public boolean getIsDamageImmune() {
        return isDamageImmune;
    }

    public double getRunningAverageDelta() {
        return runningAverageDelta;
    }

    public double getTeleportCooldown() {
        return teleportCooldown;
    } //eventually add a config option to modify how long the cooldown lasts

    public double getDismountCooldown() {
        return dismountCooldown;
    }

    public boolean getJoinedForFirstTime() { return joinedForFirstTime; }

    public void setCurrentNativeVelocity(Vec3 currentScaledVelocity) {
        this.clientScaledVelocity = currentScaledVelocity.scale(20);
    }

    public void setTargetAngle(double targetAngle) {
        this.targetAngle = targetAngle;
    }

    public void setHasTeleported(boolean value) {
        this.hasTeleported = value;
    }

    public void justDismounted() {
        setCurrentNativeVelocity(Vec3.ZERO);
        this.hasDismounted = true;
    }

    public void setJoinedForFirstTime(boolean value) { this.joinedForFirstTime = value; }

    public boolean isMostlyDownward() {
        Vec3 v = olderVelocity;
        // Uses the Pythagorean theorem to find the sideways velocity and compares it to the downward velocity
        return (- v.y) > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    public boolean isMostlyUpward() {
        Vec3 v = olderVelocity;
        // Uses the Pythagorean theorem to find the sideways velocity and compares it to the upward velocity
        return v.y > Math.sqrt(v.x * v.x + v.z * v.z);
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
        tickImmunity();
        tickRiding();

        if (Double.isNaN(runningAverageDelta))
            runningAverageDelta = 0;
        runningAverageDelta = (runningAverageDelta * 19 + scalarHorizontalAcceleration) / 20;
    }

//    private void tickBounced() {
//        if (justBounced())
//            bounced += 1;
//    }

    private void tickRiding() {
        if (hasDismounted) {
            dismountCooldown = 20;
            hasDismounted = false;
        }

        if (dismountCooldown > 0) {
            dismountCooldown--;
        }
    }

    private void tickImmunity() {

        if (hasTeleported) {
            teleportCooldown = 20;
            hasTeleported = false;
        }

        if (teleportCooldown > 0) {
            teleportCooldown--;
            isDamageImmune = true;
        } else {
            isDamageImmune = false;
//            if (entity.) {}

        }


//        if (hasTeleported) {
//            isDamageImmune = true;
//        }
//
//        if (isDamageImmune) {
//            isDamageImmune = false;
//        }
    }

    private void tickSpeed() {
        Vec3 acc_prev = oldScaledVelocity.subtract(olderVelocity);
        Vec3 vel_expe = oldScaledVelocity.add(acc_prev);
        Vec3 vel_real = currentPosition.subtract(previousPosition);
        double ratio = Math.min(1, vel_real.length() / vel_expe.length());
        Vec3 instantVelocity = currentScaledVelocity.add(acc_prev.scale(ratio));

        // Stopping force initialized to 0
        double stoppingForceX = calculateStoppingForceComponent(instantVelocity.x, oldScaledVelocity.x);
        double stoppingForceY = calculateStoppingForceComponent(instantVelocity.y, oldScaledVelocity.y);
        double stoppingForceZ = calculateStoppingForceComponent(instantVelocity.z, oldScaledVelocity.z);

        stoppingForce = Math.sqrt(
                stoppingForceX * stoppingForceX +
                stoppingForceY * stoppingForceY +
                stoppingForceZ * stoppingForceZ
        );

        acceleration = new Vec3 (instantVelocity.x - olderVelocity.x, instantVelocity.y - olderVelocity.y, instantVelocity.z - olderVelocity.z);

        scalarHorizontalAcceleration = instantVelocity.subtract(oldScaledVelocity).multiply(1, 0, 1).length();
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
        olderVelocity = oldScaledVelocity;
        oldScaledVelocity = currentScaledVelocity;

        previousPosition = currentPosition;
        currentPosition = entity.position();

        if (clientScaledVelocity != null) {
            currentScaledVelocity = clientScaledVelocity;
        } else {
            currentScaledVelocity = entity.getDeltaMovement().scale(20);
        }

        if (entity instanceof LivingEntity living) {
            double gravity = Objects.requireNonNull(living.getAttribute(ForgeMod.ENTITY_GRAVITY.get())).getValue();

            if (currentScaledVelocity.y >= gravity * -20 && currentScaledVelocity.y < 0) { // TODO REMOVE MULTIPLICATION AFTER UNSCALING THE ENTIRE MOD BY 20 😂
                currentScaledVelocity = new Vec3(currentScaledVelocity.x, 0, currentScaledVelocity.z);
            }
        }
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesEvent(AttachCapabilitiesEvent<Entity> event) {
        if ((event.getObject().getCapability(Provider.DELTAV_CAP).isPresent())) return;

        event.addCapability(DELTA_VELOCITY, new Provider(event.getObject()));
    }

    public Vec3 getCurrentNativeVelocity() {
        return currentScaledVelocity.scale(0.05);
    }

    public Vec3 getPreviousNativeVelocity() {
        return oldScaledVelocity.scale(0.05);
    }

    public Vec3 getCurrentScaledVelocity() {
        return currentScaledVelocity;
    }

    public Vec3 getPreviousScaledVelocity() {
        return oldScaledVelocity;
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();

        // Save only the fields you want to persist
        tag.putBoolean("JoinedForFirstTime", joinedForFirstTime);

        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {

        // Load the fields you saved
        joinedForFirstTime = nbt.getBoolean("JoinedForFirstTime");

    }

    public static class Provider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
        public static Capability<FullStopCapability> DELTAV_CAP = CapabilityManager.get(new CapabilityToken<>() {});
        private final Entity entity;

        private FullStopCapability capability = null;
        private final LazyOptional<FullStopCapability> lazyHandler = LazyOptional.of(this::createCapability);

        public Provider(Entity entity) {
            this.entity = entity;
        }

        private FullStopCapability createCapability() {
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

        // 🔹 Add serialization support
        @Override
        public CompoundTag serializeNBT() {
            return createCapability().serializeNBT();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            createCapability().deserializeNBT(nbt);
        }
    }

//    public static class Provider implements ICapabilityProvider {
//        public static Capability<FullStopCapability> DELTAV_CAP = CapabilityManager.get(new CapabilityToken<>() {});
//        private final Entity entity;
//
//        public FullStopCapability capability = null;
//        private final LazyOptional<FullStopCapability> lazyHandler = LazyOptional.of(this::createCapability); // OLD PROVIDER WITHOUT NBT SERIALIZATION
//
//        public Provider(Entity entity) {
//            this.entity = entity;
//        }
//
//        public FullStopCapability createCapability() {
//            if (this.capability == null) {
//                this.capability = new FullStopCapability(entity);
//            }
//            return this.capability;
//        }
//
//
//
//        @Override
//        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
//            if (cap == DELTAV_CAP) {
//                return lazyHandler.cast();
//            }
//
//            return LazyOptional.empty();
//        }
//    }




//    public static FullStopCapability grabCapability(Entity entity) {
//        return entity.getCapability(DELTAV_CAP).orElseThrow(IllegalStateException::new); //OLD grabCapability
//    }

    public static @Nullable FullStopCapability grabCapability(Entity entity) {
        return entity.getCapability(DELTAV_CAP).orElse(null);
    }
}
