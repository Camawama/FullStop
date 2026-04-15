package net.camacraft.fullstop.common.capability;

import net.camacraft.fullstop.FullStop;
import net.camacraft.fullstop.common.effect.ModEffects;
import net.camacraft.fullstop.common.util.MathUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
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
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class FullStopCapability implements INBTSerializable<CompoundTag> {

    public static final ResourceLocation DELTA_VELOCITY = new ResourceLocation(FullStop.MODID, "delta_velocity");

    private final Entity entity;

    @NotNull
    private Vec3 prevPrevVelocityMps = Vec3.ZERO;
    @NotNull
    private Vec3 prevVelocityMps = Vec3.ZERO;
    @NotNull
    private Vec3 velocityMps = Vec3.ZERO;
    private Vec3 clientVelocityMps = null;
    private Vec3 currentPosition = Vec3.ZERO;
    private Vec3 previousPosition = Vec3.ZERO;
    private Vec3 acceleration;
    private double decelerationForce = 0.0;
    private double accelMagnitude = 0.0;
    private double avgAccel = 0.0;
    private double rawAvgAccel = 0.0; // Unaffected by potions

    private boolean isDamageImmune = false;
    private boolean hasTeleported = false;
    private boolean hasDismounted = false;
    private boolean joinedForFirstTime = false;
    private boolean firstTick = true;
    private double teleportCooldown = 0.0;
    private double dismountCooldown = 0.0;
    private int soundCooldown = 0;
    private int sonicBoomCooldown = 0;
    private long lastTick = -1;

    private long lastCollisionTick = -1000;
    private BlockPos lastCollisionBlockPos = null;
    private int lastCollisionEntityId = -1;

    private double targetAngle = Double.NaN;
    private double targetPitch = Double.NaN;

    private boolean justBounced = false;

    public FullStopCapability(Entity entity) {
        this.entity = entity;
    }

    public void tick(Entity entity) {
        if (lastTick != -1 && entity.tickCount > lastTick + 1) {
            resetVelocity();
            currentPosition = entity.position();
            previousPosition = currentPosition;
        }

        tickVelocity(entity);
        tickSpeed();
        tickRotation(entity);
        tickImmunity();
        tickRiding();

        if (soundCooldown > 0) {
            soundCooldown--;
        }
        
        if (sonicBoomCooldown > 0) {
            sonicBoomCooldown--;
        }

        if (Double.isNaN(avgAccel)) avgAccel = 0;
        if (Double.isNaN(rawAvgAccel)) rawAvgAccel = 0;

        if (entity instanceof LivingEntity living) {
            double gravity = 0.08;
            if (!living.isNoGravity()) {
                var gravityAttr = living.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
                if (gravityAttr != null) {
                    gravity = gravityAttr.getValue();
                }
            } else {
                gravity = 0.0;
            }
            
            // Ensure gravity is treated as a downward force magnitude
            gravity = Math.abs(gravity);

            // Calculate proper acceleration (G-force) by subtracting gravity vector.
            // Acceleration is in m/s/tick. Gravity attribute is blocks/tick^2.
            // 1 block/tick^2 = 20 m/s/tick.
            Vec3 gravityVector = new Vec3(0, -gravity * 20, 0);
            Vec3 gForceVec = acceleration.subtract(gravityVector);

            // When falling, the "drag" force acts upwards (positive Y).
            // To simulate weightlessness during freefall, we ignore this upward component.
            // This ensures that falling at terminal velocity (where drag = gravity) results in 0 G-force.
            if (velocityMps.y < 0 && gForceVec.y > 0) {
                gForceVec = new Vec3(gForceVec.x, 0, gForceVec.z);
            }

            double gForceMagnitude = gForceVec.length();

            // Fix drift: if both current and average are negligible, snap to 0
            if (gForceMagnitude < 0.001 && avgAccel < 0.001) {
                avgAccel = 0.0;
                rawAvgAccel = 0.0;
            } else {
                // Clamp input acceleration to prevent massive single-tick spikes (e.g. teleportation artifacts)
                // 20.0 m/s/tick is ~400 m/s^2 (40g), which is a reasonable upper bound for "valid" movement.
                double clampedInput = Math.min(gForceMagnitude, 20.0);
                
                // If we just bounced, we don't want to count the sudden change in velocity as G-force
                if (justBounced) {
                    clampedInput = 0;
                    justBounced = false;
                }

                // Calculate Raw Average (Standard smoothing, unaffected by potions)
                rawAvgAccel = (rawAvgAccel * 19 + clampedInput) / 20;

                // Apply Potion Modifiers to the running average calculation
                // This affects how quickly the G-force builds up or decays
                double smoothingFactor = 20.0; // Default smoothing (higher = slower change)

                int clarityLevel = 0;
                int vertigoLevel = 0;

                MobEffectInstance clarity = living.getEffect(ModEffects.CLARITY.get());
                if (clarity != null) {
                    clarityLevel = clarity.getAmplifier() + 1;
                }

                MobEffectInstance vertigo = living.getEffect(ModEffects.VERTIGO.get());
                if (vertigo != null) {
                    vertigoLevel = vertigo.getAmplifier() + 1;
                }

                int netLevel = vertigoLevel - clarityLevel;

                if (netLevel > 0) {
                    // Vertigo: Faster buildup (lower smoothing factor)
                    smoothingFactor = Math.max(5.0, smoothingFactor * Math.pow(0.7, netLevel));
                } else if (netLevel < 0) {
                    // Clarity: Slower buildup (higher smoothing factor)
                    smoothingFactor = smoothingFactor * Math.pow(1.5, -netLevel);
                }
                
                avgAccel = (avgAccel * (smoothingFactor - 1) + clampedInput) / smoothingFactor;
            }
        }
    }

    private void tickVelocity(Entity entity) {
        if (firstTick) {
            currentPosition = entity.position();
            previousPosition = currentPosition.subtract(entity.getDeltaMovement());
            firstTick = false;
        } else {
            previousPosition = currentPosition;
        }

        if (hasTeleported) {
            currentPosition = entity.position();
            previousPosition = currentPosition;
            velocityMps = Vec3.ZERO;
            prevVelocityMps = Vec3.ZERO;
            prevPrevVelocityMps = Vec3.ZERO;
            return;
        }

        prevPrevVelocityMps = prevVelocityMps;
        prevVelocityMps = velocityMps;

        currentPosition = entity.position();

        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20);

        // We only use client velocity for players to ensure responsive controls
        // For everything else, we trust the server's calculation
        if (entity instanceof Player && clientVelocityMps != null) {
            double actualSpeedSqr = actualVelocity.lengthSqr();
            double clientSpeedSqr = clientVelocityMps.lengthSqr();

            // FIX: Completely override client velocity if actual velocity is significantly lower,
            // or if we are actively colliding with a block.
            if (entity.horizontalCollision || entity.verticalCollision || (clientSpeedSqr > actualSpeedSqr + 0.1)) {
                // If there is a huge mismatch or a hard collision, the client is probably pressing against a wall/floor.
                // We should completely trust the server's actual movement calculation to avoid velocity buildup.
                velocityMps = actualVelocity;
            } else {
                // If moving normally and no collision, trust the client for responsiveness
                velocityMps = clientVelocityMps;
            }
            clientVelocityMps = null; // Consume the value
        } else {
            velocityMps = actualVelocity;
        }

        if (entity instanceof LivingEntity living) {
            double gravity = Objects.requireNonNull(living.getAttribute(ForgeMod.ENTITY_GRAVITY.get())).getValue();

            // Only zero out Y velocity if we are actually on the ground AND not moving downwards significantly
            // This prevents "accumulating" downward velocity while standing on a ledge or hugging a wall
            if (velocityMps.y >= gravity * -20 && velocityMps.y < 0 && entity.onGround()) {
                velocityMps = new Vec3(velocityMps.x, 0, velocityMps.z);
            }
        }
    }

    private void tickSpeed() {
        // This is the definitive, server-authoritative velocity for the tick that just occurred.
        Vec3 actualVelocity = currentPosition.subtract(previousPosition).scale(20);

        // The "stopping force" is the difference between the velocity we had at the start of the tick
        // and the velocity we actually ended up with. This is the most direct way to measure deceleration.
        double stoppingForceX = calculateStoppingForceComponent(actualVelocity.x, prevVelocityMps.x);
        double stoppingForceY = calculateStoppingForceComponent(actualVelocity.y, prevVelocityMps.y);
        double stoppingForceZ = calculateStoppingForceComponent(actualVelocity.z, prevVelocityMps.z);

        decelerationForce = Math.sqrt(
                stoppingForceX * stoppingForceX +
                        stoppingForceY * stoppingForceY +
                        stoppingForceZ * stoppingForceZ
        );

        // The acceleration is the change in velocity over the last tick.
        // We use the "velocityMps" which has been sanity-checked against client input.
        acceleration = velocityMps.subtract(prevVelocityMps);

        // Calculate full 3D acceleration magnitude (including Y)
        accelMagnitude = acceleration.length();
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

    private static boolean isBounce(double v1, double v2) {
        if (v1 == 0.0 || v2 == 0.0) {
            return false;
        }

        return Math.signum(v1) != Math.signum(v2);
    }

    private void tickRotation(Entity entity) {
        if (entity instanceof Player || entity.isControlledByLocalInstance()) return;
        if (Double.isNaN(targetAngle)) return;

        if (entity instanceof Boat) return;

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

        double correction = MathUtils.angleWrap(targetAngle - entity.getYRot());

        if (Math.abs(correction) < 0.5) {
            targetAngle = Double.NaN;
        }

        return correction * 0.005 * delta;
    }

    public double pitchCorrection(double delta) {
        if (Double.isNaN(targetPitch)) {
            return 0;
        }

        double correction = MathUtils.angleWrap(targetPitch - entity.getXRot());

        if (Math.abs(correction) < 0.5) {
            targetPitch = Double.NaN;
        }

        return correction * 0.005 * delta;
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

    private void tickRiding() {
        if (hasDismounted) {
            dismountCooldown = 40;
            hasDismounted = false;
        }

        if (dismountCooldown > 0) {
            dismountCooldown--;
        }
    }

    public Vec3 getCurrentNativeVelocity() {
        return velocityMps.scale(0.05);
    }

    public void setCurrentNativeVelocity(Vec3 velocityMps) {
        this.clientVelocityMps = velocityMps.scale(20);
    }

    public Vec3 getPreviousNativeVelocity() {
        return prevVelocityMps.scale(0.05);
    }

    public Vec3 getCurrentScaledVelocity() {
        return velocityMps;
    }

    public Vec3 getPreviousScaledVelocity() {
        return prevVelocityMps;
    }

    public double getStoppingForce() {
        return decelerationForce;
    }

    public Vec3 getAcceleration() {
        return acceleration;
    }

    public double getRunningAverageDelta() {
        return avgAccel;
    }

    public double getRawRunningAverageDelta() {
        return rawAvgAccel;
    }

    public boolean getIsDamageImmune() {
        return isDamageImmune;
    }

    public void setHasTeleported(boolean value) {
        this.hasTeleported = value;
    }

    public double getTeleportCooldown() {
        return teleportCooldown;
    }

    public void justDismounted() {
        setCurrentNativeVelocity(Vec3.ZERO);
        this.hasDismounted = true;
    }

    public double getDismountCooldown() {
        return dismountCooldown;
    }

    public boolean getJoinedForFirstTime() {
        return joinedForFirstTime;
    }

    public void setJoinedForFirstTime(boolean value) {
        this.joinedForFirstTime = value;
    }

    public long getLastTick() {
        return lastTick;
    }

    public void setLastTick(long tick) {
        this.lastTick = tick;
    }

    public void setSoundCooldown(int ticks) {
        this.soundCooldown = ticks;
    }

    public boolean canPlaySound() {
        return soundCooldown <= 0;
    }

    public void setSonicBoomCooldown(int ticks) {
        this.sonicBoomCooldown = ticks;
    }
    
    public boolean canSonicBoom() {
        return sonicBoomCooldown <= 0;
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

    public void setTargetAngle(double targetAngle) {
        this.targetAngle = targetAngle;
    }

    public void setTargetPitch(double targetPitch) {
        this.targetPitch = targetPitch;
    }

    public boolean isMostlyDownward() {
        Vec3 v = prevVelocityMps;
        return (-v.y) > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    public boolean isMostlyUpward() {
        Vec3 v = prevVelocityMps;
        return v.y > Math.sqrt(v.x * v.x + v.z * v.z);
    }

    public boolean isMostlyHorizontal() {
        Vec3 v = prevVelocityMps;
        return Math.sqrt(v.x * v.x + v.z * v.z) > Math.abs(v.y);
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

    public void resetVelocity() {
        this.velocityMps = Vec3.ZERO;
        this.prevVelocityMps = Vec3.ZERO;
        this.prevPrevVelocityMps = Vec3.ZERO;
        this.clientVelocityMps = null;
    }

    public void setJustBounced(boolean justBounced) {
        this.justBounced = justBounced;
    }

    // --- NBT Serialization ---
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

    // --- Capability Management ---
    public static @Nullable FullStopCapability grabCapability(Entity entity) {
        return entity.getCapability(Provider.DELTAV_CAP).orElse(null);
    }

    @SubscribeEvent
    public static void onAttachCapabilitiesEvent(AttachCapabilitiesEvent<Entity> event) {
        if ((event.getObject().getCapability(Provider.DELTAV_CAP).isPresent())) return;

        event.addCapability(DELTA_VELOCITY, new Provider(event.getObject()));
    }

    // --- Inner Class: Provider ---
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
}
