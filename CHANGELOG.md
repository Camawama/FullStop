# FullStop! Changelog

## Unreleased

### Fixed

- Jumping the instant you land no longer deals phantom impact damage. The stopping force compares each axis against the last two ticks, and a perfectly timed jump sign-flipped against the landing's downward remnant — matching the "bounce" rule and billing fall speed PLUS jump speed as one huge vertical stop. An upward velocity born on the ground is now recognized as self-powered (the ground can only stop a fall to zero, never fling you upward): the landing is measured against zero instead. Slime and bed bounces are unaffected (they apply pre-contact, mid-air), and jump-into-ceiling impacts still hurt (those are measured on the stop tick).
- Entering and leaving a moving boat now reliably transfers momentum. The one-shot handoff kept losing to late vanilla sync on the client — dismounting makes the server send an absolute position packet whose handler overwrites the player's motion, and boarding has equivalent transient overwrites. The momentum is now captured client-side from the vehicle's locally observed movement and re-applied for a few consecutive ticks on the owning side, outliving any one-shot overwrite.
- When a driven boat ricochets off slime, the rider's head now turns WITH the boat (same yaw delta), so you keep facing the same way relative to the hull instead of staring backwards over the stern.
- Player-driven vehicle physics moved to the driver's client — the architectural fix for the whole family of ridden-boat bugs. A driven vehicle is client-authoritative: the server sees its positions only after they are clipped, detects wall contact a tick late, and everything it wrote back (bounces, skips, momentum) raced the client's own simulation — hence weak bounces, bounce-then-dead-stop, and lost momentum no matter how the sync was timed. Bounces and water skips for the vehicle you drive now run on your client (`ClientVehiclePhysics`), with fresh positions and pre-contact detection, exactly like on-foot physics; the server handles unmanned and mob-driven vehicles as before, and keeps ownership of damage, sounds, and particles.
- Boats (and horses) now turn into the rebound when bouncing off slime — the vehicle ricochets and points where it's going, the riding player's head stays free.
- A passenger's camera no longer rotates on bounces: the vehicle takes the bounce; the rider's own rays grazing the surface must not yank their view around.
- Entering a moving boat now truly keeps its momentum: the velocity is read from the boat's locally observed movement and applied on the same side at the exact takeover moment — no packets, nothing to race.
- Dismounting a moving vehicle (or being thrown off a destroyed one) now hands the vehicle's momentum to the passenger instead of freezing them in place.
- All bounce paths (server bounce, water skip, bounce camera) use the pre-impact velocity window, the same convention the damage pipeline already had.
- Riding a boat across ice no longer breaks the ice under it. A seated passenger's hitbox floor sits 0.45 blocks below the boat's hull (vanilla riding offsets) — inside the floor layer — so the rider's collision rays travelled THROUGH the ice and read every block seam as a head-on wall at boat speed, feeding the block-breaking path. A passenger's collision floor is now the higher of their own box floor and their vehicle's: the hull is between their legs and the world. (Debug rays and the floor-vs-wall rule follow the same effective floor.)
- Entering a moving boat keeps its momentum. Boarding hands control to the driver's client, which starts from its stale remote motion (~0), so a boat gliding past dead-stopped the moment you hopped in. The boat's measured velocity is now re-applied to the new driver right after the mount syncs.
- Ground-level wall slams deal damage again. Vanilla multiplies a grounded entity's deltaMovement by ground friction (~0.55) after moving, so the velocity the client reported each tick was roughly HALF its real speed — and the server's plausibility window was just wide enough to accept that deflated value as a "refinement" every tick, halving the measured stopping force of every grounded impact. Airborne there is no ground friction, which is why the same slam hurt the moment your feet left the ground. The client report now only ever refines the measurement UPWARD (not-yet-integrated intent, e.g. a jump's first tick); a smaller value falls back to the server's own measurement. A second, physics-based evidence path also corroborates wall hits independently of the client's collision flags: an entity parked flush against a face it approached, with the speed on that axis gone, has provably hit it.
- The default horizontal damage threshold moved from 12.77 to 10.5 m/s. The old default sat exactly in the sprint-jump band (the jump adds a flat +4 m/s): Speed-boosted ground runs stayed under it while the same run plus a hop crossed it. 10.5 keeps every unboosted movement safe with margin (sprint-jump peaks at 9.6) while Speed V+ runs and real launches hurt. NOTE: existing config files keep their saved 12.77 — delete the `velocityDamageThresholdHorizontal` line (or set it to 10.5) to pick up the new default.
- Rubbing along a slime/honey wall at speed no longer randomly reads as a head-on collision (red debug rays, mirror bounces that spin you around, honey pulse-braking). Vanilla parks a sliding entity flush against the surface, so the collision rays ran exactly in the wall plane and clipped the seam face of the next block along the wall — a face whose normal opposes travel at your full speed. Hits now require the entity's swept volume to genuinely overlap the face's cross-section; rubbing overlaps by exactly zero, so it can never register at any speed. Applies to walls, floors, and ceilings alike, and the debug ray colors follow the same rule.
- Slime bounces now work while riding a boat (or any player-driven vehicle). Player-controlled vehicles are client-authoritative — the server-side boat zeroes its own motion every tick, so the bounce velocity written on the server never reached the boat. Bounces and water skips are now delivered to the driver's client directly (network protocol bumped to 3).
- Corner bounces no longer rotate the camera in the wrong direction: the camera aimed off whichever hit face the rays found first; it now picks the face most opposed to your motion, the same one the server bounces you off.
- Bounce camera pitch (looking up/down) only follows the rebound during elytra flight. Jumping into a slime wall on foot no longer yanks the view upward; yaw still turns to follow the bounce.
- Riding passengers are no longer independently bounced by their vehicle's impact (the written motion was ignored by vanilla anyway and burned the rider's bounce cooldown).
- A living entity without the Forge gravity attribute no longer crashes the physics tick (degrades to vanilla gravity).
- `maxDamagePercent` config description now matches what the code does (it caps the total buffed damage as a multiple of the original, not the bonus).

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

