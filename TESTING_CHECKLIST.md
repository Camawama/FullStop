# FullStop In-Game Testing Checklist

**Build:** working tree, 2026-08-08h (vanilla push suppressed for the ex-rider/ex-boat pair — no more veer)

- [ ] **Shift-dismount a boat at speed (retest #4).** *Expect:* you fly out forward, the boat glides STRAIGHT on without you — no sideways veer, no speed dump into you. After ~2 s you and the boat push each other normally again (walk into it: it nudges).

## 0. NEW in this build `[FIX]` — test these first

**IMPORTANT: delete `velocityDamageThresholdHorizontal` from the saved server config (or set it to 10.5) — old configs keep 12.77 and mask the grounded-damage fix.**

- [ ] **Jump repeatedly, timing space exactly on each landing (bunny hop).** *Expect:* ZERO damage from 1-block hops, however precisely timed. Also drop 3-4 blocks and jump on landing: only normal fall damage for that height, no spike.
- [ ] **Jump into a low ceiling hard.** *Expect:* ceiling damage still works (regression check on the jump fix).
- [ ] **Land on slime and bounce.** *Expect:* bounces unchanged (they're pre-contact, exempt from the jump rule).
- [ ] **Enter a boat gliding past you (retest #3).** *Expect:* it keeps gliding — the velocity is now seeded client-side and re-applied for 5 ticks.
- [ ] **Shift-dismount a moving boat (retest).** *Expect:* you fly out with the boat's velocity — the seed now outlives the server's dismount position packet.
- [ ] **Boat slime-wall ricochet while riding.** *Expect:* the boat snaps to the rebound heading and your view eases around SMOOTHLY to match (same gradual turn as the on-foot bounce camera) — no head snap. Moving the mouse mid-turn cancels the auto-turn, same as on foot.
- [ ] **Shift-dismount a moving boat (retest #3).** *Expect:* BOTH keep moving — you fly out with the boat's velocity AND the boat glides on without you. No "collision" between you and the boat right after exit (the boat must not dead-stop and slingshot you — the pair is collision-exempt for 2 s).
- [ ] **Sprint into a standing player/mob with entity collisions on.** *Expect:* sane momentum exchange — and a MOVING player being grazed no longer absorbs the full impact as if they were a wall (their measured velocity counts now).

- [ ] **Drive a boat fast into a slime wall, 10× in a row.** *Expect:* a full-strength rebound EVERY time, applied instantly (the bounce now runs on YOUR client, pre-contact) — no twitch-then-dead-stop, no "sometimes works".
- [ ] **Watch the boat itself on that bounce.** *Expect:* the BOAT turns to face the rebound direction (ricochet), while your camera does NOT move.
- [ ] **On-foot slime bounces.** *Expect:* camera yaw still follows (regression check).
- [ ] **Enter a boat gliding past you (retest).** *Expect:* it keeps gliding with its speed and direction the moment you sit — the momentum is now read and applied locally on your client at takeover.
- [ ] **Dismount a moving boat (shift mid-glide).** *Expect:* you fly out with the boat's velocity instead of freezing in place.
- [ ] **Break a boat while it moves fast (another player/dispenser arrow), or ride it off a cliff and let the crash destroy it.** *Expect:* the ejected rider carries the boat's momentum.
- [ ] **Ridden-boat water skip.** *Expect:* skimming still works while driving (now client-side too).
- [ ] **Mob-driven boat (zombie in a boat) shoved onto slime.** *Expect:* still bounces (server path retained for non-player drivers).

- [ ] **Ride a boat fast across a long ice sheet.** *Expect:* the ice under/around the path is NEVER cracked or broken by the ride. F3+V shows no red rays from the rider along the floor.
- [ ] **Boat crashes head-on into a wall while ridden.** *Expect:* wall impact still registers (rider + boat damage past threshold) — the rider floor fix must not blind real wall hits at chest height.
- [ ] **Push a boat so it glides past you, then enter it mid-glide.** *Expect:* the boat keeps moving in its direction with roughly its speed after you sit down, instead of dead-stopping.
- [ ] **Sprint through a wooden door.** *Expect:* flings open exactly as before with NO damage (door slam damage reverted).

- [ ] **Speed V+, sprint on flat ground into a tall wall, feet never leaving the ground.** *Expect:* DAMAGE. This was the "only hurts if I jump first" bug — grounded runs reported half their real speed.
- [ ] **Same run with Speed 50.** *Expect:* heavy damage (or you smash through the wall, which also damages).
- [ ] **Plain sprint and sprint-jump into a wall, no Speed effect.** *Expect:* still NO damage (9.6 m/s peak < 10.5 threshold) — soft bump sound only.
- [ ] **Sprint-jump into an ice wall** (~8+ m/s). *Expect:* a visible mining-style crack overlay appears ON the block and STAYS (everyone can see it; it survives you walking away). No frosted ice, no block change.
- [ ] **Hit the same cracked ice again.** *Expect:* the crack deepens per hit and the block shatters once accumulated damage completes. Cracked ice breaks much easier than pristine.
- [ ] **Leave cracked ice alone ~30 s.** *Expect:* the crack overlay fades (ice "heals").
- [ ] **Crack packed and blue ice too.** *Expect:* same crack visuals, appropriately harder (blue ice needs serious speed to even crack).
- [ ] **Drop a slime/honey block down a shaft with a solid block partway down the wall.** *Expect:* the falling block grabs onto the wall block as it passes and stays there. Sand does not do this.
- [ ] **Elytra-dash through leaves above phase speed.** *Expect:* leaf particles spray and a quiet rustle plays while passing through.
- [ ] **Respiration item names.** *Expect:* the enchantment shows as "Better Breathing"; it slows high-altitude air loss (already did — verify with Resp III above the altitude start level).
**How to use:** work through each section in survival unless stated otherwise. Check the box if the
**Expect** line matches what you saw; if not, note what actually happened next to the item.

Tags: `[FIX]` verifies a bug fixed today · `[PERF]` performance change · `[REG]` regression guard
(old behavior that must NOT have broken) · `[VS]` needs Valkyrien Skies.

Useful setup commands:
```
/gamemode survival        /effect give @s minecraft:resistance 999 4   (survive repeated tests)
/summon minecraft:zombie ~ ~ ~ {NoAI:1b}
/data merge entity <click mob> {NoAI:1b}
/spark profiler start --timeout 120     (perf capture)
```

---

## 1. Core impact damage (the six classics) `[REG]`

- [ ] **Walk into a wall and hold W** for 10+ seconds. *Expect:* zero damage, no sounds, ever.
- [ ] **Sprint-jump into a wall** repeatedly. *Expect:* no damage at normal sprint speed (~5.6 m/s < 12.77 threshold); no thud sounds without damage.
- [ ] **Plain fall, ~5 blocks.** *Expect:* small kinetic damage (replaces vanilla fall damage), damage sound plays, sprain/slowness only on harder landings.
- [ ] **Plain fall, ~20+ blocks.** *Expect:* heavy damage or death; death message reads "hit the ground too hard" with velocity appended.
- [ ] **Fall onto hay / wool / leaves.** *Expect:* clearly reduced damage vs stone from the same height (soft landing / cushioning tags).
- [ ] **Slime block landing.** *Expect:* bounce, no damage (unless crouching), camera reaction if enabled.
- [ ] **Fall while holding a full stack of passengers is N/A — see §7 for stacks.*

## 2. Elytra impacts — your reported issue `[FIX]` (test thoroughly!)

- [ ] **Level flight into a flat wall at high speed** (rocket boost). *Expect:* damage EVERY time, scaled to speed. Repeat 10×; note any silent hit.
- [ ] **CLIMBING (swooping upward) into a cliff face / wall at speed.** *Expect:* damage — this specific case dealt zero before today's fix.
- [ ] **Shallow-descent wall impact** (slight downward glide into a wall). *Expect:* damage based on your full speed, not the tiny vertical component.
- [ ] **Graze terrain then hit a wall within ~1/4 second.** Fly low, clip a treetop/ridge, then slam a wall. *Expect:* the wall hit still damages — before today a mitigated graze started the cooldown and swallowed it.
- [ ] **Ceiling hit while flying up.** *Expect:* "hit their head on the ceiling" damage path works.
- [ ] **Glancing blow along a wall** (very shallow angle). *Expect:* little or no damage (you keep most speed — only the lost speed counts). This is intended.
- [ ] **Hear a thud → take damage.** *Expect:* impact sound and damage always come together now; a loud thud with zero hearts lost is a bug — report it.

## 3. Water `[FIX]`

- [ ] **Shallow skim at speed** (elytra or ice-boat launch, ~5° descent, >10 m/s). *Expect:* you SKIP across the surface like a stone (possibly multiple bounces). This was inverted before.
- [ ] **Steep/vertical dive into deep water.** *Expect:* you plunge IN (no trampoline off the surface).
- [ ] **Belly flop:** sprint-swim pose / holding sprint when hitting water flat from height. *Expect:* significant damage (~like pavement), "flopped into the water" on death.
- [ ] **Clean feet-first dive from height.** *Expect:* almost no damage.
- [ ] **Step off a 2-block ledge into a pond.** *Expect:* silent, no water impact triggered.
- [ ] **Minecart off a ramp onto water.** *Expect:* it skips across the surface (intended easter egg).
- [ ] **Swim around fast underwater.** *Expect:* slosh sounds rate-limited (not machine-gun), slowdown feels unchanged `[REG]`. On a server: another player swimming near you should be audible.

## 4. Dripstone `[FIX]`

- [ ] **Walk toward/beside a stalagmite column at ground level.** *Expect:* ZERO damage from proximity — walking near dripstone hurt you before.
- [ ] **Fall onto an upward stalagmite (3+ blocks).** *Expect:* amplified damage vs plain ground (multiplier applies).
- [ ] **Sprint/ram into a dripstone spike sideways.** *Expect:* small damage (2.0) on actual contact only.
- [ ] **Hit a hanging stalactite sideways at speed.** *Expect:* it snaps off and falls as the vanilla falling spike (hurts whatever it lands on).
- [ ] **In creative: fly into stalactites.** *Expect:* nothing snaps, nothing takes damage `[FIX]`.

## 5. Grounded stops with no wall `[FIX]`

- [ ] **Ice runway at speed into soul sand / honey block floor** (boat or sprint-jumping on ice). *Expect:* NO damage — a hard horizontal stop while grounded with no wall is no longer billed as a wall impact.
- [ ] **Run into a cobweb at sprint.** *Expect:* no damage.
- [ ] **Dismount a moving boat/horse.** *Expect:* no damage from the dismount itself (40-tick grace).

## 6. Blocks: interactions, breaking, cracking

- [ ] **Sprint through wooden doors / fence gates.** *Expect:* they fling open ahead of you (no speed loss), only from the push side for doors `[REG]`.
- [ ] **Sprint at a closed wall-mounted trapdoor.** *Expect:* it now opens ahead of you too `[FIX]`.
- [ ] **Sprint into an iron door.** *Expect:* does NOT open; fast enough impacts may crack/break it.
- [ ] **Hit note blocks / buttons / bells at speed.** *Expect:* they play/press/ring. Levers, chests, item frames: NO reaction `[REG]`.
- [ ] **High-speed impact into glass/ice.** *Expect:* shatters (fragile), you keep most momentum, graze damage only at very high speed.
- [ ] **Medium-speed impact into a breakable wall.** *Expect:* crack overlay appears; it CLEARS when you stop hitting it, and doesn't linger after the mob that made it dies `[FIX]`.
- [ ] **Crack a block, then take a nether portal.** *Expect:* the crack doesn't get "cleared" at the same coordinates in the other dimension (no weird behavior) `[FIX]`.
- [ ] **Elytra-crash through a grass block downward at speed.** *Expect:* dirt path flattening + shovel sound (unchanged) `[REG]`.
- [ ] **Anvil/falling block onto dripstone/sand interactions** (sand blasting copper, falling spike shattering). *Expect:* unchanged `[REG]`; a falling block corner-landing on two blocks now processes both.

## 7. Bounce & sticky things

- [ ] **Slime block wall corner hit.** *Expect:* rebound direction matches the face you hit hardest (no random direction), no ping-pong pinning `[FIX]` `[REG]`.
- [ ] **Honey block impact.** *Expect:* dead stop, no damage, no bounce.
- [ ] **Walk through a slime-block tunnel.** *Expect:* no random bounces while rubbing walls `[REG]`.
- [ ] **Falling slime block (from gravity feature) landing.** *Expect:* bounces with decay, eventually settles and places `[REG]`.
- [ ] **Two slime blocks floating side by side in the air** (place against a wall then remove the wall support). *Expect:* the pair FALLS — they used to hold each other up forever `[FIX]`.
- [ ] **Bigger sticky cluster collapse:** hang 3-6 slime/honey blocks off one support block and break the support. *Expect:* the whole cluster collapses cleanly, one block at a time, with NO crash (this exact scenario could throw a ConcurrentModificationException in an earlier iteration of the fix) `[FIX]`.
- [ ] **Slime block on a wall / hanging from ceiling.** *Expect:* still sticks (any-face support intact) `[REG]`.
- [ ] **Sand column collapse & slime attached to sand.** *Expect:* unchanged cascade behavior `[REG]`.

## 8. Entity collisions (set `entityCollisionDamage = true` in server config first)

- [ ] **Sprint into a cow.** *Expect:* modest push (not a perfectly elastic launch), both sides sync (no rubber-band).
- [ ] **Creative-mode dash into mobs.** *Expect:* YOU take nothing, but the mobs still take collision damage `[FIX]`.
- [ ] **Minecart at speed into mobs.** *Expect:* mobs take damage (was zero before) `[FIX]`.
- [ ] **Two fast flyers passing close without touching.** *Expect:* no damage to either `[FIX]`.
- [ ] **Fall onto a boat/horse.** *Expect:* auto-mount (players only), respects dismount cooldown.
- [ ] **Fall onto falling sand.** *Expect:* you do NOT mount it `[FIX]`.
- [ ] **Spectator standing in the path of a charging mob/anvil.** *Expect:* spectator camera is never shoved `[FIX]`.
- [ ] **Ram an iron golem gently.** *Expect:* clang sound, but it does NOT aggro unless the hit was hard (≥2 hearts) `[FIX]`.
- [ ] **Get knocked INTO a golem by something else (soft bump).** *Expect:* no aggro `[FIX]`.
- [ ] **Passenger on a crashing mount.** *Expect:* passenger damage is reduced by their armor and skipped for kinetic-immune mobs `[FIX]`.

## 9. Block phasing & engulfing

- [ ] **Elytra through tree canopy at 15+ m/s.** *Expect:* pass through leaves with light drag, no damage, no leaf breaking `[REG]`.
- [ ] **Dive into sand/gravel at high speed.** *Expect:* swallowed a few blocks deep, heavy drag, embedded once slow — dig out. Burrow particles/sounds play `[REG]`.
- [ ] **Walk/roll slowly across sand.** *Expect:* solid as normal, no rubber-banding `[REG]`.
- [ ] **Multiplayer: phase into sand as a player on a dedicated server.** *Expect:* no "moved wrongly" rubber-band `[REG]`.

## 10. Pressure & air `[FIX]`

- [ ] **Stand at Y=210 (above start level 200).** *Expect:* air bubbles actually START draining now (they were silently regenerating before). Drain speeds up with altitude.
- [ ] **Air runs out at altitude.** *Expect:* periodic suffocation damage; "suffocated in the thin air" on death.
- [ ] **Same spot with Respiration helmet.** *Expect:* noticeably slower drain.
- [ ] **Zombie/skeleton at high altitude** (`altitudePressureAffectsMobs=true`). *Expect:* undead never suffocate.
- [ ] **Deep dive below Y=0.** *Expect:* faster air loss than normal drowning; below Y=-32, periodic pressure damage; "crushed by the depths" on death.
- [ ] **Deep dive with Water Breathing or Conduit Power.** *Expect:* NO extra deep-water air loss `[FIX]`.
- [ ] **Creative/spectator at any altitude/depth.** *Expect:* untouched.

## 11. G-force effects, camera & audio (client)

- [ ] **Elytra loops/hard turns.** *Expect:* tunnel vision + blackout ramps smoothly; steady straight flight does NOT accumulate blackout `[REG]`.
- [ ] **Blackout ramp speed with unlocked vs 60-capped FPS.** *Expect:* same ramp speed at both (was FPS-dependent) `[FIX]`.
- [ ] **Audio muffle during blackout/drowning.** *Expect:* low-pass kicks in smoothly.
- [ ] **Press F3+T (resource reload) mid-session, then trigger blackout again.** *Expect:* the audio muffle STILL WORKS — it used to die until restart `[FIX]`.
- [ ] **Slime bounce camera at very low FPS** (cap to ~20 in your GPU driver or crank settings). *Expect:* camera eases to the bounce direction, never flails/spins divergently `[FIX]`.
- [ ] **Bounce, then open inventory immediately.** *Expect:* camera does NOT keep swinging behind the menu `[FIX]`.
- [ ] **Set minGForceThreshold == maxGForceThreshold in client config.** *Expect:* effects still function (no permanently-dead vignette) `[FIX]`. Reset after.
- [ ] **Blackout on Graphics: Fast and in third person.** *Expect:* still renders `[REG]`.
- [ ] **Creative/spectator.** *Expect:* no FOV/vignette/muffle effects.
- [ ] **Sprain effect after a hard landing.** *Expect:* slowness capped (never fully frozen), duration ≤ 30s `[FIX]`; jumping suppressed while sprained `[REG]`.
- [ ] **Clarity potion** (Awkward + Echo Shard): *Expect:* blackout builds noticeably slower.
- [ ] **Vertigo:** blackout builds faster. **NEW:** Long Clarity + Fermented Spider Eye → Long Vertigo `[FIX]`.
- [ ] **Nausea/blindness from sustained G:** *Expect:* effects apply and hold STEADY during sustained high-G (no pulsing/fading in and out while the G-force stays high), mobs included.

## 12. NoAI & immunity coherence `[FIX]`

- [ ] **`/summon zombie` then `/data merge entity <it> {NoAI:1b}` while it falls from height.** *Expect:* within ~1 second of the flag flip it becomes fully inert — it must NEVER take BOTH kinetic and vanilla fall damage (double), and never neither when the flag is OFF.
- [ ] **NoAI mob pushed off a ledge.** *Expect:* no kinetic damage, and vanilla fall damage still applies (unphysable = vanilla behavior).
- [ ] **kinetic_immune-tagged mob (e.g. bosses) falling.** *Expect:* no kinetic fall damage AND no vanilla fall damage (fully exempt).

## 13. Death messages & misc `[REG]`

- [ ] **Die to a wall impact / fall / flop / entity crush.** *Expect:* correct message with velocity appended (deathMessageAppend=true).
- [ ] **Vanilla "fell out of the water" message no longer overridden** (hard to trigger; just confirm no weird water death text on unphysable mobs).
- [ ] **Melee while sprinting toward a target.** *Expect:* small damage bonus; crit "ding" only on meaningful bonuses (not every walking hit) `[FIX]`.
- [ ] **Melee while backpedaling.** *Expect:* reduced damage (min 30%).
- [ ] **Arrows with wildMode=false.** *Expect:* plain vanilla arrow damage, but Kinetic Protection/Dampening armor still reduces it `[FIX]`.
- [ ] **projectilesHaveMomentum=true: shoot an arrow, let its chunk unload/reload (travel away & back).** *Expect:* the stuck arrow does NOT fly off on reload `[FIX]`.
- [ ] **Reflective / Kinetic Protection / Pullback enchantments.** *Expect:* unchanged behavior `[REG]`; Pullback anvil costs are sane now.
- [ ] **Sonic boom:** only if you can reach 343 m/s two ticks in a row (long elytra powerdive). *Expect:* boom + explosion sound, 1s cooldown; never triggers from /tp `[REG]`.

## 14. Valkyrien Skies integration `[VS]` `[PERF]` — critical after the raycast fast path

- [ ] **Stand on a moving/turning ship.** *Expect:* NO phantom damage, no blackout buildup from ship motion `[REG]`.
- [ ] **Board / disembark a ship.** *Expect:* no impact damage on the transition tick `[REG]`.
- [ ] **Elytra-crash into a ship hull at speed.** *Expect:* impact damage registers (ship blocks count as evidence). **This specifically validates that the new fast raycast path correctly defers to the ship-aware path near ships.**
- [ ] **Crash through a fragile block ON a ship.** *Expect:* block breaks, your motion is left to the ship solver (no wedging) `[REG]`.
- [ ] **Sprint at a door mounted on a ship.** *Expect:* still opens ahead of you (door look-ahead uses the ship-aware path near ships).
- [ ] **Set enableValkyrienSkiesCompat=false.** *Expect:* mod behaves as if VS absent (control test).

## 15. Performance `[PERF]` — the point of this whole exercise

- [ ] **Spark capture, 2 min, same world/conditions as your original profile** (~430 entities): `/spark profiler start --timeout 120`. *Expect:* `PhysicsDispatchServer.onLevelTick` total well under 0.5 ms/tick (was ~1.43). Send me the numbers either way.
- [ ] **Check the breakdown:** `detectBlocks` should be near-invisible; `grabCapability` and `isNoAi` should no longer appear as line items.
- [ ] **Stress: stand in a mob farm / dense cave** (mobs pressed against walls — worst case for the pre-check). *Expect:* still far below the old cost; TPS stable.
- [ ] **Item-heavy area (drop farms).** *Expect:* items/XP orbs cost ~nothing.
- [ ] **No new lag spikes** when many entities cross chunk borders or teleport.

## 16. Multiplayer / dedicated server (if you can)

- [ ] **Use the `-all` jar from `build/libs` when installing outside the dev environment** — it bundles MixinExtras; the plain jar will fail to load in packs where no other mod provides it `[FIX]`.
- [ ] **Mod loads on a dedicated server** (no client-class crash on boot).
- [ ] **Dismount grace works on the server** (no damage right after dismounting at speed).
- [ ] **Another player's impacts:** you hear their thuds/sloshes; their bounces don't rubber-band.
- [ ] **Config UI:** all FullStop entries show proper names (no raw `config.fullstop.*` keys) `[FIX]`.

## 17. Debug tooling

- [ ] **F3+Q help list** shows the F3+V line; **F3+V** toggles ray view with a chat message `[REG]`.
- [ ] **Ray view ON in a busy area.** *Expect:* FPS no longer tanks (rays update per tick, not per frame) `[FIX]`.
- [ ] **Ray view over water at speed.** *Expect:* water-surface hits now show `[FIX]` (note: rays are RAW clips — red rays on floors you walk on are normal and don't mean damage).

---

## Known cosmetic gaps (not bugs — no need to test)

- Clarity/Vertigo effect icons are still missing textures (art needed at
  `assets/fullstop/textures/mob_effect/clarity.png` / `vertigo.png`) — they'll show as purple/black squares.
- Debug F3 strings are English-only via fallback lang.

## When something fails

Note: **section number, what you did, what happened vs. the Expect line**, and if damage-related,
roughly your speed (elytra? sprint?) and the block/mob involved. That's enough for me to trace it.
