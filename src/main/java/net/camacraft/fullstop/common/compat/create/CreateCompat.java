package net.camacraft.fullstop.common.compat.create;

import net.camacraft.fullstop.FullStopConfig;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;

/**
 * "Create Catastrophes": with Create installed, every moving part of it is dangerous (config section
 * {@code Create}). This class is the guard: it names no Create type, so FullStop loads the same
 * without Create; everything that does lives in this package's sub-packages and is only ever reached
 * through {@link #init()} when Create is installed ({@link CreateHooks}).
 *
 * <p>The hazards: moving contraptions strike what they run into like ships do
 * ({@code contraption.ContraptionImpacts}); spinning gears and water wheels pull bodies in
 * (create-kinetics' {@code kinetic} package).
 */
public final class CreateCompat {

    /** Create's mod id. */
    public static final String MOD_ID = "create";

    /** Whether Create is installed (known from mod discovery on, so mixin plugins can ask it too). */
    public static final boolean LOADED = loaded();

    private CreateCompat() {
    }

    private static boolean loaded() {
        ModList list = ModList.get();
        if (list != null) return list.isLoaded(MOD_ID);
        LoadingModList loading = LoadingModList.get();
        return loading != null && loading.getModFileById(MOD_ID) != null;
    }

    /** Create is installed and {@code enableCreateCompat} is on. */
    public static boolean active() {
        return LOADED && FullStopConfig.SERVER_SPEC.isLoaded() && FullStopConfig.SERVER.enableCreateCompat.get();
    }

    /** Registers the Create hazards' handlers; nothing without Create. Called from FullStop's constructor. */
    public static void init() {
        if (!LOADED) return;
        CreateHooks.register();
    }
}
