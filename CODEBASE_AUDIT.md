# FullStop Codebase Audit

**Date:** 2026-07-05 · **Branch:** `experimental` (working tree, incl. uncommitted changes) · **Target:** Forge 1.20.1 (47.4.16), mod version 2.0.1dev

> **STATUS UPDATE (2026-07-05):** The overhaul described in this audit has been implemented
> (all of Phases 1–3 and most of Phase 4; `gradlew build` passes). Remaining deliberate
> deferrals: the full decomposition of `FullStopCapability` into separate tracker classes
> and the `ImpactContext` parameter object (both cosmetic; the capability was instead
> trimmed of dead state and its g-force logic isolated). File references below describe the
> PRE-overhaul code and no longer match line-for-line — treat this document as the
> historical rationale for the current structure. In-game verification of the six
> regression scenarios at the bottom of this file is still pending.

This document covers every package in the mod. Findings are tagged:

- 🔴 **Critical** — crashes, or breaks the core gameplay loop
- 🟠 **Bug** — incorrect behavior, desync, or logic error
- 🟡 **Design/Smell** — works today but fragile, confusing, or wrongly located
- 🟢 **Minor** — polish, dead code, missing lang keys

---

## Executive summary

The refactor split the god-classes along the right *conceptual* lines (detect → calculate → apply → effects), but three systemic problems were introduced and then papered over with local band-aids:

