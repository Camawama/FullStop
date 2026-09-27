# FullStop In-Game Testing Checklist

**Build:** working tree, 2026-09-26 (ship weight from WeightRS + ship collisions + physics ownership / MKW compat; 2026-09-27: ship riders keep deck momentum, fast ship hits) — new sections **18-24** at the end; the list right below is from 2026-08-09d (shallow slime bounces + stack crushing + pressure-wave sonic boom)

- [ ] **Hit a long slime wall at a SHALLOW angle (10-30°) at speed, repeatedly.** *Expect:* always deflected OUT along the wall, keeping most forward speed — never mirrored backwards, never a back-and-forth settle. Head-on hits unchanged (full mirror).
- [ ] **Stack 3+ mobs (or drop an iron golem onto a cow).** *Expect:* the BOTTOM carrier starts taking periodic squish damage ("was squished too much" on death); a single similar-sized rider deals none. The stack sheds from the bottom up as carriers die.
- [ ] **Two players + supersonic flyby (VS ship or command).** *Expect:* flyer booms once at the crossing; the bystander hears ONE boom exactly when the flyer passes within ~96 blocks (louder the closer), and never again during that supersonic run. Drop subsonic, cross again → everyone booms once more.

- [ ] **Jump onto a cow (entity collisions on).** *Expect:* it PANICS and bolts with you on its back, noticeably slower than an unridden cow (~0.6×). It keeps wandering afterward instead of freezing. Look-around behavior unchanged.
- [ ] **Stack test: cow on cow (or two mobs on one).** *Expect:* the carrier is slower the heavier the stack.
- [ ] **Ride/push a loaded mob into deep water.** *Expect:* the carrier rides low / gets pushed under proportionally to the load — cow-on-cow submerges the lower one; unridden mobs float exactly as vanilla.
- [ ] **Saddled horse/pig regression.** *Expect:* steered mounts behave exactly as vanilla — no panic on mounting, no wandering while you steer.
- [ ] **Go above the altitude threshold with NO helmet.** *Expect:* bubbles drain smoothly downward — no flickering pop animations, no bouncing up and down.
- [ ] **Same with Respiration III.** *Expect:* bubbles drain ~4× slower but STILL go down steadily; spared ticks hold level, never rise. No blinking.
- [ ] **Fly supersonic (343+ m/s, VS ship or command).** *Expect:* ONE boom exactly when crossing the barrier; silence while staying supersonic however long; slow below ~330 and accelerate past 343 again → exactly one more boom.

- [ ] **`/summon minecraft:cow ~ ~1 ~ {Motion:[2.0,0.0,0.0]}` aimed at a wall a few blocks away.** *Expect:* the cow takes wall damage on impact (it was inside a 5-tick spawn immunity before). Falls unchanged.
- [ ] **Throw the hook at ice and let it LATCH.** *Expect:* exactly ONE crack step per throw — the crack must not deepen further (or break through) while the hook sits anchored. Repeated separate throws still accumulate.
- [ ] **Fire the tagged grappling hook at an ice WALL.** *Expect:* cracks on lighter hits, breaks through on fast ones (mass floor 0.5 — with your weight-50 config it hits hard). Also try a steep downward shot at floor ice: registers now.
- [ ] **Fire the hook between two slime walls.** *Expect:* it ricochets off BOTH — no dead stop at the second (bounce refractory reduced to 1 tick).
- [ ] **Player in a tight slime corridor.** *Expect:* rapid wall-to-wall bounces chain properly; rubbing along a single wall still never bounces (seam guard regression check).

- [ ] **Speed V+, sprint across flat sand.** *Expect:* smooth running — no sinking, no jitter, no sand breaking under you, no g-force blackout. Then DIVE into sand from a tall drop or steep elytra dive: engulfment still swallows you.
- [ ] **Elytra-fly horizontally into a dune wall above phase speed.** *Expect:* still punches in (body-height blocks phase as before).
- [ ] **Crack some ice, save & quit, re-enter the world.** *Expect:* cracks still visible AND still functional — one more good hit finishes a nearly-broken block instead of starting over. Cracks are PERMANENT now by default (no 30 s heal); set `crackHealSeconds` > 0 and verify they fade after that long.
- [ ] **Slam into a wall and watch the particles.** *Expect:* they burst from the impact point on the block face, not from your body center. Check a ceiling hit and a floor landing too.

