package net.camacraft.fullstop.common.compat.create.kinetic;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The spin of Camafy's real Create flywheel ({@code net.camacraft.camafy.compat.create.FlywheelSpin#camafy$spin}, Camafy
 * 0.1.36 and later): a coasting flywheel turns at its own speed, not its network's. Looked up by name once, so Full Stop
 * keeps building against its older Camafy; 0 when that Camafy (or its flywheels) isn't there.
 */
final class FlywheelSpins {

    @Nullable
    private static final Class<?> TYPE;
    @Nullable
    private static final MethodHandle SPIN;

    static {
        Class<?> type = null;
        MethodHandle spin = null;
        try {
            type = Class.forName("net.camacraft.camafy.compat.create.FlywheelSpin");
            spin = MethodHandles.publicLookup().findVirtual(type, "camafy$spin", MethodType.methodType(double.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            type = null;
            spin = null;
        }
        TYPE = type;
        SPIN = spin;
    }

    private FlywheelSpins() {
    }

    /** The wheel's own spin (RPM, signed as Create's speed), 0 when unknown. */
    static double spin(@Nullable Object blockEntity) {
        if (SPIN == null || TYPE == null || !TYPE.isInstance(blockEntity)) return 0.0;
        try {
            double rpm = (double) SPIN.invoke(blockEntity);
            return Double.isFinite(rpm) ? rpm : 0.0;
        } catch (Throwable e) {
            return 0.0;
        }
    }
}
