# FullStop Codebase Audit

**Date:** 2026-07-21 · **Branch:** `main` (working tree, incl. uncommitted changes) · **Target:** Forge 1.20.1 (47.4.16)

> Replaces the 2026-07-05 audit. The systemic redesign that audit called for is **implemented and
> verified in this pass**: single velocity source of truth with client refinement bounded by the
> measured delta, stopping-force-as-trigger / raycast-as-classifier, server-owns-motion (the client
> never writes velocity), per-axis damage thresholds, data-driven tags and damage types, and clean
> side separation. This audit is a fresh full-spectrum review of the post-overhaul code, plus the
> **2026-07-21 server-tick performance overhaul** (see Part 1).

Findings are tagged:

- 🔴 **Critical** — crashes, or breaks the core gameplay loop *(none found this pass)*
- 🟠 **Bug** — incorrect behavior, desync, or exploitable
- 🟡 **Design/Smell** — works today but fragile, confusing, or wrongly located
- 🟢 **Minor** — polish, dead code, missing lang keys
- ✅ — fixed during the 2026-07-21 session

---

## Executive summary

The architecture is now sound; no crash paths were found and the historical failure classes
(wall-hold damage, trapdoor cast crash, client-side simulation desync, unit slips) are verifiably
gone. What remains is a layer of **feature-level bugs** — the most player-visible being an
**inverted water-skip angle test** (divers bounce, skimmers plunge), **dripstone dealing damage
with no contact**, and `onGround()` accepted as evidence for *horizontal* impacts — plus two
**network-validation gaps** (NaN poisoning, velocity under-reporting) and a handful of
client-polish issues (a dead sound-reload handler that permanently kills the muffle after F3+T,
frame-rate-dependent easing).

Server-thread performance was overhauled this session (Part 1): the per-tick cost in a ~430-entity
world should drop from ~1.4 ms/tick to an estimated **~0.3 ms/tick**, dominated previously by
Valkyrien-Skies-wrapped raycasts that are now skipped or routed around whenever no ship is nearby.

---

## Part 1 — Performance overhaul (2026-07-21) ✅

### The profile that motivated it (2-min spark capture, ~430 entities, TPS 19.2)

| Cost | Time / 120 s | Per tick |
|---|---|---|
| `PhysicsDispatchServer.onLevelTick` total | 3.44 s | ~1.43 ms |
| └ `CommonCollisionDetector.detectBlocks` (raycasts) | 2.01 s | ~0.84 ms |
| &nbsp;&nbsp;└ `Level.clip` — **1.38 s of 1.43 s inside the Valkyrien Skies wrapper** | 1.43 s | |
| &nbsp;&nbsp;└ `ClipContext` ctor (SynchedEntityData reads per ray) | 0.42 s | |
| └ `grabCapability` (Forge dispatcher walk per entity per tick) | 0.33 s | ~0.14 ms |
| └ `unphysable` → `Mob.isNoAi` (locked datawatcher read per mob per tick) | 0.30 s | ~0.13 ms |

### Changes made (all verified by an independent adversarial review + full build)

