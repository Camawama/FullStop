package net.camacraft.fullstop.common.compat.create.kinetic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One spinning part of Create that can catch a body: where it is (a world block, or a block of a
 * Valkyrien Skies ship by its shipyard position) and what kind it is. Found by
 * {@link HazardScanner#scan} a few times a second per body nearby; {@link HazardScanner#resolve}
 * turns it into a {@link Live} every tick (where it is now, how fast it turns).
 *
 * @param kind   what sort of part
 * @param pos    its block (shipyard coordinates on a ship)
 * @param shipId the ship it's on, or {@link #NO_SHIP}
 */
record Hazard(Kind kind, BlockPos pos, long shipId) {

    static final long NO_SHIP = Long.MIN_VALUE;

    /**
     * The parts and their reach, in blocks at full size: {@code radius} is where the teeth or
     * paddles end (from Create's own geometry: a cogwheel's teeth reach about 9/16 from its axis, a
     * large cogwheel's 1 1/8, its particles' target radius; a water wheel's paddles reach into the
     * cells Create reads its flow from, the four beside it, a large one's into the ring two out; Create's flywheel's
     * rim reaches 1.37 from its axis, its model 2.75 blocks across, well past the block it stands in, and it acts like
     * a single gear's teeth by its rim's speed, never in a nip; a shaft is a smooth pole of radius 3/16 that only drags
     * what stands on it along its turning surface, {@code surface});
     * {@code halfWidth} is half the disc's thickness (a cogwheel is 4/16 thick, a wheel a block, a shaft's whole block).
     */
    enum Kind {
        COGWHEEL(0.5625, 0.16, false),
        LARGE_COGWHEEL(1.125, 0.16, false),
        WATER_WHEEL(0.9, 0.5, true),
        LARGE_WATER_WHEEL(1.75, 0.5, true),
        FLYWHEEL(1.37, 0.375, false),
        SHAFT(0.1875, 0.5, false, true);

        final double radius;
        final double halfWidth;
        final boolean wheel;
        /** Only a surface to stand on: it drags what rests on it, never pushes off, bites or flings. */
        final boolean surface;

        Kind(double radius, double halfWidth, boolean wheel) {
            this(radius, halfWidth, wheel, false);
        }

        Kind(double radius, double halfWidth, boolean wheel, boolean surface) {
            this.radius = radius;
            this.halfWidth = halfWidth;
            this.wheel = wheel;
            this.surface = surface;
        }

        /** A toothed gear: the only parts that mesh into a nip. */
        boolean gear() {
            return this == COGWHEEL || this == LARGE_COGWHEEL;
        }

        boolean large() {
            return this == LARGE_COGWHEEL || this == LARGE_WATER_WHEEL;
        }
    }

    /**
     * A part as it is this tick, in world terms.
     *
     * @param center    its axis' centre point
     * @param axis      its axis, a unit vector; it turns by {@code omega} about it (right-handed)
     * @param radius    tooth or paddle reach (scaled with its ship)
     * @param halfWidth half its thickness (scaled with its ship)
     * @param omega     signed turn per tick about {@code axis}, radians, right-handed: Create's RPM x 2pi/60/20
     *                  (Create's positive speed is a right-handed turn about the axis' positive direction, as drawn)
     * @param rpm       Create's speed, RPM (signed; 0 while its network is overstressed)
     * @param base      velocity of the ground it stands on at its centre, blocks per tick (a ship's)
     * @param driveRpm  the speed its network drives it at, RPM (signed), overstressed or not (Create's theoretical
     *                  speed): which way a jammed pair of gears would turn
     * @param stalled   its network is overstressed (it stands still though driven)
     * @param gridAxis  its axis in its own block grid (the world's, or its ship's)
     */
    record Live(Hazard hazard, Vec3 center, Vec3 axis, double radius, double halfWidth, double omega, double rpm,
                Vec3 base, double driveRpm, boolean stalled, net.minecraft.core.Direction.Axis gridAxis) {

        /** Turning at {@code minRpm} or faster right now (not stalled). */
        boolean turning(double minRpm) {
            return rpm != 0.0 && Math.abs(rpm) >= minRpm;
        }
    }
}