1. **There is no single source of truth for velocity.** The capability juggles four different velocity sources (position delta, client-reported delta, `getDeltaMovement`, `hasImpulse`), and collision *detection* (raycasts) is completely decoupled from collision *evidence* (Minecraft's own `horizontalCollision`/`verticalCollision` flags and the measured stopping force). The walk-into-wall damage bug, the "fix velocity increase on wall" commit, and the overlap-skip band-aid in `CommonCollisionDetector` are all symptoms of this one root cause.
2. **Client and server both run the full physics simulation independently** instead of the server simulating and syncing results. The client ticks capabilities for *all* rendered entities and even applies bounces (`setDeltaMovement`) to entities it doesn't control, which guarantees desync.
3. **Side separation is inconsistent.** Server-only logic lives in `common.*`, one `common` class imports `net.minecraft.client.*` (a latent dedicated-server crash), and several handlers run on both logical sides with ad-hoc guards.

There is also one confirmed vanilla-gameplay crash (trapdoor/fence-gate `ClassCastException`) that is very likely one of your "crashing issues."

The good news: the individual pieces (damage math, block interactions, G-force effects, audio filter) are mostly fine in isolation. Fixing FullStop is primarily about **fixing the data flow between them**, not rewriting features.

---

## How the pipeline currently fits together

```
Client (every player tick, ×2 due to missing phase check):
  PhysicsDispatchClient.onPlayerTick → PlayerDeltaPacket → server capability.clientVelocityMps

Server (every level tick, every entity):
  PhysicsDispatchServer.onLevelTick
    → FullStopCapability.tick()          (position-delta velocity, stopping force, g-force average)
    → ServerCollisionDetector.detect()   (raycasts from AABB corners along previous velocity)
    → EntityCollisionHandler.handle()    (elastic impulses, auto-mounting)
    → StatusEffectApplier.applyForceEffects()
    → KineticDamageCalculator.calculateDamage()
    → KineticBlockInteractions.handleBlockImpacts()
    → BounceHandler.apply()
    → KineticDamageApplier.apply() + applyDamageEffects()

Client (every level tick, ALL rendered entities):
  PhysicsDispatchClient.onEntityTick → capability.tick() + ClientCollisionDetector + BounceHandler  ← duplicate simulation
```

---

## Part 1 — Critical findings (fix these first)

### C1. 🔴 Crash: `ClassCastException` on trapdoors and fence gates
`KineticBlockInteractions.java:103-105` treats trapdoors and fence gates as "door-like", then unconditionally casts to `DoorBlock`:

```java
boolean isDoorLike = state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock;
if (isDoorLike && state.hasProperty(BlockStateProperties.OPEN)) {
    BlockSetType blockSetType = ((DoorBlock) state.getBlock()).type();   // ← CCE for TrapDoorBlock / FenceGateBlock
```

Any entity moving ≥ 3 m/s (`MIN_INTERACTION_VELOCITY = 0.15` blocks/tick — i.e., *walking speed*) into a trapdoor or fence gate crashes the server. **Fix:** branch per block class (`TrapDoorBlock.type()` and `FenceGateBlock.type()` exist as separate methods), or pattern-match: `if (state.getBlock() instanceof DoorBlock door) { door.type() } else if (... instanceof TrapDoorBlock td) { td.type() } ...`.

### C2. 🔴 The wall-damage saga: detection and damage disagree about what a collision is
This is the root cause behind "damage while holding W into a wall" *and* the later band-aids that likely broke legitimate impact damage:

- **Damage trigger** is `fullstop.getStoppingForce()` — a velocity *delta* measured from positions (`FullStopCapability.tickSpeed()`).
- **Damage gate** is a *raycast* (`CommonCollisionDetector.detectBlocks`) firing 15+ rays along last tick's velocity.
- These can disagree in both directions:
  - Rays hit the wall you're leaning against every tick → historically produced damage when the stopping force was polluted by the client-reported velocity path (see C3).
  - The band-aid added at `CommonCollisionDetector.java:84-87` now **skips any block whose collision shape overlaps the entity's inflated bounding box**:
    ```java
    VoxelShape shape = hitState.getCollisionShape(level, hitPos);
    if (!shape.isEmpty() && shape.bounds().move(...).intersects(entity.getBoundingBox().inflate(0.01))) {
        continue;
    }
    ```
    But at the tick a real impact happens, Minecraft has already moved the entity **flush against the wall/floor** — so the impacted block *always* overlaps the inflated box and gets skipped. This band-aid suppresses the spurious wall damage *and* a large fraction of legitimate impact damage. It is the wrong layer for this fix.
- Compounding it, `KineticDamageCalculator.java:41-45` classifies an impact as "downward" if `previousVelocity.y < -0.1` — i.e., *any* slight descent, even during near-horizontal elytra flight — and then replaces the whole stopping force with `|velocity.y|`. Flying full speed into a wall while descending 0.2 m/s computes damage from the 0.2, not the 30. Use `isMostlyDownward()` (which compares axes) instead of a fixed `-0.1` threshold.

**Recommended redesign (the single highest-value change in this audit):**
1. Make the **stopping force the trigger** and the **raycast only the classifier**. An impact "happened" iff `decelerationForce > threshold` *and* (`entity.horizontalCollision || entity.verticalCollision || collision-type is entity/water`). Minecraft already tells you a collision occurred; trust it.
2. Delete the overlap-skip band-aid at `CommonCollisionDetector.java:84-87`.
3. Because the trigger is now a *delta*, holding W against a wall is inherently damage-free: after the first contact tick, velocity is ~0 every tick, so there is no further deceleration to punish. No cooldown hacks needed.
4. Decompose the stopping force per axis and compare the horizontal component against `velocityDamageThresholdHorizontal` and the vertical component against `...Vertical`, instead of the current either/or classification.

### C3. 🟠 Player velocity has four competing sources of truth
`FullStopCapability.tickVelocity()` (lines 202-214) picks between the client-reported velocity and the measured position delta with a heuristic (`horizontalCollision || verticalCollision || clientSpeedSqr > actualSpeedSqr + 0.1`). Meanwhile `EntityCollisionHandler` uses `entity.hasImpulse ? getDeltaMovement() : capability velocity`, and `VelocityMath.entityVelocity` returns the capability value for anything that has one. Problems:

- `PhysicsDispatchClient.onPlayerTick` has **no `event.phase` check**, so the packet is built and sent **twice per tick** (START and END phases). Add `if (event.phase != TickEvent.Phase.END) return;`.
- Same method: `grabCapability(event.player)` result is dereferenced without a null check (`PhysicsDispatchClient.java:30-32`) — NPE risk if the capability is ever absent (entity revival/clone edge cases).
- When riding, the client sends the **vehicle's** delta (`PhysicsDispatchClient.java:34-40`); if the packet arrives just after a dismount race, `PlayerDeltaPacket.handle` applies the *vehicle's* velocity to the *player*. Send an explicit flag ("this is vehicle velocity") or have the server derive vehicle velocity itself.
- Security: the server accepts client-claimed velocities up to 100 blocks/tick (2000 m/s) and feeds them into damage dealt **to other entities** (`KineticDamageApplier`). A cheat client can weaponize this. Clamp against the server's own measured position delta (e.g., accept client value only within 2× the measured speed), not an absolute bound.

**Recommendation:** the capability should store exactly one velocity per tick, computed server-side from positions; the client packet should only ever *refine* it (never exceed it) and only for the sending player.

### C4. 🟠 Client runs the full physics pipeline on entities it doesn't own
`PhysicsDispatchClient.onEntityTick` (lines 67-93) ticks the capability and calls `BounceHandler.apply(...)` for **every rendered entity**. `BounceHandler` then calls `entity.setDeltaMovement(...)` (line 102) before its client/server branch — so the client applies bounce velocity to server-owned mobs, boats, and other players. The server does the same thing to the same entity a packet-latency apart. Result: rubber-banding and the general "instability" you're seeing.

**Fix:** on the client, run physics only for `Minecraft.getInstance().player` (the one entity the client owns), and restrict `BounceHandler`'s client path to the camera-rotation targets (`setTargetAngle/Pitch`) — never `setDeltaMovement` for non-local entities. Long term: sync the few values the client needs (g-force average, bounce events) via a small clientbound packet instead of re-simulating.

### C5. 🟠 Entity-collision impulses never sync, elastic restitution, auto-mount surprises
`EntityCollisionHandler` (experimental feature, but worth fixing before enabling):
- Pushed entities get `other.setDeltaMovement(...)` + `hasImpulse = true` but **never `hurtMarked = true`** (line 228-232). For living entities the server only broadcasts velocity on `hurtMarked`, so clients never see the push — server/client positions drift until a teleport correction snaps them.
- `restitution = 1.0` (line 197-199) is a perfectly elastic collision — walking into a cow flings both parties apart with no energy loss. Use ~0.2-0.4.
- The impulse denominator `(n / m1 + 1 / m2)` (line 200) divides *your* inverse mass by candidate count — a hack that under-applies impulse asymmetrically. Resolve candidates sequentially against the updated velocity instead.
- Falling onto any rideable entity **auto-mounts it** (lines 147-158) and `startRiding(entity, true)` force-bypasses vanilla checks. Fun feature, but it fires for mobs too (mobs stack on each other) and was the source of the "broke entity riding" commit. Gate it to players, or behind its own config.
- It ticks *other* entities' capabilities mid-iteration (lines 69-77), creating order-dependent behavior within a single level tick.

### C6. 🟠 High-altitude pressure suffocates everything above Y=128 — including normal mountains
`PhysicsDispatchServer.handlePressure` (lines 184-221) applies air drain to **every living entity** above `highAltitudeStartLevel` (default 128). In 1.20.1, ordinary terrain regularly exceeds Y=128 — goats, villagers in mountain villages, and idle players on hills will slowly suffocate. Also:
- No exemption for undead (skeletons/zombies don't breathe) or bosses; only `canBreatheUnderwater()` is checked.
- Spectators aren't exempted (only creative).
- `setAirSupply(nextAir)` can drive air far below −20 in one tick at extreme altitude before the reset-to-0 kicks in, making damage cadence altitude-dependent in a jumpy way.

**Fix:** raise the default well above terrain (e.g., 200+), exempt `entity.getMobType() == MobType.UNDEAD`, spectators, and non-players by default, and clamp `nextAir` to ≥ −20.

### C7. 🟠 Reflection into `LivingHurtEvent.source`
`PhysicsDispatchServer` (lines 51-60, 113-125) reflectively overwrites the **final** `source` field of a Forge event. This depends on JVM internals (final-field write-through), breaks under JPMS strictness, and silently no-ops if the field name changes. You don't need it: cancel the event and call `event.getEntity().hurt(FullStopDamageSources.fallingStalactite(...), event.getAmount() * 2)` yourself, or keep the original source and only use `event.setAmount(...)`. Note this dripstone handling also **duplicates** `EntityCollisionHandler`'s falling-dripstone branch (flat 10.0 damage + discard) — two systems doing the same feature with different numbers; keep one.

### C8. 🟡 Latent dedicated-server crash: `common.message.LogToChat`
`LogToChat` lives in a `common` package but imports `net.minecraft.client.Minecraft`. It's currently only referenced from commented-out client code, so nothing breaks *today* — but the first time anyone calls it from server code, a dedicated server dies with `NoClassDefFoundError`. Move it to `client.*`, or split into `LogToChat` (server, uses `entity.sendSystemMessage`) and `ClientLog`.

---

## Part 2 — Package-by-package review

### `net.camacraft.fullstop` (root)

**`FullStop.java`** — mostly fine.
- 🟢 `MinecraftForge.EVENT_BUS.register(FullStopConfig.class)` (line 56): `FullStopConfig` has no `@SubscribeEvent` methods; dead registration.
- 🟡 `PhysicsDispatchServer` is registered on both dists (fine — its handlers check sides), but `PhysicsDispatchClient` handles `EntityMountEvent` (`onDismount`) that also fires server-side; because the class is only registered on the client dist, **dismount cooldown is never set on dedicated servers** → the dismount damage-grace works in singleplayer but not multiplayer. Move dismount tracking to a common/server handler.

**`FullStopConfig.java`**
- 🟢 Translation keys use the legacy `config.velocitydamage.` prefix; half the newer options have no translation entries at all, and `DEFAULT_VELOCITY_THRESHOLD` (6.3) is an unused constant.
- 🟡 `wildMode` defaults to `true` while its comment reads like an opt-in chaos switch — confirm intent.
- 🟡 Client code reads `SERVER.enableGForceEffects` in render/tick handlers. That's legal (server configs sync to clients), but `GforceEffectsRenderer.onComputeFov` reads it **before** checking `minecraft.player == null` (line 34 vs 37). Add a `SERVER_SPEC.isLoaded()`/player-null guard first so a menu-time event can't hit an unloaded spec.

### `common.capability.FullStopCapability`

The heart of the mod, and the file that most needs decomposition.
- 🟡 It currently owns: velocity tracking, stopping force, g-force smoothing (with potion modifiers), rotation correction, five unrelated cooldowns, teleport/dismount immunity, collision dedup memory, plus static equipment helpers (`hasElytraEquipped`, `hasDepthStrider`...). Split into `VelocityTracker`, `GForceTracker`, `CooldownSet`, and move the static helpers to a `rules`/util class.
- 🟠 The same capability instance is ticked by **both** server (`PhysicsDispatchServer`) and client (`PhysicsDispatchClient`) code paths guarded only by `tickCount != lastTick`. In singleplayer these are different instances (different worlds), but the design invites exactly the double-tick bugs the guard is patching. Make ticking owner-explicit.
- 🟢 `prevPrevVelocityMps` is written and reset but never read — dead.
- 🟢 `teleportCooldown`/`dismountCooldown` are `double`s decremented by 1 — should be `int`.
- 🟠 `tick()` g-force section: gravity compensation subtracts a constant `-g*20` vector but `acceleration` is a per-tick velocity *difference* (m/s per tick), so units only line up because both happen to be "per tick × 20". Document the unit convention (m/s everywhere, tick = 1/20 s) — half the bugs in this codebase are unit mix-ups between native (blocks/tick) and scaled (m/s) velocities. Consider wrapping them in distinct types or at least a naming convention (`*Native` vs `*Mps`) applied *everywhere* (`Collision`, `RaycastUtil.getRayLength`, `KineticBlockInteractions` all take bare `Vec3`s today).
- 🟡 `isCollisionOnCooldown` dedups by exact `BlockPos`/entity id — sliding along a wall hits a *new* block every tick and bypasses the cooldown. Dedup by time + collision *type* instead.
- 🟢 NBT persistence only saves `joinedForFirstTime`, which is set but never used for anything. Either use it or delete it.

### `common.data.Collision`

- 🟡 `CollisionType` ordinal is used for priority (`// do not reorder`) — give the enum an explicit `priority` field so reordering can't silently break logic.
- 🟢 `Collision.NONE` is built with **mutable** `ArrayList`s; any accidental `collision.blockStates.add(...)` corrupts the shared singleton. Use `List.of()`.
- 🟡 The class conflates "what did we hit" (blocks/entities/hit results) with "water-surface metadata" (`highestYLevel`/`lowestYLevel`, which are barely used). Consider a record with factory methods (`Collision.blocks(...)`, `Collision.entities(...)`).

### `common.physics.collision` + `server/client` detectors

- See **C2** for the main issue (overlap-skip band-aid).
- 🟡 `RaycastUtil.getRayLength` for arrows is `Math.min(len, 0.00)` → always 0 → arrows never collide via raycast, yet the config ships `minecraft:arrow,1000000.0` as an entity weight. One of these is dead; decide whether arrows participate.
- 🟡 `RaycastLineRenderer` (debug) uses `getRayStarts(entity)` hardcoded to `CORNERS_AND_CENTERS`, so debug lines don't match the configured `raycastMode`. Pass the config mode through.
- 🟡 Performance: 15-23 `level.clip(...)` calls per entity per tick for **every entity moving > 0.02 m/s** (the `lengthSqr() < 0.0001` early-out is effectively never taken for mobs with AI). Raise the early-out to just below the smallest velocity anything cares about (`MIN_INTERACTION_VELOCITY` = 3 m/s is the current floor) — that alone removes ~90% of the raycasts on a busy server.
- 🟠 `ServerCollisionDetector`/`ClientCollisionDetector` are near-duplicates; the client one adds an "all overlapping → ignore" heuristic the server lacks. Merge into one detector parameterized by side, or better: don't detect entities on the client at all (see C4).

### `common.physics.interaction.BounceHandler`

- 🟠 Runs on both sides and applies `setDeltaMovement` on both (see C4).
- 🟡 Uses only `impactedHits.get(0)`'s face normal ("average" of one). With multiple hits (corner impacts) the bounce direction is arbitrary. Actually average the normals, or pick the face most opposed to velocity.
- 🟢 `if (entity instanceof Minecart) return;` sits *after* all the math (line 100) — hoist to the top.
- 🟡 The structure of the client/server split (lines 117-121: `if client → check config; else → return;`) is easy to misread and is why the mob-brain-wipe/camera logic ordering is fragile. Split into `applyServer(...)` and `applyClientCamera(...)`.
- 🟢 `handleWaterSkip` threshold `lengthSqr() < 100` = 10 m/s — fine, but write it as `10 * 10` with a named constant.

### `common.physics.interaction.KineticBlockInteractions`

- 🔴 The trapdoor/fence-gate cast crash (**C1**).
- 🟠 `state.use(level, fakePlayer, ...)` for every non-door block (line 121) means bumping into **any** interactable block activates it: levers get toggled, buttons pressed, chests "opened", flowerpots emptied, item frames rotated — at walking speed (3 m/s threshold). If "bump-to-interact" is the intended feature, invert the model: use an *allowlist* of interactions you want (doors, note blocks, buttons?) instead of `use()`-everything-except-a-5-entry-blacklist.
- 🟠 Inside the block loop, hitting slime/honey/bed as an `Arrow` does `return false` (line 85) — aborting processing of all *other* impacted blocks. Should be `continue` (and the arrow check hoisted out of the loop).
- 🟠 `handleFallingBlockInteraction` is followed by `return true` (line 129) — "true" means "broke a block", which suppresses `BounceHandler` in the dispatcher even when the falling block merely poofed itself. Return what actually happened.
- 🟡 The shared `FakePlayer` is positioned/rotated but never reset, and `fakePlayer.setItemInHand` mutates a server-global object. Harmless now, but any exception between setup and completion leaks state into the next user of that fake player.
- 🟡 `destroyBlockProgress(entity.getId(), pos, crackStage)` (line 170) leaves crack overlays on blocks forever if the entity never returns (no `-1` reset). Track cracked positions and clear them after a timeout, or accept vanilla's per-breaker overwrite semantics consciously.
- 🟢 Kinetic energy math mixes native velocity (blocks/tick) with "hardness × 2" — it works because it was tuned that way, but name the unit in the constant (`HARDNESS_BREAK_THRESHOLD_MULTIPLIER` says nothing about blocks/tick²).

### Damage chain (`KineticDamageCalculator`, `KineticDamageApplier`, `DamageMitigation`, `FullStopDamageSources`)

- 🟠 `isDownwardImpact` misclassification (**C2**, calculator lines 41-45).
- 🟠 Dispatcher plays the damage sound for `damage > 0` (`PhysicsDispatchServer.java:167-169`) but the applier ignores `damage < 1` (`KineticDamageApplier.java:37`) — you *hear* an impact that deals nothing. Use one threshold in one place.
- 🟠 `entity.hurt(source, 0)` is still called on several paths where damage was zeroed (`KineticDamageApplier.java:258-272, 319`) — a 0-damage `hurt()` still triggers hurt events, invulnerability frames, and mods listening to `LivingHurtEvent`. Skip the call when damage ≤ 0.
- 🟡 The applier mixes three unrelated responsibilities: damage application, death-message color lerping, and iron-golem aggro/sound easter eggs. Extract the presentation (color/velocity string) into `FullStopDamageSources` and the golem behavior into a rules class.
- 🟠 `FullStopDamageSources.makeSelfSource` checks `baseSource.getMsgId().equals("death.attack.stalagmite")` (line 72) — but `stalagmite()` is built on `generic()`'s type holder, so `getMsgId()` returns `"generic"` and the branch is **dead**; additionally the `death.attack.stalagmite.player` lang key it would use doesn't exist.
- 🟡 All death messages are **hardcoded English** (`" hit the ground too hard"` etc.) — use `Component.translatable` with lang entries; you already do this correctly for pressure/stalagmite/atmosphere.
- 🟡 `DamageMitigation` horizontal branch damages all four armor pieces *every* impact tick with no cooldown — armor durability melts when tumbling. Tie durability loss to actual damage applied.
- 🟡 Proper approach for custom death messages long-term: define real `DamageType`s in a datapack (`data/fullstop/damage_type/...`) instead of anonymous `DamageSource` subclasses overriding `getLocalizedDeathMessage` — that also makes them work with `death.attack.<id>.player` (kill credit) for free and keeps tags (bypass armor, etc.) data-driven.

### `common.physics.rules` (`DamageImmunityRules`, `EntityCollisionRules`, `EntityWeight`)

- 🟢 Good idea, right shape. Two notes:
- 🟡 `unphysable` excludes `Mob.isNoAi()` — NoAI mobs are commonly used in maps/farms and will silently never take kinetic damage; is that intended?
- 🟡 `EntityWeight` caches into a static map with a "reload if empty" fallback — if a user configures an *empty* list, it reloads every call. Track a `loaded` boolean instead. Also entity weight `minecraft:item,0.0` is dead config: `ItemEntity` is already `unphysable`.
- 🟡 Hardcoded class-based immunity lists (`DamageImmunityRules`) would serve players better as entity-type tags (`fullstop:kinetic_immune`) so modpacks can extend them without code.

### Event layer (`PhysicsDispatchServer`, `KineticDamageEventHandler`, `CancelEvents`)

- 🟠 `CancelEvents.onLivingFall` cancels **all** fall damage unconditionally. Combined with the detection band-aid (C2) currently suppressing many landings, the net current behavior is "nobody takes fall damage in a range of situations." Consider canceling only when FullStop actually computed a replacement (e.g., set a flag on the capability when kinetic fall damage was applied this tick), so a detection bug degrades to *vanilla* behavior instead of *no* behavior.
- 🟠 `KineticDamageEventHandler.calcNewDamage` (lines 171-179): weapon durability is modified via `item.setDamageValue(...)` directly — this bypasses Unbreaking, `ItemStack.hurtAndBreak`'s break handling, and fires no break event; a high-velocity hit can push damage past max and produce a ghost item. Use `hurtAndBreak`. Also `damageRatio` rounds `newDamage/originalDamage`, so a 1.4× hit costs 0 durability and 1.6× costs 1 — probably fine, but it also runs for projectile sources (damaging the shooter's held item on arrow impact).
- 🟡 The crit sound at line 181-183 plays for *every* velocity-boosted hit, including mob-on-mob across the map (volume 0.6 broadcast at the victim) — gate on player involvement or scale with the bonus.
- 🟡 `applyReflective` pushes the attacker even when `attacker` is a projectile — reflecting an *arrow's* velocity after it already hit does nothing useful; filter to `LivingEntity` attackers.
- 🟡 `PhysicsDispatchServer.onLevelTick` copies the entire entity list each tick (`Lists.newArrayList`). Necessary if handlers mount/dismount (they do), but pair it with the higher velocity early-out from the collision section to keep per-entity cost down.

### Pressure system (`handlePressure`)

- See **C6**. Additionally 🟡: this system is unrelated to kinetic physics and lives inside the physics dispatcher — move to its own `PressureHandler` class; it will make the eventual "disable pressure separately" support trivial.

### Networking (`PacketHandler`, `PlayerDeltaPacket`)

- See **C3** for the velocity-trust issues.
- 🟢 Structure is fine. When you add server→client sync (recommended in C4), keep the pattern.

### Effects / potions / enchantments / attributes

- 🟢 Generally clean. Specific notes:
- 🟡 `SprainEffect.applyEffectTick` calls `entity.setJumping(false)` every tick on both sides — this fights the AI's jump control but does *not* reliably block player jumps (client-authoritative); the actual jump-block happens in `CancelEvents.onLivingJump` by zeroing Y velocity — which fires *after* the jump event, so players still get a stub-jump animation and `LivingJumpEvent` side effects from other mods. Consider a jump-power attribute modifier instead (Forge exposes `ForgeMod.SWIM_SPEED`-style attributes; for jump, intercept `LivingEvent.LivingJumpEvent` is acceptable but document that the `setJumping(false)` line is best-effort).
- 🟡 `PullbackEnchantment.doPostAttack` fires on both logical sides (enchant hooks run wherever `doPostAttack` is called) — guard with `!attacker.level().isClientSide` to avoid a double push in singleplayer.
- 🟠 Reflective enchantment double-dips: it reduces incoming damage in `KineticDamageEventHandler.applyReflective` *and* reduces received impulse in `EntityCollisionHandler` (both directions, attacker and defender). Decide which layer owns it.
- 🟢 `BetterBrewingRecipe.getOutput` does `new ItemStack(input.getItem()); itemStack.setTag(new ItemStack(input.getItem()).getTag())` — the inner stack's tag is always null; if the intent was to preserve the input's custom NBT, copy `input.getTag()`.

### Client rendering & audio (`GforceEffectsRenderer`, `AudioFilterManager`, debug renderers)

- 🟡 `GforceEffectsRenderer.onRenderGuiOverlayPost` keys off `VanillaGuiOverlay.VIGNETTE` — verify the blackout still renders with **Graphics: Fast** (vanilla skips vignette rendering on fast graphics; whether Forge still fires Post for it decides if your blackout vanishes). If it doesn't fire, hook `RenderGuiEvent.Post` instead.
- 🟡 Both the FOV handler and the vignette handler exempt creative but not spectator.
- 🟡 The clarity/vertigo threshold-adjustment block is copy-pasted **three times** (`GforceEffectsRenderer` lines 75-91, `AudioFilterManager` lines 155-171, `FullStopCapability` lines 152-170 in a different form). Extract `GForceThresholds.effective(player)`.
- 🟢 `AudioFilterManager` is well-guarded. Note the low-pass hits *all* channels including music and UI clicks — probably desirable for blackout, but menu/music exemption is a common polish request.
- 🟢 `RaycastLineRenderer` is solid for a debug tool. `LINE_WIDTH` is vestigial (you render quads now).

### Client mixins

- 🟢 `PlayerTurnHook`: `delta = (time - lastMouseEventTime) * 1000 * 20` — the units here are suspicious (seconds → "ticks" via ×20, the ×1000 makes it ms-based… then ×0.005 inside `rotationCorrection`). It works by tuning, but write the intended unit down; this is exactly the kind of expression the next refactor breaks.
- 🟢 `KeyboardHandlerMixin.fullstop$consumeF3V` returns `true` for **every** F3+V press even when the debug toggle is separately handled in `DebugHotkeyHandler` via raw key events — two systems own one hotkey. Move the toggle *into* the mixin (or use Forge's `RegisterKeyMappingsEvent` for a real, remappable keybind — nicer for users).

### Common mixins

- 🟡 `WaterSlowdownMixin` runs the slowdown math *before* checking the slosh cooldown, fine — but it applies `setDeltaMovement` on both client and server for the local player (client-auth movement) and server-side for mobs. That's the correct pattern, but the sound block plays server-broadcast for non-players *and* client-local for players — nearby players hear other players' sloshes only via… nothing. Sounds for *other players* are currently silent to observers (server branch excludes the moving player but non-moving observers rely on the excluded-player broadcast — that part is actually OK; just verify in multiplayer).
- 🟡 `SlimeBlockNoBounceMixin` uses `@Overwrite` — maximum incompatibility with any other mod touching slime blocks. An `@Inject(cancellable = true)` at HEAD that zeroes Y and cancels achieves the same with better compat.
- 🟢 `ElytraDamageCanceler` + `CancelEvents.onLivingFall`: you cancel vanilla fly-into-wall and fall damage globally — both rely on FullStop's replacement working (see C2/CancelEvents note).

### Resources

- 🟢 `en_us.json` is missing: potion names (`item.minecraft.potion.effect.clarity_potion` family × splash/lingering), `death.attack.stalagmite.player`, all `config.fullstop.*` keys referenced in `FullStopConfig`, and entries for the hardcoded death messages once translated.
- 🟢 `accesstransformer.cfg` exposes `ClientLevel.tickingEntities` (`f_171630_`) and `Entity.m_20272_` — grep says nothing uses them anymore; prune (AT entries have load-time cost and compat surface).
- 🟢 `mods.toml` declares optional `valkyrienskies` dependency but no integration code exists.

---

## Part 3 — Reorganization recommendations

The current tree is *close* to right. The main fix is making `common/client/server` mean what they say:

```
fullstop/
├─ FullStop.java, FullStopConfig.java
├─ common/           ← ONLY side-safe code (loaded on dedicated servers)
│  ├─ capability/    (velocity/gforce state — split FullStopCapability)
│  ├─ registry/      (ModEffects, ModPotions, ModEnchantments, ModAttributes)  ← merge the 4 single-class packages
│  ├─ physics/       (math, rules, data)
│  └─ network/
├─ server/           ← everything that mutates the world
│  ├─ PhysicsDispatchServer (thin orchestrator only)
│  ├─ collision/  damage/  interaction/  pressure/
│  │   ↑ move BounceHandler, EntityCollisionHandler, KineticBlockInteractions here —
│  │     they are server-only in practice (world mutation, FakePlayer, destroyBlock)
│  └─ events/
└─ client/
   ├─ PhysicsDispatchClient (local player only)
   ├─ render/  sound/  mixin/  hotkey/
   └─ LogToChat (moved from common)
```

Principles to adopt:

1. **One orchestrator per side, and orchestrators contain no logic.** `PhysicsDispatchServer.onEntityTick` currently embeds the sonic boom feature, sound aesthetics, and ordering decisions. Reduce it to: tick capability → detect → hand one `ImpactContext` object to each subsystem. A single `ImpactContext { entity, capability, collision, damage }` record kills the 4-5-argument parameter lists that every handler repeats.
2. **Server simulates; client presents.** Delete the client-side collision/bounce simulation (C4) and sync what the presentation layer needs. This removes `ClientCollisionDetector` entirely.
3. **Unit discipline.** Two `Vec3` meanings exist (blocks/tick vs m/s). Suffix every variable/method (`velocityNative` / `velocityMps`) or introduce tiny wrapper records. Most historical bugs here (`getRayLength`, `KineticBlockInteractions` energy, gravity compensation) are unit slips.
4. **Data-driven rules.** Immunity lists, soft-landing blocks (hay/wool/leaves in `KineticDamageCalculator`), fragile blocks (glass/ice, duplicated in calculator *and* block interactions), and interactable-on-bump blocks should be block/entity **tags**, defined once. The fragile-block list literally appears twice with different members today.
5. **Kill duplicated snippets:** clarity/vertigo threshold math (×3), fragile-block list (×2), falling-dripstone handling (×2), Reflective enchantment application (×2), armor-slot enchantment summing (×3 in `EntityCollisionHandler`/`KineticDamageEventHandler`).

---

## Part 4 — Performance notes

| Location | Issue | Suggestion |
|---|---|---|
| `CommonCollisionDetector` | 15-23 `level.clip` rays per entity per tick, for every entity moving >0.02 m/s | Early-out below ~3 m/s; skip entities with no recent deceleration |
| `PhysicsDispatchServer.onLevelTick` | Full entity-list copy per level per tick | Keep (mutation safety) but pair with the early-out above |
| `ServerCollisionDetector` | `level.getEntities` AABB query per entity per tick when `entityCollisionDamage` on | Only query when speed > threshold |
| `FullStopCapability` | `getCapability(...).orElse(null)` twice per call site in hot paths (`VelocityMath.entityVelocity`) | Cache per-tick or pass the capability down |
| `RaycastLineRenderer` | fine (debug-gated) | — |

None of these are emergencies; correctness first.

---

## Part 5 — Suggested fix order

**Phase 1 — stop the bleeding (small, independent fixes)**
1. C1 trapdoor/fence-gate cast crash.
2. `PlayerTickEvent` phase check + capability null check (C3).
3. `hurtMarked` on pushed entities; restitution < 1 (C5).
4. Pressure defaults + undead/spectator exemptions (C6).
5. Replace the `LivingHurtEvent` reflection with cancel+re-hurt (C7); delete the duplicate dripstone path.

**Phase 2 — the velocity/collision redesign (the real fix)**
6. Make stopping force the damage trigger, raycast the classifier; delete the overlap-skip band-aid; fix `isDownwardImpact`; per-axis thresholds (C2).
7. Single velocity source of truth in the capability; clamp client-reported velocity against measured (C3).
8. Client simulates only the local player; no client `setDeltaMovement` on remote entities (C4).
9. Make `CancelEvents.onLivingFall` conditional on FullStop having handled the impact.

**Phase 3 — feature correctness**
10. Block-interaction allowlist instead of `use()`-everything; fix `return`/`continue` slips; `hurtAndBreak` for weapon durability.
11. Consolidate duplicated logic (thresholds, fragile blocks, Reflective).
12. Datapack `DamageType`s + translatable death messages + missing lang keys.

**Phase 4 — restructure**
13. Package moves per Part 3, split `FullStopCapability`, introduce `ImpactContext`.
14. Convert hardcoded lists to tags; real keybind for the debug toggle.

A practical tip for Phase 2: before changing anything, write down the *intended* invariant — e.g. "an entity takes horizontal kinetic damage iff its horizontal speed dropped by more than `velocityDamageThresholdHorizontal` m/s in one tick while `horizontalCollision` was true" — and test each change against walking-into-wall, sprint-jumping into wall, elytra wall impact, elytra shallow-descent wall impact, plain falls, and slime bounces. Those six scenarios cover every regression this codebase has had.