1. **Swept-volume pre-check** (`CommonCollisionDetector`, `FastRaycast.mayHitAnything`): every ray
   lies inside the *ray-start envelope* (`minY + 0.1` → the rays' actual floor) swept along the
   travel direction. If that volume is provably all air and no VS ship intersects it, all 15+ clips
   are skipped for ~a dozen block-state reads. This covers both airborne movers **and** grounded
   sprinters on flat terrain (the envelope floor deliberately excludes the block layer the entity
   stands on — using the full bounding box would defeat the skip for every grounded entity).
   Capped at 128 cells; larger sweeps conservatively fall through to raycasting.
2. **`FastRaycast.clip`**: vanilla-equivalent COLLIDER/SOURCE_ONLY traversal (verified line-by-line
   against 1.20.1 `BlockGetter.clip`) used when no ship intersects the swept volume. Avoids the VS
   `Level.clip` wrapper (~3–4× per-ray overhead in this pack) and reuses **one**
   `CollisionContext.of(entity)` per entity instead of 15 `ClipContext` constructions (each of
   which read SynchedEntityData). Near ships, rays still go through the wrapped `Level.clip`, so
   ship impacts keep working. Assumption documented in code: a ship's world AABB contains its
   block geometry on the query tick.
3. **Capability caching** (`EntityCapabilityCacheMixin` + `FullStopCapabilityCache`):
   `grabCapability` is now a volatile field read on the entity, invalidation-listener backed
   (the Provider now actually registers `event.addListener(provider::invalidate)`, which the old
   code never did). Benefits every call site — the tick loop, mixins, event handlers, client code.
4. **NoAI caching** (`FullStopCapability.isNoAiCached`, 20-tick refresh) + cheapest-first ordering
   in `DamageImmunityRules`. `CancelEvents.onLivingFall` uses the **same cached value** so the
   fall-damage canceller can never disagree with the tick loop during the cache window
   (double-damage / zero-damage split-brain — caught in review, fixed).
5. **Filter order in `onEntityTick`**: type/field exclusions (now including `ExperienceOrb` and
   `AreaEffectCloud`) → capability grab → cached NoAI → tick → g-force effects → sonic boom
   (`lengthSqr` vs 343², no sqrt) → 3 m/s speed gate → door look-ahead → raycast. Drop-like
   entities no longer allocate a capability at all.
6. **`openDoorsAhead` pre-check**: same swept-envelope skip before its 4 clips (it fires every tick
   for every sprinting entity). Side-ray offset clamped to `min(Xsize, Zsize) * 0.45` so
   non-square modded hitboxes keep the rays inside the checked volume.
7. **`StatusEffectApplier.applyForceEffects`**: g-force threshold (plain field read, almost always
   fails) checked before `isDamageImmune` (datawatcher-backed sleeping check).

### Estimated per-tick effect (~430-entity world)

| Item | Before | After (est.) |
|---|---|---|
| Raycasts + ClipContext | ~0.84 ms | ~0.05–0.15 ms (skip covers open air + flat ground; residual near-wall rays are un-wrapped and share one context) |
| grabCapability | ~0.14 ms | ~0.01 ms |
| unphysable / isNoAi | ~0.13 ms | ~0.03 ms |
| Capability tick + orchestration | ~0.26 ms | ~0.26 ms (unchanged) |
| **Total** | **~1.43 ms** | **~0.3–0.4 ms** |

Worst case (many mobs pressed against walls, e.g. dense caves/farms) stays well below baseline
because the per-ray cost itself dropped ~3–4×. **Re-profile with spark to confirm**; the biggest
single remaining line item is the capability tick itself.

### Remaining headroom (not done, in cost order)

- `living.isSleeping()` (pose datawatcher read) per living entity per tick in
  `FullStopCapability.tick` and `isDeadOrDying()` (health read) in `unphysable` — both candidates
  for the same once-a-second cache treatment as NoAI (sleeping needs care: bed wake-up detection
  must stay 1-tick accurate, so cache only the *false* state).
- `Lists.newArrayList(level.getAllEntities())` copy per level tick — required for mount/dismount
  safety; could become a reused ArrayList.
- `PhaseableBlockMixin.getCollisionShape` does instanceof + config + capability work before the
  cheap `state.is(PHASEABLE)` tag test — reorder (it runs per block shape query; see Part 3).

### Behavior notes (deliberate, small)

- NoAI toggles are noticed within 1 s (cache window) — consistently across damage and
  fall-cancel paths.
- XP orbs and area-effect clouds are now fully excluded like items. Orbs previously could (rarely)
  press buttons/ring bells/break fragile blocks when ejected at ≥3–8 m/s; that no longer happens.

---

## Part 2 — Verified fixed from the previous audit

Confirmed against the current code by independent reviewers (kept here so nobody re-fixes them):

- Trapdoor/fence-gate `ClassCastException`: gone — door-likes toggle via `state.use()` behind an
  explicit allowlist; both halves deduped via lower-half canonical pos in impact *and* look-ahead paths.
- Wall-hold damage: structurally impossible — trigger is per-tick measured speed loss, gated by a
  2-tick collision-flag grace window and a time-based (not per-block) damage cooldown.
- Downward-impact misclassification: `isMostlyDownward()` compares axes on the pre-impact velocity.
- Client simulation of remote entities: the client never calls `setDeltaMovement` anywhere;
  `CameraBounceHandler` is camera-only; packet has phase/side/local-player gates (one send per tick).
- `LivingHurtEvent` reflection hack: replaced with `event.setAmount` on `FALLING_STALACTITE`.
- Pressure defaults/exemptions: start level 200, undead/creative/spectator exempt, −20 clamp (but see P-bugs below).
- `Collision.NONE` immutability (`List.of()`), explicit `CollisionType.priority` field.
- `EntityWeight` reload-if-empty loop: gone (loaded flag + `ModConfigEvent` listeners).
- `BetterBrewingRecipe` NBT copy: correct (`input.getTag().copy()`).
- `SlimeBlockNoBounceMixin`: cancellable `@Inject`, not `@Overwrite`.
- Death messages: all translatable, all `death.fullstop.*` keys present; damage types are
  datapack-defined with correct `bypasses_armor`/`is_fall` tags.
- Reflective enchantment: cleanly split (impulse in `EntityCollisionHandler`, absorption in the
  LOWEST-priority hurt handler) — the old double-dip is documented and gone.
- `LogToChat` moved to `client.util` (now dead code — see Minors).
- All 8 `FullStopTags` entries have valid data JSONs; no silently-empty tags.
- Unit discipline: every m/s ↔ blocks/tick boundary checked clean (×20 / ×0.05 with comments).

---

## Part 3 — Findings

> **STATUS UPDATE (2026-07-21, same day):** Every 🟠 bug and nearly every 🟡/🟢 item below has
> been **fixed** in the working tree. The only deliberate deferrals, all low-risk:
> - Clarity/Vertigo **effect icons** (art assets — code is ready, textures needed at
>   `assets/fullstop/textures/mob_effect/clarity.png` and `vertigo.png`).
> - `CancelEvents.onLivingFall` stays an **unconditional** cancel for physable entities by
>   design: FullStop fully owns fall damage for them, and a conditional cancel risks double
>   damage (vanilla's fall event fires before FullStop's same-tick damage). The detection-miss
>   bugs that made this dangerous (dripstone, onGround wildcard, cooldown-without-hurt) are fixed
>   instead.
> - Presentation/golem extraction from `KineticDamageApplier` reduced to behavior fixes (golem
>   aggro now requires damage ≥ 4); the full move to a presentation layer is a pure refactor.
> - `PhysicsDispatchClient.class` literal in the FullStop constructor (safe lazy classloading
>   today) and the mixed manual/`@EventBusSubscriber` registration style.
> - `config.fullstop.server.enableGForceEffects` keeps its inconsistent key (it has a lang entry;
>   renaming would orphan translations).
>
> **Elytra intermittent no-damage report (user-observed) — root causes found and fixed:**
> 1. The detector unconditionally ignored wall faces while "mostly upward" — a climbing elytra
>    impact at any speed dealt zero damage. Now only ignored below the horizontal damage threshold.
> 2. The damage cooldown was marked even when mitigation zeroed everything — a terrain graze up to
>    5 ticks before a wall hit swallowed the real impact. Now only actual hurts start the cooldown.
> 3. (Related polish: impact sound/sprain now only fire when damage actually landed, so a silent
>    "thud with no damage" can't mask these cases anymore.)

### Physics & interaction layer

- 🟠 **Water-skip angle test is inverted** — `BounceHandler.java:115-116` (`handleWaterSkip`).
  Water hits always carry `Direction.UP` normals, so `acos(v̂·(-n))` is **0° for a vertical dive
  and ~90° for a shallow skim** — the exact opposite of the comment, and `if (angle > 25) return`
  therefore skips skimmers and trampolines divers (≥10 m/s vertical dive rebounds at 60%).
  This inverts the intended feature for every water landing. Fix: `if (angle < 65) return;`
  (skip only shallow grazes), and fix the comment.
- 🟠 **Dripstone deals flat damage with zero stopping force** — `KineticDamageCalculator.java:72-81`.
  The dripstone branch precedes any exceedance check and `onGround()` satisfies the evidence gate,
  so *walking* toward a dripstone column (4.3 m/s > the 3 m/s gate; one-tick-lookahead ray reaches
  it) deals 1♥ every 5 ticks without contact. Fix: require actual exceedance (or contact flags)
  before both dripstone returns.
- 🟠 **`onGround()` accepted as evidence for horizontal impacts** — `KineticDamageCalculator.java:62-66`.
  Any grounded entity that sheds >12.77 m/s horizontally without a wall (ice → soul sand/honey,
  boat dismount, cobweb) is billed wall damage. Fix: corroborate the axis actually used —
  horizontal exceedance requires `hadRecentHorizontalHit()`.
- 🟠 **Mover immunity nullifies damage to targets** — `KineticDamageApplier.java:178-217`.
  `splitEntityDamage` is zeroed when the *mover* is immune/non-living, but the same variable later
  feeds *target* damage: a creative player or minecart ramming mobs deals 0. Fix: separate
  self-damage from dealt-damage variables.
- 🟠 **Damage cooldown marked even when nothing was hurt** — `KineticDamageApplier.java:57`.
  `markDamageApplied` runs unconditionally (including empty-entity-list bails and fully-zeroed
  hurts); a real impact in the next 5 ticks is swallowed — and with vanilla fall damage cancelled,
  that means *no* damage. Fix: mark only when a `hurt()` actually landed.
- 🟠 **Passengers bypass immunity rules and mitigation** — `KineticDamageApplier.java:95-101, 199-204`.
  Passenger crush damage skips `isDamageImmune` (kinetic_immune-tagged mobs included) and
  `DamageMitigation`, with a `bypasses_armor` source. Fix: run passengers through the same gates.
- 🟠 **Boots can break before Feather Falling is read** — `DamageMitigation.java:25-31`.
  Durability hit at line 28, `getEnchantmentLevel(FALL_PROTECTION)` at line 31 — the landing that
  breaks your boots also deletes the FF reduction *and the FF-IV lethal cap*. Fix: read the level
  first, then damage the item.
- 🟠 **Entity-collision damage ungated by deceleration** — `KineticDamageCalculator.java:84-95`.
  ENTITY collisions use average relative speed and are exempt from the evidence gate; two fast
  movers passing close can exchange damage while both keep moving. Off by default
  (`entityCollisionDamage=false`) — gate on stopping force before ever enabling it.
- 🟠 **Spectators/creative fliers can be shoved by entity collisions** — `ServerCollisionDetector.java:37-42`
  predicate accepts any `LivingEntity`; a passing anvil/mob yanks a spectator camera
  (`EntityCollisionHandler` applies impulse + `hurtMarked`). Fix: exclude `isSpectator()` (and
  `noPhysics`) in the predicate.
- 🟠 **`setPos` tunnel-through repositions ServerPlayers silently** — `EntityCollisionHandler.java:150-153`.
  No teleport packet → "moved wrongly" rubber-band on the next move packet. Fix: skip the
  reposition for players or use `connection.teleport`.
- 🟠 **`LAST_CRACK` leaks and clears across dimensions** — `KineticBlockInteractions.java:88, 342-352`.
  Entries survive entity death/unload forever (id reuse can clear someone else's overlay), and the
  `-1` reset is sent through the entity's *current* level after a dimension change. Fix: evict on
  `EntityLeaveLevelEvent`; store the dimension with the pos.
- 🟡 `PhaseableBlockMixin.java:39-47` — per-shape-query hook does instanceof/config/capability work
  before the cheap `state.is(PHASEABLE)` test; reorder tag-first (hot path: every block shape query
  near a fast player, including pathfinding).
- 🟡 Phase-speed helper duplicated verbatim (`BlockPhasing.java:92-101` vs
  `PhaseableBlockMixin.fullstop$phaseSpeedSqr`) — consistent today only by convention; share one helper.
- 🟡 First-hit-only bounce normal (`BounceHandler.java:101-105`) — corner impacts bounce in
  list-order-arbitrary directions; pick the most velocity-opposed hit or average normals.
- 🟡 `ElytraDamageCanceler` — unconditional `@Redirect` with no config gate: with kinetic damage
  disabled, elytra crashes are damage-free with no vanilla fallback; `@Redirect` also hard-conflicts
  with other mods touching the same call. Prefer a gated `@WrapOperation`/inject.
- 🟡 `CancelEvents.onLivingFall` cancels ALL vanilla fall damage for physable entities
  unconditionally — every FullStop detection miss (cooldown window, teleport immunity) degrades to
  *no* damage instead of *vanilla* damage. Consider a capability flag "FullStop handled this fall".
  ✅ *(NoAI-coherence half of this fixed 2026-07-21: it now shares the tick loop's cached verdict.)*
- 🟡 Sticky mutual support (`GravityBlockHandler.java:116-127`) — two floating sticky blocks
  support each other forever; needs a connected-component check to ground.
- 🟡 Server-side player slosh reads `getDeltaMovement()` (non-authoritative for players) —
  `WaterSlowdownMixin.java:44-48` — the broadcast-to-others branch essentially never fires; use the
  capability velocity. No config gate for the feature either.
- 🟡 `EntityWeight.java:37-47` — lazy load calls `SERVER.entityWeights.get()` without
  `SERVER_SPEC.isLoaded()`; a mass query before config load (reachable via `WaterSlowdownMixin`)
  throws `IllegalStateException`.
- 🟡 In-loop `return` for falling blocks (`KineticBlockInteractions.java:176-179`) — corner
  landings only evaluate the first block; the file's historical return-vs-continue slip class.
- 🟢 Dripstone side-snap runs before the creative/spectator early-out (creative fliers knock
  spikes loose); only the last cracked pos per pass is remembered; `openDoorsAhead` skips trapdoors;
  minecarts water-skip (intended?); `canRideSafely` lets players mount falling sand;
  `fullstop_slosh_cd` junk int persisted to entity NBT; `FSSoundPlayer.sound()` returns null for
  unregistered ids straight into `playSound`.

### Damage & pressure

- 🟠 **Altitude pressure is inert until ~y260** — `PressureHandler.java:38-44`. Vanilla regenerates
  +4 air/tick out of water; `airLoss` only exceeds 4 above ~y260 with defaults, then kicks in
  abruptly. Compute loss net of regen (or suppress regen above the start level).
- 🟠 **Breathing protections ignored** — `PressureHandler.java:23-25, 52-59`. Direct
  `setAirSupply` bypasses Water Breathing, Respiration, turtle helmet, Conduit Power. Route through
  a vanilla-style decrement (`determineNextAirInWater`-equivalent) or check the modifiers.
- 🟠 **Unbounded Slowness amplifier** — `StatusEffectApplier.java` damage-effects path:
  `(int)((damage / 2) * scale)` → Slowness X+ on big survivable hits. Clamp amplifier (~4) and duration.
- 🟡 Impact sound + sprain effects fire on `damage >= 1` even when the applier then bails on its
  5-tick cooldown (`PhysicsDispatchServer` vs `KineticDamageApplier:40`) — you hear a big hit that
  dealt nothing. Let the applier report whether it actually applied.
- 🟡 CONFUSION re-added every tick for every living entity above 5.0 avg g-force — each add syncs
  entity data, and nausea is meaningless to mobs. Gate to players / skip while active with
  sufficient remaining duration.
- 🟡 Golem aggro/sound easter egg and death-message presentation (color/velocity strings) live
  inside `KineticDamageApplier` — move to a rules/presentation layer.
- 🟡 Netherite (any knockback-resistant armor) silently exempt from impact durability loss —
  `DamageMitigation.java:64-66`; document or make it a tag.
- 🟡 Crit ding on nearly every moving melee hit (`KineticDamageEventHandler.java:224-227`) —
  require a minimum bonus fraction.
- 🟢 `Horse`-only forgiveness (donkeys/mules/camels excluded); `(int) multiplier` used as absolute
  air loss; dead defensive re-checks; `!(attacker instanceof Projectile)` unreachable inside a
  `LivingEntity` branch; vanilla `death.fell.accident.water` globally overridden (stale — remove);
  `death.attack.fullstop.kinetic(.player)` keys unreachable via FullStop's own sources (fine as
  third-party fallback).

### Network & config

- 🟠 **NaN/Infinity accepted from clients** — `PlayerDeltaPacket.java:51`. `NaN > cap` is false, so
  a hostile packet poisons the player's server-side velocity state (g-force, damage, sound pitch).
  Fix: reject unless all components are `Double.isFinite()`.
- 🟠 **Velocity under-reporting = impact-damage immunity** — `FullStopCapability.java:285-289`.
  The clamp only prevents inflation; a client that always claims `Vec3.ZERO` never accumulates the
  velocity history the stopping force is computed from. Fix: accept the client value only within a
  tolerance of the measured delta in *both* directions.
- 🟠 **Vehicle velocity reports are dead** — `PlayerDeltaPacket.java:63-64` stores onto the
  vehicle's capability, but consumption is gated `entity instanceof Player` — sent every tick while
  riding, never read. Consume for player-controlled vehicles or stop sending.
- 🟠 **Projectile momentum re-applied on chunk reload** — `FullStop.java:88-99`.
  `EntityJoinLevelEvent` fires for loads from disk; a stuck arrow gains its online owner's current
  velocity on every chunk reload (with `projectilesHaveMomentum=true`). Fix: bail on
  `event.loadedFromDisk()`.
- 🟠 **`minGForceThreshold == maxGForceThreshold` NaN-kills the vignette permanently** —
  `FullStopConfig.java:250-260` + `GforceEffectsRenderer.java:84,100`. 0/0 → NaN enters the lerp
  and never leaves. Validate max > min at load or clamp the denominator.
- 🟡 `wildMode` comment inverted relative to behavior (`KineticDamageEventHandler.java:82`): false
  = vanilla arrows, true = FullStop rescaling *stacked* on vanilla's own bonus — and a
  "causes mayhem" option defaults to `true`. Also `wildMode=false` skips ALL mitigation layers for
  arrows, not just the bonus.
- 🟡 `projectileMultiplier`: default 1.0 makes the projectile's own speed contribute exactly zero,
  and the `== 0` special case *disables* processing while the comment promises "crazy damage".
  Redesign this knob.
- 🟡 Double thread-hop in packet handling (`consumerMainThread` + `enqueueWork`); client class
  literal in the common constructor (`FullStop.java:53-56` — safe today, refactor-fragile); mixed
  manual vs `@EventBusSubscriber` registration; `PullbackEnchantment.getMaxCost` calls
  `super.getMinCost` (typo-grade).
- 🟢 Missing lang: `config.fullstop.enableGravityBlocks`, `config.fullstop.enableValkyrienSkiesCompat`;
  11 config entries have no `.translation()` at all (dripstone, entityWeights, all of Pressure);
  clarity/vertigo effect icons missing (missing-texture squares); no optional `valkyrienskies`
  entry in mods.toml; no LONG_CLARITY → LONG_VERTIGO brewing corruption; inconsistent
  `config.fullstop.server.` key prefix.

### Client

- 🟠 **Sound-engine reload handler never fires** — `AudioFilterManager.java:93-99`.
  `SoundEngineLoadEvent` is a **mod-bus** event but the class subscribes on the Forge bus, so after
  F3+T / resource-pack toggle / output-device change the EFX filter id is stale, every source
  update raises AL errors, and the muffle is dead until restart. Fix: subscribe that handler on the
  mod bus (or re-init when `alIsFilter` fails).
- 🟠 **Unclamped camera-correction gain** — `PlayerTurnHook.java:33,58`. Effective per-frame lerp
  factor ≈ `15 × dt` (includes `Entity.turn`'s hidden 0.15): >1 below ~15 FPS (jitter), >2 below
  ~7.5 FPS or after a >133 ms frame stall (divergent flailing). Clamp the factor or use
  `1 − exp(−k·dt)`.
- 🟡 Low-pass filter attaches to **every** channel (music, UI) and reattaches per tick even when
  fully transparent, including on the title screen — skip while cutoff ≈ 1 and nothing changed;
  consider exempting music/UI. (Note: reattachment itself is load-bearing for EFX parameter
  changes; the "already on sound thread" comment is wrong but rescued by `executeOnChannels`.)
- 🟡 Frame-rate-dependent easing: blackout/FOV smoothing advances per *frame* (converges ~4× faster
  at 240 FPS than 60); the audio muffle smooths per *tick*. Move visual smoothing to tick or scale by dt.
- 🟡 Debug raycast renderer recomputes 15 clips per entity per **frame** while toggled on (fine
  when off); also clips with `Fluid.NONE` and no opposing-face filtering, so the debug view shows
  hits gameplay rejects and misses water-skip hits. Recompute per tick; mirror the classifier.
- 🟡 Camera correction continues while the mouse is ungrabbed (inventory/pause) where mouse motion
  can't cancel it — bail when not grabbed.
- 🟢 `LogToChat` dead; unreachable `filterObject != -1` branch; hardcoded English F3 strings;
  `@Mod.EventBusSubscriber` without `modid` (×2); vestigial `LINE_WIDTH`; SERVER config reads
  without `isLoaded()` guard in two spots (defensive-consistency only — config sync precedes spawn).

---

## Part 4 — Suggested fix order

**Phase 1 — player-facing physics bugs (small, independent)**
1. Water-skip angle inversion (one comparison — restores belly-flops AND skipping).
2. Dripstone no-contact damage + `onGround()` horizontal-evidence wildcard (same gate block).
3. Cooldown-marked-without-hurt + mover-immunity-zeroes-target (same applier pass).
4. Boots-before-Feather-Falling order swap.
5. Slowness amplifier clamp; passenger immunity/mitigation.

**Phase 2 — validation & config hardening**
6. `PlayerDeltaPacket`: `isFinite` check + two-sided tolerance clamp; delete or consume the vehicle branch.
7. `loadedFromDisk` bail in projectile momentum.
8. G-force threshold validation (max > min).
9. `EntityWeight` spec-loaded guard.

**Phase 3 — pressure & polish**
10. Altitude air-loss net of vanilla regen; respect breathing protections.
11. Sound-engine reload → mod bus; camera-gain clamp; tick-based easing.
12. `LAST_CRACK` eviction + dimension key; spectator exclusion in the entity-collision predicate;
    player `setPos` teleport packet.
13. Lang keys, effect icons, mods.toml optional VS dependency, dead code removal.

**Phase 4 — structural (as touched)**
14. Share the phase-speed helper; tag-first ordering in `PhaseableBlockMixin`; extract golem/
    presentation logic from the applier; `CancelEvents` conditional on "FullStop handled it";
    sleeping/health once-a-second caches (perf headroom).

---

## Part 5 — Regression scenarios

The six classics (walking into wall, sprint-jump into wall, elytra wall impact, elytra
shallow-descent impact, plain falls, slime bounces) **plus**, new this pass:

1. **Water**: 5° skim at ≥10 m/s must skip; vertical dive must plunge (belly-flop damage), not trampoline.
2. **Dripstone**: walking beside/toward a column at ground level must deal nothing; falling onto a
   stalagmite must hurt.
3. **Ice runway into soul sand** (no wall): no damage.
4. **Perf**: spark profile in the ~430-entity world — `onLevelTick` should sit well under
   0.5 ms/tick; `detectBlocks` should be near-invisible away from walls; verify ship impacts still
   register when standing on / flying into a moving VS ship (fast path must defer to `Level.clip`
   near ships).
5. **NoAI**: `/data merge entity <mob> {NoAI:1b}` mid-fall — within 1 s the mob must become inert to
   BOTH kinetic and vanilla fall damage (never double-damaged, never zero-damaged while flagged off).
6. **F3+T / resource-pack toggle**: g-force audio muffle must survive a sound-engine reload
   (currently fails — Part 3 client bug).
