package net.camacraft.fullstop.common.compat.create;

import net.camacraft.fullstop.common.compat.create.contraption.ContraptionDebugView;
import net.camacraft.fullstop.common.compat.create.kinetic.KineticDebugView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * FullStop's F3+V view of Create's hazards: moving contraptions' coming paths ({@link ContraptionDebugView}) and
 * the gears' and water wheels' catch zones and pulls ({@link KineticDebugView}). Client only; reached from
 * {@code ClientRenderEvents} only when Create is installed ({@link CreateCompat#LOADED}), since both views name
 * Create's types.
 */
public final class CreateDebugHooks {

    private CreateDebugHooks() {
    }

    /** Queues this frame's lines (F3+V on). */
    public static void update(ClientLevel level, float partialTick) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        ContraptionDebugView.update(level, partialTick, camera);
        KineticDebugView.update(level, partialTick, camera);
    }
}
