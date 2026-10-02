package net.camacraft.fullstop.gametest.mixin;

import net.minecraft.gametest.framework.GameTestRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Runs only some of the test classes: {@code gradlew runGameTestServer -PshipTests -PtestOnly=ShipPistonTests,ShipJoinedTests}
 * (the classes' simple names, any case) registers nothing else, so a few tests take a minute instead of the whole
 * suite's seven. Dev runs with {@code -PshipTests} only (its mixin config is added just then); without the property
 * everything registers as before.
 */
@Mixin(GameTestRegistry.class)
public abstract class GameTestFilterMixin {

    @Inject(method = "register(Ljava/lang/reflect/Method;)V", at = @At("HEAD"), cancellable = true)
    private static void fullstop$onlyTheseClasses(Method method, CallbackInfo ci) {
        String only = System.getProperty("fullstop.test.only");
        if (only == null || only.isBlank()) return;
        String name = method.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT);
        for (String wanted : only.split(",")) {
            if (wanted.trim().toLowerCase(Locale.ROOT).equals(name)) return;
        }
        ci.cancel();
    }
}
