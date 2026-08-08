package net.camacraft.fullstop;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Arrays;
import java.util.List;

public class FullStopConfig {
    public static final float DEFAULT_SQUASH = 6.90F;
    public static final float DEFAULT_EXPONENTIATION = 1.40F;
    public static final float DEFAULT_MINIMUM_DMG = 0.30F;
    public static final float DEFAULT_MAXIMUM_DMG = Float.MAX_VALUE;
    public static final float DEFAULT_PROJECTILE_MULTIPLIER = 1.00F;
    public static final float DEFAULT_VELOCITY_DAMAGE_THRESHOLD_HORIZONTAL = 12.77f;
    public static final float DEFAULT_VELOCITY_DAMAGE_THRESHOLD_VERTICAL = 12.77F;

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ServerConfigValues SERVER;

    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ClientConfigValues CLIENT;

    static {
        Pair<ServerConfigValues, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(ServerConfigValues::new);
        SERVER_SPEC = pair.getRight();
        SERVER = pair.getLeft();

        Pair<ClientConfigValues, ForgeConfigSpec> clientPair = new ForgeConfigSpec.Builder().configure(ClientConfigValues::new);
        CLIENT_SPEC = clientPair.getRight();
        CLIENT = clientPair.getLeft();
    }

    public static class ServerConfigValues {
        public final ForgeConfigSpec.DoubleValue velocityIncrement;
        public final ForgeConfigSpec.DoubleValue exponentiationConstant;
        public final ForgeConfigSpec.DoubleValue minDamagePercent;
        public final ForgeConfigSpec.DoubleValue maxDamagePercent;
        public final ForgeConfigSpec.DoubleValue projectileMultiplier;
        public final ForgeConfigSpec.BooleanValue projectilesHaveMomentum;
        public final ForgeConfigSpec.BooleanValue wildMode;
        public final ForgeConfigSpec.BooleanValue deathMessageAppend;
        public final ForgeConfigSpec.BooleanValue entityCollisionDamage;
        public final ForgeConfigSpec.BooleanValue kineticBlockBreaking;
        public final ForgeConfigSpec.BooleanValue kineticDoorOpening;
        public final ForgeConfigSpec.BooleanValue kineticButtonPressing;
        public final ForgeConfigSpec.BooleanValue kineticNoteBlocks;
        public final ForgeConfigSpec.BooleanValue kineticBellRinging;
        public final ForgeConfigSpec.BooleanValue sandBlasting;
        public final ForgeConfigSpec.BooleanValue enableGravityBlocks;
        public final ForgeConfigSpec.BooleanValue enableValkyrienSkiesCompat;
        public final ForgeConfigSpec.DoubleValue phaseMinimumSpeed;
        public final ForgeConfigSpec.DoubleValue velocityDamageThresholdHorizontal;
        public final ForgeConfigSpec.DoubleValue velocityDamageThresholdVertical;
        public final ForgeConfigSpec.EnumValue<FallDamageMode> fallDamageMode;
        public final ForgeConfigSpec.DoubleValue kineticDamageMultiplier;
        public final ForgeConfigSpec.BooleanValue hardnessAffectsDamage;
        public final ForgeConfigSpec.DoubleValue minimumSolidImpactDamage;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> entityWeights;
        public final ForgeConfigSpec.EnumValue<RaycastMode> raycastMode;
        public final ForgeConfigSpec.BooleanValue enableGForceEffects;
        public final ForgeConfigSpec.BooleanValue enablePressureSimulation;
        public final ForgeConfigSpec.BooleanValue altitudePressureAffectsMobs;
        public final ForgeConfigSpec.IntValue highAltitudeStartLevel;
        public final ForgeConfigSpec.DoubleValue highAltitudeAirLossRate;
        public final ForgeConfigSpec.IntValue deepWaterStartLevel;
        public final ForgeConfigSpec.DoubleValue deepWaterAirLossMultiplier;
        public final ForgeConfigSpec.IntValue pressureDamageStartDepth;
        public final ForgeConfigSpec.DoubleValue pressureDamageAmount;
        public final ForgeConfigSpec.IntValue pressureDamageTickRate;
        public final ForgeConfigSpec.DoubleValue dripstoneDamageMultiplier;

