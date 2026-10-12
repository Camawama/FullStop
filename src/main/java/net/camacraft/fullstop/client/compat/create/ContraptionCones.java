package net.camacraft.fullstop.client.compat.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.camacraft.fullstop.common.compat.create.contraption.ContraptionImpacts;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where a contraption's supersonic tip is drawn, for its shock cone ({@code client.render.MachCones}): the tip's place
 * in its local frame taken to the world with Create's own pose at this frame, and its velocity there. Only reached with
 * Create loaded.
 */
public final class ContraptionCones {

    private ContraptionCones() {
    }

    /** {@code {world point, velocity m/s}} of a contraption's local point at {@code partial}, or null for anything else. */
    @Nullable
    public static Vec3[] tipAt(Entity entity, Vec3 local, float partial) {
        if (!(entity instanceof AbstractContraptionEntity c)) return null;
        Vec3 at = ContraptionImpacts.worldPoint(c, local, partial);
        Vec3 ahead = ContraptionImpacts.worldPoint(c, local, partial + 0.05);
        return new Vec3[]{at, ahead.subtract(at).scale(20.0 / 0.05)};
    }
}
