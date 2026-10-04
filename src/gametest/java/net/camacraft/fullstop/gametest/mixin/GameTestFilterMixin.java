package net.camacraft.fullstop.gametest.mixin;

import net.minecraft.gametest.framework.GameTestRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;

/**
 * Runs only some of the tests: {@code gradlew runGameTestServer -PshipTests -PtestOnly=ShipPistonTests,ShipJoinedTests}
 * (test classes' simple names, or single test methods' names, any case) registers nothing else, so a few tests take a
 * minute instead of the whole suite's seven. Dev runs with {@code -PshipTests} only (its mixin config is added just
 * then); without the property everything registers as before. Forge registers its {@code @GameTestHolder} tests
 * through the {@code (Method, Set)} overload, so both are filtered (only the other one was, and nothing was filtered).
 */
@Mixin(GameTestRegistry.class)
public abstract class GameTestFilterMixin {

    @Inject(method = "register(Ljava/lang/reflect/Method;)V", at = @At("HEAD"), cancellable = true)
    private static void fullstop$onlyTheseClasses(Method method, CallbackInfo ci) {
        if (!fullstop$wanted(method)) ci.cancel();
    }

    @Inject(method = "register(Ljava/lang/reflect/Method;Ljava/util/Set;)V", at = @At("HEAD"), cancellable = true,
            require = 0, remap = false) // Forge's own overload: no obfuscated name
    private static void fullstop$onlyTheseClassesForge(Method method, Set<String> namespaces, CallbackInfo ci) {
        if (!fullstop$wanted(method)) ci.cancel();
    }

    private static boolean fullstop$wanted(Method method) {
        String only = System.getProperty("fullstop.test.only");
        if (only == null || only.isBlank()) return true;
        String type = method.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT);
        String name = method.getName().toLowerCase(Locale.ROOT);
        for (String wanted : only.split(",")) {
            String w = wanted.trim().toLowerCase(Locale.ROOT);
            if (w.equals(type) || w.equals(name)) return true;
        }
        return false;
    }
}
