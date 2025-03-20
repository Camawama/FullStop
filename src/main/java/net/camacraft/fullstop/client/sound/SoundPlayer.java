package net.camacraft.fullstop.client.sound;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class SoundPlayer {

    public static void playSound(Entity entity, SoundEvent sound, float volume, float pitch) {

        entity.level().playLocalSound(entity.blockPosition(), sound, SoundSource.BLOCKS, volume, pitch, false);

    }
}