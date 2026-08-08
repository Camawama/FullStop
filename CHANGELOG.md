# FullStop! Changelog

## Unreleased

### Fixed

- Horizontal collision damage works again for players: the server can never see a player's `horizontalCollision` flag (move packets arrive pre-clipped), so the damage evidence gate rejected every wall/ceiling hit. The client now reports its collision flags in the velocity packet (network protocol bumped to 2). Wall and ceiling impacts — running, sprint-jumping, and elytra — now deal damage past the threshold.
- Fall damage is consistent again: a two-tick impact could land a small partial hit on the contact tick, which started the damage cooldown and swallowed the real hit one tick later (9/15/18/21-block falls dealing one heart). Damage now tops up to the larger value during the cooldown window.
- Standing flush against a wall no longer counts as a collision: block hits now require ~2 m/s of approach speed into the face. This removes the red debug rays while wall-hugging, the sound/particle spam when stopping beside a wall, and the honey-wall collision spam.
- Brushing along slime/honey walls no longer bounces or dead-stops the player: bounces now require a mostly-direct impact (or ≥8 m/s into the surface, so fast grazes still bounce).
- Boats no longer visually bounce off slime long before reaching it: vanilla boats interpolate over 10 ticks (other entities use 3), leaving the rendered boat several blocks behind its real position; boat interpolation now matches other entities.
- Entering a minecart (or any mount) from a distance no longer reads as a violent movement that breaks nearby blocks and kills the rider — mount/dismount position snaps are treated as teleports.
- Minecarts actually skip across water now (their skip threshold was above their vanilla top speed).
- Note blocks no longer replay several times per bump (per-block retrigger cooldown).
- Running into buttons now presses them: buttons have no collision shape, so the collision rays could never see one; a swept-volume pass handles them.
- Getting swallowed by sand/gravel is sticky again: vanilla's push-toward-open-space shove is suppressed while buried in engulfing blocks, so you must jump/dig your way out instead of being ejected (this also fixes flying into a sand cube pushing you back out).
- Fixed OpenAL "Invalid parameter" console spam while drowning: audio-filter AL calls were made from the game thread and raced the sound engine's own error checks; they now run on the sound thread.
- The g-force blackout no longer snaps to full black after a lag spike — it still activates immediately but fades in over a few frames.
- High-speed FOV stretch can no longer push the FOV past 180° and flip the camera upside down.
- Debug raycast view updates per frame again (interpolated with entity rendering), and rays are only red for collision-grade hits — touching without colliding is yellow.
- Block crack overlays appear much earlier (from 15% of break energy instead of 40%), so medium-speed impacts visibly crack walls.
- Button pressing is precise now: the sweep covers one tick of travel (no more activating a full block early), the entity must overlap the button's actual outline shape, and it must be moving into the button's face. Brushing along a wall no longer presses its buttons; floor buttons need something falling onto them.
- Ice (and other blocks) can no longer be cracked or broken by sliding across them: breaking requires real speed into the hit face, so a boat gliding on packed ice is harmless while head-on impacts are unchanged.
- A piston-launched boat with a rider no longer randomly dead-stops shortly after launch: the entity collision system was treating the boat's own passenger as a collision target (the passenger's velocity reads as zero) and the momentum exchange ate the boat's speed. An entity's own ride stack is now excluded from entity collisions.
- Fixed floor seams reading as walls: the bottom collision rays start 0.1 above the surface, and any tiny downward drift (boat gravity, micro-hops) dipped them into the next floor block's side face at every block boundary. That phantom "wall" hit at full horizontal speed caused repeated collision sounds while boating on ice, smashed the ice under a launched boat (breaking the boat with it), and dead-stopped ridden boats when the phantom damage synced the server's stale velocity to the client. Side faces on blocks whose top is at foot level are now classified as floor, and non-living grounded movers (boats, minecarts) get the same vertical drift cleanup living entities already had.

### Added

- **Potion of Acclimation** (Awkward + Phantom Membrane; extendable with Redstone): the high-altitude counterpart to Water Breathing — no air loss in thin air while active.
- **Lung Capacity** attribute (`fullstop:lung_capacity`, default 1.0): divides the thin-air drain rate; a base for how long breath lasts.
- Thin-air air loss is now ramped and capped: entering thin air pops bubbles slowly at first, faster the higher/longer you stay, up to a hard cap — even teleporting thousands of blocks up leaves several seconds before suffocation starts.
- `fullstop:slowing` block tag (honey + the soul sand family): moving beside these blocks applies a continuous, silent slow — soul sand now slows like honey does, without collision sound/particle spam.
- Client config `rotateCameraOnlyWhenFlying`: restrict bounce camera rotation to elytra flight.
- Per-interaction server config toggles: `kineticDoorOpening`, `kineticButtonPressing`, `kineticNoteBlocks`, `kineticBellRinging`, and `sandBlasting` (all default on).
- Overhauled README.

## 2.0.0 — Minecraft 1.20.1 (Forge)

A full rework of the mod on a new codebase.

### Data-Driven Block Behaviors

Block physics and behaviors are now defined by block tags under `data/fullstop/tags/blocks/`, so datapacks and other mods can customize them without code changes:

