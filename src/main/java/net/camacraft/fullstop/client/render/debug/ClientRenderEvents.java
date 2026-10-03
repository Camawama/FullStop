package net.camacraft.fullstop.client.render.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import net.camacraft.fullstop.FullStop;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles client-side world rendering for debug visuals (e.g. raycast lines).
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = FullStop.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientRenderEvents {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Only render after particles, so it’s drawn clearly on top of the world
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            Minecraft mc = Minecraft.getInstance();

            // Only update and render if the velocity debug toggle is enabled
            if (net.camacraft.fullstop.client.hotkey.DebugHotkeyHandler.isVelocityDebugEnabled() && mc.level != null) {
                // Render for all other entities
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                        // A projectile's own view: its swept path and heading (it has no collision rays
                        // unless a pack tags it fullstop:physics_projectiles, in which case it has both).
                        ProjectileDebug.update(projectile, event.getPartialTick());
                        if (!entity.getType().is(net.camacraft.fullstop.common.physics.rules.FullStopTags.PHYSICS_PROJECTILES)) {
                            continue;
                        }
                    }
                    RaycastLineRenderer.updateDebugRays(entity, event.getPartialTick());
                }

                // Explicitly render for the local player (who is often not in entitiesForRendering)
                if (mc.player != null) {
                    RaycastLineRenderer.updateDebugRays(mc.player, event.getPartialTick());
                }

                // Moving Valkyrien Skies ships, in the same style (no-op without VS).
                ShipVelocityDebug.update(mc.level, event.getPartialTick());

                // Create's moving contraptions and its gears and water wheels (only with Create installed).
                if (net.camacraft.fullstop.common.compat.create.CreateCompat.LOADED) {
                    net.camacraft.fullstop.common.compat.create.CreateDebugHooks.update(mc.level, event.getPartialTick());
                }

                PoseStack poseStack = event.getPoseStack();
                RaycastLineRenderer.render(poseStack, event.getPartialTick());
            } else {
                RaycastLineRenderer.clear();
            }
        }
    }
}