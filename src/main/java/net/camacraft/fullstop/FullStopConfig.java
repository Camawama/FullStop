package net.camacraft.fullstop;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class FullStopConfig {
    public static final float DEFAULT_SQUASH = 6.90F;
    public static final float DEFAULT_EXPONENTIATION = 1.40F;
    public static final float DEFAULT_MINIMUM_DMG = 0.30F;
    public static final float DEFAULT_MAXIMUM_DMG = Float.MAX_VALUE;
    public static final float DEFAULT_PROJECTILE_MULTIPLIER = 1.00F;
    // Reference speeds (m/s): walk 4.3, sprint 5.6, sprint-jump peak 9.6 (the
    // jump adds a flat +4), Speed IV sprint 10.1, Speed V sprint 11.2. 10.5
    // keeps every unboosted movement safe with margin while Speed V+ runs and
    // any real launch (elytra, slime cannon) hurt. The old 12.77 sat exactly in
    // the sprint-jump band: Speed-boosted GROUND runs stayed under it while the
    // same run plus a jump crossed it — which read as "wall damage only works
    // when my feet leave the ground".
    public static final float DEFAULT_VELOCITY_DAMAGE_THRESHOLD_HORIZONTAL = 10.5f;
    // Just above the impact speed of a 3-block fall (12.3 m/s), vanilla's safe-fall limit.
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
        public final ForgeConfigSpec.BooleanValue enableGravityUnboundCompat;
        public final ForgeConfigSpec.DoubleValue phaseMinimumSpeed;
        public final ForgeConfigSpec.DoubleValue velocityDamageThresholdHorizontal;
        public final ForgeConfigSpec.DoubleValue velocityDamageThresholdVertical;
        public final ForgeConfigSpec.EnumValue<FallDamageMode> fallDamageMode;
        public final ForgeConfigSpec.DoubleValue kineticDamageMultiplier;
        public final ForgeConfigSpec.BooleanValue hardnessAffectsDamage;
        public final ForgeConfigSpec.DoubleValue minimumSolidImpactDamage;
        public final ForgeConfigSpec.IntValue crackHealSeconds;
        public final ForgeConfigSpec.EnumValue<RaycastMode> raycastMode;
        public final ForgeConfigSpec.BooleanValue debugImpactLog;
        public final ForgeConfigSpec.BooleanValue enableGForceEffects;
        public final ForgeConfigSpec.DoubleValue minGForceThreshold;
        public final ForgeConfigSpec.DoubleValue maxGForceThreshold;
        public final ForgeConfigSpec.DoubleValue spinNauseaThreshold;
        public final ForgeConfigSpec.DoubleValue spinNauseaTurns;
        public final ForgeConfigSpec.DoubleValue minimumFallDistance;
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
        public final ForgeConfigSpec.IntValue configVersion;

        // Ship Weight (from WeightRS; the key names are WeightRS's so its settings import)
        public final ForgeConfigSpec.BooleanValue enableShipWeight;
        public final ForgeConfigSpec.BooleanValue includePlayers;
        public final ForgeConfigSpec.DoubleValue gravity;
        public final ForgeConfigSpec.DoubleValue minPhysicsWeightKg;
        public final ForgeConfigSpec.BooleanValue enableItemEntityPhysics;
        public final ForgeConfigSpec.BooleanValue enableStandingWeight;
        public final ForgeConfigSpec.BooleanValue enableJumpImpulse;
        public final ForgeConfigSpec.BooleanValue enableFallImpact;
        public final ForgeConfigSpec.DoubleValue jumpImpulseMultiplier;
        public final ForgeConfigSpec.DoubleValue fallImpactMultiplier;
        public final ForgeConfigSpec.DoubleValue minFallDistance;
        public final ForgeConfigSpec.DoubleValue maxForceNewtons;

        // Ship Collisions
        public final ForgeConfigSpec.BooleanValue enableShipCollisions;
        public final ForgeConfigSpec.BooleanValue solidHulls;
        public final ForgeConfigSpec.BooleanValue entityImpacts;
        public final ForgeConfigSpec.BooleanValue damageEntities;
        public final ForgeConfigSpec.DoubleValue pushMinSpeed;
        public final ForgeConfigSpec.DoubleValue hullFriction;
        public final ForgeConfigSpec.BooleanValue debugShipContactLog;
        public final ForgeConfigSpec.DoubleValue hitLift;
        public final ForgeConfigSpec.DoubleValue entityDamageMultiplier;
        public final ForgeConfigSpec.DoubleValue entityRestitution;
        public final ForgeConfigSpec.DoubleValue pinnedDamageMultiplier;
        public final ForgeConfigSpec.BooleanValue creditPilot;
        public final ForgeConfigSpec.BooleanValue reactionImpulses;
        public final ForgeConfigSpec.BooleanValue breakTerrain;
        public final ForgeConfigSpec.BooleanValue hullDamage;
        public final ForgeConfigSpec.DoubleValue massInfluence;
        public final ForgeConfigSpec.DoubleValue breakMinSpeed;
        public final ForgeConfigSpec.DoubleValue terrainEnergyFraction;
        public final ForgeConfigSpec.DoubleValue breakCostMultiplier;
        public final ForgeConfigSpec.DoubleValue terrainBreakDrag;
        public final ForgeConfigSpec.IntValue terrainMaxBlocksPerTick;
        public final ForgeConfigSpec.BooleanValue terrainBlockDrops;
        public final ForgeConfigSpec.BooleanValue terrainBreakBlockEntities;
        public final ForgeConfigSpec.BooleanValue unpilotedFollowsMobGriefing;
        public final ForgeConfigSpec.EnumValue<ShipVsShipBreaking> shipVsShipBreaking;
        public final ForgeConfigSpec.BooleanValue slimeBounce;
        public final ForgeConfigSpec.DoubleValue slimeRestitution;
        public final ForgeConfigSpec.DoubleValue slimeMinSpeed;
        public final ForgeConfigSpec.BooleanValue honeyGlue;
        public final ForgeConfigSpec.DoubleValue honeyGlueStrength;
        public final ForgeConfigSpec.DoubleValue honeySoftening;
        public final ForgeConfigSpec.BooleanValue sweepVegetation;
        public final ForgeConfigSpec.DoubleValue sweepMinSpeed;
        public final ForgeConfigSpec.BooleanValue sweepPlacedLeaves;
        public final ForgeConfigSpec.DoubleValue sweepDrag;
        public final ForgeConfigSpec.IntValue sweepMaxBlocksPerTick;
        public final ForgeConfigSpec.BooleanValue impactSounds;
        public final ForgeConfigSpec.DoubleValue impactSoundMinSpeed;
        public final ForgeConfigSpec.DoubleValue impactSoundVolume;
        public final ForgeConfigSpec.BooleanValue impactParticles;
        public final ForgeConfigSpec.IntValue newShipGraceTicks;
        public final ForgeConfigSpec.IntValue maxHitChecksPerTick;
        public final ForgeConfigSpec.BooleanValue debugShipCollisionLog;

        // Ship Riders
        public final ForgeConfigSpec.BooleanValue deckMomentum;
        public final ForgeConfigSpec.DoubleValue deckGrip;
        public final ForgeConfigSpec.DoubleValue deckThrowLift;
        public final ForgeConfigSpec.BooleanValue deckMomentumSeated;
        public final ForgeConfigSpec.BooleanValue releaseLeavingRiders;

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

            this.minGForceThreshold = builder
                    .comment("Sustained felt acceleration (m/s per tick above the pull of local gravity everyone feels at rest: 1 g",
                            "normally, a sixth on the Moon, none in zero gravity, where the acceleration itself is felt) at which the",
                            "g-force effects start: tunnel vision, muffled audio. Gameplay-relevant, so it is a SERVER setting:",
                            "the Clarity and Vertigo potions scale it per player, clients cannot.",
                            "Reference: a hard elytra pull-out is about 3, a firework kick briefly 15+, a slime launch 20+.")
                    .translation("config.fullstop.server.minGForceThreshold")
                    .comment("Default: 2.5")
                    .defineInRange("minGForceThreshold", 2.5, 0.0, 100.0);

            this.maxGForceThreshold = builder
                    .comment("Sustained felt acceleration at which the blackout is complete: the screen goes black and the",
                            "player is BLINDED (a G-LOC that outlasts the g-force itself), with nausea that lingers after the",
                            "blindness fades. Nausea alone starts halfway between the two thresholds.")
                    .translation("config.fullstop.server.maxGForceThreshold")
                    .comment("Default: 7.0")
                    .defineInRange("maxGForceThreshold", 7.0, 0.0, 100.0);

            this.spinNauseaThreshold = builder
                    .comment("Turning rate (degrees per tick, averaged over about a second) past which spinning starts to make a",
                            "player dizzy - the view spun round and round, or running in tight circles at speed. Only turning FASTER",
                            "than this builds dizziness, and it drains again as soon as you slow down or stop (see spinNauseaTurns):",
                            "15 is 300 degrees a second, almost a full turn every second. Normal mouse-look, quick 180s and even a few",
                            "fast spins never make you dizzy. Clarity/Vertigo scale it. 0 disables spin nausea.")
                    .translation("config.fullstop.server.spinNauseaThreshold")
                    .comment("Default: 15.0")
                    .defineInRange("spinNauseaThreshold", 15.0, 0.0, 180.0);

            this.spinNauseaTurns = builder
                    .comment("How much sustained spinning it takes before the nausea starts: full turns (360 degrees each) turned",
                            "beyond the spinNauseaThreshold rate, counted while you keep spinning faster than it and drained while",
                            "you don't. At the default, spinning at 30 degrees a tick (1.7 turns a second) makes you dizzy after about",
                            "4 seconds, at 20 degrees a tick after about 9; a single 360 or a quick 180 never does. 0 = the moment the",
                            "averaged rate passes the threshold.")
                    .translation("config.fullstop.server.spinNauseaTurns")
                    .comment("Default: 2.0")
                    .defineInRange("spinNauseaTurns", 2.0, 0.0, 100.0);

            this.enableGravityBlocks = builder
                    .comment("When true, blocks tagged fullstop:gravity_affected (slime and honey by default) fall like sand when nothing supports them.",
                            "Blocks also tagged fullstop:sticky cling to any touching block, not just the one below.",
                            "Never on a Valkyrien Skies ship (the ship holds all its blocks as one body) and never in zero gravity.")
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

            this.enableGravityUnboundCompat = builder
                    .comment("When true and Gravity Unbound is installed, FullStop measures impacts in each entity's gravity frame:",
                            "a fall along a gravity field's pull is a FALL (vertical threshold, boots, Feather Falling) whichever world",
                            "axis it happens along, walls and floors are classified relative to the field, and velocity is exchanged",
                            "with the mod in world space. Turn off to test FullStop behavior as if Gravity Unbound were not installed.",
                            "Gravity's STRENGTH is read through Camafy either way (a field's strength, VS Genesis's space, Ad Astra's",
                            "orbits and planets, the forge:entity_gravity attribute): in zero gravity nothing is a fall.")
                    .translation(key("enableGravityUnboundCompat"))
                    .comment("Default: true")
                    .define("enableGravityUnboundCompat", true);

            this.configVersion = builder
                    .comment("Bookkeeping, not a setting: which one-time migrations have already run on this file",
                            "(2 = WeightRS's ship-weight settings were imported). Leave it alone.")
                    .translation(key("configVersion"))
                    .defineInRange("configVersion", 0, 0, Integer.MAX_VALUE);

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
                    .comment("How fast an entity must be moving (m/s) for a horizontal impact to deal kinetic damage. Very low values may be unplayable!",
                            "Reference speeds: walk 4.3, sprint 5.6, sprint-jump peak 9.6, Speed IV sprint 10.1, Speed V sprint 11.2.",
                            "In zero gravity (VS Genesis's space, an Ad Astra orbit) every impact uses this threshold: nothing is a fall there.")
                    .translation(key("velocityDamageThresholdHorizontal"))
                    .comment("Default: 10.5")
                    .defineInRange("velocityDamageThresholdHorizontal", DEFAULT_VELOCITY_DAMAGE_THRESHOLD_HORIZONTAL, 0, 100);

            this.velocityDamageThresholdVertical = builder
                    .comment("This value determines how fast an entity must be moving in order to apply kinetic damage vertically",
                            "(a fall: along the pull of gravity). Very low values may be unplayable!")
                    .translation(key("velocityDamageThresholdVertical"))
                    .comment("Default: 12.77")
                    .defineInRange("velocityDamageThresholdVertical", DEFAULT_VELOCITY_DAMAGE_THRESHOLD_VERTICAL, 0, 100);

            this.minimumFallDistance = builder
                    .comment("KINETIC mode: the same vertical threshold expressed as a fall height in blocks - falls (and any other",
                            "impact along gravity) up to this many blocks never deal damage. When set above 0 it REPLACES",
                            "velocityDamageThresholdVertical (3.0 blocks is vanilla's limit, about 12.3 m/s, a little under the",
                            "12.77 m/s default; 4.0 blocks is about 14.2 m/s, 5.0 about 15.9 m/s). 0 keeps using the m/s value above.")
                    .translation(key("minimumFallDistance"))
                    .comment("Default: 0.0")
                    .defineInRange("minimumFallDistance", 0.0, 0.0, 256.0);

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

            this.crackHealSeconds = builder
                    .comment("Seconds after the last hit before accumulated impact cracks (fullstop:crackable blocks, the ice family) heal and their overlay fades.",
                            "0 = cracks never heal: they persist (and save with the world) until the block breaks or is replaced.")
                    .translation(key("crackHealSeconds"))
                    .comment("Default: 0")
                    .defineInRange("crackHealSeconds", 0, 0, 86400);

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

            this.raycastMode = builder
                    .comment("Experimental: Choose the raycast mode for collision detection. FULL_SWEEP may have significant performance impact.")
                    .translation(key("raycastMode"))
                    .defineEnum("raycastMode", RaycastMode.CORNERS_AND_CENTERS);

            this.debugImpactLog = builder
                    .comment("Diagnostics: log every significant impact decision to the server log - the entity, its gravity frame,",
                            "the speed lost (in the frame and per world axis), the collision evidence the damage gate saw, what the",
                            "rays hit, and the damage computed and applied. For reporting impacts that should have hurt but did not.")
                    .translation(key("debugImpactLog"))
                    .comment("Default: false")
                    .define("debugImpactLog", false);

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
            builder.comment("How entities weigh on Valkyrien Skies ships (formerly the WeightRS mod; its settings are imported",
                            "automatically). Needs Valkyrien Skies and enableValkyrienSkiesCompat. How heavy things ARE (entity",
                            "weights, densities, carried items, the blacklist) is Camafy's weight model: config/camafy-weight.toml.",
                            "Weight on a deck counts everything an entity carries plus its riders; impact damage elsewhere in",
                            "FullStop uses body mass only.")
                    .push("Ship Weight");

            this.enableShipWeight = builder
                    .comment("Master switch: entities standing on ships push them down (m*g), jumping off kicks them, and landing",
                            "on them slams them. Stays OFF while the old WeightRS mod is installed (so nothing weighs twice).")
                    .translation(key("enableShipWeight"))
                    .comment("Default: true")
                    .define("enableShipWeight", true);

            this.includePlayers = builder
                    .comment("Whether players (and players riding things) weigh on ships.")
                    .translation(key("includePlayers"))
                    .comment("Default: true")
                    .define("includePlayers", true);

            this.gravity = builder
                    .comment("Gravitational acceleration (m/s^2) for standing weight (m*g) under normal gravity. Valkyrien Skies",
                            "pulls ships at 10; set 10 for exact parity. Scaled by each entity's local gravity (Camafy): nothing",
                            "in VS Genesis's space or an Ad Astra orbit, a sixth on the Moon, a Gravity Unbound field's strength.",
                            "With the Gravity Unbound compat on it pushes along the field's pull.")
                    .translation(key("gravity"))
                    .comment("Default: 9.81")
                    .defineInRange("gravity", 9.81, 0.0, 100.0);

            this.minPhysicsWeightKg = builder
                    .comment("Entities lighter than this (kg, with what they carry and their riders) don't weigh on ships.")
                    .translation(key("minPhysicsWeightKg"))
                    .comment("Default: 1.0")
                    .defineInRange("minPhysicsWeightKg", 1.0, 0.0, 1.0E6);

            this.enableItemEntityPhysics = builder
                    .comment("Whether dropped items weigh down (and land on) ships.")
                    .translation(key("enableItemEntityPhysics"))
                    .comment("Default: true")
                    .define("enableItemEntityPhysics", true);

            this.enableStandingWeight = builder
                    .comment("Entities standing on a ship continuously push it down with m*g.")
                    .translation(key("enableStandingWeight"))
                    .comment("Default: true")
                    .define("enableStandingWeight", true);

            this.enableJumpImpulse = builder
                    .comment("Jumping off a ship kicks it the other way (the jump's reaction impulse): the rider's mass and the ship's",
                            "effective mass where it stands share the push (a two-body exchange), so jumping off a light raft",
                            "rocks it and doesn't fling it.")
                    .translation(key("enableJumpImpulse"))
                    .comment("Default: true")
                    .define("enableJumpImpulse", true);

            this.enableFallImpact = builder
                    .comment("Landing on a ship pushes it down with the momentum of the landing: a two-body exchange along the deck's",
                            "normal, using the lander's speed RELATIVE to the deck where it lands (never the motion the ship itself",
                            "gave it) and the ship's effective mass there, so a heavy rider landing on the edge of a small raft tips",
                            "it and never kicks it harder than the rider was moving. A rider the ship just threw (deck momentum)",
                            "pushes that ship no more until it has settled. Slams into a hull from the side: see entityImpacts in",
                            "Ship Collisions. Measured by FullStop's own impact tracking: players, mobs, boats, carts and karts.")
                    .translation(key("enableFallImpact"))
                    .comment("Default: true")
                    .define("enableFallImpact", true);

            this.jumpImpulseMultiplier = builder
                    .comment("Scales the jump reaction impulse.")
                    .translation(key("jumpImpulseMultiplier"))
                    .comment("Default: 1.0")
                    .defineInRange("jumpImpulseMultiplier", 1.0, 0.0, 100.0);

            this.fallImpactMultiplier = builder
                    .comment("Scales the landing impulse (1 = momentum is conserved).")
                    .translation(key("fallImpactMultiplier"))
                    .comment("Default: 1.0")
                    .defineInRange("fallImpactMultiplier", 1.0, 0.0, 100.0);

            this.minFallDistance = builder
                    .comment("Landings softer than a fall from this height (blocks) push nothing; the height is converted to",
                            "vanilla's landing speed for it (1 block is about 7 m/s).")
                    .translation(key("minFallDistance"))
                    .comment("Default: 1.0")
                    .defineInRange("minFallDistance", 1.0, 0.0, 100.0);

            this.maxForceNewtons = builder
                    .comment("Cap (newtons) on any single force FullStop applies to a ship - standing weight, landings, jumps and",
                            "ship-collision reactions and slowdowns alike. An impulse is delivered over one 1/60 s physics step,",
                            "so it is capped at this / 60 newton-seconds. The safety valve against yeeting ships into orbit.")
                    .translation(key("maxForceNewtons"))
                    .comment("Default: 5.0E7")
                    .defineInRange("maxForceNewtons", 5.0E7, 0.0, 1.0E12);

            builder.pop();
            builder.comment("What moving Valkyrien Skies ships do to what they hit, and what hits them. Needs Valkyrien Skies and",
                            "enableValkyrienSkiesCompat. Entities riding a ship, creative/spectator players, entities tagged",
                            "fullstop:ship_immune and anything another mod owns the contacts of (MKW Mobility's karts and their",
                            "drivers while racing) are never hit. A vehicle with a rider (a boat, a cart, a free-roaming MKW kart)",
                            "is hit as one body with everyone on it, and a driver's vehicle is pushed through the driver's client.")
                    .push("Ship Collisions");

            this.enableShipCollisions = builder
                    .comment("Master switch for everything in this section.")
                    .translation(key("enableShipCollisions"))
                    .comment("Default: true")
                    .define("enableShipCollisions", true);

            this.solidHulls = builder
                    .comment("Keeps fast entities from passing through ship hulls. Valkyrien Skies 2.4.11 resolves an entity against",
                            "a hull one block at a time and prefers the smallest nudge, so anything moving more than about a block",
                            "a tick once it touches a hull (a player flying at 22 m/s, a mob thrown at 40) was nudged sideways and",
                            "went straight through. With this on, a move that fast near a ship is resolved in quarter-block steps,",
                            "so the hull stops it like any wall. Runs where each entity is simulated (players on their own client,",
                            "mobs on the server), so it also needs FullStop on the client for players.")
                    .translation(key("solidHulls"))
                    .comment("Default: true")
                    .define("solidHulls", true);

            this.entityImpacts = builder
                    .comment("An entity that runs, flies or falls into a ship collides with it as two bodies: along the contact",
                            "normal they exchange momentum by their masses (the entity's body mass, the ship's effective mass at the",
                            "point hit) with entityRestitution, the ship is pushed at the contact point, and the entity takes the",
                            "kinetic damage of the speed it actually lost. A heavy golem hitting a small light raft shoves it away",
                            "and barely slows; a player hitting a big ship stops as against a wall and is hurt as much. Contacts",
                            "from above a deck (landings) are inelastic. The same law as ships hitting entities, so a collision",
                            "comes out the same whichever of the two was moving. Off: a hull is an immovable wall for entities.")
                    .translation(key("entityImpacts"))
                    .comment("Default: true")
                    .define("entityImpacts", true);

            this.damageEntities = builder
                    .comment("Whether a ship hitting an entity (players included) deals kinetic damage. When off, ships still",
                            "shove what they hit.")
                    .translation(key("damageEntities"))
                    .comment("Default: true")
                    .define("damageEntities", true);

            this.pushMinSpeed = builder
                    .comment("Slowest hull speed (m/s, at the point of contact) that counts as a hit at all. Damage follows the",
                            "same speed thresholds as any other impact (velocityDamageThresholdHorizontal/Vertical).")
                    .translation(key("pushMinSpeed"))
                    .comment("Default: 1.0")
                    .defineInRange("pushMinSpeed", 1.0, 0.0, 100.0);

            this.entityDamageMultiplier = builder
                    .comment("Multiplies the damage a ship deals to an entity (after kineticDamageMultiplier).")
                    .translation(key("entityDamageMultiplier"))
                    .comment("Default: 1.0")
                    .defineInRange("entityDamageMultiplier", 1.0, 0.0, 100.0);

            this.entityRestitution = builder
                    .comment("How bouncy a collision between an entity and a hull is (either one moving): 0 = they end up moving",
                            "together, 1 = fully elastic. 0.3 matches FullStop's body-on-body collisions. A hull meeting an entity",
                            "from below (a landing, a deck rising under it) is always inelastic - except in zero gravity, where",
                            "nothing lands on a deck and a hull meets a floating body alike from every side.")
                    .translation(key("entityRestitution"))
                    .comment("Default: 0.3")
                    .defineInRange("entityRestitution", 0.3, 0.0, 1.0);

            this.hullFriction = builder
                    .comment("Friction between a hull and an entity it strikes: a hull sliding past (a spinning paddle's sweep, a",
                            "ship grazing a mob) drags the entity along its surface, by at most this times the push along the",
                            "contact normal (Coulomb) and never faster than the hull moves there. 0 = only the normal push.")
                    .translation(key("hullFriction"))
                    .comment("Default: 0.4")
                    .defineInRange("hullFriction", 0.4, 0.0, 2.0);

            this.hitLift = builder
                    .comment("An entity a hull knocks sideways off the ground also gets this share of that push as lift, so it",
                            "flies clear instead of skidding to a stop on the ground and being shoved along by the hull (vanilla's",
                            "knockback lifts the same way). 0 = it slides. Scaled by the entity's gravity (at most 1 g): a sixth on",
                            "the Moon, none in zero gravity, where there is no ground friction to beat.")
                    .translation(key("hitLift"))
                    .comment("Default: 0.3")
                    .defineInRange("hitLift", 0.3, 0.0, 2.0);

            this.pinnedDamageMultiplier = builder
                    .comment("Damage multiplier when the entity is pinned between the hull and a wall (or another ship) and can't",
                            "be pushed away: crushed. Repeats while the hull keeps pressing.")
                    .translation(key("pinnedDamageMultiplier"))
                    .comment("Default: 1.5")
                    .defineInRange("pinnedDamageMultiplier", 1.5, 0.0, 100.0);

            this.creditPilot = builder
                    .comment("When a player is seated on the ship, the hit is theirs: kill credit, and the server's PvP and team",
                            "rules apply as for any attack.")
                    .translation(key("creditPilot"))
                    .comment("Default: true")
                    .define("creditPilot", true);

            this.reactionImpulses = builder
                    .comment("Whatever a ship hits pushes back on the ship (momentum is conserved): a heavy ship barely notices a",
                            "zombie, a light one is slowed by a golem. Uses the ship's effective mass at the point hit, so a hull",
                            "struck off-centre also turns.")
                    .translation(key("reactionImpulses"))
                    .comment("Default: true")
                    .define("reactionImpulses", true);

            this.breakTerrain = builder
                    .comment("Ships crashing into terrain break what they hit, as FullStop's impacts do for fast entities. At each",
                            "contact only the WEAKER of the two touching blocks can give - the terrain block, or the ship's own block",
                            "there (hullDamage) - by toughness = hardness x (block mass / 1000 kg)^massInfluence: a stone hull crushes",
                            "grass, a wooden hull rammed into stone loses planks while the stone holds, obsidian against obsidian holds.",
                            "Also switches vegetation sweeping (below) on and off.",
                            "Works on every Valkyrien Skies physics backend (physicsBackend in the world's vs-core-server.toml): on",
                            "KRUNCH_PHYSX and KRUNCH_JOLT the crashes come from VS's collision events; on the default, KRUNCH_CLASSIC,",
                            "which sends none, Camafy detects them itself (a ship that suddenly slows has its hull probed for what it",
                            "hit; a glancing scrape that barely slows it can go unnoticed).")
                    .translation(key("breakTerrain"))
                    .comment("Default: true")
                    .define("breakTerrain", true);

            this.hullDamage = builder
                    .comment("Whether a ship's own blocks break where they hit tougher terrain (a wooden hull against stone, a glass",
                            "porthole against anything): they drop like mined blocks and the ship stays stopped. Off: hulls never break.")
                    .translation(key("hullDamage"))
                    .comment("Default: true")
                    .define("hullDamage", true);

            this.massInfluence = builder
                    .comment("How much a block's weight counts toward its toughness = hardness x (mass / 1000 kg)^massInfluence, with",
                            "Valkyrien Skies' own mass for the block (its vs_mass data: stone 2500 kg, oak planks 500, grass block 1250,",
                            "obsidian 5000, leaves 200; 1000 for blocks it doesn't list). At 1: leaves 0.04, grass 0.75, planks 1.0,",
                            "logs 1.6, stone 3.75, cobblestone 4.8, deepslate 8.7, iron block 39, obsidian 250. At 0 hardness alone",
                            "decides (oak planks, 2.0, then beat stone, 1.5).")
                    .translation(key("massInfluence"))
                    .comment("Default: 1.0")
                    .defineInRange("massInfluence", 1.0, 0.0, 4.0);

            this.breakMinSpeed = builder
                    .comment("Slowest speed into the contact (m/s) at which anything breaks, terrain or hull. Slower hits only make",
                            "noise (and a ship resting or rolling on the ground never grinds anything away).")
                    .translation(key("breakMinSpeed"))
                    .comment("Default: 5.0")
                    .defineInRange("breakMinSpeed", 5.0, 0.0, 1000.0);

            this.terrainEnergyFraction = builder
                    .comment("Share of the ship's kinetic energy into the terrain (1/2 * mass * speed^2, per tick) available for",
                            "breaking blocks. Energy is in FullStop's units: one unit is about 46 kJ; what a block costs is",
                            "breakCostMultiplier's business.")
                    .translation(key("terrainEnergyFraction"))
                    .comment("Default: 0.5")
                    .defineInRange("terrainEnergyFraction", 0.5, 0.0, 10.0);

            this.breakCostMultiplier = builder
                    .comment("What breaking a block costs, in energy units per point of toughness. The weaker block costs",
                            "  toughness(weaker) x breakCostMultiplier x r / (r - 1),   r = toughness(stronger) / toughness(weaker)",
                            "so against a far tougher striker about toughness x this (grass under an obsidian hull: 0.75 x 8 = 6 units),",
                            "near-equal materials many times more, and equal ones never break. fullstop:fragile blocks (glass, snow)",
                            "count as hardness 0.01; unbreakable blocks never break; cracks on fullstop:crackable blocks (ice) lower",
                            "what is left. At 8 a 150 t wooden ship bumping stone at 3 m/s at most cracks a plank, and at 8 m/s",
                            "loses about four; a 3x3x3 stone cube dropped ten blocks onto grass crushes the grass it lands on.",
                            "Swept vegetation costs toughness x this, with no contest (a leaf 0.32 units).")
                    .translation(key("breakCostMultiplier"))
                    .comment("Default: 8.0")
                    .defineInRange("breakCostMultiplier", 8.0, 0.0, 10000.0);

            this.terrainBreakDrag = builder
                    .comment("How much each broken terrain block slows the ship: the energy spent (times this) is taken out of its",
                            "momentum into the terrain. When everything the ship hit broke, it keeps going with what is left;",
                            "if anything held (or its own hull gave way), the ship stays stopped.")
                    .translation(key("terrainBreakDrag"))
                    .comment("Default: 2.0")
                    .defineInRange("terrainBreakDrag", 2.0, 0.0, 100.0);

            this.terrainMaxBlocksPerTick = builder
                    .comment("Most blocks (terrain and hull) ship crashes may break per level per tick, and at most 12 per ship.",
                            "Swept vegetation has its own cap, sweepMaxBlocksPerTick.")
                    .translation(key("terrainMaxBlocksPerTick"))
                    .comment("Default: 24")
                    .defineInRange("terrainMaxBlocksPerTick", 24, 0, 4096);

            this.terrainBlockDrops = builder
                    .comment("Broken blocks - terrain, hull and swept vegetation - drop items like mined blocks (the doTileDrops",
                            "gamerule still applies). A hull block's drops land in the world where the ship is.")
                    .translation(key("terrainBlockDrops"))
                    .comment("Default: true")
                    .define("terrainBlockDrops", true);

            this.terrainBreakBlockEntities = builder
                    .comment("Whether ships may break blocks with block entities (chests, furnaces, spawners...) - in the terrain or",
                            "in their own hull.")
                    .translation(key("terrainBreakBlockEntities"))
                    .comment("Default: false")
                    .define("terrainBreakBlockEntities", false);

            this.unpilotedFollowsMobGriefing = builder
                    .comment("A ship nobody is seated on only breaks blocks where mobGriefing allows it. A piloted ship is the",
                            "pilot acting, like a player's own crash. Protection mods and minigame arenas are asked either way.")
                    .translation(key("unpilotedFollowsMobGriefing"))
                    .comment("Default: true")
                    .define("unpilotedFollowsMobGriefing", true);

            this.shipVsShipBreaking = builder
                    .comment("Ships hitting ships: FULL breaks the weaker of the two blocks that meet at each contact, on either",
                            "ship, by the same toughness rule as terrain (an obsidian ship rammed into a wooden one breaks planks,",
                            "never obsidian; equal blocks hold), paid for out of the collision's energy - 1/2 x reduced mass x",
                            "closing speed^2 x terrainEnergyFraction, so a heavy ship ramming a light raft has only the raft's share",
                            "to spend - and when everything struck gave, the two plough on, both slowed by what breaking cost.",
                            "FRAGILE breaks only fullstop:fragile blocks (glass); NONE never breaks a ship's blocks. Either way the",
                            "collision sounds.")
                    .translation(key("shipVsShipBreaking"))
                    .comment("Default: FULL")
                    .defineEnum("shipVsShipBreaking", ShipVsShipBreaking.FULL);

            this.slimeBounce = builder
                    .comment("Ships bounce off slime, and slime hulls bounce off anything: when a ship meets terrain or another ship",
                            "and either block at the contact is slime (block tag fullstop:bouncy), it springs back at slimeRestitution",
                            "of the speed it hit with, measured at the contact - whatever Valkyrien Skies' own solver gave back, the",
                            "rest is added there. Two ships bounce off each other equally and oppositely. Honey in the same crash wins",
                            "(see honeyGlue). Slime never breaks, on a hull or in the terrain.")
                    .translation(key("slimeBounce"))
                    .comment("Default: true")
                    .define("slimeBounce", true);

            this.slimeRestitution = builder
                    .comment("How much of its speed into the slime a ship gets back: 1 = the same speed back, as FullStop bounces",
                            "entities off slime. Slime on both sides of the contact (a slime hull on slime) springs back at the square",
                            "root of this, the bounciest hit. A dropped ship rises to this squared of its drop height. The default",
                            "leaves a little out because a ship has no air drag to take it out as it does from a bouncing player: at",
                            "1.0 a slime hull dropped on stone bounces back to the same height every time.")
                    .translation(key("slimeRestitution"))
                    .comment("Default: 0.9")
                    .defineInRange("slimeRestitution", 0.9, 0.0, 1.0);

            this.slimeMinSpeed = builder
                    .comment("Slowest hit (m/s into the slime) that bounces a ship. Slower, it just settles.")
                    .translation(key("slimeMinSpeed"))
                    .comment("Default: 2.0")
                    .defineInRange("slimeMinSpeed", 2.0, 0.0, 100.0);

            this.honeyGlue = builder
                    .comment("Honey sticks ships: when a ship meets terrain or another ship and either block at the contact is honey",
                            "(block tag fullstop:gluey; honey on one side and slime on the other counts too), it doesn't bounce, the",
                            "crash is softened (honeySoftening), and once the two have settled against each other they are glued",
                            "there - a joint in Valkyrien Skies' own physics, like Create's super glue - until pulled apart harder",
                            "than honeyGlueStrength, a block at the glue is broken, or a ship is disassembled. Ships stay glued",
                            "across unloading and restarts (glued again when both are back, if they still touch). Honey never glues",
                            "entities (vanilla honey already slows them). A hull must meet the honey at 0.5 m/s or more, and on the",
                            "default KRUNCH_CLASSIC backend hard enough for Camafy's crash detection (about 1 m/s): a hull set down",
                            "more gently than that doesn't stick.")
                    .translation(key("honeyGlue"))
                    .comment("Default: true")
                    .define("honeyGlue", true);

            this.honeyGlueStrength = builder
                    .comment("How much more pull (newtons, or that at 1 m of twist) a honey glue joint takes than it held when it",
                            "set before it tears. A glue holds what it holds when it sets - a ship resting on the ground, one hanging",
                            "from a ceiling it flew up into - and tears when the pull changes by more than this: the default holds a",
                            "player or mob bumping the ship, a gentle tow and a small raft hanging from it, and gives way to a strong",
                            "push, a ship ramming it or an engine pulling hard. How the pull is measured depends on Valkyrien Skies'",
                            "physics backend (see Camafy's ShipJoints): every physics step on KRUNCH_CLASSIC, twice a second on",
                            "KRUNCH_PHYSX (whose physics engine also breaks it at once under a sudden blow) and KRUNCH_JOLT.")
                    .translation(key("honeyGlueStrength"))
                    .comment("Default: 60000.0")
                    .defineInRange("honeyGlueStrength", 60_000.0, 1.0, 1.0e9);

            this.honeySoftening = builder
                    .comment("Share of a crash's block-breaking energy honey soaks up when any contact of the crash is honey (on the",
                            "hull or in the terrain): 1 = nothing breaks in a crash honey takes part in, 0 = honey softens nothing.",
                            "Honey itself never breaks.")
                    .translation(key("honeySoftening"))
                    .comment("Default: 0.75")
                    .defineInRange("honeySoftening", 0.75, 0.0, 1.0);

            this.sweepVegetation = builder
                    .comment("Moving ships plough through tree canopies and soft plants (block tag fullstop:ship_sweepable: leaves,",
                            "saplings, flowers, tall grass, ferns, vines, sugar cane, berry bushes...) wherever the hull passes,",
                            "breaking them with drops. Valkyrien Skies lets ships pass through leaves, so without this they ghost",
                            "through trees. Needs breakTerrain.")
                    .translation(key("sweepVegetation"))
                    .comment("Default: true")
                    .define("sweepVegetation", true);

            this.sweepMinSpeed = builder
                    .comment("Slowest hull speed (m/s) that clears vegetation. A ship drifting slower slides through it untouched.")
                    .translation(key("sweepMinSpeed"))
                    .comment("Default: 4.0")
                    .defineInRange("sweepMinSpeed", 4.0, 0.0, 1000.0);

            this.sweepPlacedLeaves = builder
                    .comment("Whether ships also clear leaves players placed (hedges, leaf roofs, decoration). Natural leaves always go.")
                    .translation(key("sweepPlacedLeaves"))
                    .comment("Default: false")
                    .define("sweepPlacedLeaves", false);

            this.sweepDrag = builder
                    .comment("How much sweeping slows the ship: this share of the energy the plants cost comes out of its momentum.",
                            "0 = canopies don't slow ships at all.")
                    .translation(key("sweepDrag"))
                    .comment("Default: 0.25")
                    .defineInRange("sweepDrag", 0.25, 0.0, 10.0);

            this.sweepMaxBlocksPerTick = builder
                    .comment("Performance cap: most vegetation blocks ships clear per level per tick (and at most 32 per ship).")
                    .translation(key("sweepMaxBlocksPerTick"))
                    .comment("Default: 64")
                    .defineInRange("sweepMaxBlocksPerTick", 64, 0, 4096);

            this.impactSounds = builder
                    .comment("Ships hitting terrain or each other sound like it: BOTH blocks' own sounds at the contact (a stone",
                            "cube landing on grass plays grass and stone), louder and deeper the harder the hit and the heavier the",
                            "ship. Works with block breaking off. Only new contacts sound; resting and sliding ships are quiet.",
                            "Works on every VS physics backend, like block breaking (see breakTerrain).")
                    .translation(key("impactSounds"))
                    .comment("Default: true")
                    .define("impactSounds", true);

            this.impactSoundMinSpeed = builder
                    .comment("Slowest impact (m/s into the contact) that makes a sound.")
                    .translation(key("impactSoundMinSpeed"))
                    .comment("Default: 1.5")
                    .defineInRange("impactSoundMinSpeed", 1.5, 0.0, 1000.0);

            this.impactSoundVolume = builder
                    .comment("Volume multiplier for ship impact sounds (above 1 they also carry further).")
                    .translation(key("impactSoundVolume"))
                    .comment("Default: 1.0")
                    .defineInRange("impactSoundVolume", 1.0, 0.0, 4.0);

            this.impactParticles = builder
                    .comment("Block-crack particles from both blocks at ship impacts of 4 m/s and more.")
                    .translation(key("impactParticles"))
                    .comment("Default: true")
                    .define("impactParticles", true);

            this.newShipGraceTicks = builder
                    .comment("Ticks a newly seen ship (assembled, loaded, pasted, split off) is harmless, so assembly and chunk",
                            "loads never count as crashes. Teleported ships get a shorter grace of their own.")
                    .translation(key("newShipGraceTicks"))
                    .comment("Default: 40")
                    .defineInRange("newShipGraceTicks", 40, 0, 1200);

            this.maxHitChecksPerTick = builder
                    .comment("Performance cap: most entities checked against ship hulls per level per tick.")
                    .translation(key("maxHitChecksPerTick"))
                    .comment("Default: 256")
                    .defineInRange("maxHitChecksPerTick", 256, 0, 100000);

            this.debugShipCollisionLog = builder
                    .comment("Diagnostics: log every ship hit, entity-hits-ship collision, entity striking a ship's block, terrain-breaking",
                            "decision and rider thrown off a deck to the server log.")
                    .translation(key("debugShipCollisionLog"))
                    .comment("Default: false")
                    .define("debugShipCollisionLog", false);

            this.debugShipContactLog = builder
                    .comment("Diagnostics for players and ships: logs every decision about a player touching a ship - which part",
                            "decided (a hull hit the player's client reported, one the server ignored and why, a ray of the",
                            "player's own motion hitting a ship block that isn't a contact, a deck throwing a rider or refusing",
                            "a one-frame spike, a hull shoving the player), with closing speeds, cooldowns and whether a",
                            "velocity change was sent to the player's client. Client-side lines (the hull shoving you) go to",
                            "your own client's log; in single player both are in logs/latest.log. Turn on, reproduce, turn off.")
                    .translation(key("debugShipContactLog"))
                    .comment("Default: false")
                    .define("debugShipContactLog", false);

            builder.pop();
            builder.comment("What happens to whatever rides a Valkyrien Skies ship when the ship changes speed or direction",
                            "hard. Needs Valkyrien Skies and enableValkyrienSkiesCompat.")
                    .push("Ship Riders");

            this.deckMomentum = builder
                    .comment("Riders keep the momentum a ship loses. When a deck stops, slows, lurches, throws itself into a turn or",
                            "spins up or down harder than riders can hold on (deckGrip), they keep the difference: a ship that crashes",
                            "throws what stands on it forward off the deck, into whatever is ahead (FullStop's ordinary impacts apply).",
                            "A ship turning or spinning STEADILY (a Clockwork rotor at any speed, a ship holding a curve) changes nothing",
                            "under its riders: they stay put, carried round as Valkyrien Skies carries them. Players, mobs, dropped",
                            "items, boats and carts alike. Never thrown: riders anchored to the deck - standing on a fullstop:sticky",
                            "block (slime, honey), on Gravity Unbound's gravity plating, or clinging with its Surface Cling boots -",
                            "seated riders (see deckMomentumSeated), creative-flying players, NoAI mobs, fullstop:physics_blacklist",
                            "entities, and bodies another mod moves (a grappling player) unless it takes the change through Camafy's",
                            "Impulses (a free-roaming MKW kart does). A thrown rider pushes the ship that threw it no more until it has",
                            "settled, and a deck never throws the rider it has just collided with.")
                    .translation(key("deckMomentum"))
                    .comment("Default: true")
                    .define("deckMomentum", true);

            this.deckGrip = builder
                    .comment("How hard a deck may change speed under a rider's feet (m/s^2) before the rider stops holding on,",
                            "measured in the ship's own frame (a steady turn or spin doesn't count). 9.81 = 1 g: a ship braking,",
                            "speeding up, or starting and stopping a turn or a spin more gently than that carries everyone along as",
                            "before; beyond it riders keep the difference. Higher = stickier decks. Grip is friction, so it scales",
                            "with each rider's gravity (at most 1 g): a sixth on the Moon, none in zero gravity (a floating rider",
                            "keeps its own velocity; anchored riders still hold on).")
                    .translation(key("deckGrip"))
                    .comment("Default: 9.81")
                    .defineInRange("deckGrip", 9.81, 0.0, 1000.0);

            this.deckThrowLift = builder
                    .comment("A thrown rider also gets this share of the speed it kept as lift (along its up), so ground",
                            "friction doesn't just skid it to a halt on the deck. 0 = it slides. Scaled by the rider's gravity",
                            "(at most 1 g): a sixth on the Moon, none in zero gravity (it would carry the rider off upward for good).")
                    .translation(key("deckThrowLift"))
                    .comment("Default: 0.15")
                    .defineInRange("deckThrowLift", 0.15, 0.0, 2.0);

            this.deckMomentumSeated = builder
                    .comment("Whether seated riders (a ship's seats, helm or chairs) are thrown out of their seats too. Off: seat",
                            "belts - they stay seated whatever the ship does.")
                    .translation(key("deckMomentumSeated"))
                    .comment("Default: false")
                    .define("deckMomentumSeated", false);

            this.releaseLeavingRiders = builder
                    .comment("Valkyrien Skies lets go of a rider the moment it leaves the deck: a jump, a step off the edge, a hull",
                            "that knocks it away. Valkyrien Skies alone keeps moving everything that stood on a ship WITH the ship",
                            "for another 25 ticks (1.25 s), turning it round a spinning rotor all the while, so a jumper was swung",
                            "round with the deck and anything flung off a rotor curved away with it. Released, a rider flies on",
                            "the deck's momentum from the moment it left, in a straight line: a jump on a ship cruising steadily",
                            "still lands where it took off (the deck's speed isn't lost to air drag), a jump on a spinning rotor",
                            "flies off along its edge, a ship that stops mid-jump flies on without its rider. Landing on a deck",
                            "gives it back to Valkyrien Skies as usual. Riders held by Gravity Unbound (plating, Surface Cling)",
                            "are left to it. Players only (on their own client).")
                    .translation(key("releaseLeavingRiders"))
                    .comment("Default: true")
                    .define("releaseLeavingRiders", true);

            builder.pop();
        }

        private static String key(String valueName) {
            return "config.fullstop." + valueName;
        }
    }

    public static class ClientConfigValues {
        public final ForgeConfigSpec.BooleanValue rotateCamera;
        public final ForgeConfigSpec.BooleanValue rotateCameraOnlyWhenFlying;

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

            // The g-force thresholds used to live here. They gate a gameplay
            // mechanic (blackouts, nausea, the potions that shift them), so a
            // client must not be able to move them: they are server settings now.

            builder.pop();
        }
    }

    public enum RaycastMode {
        CORNERS_ONLY,
        CORNERS_AND_CENTERS,
        FULL_SWEEP
    }

    public enum ShipVsShipBreaking {
        /** Ships never break each other's blocks. */
        NONE,
        /** Only fullstop:fragile blocks (glass, snow) break when ships collide. */
        FRAGILE,
        /** The weaker (by toughness) of the two contacting blocks breaks. */
        FULL
    }

    public enum FallDamageMode {
        /** FullStop's stopping-force model: thresholds, block hardness, the works. */
        KINETIC,
        /** Impacts deal what vanilla would for the equivalent fall distance. */
        VANILLA_PARITY
    }
}
