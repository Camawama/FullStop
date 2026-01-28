package net.camacraft.fullstop.common.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class SoundPlayer {

    // You can swap this for "entity.generic.splash" if you prefer a splashy sound.
    // This one is more like "moving through water".
    private static final ResourceLocation WATER_SLOSH_ID = new ResourceLocation("entity.player.swim");

    private SoundPlayer() {}

    public static void playWaterSlosh(Entity entity, float volume, float pitch) {
        Level level = entity.level();
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(WATER_SLOSH_ID);

        BlockPos pos = entity.blockPosition();

        // Server: broadcast to nearby players
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.playSound(
                    null,
                    pos,
                    sound,
                    SoundSource.PLAYERS,
                    volume,
                    pitch
            );
            return;
        }

        // Client: local sound (so you actually hear it when running client-side logic)
        level.playLocalSound(
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                sound,
                SoundSource.PLAYERS,
                volume,
                pitch,
                false
        );
    }
}