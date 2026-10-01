package net.camacraft.fullstop.advancement;

import com.google.gson.JsonObject;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A criterion with no conditions of its own: it fires for a player when FullStop's code says so,
 * through vanilla's listener path like any other trigger. It also remembers which players it fired
 * for, so the game tests can see a trigger work: Forge's {@code PlayerAdvancements.award} refuses
 * every award to a {@code FakePlayer}, so a test can watch the trigger but never the advancement.
 */
public final class PlainTrigger extends SimpleCriterionTrigger<PlainTrigger.Instance> {
    private final ResourceLocation id;
    private final Set<UUID> firedFor = Collections.synchronizedSet(new LinkedHashSet<>());

    public PlainTrigger(ResourceLocation id) {
        this.id = id;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
        return new Instance(id, player);
    }

    /** Fires the criterion for the player (a no-op for a player who already has the advancement). */
    public void trigger(ServerPlayer player) {
        synchronized (firedFor) {
            firedFor.add(player.getUUID());
            if (firedFor.size() > 64) {
                Iterator<UUID> oldest = firedFor.iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
        }
        this.trigger(player, instance -> true);
    }

    /** Whether the trigger has fired for the player since the server started (or since {@link #forget}). */
    public boolean firedFor(ServerPlayer player) {
        return firedFor.contains(player.getUUID());
    }

    public void forget(ServerPlayer player) {
        firedFor.remove(player.getUUID());
    }

    public static final class Instance extends AbstractCriterionTriggerInstance {
        Instance(ResourceLocation id, ContextAwarePredicate player) {
            super(id, player);
        }
    }
}
