package net.camacraft.fullstop.capabilities;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
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

    @NotNull
    private Vec3 clientVelocity = Vec3.ZERO;

    //private int lastSeenRiptiding = Integer.MAX_VALUE;
    private double deltaSpeed = 0.0;
    private double runningAverageDelta = 0.0;

    public boolean recentlyRiptiding(LivingEntity player) {
        return tickRiptiding(player);
    }

    public boolean hasDolphinsGrace(LivingEntity entity) {
        return tickDolphinsGrace(entity);
    }

    public boolean hasDepthStrider(LivingEntity entity) {
        return tickDepthStrider(entity);
    }

    public double getDeltaSpeed() {
      return deltaSpeed;
    }

    public double getRunningAverageDelta() {
        return runningAverageDelta;
    }

    public void setCurrentVelocity(Vec3 currentVelocity) {
        this.clientVelocity = currentVelocity.scale(20);
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

    public void tick(LivingEntity entity) {
        tickVelocity(entity);
        tickRiptiding(entity);
        tickDolphinsGrace(entity);
        tickDepthStrider(entity);
        tickSpeed();

        runningAverageDelta = (runningAverageDelta * 19 + deltaSpeed) / 20;
    }

//    private void tickRiptiding(LivingEntity player) {
//        if (isRiptiding(player)) {
//            lastSeenRiptiding = 0;  // Reset when riptiding
//        } else if (lastSeenRiptiding < 100) {
//            lastSeenRiptiding++;  // Increment if not riptiding
//        }
//    }

    private static boolean tickRiptiding(LivingEntity entity) {
        if (entity.isAutoSpinAttack()) {
            return true;
        }
        return false;
//        // Check if the entity is a player and is currently using an item
//        if (player.isUsingItem()) {
//            // Check if the used item is a Trident and has the Riptide enchantment
//            ItemStack usedItem = player.getUseItem();
//            if (usedItem.getItem() instanceof TridentItem) {
//                int enchantLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.RIPTIDE, usedItem);
//                return enchantLevel > 0;
//            }
//        }
//        return false;
    }

    private static boolean tickDepthStrider(LivingEntity entity) {
        if (entity instanceof Player player) {
            ItemStack boots = player.getInventory().getArmor(3); // Index 3 corresponds to boots

            return !boots.isEmpty() && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.DEPTH_STRIDER, boots) > 0;
        }
        return false;
    }

    private static boolean tickDolphinsGrace(LivingEntity entity) {
        if (entity instanceof Player player && player.hasEffect(MobEffects.DOLPHINS_GRACE)) {
            return true;
        }
        return false;
    }

    private void tickSpeed() {
        boolean bX = isBounce(currentVelocity.x, oldVelocity.x),
                bY = isBounce(currentVelocity.y, oldVelocity.y),
                bZ = isBounce(currentVelocity.z, oldVelocity.z);

        if (bX || bY || bZ) {
            deltaSpeed = 0.0;
        } else {
            deltaSpeed = currentVelocity.subtract(oldVelocity).length();
        }
    }

    private void tickVelocity(Entity entity) {
        olderVelocity = oldVelocity;
        oldVelocity = currentVelocity;

        if (entity instanceof Player) {
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