        protected ServerConfigValues(ForgeConfigSpec.Builder builder) {
            builder.push("General settings");
            this.velocityIncrement = builder
                    .comment("\"Increases\" the necessary velocity to do an arbitrary damage by a factor of this.")
                    .translation(key("velocityIncrement"))
                    .comment("Default: 6.90")
                    .defineInRange("velocityIncrement", DEFAULT_SQUASH, 1, Float.MAX_VALUE);

            this.exponentiationConstant = builder
                    .comment("Changes the power of the damage calculation function. Determines growth curve.")
                    .translation(key("exponentiationConstant"))
                    .comment("Default: 1.40")
                    .defineInRange("exponentiationConstant", DEFAULT_EXPONENTIATION, 0, Float.MAX_VALUE);

            this.minDamagePercent = builder
                    .comment("The minimum amount of damage, as a percentage of the original, that a debuffed attack may do.")
                    .translation(key("minDamagePercent"))
                    .comment("Default: 0.30")
                    .defineInRange("minDamagePercent", DEFAULT_MINIMUM_DMG, 0, 1.0);

            this.maxDamagePercent = builder
                    .comment("The maximum TOTAL damage, as a multiple of the original, that a velocity-buffed attack may deal (e.g. 2.0 caps buffed attacks at double damage).")
                    .translation(key("maxDamagePercent"))
                    .comment("Default: " + Float.MAX_VALUE)
                    .defineInRange("maxDamagePercent", DEFAULT_MAXIMUM_DMG, 0, Float.MAX_VALUE);

            this.deathMessageAppend = builder
                    .comment("When true, adds the velocity to the death message")
                    .translation(key("deathMessageAppend"))
                    .comment("Default: true")
                    .define("deathMessageAppend", true);

            this.enableGForceEffects = builder
                    .comment("When true, enables visual effects (vignette and blackout) when experiencing high G-forces")
                    .translation("config.fullstop.server.enableGForceEffects")
                    .comment("Default: true")
                    .define("enableGForceEffects", true);

            this.enableGravityBlocks = builder
                    .comment("When true, blocks tagged fullstop:gravity_affected (slime and honey by default) fall like sand when nothing supports them.",
                            "Blocks also tagged fullstop:sticky cling to any touching block, not just the one below.")
                    .translation(key("enableGravityBlocks"))
                    .comment("Default: true")
                    .define("enableGravityBlocks", true);

            this.enableValkyrienSkiesCompat = builder
                    .comment("When true and Valkyrien Skies is installed, FullStop compensates for ship motion",
                            "(no phantom impact damage while riding ships) and recognizes impacts against ship blocks.",
                            "Turn off to test FullStop behavior as if Valkyrien Skies were not installed.")
                    .translation(key("enableValkyrienSkiesCompat"))
                    .comment("Default: true")
                    .define("enableValkyrienSkiesCompat", true);

            builder.pop();
            builder.push("Projectile settings");

            this.projectileMultiplier = builder
                    .comment("Projectile speeds (IN CALCULATIONS) are subtracted by this percentage of the original value. Set to 0 for crazy damage.")
                    .translation(key("projectileMultiplier"))
                    .comment("Default: 1.00")
                    .defineInRange("projectileMultiplier", DEFAULT_PROJECTILE_MULTIPLIER, 0, 1.00);

            this.projectilesHaveMomentum = builder
                    .comment("If true, entities who fire a projectile have their velocity applied to the projectile")
                    .translation(key("projectilesHaveMomentum"))
                    .comment("Default: false")
                    .define("projectilesHaveMomentum", false);

            this.wildMode = builder
                    .comment("When true, arrows go through FullStop's velocity damage re-scaling (stacked on vanilla's own arrow speed bonus).",
                            "When false, arrows keep plain vanilla damage; FullStop's armor mitigation layers still apply either way.")
                    .translation(key("wildMode"))
                    .comment("Default: true")
                    .define("wildMode", true);

            builder.pop();
            builder.push("Kinetic Damage settings");

            this.velocityDamageThresholdHorizontal = builder
                    .comment("This value determines how fast an entity must be moving in order to apply kinetic damage horizontally. Very low values may be unplayable!")
                    .translation(key("velocityDamageThresholdHorizontal"))
                    .comment("Default: 12.77")
                    .defineInRange("velocityDamageThresholdHorizontal", DEFAULT_VELOCITY_DAMAGE_THRESHOLD_HORIZONTAL, 0, 100);

            this.velocityDamageThresholdVertical = builder
                    .comment("This value determines how fast an entity must be moving in order to apply kinetic damage vertically. Very low values may be unplayable!")
                    .translation(key("velocityDamageThresholdVertical"))
                    .comment("Default: 12.77")
                    .defineInRange("velocityDamageThresholdVertical", DEFAULT_VELOCITY_DAMAGE_THRESHOLD_VERTICAL, 0, 100);

            this.fallDamageMode = builder
                    .comment("How kinetic impact damage is computed.",
                            "KINETIC: FullStop's own model. Damage grows with the speed lost beyond the thresholds above and scales with the hardness of what you hit.",
                            "VANILLA_PARITY: an impact is converted to the vanilla fall distance that produces the same impact speed and deals vanilla fall damage ((distance - 3) points, Jump Boost included) - a 20-block fall onto grass hurts exactly like vanilla. Wall and ceiling impacts use the same conversion. In this mode the two thresholds above, block hardness and minimumSolidImpactDamage are ignored.",
                            "Both modes: soft blocks (fullstop:soft_landing / fullstop:cushioning), belly flops, Feather Falling and armor still apply.")
                    .translation(key("fallDamageMode"))
                    .comment("Default: KINETIC")
                    .defineEnum("fallDamageMode", FallDamageMode.KINETIC);

            this.kineticDamageMultiplier = builder
                    .comment("Global multiplier applied to all kinetic impact damage (falls, wall crashes, ceiling hits) after every other modifier, in both fall damage modes.",
                            "0.5 = half damage, 2.0 = double. Does not affect velocity-scaled ATTACK damage (see General settings).")
                    .translation(key("kineticDamageMultiplier"))
                    .comment("Default: 1.0")
                    .defineInRange("kineticDamageMultiplier", 1.0, 0.0, 100.0);

            this.hardnessAffectsDamage = builder
                    .comment("KINETIC mode only: when true, the hardness of the block you hit scales the damage - soft blocks (dirt, sand) hurt less than hard ones (stone, obsidian).",
                            "When false, every block hits equally hard.")
                    .translation(key("hardnessAffectsDamage"))
                    .comment("Default: true")
                    .define("hardnessAffectsDamage", true);

            this.minimumSolidImpactDamage = builder
                    .comment("KINETIC mode only: minimum damage for any over-threshold impact against a solid block, so a genuine overspeed landing is never completely free even on soft or fragile blocks.",
                            "Set to 0 to disable the floor.")
                    .translation(key("minimumSolidImpactDamage"))
                    .comment("Default: 2.0")
                    .defineInRange("minimumSolidImpactDamage", 2.0, 0.0, 100.0);

            this.entityCollisionDamage = builder
                    .comment("When enabled, colliding with entities will cause kinetic damage. This is a highly experimental feature, use at your own risk!")
                    .translation(key("entityCollisionDamage"))
                    .comment("Default: false")
                    .define("entityCollisionDamage", false);

            this.kineticBlockBreaking = builder
                    .comment("When enabled, entities hitting blocks at high speeds can crack or break them.")
                    .translation(key("kineticBlockBreaking"))
                    .comment("Default: true")
                    .define("kineticBlockBreaking", true);

            this.kineticDoorOpening = builder
                    .comment("When enabled, sprinting into doors, trapdoors and fence gates flings them open.")
                    .translation(key("kineticDoorOpening"))
                    .comment("Default: true")
                    .define("kineticDoorOpening", true);

            this.kineticButtonPressing = builder
                    .comment("When enabled, running into a button (or falling onto a floor button) presses it.")
                    .translation(key("kineticButtonPressing"))
                    .comment("Default: true")
                    .define("kineticButtonPressing", true);

            this.kineticNoteBlocks = builder
                    .comment("When enabled, colliding with a note block plays its note.")
                    .translation(key("kineticNoteBlocks"))
                    .comment("Default: true")
                    .define("kineticNoteBlocks", true);

            this.kineticBellRinging = builder
                    .comment("When enabled, colliding with a bell rings it.")
                    .translation(key("kineticBellRinging"))
                    .comment("Default: true")
                    .define("kineticBellRinging", true);

            this.sandBlasting = builder
                    .comment("When enabled, falling sand strips logs and deoxidizes/dewaxes copper it lands on (sand blasting).")
                    .translation(key("sandBlasting"))
                    .comment("Default: true")
                    .define("sandBlasting", true);

            this.phaseMinimumSpeed = builder
                    .comment("Speed in m/s a living entity must be moving to phase into blocks tagged fullstop:phaseable.",
                            "Fast movers pass through leaves without breaking them and get embedded in engulfing blocks (sand, gravel, snow).")
                    .translation(key("phaseMinimumSpeed"))
                    .comment("Default: 15.0")
                    .defineInRange("phaseMinimumSpeed", 15.0, 1.0, 1000.0);
            
            this.dripstoneDamageMultiplier = builder
                    .comment("Multiplier for damage when falling on pointed dripstone.")
                    .translation(key("dripstoneDamageMultiplier"))
                    .defineInRange("dripstoneDamageMultiplier", 2.0, 1.0, 100.0);

            this.entityWeights = builder
                    .comment("Define custom weights (multipliers) for specific entities. Format: \"entity_id,multiplier\"")
                    .translation(key("entityWeights"))
                    .defineList("entityWeights", Arrays.asList(
                            "minecraft:iron_golem,8.0",
                            "minecraft:skeleton,0.333",
                            "minecraft:skeleton_horse,0.333",
                            "minecraft:ghast,0.125"
                    ), o -> o instanceof String);

            this.raycastMode = builder
                    .comment("Experimental: Choose the raycast mode for collision detection. FULL_SWEEP may have significant performance impact.")
                    .translation(key("raycastMode"))
                    .defineEnum("raycastMode", RaycastMode.CORNERS_AND_CENTERS);

            builder.pop();
            builder.push("Pressure Simulation");

            this.enablePressureSimulation = builder
                    .comment("Enables atmospheric and underwater pressure effects.")
                    .translation(key("enablePressureSimulation"))
                    .define("enablePressureSimulation", true);

            this.altitudePressureAffectsMobs = builder
                    .comment("When true, non-player mobs also lose air at high altitudes. Beware: ordinary mountain mobs can suffocate if the start level is low.")
                    .translation(key("altitudePressureAffectsMobs"))
                    .define("altitudePressureAffectsMobs", false);

            this.highAltitudeStartLevel = builder
                    .comment("The Y-level where players start losing air due to thin atmosphere. Note that 1.20 jagged peaks can approach Y=250.")
                    .translation(key("highAltitudeStartLevel"))
                    .defineInRange("highAltitudeStartLevel", 200, -64, 320);

            this.highAltitudeAirLossRate = builder
                    .comment("Multiplier for air loss rate at high altitudes. Higher is faster.")
                    .translation(key("highAltitudeAirLossRate"))
                    .defineInRange("highAltitudeAirLossRate", 0.5, 0.0, 10.0);

            this.deepWaterStartLevel = builder
                    .comment("The Y-level below which entities lose air faster underwater.")
                    .translation(key("deepWaterStartLevel"))
                    .defineInRange("deepWaterStartLevel", 0, -64, 320);

            this.deepWaterAirLossMultiplier = builder
                    .comment("Extra air lost per tick (on top of vanilla drowning) below the deep water start level.")
                    .translation(key("deepWaterAirLossMultiplier"))
                    .defineInRange("deepWaterAirLossMultiplier", 2.0, 1.0, 20.0);

            this.pressureDamageStartDepth = builder
                    .comment("The Y-level where entities start taking pressure damage underwater.")
                    .translation(key("pressureDamageStartDepth"))
                    .defineInRange("pressureDamageStartDepth", -32, -64, 0);

            this.pressureDamageAmount = builder
                    .comment("Amount of damage dealt by underwater pressure.")
                    .translation(key("pressureDamageAmount"))
                    .defineInRange("pressureDamageAmount", 2.0, 0.0, 100.0);

            this.pressureDamageTickRate = builder
                    .comment("How often (in ticks) pressure damage is applied. 20 ticks = 1 second.")
                    .translation(key("pressureDamageTickRate"))
                    .defineInRange("pressureDamageTickRate", 40, 1, 200);

            builder.pop();
        }

