package net.camacraft.fullstop.client.compat.create;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionRiders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The local player standing on a Create contraption that turns about the vertical turns with it, every frame (the
 * client half of {@link ContraptionRiders}): the contraption's angle at this frame's partial tick against the last
 * frame's, taken off the player's facing and its previous facing alike, so the view turns smoothly, not in tick steps.
 */
public final class ContraptionRidersClient {

    private static int lastContraption = -1;
    private static float lastAngle;
    /** The total turn this has given the player since the game started (degrees; for the pack harness). */
    public static double turnedTotal;

    private ContraptionRidersClient() {
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.isPaused() || !ContraptionRiders.enabled() || player.isPassenger()) {
            lastContraption = -1;
            return;
        }
        float partialTick = event.renderTickTime;
        AABB near = player.getBoundingBox().inflate(64.0);
        ControlledContraptionEntity on = ContraptionRiders.under(player,
                mc.level.getEntitiesOfClass(ControlledContraptionEntity.class, near), partialTick);
        if (on == null) {
            lastContraption = -1;
            return;
        }
        float angle = on.getAngle(partialTick);
        if (lastContraption == on.getId()) {
            float turn = Mth.wrapDegrees(angle - lastAngle);
            if (Math.abs(turn) > 1.0e-4f && Math.abs(turn) <= ContraptionRiders.MAX_STEP) {
                player.setYRot(player.getYRot() - turn);
                player.yRotO -= turn;
                turnedTotal -= turn;
            }
        }
        lastContraption = on.getId();
        lastAngle = angle;
    }
}
