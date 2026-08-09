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
- 😵 **G-Force Effects (G-LOC):** Sustained high-G maneuvers bring tunnel vision, muffled audio, and full blackout if you keep pushing. FOV stretches with acceleration.
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
- 📦 **Data-Driven Tags:** Block behaviors (fragile, crackable, cushioning, phaseable, engulfing, gravity-affected, slowing, and more) are plain block tags under `data/fullstop/tags/`, so datapacks and other mods can customize everything without code. Entity tags too: blacklist modded entities from FullStop physics entirely (`fullstop:physics_blacklist`), or opt modded projectiles like grappling hooks into the full physics — bounces, block breaking, collision damage (`fullstop:physics_projectiles`).
- 🛠️ **Debug Mode:** Press `F3 + V` to visualize collision raycasts in real time. Red rays are collision-grade hits, yellow means touching, green is clear.

## Configuration
Nearly everything is tunable in the server config: damage thresholds, block breaking, each kinetic interaction (doors, buttons, note blocks, bells, sand blasting), pressure simulation, phasing speed, entity weights, and Valkyrien Skies compat. Client config covers the bounce camera (including an elytra-only mode) and G-force effect thresholds.

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
