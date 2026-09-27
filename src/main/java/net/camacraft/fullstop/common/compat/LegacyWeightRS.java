package net.camacraft.fullstop.common.compat;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.mojang.logging.LogUtils;
import net.camacraft.camafy.compat.ModPresence;
import net.camacraft.camafy.weight.WeightConfig;
import net.camacraft.fullstop.FullStopConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries the retired WeightRS mod's settings over, once, so nobody has to redo them:
 * <ol>
 *   <li>its weight MODEL (entity weights, densities, carried items, the blacklist) goes to
 *   Camafy's {@code camafy-weight.toml} ({@link #bootstrap}, from the mod constructor, before any
 *   config loads); the old file is then renamed {@code weightrs-common.toml.migrated};</li>
 *   <li>its ship PHYSICS settings go to each world's FullStop server config, the first time that
 *   world loads ({@link #onServerConfigLoading}), and {@code configVersion} records that it ran.</li>
 * </ol>
 * Nothing already tuned is overwritten: a setting is only copied while FullStop's is still at
 * its default, and only valid, non-default old values are taken. While WeightRS itself is still
 * installed its file is left where it is (it is still WeightRS's), but its values still seed new
 * worlds.
 */
public final class LegacyWeightRS {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FILE = "weightrs-common.toml";
    private static final String MIGRATED = FILE + ".migrated";
    /** {@code configVersion} once the import has run on a world's file. */
    public static final int IMPORTED_VERSION = 2;

    private LegacyWeightRS() {
    }

    /** Mod constructor: the weight-model settings into Camafy's config, and the old file retired. */
    public static void bootstrap() {
        if (ModPresence.isLoaded("weightrs")) return; // still WeightRS's own file
        Path old = FMLPaths.CONFIGDIR.get().resolve(FILE);
        if (!Files.isRegularFile(old)) return;
        try (CommentedFileConfig file = CommentedFileConfig.builder(old).build()) {
            file.load();
            if (file.contains("entities") || file.contains("inventory")) { // the pre-Camafy layout
                Files.copy(old, old.resolveSibling(FILE + ".pre-camafy"), StandardCopyOption.REPLACE_EXISTING);
                WeightConfig.importLegacy(file, FILE);
            }
        } catch (Exception e) {
            LOGGER.warn("[FullStop] Couldn't import the weight settings from the old {}", FILE, e);
            return;
        }
        try {
            // Renamed, not deleted: it still seeds each world's ship-weight settings, and a file
            // left in place would be imported again on every launch.
            Files.move(old, old.resolveSibling(MIGRATED), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[FullStop] WeightRS is part of FullStop now: {} was imported and renamed to {}", FILE, MIGRATED);
        } catch (Exception e) {
            LOGGER.warn("[FullStop] Couldn't rename the old {} after importing it", FILE, e);
        }
    }

    /** WeightRS's setting paths, and the FullStop "Ship Weight" settings they become. */
    private static Map<List<String>, ForgeConfigSpec.ConfigValue<?>> keyMap() {
        FullStopConfig.ServerConfigValues c = FullStopConfig.SERVER;
        Map<List<String>, ForgeConfigSpec.ConfigValue<?>> map = new LinkedHashMap<>();
        map.put(List.of("physics", "enablePhysics"), c.enableShipWeight);
        map.put(List.of("general", "includePlayers"), c.includePlayers);
        map.put(List.of("general", "gravity"), c.gravity);
        map.put(List.of("general", "minPhysicsWeightKg"), c.minPhysicsWeightKg);
        map.put(List.of("items", "enableItemEntityPhysics"), c.enableItemEntityPhysics);
        map.put(List.of("physics", "enableStandingWeight"), c.enableStandingWeight);
        map.put(List.of("physics", "enableJumpImpulse"), c.enableJumpImpulse);
        map.put(List.of("physics", "enableFallImpact"), c.enableFallImpact);
        map.put(List.of("physics", "jumpImpulseMultiplier"), c.jumpImpulseMultiplier);
        map.put(List.of("physics", "fallImpactMultiplier"), c.fallImpactMultiplier);
        map.put(List.of("physics", "minFallDistance"), c.minFallDistance);
        map.put(List.of("physics", "maxForceNewtons"), c.maxForceNewtons);
        return map;
    }

    /**
     * ModConfigEvent.Loading: a world's FullStop server config that hasn't had the import yet gets
     * WeightRS's ship-weight settings, written to the file in ONE save together with
     * {@code configVersion}; Forge's file watcher then reloads it. (Setting the loaded values
     * one by one instead would race: Forge autosaves after every set while its watcher reloads
     * the file on another thread.)
     */
    public static void onServerConfigLoading(ModConfig config) {
        if (config.getSpec() != FullStopConfig.SERVER_SPEC) return;
        if (FullStopConfig.SERVER.configVersion.get() >= IMPORTED_VERSION) return;
        Path legacy = legacyFile();
        if (legacy == null) return;
        Path target = config.getFullPath();
        if (target == null || !Files.isRegularFile(target)) return;

        int changed = 0;
        try (CommentedFileConfig old = CommentedFileConfig.builder(legacy).build();
             CommentedFileConfig file = CommentedFileConfig.builder(target)
                     .preserveInsertionOrder().writingMode(WritingMode.REPLACE).build()) {
            old.load();
            file.load();
            for (Map.Entry<List<String>, ForgeConfigSpec.ConfigValue<?>> entry : keyMap().entrySet()) {
                ForgeConfigSpec.ConfigValue<?> value = entry.getValue();
                Object fallback = value.getDefault();
                Object current = convert(file.get(value.getPath()), fallback);
                if (current != null && !current.equals(fallback)) continue; // tuned here already
                Object candidate = convert(old.get(entry.getKey()), fallback);
                if (candidate == null || candidate.equals(fallback) || !valid(value, candidate)) continue;
                file.set(value.getPath(), candidate);
                changed++;
            }
            file.set(FullStopConfig.SERVER.configVersion.getPath(), IMPORTED_VERSION);
            file.save();
        } catch (Exception e) {
            LOGGER.warn("[FullStop] Couldn't import WeightRS's ship-weight settings into {}", target, e);
            return;
        }
        LOGGER.info("[FullStop] Imported {} WeightRS ship-weight setting(s) into {}", changed, target);
    }

    @Nullable
    private static Path legacyFile() {
        Path dir = FMLPaths.CONFIGDIR.get();
        Path migrated = dir.resolve(MIGRATED);
        if (Files.isRegularFile(migrated)) return migrated;
        Path live = dir.resolve(FILE);
        return Files.isRegularFile(live) ? live : null;
    }

    /** A TOML value as the type of {@code like} (TOML hands numbers back as Integer, Long or Double). */
    @Nullable
    private static Object convert(@Nullable Object raw, Object like) {
        if (raw == null) return null;
        if (like instanceof Double) return raw instanceof Number n ? n.doubleValue() : null;
        if (like instanceof Integer) return raw instanceof Number n ? n.intValue() : null;
        if (like instanceof Boolean) return raw instanceof Boolean ? raw : null;
        return like.getClass().isInstance(raw) ? raw : null;
    }

    private static boolean valid(ForgeConfigSpec.ConfigValue<?> value, Object candidate) {
        Object spec = FullStopConfig.SERVER_SPEC.getSpec().get(value.getPath());
        return spec instanceof ForgeConfigSpec.ValueSpec valueSpec && valueSpec.test(candidate);
    }
}
