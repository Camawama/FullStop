package net.camacraft.fullstop.common.physics.rules;

import net.camacraft.fullstop.FullStopConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EntityWeight {

    private static final Map<String, Double> CACHED_WEIGHTS = new HashMap<>();
    private static boolean loaded = false;

    public static double getEntityMass(Entity entity) {
        AABB box = entity.getBoundingBox();
        double entityVolume = (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);

        if (entity instanceof LivingEntity living && living.isFallFlying()) {
            return entityVolume * 3;
        }

        double multiplier = getWeightMultiplier(entity);
        return entityVolume * multiplier;
    }

    private static double getWeightMultiplier(Entity entity) {
        ResourceLocation registryName = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (registryName == null) return 1.0;
        
        String key = registryName.toString();

        if (!loaded) {
            loadConfigWeights();
        }

        return CACHED_WEIGHTS.getOrDefault(key, 1.0);
    }

    public static void loadConfigWeights() {
        CACHED_WEIGHTS.clear();
        loaded = true;
        List<? extends String> weights = FullStopConfig.SERVER.entityWeights.get();
        for (String entry : weights) {
            String[] parts = entry.split(",");
            if (parts.length == 2) {
                try {
                    String entityId = parts[0].trim();
                    double multiplier = Double.parseDouble(parts[1].trim());
                    CACHED_WEIGHTS.put(entityId, multiplier);
                } catch (NumberFormatException ignored) {
                    // Log error or ignore
                }
            }
        }
    }
}