- [ ] **Jump repeatedly onto the CORNERS of stairs from 1-3 blocks up.** *Expect:* only normal (tiny/zero) fall damage — no phantom spikes, no deaths. Also hop along slab edges and up/down staircases at sprint.
- [ ] **Spawn a fast boat at a STANDING player (dispenser/summon with Motion).** *Expect:* damage on hit, standing or moving — and the death message names the boat ("was hit by Boat"), not "experienced kinetic energy". Sprint into a parked boat fatally: "slammed into Boat".
- [ ] **Rub along walls in ALL four directions with F3+V.** *Expect:* rays touching the wall stay YELLOW in every direction — north/west no longer flash-then-green.
- [ ] **Tag a modded projectile (e.g. grappling hook) into `fullstop:physics_projectiles` via datapack.** *Expect:* fired at slime it bounces instead of latching; fired fast at glass it shatters through (bump its `entityWeights` mass if it lacks punch); fired through a mob it hurts it (entity collisions on). Untagged projectiles (arrows) unchanged — they stick.
- [ ] **Tag an entity into `fullstop:physics_blacklist`.** *Expect:* FullStop ignores it entirely (no bounces, no kinetic damage, no collisions).
- [ ] **Shift-dismount a boat at speed (retest #4).** *Expect:* you fly out forward, the boat glides STRAIGHT on without you — no sideways veer, no speed dump into you. After ~2 s you and the boat push each other normally again (walk into it: it nudges).
- [ ] **Dismount at speed, WAIT for the boat to fully stop, re-enter it.** *Expect:* the boat stays put — no ghost launch with its old speed. Then retest boarding a boat that IS moving: momentum still transfers.

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
- [ ] ~~Leave cracked ice alone ~30 s~~ (superseded: cracks are permanent by default now, `crackHealSeconds` config).
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

## 18. Ship weight (formerly WeightRS) `[VS]`

Needs Valkyrien Skies, `enableValkyrienSkiesCompat` and `enableShipWeight` (both on by default). Remove the WeightRS jar first.

- [ ] **Launch with an old `config/weightrs-common.toml` present (WeightRS removed).** *Expect:* log "WeightRS is part of FullStop now: … renamed to weightrs-common.toml.migrated"; old-layout files also get a `.pre-camafy` backup and their weights appear in `config/camafy-weight.toml`. Launch again: no second import.
- [ ] **Open a world for the first time after that.** *Expect:* log "Imported N WeightRS ship-weight setting(s) into …/serverconfig/fullstop-server.toml"; tuned WeightRS values (e.g. `gravity = 10`) show in the new "Ship Weight" section and `configVersion = 2`. Reopen: no second import. A value you already changed in FullStop's file is never overwritten.
- [ ] **Keep the old WeightRS jar installed.** *Expect:* one log warning at startup ("WeightRS is installed: Full Stop's ship weight stays off…"); `/fullstop ship info` says "weight off (WeightRS installed)"; the ship sinks only as much as with WeightRS alone (nothing weighs twice); `/weightrs` is WeightRS's own command.
- [ ] **Stand still on a small floating ship; bring 10 friends / spawn 20 cows on deck.** *Expect:* the deck sinks/tilts toward the load; walking to one edge tips it that way. Leaving the ship: it recovers.
- [ ] **Drop a stack of iron blocks (items) on the deck.** *Expect:* the ship dips on the landing and stays lower while they lie there (`enableItemEntityPhysics`).
- [ ] **Jump on the deck repeatedly.** *Expect:* a small dip on every jump (the jump's reaction), and a bump on every landing.
- [ ] **Fall 10+ blocks onto a deck.** *Expect:* a clear slam (the momentum you lose), ONE push per landing — not two for the two-tick impact. FullStop fall damage as on the ground.
- [ ] **Ride a boat / minecart / horse off a ledge onto a deck; drop a mob onto it.** *Expect:* each landing pushes the ship (WeightRS only handled living fall events; FullStop measures every body).
- [ ] **`includePlayers = false`.** *Expect:* players weigh nothing and their jumps/landings push nothing; mobs still do.
- [ ] **Under a Gravity Unbound field, walk on a ship's wall/underside.** *Expect:* the standing weight pushes along the field's pull, not world-down.
- [ ] **`/fullstop weigh @e[type=cow,limit=1,sort=nearest]`, `/fullstop weigh held`, `/fullstop ship info`, `/weightrs weigh @s`, `/weightrs held`.** *Expect:* weights with body/carried/basis, the held stack's mass, the nearest ship's mass/speed/grace; the `/weightrs` aliases work (only while WeightRS is absent).
- [ ] **`enableShipWeight = false` (edit the world's server config while playing).** *Expect:* within a tick or two the deck stops carrying any load (standing forces lapse on their own).

## 19. Ship collisions — entities `[VS]`

Setup: a ship you can drive (Eureka / VS helm), `/effect give @s resistance` for yourself while testing on foot. `debugShipCollisionLog = true` prints every hit.

- [ ] **Ram a zombie standing in the open at ~8 m/s.** *Expect:* it is shoved away along the hull (no damage below the 10.5 m/s horizontal threshold); the ship barely slows.
- [ ] **Ram a zombie at 20+ m/s.** *Expect:* it flies off, takes damage (≈ (speed − 10.5) × hull-block hardness factor × 1.07; oak-plank hull at 20 m/s ≈ 10), hull-block particles and a thud. Death message "was hit by a ship" (+ "(at X m/s)").
- [ ] **Same while seated at the helm.** *Expect:* death message "was hit by <you>'s ship"; the kill counts for you; a mob that survives targets you.
- [ ] **Pin a zombie between the hull and a stone wall, keep pushing.** *Expect:* no knockback, "was crushed by a ship", damage ×1.5, repeating about every half second while the hull presses.
- [ ] **Survival player (second account or `/gamemode survival`) in the ship's path.** *Expect:* pushed and damaged like a mob; on a `pvp=false` server with a pilot seated, pushed but NOT damaged (the pilot is the attacker).
- [ ] **Creative/spectator player, a mob tagged `fullstop:ship_immune`, a `fullstop:physics_blacklist` entity in the path.** *Expect:* never pushed, never damaged.
- [ ] **Stand / sit / walk / jump on the moving ship's own deck, ride a boat parked on the deck.** *Expect:* never hit by your own ship `[REG]`.
- [ ] **A light ship (a few blocks) rams an iron golem.** *Expect:* the ship visibly slows (reaction impulse); `reactionImpulses = false` → it doesn't.
- [ ] **Assemble a ship right next to (or around) a mob; paste a moving ship; load a chunk with a moving ship; `/vs teleport` a ship.** *Expect:* no hits during the grace (`newShipGraceTicks`, 40 ticks for new ships, 10 after a teleport).
- [ ] **Walk into a slowly drifting hull on foot.** *Expect:* ordinary FullStop wall contact (no ship "hit", no knockback) — the ship must supply most of the closing speed.
- [ ] **Kinetic_immune mob (slime, cat) rammed at speed.** *Expect:* pushed, never damaged.
- [ ] **A mob riding a horse, rammed at 20 m/s.** *Expect:* the horse takes full damage, the rider half, and the rider is thrown off on a hard hit.
- [ ] **`damageEntities = false`.** *Expect:* ships still shove, never hurt.
- [ ] **A survival player (second account) standing on open ground in front of a ship doing 80+ m/s** (`/fullstop ship info` shows its speed). *Expect:* the player is launched along the hull at about (1 + `entityRestitution`) × the ship's speed, ≈ 104 m/s at 80 m/s (F3 or a speedometer mod on their client) - not capped at 78 m/s - and dies from the hit; the body keeps flying and the death screen's camera follows it. With `debugShipCollisionLog = true` the hit line shows `kick` ≈ that speed.
- [ ] **A zombie standing on open ground, rammed at 60, 80, 120 m/s.** *Expect:* hit every time (never flown through), killed, and the body flies on ahead of the ship. `debugShipCollisionLog` shows no `jumped` line for the ship unless it really teleported.
- [ ] **A zombie on flat ground rammed by a ship pitched a few degrees nose-down.** *Expect:* launched, "was hit by a ship" - not "was crushed by a ship". Pinned against a wall it is still crushed; a ship settling down onto it from above still crushes it.

## 20. Ship collisions — terrain, vegetation, impact sounds `[VS]`

**Backend:** everything here works on all three of Valkyrien Skies' physics backends (`physicsBackend` in `<world>/serverconfig/valkyrienskies/vs-core-server.toml`, read when the world loads). On `KRUNCH_PHYSX` and `KRUNCH_JOLT` crashes come from VS's collision events; on the default `KRUNCH_CLASSIC`, which sends none at all, from Camafy's collision fallback (Camafy 0.1.2+). The log says which, once per world ("Camafy: ship collisions come from Valkyrien Skies' collision events" or "Camafy: Valkyrien Skies runs KRUNCH_CLASSIC … Camafy's collision fallback detects them instead"). Run the list on the default backend and on PhysX. On Jolt, VS reports one contact point per collision (PhysX five, the fallback up to eight), so a hull loses fewer blocks per tick there.

Every block has a **toughness** = hardness × (Valkyrien Skies' mass for it ÷ 1000 kg) — leaves 0.04, grass 0.75, dirt 0.63, oak planks 1.0, logs 1.6, stone 3.75, cobblestone 4.8, deepslate 8.7, iron block 39, obsidian 250. At each contact only the WEAKER of the terrain block and the hull block there can break (the hull's only with `hullDamage`, on by default); equal toughness never breaks. Nothing breaks below `breakMinSpeed` (5 m/s). Watch the log with `debugShipCollisionLog = true`: "ship N (… kg at … m/s) vs Block{…} at …: terrain gives / hull Block{…} gives, cost … of … units -> broken/cracked/held", "holds (toughness hull … vs terrain …)", "swept N block(s)".

- [ ] **Wooden ship (~150 t, about 300 planks) bumps a stone cliff at ≤ 3 m/s.** *Expect:* nothing breaks; a wood + stone thud.
- [ ] **Same ship rams the cliff head-on at ~8 m/s.** *Expect:* a few (≈ 4) bow planks at the contact break and drop as items in the world (under the bow, not somewhere far off); the stone is untouched; the ship stays stopped.
- [ ] **Wooden hull into stone / cobblestone / deepslate at ANY speed.** *Expect:* the rock never breaks (log: "hull … gives" or "holds").
- [ ] **A 3×3×3 stone cube assembled ten blocks above grass, then dropped.** *Expect:* the grass under it breaks (a little dirt below too), the stone further down holds; grass AND stone landing sounds.
- [ ] **Obsidian ship dropped onto / rammed into obsidian, any speed.** *Expect:* NOTHING breaks on either side; the ship stops dead.
- [ ] **Obsidian ship into grass / dirt / stone / deepslate.** *Expect:* they break cheaply (grass ≈ 6 units, stone ≈ 30) and drop like mined blocks; the ship ploughs on, slowing a little per block.
- [ ] **Glass portholes (fragile → toughness 0.02) hitting anything at ≥ 5 m/s.** *Expect:* the glass at the contact shatters; the terrain holds.
- [ ] **`hullDamage = false`, ram stone with the wooden ship.** *Expect:* nothing breaks; the ship just stops.
- [ ] **`massInfluence = 0`.** *Expect:* hardness alone decides again: an oak-plank hull (2.0) breaks stone (1.5).
- [ ] **Iron-block hull into ice (crackable), slowly then faster.** *Expect:* persistent cracks that finish on a later hit, or it smashes outright.
- [ ] **Any hull into bedrock / a `fullstop:ship_unbreakable` block / a chest / slime / honey.** *Expect:* those never break (a plank hull against bedrock still splinters). Chests - on the terrain or on the ship - only with `terrainBreakBlockEntities = true`.
- [ ] **Ship with an iron hull ploughs into a dirt hill.** *Expect:* keeps going while everything it hits breaks, slowing each block (`terrainBreakDrag`), and stops when its energy runs out or it meets something that holds (then it STAYS stopped).
- [ ] **A heavy iron ship head-on into stone at 15 m/s.** *Expect:* at most 12 blocks per tick for the ship, 24 per level (`terrainMaxBlocksPerTick`).
- [ ] **A ship resting on the ground, sliding along it, rolling over a hill, or pressed into a wall by its engines.** *Expect:* nothing breaks, the hull never wears away, no sound after the first touch.
- [ ] **`mobGriefing = false`, nobody at the helm, ram a wall / fly through a canopy.** *Expect:* nothing breaks, nothing is swept. Seated at the helm: both happen (you are acting). `unpilotedFollowsMobGriefing = false`: unpiloted ships break too.
- [ ] **Ram a claim-protected area (FTB Chunks / Open Parties & Claims / similar) and a Game Start arena with block breaking off.** *Expect:* nothing breaks or is swept there (break event with the pilot or the "[Full Stop]" fake player; `MatchPolicies.mayAlter`).
- [ ] **Two ships collide.** *Expect (default `shipVsShipBreaking = NONE`):* no ship block breaks, both hulls' sounds play. FRAGILE: only glass/snow on either ship breaks. FULL: the weaker of the two touching blocks by toughness.
- [ ] **`breakTerrain = false`.** *Expect:* terrain never breaks and nothing is swept; hulls still take damage against tougher terrain (unless `hullDamage = false`); impacts still sound.

Vegetation (`fullstop:ship_sweepable`):
- [ ] **Fly a ship at 6+ m/s through an oak/birch/jungle canopy.** *Expect:* the leaves the hull passes through break (saplings, sticks, apples drop), the ship slows slightly (`sweepDrag`); flowers, tall grass, ferns, sugar cane, berry bushes and vines in its path go too.
- [ ] **Drift through the same canopy at 2 m/s.** *Expect:* the ship slides through untouched (plain VS behaviour).
- [ ] **A player-placed hedge or leaf roof.** *Expect:* untouched (`sweepPlacedLeaves = false`); with it on, cleared like natural leaves.
- [ ] **Crops, kelp, seagrass, waterlogged leaves in the path.** *Expect:* never broken.
- [ ] **A large ship ploughing a jungle at speed (performance).** *Expect:* no TPS drop worth noticing (spark); at most 32 blocks per ship and `sweepMaxBlocksPerTick` (64) per level each tick, so a very fast big ship may leave some canopy behind.

Impact sounds:
- [ ] **Drop a stone cube onto grass from 2, 5 and 10 blocks.** *Expect:* grass AND stone sounds at the contact each time, louder and deeper the higher the drop; from ~6 m/s a harder hit layer, from ~12 m/s a crunch; block-crack particles of both from 4 m/s.
- [ ] **A heavy ship vs a light ship landing at the same speed.** *Expect:* the heavy one a little louder and deeper.
- [ ] **A ship resting / sliding / grinding along terrain.** *Expect:* one sound when it touches down, then quiet (no machine-gun).
- [ ] **`breakTerrain = false` and `hullDamage = false`.** *Expect:* impacts still sound.
- [ ] **`impactSounds = false`.** *Expect:* silent. `impactSoundVolume = 2`: heard from twice as far. `impactParticles = false`: no particles.

## 21. Ship collision checks in dev `[VS]`

- [ ] **Automated: `gradlew runGameTestServer -PshipTests -PvsBackend=classic`, then `=physx` and `=jolt`** (headless; its own `run-gametest/` directory and a fresh flat world each run, started on the backend asked for, PhysX when none is given; needs Camafy 0.1.3, which `gradle.properties` asks for). *Expect:* "All N required tests passed" on each. The material tests check the toughness rule, the calibration and the sound mapping against VS's real block masses. The ship tests assemble real VS ships and crash them: a plank cube into a stone wall, a stone cube onto grass, obsidian onto obsidian, a 16x2x16 raft landing flat, a stone raft hopping five times (one burst of contacts per landing), a plank cube onto a static ship (ship-to-ship), and a plank cube through leaves fast and slow. They also check that a ship resting or sliding on the ground gets no fallback contact, and that Camafy used the source the backend calls for, only that one (on PhysX/Jolt the fallback never runs, so nothing is reported twice). Entity hits: a floating plank ship rammed into a villager at 8, 36 (twice, once with `damageEntities = false`), 60 and 80 m/s, plus 120 and 200 m/s as optional tests - each launch must match the rule within 5% and the ship must never enter jump grace; the pin check (open ground vs a wall ahead, pushes from level to straight down); `syncLaunchToPlayer`. Deck momentum (batch `fullstop_deck_momentum`, ship weight off for it): a raft stopped dead from 23 m/s throws the cow on its bare deck forward off the bow while a cow on honey and one in a VS seat stay; a raft braking gently, one resting on the ground and one turning at 0.5 rad/s throw nobody. Every contact is logged as a `[FullStop ship test] … contact …` line. `-PcollisionFallback=shadow` also logs what the fallback would have reported next to VS's events; `-PhideBackend` makes Camafy treat the backend as unknown (fallback until VS's first event).
- [ ] **On the default `KRUNCH_CLASSIC` backend, crash a ship into terrain, drop one onto another ship, and slide one along the ground.** *Expect:* the crashes break and sound as on PhysX (wooden ship into stone at ~8 m/s loses bow planks and the stone holds; stone cube onto grass crushes it), the ship-on-ship drop sounds both hulls, and the slide is silent. With `-Dcamafy.debugCollisions=true` each crash logs `fallback: ship … slowed by … m/s` and a `via fallback` collision line. A ship scraping along a wall at a shallow angle may go unreported: the fallback only sees ships that suddenly slow down.
- [ ] **Add `-Dcamafy.debugCollisions=true` to a dev run config (the ship tests set it), crash a ship into terrain and into another ship, then read the log.** *Expect:* `[camafy.debugCollisions]` lines per collision. Confirm (a) "point … m outside the ship's bounds" is about 0 (contact points are WORLD space); (b) for a ship that landed flat the normal is about (0,1,0) (from terrain toward the ship); (c) right after a slam, "approach X m/s" vs "ship's speed into it now": large approach with ~0 speed ⇒ VS measures before its solver; both ~0 (or both negative, the bounce) ⇒ after. Measured by the ship tests: PhysX measures AFTER its solver (a 12 m/s ram reports -3.4 m/s, the rebound), Jolt BEFORE (11.7 m/s); FullStop uses last tick's hull speed for contacts that just began, so both work.
- [ ] **Place a stone wall in an area that was already loaded, then drive a ship into it (PhysX).** *Expect:* the ship stops at the wall. In the game-test server, blocks placed after VS loaded their chunk did not collide at all (ships passed through them, on every backend); if that happens in a real game too, it is a VS limitation to report, not Full Stop's.
- [ ] **Camafy's one-time log of the learned normal convention.** *Expect:* it matches (b). If normals come out reversed, fix with `-Dcamafy.vsCollisionNormal=towardA` or `towardB` and report it.
- [ ] **FullStop's `debugShipCollisionLog = true`.** *Expect:* one line per breaking decision (see §20), per sweep, and per entity hit ("ship N hit Zombie: approach … lost … kick … pinned=…").

## 22. MKW Mobility compatibility (physics ownership) `[FIX]`

Needs MKW Mobility with its Camafy physics claims (karts ALL; seated drivers IMPACT/G_FORCE/BOUNCE/CONTACT/BLOCKS, plus MOTION/ENVIRONMENT in races). `debugImpactLog = true` shows `claims=…` per impact.

- [ ] **Drive a kart into walls at full speed, free roam and in a race.** *Expect:* no FullStop damage, thud or particles for the kart or its driver.
- [ ] **Drive over glass, ice, snow, quartz track blocks; through doors, gates, buttons, note blocks.** *Expect:* FullStop never cracks, breaks, opens or presses anything for a kart.
- [ ] **Race with boosts, hops, drifts, glides, slime kicks.** *Expect:* no blindness, nausea, vignette, FOV stretch or audio muffle from FullStop; no bounce-camera turns.
- [ ] **Race on a track above Y 200.** *Expect:* no air loss or drowning muffle while racing (free roam: FullStop's altitude pressure applies as normal on foot).
- [ ] **Free roam: hop off a moving kart.** *Expect:* you keep the kart's momentum; no phantom impact at the moment you leave the seat; a real crash afterwards hurts as usual.
- [ ] **Fall off a kart from a height (free roam).** *Expect:* FullStop landing damage applies once you are off the kart.
- [ ] **Hit by a kart / hazard (shell, banana) as a bystander.** *Expect:* MKW's damage only — FullStop doesn't scale it by speed (no ~6× hazard hits).
- [ ] **`entityCollisionDamage = true`: run into a parked kart and a driven kart on foot.** *Expect:* no FullStop momentum exchange or collision damage with the kart or its driver.
- [ ] **A mob run over by a kart, then flying into a wall.** *Expect:* Automobility's run-over hit, then FullStop's wall damage for the mob's flight (the mob is FullStop's).
- [ ] **A VS ship rams a kart with a driver.** *Expect:* neither pushed nor damaged by the ship. A kart parked on a ship deck weighs on it; a kart landing on a deck thumps it.
- [ ] **Ride a boat as a PASSENGER (second seat) while a player drives fast and turns.** *Expect:* no g-force blindness/nausea from the smooth ride (passengers replay the vehicle's motion now).

## 23. Modpack rules `[FIX]`

- [ ] **`entityCollisionDamage = true`, a player drives a boat into another player on a `pvp=false` server.** *Expect:* no damage. With pvp on: "was hit by <driver>'s Boat", kill credit to the driver; a mob hit that way targets the driver.
- [ ] **`mobGriefing = false`: launch a mob into glass at speed; crash into glass yourself; fire your grappling hook into ice.** *Expect:* the mob breaks nothing; you and your hook still do.
- [ ] **Crash into blocks inside a claim you don't own / a Game Start no-break arena.** *Expect:* nothing breaks or cracks (protection mods see a break event from you, or from "[Full Stop]" for mobs).
- [ ] **`/gamerule drowningDamage false`, then high altitude / deep water.** *Expect:* no atmosphere/pressure damage to players (both are `#minecraft:is_drowning` now).
- [ ] **Blackout at full g next to other HUD layers (Space Events telescope glare, MKW HUD).** *Expect:* the blackout darkens the whole screen, HUD and glare included.
- [ ] **Gravity Unbound, non-default gravity: shift-dismount a moving boat.** *Expect:* no sideways veer (FullStop's push veto runs before Gravity Unbound's push rewrite).
- [ ] **Gravity Unbound surface-cling release / gravity flip at speed.** *Expect:* no phantom impact damage (client-side discontinuities reach the server now).
- [ ] **Client and server must both run this build** (network protocol 4). *Expect:* an older FullStop on either side is refused at login.

---

## 24. Ship riders (deck momentum) `[VS]`

Setup: a ship you can drive at 20+ m/s (Eureka / VS helm, or `/vs` commands), something solid to crash it into, `/effect give @s resistance`. `debugShipCollisionLog = true` logs every throw ("ship N threw …: deck lost (…) m/s, rider keeps (…) m/s").

- [ ] **Stand on the bare deck (not seated) while someone crashes the ship into a cliff at 20+ m/s.** *Expect:* you keep going: thrown forward off the bow at about the ship's old speed, with a little lift, into the cliff (FullStop impact damage). At 20 m/s a thrown player or mob flies roughly 4-5 blocks (vanilla air drag takes 9% of it every tick), at 80 m/s much further. No rubber-band on your client.
- [ ] **Same, standing on a slime or honey block on the deck.** *Expect:* you stay where you stand.
- [ ] **Same, standing on Gravity Unbound gravity plating on the deck (floor, or plating on a wall you walk on), and again wearing Surface Cling boots.** *Expect:* you stay put in both cases.
- [ ] **Same, seated in a helm or seat.** *Expect:* you stay seated. With `deckMomentumSeated = true`: thrown out of the seat and forward.
- [ ] **Cruise, brake gently, turn gently, drift and rest on the ground with mobs and dropped items on deck.** *Expect:* nobody slides or is thrown. Brake or turn harder than 1 g (or lower `deckGrip`): riders slide the other way; a ship lurching forward hard leaves them sliding aft.
- [ ] **Mobs, a boat with a passenger, and dropped items on the deck when the ship crashes.** *Expect:* all thrown forward (the boat with its passenger aboard); NoAI mobs and `fullstop:physics_blacklist` entities stay.
- [ ] **Creative-flying above the deck, an MKW kart parked on the deck, a grappling player swinging from the ship.** *Expect:* never thrown by the deck.
- [ ] **Jump on the deck and have the ship stop while you are in the air.** *Expect:* you keep flying forward at the ship's old speed (VS keeps carrying riders for a moment after they leave the deck; that carry is what stops).
- [ ] **`/vs teleport` a moving ship with riders.** *Expect:* nobody is thrown (the teleport puts the ship in its short grace).
- [ ] **`deckMomentum = false`.** *Expect:* the old behaviour: riders stop dead with the ship.

## Known cosmetic gaps (not bugs — no need to test)

- Clarity/Vertigo effect icons are still missing textures (art needed at
  `assets/fullstop/textures/mob_effect/clarity.png` / `vertigo.png`) — they'll show as purple/black squares.
- Debug F3 strings are English-only via fallback lang.

## When something fails

Note: **section number, what you did, what happened vs. the Expect line**, and if damage-related,
roughly your speed (elytra? sprint?) and the block/mob involved. That's enough for me to trace it.
