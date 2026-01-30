package net.camacraft.fullstop.common.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Full Stop sound helper.
 */
public final class FSSoundPlayer {

    public static final ResourceLocation WATER_SLOSH_ID = new ResourceLocation("entity.player.swim");

    private FSSoundPlayer() {}

    /**
     * Server-side broadcast: all nearby players hear it.
     * Does nothing if called on the client.
     */
    public static void playSoundServer(Level level, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        serverLevel.playSound(
                null,       // null -> broadcast to all nearby players
                pos,
                sound,
                source,
                volume,
                pitch
        );
    }

    /**
     * Client-side local playback: only the local client hears it.
     * Does nothing if called on the server.
     */
    public static void playSoundClient(Level level, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch) {
        if (!level.isClientSide()) return;

        // playLocalSound is the "local only" version (no networking).
        level.playLocalSound(
                x, y, z,
                sound,
                source,
                volume,
                pitch,
                false
        );
    }

    public static void playSoundServer(Entity entity, SoundEvent sound, SoundSource source, float volume, float pitch) {
        playSoundServer(entity.level(), entity.blockPosition(), sound, source, volume, pitch);
    }

    public static void playSoundClient(Entity entity, SoundEvent sound, SoundSource source, float volume, float pitch) {
        playSoundClient(entity.level(), entity.getX(), entity.getY(), entity.getZ(), sound, source, volume, pitch);
    }

    /** Lookup by ResourceLocation (handy for “vanilla” sounds or config-driven sounds). */
    public static SoundEvent sound(ResourceLocation id) {
        return BuiltInRegistries.SOUND_EVENT.get(id);
    }
}
