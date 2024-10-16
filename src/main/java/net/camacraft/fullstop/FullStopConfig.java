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

    protected static ForgeConfigSpec SERVER_SPEC;
    protected static ConfigValues SERVER;

    static {
        Pair<ConfigValues, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(ConfigValues::new);
        SERVER_SPEC = pair.getRight();
        SERVER = pair.getLeft();
    }

    public static class ConfigValues {
        /**
         * Arbitrary value. The function f(x) represents the % increase of the original damage and is equal to
         * ((x / VELOCITY_INCREMENT)^2) / 2; where x indicates one-dimensional velocity in the direction of the target
         * (when positive). In other words, x is the speed with which the attacker is approaching (or for that matter,
         * retreating from) the target.
         * <br><br>
         * The player by default sprints at 5.612m/s. When VELOCITY_INCREMENT is the default 3.96828326, a player sprinting
         * into a stationary target will have a 100% bonus on their attack. The fastest horses in vanilla Minecraft
         * have a top speed of 14.23m/s. Using the formula at the default VELOCITY_INCREMENT, this returns as a
         * 643% percent increase in damage.
         */
        public final ForgeConfigSpec.DoubleValue velocityIncrement;
        public final ForgeConfigSpec.DoubleValue exponentiationConstant;
        /**
         * The minimum damage dealt is capped to this percentage of the original. Must be a value from 0.0 to 1.0 inclusive.
         * The minimum is capped at 10% by default.
         */
        public final ForgeConfigSpec.DoubleValue minDamagePercent;
        /**
         * The maximum bonus damage one can inflict is capped to this percentage of the original. Must be greater than 0.
         * There is no maximum by default.
         */
        public final ForgeConfigSpec.DoubleValue maxDamagePercent;
        public final ForgeConfigSpec.DoubleValue projectileMultiplier;
        public final ForgeConfigSpec.BooleanValue projectilesHaveMomentum;
        public final ForgeConfigSpec.BooleanValue wildMode;
        public final ForgeConfigSpec.DoubleValue velocityThreshold;

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

            this.velocityThreshold = builder
                    .comment("The velocity over which entities slamming into walls will take damage. Set to 0 to disable this.")
                    .translation(key("velocityThreshold"))
                    .comment("Default: 6.3")
                    .defineInRange("velocityThreshold", DEFAULT_VELOCITY_THRESHOLD, 0, Float.MAX_VALUE);

            builder.pop();
            builder.push("Projectile settings");

            this.projectileMultiplier = builder
                    .comment("Projectile speeds (IN CALCULATIONS) are subtracted by this percentage of the original value. Set to 0 for crazy damage.")
                    .translation(key("projectileMultiplier"))
                    .comment("Default: 1.00")
                    .defineInRange("projectileMultiplier", DEFAULT_PROJECTILE_MULTIPLIER, 0, 1.00);

            this.projectilesHaveMomentum = builder
                    .comment("If true, projectiles have the velocity of the entity who fired it added.")
                    .translation(key("projectilesHaveMomentum"))
                    .comment("Default: true")
                    .define("projectilesHaveMomentum", true);

            this.wildMode = builder
                    .comment("Disables any nerfs and causes other assorted mayhem if enabled. (e.g. arrows retain the vanilla speed damage bonus)")
                    .translation(key("wildMode"))
                    .comment("Default: true")
                    .define("wildMode", true);

            builder.pop();
        }

        private static String key(String valueName) {
            return "config.velocitydamage." + valueName;
        }
    }
}