- `fullstop:fragile` — blocks that shatter easily and count as nearly zero hardness on impact (glass, ice, snow).
- `fullstop:soft_landing` — blocks that absorb most of an impact (hay).
- `fullstop:cushioning` — blocks that soften an impact (wool, leaves, moss, ...).
- `fullstop:phaseable` — blocks that fast-moving entities pass through instead of colliding with (leaves).
- `fullstop:engulfing` — phaseable blocks that swallow fast movers (sand, gravel, snow): heavy drag bleeds off their speed, and once slowed they become lodged inside and must dig their way out.
- `fullstop:gravity_affected` — blocks that fall like sand when unsupported.
- `fullstop:sticky` — gravity-affected blocks that cling to any touching block (slime, honey), so they only fall when floating completely free.
- `fullstop:kinetic_immune` — entity types that never take kinetic damage (bosses, flying/agile/soft-bodied mobs).

### Unsupported (Gravity-Affected) Blocks

- Added a data-driven unsupported block system: blocks tagged `fullstop:gravity_affected` become falling blocks when no longer connected to the world. Columns collapse one block at a time, just like sand. This system will be expanded in future updates.
- Slime and honey blocks now fall when unsupported. As `fullstop:sticky` blocks, they count a neighbor on any face as support, so they can still hang from walls and ceilings.

### New Block Interactions

- Entities colliding with engulfing blocks (sand, gravel, snow) at high speed become lodged inside and must dig their way out.
- Fast-moving entities phase through passable blocks such as leaves instead of taking impact damage.
- Kinetic block breaking: fragile blocks shatter on high-speed impact.

### G-Force & Blackouts

- Expanded G-force and blackout mechanics with new status effects tied to sustained G-force.
- Revamped G-force visuals and sounds, with smooth eased blackout effects.
- Blackouts can now occur while drowning (blackout extended to air supply).
- Blackout effects now render correctly in third person.
- Added new enchantments and status effects.

### Environmental Damage

- Added atmospheric damage.
- Added underwater pressure damage.

### Physics & Performance

- Improved physics calculations, including reworked collision detection and bounce edge cases.
- Major server-tick optimization of the physics loop:
  - Collision raycasts are skipped entirely when the volume the entity sweeps through this tick is
    provably empty (open air, flat ground) — the common case for almost every entity.
  - When rays do fire away from Valkyrien Skies ships, they use a vanilla-equivalent fast path that
    bypasses ship-aware raycast wrappers and reuses a single collision context (ship impacts still
    use the ship-aware path when a ship is nearby).
  - Capability lookups are cached per entity (field read instead of a Forge dispatcher walk).
  - Per-tick synched-data reads (NoAI checks) are cached and refreshed once a second.
  - Items, XP orbs, and area-effect clouds are excluded from physics before any work is done.
- Tweaked the sonic boom cooldown.

### Configuration

- Added new configuration options, including `enableGravityBlocks` to toggle the unsupported block system.

### Bug Fixes

- Fixed elytra wall impacts intermittently dealing no damage (climbing impacts were ignored
  outright, and a fully-mitigated graze could swallow the real hit that followed).
- Fixed water skipping being inverted: shallow skims now skip across the surface and steep dives
  plunge in (divers no longer trampoline off water).
- Fixed pointed dripstone dealing damage without contact (walking near a column), and grounded
  stops with no wall (e.g. ice into soul sand) being billed as wall impacts.
- Fixed impact sounds and sprains playing for impacts that dealt no damage.
- Fixed a creative/immune mover dealing zero damage to whatever it rammed.
- Fixed Feather Falling being lost on the exact landing that breaks the boots.
- Fixed passengers taking crush damage with no immunity checks or armor mitigation.
- Fixed spectators being shoved by entity collisions, and players rubber-banding when
  tunneling through another entity.
- Fixed block crack overlays leaking (lingering forever after death/unload, or being cleared in
  the wrong dimension).
- Fixed two floating sticky blocks holding each other up forever.
- Fixed the G-force audio muffle permanently breaking after a sound reload (F3+T, resource packs).
- Fixed the bounce-camera correction flailing at low framerates and swinging behind open menus.
- Fixed high-altitude pressure being inert below ~Y260, and pressure ignoring Water Breathing,
  Conduit Power, and Respiration.
- Fixed unbounded Slowness from hard landings (now capped at Slowness V, 30 seconds).
- Hardened the client velocity channel: non-finite values are rejected and reports are only
  accepted within a tolerance of the server's own measurement (both directions).
- Fixed persisted arrows re-gaining their owner's velocity on chunk reload.
- `projectileMultiplier = 0` now works as documented ("crazy damage": projectiles keep their full
  velocity in damage calculations) — it previously disabled projectile velocity scaling entirely.
- MixinExtras is now bundled inside the release jar (previously the mod could fail to load in
  packs where no other mod provided it). Use the `-all` jar from `build/libs` for releases.
- Fixed a config combination (min G-force threshold = max) permanently disabling the blackout.
- Fixed broken death messages, including entity collision death messages.
- Fixed entities dying on impact when getting into or out of bed (e.g. villagers at sunrise).
- Fixed item entity velocity desync.
- Fixed audio filtering for the G-force effect.
- Fixed vertical impact damage.
- Fixed velocity increasing when sliding along a wall.
- Fixed entity riding and dismount edge cases, and prevented mounting while flying.
- Numerous other bug fixes, improvements, and changes.
