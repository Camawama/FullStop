package net.camacraft.fullstop.common.compat.create.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionStress;
import net.camacraft.fullstop.common.compat.create.contraption.StallControl;
import net.minecraft.network.syncher.EntityDataAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A contraption pressed against something it can't get through stays stalled ({@code contraptionImpactStress}): Create
 * clears a contraption's stall at the start of every {@code tickActors} and sets it again only for an actor still at
 * work (a drill mining), so FullStop sets it again at the end for a contraption {@link ContraptionStress} holds. The
 * bearing, piston, pulley, gantry or cart that moves it reads the stall after its entity's tick (entities tick before
 * block entities) and holds it where it is, and clients get the stall through Create's own synced flag.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class ContraptionStallMixin implements StallControl {

    @Shadow(remap = false)
    @Final
    private static EntityDataAccessor<Boolean> STALLED;

    @Shadow(remap = false)
    protected Contraption contraption;

    @Shadow(remap = false)
    protected abstract void onContraptionStalled();

    @Inject(method = "tickActors", at = @At("RETURN"), require = 0, remap = false)
    private void fullstop$holdStall(CallbackInfo ci) {
        AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (contraption == null || self.level().isClientSide() || !ContraptionStress.holds(self)) return;
        contraption.stalled = true;
        self.getEntityData().set(STALLED, true);
    }

    /**
     * A bearing's arm that was held against an obstacle and is now taken apart (its motor stopped) is set down at the
     * last quarter turn it passed, not rounded through the obstacle ({@link ContraptionStress#settleAngle}). Create
     * builds the placement and the clients' disassembly packet from the entity's angle right after this.
     */
    @Inject(method = "disassemble", at = @At("HEAD"), require = 0, remap = false)
    private void fullstop$settleClearOfTheObstacle(CallbackInfo ci) {
        AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (self.level().isClientSide() || !(self instanceof ControlledContraptionEntity controlled)) return;
        float angle = ContraptionStress.settleAngle(self);
        if (!Float.isNaN(angle)) controlled.setAngle(angle);
    }

    @Override
    public void fullstop$stall() {
        AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (contraption == null || self.level().isClientSide()) return;
        contraption.stalled = true;
        self.getEntityData().set(STALLED, true);
        // Create's own stall announcement: the clients get its pose exactly, and its controller (a bearing, a piston)
        // sends its own state; sent even if an actor had it stalled already, as FullStop may just have moved it
        onContraptionStalled();
    }
}
