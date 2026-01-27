package net.camacraft.fullstop.common.capabilities;

import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.Physics;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static net.camacraft.fullstop.FullStop.MOD_ID;

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
    private long lastTick = -1;
    private int soundCooldown = 0;
    private long lastCollisionTick = -1000;
    private BlockPos lastCollisionBlockPos = null;
    private int lastCollisionEntityId = -1;

    public FullStopCapability(Entity entity) {
        this.entity = entity;
    }

    public static boolean hasDolphinsGrace(LivingEntity entity) {
        return entity instanceof Player player && player.hasEffect(MobEffects.DOLPHINS_GRACE);
    }

    public static boolean hasElytraEquipped(LivingEntity entity) {
        ItemStack chestStack = entity.getItemBySlot(EquipmentSlot.CHEST);

        if (chestStack.getItem() instanceof ElytraItem) {
            int remainingDurability = chestStack.getMaxDamage() - chestStack.getDamageValue();
            return remainingDurability > 1;
        }

        return false;
    }

    public static boolean hasDepthStrider(LivingEntity entity) {
        ItemStack boots = entity.getItemBySlot(EquipmentSlot.FEET);

        return !boots.isEmpty() &&
                EnchantmentHelper.getItemEnchantmentLevel(Enchantments.DEPTH_STRIDER, boots) > 0;
    }

    public void setSoundCooldown(int ticks) {
        this.soundCooldown = ticks;
    }

    public boolean canPlaySound() {
        return soundCooldown <= 0;
    }

    public boolean isCollisionOnCooldown(long currentTick, BlockPos blockPos, int entityId, int cooldownTicks) {
        if (currentTick - lastCollisionTick > cooldownTicks) {
            return false;
        }

        if (blockPos != null && blockPos.equals(lastCollisionBlockPos)) {
            return true;
        }

        return entityId != -1 && entityId == lastCollisionEntityId;
    }

    public void recordCollision(long currentTick, BlockPos blockPos, int entityId) {
        lastCollisionTick = currentTick;
        lastCollisionBlockPos = blockPos;
        lastCollisionEntityId = entityId;
    }

    public long getLastTick() {
        return lastTick;
    }

    public void setLastTick(long tick) {
        this.lastTick = tick;
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
    }

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
        return (- v.y) > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    public boolean isMostlyUpward() {
        Vec3 v = olderVelocity;
        return v.y > Math.sqrt(v.x * v.x + v.z * v.z);
    }

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

        if (soundCooldown > 0) {
            soundCooldown--;
        }

        if (Double.isNaN(runningAverageDelta))
            runningAverageDelta = 0;
        runningAverageDelta = (runningAverageDelta * 19 + scalarHorizontalAcceleration) / 20;
    }

    private void tickRiding() {
        if (hasDismounted) {
            dismountCooldown = 40;
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
        }
    }

    private void tickSpeed() {
        // This is the definitive, server-authoritative velocity for the tick that just occurred.
        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20);

        // The "stopping force" is the difference between the velocity we had at the start of the tick
        // and the velocity we actually ended up with. This is the most direct way to measure deceleration.
        double stoppingForceX = calculateStoppingForceComponent(actualVelocity.x, oldScaledVelocity.x);
        double stoppingForceY = calculateStoppingForceComponent(actualVelocity.y, oldScaledVelocity.y);
        double stoppingForceZ = calculateStoppingForceComponent(actualVelocity.z, oldScaledVelocity.z);

        stoppingForce = Math.sqrt(
                stoppingForceX * stoppingForceX +
                stoppingForceY * stoppingForceY +
                stoppingForceZ * stoppingForceZ
        );

        // The acceleration is the change in velocity over the last tick.
        // We use the "currentScaledVelocity" which has been sanity-checked against client input.
        acceleration = currentScaledVelocity.subtract(oldScaledVelocity);

        scalarHorizontalAcceleration = acceleration.multiply(1, 0, 1).length();
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

    private double calculateStoppingForceComponent(double current, double old) {
        boolean slowedDown = Math.abs(current) < Math.abs(old);
        boolean bounced = isBounce(current, old);

        if (slowedDown || bounced) {
            return Math.abs(old - current);
        } else {
            return 0.0;
        }
    }

    private void tickVelocity(Entity entity) {
        olderVelocity = oldScaledVelocity;
        oldScaledVelocity = currentScaledVelocity;

        previousPosition = currentPosition;
        currentPosition = entity.position();

        if (clientScaledVelocity != null) {
            // This is the server-authoritative velocity based on actual position change.
            Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20);

            // SANITY CHECK: If the client claims to be moving fast but the server sees little to no movement,
            // it's a phantom force (e.g., hugging a wall). In this case, we MUST trust the server's calculation.
            double clientSpeedSqr = clientScaledVelocity.lengthSqr();
            double actualSpeedSqr = actualVelocity.lengthSqr();

            // Thresholds: client claims > ~4.5m/s, but server sees < 0.5m/s.
            if (clientSpeedSqr > 5.0 && actualSpeedSqr < 0.25) {
                currentScaledVelocity = actualVelocity;
            } else {
                // Otherwise, we trust the client's input, which is needed for responsive controls.
                currentScaledVelocity = clientScaledVelocity;
            }
            clientScaledVelocity = null; // Consume the value
        } else {
            // For non-player entities, we just use their deltaMovement.
            currentScaledVelocity = entity.getDeltaMovement().scale(20);
        }

        if (entity instanceof LivingEntity living) {
            double gravity = Objects.requireNonNull(living.getAttribute(ForgeMod.ENTITY_GRAVITY.get())).getValue();

            if (currentScaledVelocity.y >= gravity * -20 && currentScaledVelocity.y < 0) {
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
        tag.putBoolean("JoinedForFirstTime", joinedForFirstTime);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        joinedForFirstTime = nbt.getBoolean("JoinedForFirstTime");
    }

    public static class Provider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
        public static final Capability<FullStopCapability> DELTAV_CAP = CapabilityManager.get(new CapabilityToken<>() {});
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

        @Override
        public CompoundTag serializeNBT() {
            return createCapability().serializeNBT();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            createCapability().deserializeNBT(nbt);
        }
    }

    public static @Nullable FullStopCapability grabCapability(Entity entity) {
        return entity.getCapability(Provider.DELTAV_CAP).orElse(null);
    }
}
