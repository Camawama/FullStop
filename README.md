[<img alt="Mod Loader: Forge" src="https://img.shields.io/badge/loader-forge-1976d2?style=flat-square"/>](https://files.minecraftforge.net/)
[<img alt="Curse Forge" src="https://cf.way2muchnoise.eu/1118198.svg?badge_style=flat"/>](https://www.curseforge.com/minecraft/mc-mods/full-stop)


[<img alt="Discord" src="https://img.shields.io/discord/824044029502292011?style=for-the-badge&logo=discord"/>](https://discord.gg/c9DshjA8jF)

<img src="https://raw.githubusercontent.com/Camawama/FullStop/main/src/main/resources/fullstop.png" alt="Mod Logo" width="128" height="128">

[Curseforge Page](https://www.curseforge.com/minecraft/mc-mods/full-stop)

[Modrinth Page](https://modrinth.com/mod/full-stop!-)

# 🛑 Full Stop!

NOTICE: This mod is in Beta. There will be many bugs! Please report all bugs on the issue tracker!

## Overview
Full Stop! makes velocity dangerous. Every sprint, dive, crash, and fall is measured, and the world pushes back: walls hurt at speed, blocks crack and shatter under hard impacts, boats skip across water, sand swallows divers whole, and the sky itself runs out of air if you climb too high. This mod is inspired by [Collision Damage by fonnymunkey](https://www.curseforge.com/minecraft/mc-mods/collision-damage) and started as a fork of [Velocity Based Damage Deluxe by kawaiicakes](https://www.curseforge.com/minecraft/mc-mods/velocity-based-damage-deluxe).

## Features

### Kinetic Physics
- 💥 **Dynamic Kinetic Damage:** Impact damage is calculated from real measured stopping force, factoring in mass, velocity, impact direction, block hardness, and armor. Fall damage, wall crashes, and ceiling hits all use the same system. Vanilla fall damage is replaced entirely.
- 🏃 **Velocity-Based Attack Scaling:** Speed is power. Attacks deal more damage when moving toward your target and less when retreating.
- 🧱 **Kinetic Block Breaking:** Become a wrecking ball. Fast, heavy entities crack and shatter blocks on impact. Fragile blocks like glass give way easily, but only to impacts that actually drive into them. Ice is tiered: impacts leave real, persistent cracks on the block (the mining-crack overlay), and cracked ice takes less to shatter. Cracks are permanent (and save with the world) unless you configure them to heal.
- 🕳️ **Block Phasing and Engulfment:** At high speed you punch through leaves, and dive straight into sand, gravel, or snow. Engulfing blocks bleed off your speed and bury you inside. Dig or jump your way back out.
- 🪨 **Gravity-Affected Blocks:** Slime and honey blocks fall like sand when nothing supports them, and sticky blocks cling to neighbors on any face. A falling sticky block even grabs onto blocks it falls past.
- 🤼 **Entity Collisions:** Collide with entities using momentum transfer, and even land on a mob or boat to ride it. (Experimental, off by default.)

### The World Reacts
- 🚪 **Kinetic Interactions:** Sprint into doors and fence gates to fling them open (only from the side they swing toward), crash into note blocks to play them, ring bells, press buttons by running into them, and turn grass to path by landing hard on it. Each interaction has its own config toggle.
- 🌪️ **Sand Blasting:** Falling sand strips logs and scrapes oxidation and wax off copper.
- 🟢 **Slime Bounce Overhaul:** Bounce off slime in *any* direction, walls and ceilings included, with optional camera rotation to match. Beds and honey have their own impact behavior.
- 🌊 **Water Physics:** Skim across water at a shallow angle like a thrown stone (minecarts too), dive clean to survive, or belly flop and regret it. Underwater movement has extra drag and sound.
- 🍯 **Viscous Blocks:** Honey and the soul sand family slow you down while you move beside them, not just on top.

### The Body Reacts
- 😵 **G-Force Effects (G-LOC):** Sustained high-G maneuvers bring tunnel vision, muffled audio, and full blackout if you keep pushing — past the blackout you are blinded, with nausea that lingers after your sight returns. Spinning round and round or running in tight circles makes you dizzy (nausea) without blacking out. FOV stretches with acceleration.
- 🫁 **Atmosphere and Pressure:** Air thins out at high altitude. Climb (or teleport) too high and your air supply drains, slowly at first, faster the higher and longer you stay. Deep water crushes and drowns you faster below configurable depths. Respiration is renamed **Better Breathing** and slows thin-air suffocation the same way it slows drowning.
- 🧪 **Status Effects:**
  - **Clarity:** Focus your mind to resist G-force effects.
  - **Vertigo:** Amplifies G-force penalties. Deadly in elytra combat.
  - **Sprain:** Land too hard and you'll limp for a while.
  - **Acclimation:** The high-altitude Water Breathing. Brew it with a Phantom Membrane and breathe easy in thin air.
- ⚖️ **Attributes:** Kinetic Dampening reduces impact damage (leather armor grants some naturally). Lung Capacity controls how long your breath lasts in thin air.
- ✨ **Enchantments:**
  - **Pullback:** The opposite of Knockback. Pull your enemies closer.
  - **Reflective:** Armor that throws kinetic energy back at whatever hits you.
  - **Kinetic Protection:** Specialized armor protection against high-velocity impacts.

### Quality of Life
- 💀 **Detailed Death Messages:** Death messages report the exact speed of the fatal impact.
- 🚢 **Valkyrien Skies Compatibility:** Riding a moving ship doesn't count as your own velocity, and impacts against ship blocks are recognized.
- ⚓ **Ship Weight** (formerly the WeightRS mod): what stands on a Valkyrien Skies ship weighs it down, jumping off kicks it, and landing on it slams it. WeightRS's settings import automatically.
- ⛵ **Ship Collisions:** a moving ship knocks away and hurts what it hits (players included; the seated pilot gets the credit; at any speed - an 80 m/s ship launches a player at about 104 m/s, and the body of whatever it kills flies on), crushes what it pins, and crashes into terrain the way its materials say: at each contact the weaker block gives, weighed by hardness and Valkyrien Skies' block mass. A stone hull crushes grass, a wooden hull rammed into stone splinters while the stone holds, obsidian against obsidian holds. Ships at speed plough through tree canopies and soft plants, and every impact sounds like both blocks that met - louder and deeper the harder the hit.
- 🛳️ **Ship Riders:** what rides a Valkyrien Skies ship keeps the momentum the ship loses. When a ship crashes, brakes, lurches or swerves harder than riders can hold on (1 g by default), they keep the difference: a ship that stops dead throws its crew forward off the bow, into whatever it hit. Riders anchored to the deck stay put: standing on slime or honey (`fullstop:sticky`), on Gravity Unbound's gravity plating, or clinging with its Surface Cling boots. Seated riders stay seated unless you say otherwise.
- 🏎️ **Plays well with movement mods:** mods that script an entity's motion (MKW Mobility's karts, grapples, meteors) claim it through Camafy's physics-ownership contract, and FullStop keeps its hands off whatever they claim - no crash damage, g-force effects, bounces, pushes or block breaking for a kart or its driver.
- **Gravity Unbound Compatibility:** Impacts are measured in your gravity frame, so being pulled into a wall by a gravity field hurts like the fall it is, walls and floors are what the field says they are, and momentum exchanged with the mod stays in world space.
- 📦 **Data-Driven Tags:** Block behaviors (fragile, crackable, cushioning, phaseable, engulfing, gravity-affected, slowing, and more) are plain block tags under `data/fullstop/tags/`, so datapacks and other mods can customize everything without code. Entity tags too: blacklist modded entities from FullStop physics entirely (`fullstop:physics_blacklist`), or opt modded projectiles like grappling hooks into the full physics — bounces, block breaking, collision damage (`fullstop:physics_projectiles`).
- 🛠️ **Debug Mode:** Press `F3 + V` to visualize collision raycasts in real time. Red rays are collision-grade hits, yellow means touching, green is clear.

## Configuration
Nearly everything is tunable in the server config (per world: `<world>/serverconfig/fullstop-server.toml`): damage thresholds, block breaking, each kinetic interaction (doors, buttons, note blocks, bells, sand blasting), pressure simulation, phasing speed, and Valkyrien Skies / Gravity Unbound compat. Entity weights come from Camafy's shared weight model (`config/camafy-weight.toml`). The G-force thresholds (effect start, blackout, spin nausea) are server settings — they are gameplay, shifted per player by the Clarity and Vertigo potions. Client config covers only the bounce camera (including an elytra-only mode).

With Valkyrien Skies installed (and `enableValkyrienSkiesCompat` on) two more sections apply:

- **Ship Weight** — `enableShipWeight` (master switch; stays off while the old WeightRS mod is installed), `includePlayers`, `gravity` (9.81 m/s²; VS pulls ships at 10), `minPhysicsWeightKg`, `enableItemEntityPhysics`, `enableStandingWeight`, `enableJumpImpulse`, `enableFallImpact`, `jumpImpulseMultiplier`, `fallImpactMultiplier`, `minFallDistance` (blocks), and `maxForceNewtons` — the cap on every force FullStop puts on a ship (an impulse is capped at a 1/60 s step of it).
- **Ship Collisions** — `enableShipCollisions` (master switch), entities: `damageEntities`, `pushMinSpeed`, `entityDamageMultiplier`, `entityRestitution`, `pinnedDamageMultiplier`, `creditPilot`, `reactionImpulses`; terrain: `breakTerrain`, `hullDamage` (on: a hull breaks where it hits tougher terrain), `massInfluence`, `breakMinSpeed` (5 m/s), `terrainEnergyFraction`, `breakCostMultiplier`, `terrainBreakDrag`, `terrainMaxBlocksPerTick`, `terrainBlockDrops`, `terrainBreakBlockEntities`, `unpilotedFollowsMobGriefing`, `shipVsShipBreaking` (NONE / FRAGILE / FULL, default NONE); vegetation: `sweepVegetation`, `sweepMinSpeed` (4 m/s), `sweepPlacedLeaves` (off), `sweepDrag`, `sweepMaxBlocksPerTick`; sound: `impactSounds`, `impactSoundMinSpeed` (1.5 m/s), `impactSoundVolume`, `impactParticles`; and `newShipGraceTicks`, `maxHitChecksPerTick`, `debugShipCollisionLog`.
- **Ship Riders** — `deckMomentum` (master switch, on), `deckGrip` (9.81 m/s²: how hard a deck may change speed under a rider before it lets go), `deckThrowLift` (0.15: a thrown rider also gets this share of its kept speed as lift, so it leaves the deck instead of skidding), `deckMomentumSeated` (off: seated riders stay in their seats).

  Riders keep what the deck under their feet lost in one physics frame, less `deckGrip` × the frame's length: `excess = lost × max(0, 1 − deckGrip·dt / |lost|)`. Valkyrien Skies goes on carrying them at the deck's new speed, so in the world they keep their old speed less the grip. How far a thrown rider flies is vanilla's business (a mob or player loses 9% of its horizontal speed to air drag every tick): about 5 blocks from a 23 m/s stop. Never thrown: anchored riders (a `fullstop:sticky` block under the feet, or Camafy's `Gravity.isAnchored`: Gravity Unbound's plating and Surface Cling), seated riders (unless `deckMomentumSeated`), creative-flying players, NoAI mobs, `fullstop:physics_blacklist` entities and bodies another mod moves (MOTION claims: MKW karts, grappling players). A grounded rider never keeps velocity into its deck (a ship landing hard or rising fast presses it down; the deck holds it). Needs Camafy 0.1.3.

  What breaks when a ship hits terrain: every block has a **toughness**, its hardness × (Valkyrien Skies' mass for it ÷ 1000 kg)^`massInfluence` — leaves 0.04, grass 0.75, oak planks 1.0, logs 1.6, stone 3.75, cobblestone 4.8, deepslate 8.7, iron block 39, obsidian 250 (`fullstop:fragile` blocks such as glass count as hardness 0.01; unbreakable blocks are infinitely tough). Hardness alone would rank oak planks (2.0) above stone (1.5); the mass puts them right. At each contact only the WEAKER of the terrain block and the ship's own block there can break, at a cost of toughness(weaker) × `breakCostMultiplier` × r/(r−1) FullStop energy units (r = the toughness ratio; a unit is about 46 kJ): about toughness × 8 against a far tougher striker, a fortune for near-equal materials, never for equal ones. The ship's kinetic energy into the terrain pays, ½·m·v² × `terrainEnergyFraction` per tick, fastest contact first; nothing breaks below `breakMinSpeed`. With the defaults a 150 t wooden ship bumping stone at 3 m/s breaks nothing, at 8 m/s loses about four bow planks (the stone holds), and a 3×3×3 stone cube dropped ten blocks onto grass crushes the grass it lands on. When everything the ship hit gave way it ploughs on with what is left; when anything held, or its own hull gave way, it stays stopped. Broken blocks drop like mined ones, the hull's included.

  Vegetation: VS lets ships pass straight through leaves, so a ship moving at `sweepMinSpeed` or faster breaks the leaves and soft plants (`fullstop:ship_sweepable`) its hull passes through, a tree canopy included; drifting slower, it slides through untouched. Leaves players placed stay unless `sweepPlacedLeaves` is on; crops, water plants and anything waterlogged are never swept.

  Sound: a ship hitting terrain or another ship plays BOTH blocks' own sounds at the contact (a stone cube on grass: grass and stone), louder and deeper the faster the hit and the heavier the ship, with block-crack particles on harder hits. Only new contacts sound, so resting and sliding ships stay quiet; breaking can be off.

  Crashes (breaking and impact sounds) work on all of Valkyrien Skies' physics backends (`physicsBackend` in `<world>/serverconfig/valkyrienskies/vs-core-server.toml`). On **PhysX or Jolt** (`"KRUNCH_PHYSX"`, `"KRUNCH_JOLT"`) they come from VS's own collision events. On the default `KRUNCH_CLASSIC`, which sends none, Camafy (0.1.2+) detects them itself: a ship that suddenly slows has its hull probed for what it hit. The ship tests give the same results on all three; the fallback can miss a glancing scrape that barely slows a ship. Vegetation sweeping and ships hitting entities work on every backend.

  Tags: `fullstop:ship_unbreakable` (blocks that never break, on the terrain or a hull; `#minecraft:wither_immune` by default), `fullstop:ship_sweepable` (the vegetation ships plough through) and `fullstop:ship_immune` (entities ships never hit).

Commands (permission level 2): `/fullstop weigh <targets>` and `/fullstop weigh held` show Camafy's weights, `/fullstop ship info` the nearest ship's mass, motion and grace.

Damage is tunable too. Pick a **fall damage mode**: `KINETIC` (FullStop's stopping-force model, scaled by block hardness) or `VANILLA_PARITY` (impacts deal exactly what vanilla would for the equivalent fall distance, so a 20-block fall onto grass hurts like vanilla). There is also a global kinetic damage multiplier, a block-hardness toggle, and a configurable minimum solid-impact damage.

## Known Incompatibilities
- CustomNPCs (NPCs will not look at players)

🚧 Warning:
Speed is your enemy!

## License & Use
This project, FullStop, is licensed under the GNU General Public License v3.0 (GPLv3). This means:

* You may copy, distribute, and modify this software as long as any derivative work is also licensed under the GPLv3.
* Any software that incorporates code from this project must also be released under the GPLv3.
* The source code must be made available with any distributed version of this software or any derivatives.