### Changed

- Ice toughness is tiered, with real cracks: the ice family (`fullstop:crackable` tag — ice, packed ice, blue ice) is out of the insta-break fragile tag and resists impacts at its real hardness. Every solid sub-break impact adds PERSISTENT damage to the block, shown as the vanilla mining-crack overlay (visible to everyone, per block, not per entity), and cracked blocks need less energy to finish — so pristine ice is tough while cracked ice shatters from the next good hit. Cracks heal and fade after ~30 s without further hits. Glass and snow stay insta-fragile.

### Added

- **Falling sticky blocks cling mid-fall**: a falling slime/honey block that passes beside a solid block grabs onto it instead of falling past — the same any-face support rule placed sticky blocks already follow.
- **Better Breathing**: Respiration is renamed Better Breathing (it already slows thin-air suffocation at altitude the same way it slows drowning — the name now says so).
- **Phase-through effects**: crashing through `fullstop:phaseable` blocks (leaves) now rustles — block particles and the block's quiet hit sound while you pass through.

- **`fallDamageMode` server config** — `KINETIC` (default, FullStop's stopping-force model) or `VANILLA_PARITY`: impacts convert to the vanilla fall distance that produces the same impact speed and deal exactly vanilla fall damage (a 20-block fall onto grass hurts like vanilla; Jump Boost included, dripstone matches vanilla's ×2). Wall/ceiling crashes use the same conversion. Feather Falling, armor, belly flops, and soft-block tags still apply in both modes.
- **`kineticDamageMultiplier` server config** — global scale for all kinetic impact damage (default 1.0).
- **`hardnessAffectsDamage` server config** — turn off the block-hardness damage scaling without leaving KINETIC mode (default true).
- **`minimumSolidImpactDamage` server config** — the previously hardcoded 1-heart floor on over-threshold solid impacts is now configurable (0 disables it).
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
