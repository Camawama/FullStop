[<img alt="Mod Loader: Forge" src="https://img.shields.io/badge/loader-forge-1976d2?style=flat-square"/>](https://files.minecraftforge.net/)
[<img alt="Curse Forge" src="https://cf.way2muchnoise.eu/1118198.svg?badge_style=flat"/>](https://www.curseforge.com/minecraft/mc-mods/full-stop)

[<img alt="Discord" src="https://img.shields.io/discord/824044029502292011?style=for-the-badge&logo=discord"/>](https://discord.gg/c9DshjA8jF)

<img src="https://raw.githubusercontent.com/Camawama/FullStop/main/src/main/resources/fullstop.png" alt="Mod Logo" width="128" height="128">

[Curseforge Page](https://www.curseforge.com/minecraft/mc-mods/full-stop)

[Modrinth Page](https://modrinth.com/mod/full-stop!-)

# 🛑 Full Stop!

Full Stop! is a Forge 1.20.1 overhaul that treats velocity like a first‑class mechanic. Momentum matters. Sudden stops hurt, fast hits hit harder, and the world fights back when you slam into it.

**Status:** Beta. Expect bugs, please report them in the issue tracker.

## What it does (current behavior)
- **Velocity‑scaled combat damage**: attacks scale up or down based on approach velocity, including projectile momentum and durability costs that match the final damage dealt. (See `Physics.calcNewDamage`.)
- **Kinetic collision damage**: impacts with blocks or entities use stopping force, block hardness, armor/toughness, and special block materials (wool/leaves/hay/water) to shape damage. (See `Physics.calcKineticDamageTotal` + `applyBlockCollisionDamage`.)
- **Bounce physics**: slime/honey/beds change restitution, and strong impacts rotate the camera toward the bounce direction. (See `Physics.bounceEntity`.)
- **Block interactions on impact**: doors, trapdoors, fence gates, and note blocks can be toggled/triggered by collisions. (See `KineticInteractions.handleBlockImpacts`.)
- **Kinetic block breaking**: high‑energy impacts can crack or break blocks, including fragile glass/ice handling and grass‑to‑path conversion. (See `KineticInteractions`.)
- **Falling‑block “sandblasting”**: falling blocks can strip logs, break stripped logs, and de‑oxidize copper one stage at a time. (See `KineticInteractions.handleFallingBlockImpact`.)
- **Sprain status effect**: hard, mostly‑downward impacts apply sprain (slows movement and suppresses jumping). (See `SprainEffect`.)
- **G‑force effects**: sustained high forces can apply nausea and blackout‑style blindness. (See `Physics.applyForceEffects`.)
- **Entity collision transfer**: entity‑to‑entity impacts transfer velocity and distribute damage by mass, with special handling for iron golems, slime, minecarts, and stacks. (See `Physics.handleEntityCollision` + `applyEntityCollisionDamage`.)
- **Water skipping**: shallow‑angle impacts can skip across water with configurable dampening. (See `Physics.shouldWaterSkip`.)

## Config highlights
Full Stop ships a server config with gameplay‑tunable values. Some key knobs:
- **Velocity damage curve** (`velocityIncrement`, `exponentiationConstant`, min/max damage percent)
- **Kinetic thresholds** (horizontal/vertical minimums)
- **Collision toggles** (entity collision damage, kinetic block breaking)
- **Kinetic protection & dampening** (new enchant multipliers and attribute scaling)
- **Water skipping** (angle threshold + speed dampening)

See `FullStopConfig` for the full list.

## Compatibility notes
- Written for **Forge 1.20.1**.
- Designed to be configuration‑driven; many features can be dialed down or disabled if they clash with a pack.

## Roadmap / ideas in flight
- More robust stacking/vehicle logic for extreme collisions.
- Additional tuning for edge‑case interactions (elytra speed changes, boat physics on ice).
- Optional APIs once the core behavior stabilizes.

## License
This project is licensed under the GNU General Public License v3.0 (GPLv3). If you distribute a modified version, you must ship the source under the same license.