        private static String key(String valueName) {
            return "config.fullstop." + valueName;
        }
    }

    public static class ClientConfigValues {
        public final ForgeConfigSpec.BooleanValue rotateCamera;
        public final ForgeConfigSpec.BooleanValue rotateCameraOnlyWhenFlying;
        public final ForgeConfigSpec.DoubleValue minGForceThreshold;
        public final ForgeConfigSpec.DoubleValue maxGForceThreshold;

        protected ClientConfigValues(ForgeConfigSpec.Builder builder) {
            builder.push("Client settings");
            this.rotateCamera = builder
                    .comment("When true, the camera turns to follow the rebound when bouncing off a bouncy block (slime, beds).",
                            "Pitch (looking up/down) only follows during Elytra flight; on foot only yaw turns.")
                    .translation("config.fullstop.client.rotateCamera")
                    .comment("Default: true")
                    .define("rotateCamera", true);

            this.rotateCameraOnlyWhenFlying = builder
                    .comment("When true, bounce camera rotation only happens while flying with an Elytra")
                    .translation("config.fullstop.client.rotateCameraOnlyWhenFlying")
                    .comment("Default: false")
                    .define("rotateCameraOnlyWhenFlying", false);

            this.minGForceThreshold = builder
                    .comment("The G-force value at which visual effects start to appear")
                    .translation("config.fullstop.client.minGForceThreshold")
                    .comment("Default: 2.5")
                    .defineInRange("minGForceThreshold", 2.5, 0.0, 100.0);

            this.maxGForceThreshold = builder
                    .comment("The G-force value at which visual effects reach maximum intensity")
                    .translation("config.fullstop.client.maxGForceThreshold")
                    .comment("Default: 7.0")
                    .defineInRange("maxGForceThreshold", 7.0, 0.0, 100.0);

            builder.pop();
        }
    }

    public enum RaycastMode {
        CORNERS_ONLY,
        CORNERS_AND_CENTERS,
        FULL_SWEEP
    }

    public enum FallDamageMode {
        /** FullStop's stopping-force model: thresholds, block hardness, the works. */
        KINETIC,
        /** Impacts deal what vanilla would for the equivalent fall distance. */
        VANILLA_PARITY
    }
}
