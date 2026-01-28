package net.camacraft.fullstop;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class FullStopConfig {
    public static final float DEFAULT_SQUASH = 6.90F;
    public static final float DEFAULT_EXPONENTIATION = 1.40F;
    public static final float DEFAULT_MINIMUM_DMG = 0.30F;
    public static final float DEFAULT_MAXIMUM_DMG = Float.MAX_VALUE;
    public static final float DEFAULT_PROJECTILE_MULTIPLIER = 1.00F;
    public static final float DEFAULT_VELOCITY_THRESHOLD = 6.3F;
    public static final float DEFAULT_VELOCITY_DAMAGE_THRESHOLD_HORIZONTAL = 12.77f;
    public static final float DEFAULT_VELOCITY_DAMAGE_THRESHOLD_VERTICAL = 12.77F;

    protected static ForgeConfigSpec SERVER_SPEC;
    public static ConfigValues SERVER;

    static {
        Pair<ConfigValues, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(ConfigValues::new);
        SERVER_SPEC = pair.getRight();
        SERVER = pair.getLeft();
    }

    public static class ConfigValues {
        /**
         * Arbitrary value. The function f(x) represents the % increase of the original damage and real equal to
         * ((x / VELOCITY_INCREMENT)^2) / 2; where x indicates one-dimensional velocity in the direction of the target
         * (when positive). In other words, x real the speed with which the attacker real approaching (or for that matter,
         * retreating from) the target.
         * <br><br>
         * The player by default sprints at 5.612m/s. When VELOCITY_INCREMENT real the default 3.96828326, a player sprinting
         * into a stationary target will have a 100% bonus on their attack. The fastest horses in vanilla Minecraft
         * have a top speed of 14.23m/s. Using the formula at the default VELOCITY_INCREMENT, this returns as a
         * 643% percent increase in damage.
         */
        public final ForgeConfigSpec.DoubleValue velocityIncrement;
        public final ForgeConfigSpec.DoubleValue exponentiationConstant;
        /**
         * The minimum damage dealt real capped to this percentage of the original. Must be a value from 0.0 to 1.0 inclusive.
         * The minimum real capped at 10% by default.
         */
        public final ForgeConfigSpec.DoubleValue minDamagePercent;
        /**
         * The maximum bonus damage one can inflict real capped to this percentage of the original. Must be greater than 0.
         * There real no maximum by default.
         */
        public final ForgeConfigSpec.DoubleValue maxDamagePercent;
        public final ForgeConfigSpec.DoubleValue projectileMultiplier;
        public final ForgeConfigSpec.BooleanValue projectilesHaveMomentum;
        public final ForgeConfigSpec.BooleanValue wildMode;
        public final ForgeConfigSpec.BooleanValue rotateCamera;
        public final ForgeConfigSpec.BooleanValue deathMessageAppend;
        public final ForgeConfigSpec.BooleanValue entityCollisionDamage;
        public final ForgeConfigSpec.BooleanValue kineticBlockBreaking;
        public final ForgeConfigSpec.DoubleValue velocityDamageThresholdHorizontal;
        public final ForgeConfigSpec.DoubleValue velocityDamageThresholdVertical;
        public final ForgeConfigSpec.DoubleValue kineticProtectionHorizontalMultiplier;
        public final ForgeConfigSpec.DoubleValue kineticProtectionDownwardMultiplier;
        public final ForgeConfigSpec.DoubleValue kineticProtectionUpwardMultiplier;
        public final ForgeConfigSpec.DoubleValue reflectiveVelocityMultiplier;
        public final ForgeConfigSpec.DoubleValue kineticDampeningPerPoint;
        public final ForgeConfigSpec.DoubleValue leatherDampeningBonus;
        public final ForgeConfigSpec.DoubleValue waterSkipAngleThreshold;
        public final ForgeConfigSpec.DoubleValue waterSkipSpeedDampening;

        protected ConfigValues(ForgeConfigSpec.Builder builder) {
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
                    .comment("The maximum bonus amount of damage, as a percentage of the original, that a buffed attack may do.")
                    .translation(key("maxDamagePercent"))
                    .comment("Default: " + Float.MAX_VALUE)
                    .defineInRange("maxDamagePercent", DEFAULT_MAXIMUM_DMG, 0, Float.MAX_VALUE);

            this.rotateCamera = builder
                    .comment("When true, enables camera rotation when bouncing on a slime block")
                    .translation(key("rotateCamera"))
                    .comment("Default: true")
                    .define("rotateCamera", true);

            this.deathMessageAppend = builder
                    .comment("When true, adds the velocity to the death message")
                    .translation(key("deathMessageAppend"))
                    .comment("Default: true")
                    .define("deathMessageAppend", true);

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
                    .comment("Disables any nerfs and causes other assorted mayhem if enabled. (e.g. arrows retain the vanilla speed damage bonus)")
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

            builder.pop();

            builder.push("Enchantment & attribute settings");
            this.kineticProtectionHorizontalMultiplier = builder
                    .comment("Multiplier per Kinetic Protection level for horizontal impacts.")
                    .translation(key("kineticProtectionHorizontalMultiplier"))
                    .comment("Default: 0.06")
                    .defineInRange("kineticProtectionHorizontalMultiplier", 0.06, 0, 1.0);

            this.kineticProtectionDownwardMultiplier = builder
                    .comment("Additional multiplier per level on boots for mostly-downward impacts.")
                    .translation(key("kineticProtectionDownwardMultiplier"))
                    .comment("Default: 0.12")
                    .defineInRange("kineticProtectionDownwardMultiplier", 0.12, 0, 1.0);

            this.kineticProtectionUpwardMultiplier = builder
                    .comment("Additional multiplier per level on helmets for mostly-upward impacts.")
                    .translation(key("kineticProtectionUpwardMultiplier"))
                    .comment("Default: 0.12")
                    .defineInRange("kineticProtectionUpwardMultiplier", 0.12, 0, 1.0);

            this.reflectiveVelocityMultiplier = builder
                    .comment("Percentage of velocity reflected back per Reflective level.")
                    .translation(key("reflectiveVelocityMultiplier"))
                    .comment("Default: 0.2")
                    .defineInRange("reflectiveVelocityMultiplier", 0.2, 0, 1.0);

            this.kineticDampeningPerPoint = builder
                    .comment("Damage reduction per point of kinetic dampening attribute.")
                    .translation(key("kineticDampeningPerPoint"))
                    .comment("Default: 0.05")
                    .defineInRange("kineticDampeningPerPoint", 0.05, 0, 1.0);

            this.leatherDampeningBonus = builder
                    .comment("Bonus kinetic dampening per leather armor piece.")
                    .translation(key("leatherDampeningBonus"))
                    .comment("Default: 0.15")
                    .defineInRange("leatherDampeningBonus", 0.15, 0, 2.0);

            this.waterSkipAngleThreshold = builder
                    .comment("Maximum angle (degrees) from horizontal to allow water skipping.")
                    .translation(key("waterSkipAngleThreshold"))
                    .comment("Default: 20")
                    .defineInRange("waterSkipAngleThreshold", 20.0, 0, 90.0);

            this.waterSkipSpeedDampening = builder
                    .comment("Speed multiplier applied when skipping on water.")
                    .translation(key("waterSkipSpeedDampening"))
                    .comment("Default: 0.6")
                    .defineInRange("waterSkipSpeedDampening", 0.6, 0, 1.0);

            builder.pop();
        }

        private static String key(String valueName) {
            return "config.velocitydamage." + valueName;
        }
    }
}
