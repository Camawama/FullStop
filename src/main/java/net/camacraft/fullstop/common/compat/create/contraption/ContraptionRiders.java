package net.camacraft.fullstop.common.compat.create.contraption;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionHandler;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.camacraft.fullstop.common.compat.create.CreateCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.camacraft.fullstop.FullStopConfig.SERVER;

/**
 * Bodies standing on a Create contraption that turns about the vertical (a bearing's turning plate, a carousel) turn
 * with it, as riders of a Valkyrien Skies ship do ({@code contraptionRidersTurn}): Create carries them round in
 * position but left them facing where they faced. Mobs here, on the server, by the contraption's turn each tick (body,
 * head and look); a player on its own client, every frame ({@code ContraptionRidersClient}), since a player moves
 * itself. Only a bearing turning about the vertical turns its riders: a windmill's or a horizontal bearing's turn has
 * no part about the vertical. Seated passengers are Create's.
 */
public final class ContraptionRiders {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A turn larger than this in one tick or frame (degrees) is a contraption set to a new angle, not turning. */
    public static final float MAX_STEP = 60.0f;

    private ContraptionRiders() {
    }

    public static boolean enabled() {
        return CreateCompat.active() && SERVER.contraptionRidersTurn.get();
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || !enabled()) return;
        Map<Integer, WeakReference<AbstractContraptionEntity>> loaded = ContraptionHandler.loadedContraptions.get(level);
        if (loaded == null || loaded.isEmpty()) return;
        List<ControlledContraptionEntity> turning = new ArrayList<>();
        for (WeakReference<AbstractContraptionEntity> ref : loaded.values()) {
            if (ref.get() instanceof ControlledContraptionEntity c && c.isAlive() && c.getRotationAxis() == Direction.Axis.Y) {
                turning.add(c);
            }
        }
        for (ControlledContraptionEntity c : turning) {
            // this tick's turn about +Y: Create turns a point by +angle (VecHelper.rotate), which a facing follows as a
            // yaw change of -angle
            float turn = Mth.wrapDegrees(c.getAngle(1.0f) - c.getAngle(0.0f));
            if (Math.abs(turn) < 1.0e-4f || Math.abs(turn) > MAX_STEP) continue;
            // the bodies right above it (Create's own list of bodies on a contraption misses a mob resting still on it:
            // a pig on a turning plate was listed 4 ticks in 60), round its blocks (the entity's own box isn't sized to
            // them for the first ticks after the bearing assembles)
            AABB above = region(c);
            if (above == null) continue;
            for (Entity body : level.getEntities(c, above, e -> !(e instanceof Player) && !e.isPassenger() && e.isAlive()
                    && !(e instanceof AbstractContraptionEntity))) {
                // a player turns on its own client
                if (!standsOn(c, body, 1.0f)) continue;
                turn(body, -turn);
                if (SERVER.debugContraptionLog.get()) {
                    LOGGER.info("[FullStop] contraption {} turned {} by {} degrees (its yaw now {})", c.getId(), body.getName().getString(),
                            String.format(Locale.ROOT, "%.2f", -turn), String.format(Locale.ROOT, "%.2f", body.getYRot()));
                }
            }
        }
    }

    /**
     * The world box a body standing on {@code c} is in, for a contraption turning about the vertical: round its axis out
     * to its farthest block, from its lowest block to a little over its highest. Null without blocks.
     */
    @Nullable
    static AABB region(AbstractContraptionEntity c) {
        Contraption contraption = c.getContraption();
        if (contraption == null) return null;
        AABB local = ContraptionTerrainImpacts.blockBounds(contraption);
        if (local == null) return null;
        Vec3 anchor = c.getAnchorVec();
        double reach = Math.max(Math.max(Math.abs(local.minX), Math.abs(local.maxX)), Math.max(Math.abs(local.minZ), Math.abs(local.maxZ))) + 1.5;
        return new AABB(anchor.x + 0.5 - reach, anchor.y + local.minY - 0.5, anchor.z + 0.5 - reach,
                anchor.x + 0.5 + reach, anchor.y + local.maxY + 2.5, anchor.z + 0.5 + reach);
    }

    /** Turns {@code body} by {@code yaw} degrees: its facing, and a living body's head and body. */
    static void turn(Entity body, float yaw) {
        body.setYRot(body.getYRot() + yaw);
        body.yRotO += yaw;
        if (body instanceof LivingEntity living) {
            living.setYHeadRot(living.getYHeadRot() + yaw);
            living.yHeadRotO += yaw;
            living.yBodyRot += yaw;
            living.yBodyRotO += yaw;
        }
    }

    /**
     * Whether {@code body} stands on {@code c}: one of the contraption's solid blocks is right under its feet (the
     * middle or a corner of its feet, a little below), in the contraption's pose at {@code partialTick}.
     */
    public static boolean standsOn(AbstractContraptionEntity c, Entity body, float partialTick) {
        Contraption contraption = c.getContraption();
        if (contraption == null) return false;
        AABB box = body.getBoundingBox();
        double y = box.minY - 0.05;
        double[][] spots = {{body.getX(), body.getZ()}, {box.minX + 0.05, box.minZ + 0.05}, {box.maxX - 0.05, box.minZ + 0.05},
                {box.minX + 0.05, box.maxZ - 0.05}, {box.maxX - 0.05, box.maxZ - 0.05}};
        for (double[] spot : spots) {
            Vec3 local = c.toLocalVector(new Vec3(spot[0], y, spot[1]), partialTick);
            BlockPos cell = BlockPos.containing(local);
            StructureTemplate.StructureBlockInfo info = contraption.getBlocks().get(cell);
            if (info == null) continue;
            BlockState state = info.state();
            if (!state.isAir() && !state.getCollisionShape(contraption.getContraptionWorld(), cell).isEmpty()) return true;
        }
        return false;
    }

    /** The bearing contraption turning about the vertical that {@code body} stands on, among {@code candidates}, or null. */
    @Nullable
    public static ControlledContraptionEntity under(Entity body, Iterable<? extends Entity> candidates, float partialTick) {
        for (Entity e : candidates) {
            if (e instanceof ControlledContraptionEntity c && c.isAlive() && c.getRotationAxis() == Direction.Axis.Y
                    && standsOn(c, body, partialTick)) {
                return c;
            }
        }
        return null;
    }
}
