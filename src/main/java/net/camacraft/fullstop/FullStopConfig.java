package net.camacraft.fullstop;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

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

        // Bounces and booms (projectiles and items on slime, foliage, slime-bounce flight, sonic boom range)
        public final ForgeConfigSpec.BooleanValue projectilesBounceOnSlime;
        public final ForgeConfigSpec.DoubleValue projectileSlimeSkipAngle;
        public final ForgeConfigSpec.DoubleValue projectileSlimeRestitution;
        public final ForgeConfigSpec.DoubleValue projectileSlimeFriction;
        public final ForgeConfigSpec.IntValue projectileSlimeMaxSkips;
        public final ForgeConfigSpec.DoubleValue bouncyItemRestitution;
        public final ForgeConfigSpec.BooleanValue foliageNeverHurts;
        public final ForgeConfigSpec.BooleanValue slimeBounceKeepsMomentum;
        public final ForgeConfigSpec.DoubleValue sonicBoomRange;
        public final ForgeConfigSpec.BooleanValue deathMessageAppend;
        public final ForgeConfigSpec.BooleanValue entityCollisionDamage;
        public final ForgeConfigSpec.BooleanValue scaledKnockback;
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
        public final ForgeConfigSpec.BooleanValue impactCraters;
        public final ForgeConfigSpec.IntValue impactCraterMaxRadius;
        public final ForgeConfigSpec.IntValue impactCraterMaxDepth;
        public final ForgeConfigSpec.EnumValue<RaycastMode> raycastMode;
        public final ForgeConfigSpec.BooleanValue playerSlimeLandingsOnClient;
        // speed blows fire out (server/physics/interaction/WindOnFire)
        public final ForgeConfigSpec.BooleanValue windPutsOutFire;
        public final ForgeConfigSpec.DoubleValue fireWindMinSpeed;
        public final ForgeConfigSpec.DoubleValue fireWindDrain;
        public final ForgeConfigSpec.DoubleValue fireWindMaxDrain;
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
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> highAltitudeDimensions;
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
        public final ForgeConfigSpec.DoubleValue maxHitLaunchSpeed;
        public final ForgeConfigSpec.DoubleValue gentlePushSpeed;
        public final ForgeConfigSpec.BooleanValue shipSonicBooms;
        public final ForgeConfigSpec.BooleanValue pistonsPushShips;
        public final ForgeConfigSpec.DoubleValue pistonPushMassKg;
        public final ForgeConfigSpec.BooleanValue pistonStickyPulls;
        public final ForgeConfigSpec.BooleanValue pistonShipReaction;
        public final ForgeConfigSpec.DoubleValue entityDamageMultiplier;
        public final ForgeConfigSpec.DoubleValue entityRestitution;
        public final ForgeConfigSpec.DoubleValue softStrikeDamage;
        public final ForgeConfigSpec.BooleanValue punchShips;
        public final ForgeConfigSpec.DoubleValue punchShipImpulse;
        public final ForgeConfigSpec.BooleanValue punchShipsAnyItem;
        public final ForgeConfigSpec.DoubleValue punchShipEnchantBonus;
        public final ForgeConfigSpec.BooleanValue tntImpacts;
        public final ForgeConfigSpec.DoubleValue tntImpactMinSpeed;
        public final ForgeConfigSpec.DoubleValue tntImpactInstantSpeed;
        public final ForgeConfigSpec.IntValue tntImpactFuseTicks;
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
        public final ForgeConfigSpec.DoubleValue mutualBreakRatio;
        public final ForgeConfigSpec.BooleanValue crushPinnedShips;
        public final ForgeConfigSpec.DoubleValue shipShoveLimit;
        public final ForgeConfigSpec.BooleanValue shipClashSweep;
        public final ForgeConfigSpec.IntValue shipClashMaxBlocksPerTick;
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
        public final ForgeConfigSpec.IntValue breakSoundsPerTick;
        public final ForgeConfigSpec.IntValue breakDustPerTick;
        public final ForgeConfigSpec.BooleanValue mergeBreakDrops;
        public final ForgeConfigSpec.DoubleValue sweepPlacedLeavesMinSpeed;
        public final ForgeConfigSpec.BooleanValue shipRedstone;
        public final ForgeConfigSpec.DoubleValue shipWeightedPlateKgPerSignal;
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

        // Entity Collisions (BodyCollisions)
        public final ForgeConfigSpec.BooleanValue enableEntityCollisions;
        public final ForgeConfigSpec.DoubleValue entityCollisionMinSpeed;
        public final ForgeConfigSpec.DoubleValue entityCollisionRestitution;
        public final ForgeConfigSpec.DoubleValue entityCollisionFriction;
        public final ForgeConfigSpec.DoubleValue entityCollisionDamageMultiplier;

        // Projectile Collisions (ProjectileCollisions)
        public final ForgeConfigSpec.BooleanValue enableProjectileCollisions;
        public final ForgeConfigSpec.DoubleValue projectileRestitution;

        // Create ("Create Catastrophes", compat/create): the section and the master switch; contraption hits
        public final ForgeConfigSpec.BooleanValue enableCreateCompat;
        public final ForgeConfigSpec.BooleanValue contraptionImpacts;
        public final ForgeConfigSpec.DoubleValue contraptionDamageMultiplier;
        public final ForgeConfigSpec.BooleanValue contraptionsReplaceTrainDamage;
        public final ForgeConfigSpec.IntValue maxContraptionHitChecksPerTick;
        public final ForgeConfigSpec.BooleanValue debugContraptionLog;
        // Create: contraptions meeting terrain and ships, both sides giving (compat/create/contraption)
        public final ForgeConfigSpec.BooleanValue contraptionTerrainImpacts;
        public final ForgeConfigSpec.BooleanValue contraptionBlocksBreak;
        public final ForgeConfigSpec.BooleanValue contraptionImpactStress;
        public final ForgeConfigSpec.BooleanValue contraptionShipImpacts;
        public final ForgeConfigSpec.DoubleValue contraptionTerrainMinSpeed;
        public final ForgeConfigSpec.BooleanValue contraptionSlowJams;
        public final ForgeConfigSpec.BooleanValue contraptionsMeet;
        public final ForgeConfigSpec.BooleanValue contraptionHitsOverstress;
        public final ForgeConfigSpec.DoubleValue contraptionImpactStrength;
        public final ForgeConfigSpec.DoubleValue contraptionStressPerImpact;
        public final ForgeConfigSpec.IntValue maxContraptionTerrainChecksPerTick;
        public final ForgeConfigSpec.BooleanValue contraptionSonicBooms;
        public final ForgeConfigSpec.BooleanValue contraptionRidersTurn;
        // Create: spinning gears and water wheels (compat/create/kinetic)
        public final ForgeConfigSpec.BooleanValue gearHazards;
        public final ForgeConfigSpec.DoubleValue gearPullStrength;
        public final ForgeConfigSpec.DoubleValue gearDamage;
        public final ForgeConfigSpec.DoubleValue gearTouchDamage;
        public final ForgeConfigSpec.DoubleValue gearMaxDamage;
        public final ForgeConfigSpec.DoubleValue gearMinRpm;
        public final ForgeConfigSpec.DoubleValue gearJamStress;
        public final ForgeConfigSpec.DoubleValue gearFlingSpeed;
        public final ForgeConfigSpec.BooleanValue gearsPushShips;
        public final ForgeConfigSpec.BooleanValue gearsPushVehicles;
        public final ForgeConfigSpec.DoubleValue gearShipMass;
        public final ForgeConfigSpec.BooleanValue waterWheelHazards;
        public final ForgeConfigSpec.DoubleValue waterWheelGrip;
        public final ForgeConfigSpec.DoubleValue waterWheelHoldSeconds;

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

            this.scaledKnockback = builder
                    .comment("Knockback goes by the sizes of the hitter and the victim relative to each other (Pehkui; VS Genesis's",
                            "space shrinks everything to 1/16). Two bodies of the same size knock each other back as vanilla does;",
                            "a full-size player's hit sends a 1/16 mob as far across the world as it sends a normal one (sixteen of",
                            "its own lengths); a 1/16 player's hit barely moves a full-size mob.")
                    .translation(key("scaledKnockback"))
                    .comment("Default: true")
                    .define("scaledKnockback", true);

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
            builder.comment("What bounces off slime besides bodies, what never hurts to run into, and how far a sonic boom carries.")
                    .push("Bounces and booms");

            this.projectilesBounceOnSlime = builder
                    .comment("Arrows, tridents and firework rockets (#fullstop:bounces_on_slime) bounce off slime, in the world and on ships.",
                            "An arrow or trident meeting the face at a shallow angle skips off it (as a stone skips on water); one",
                            "shot more steeply into it sticks, as on any block. A firework rocket always bounces.")
                    .translation(key("projectilesBounceOnSlime"))
                    .comment("Default: true")
                    .define("projectilesBounceOnSlime", true);

            this.projectileSlimeSkipAngle = builder
                    .comment("The steepest angle (degrees above the slime's face) at which an arrow or trident still skips off it;",
                            "steeper, it sticks. Water skipping takes 25.")
                    .translation(key("projectileSlimeSkipAngle"))
                    .comment("Default: 30.0")
                    .defineInRange("projectileSlimeSkipAngle", 30.0, 0.0, 90.0);

            this.projectileSlimeRestitution = builder
                    .comment("The share of a projectile's speed INTO the slime given back out of it when it skips or bounces.")
                    .translation(key("projectileSlimeRestitution"))
                    .comment("Default: 0.6")
                    .defineInRange("projectileSlimeRestitution", 0.6, 0.0, 1.0);

            this.projectileSlimeFriction = builder
                    .comment("The share of a projectile's speed ALONG the slime it keeps when it skips or bounces: below 1, every skip",
                            "is shorter and slower than the last, so an arrow can't skip across slime forever.")
                    .translation(key("projectileSlimeFriction"))
                    .comment("Default: 0.8")
                    .defineInRange("projectileSlimeFriction", 0.8, 0.0, 1.0);

            this.projectileSlimeMaxSkips = builder
                    .comment("An arrow or trident skips off slime at most this many times; the next time it sticks.")
                    .translation(key("projectileSlimeMaxSkips"))
                    .comment("Default: 6")
                    .defineInRange("projectileSlimeMaxSkips", 6, 0, 64);

            this.bouncyItemRestitution = builder
                    .comment("Dropped slime balls and slime blocks (#fullstop:bouncy_items) bounce off whatever they land on or hit,",
                            "giving back this share of their speed into it (0: they land like any item). They settle after a few bounces.")
                    .translation(key("bouncyItemRestitution"))
                    .comment("Default: 0.8")
                    .defineInRange("bouncyItemRestitution", 0.8, 0.0, 0.95);

            this.foliageNeverHurts = builder
                    .comment("Running (or being thrown) into foliage never hurts: plants and leaves of any mod, told by what they are",
                            "(breaking at once or nearly, with a plant's sound or a plant's map colour) or by #fullstop:foliage",
                            "(#fullstop:not_foliage takes a block out). Landing on foliage from a fall counts as a soft landing.")
                    .translation(key("foliageNeverHurts"))
                    .comment("Default: true")
                    .define("foliageNeverHurts", true);

            this.slimeBounceKeepsMomentum = builder
                    .comment("A body bounced off slime at an angle (a tilted ship's slime, a slime wall) keeps the sideways speed the",
                            "bounce gave it through the air as it keeps its upward speed, instead of losing it to the walking air drag",
                            "(vanilla slows sideways motion in the air about five times as fast as vertical motion).")
                    .translation(key("slimeBounceKeepsMomentum"))
                    .comment("Default: true")
                    .define("slimeBounceKeepsMomentum", true);

            this.sonicBoomRange = builder
                    .comment("How far (blocks) a sonic boom is heard: loud a long way off, deeper and more muffled the farther it",
                            "travelled. A supersonic rotor's train of cracks keeps its own range (192 blocks).")
                    .translation(key("sonicBoomRange"))
                    .comment("Default: 768.0")
                    .defineInRange("sonicBoomRange", 768.0, 64.0, 4096.0);

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
                    .comment("Legacy: the old experimental entity collisions (a momentum exchange and damage per entity, and landing on a",
                            "mob or boat rides it). Superseded by the Entity Collisions section: this only runs while",
                            "enableEntityCollisions is off. Highly experimental, use at your own risk!")
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

            this.impactCraters = builder
                    .comment("When enabled (with kineticBlockBreaking), a body wider than a block that crashes into the ground or a wall breaks the",
                            "blocks under its whole footprint, not only the few its collision rays happen to hit: a giant dropped from a height",
                            "leaves a hole the width of its feet, and with energy to spare digs a shallow bowl below it, narrower each layer down.",
                            "Each block costs its hardness in impact energy as usual, so a wide body landing softly breaks nothing.")
                    .translation(key("impactCraters"))
                    .comment("Default: true")
                    .define("impactCraters", true);

            this.impactCraterMaxRadius = builder
                    .comment("How far from the body's centre column (blocks) a crater may reach; a wider footprint is clipped to it.")
                    .translation(key("impactCraterMaxRadius"))
                    .comment("Default: 6")
                    .defineInRange("impactCraterMaxRadius", 6, 1, 32);

            this.impactCraterMaxDepth = builder
                    .comment("How many block layers deep a crater may go in one impact (the surface layer counts as one).")
                    .translation(key("impactCraterMaxDepth"))
                    .comment("Default: 3")
                    .defineInRange("impactCraterMaxDepth", 3, 1, 8);

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

            this.playerSlimeLandingsOnClient = builder
                    .comment("A player landing on slime bounces on its own client the moment it lands (as in vanilla, with Full Stop's",
                            "restitution, off the slime's face and relative to a moving Valkyrien Skies deck). A player's client moves the",
                            "player, and the server sees a landing a tick or two after it happened (later still on a ship): its bounce came",
                            "after the player had stopped on the slime, and on a ship it was sometimes lost. Off: the server bounces players",
                            "as it does mobs.")
                    .translation(key("playerSlimeLandingsOnClient"))
                    .define("playerSlimeLandingsOnClient", true);

            this.windPutsOutFire = builder
                    .comment("Speed blows a fire out: a burning entity moving through the air loses its fire time faster the faster it",
                            "goes (running, falling, flying, riding), on top of the tick vanilla takes each tick. The flames die down",
                            "sooner, never at once. Not in lava or while standing in fire. A burning Valkyrien Skies ship (Camafy's",
                            "ship fire) under way goes out sooner the same way.")
                    .translation(key("windPutsOutFire"))
                    .comment("Default: true")
                    .define("windPutsOutFire", true);

            this.fireWindMinSpeed = builder
                    .comment("Slowest speed (m/s) at which the air takes anything off a fire. A walk is 4.3, a sprint 5.6, a sprint",
                            "with jumps about 7, a horse up to 14, a long fall up to 78.")
                    .translation(key("fireWindMinSpeed"))
                    .comment("Default: 4.5")
                    .defineInRange("fireWindMinSpeed", 4.5, 0.0, 1000.0);

            this.fireWindDrain = builder
                    .comment("Extra fire ticks taken per tick for every m/s over fireWindMinSpeed. At 0.5 a sprint burns out about",
                            "1.5 times as fast as standing still, a sprint with jumps 2.3 times, a gallop at 14 m/s 5.7 times.")
                    .translation(key("fireWindDrain"))
                    .comment("Default: 0.5")
                    .defineInRange("fireWindDrain", 0.5, 0.0, 100.0);

            this.fireWindMaxDrain = builder
                    .comment("Most extra fire ticks the air takes per tick, however fast. At 10 the 8 seconds of fire a body carries",
                            "out of a blaze still last three quarters of a second in a long fall, lava's 15 seconds a second and a half.")
                    .translation(key("fireWindMaxDrain"))
                    .comment("Default: 10.0")
                    .defineInRange("fireWindMaxDrain", 10.0, 0.0, 1000.0);

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

            this.highAltitudeDimensions = builder
                    .comment("The dimensions whose air thins with height (dimension ids). Anywhere else the altitude rule is off,",
                            "whatever the Y: VS Genesis's space (the Great Unknown), the Nether, the End and modded worlds are not",
                            "the overworld's sky. Empty = nowhere.")
                    .translation(key("highAltitudeDimensions"))
                    .comment("Default: [\"minecraft:overworld\"]")
                    .defineListAllowEmpty("highAltitudeDimensions", List.of("minecraft:overworld"),
                            o -> o instanceof String s && ResourceLocation.isValidResourceLocation(s));

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
                            "enableValkyrienSkiesCompat. Entities riding a ship, spectators, entities tagged fullstop:ship_immune and",
                            "anything another mod owns the contacts of (MKW Mobility's karts and their drivers while racing) are never",
                            "hit; creative players are knocked about like anyone else but never hurt. A vehicle with a rider (a boat,",
                            "a cart, a free-roaming MKW kart) is hit as one body with everyone on it, and a driver's vehicle is pushed",
                            "through the driver's client.")
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

            this.softStrikeDamage = builder
                    .comment("The share of a strike's damage a soft block deals when it is the block that hits (a hull's or a Create",
                            "contraption's): wool, hay, leaves, moss, sponge, dripleaves, wool carpets and beds (#fullstop:cushioning,",
                            "#fullstop:soft_landing). A soft block also springs back less (a third of entityRestitution): it still",
                            "carries the body along at its own speed, so a fast wool arm shoves an iron golem hard but barely hurts it.",
                            "1 = soft blocks hit as hard as their hardness says.")
                    .translation(key("softStrikeDamage"))
                    .comment("Default: 0.1")
                    .defineInRange("softStrikeDamage", 0.1, 0.0, 1.0);

            this.punchShips = builder
                    .comment("A player hitting a block of a ship (the start of a click, with a fist or whatever is in hand: see",
                            "punchShipsAnyItem) gives the ship a small push at the point hit, along the hit: a one-block ship drifts off,",
                            "a raft barely moves, a big ship not at all. Not while sneaking, not in creative, not on the ship you stand on.")
                    .translation(key("punchShips"))
                    .comment("Default: true")
                    .define("punchShips", true);

            this.punchShipImpulse = builder
                    .comment("The push of a 75 kg player's bare fist on a ship (newton-seconds; heavier and bigger players push more,",
                            "a sword up to twice as much). A plank weighs 500 kg to Valkyrien Skies: 250 sends a one-block ship off at",
                            "half a metre a second.")
                    .translation(key("punchShipImpulse"))
                    .comment("Default: 250.0")
                    .defineInRange("punchShipImpulse", 250.0, 0.0, 100000.0);

            this.punchShipsAnyItem = builder
                    .comment("On: a hit pushes the ship whatever the player holds (a pickaxe's first hit on a block shoves the ship;",
                            "holding the button to mine doesn't push again; sneak to hit a ship without moving it).",
                            "Off: only an empty hand and swords push, as before.")
                    .translation(key("punchShipsAnyItem"))
                    .comment("Default: true")
                    .define("punchShipsAnyItem", true);

            this.punchShipEnchantBonus = builder
                    .comment("What each level of Knockback on the item in hand adds to its push on a ship, in pushes (1 = Knockback I",
                            "doubles it, Knockback II triples it). Full Stop's Pullback counts the same the other way: the ship is",
                            "pulled toward the player, a plain push's worth per level. With both on one item their levels subtract.")
                    .translation(key("punchShipEnchantBonus"))
                    .comment("Default: 1.0")
                    .defineInRange("punchShipEnchantBonus", 1.0, 0.0, 100.0);

            this.tntImpacts = builder
                    .comment("TNT goes off when it is hit: a TNT block of a ship that strikes the terrain or another ship (or is the",
                            "ship), TNT in the terrain or on a ship that a hull strikes, and with Create a contraption's TNT block or",
                            "TNT a contraption runs into. Hit at tntImpactMinSpeed or faster it is lit with a short fuse",
                            "(tntImpactFuseTicks), at tntImpactInstantSpeed or faster it explodes on the spot. Minigame protection",
                            "is respected; like any TNT it doesn't follow mobGriefing.")
                    .translation(key("tntImpacts"))
                    .comment("Default: true")
                    .define("tntImpacts", true);

            this.tntImpactMinSpeed = builder
                    .comment("The slowest hit (m/s) that lights TNT. A ship settling onto TNT, or a TNT ship set down gently, is slower.")
                    .translation(key("tntImpactMinSpeed"))
                    .comment("Default: 5.0")
                    .defineInRange("tntImpactMinSpeed", 5.0, 0.0, 1000.0);

            this.tntImpactInstantSpeed = builder
                    .comment("A hit at this speed (m/s) or faster sets TNT off at once (12 m/s: a fall of about seven blocks).")
                    .translation(key("tntImpactInstantSpeed"))
                    .comment("Default: 12.0")
                    .defineInRange("tntImpactInstantSpeed", 12.0, 0.0, 1000.0);

            this.tntImpactFuseTicks = builder
                    .comment("The fuse (ticks) of TNT lit by a hit too slow to set it off at once.")
                    .translation(key("tntImpactFuseTicks"))
                    .comment("Default: 10")
                    .defineInRange("tntImpactFuseTicks", 10, 1, 80);

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

            this.maxHitLaunchSpeed = builder
                    .comment("The fastest (m/s) a hull's hit can launch an entity. The damage still follows the full speed of the",
                            "hit; only the flight is capped. It is there for extreme rotors: an arm whose tip moves at many hundreds",
                            "of m/s launched a player across dozens of blocks a tick, generating chunks faster than the game could",
                            "(it dropped to a frame a second). Up to 250 m/s the fling is the hit's own: a 240 RPM arm launched a",
                            "player at 139 m/s, 96 blocks, and the worst frame took 39 ms. Lower it on a slow server. 0 = no limit.")
                    .translation(key("maxHitLaunchSpeed"))
                    .comment("Default: 250.0")
                    .defineInRange("maxHitLaunchSpeed", 250.0, 0.0, 1000.0);

            this.gentlePushSpeed = builder
                    .comment("A hull closing on an entity slower than this (m/s, at the entity's own scale) pushes it along instead",
                            "of striking it: no bounce off the hull and no lift, so a slow ship or a slowly turning rotor shoves what it",
                            "meets along smoothly, as a moving wall would. Faster than this it is a hit (entityRestitution, hitLift).",
                            "Pushes never hurt: the damage thresholds are well above this.")
                    .translation(key("gentlePushSpeed"))
                    .comment("Default: 5.0")
                    .defineInRange("gentlePushSpeed", 5.0, 0.0, 100.0);

            this.shipSonicBooms = builder
                    .comment("A ship whose hull moves faster than sound (343 m/s) anywhere - a fast ship, or the tips of a fast",
                            "rotor - makes a sonic boom, as a fast entity does: once as it crosses the barrier, and once for each",
                            "player within 96 blocks of it while it stays supersonic. Its riders don't boom on their own.")
                    .translation(key("shipSonicBooms"))
                    .comment("Default: true")
                    .define("shipSonicBooms", true);

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

            this.mutualBreakRatio = builder
                    .comment("Ships hitting ships: two blocks whose toughness is within this ratio of each other (the tougher at most",
                            "this many times the weaker) BOTH break when the collision has the energy for it - neither is tough enough",
                            "to survive the other, and planks meeting planks at speed splinter both - each costing",
                            "  toughness x breakCostMultiplier x ratio / (ratio - 1)",
                            "(what the rule above charges at the edge of this band, so the two rules join up). Blocks further apart",
                            "than this lose only the weaker, as before. 1.0 restores the old rule: equal blocks never break.")
                    .translation(key("mutualBreakRatio"))
                    .comment("Default: 1.25")
                    .defineInRange("mutualBreakRatio", 1.25, 1.0, 100.0);

            this.crushPinnedShips = builder
                    .comment("Ships hitting ships: a ship with terrain, a static ship or another ship right behind it along the push",
                            "(lying on the ground under a hull coming down on it, pinned against a wall) has nowhere to go, and the",
                            "crash has the striker's whole mass behind it, as a crash into terrain has: a heavy obsidian ship dropped",
                            "on a one-plank ship lying on the ground breaks it. Off: two ships always crash with their reduced mass,",
                            "which for a one-block ship is next to nothing (it took 55 m/s to break that plank).")
                    .translation(key("crushPinnedShips"))
                    .comment("Default: true")
                    .define("crushPinnedShips", true);

            this.shipShoveLimit = builder
                    .comment("Ships hitting ships: the most a crash can change a ship's speed by at once (m/s) and still just shove it",
                            "aside. A ship the crash would have to throw aside faster than this has no time to get out of the way and",
                            "is hit as if it stood still, as terrain is: a heavy ship swung into a free one-block ship at this speed",
                            "breaks it instead of batting it away. From half of it the ship already counts as partly held. The crash",
                            "never has more behind it than the heavier ship's whole mass, and what breaks is still decided block",
                            "against block (the weaker gives). 0 turns the rule off.")
                    .translation(key("shipShoveLimit"))
                    .comment("Default: 15.0")
                    .defineInRange("shipShoveLimit", 15.0, 0.0, 1000.0);

            this.shipClashSweep = builder
                    .comment("Fast hulls meeting hulls. A block moving tens of blocks a tick (the tips of a 256 RPM rotor) passes clean",
                            "through another ship between two physics frames, and Valkyrien Skies never reports a contact. Each tick",
                            "every block of the smaller of two ships that could reach the other is followed along the path it really",
                            "took over the tick (the ships' poses, interpolated) against the other's blocks, and where they crossed,",
                            "the blocks break by the ship-vs-ship rule (shipVsShipBreaking, mutualBreakRatio), paid for out of the",
                            "pair's collision energy at the speed the two blocks met. Only for pairs closing faster than breakMinSpeed;",
                            "the ships' momentum is left to VS (a rotor ploughs on through what it broke).")
                    .translation(key("shipClashSweep"))
                    .comment("Default: true")
                    .define("shipClashSweep", true);

            this.shipClashMaxBlocksPerTick = builder
                    .comment("Performance cap: most blocks the swept ship-vs-ship test may break per level per tick (and per pair of",
                            "ships). Separate from terrainMaxBlocksPerTick: two rotors crossing lose a few dozen blocks in a tick.")
                    .translation(key("shipClashMaxBlocksPerTick"))
                    .comment("Default: 96")
                    .defineInRange("shipClashMaxBlocksPerTick", 96, 0, 4096);

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

            this.breakSoundsPerTick = builder
                    .comment("Performance: when ships, contraptions and fast bodies break many blocks in one tick (a ship ploughing a",
                            "meadow, an arm carving a hillside), only this many of them per level and tick play the block's break",
                            "sound and dust as a mined block does (none right beside another that did). Every sound start makes the",
                            "game wait for the sound thread: dozens a tick froze the screen for up to a second while a fast",
                            "contraption carved stone. 4096 = every break as vanilla.")
                    .translation(key("breakSoundsPerTick"))
                    .comment("Default: 3")
                    .defineInRange("breakSoundsPerTick", 3, 0, 4096);

            this.breakDustPerTick = builder
                    .comment("Of the rest of a tick's mass breaks, this many more throw a lighter puff of the block's dust, without a",
                            "sound; the others break the same way, quietly. 0 = none.")
                    .translation(key("breakDustPerTick"))
                    .comment("Default: 24")
                    .defineInRange("breakDustPerTick", 24, 0, 4096);

            this.mergeBreakDrops = builder
                    .comment("Performance: the drops of the blocks ships, contraptions and fast bodies break in one tick land as merged",
                            "stacks (one per kind of item in each 3-block cell) instead of one item entity per block. The same items:",
                            "a ship mowing a meadow left thousands of separate item entities that slowed the server for minutes.")
                    .translation(key("mergeBreakDrops"))
                    .comment("Default: true")
                    .define("mergeBreakDrops", true);

            this.sweepPlacedLeavesMinSpeed = builder
                    .comment("With sweepPlacedLeaves off, leaves players placed (hedges, leaf roofs) still give to a hull hitting them at",
                            "this speed (m/s) or faster: the tips of a spinning arm, a ship at speed. A raft drifting into a hedge at",
                            "the sweep speed leaves it. 0 = placed leaves go like natural ones.")
                    .translation(key("sweepPlacedLeavesMinSpeed"))
                    .comment("Default: 8.0")
                    .defineInRange("sweepPlacedLeavesMinSpeed", 8.0, 0.0, 1000.0);

            this.shipRedstone = builder
                    .comment("Ships work redstone: a hull touching a button presses it and holds it down while it stays; a hull",
                            "resting on a pressure plate holds it down (any plate, stone ones included: a ship is heavy), weighted",
                            "plates by the ship's mass (shipWeightedPlateKgPerSignal).")
                    .translation(key("shipRedstone"))
                    .comment("Default: true")
                    .define("shipRedstone", true);

            this.shipWeightedPlateKgPerSignal = builder
                    .comment("Kilograms of ship per signal level on a light weighted pressure plate (gold): Valkyrien Skies' oak plank",
                            "weighs 500 kg, so a four-plank raft gives 2 and a 15 t ship the full 15. A heavy plate (iron) takes ten",
                            "times as much per level, as vanilla's 15 and 150 entities do. Capped at 15.")
                    .translation(key("shipWeightedPlateKgPerSignal"))
                    .comment("Default: 1000.0")
                    .defineInRange("shipWeightedPlateKgPerSignal", 1000.0, 1.0, 1.0e9);

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
            builder.comment("Pistons and Valkyrien Skies ships. A piston extending into a ship's hull pushes the ship a block, in",
                            "step with its head; a sticky piston pulls it back a block when it retracts; a piston on a ship that",
                            "pushes against the terrain (or another ship) shoves its own ship off it. Needs Valkyrien Skies and",
                            "enableValkyrienSkiesCompat.")
                    .push("Pistons");

            this.pistonsPushShips = builder
                    .comment("Pistons move ships: a world piston extending into a hull pushes the ship one block the way its head",
                            "goes, over the two ticks the head takes, and a piston on a ship pushing into the terrain shoves the",
                            "ship back the same way. Off: pistons pass through ships as Valkyrien Skies leaves them.")
                    .translation(key("pistonsPushShips"))
                    .comment("Default: true")
                    .define("pistonsPushShips", true);

            this.pistonPushMassKg = builder
                    .comment("How heavy a ship (kg) one piston can move: by default the twelve oak planks a vanilla piston pushes",
                            "(Valkyrien Skies' plank is 500 kg, its stone 2 t). Pistons pushing the same ship the same way add up,",
                            "so a heavier ship needs more of them; a piston that can't move its ship doesn't extend, as against any",
                            "immovable block. A ship scaled down is as heavy as its physics body (its blocks' mass times the cube",
                            "of its scale).")
                    .translation(key("pistonPushMassKg"))
                    .comment("Default: 6000.0")
                    .defineInRange("pistonPushMassKg", 6000.0, 0.0, 1.0e7);

            this.pistonStickyPulls = builder
                    .comment("A sticky piston whose head touches a ship pulls the ship back a block when it retracts.")
                    .translation(key("pistonStickyPulls"))
                    .comment("Default: true")
                    .define("pistonStickyPulls", true);

            this.pistonShipReaction = builder
                    .comment("A piston on a ship that extends into the terrain (or another ship) shoves its own ship a block the",
                            "other way, and a sticky one retracting from the terrain pulls its ship a block toward it.")
                    .translation(key("pistonShipReaction"))
                    .comment("Default: true")
                    .define("pistonShipReaction", true);

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
            builder.comment("Mobs, players and vehicles (with whoever rides them, as one body) that run, fly or fall into each other.",
                            "Minecraft only nudges overlapping entities apart, a few hundredths of a block a tick, so fast ones flew",
                            "straight through each other. Supersedes the old experimental entityCollisionDamage (Kinetic Damage",
                            "settings), which only runs while enableEntityCollisions is off.")
                    .push("Entity Collisions");

            this.enableEntityCollisions = builder
                    .comment("Two bodies meeting faster than entityCollisionMinSpeed collide: along the line between them they exchange",
                            "momentum by their masses (Camafy's body weights, riders included: config/camafy-weight.toml) with",
                            "entityCollisionRestitution, friction acts across it, and each takes the kinetic damage of the speed it",
                            "lost (the same thresholds and armour as any impact). Their paths are swept, so fast bodies can't pass",
                            "through each other between ticks. A body landing on one that stands on the ground crushes it (the ground",
                            "holds it up) and, as mobs aren't solid, falls on past it to land. A player's share of the push goes to",
                            "its own client. Never: spectators, bats, armour stands, the ender dragon, NoAI mobs, anything climbing,",
                            "sleeping or seated on a ship, riders of the same mount, two minecarts (vanilla bumps those), bodies another",
                            "mod owns the contacts of (MKW's karts and racers), bodies whose scoreboard teams don't collide, and pairs",
                            "a minigame host doesn't let touch.")
                    .translation(key("enableEntityCollisions"))
                    .comment("Default: true")
                    .define("enableEntityCollisions", true);

            this.entityCollisionMinSpeed = builder
                    .comment("Slowest closing speed (m/s) that is a collision. Slower contacts are left to Minecraft's gentle pushing, so",
                            "walking or sprinting into a crowd stays what it was. Reference: walk 4.3, sprint 5.6, two players",
                            "sprinting into each other 11.2, a 3-block fall 12.3, a galloping horse 14.")
                    .translation(key("entityCollisionMinSpeed"))
                    .comment("Default: 10.0")
                    .defineInRange("entityCollisionMinSpeed", 10.0, 0.5, 1000.0);

            this.entityCollisionRestitution = builder
                    .comment("How bouncy a collision between two bodies is: 0 = they end up moving together along the line between",
                            "them, 1 = fully elastic. 0.3 matches a body hitting a ship (entityRestitution).")
                    .translation(key("entityCollisionRestitution"))
                    .comment("Default: 0.3")
                    .defineInRange("entityCollisionRestitution", 0.3, 0.0, 1.0);

            this.entityCollisionFriction = builder
                    .comment("Friction between two bodies that meet at an angle: across the line between them each is dragged toward",
                            "the other's motion by at most this times the push along it (Coulomb), and never past moving together.",
                            "0 = a glancing hit only pushes them apart.")
                    .translation(key("entityCollisionFriction"))
                    .comment("Default: 0.3")
                    .defineInRange("entityCollisionFriction", 0.3, 0.0, 2.0);

            this.entityCollisionDamageMultiplier = builder
                    .comment("Multiplies the damage two colliding bodies deal each other (after kineticDamageMultiplier). 0 = collisions",
                            "push but never hurt.")
                    .translation(key("entityCollisionDamageMultiplier"))
                    .comment("Default: 1.0")
                    .defineInRange("entityCollisionDamageMultiplier", 1.0, 0.0, 100.0);

            builder.pop();
            builder.comment("Projectiles that meet in the air. Vanilla lets projectiles fly through each other (a projectile's own",
                            "hit test only looks for mobs and the few 'pickable' projectiles: fireballs and shulker bullets).")
                    .push("Projectile Collisions");

            this.enableProjectileCollisions = builder
                    .comment("Every projectile collides with every other in flight: arrows (spectral and tipped too, and modded",
                            "arrows), tridents, thrown eggs, snowballs, splash and lingering potions, ender pearls, bottles o'",
                            "enchanting, fireballs and wither skulls, shulker bullets, llama spit, and whatever other mods throw or",
                            "shoot (Supplementaries' and the like). Their paths are swept each tick, so two arrows can't pass",
                            "through each other. Arrows and tridents exchange momentum by mass (Camafy's weights: an arrow 0.05 kg,",
                            "a trident 2) with projectileRestitution: two arrows meeting head-on stop and tumble down, a glancing",
                            "hit deflects both, and afterwards each is an ordinary arrow (it sticks where it lands and can be picked",
                            "up as its pickup rule allows). Everything else meets the other as it meets anything it hits - an egg",
                            "bursts, a snowball puffs, a potion splashes there, a bottle spills its experience, an ender pearl lands",
                            "its thrower there, a fireball or wither skull explodes there - and slows the other by the momentum it",
                            "carried. Never: fishing bobbers, firework rockets and grappling hooks (the #fullstop:no_projectile_collision",
                            "tag), projectiles a volley fires together, and any hit Forge's projectile impact event cancels.")
                    .translation(key("enableProjectileCollisions"))
                    .comment("Default: true")
                    .define("enableProjectileCollisions", true);

            this.projectileRestitution = builder
                    .comment("How bouncy two arrows or tridents meeting in the air are: 0 = along the line between them they end up",
                            "moving together, 1 = fully elastic. At 0.1 two arrows meeting head-on at the same speed each bounce back",
                            "at a tenth of it. A thrown item that breaks never bounces.")
                    .translation(key("projectileRestitution"))
                    .comment("Default: 0.1")
                    .defineInRange("projectileRestitution", 0.1, 0.0, 1.0);

            builder.pop();
            builder.comment("Create Catastrophes: with Create installed, every moving part of it is dangerous. Moving contraptions",
                            "(bearings and windmills, pistons, gantries, pulleys and elevators, minecart contraptions, trains) strike",
                            "what they run into like a Valkyrien Skies ship does, spinning gears pull in and hurt whoever stands too",
                            "close, and water wheels drag bodies under. Nothing here does anything without Create.")
                    .push("Create");

            this.enableCreateCompat = builder
                    .comment("When true and Create is installed, Create's moving parts are dangerous (the settings below). Turn off to",
                            "play with Create as if FullStop didn't know it.")
                    .translation(key("enableCreateCompat"))
                    .comment("Default: true")
                    .define("enableCreateCompat", true);

            this.contraptionImpacts = builder
                    .comment("A moving contraption strikes the bodies it runs into exactly as a ship's hull does: the speed of the",
                            "contraption's block at the point of contact (a bearing's arm is fastest at its tip) against the body's own,",
                            "the same two-body law (a contraption is driven: it never gives way), the same damage, knock-away, friction,",
                            "lift and crush against what's behind (shipEntityImpacts' settings: pushMinSpeed, gentlePushSpeed,",
                            "entityRestitution, hullFriction, hitLift, maxHitLaunchSpeed, pinnedDamageMultiplier). Slower than",
                            "gentlePushSpeed it only pushes, as Create itself does; bodies standing on or riding the contraption move",
                            "with it and are never hit. Off: contraptions push bodies out of the way as Create does.")
                    .translation(key("contraptionImpacts"))
                    .comment("Default: true")
                    .define("contraptionImpacts", true);

            this.contraptionDamageMultiplier = builder
                    .comment("Multiplies the damage a contraption deals to what it strikes (after kineticDamageMultiplier).")
                    .translation(key("contraptionDamageMultiplier"))
                    .comment("Default: 1.0")
                    .defineInRange("contraptionDamageMultiplier", 1.0, 0.0, 100.0);

            this.contraptionsReplaceTrainDamage = builder
                    .comment("With contraptionImpacts on, a train or minecart contraption running into a body is judged like any other",
                            "contraption hit, and Create's own run-over damage (its trainsCauseDamage) stands down so nothing is hurt",
                            "twice. Off: Create's train damage as Create deals it, and trains are left to it.")
                    .translation(key("contraptionsReplaceTrainDamage"))
                    .comment("Default: true")
                    .define("contraptionsReplaceTrainDamage", true);

            this.maxContraptionHitChecksPerTick = builder
                    .comment("Most bodies checked against moving contraptions per level per tick (each a swept test through the",
                            "contraption's motion). Contraptions slower than pushMinSpeed everywhere cost nothing.")
                    .translation(key("maxContraptionHitChecksPerTick"))
                    .comment("Default: 256")
                    .defineInRange("maxContraptionHitChecksPerTick", 256, 0, 100000);

            this.debugContraptionLog = builder
                    .comment("Log every contraption hit (closing speed, the block, damage, push) to the server log.")
                    .translation(key("debugContraptionLog"))
                    .comment("Default: false")
                    .define("debugContraptionLog", false);

            this.contraptionTerrainImpacts = builder
                    .comment("A moving contraption that sweeps into the terrain at contraptionTerrainMinSpeed or faster meets it like a",
                            "ship's hull does: the speed of its block there, and FullStop's material rule (toughness = hardness x",
                            "(block mass / 1000 kg)^massInfluence) decides which side gives. A fast iron arm carves stone, cracking",
                            "it first when one hit isn't enough (hits add up); bedrock, ship_unbreakable blocks and protected blocks",
                            "never break. What nothing gives way to stops the contraption (contraptionImpactStress). Slower than",
                            "contraptionTerrainMinSpeed, a turning arm or a cart jams against them instead (contraptionSlowJams). Also needs kineticBlockBreaking.")
                    .translation(key("contraptionTerrainImpacts"))
                    .comment("Default: true")
                    .define("contraptionTerrainImpacts", true);

            this.contraptionBlocksBreak = builder
                    .comment("When the contraption's own block is the weaker one (a wool sail hitting stone, planks hitting obsidian),",
                            "it is knocked off the moving contraption and drops, and the rest keeps moving. Blocks with block entities",
                            "(chests, drills, saws), seats, controls and the block the contraption hangs from are never knocked off.",
                            "Off: the contraption's blocks never break; a contact where they would is one nothing gives way to.")
                    .translation(key("contraptionBlocksBreak"))
                    .comment("Default: true")
                    .define("contraptionBlocksBreak", true);

            this.contraptionImpactStress = builder
                    .comment("Every hit a contraption makes on terrain or a ship loads the kinetic network driving it (its bearing,",
                            "piston, pulley or gantry shaft) with stress, in proportion to the hit (contraptionStressPerImpact),",
                            "fading over about a second. A hit nothing gives way to (bedrock, a block too tough to break this hit, a",
                            "heavy ship) jams it: it stops with its block against the obstacle, stalled as Create stalls a contraption,",
                            "and its network is held overstressed (everything on it stops, as any overstressed network does) for as",
                            "long as its drive would still turn it into the obstacle and the obstacle is there. Stopping or reversing",
                            "the motor, or moving the obstacle, lets it go. Minecart contraptions and trains have no network: they",
                            "lose speed instead, and stop dead on a jam.")
                    .translation(key("contraptionImpactStress"))
                    .comment("Default: true")
                    .define("contraptionImpactStress", true);

            this.contraptionShipImpacts = builder
                    .comment("A contraption sweeping into a Valkyrien Skies ship at contraptionTerrainMinSpeed or faster pushes the ship",
                            "at the point it hits, as an immovable driven body would (a raft is swatted, a big ship barely moves), and",
                            "breaks blocks on whichever side is weaker (the ship's by the hullDamage rules). A ship far heavier than",
                            "the contraption, or a static one, jams it. Contraptions that are themselves on a ship are left to the ship.")
                    .translation(key("contraptionShipImpacts"))
                    .comment("Default: true")
                    .define("contraptionShipImpacts", true);

            this.contraptionTerrainMinSpeed = builder
                    .comment("Slowest speed (m/s, of the contraption's block where it meets the terrain or a ship) that counts as a",
                            "hit. A bearing arm at 8 RPM moves about 3.4 m/s four blocks out, at 64 RPM about 27 m/s.")
                    .translation(key("contraptionTerrainMinSpeed"))
                    .comment("Default: 5.0")
                    .defineInRange("contraptionTerrainMinSpeed", 5.0, 0.0, 1000.0);

            this.contraptionSlowJams = builder
                    .comment("A contraption turned by a bearing or carried by a minecart whose block moves slower than",
                            "contraptionTerrainMinSpeed doesn't strike what it runs into, it presses: it stops against a solid block and",
                            "jams (its network overstressed until its drive stops or turns away), at any speed, instead of passing",
                            "through it as Create lets it. It pushes plants and natural leaves over (breaks them); its drills, saws and",
                            "harvesters work through what they can as in Create. Pistons, pulleys and gantries Create stops itself.")
                    .translation(key("contraptionSlowJams"))
                    .comment("Default: true")
                    .define("contraptionSlowJams", true);

            this.contraptionsMeet = builder
                    .comment("Moving contraptions meet each other (two arms on bearings side by side whose sweeps cross): the weaker",
                            "block is knocked off its contraption (both when they are about as tough), and when neither gives, or",
                            "they press slowly, both jam against each other (overstressed) until either drive stops or turns away.",
                            "Needs contraptionTerrainImpacts or contraptionShipImpacts.")
                    .translation(key("contraptionsMeet"))
                    .comment("Default: true")
                    .define("contraptionsMeet", true);

            this.contraptionImpactStrength = builder
                    .comment("How hard a contraption hits terrain and ships: the breaking energy of a hit is the striking block's",
                            "kinetic energy at that speed (times terrainEnergyFraction) times this. A contraption is driven: its",
                            "motor keeps pushing through the hit, so it hits harder than the block alone would. At 4 an iron",
                            "arm at 64 RPM breaks stone in one pass; at 32 RPM it cracks it and grinds through in a few.")
                    .translation(key("contraptionImpactStrength"))
                    .comment("Default: 4.0")
                    .defineInRange("contraptionImpactStrength", 4.0, 0.0, 1000.0);

            this.contraptionStressPerImpact = builder
                    .comment("Stress (SU) a contraption's network takes per FullStop energy unit of a hit (about 46 kJ). Breaking a",
                            "stone block costs about 12 units (768 SU at the default): a blip on a big network, all a small water",
                            "wheel can spare on a small one (never more: contraptionHitsOverstress). It fades by about a tenth each tick.")
                    .translation(key("contraptionStressPerImpact"))
                    .comment("Default: 64.0")
                    .defineInRange("contraptionStressPerImpact", 64.0, 0.0, 1.0E7);

            this.contraptionHitsOverstress = builder
                    .comment("Whether a contraption's hit that got through (it broke or knocked off a block) may overstress its network",
                            "by itself, stopping everything on it until the load fades (about a second). Off: the hit's load is held to",
                            "what the network can still carry beside everything else (its stressometer peaks, the machine runs on);",
                            "only a jam (a hit nothing gave way to) overstresses it.")
                    .translation(key("contraptionHitsOverstress"))
                    .comment("Default: false")
                    .define("contraptionHitsOverstress", false);

            this.maxContraptionTerrainChecksPerTick = builder
                    .comment("Most block positions checked against the terrain and ships per level per tick, over all moving",
                            "contraptions (only their outer blocks moving at contraptionTerrainMinSpeed or faster are checked).")
                    .translation(key("maxContraptionTerrainChecksPerTick"))
                    .comment("Default: 8192")
                    .defineInRange("maxContraptionTerrainChecksPerTick", 8192, 0, 1000000);

            this.contraptionSonicBooms = builder
                    .comment("A contraption whose fastest point passes the speed of sound (343 m/s) booms like a ship: once per",
                            "supersonic stretch for everyone its shock reaches, its riders hearing nothing; a bearing's arm whose",
                            "tips stay supersonic makes the shock train of a supersonic rotor (one crack per tip per turn). At",
                            "Create's top speed, 256 RPM, an arm's tip passes the barrier from 12.8 blocks out.")
                    .translation(key("contraptionSonicBooms"))
                    .comment("Default: true")
                    .define("contraptionSonicBooms", true);

            this.contraptionRidersTurn = builder
                    .comment("Players and mobs standing on a contraption that turns about the vertical (a bearing's turning plate, a",
                            "carousel) turn with it, as on a Valkyrien Skies ship: Create carries them round, this turns their facing",
                            "too (a player's every frame, on its own client). Seated passengers are Create's own.")
                    .translation(key("contraptionRidersTurn"))
                    .comment("Default: true")
                    .define("contraptionRidersTurn", true);

            this.gearHazards = builder
                    .comment("Spinning gears are dangerous where two of them mesh: a body at the in-running side of the nip (where",
                            "both gears' teeth run into the mesh: two cogwheels side by side, or a large and a small one diagonally)",
                            "is drawn in and crushed (gearDamage), and jams them (gearJamStress). A single spinning gear never pulls:",
                            "touching its teeth pushes you off, along the way they move (a nudge when slow), bites (gearTouchDamage)",
                            "and a very fast one flings you (gearFlingSpeed). No bite of a gear deals more than gearMaxDamage.",
                            "Encased cogwheels, gearboxes and shafts are safe. Creative",
                            "and spectator players are never caught (a creative player on foot is still pushed and flung).")
                    .translation(key("gearHazards"))
                    .comment("Default: true")
                    .define("gearHazards", true);

            this.gearPullStrength = builder
                    .comment("How hard gears move bodies: the nip of two gears drawing a body in (1 = at 64 RPM a nip draws about a",
                            "third as hard as walking pushes, growing with the square root of the speed: half at 16 RPM, double at",
                            "256), and a single gear pushing off what touches it. A sneaking player braces against it, heavy bodies",
                            "are harder to move.")
                    .translation(key("gearPullStrength"))
                    .comment("Default: 1.0")
                    .defineInRange("gearPullStrength", 1.0, 0.0, 10.0);

            this.gearDamage = builder
                    .comment("Damage the nip of two gears deals per bite at 64 RPM (a bite whenever the damage cooldown allows, twice",
                            "a second). It grows with the speed to the 0.75 power (16 RPM: 0.35x, 256 RPM: 2.8x), a quarter more with",
                            "a large cogwheel in the pair, at most gearMaxDamage. Nothing below 8 RPM (a single gear's bite is gearTouchDamage). 0 turns the bites off",
                            "but keeps the pull.")
                    .translation(key("gearDamage"))
                    .comment("Default: 3.0")
                    .defineInRange("gearDamage", 3.0, 0.0, 1000.0);

            this.gearTouchDamage = builder
                    .comment("Damage a single spinning gear's teeth deal a body touching them, per bite (whenever the damage cooldown",
                            "allows, twice a second at most), when they hit it at 7.5 m/s (a cogwheel's rim at 128 RPM), in",
                            "proportion to how fast they hit it: a cogwheel at 64 RPM 1, at 256 RPM 4, a large cogwheel at 256 RPM",
                            "8. Teeth slower than 1 m/s against the body (a cogwheel under about 17 RPM) don't hurt. Never more",
                            "than gearMaxDamage. 0: a single gear only pushes and flings.")
                    .translation(key("gearTouchDamage"))
                    .comment("Default: 2.0")
                    .defineInRange("gearTouchDamage", 2.0, 0.0, 1000.0);

            this.gearMaxDamage = builder
                    .comment("The most one bite of a gear may deal (a single gear's teeth or the nip of two gears), so gears hurt",
                            "but never kill a healthy player outright: 8 is 4 hearts, 40% of a full health bar.")
                    .translation(key("gearMaxDamage"))
                    .comment("Default: 8.0")
                    .defineInRange("gearMaxDamage", 8.0, 0.0, 1000.0);

            this.gearMinRpm = builder
                    .comment("Gears and water wheels turning slower than this (RPM) catch nothing.")
                    .translation(key("gearMinRpm"))
                    .comment("Default: 4.0")
                    .defineInRange("gearMinRpm", 4.0, 0.0, 256.0);

            this.gearJamStress = builder
                    .comment("Stress a body caught in the nip of two gears puts on their network, as Create counts a machine's",
                            "stress impact: stress units per RPM, per kilogram of the body. At 0.5 a player (75 kg) jams like a",
                            "37.5 SU/RPM machine, more than a lone water wheel or a hand crank supplies, a pig (150 kg) like 75 and",
                            "a cow (650 kg) like 325; a creative motor never notices. Overstressed, the gears stop and the body",
                            "stays wedged until it walks out. 0: bodies never jam the gears.")
                    .translation(key("gearJamStress"))
                    .comment("Default: 0.5")
                    .defineInRange("gearJamStress", 0.5, 0.0, 1000.0);

            this.gearFlingSpeed = builder
                    .comment("A single gear whose teeth hit a body at least this fast (m/s) flings it instead of pushing it off: thrown",
                            "along the teeth and out from the gear like a hull's strike, at (1 + entityRestitution) of that speed,",
                            "lifted off the ground (hitLift), at most maxHitLaunchSpeed; once per gear per half second. The throw",
                            "itself never hurts; what it is thrown into (a wall, the ground) does, as any impact. Teeth speed is the",
                            "rim's: a cogwheel's at 256 RPM is 15 m/s, at 128 RPM 7.5; a large cogwheel's twice that. At 10 a cogwheel",
                            "flings at 256 RPM and a large one from about 128. 0: any turning gear flings; past 30 none does.")
                    .translation(key("gearFlingSpeed"))
                    .comment("Default: 10.0")
                    .defineInRange("gearFlingSpeed", 10.0, 0.0, 1000.0);

            this.gearsPushShips = builder
                    .comment("Gears act on Valkyrien Skies ships as on bodies: a single gear's teeth push a hull that touches them",
                            "(fling it at gearFlingSpeed), applied where they touch, so a hull caught off its middle turns; the less",
                            "the heavier the ship (gearShipMass). A hull in the in-running nip of two meshing gears is drawn in and",
                            "jams them with gearJamStress for its mass: any ship of size stops the gears. Gears on a ship act on",
                            "other ships, never on their own.")
                    .translation(key("gearsPushShips"))
                    .comment("Default: true")
                    .define("gearsPushShips", true);

            this.gearsPushVehicles = builder
                    .comment("Gears act on vehicles (entity types in #fullstop:gear_pushed: Automobility's and RIAutomobility's cars)",
                            "as on bodies: pushed, flung, drawn into a nip and jamming it, by their weight (a car is 1000 kg: pushed",
                            "about a quarter as hard as a player, and it stops most gears it is wedged in). Applied on the side that",
                            "drives the vehicle, through Camafy's impulses.")
                    .translation(key("gearsPushVehicles"))
                    .comment("Default: true")
                    .define("gearsPushVehicles", true);

            this.gearShipMass = builder
                    .comment("The mass (kg) of ship a gear moves as it moves a body: a ship this heavy or lighter takes the whole",
                            "push or fling, a heavier one that share of it (a 10 t ship at 1000: a tenth).")
                    .translation(key("gearShipMass"))
                    .comment("Default: 1000.0")
                    .defineInRange("gearShipMass", 1000.0, 1.0, 1.0E9);

            this.waterWheelHazards = builder
                    .comment("Turning water wheels (both sizes) drag bodies under: above the axle the paddles sweep a body off the",
                            "wheel and down into the water; on the downstroke and below the axle every paddle presses it under, so it",
                            "can't surface and drowns as in any water, unless it swims out sideways (the wheel holds weakly along its",
                            "axle), the wheel stops, or it has been held waterWheelHoldSeconds. A wheel driven fast (24 RPM or more)",
                            "also bites like a gear out of water.")
                    .translation(key("waterWheelHazards"))
                    .comment("Default: true")
                    .define("waterWheelHazards", true);

            this.waterWheelGrip = builder
                    .comment("How firmly water wheel paddles hold a body (1 = as described in waterWheelHazards; below about 0.6",
                            "a swimming body can rise out from under the wheel).")
                    .translation(key("waterWheelGrip"))
                    .comment("Default: 1.0")
                    .defineInRange("waterWheelGrip", 1.0, 0.0, 10.0);

            this.waterWheelHoldSeconds = builder
                    .comment("Longest a water wheel holds a body under without a break, in seconds; then it lets go for five seconds.",
                            "Air lasts 15 seconds and drowning then costs 2 health a second, so the default drowns what can't escape.")
                    .translation(key("waterWheelHoldSeconds"))
                    .comment("Default: 30.0")
                    .defineInRange("waterWheelHoldSeconds", 30.0, 0.0, 600.0);
            builder.pop();
        }

        private static String key(String valueName) {
            return "config.fullstop." + valueName;
        }
    }

    public static class ClientConfigValues {
        public final ForgeConfigSpec.BooleanValue rotateCamera;
        public final ForgeConfigSpec.BooleanValue rotateCameraOnlyWhenFlying;
        public final ForgeConfigSpec.DoubleValue shockTrainVolume;
        public final ForgeConfigSpec.BooleanValue blackoutOnlyWithoutAir;
        public final ForgeConfigSpec.BooleanValue machCones;
        public final ForgeConfigSpec.DoubleValue machConeOpacity;

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

            this.shockTrainVolume = builder
                    .comment("How loud a supersonic rotor's train of cracks is on this client (1 = full; the default is about 4 dB",
                            "quieter). The single boom when something first passes the speed of sound is not affected.")
                    .translation("config.fullstop.client.shockTrainVolume")
                    .comment("Default: 0.63")
                    .defineInRange("shockTrainVolume", 0.63, 0.0, 1.0);

            this.blackoutOnlyWithoutAir = builder
                    .comment("When true, running out of air dims the screen and muffles sound only near the end of the air bar:",
                            "it begins gently over the last two bubbles, fades faster once the bar is empty (out cold about",
                            "5 seconds later) and comes back over 1.5 seconds after a breath. When false, the old curve:",
                            "darker as the bubbles go, black at the last.")
                    .translation("config.fullstop.client.blackoutOnlyWithoutAir")
                    .comment("Default: true")
                    .define("blackoutOnlyWithoutAir", true);

            this.machCones = builder
                    .comment("Draw the shock cone (a faint, foggy Mach cone) behind anything moving faster than sound: an entity, a",
                            "ship flying supersonic, or the tips of a supersonic rotor or Create contraption; it opens narrower the",
                            "faster it goes and is as big as what makes it.")
                    .translation("config.fullstop.client.machCones")
                    .comment("Default: true")
                    .define("machCones", true);

            this.machConeOpacity = builder
                    .comment("How visible the shock cones are (0..1).")
                    .translation("config.fullstop.client.machConeOpacity")
                    .comment("Default: 0.6")
                    .defineInRange("machConeOpacity", 0.6, 0.0, 1.0);

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
