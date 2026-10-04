# FullStop In-Game Testing Checklist

**Build:** working tree, 2026-09-26 (ship weight from WeightRS + ship collisions + physics ownership / MKW compat; 2026-09-27: ship riders keep deck momentum, fast ship hits; later on 2026-09-27: solid hulls, entities hitting ships as two bodies, heavy riders on small rafts, ship velocity debug rays, spin nausea build-up, vehicles and karts in free roam) — new sections **18-33** at the end; the list right below is from 2026-08-09d (shallow slime bounces + stack crushing + pressure-wave sonic boom); round 2 on 2026-09-27: riders on spinning ships, hull strikes swept in the ship's frame (fast rotors), hull friction and lift, frozen ships' debug rays, ships breaking ships by default; round 3 on 2026-09-27: players and fast ships (hull hits judged on the player's client, the player measured with its client's ship carry, ship ray hits only when real, deck jolts must stand), seated riders feel the ship's g-force, debugShipContactLog; round 4 on 2026-09-27: zero and low gravity (sections **34-35**; needs Camafy 0.1.7), slime and honey never fall off ships (section **36**); slime bounces and honey glue on ships (section **37**; needs Camafy 0.1.10)

- [ ] **Hit a long slime wall at a SHALLOW angle (10-30°) at speed, repeatedly.** *Expect:* always deflected OUT along the wall, keeping most forward speed — never mirrored backwards, never a back-and-forth settle. Head-on hits unchanged (full mirror).
- [ ] **Stack 3+ mobs (or drop an iron golem onto a cow).** *Expect:* the BOTTOM carrier starts taking periodic squish damage ("was squished too much" on death); a single similar-sized rider deals none. The stack sheds from the bottom up as carriers die.
- [ ] **Two players + supersonic flyby (VS ship or command).** *Expect:* flyer booms once at the crossing; the bystander hears ONE boom exactly when the flyer passes within ~96 blocks (louder the closer), and never again during that supersonic run. Drop subsonic, cross again → everyone booms once more.

- [ ] **Jump onto a cow (legacy entity collisions: `enableEntityCollisions` off, `entityCollisionDamage` on).** *Expect:* it PANICS and bolts with you on its back, noticeably slower than an unridden cow (~0.6×). It keeps wandering afterward instead of freezing. Look-around behavior unchanged.
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

## Create Catastrophes: contraptions strike `[NEW]` (needs Create)

- [ ] **Stand in the sweep of a mechanical bearing's arm (a few planks glued to it) at 64 RPM.** *Expect:* the arm knocks you away and hurts you (about 13 damage at 3.5 blocks from the axis), dust and the planks' sound where it hit; at 256 RPM it never passes through you, it launches you hard.
- [ ] **Slow it to 8 RPM.** *Expect:* the arm only shoves you along, as Create does: no damage, no fling.
- [ ] **Stand on a plate the bearing turns, and ride a fast piston or pulley elevator.** *Expect:* carried, never struck, never hurt.
- [ ] **Get rammed by a mechanical piston at full speed, then with a wall behind you.** *Expect:* knocked ahead of the ram; against the wall, crushed (no knock-away).
- [ ] **Stand in front of a train.** *Expect:* one hit, judged like a ship's ("was struck by a contraption"), not Create's run-over on top of it.
- [ ] **Turn `contraptionImpacts` off.** *Expect:* Create's own behaviour: contraptions shove, trains deal their run-over damage.
- [ ] **Remove Create.** *Expect:* FullStop loads and plays exactly as before; no errors about Create in the log.
- [ ] **A gantry at full speed rams you, a rope pulley's platform comes down on you.** *Expect:* a hit each (gantry 9.8 m/s, knocked away; the platform pins you against the ground), not just a shove.
- [ ] **F3+V by a spinning bearing arm, a gantry, a cogwheel and a water wheel.** *Expect:* the arm's paths curve round its axis and turn red where they reach you at speed; the gantry's go straight along its shafts; the cogwheel and wheel show a ring with arrows (red when fast enough to bite or drown, grey when stopped) and a line from you to the teeth or the wheel's bottom while you're caught.
- [ ] **`debugContraptionLog` on, stand by a moving contraption.** *Expect:* the log says why it didn't strike you (carried, a gentle push, ...), once a second per reason.

## Create Catastrophes: gears and water wheels `[NEW]` (needs Create)

- [ ] **Walk into the teeth of a single cogwheel turning at 4, 16 and 64 RPM.** *Expect:* never hurt, never pulled in; pushed off along the way the teeth move (barely at 4 RPM). Standing half a block away nothing happens at all `[FIX]`.
- [ ] **Walk into a single cogwheel at 256 RPM, and a large cogwheel at 128 and 256.** *Expect:* flung, a whack sound: up and out where the teeth run up (about 20 m/s from a cogwheel, about twice that from a large one at 256), squeezed out along the floor where they run down; the gear itself never hurts you, the landing or a wall you hit may. Once per half second per gear `[NEW]`.
- [ ] **Stand between a large cogwheel at 256 RPM and a wall two blocks away.** *Expect:* thrown into the wall and hurt by it (Full Stop's impact), not by the gear `[NEW]`.
- [ ] **F3+V by a single gear at 256 RPM.** *Expect:* its ring and arrows red (it flings), yellow at 64; red line out from you while you touch it `[NEW]`.
- [ ] **Two cogwheels side by side on one axis (or a large and a small one diagonally), spinning; step into the V where both gears' teeth run into the mesh.** *Expect:* drawn in and crushed (about 3 a bite at 64 RPM, "was caught in the gears"; with a large cog "was ground up by a large cogwheel"); on the other side of the pair (where the teeth come out of the mesh) you're only pushed away `[NEW]`.
- [ ] **The same pair driven by a single water wheel or a hand crank.** *Expect:* the moment you're caught the network overstresses (goggles say so) and the gears stop with you wedged in them; step out and they turn again. Driven by a creative motor they keep crushing `[NEW]`.
- [ ] **Sneak while caught in a nip, then walk out of the gears' plane.** *Expect:* sneaking pulls in slower; walking out along the axles frees you within a second `[NEW]`.
- [ ] **An encased cogwheel, a gearbox, a shaft, a cogwheel turning under 4 RPM.** *Expect:* nothing at all.
- [ ] **Swim under / next to a water wheel turning in a channel (on the side the water falls).** *Expect:* swept down and held under the paddles; holding jump doesn't bring you up; air runs out, then drowning damage. Swimming sideways along the axle gets you out. After 30 s held you are let go.
- [ ] **Stand on top of a turning water wheel.** *Expect:* the paddles sweep you off the downstroke side into the water.
- [ ] **Creative mode near any of these.** *Expect:* never caught, never hurt; on foot a single gear still pushes you off, and a very fast one flings you `[NEW]`.
- [ ] **A cogwheel on a Valkyrien Skies ship (Clockwork), spinning, with you beside it on the deck.** *Expect:* the same pull and bites as on the ground.
- [ ] **`gearHazards` / `waterWheelHazards` off.** *Expect:* Create's vanilla behaviour.

## Create Catastrophes: contraptions meet terrain and ships `[NEW]` (needs Create)
- [ ] A bearing with an iron arm at 64 RPM swept into a stone block breaks it in one pass (drops, crack sound); a stressometer on its network shows the load jump and fade; the arm keeps turning.
- [ ] The same arm at 16 RPM cracks the stone (overlay) and jams with its tip against the stone (Create's overstressed state, goggles say so). It stays there as long as the motor runs: it never pushes into the stone, never restarts by itself, never breaks through `[FIX]`.
- [ ] An arm into bedrock (or obsidian it can't break) jams against it and stays jammed. Stop the motor: the arm is set down as blocks at the last quarter turn it passed, whole, the obstacle untouched. Start it again: it turns into the obstacle and jams again. Reverse the motor instead: it turns away. Remove the obstacle instead: it turns on `[FIX]`.
- [ ] A gantry with an iron ram at full speed into bedrock stops right in front of it and is set down as blocks there; it doesn't keep starting up into it `[FIX]`.
- [ ] A wool arm into stone loses its tip: the wool vanishes from the drawn contraption (no ghost block), drops, and the rest keeps turning; the stone stands.
- [ ] An arm at 8 RPM passes through stone as in Create (nothing breaks, no jam).
- [ ] A gantry with a heavy ram (iron) at full speed ploughs through dirt; a mechanical piston retracting never breaks its own poles.
- [ ] A minecart contraption on a rail at full speed stops dead at a bedrock wall across its load; a furnace cart (4 m/s) passes through.
- [ ] A fast arm swats a small VS raft (pushed, planks broken); a heavy or static ship jams the arm.
- [ ] F3+V on a spinning arm: the paths start on the arm's blocks (its corners and faces), not out in a wide circle around it `[FIX]`. A fast arm's paths turn red where they reach stone it will break.
- [ ] F3+V on a cogwheel spinning clockwise: its arrows point clockwise, the way its teeth move; and the teeth push you that way too `[FIX]`.
- [ ] F3+V on two meshing cogwheels: a wedge at the in-running side of their nip with an arrow into the mesh (red when fast enough to crush, grey when jammed), and a red line from you into the mesh while you're caught `[NEW]`.
- [ ] F3+V on a train, a minecart contraption, a gantry, a piston and a pulley: every line starts on the contraption's blocks; nothing is drawn from far outside it `[FIX]`.
- [ ] A bearing arm at least 13 blocks long at 256 RPM, heard from 20-30 blocks away: one sonic boom as it goes supersonic, then a buzzing train of cracks (about 4 a second) while it spins; riding it you hear no boom. A shorter arm never booms `[NEW]`.
- [ ] Death messages: kill a mob (or yourself in survival) with a cogwheel, a large cogwheel, a fast water wheel's paddles, by drowning under a water wheel, and with a windmill, a piston, a gantry, a pulley, a train: each has its own message; pinned against a wall / a ship, the crushed variants.

## 0. NEW in this build `[FIX]` — test these first

**IMPORTANT: delete `velocityDamageThresholdHorizontal` from the saved server config (or set it to 10.5) — old configs keep 12.77 and mask the grounded-damage fix.**

- [ ] **Jump repeatedly, timing space exactly on each landing (bunny hop).** *Expect:* ZERO damage from 1-block hops, however precisely timed. Also drop 3-4 blocks and jump on landing: only normal fall damage for that height, no spike.
- [ ] **Jump into a low ceiling hard.** *Expect:* ceiling damage still works (regression check on the jump fix).
- [ ] **Land on slime and bounce.** *Expect:* bounces unchanged (they're pre-contact, exempt from the jump rule).
- [ ] **Enter a boat gliding past you (retest #3).** *Expect:* it keeps gliding — the velocity is now seeded client-side and re-applied for 5 ticks.
- [ ] **Shift-dismount a moving boat (retest).** *Expect:* you fly out with the boat's velocity — the seed now outlives the server's dismount position packet.
- [ ] **Boat slime-wall ricochet while riding.** *Expect:* the boat snaps to the rebound heading and your view eases around SMOOTHLY to match (same gradual turn as the on-foot bounce camera) — no head snap. Moving the mouse mid-turn cancels the auto-turn, same as on foot.
- [ ] **Shift-dismount a moving boat (retest #3).** *Expect:* BOTH keep moving — you fly out with the boat's velocity AND the boat glides on without you. No "collision" between you and the boat right after exit (the boat must not dead-stop and slingshot you — the pair is collision-exempt for 2 s).
- [ ] **Sprint into a standing player/mob with the legacy entity collisions on.** *Expect:* sane momentum exchange — and a MOVING player being grazed no longer absorbs the full impact as if they were a wall (their measured velocity counts now).

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
- [ ] **Land on slime on a Valkyrien Skies ship, from low and from high (20+ blocks), on a ship at rest, one moving and one turning, many times.** *Expect:* a bounce every time, as on world slime, never a dead stop; sneaking lands without a bounce `[FIX]`.
- [ ] **Slime on a ship's underside and sides: jump or get flung up into it, run or get flung into it; roll a slime ship upside down or on its side and land on its slime; stand under a ship with a gravity core in it and fall up onto its slime underside.** *Expect:* each bounces the way the same face of world slime does (the underside sends you back down) `[FIX]`.
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
- [ ] **Fall into shallow water** (30 blocks up, into pools 1, 2 and 8 deep over stone). *Expect:* 1 deep hurts nearly as much as landing on the stone itself, 2 deep less, 8 deep nothing; the bottom blocks can crack like any landing. `[FIX]`
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

## 8. Mid-air collisions (`enableEntityCollisions`, `enableProjectileCollisions`: on by default)

- [ ] **Elytra into a flying mob (or a phantom swooping into you).** *Expect:* both are knocked apart along the line between them and each takes kinetic damage for the speed it lost; the server finds the hit, so the change can arrive 1-3 ticks late - check it doesn't look like a snap.
- [ ] **PvP off (`pvp=false`): two players colliding at speed.** *Expect:* neither is hurt; with pvp on, the damage is credited to the other player.
- [ ] **Shield up when a mob slams into you.** *Expect:* decide whether blocking should soak the kinetic hit (it is kinetic damage, not an attack).
- [ ] **Two players sprinting head-on (11.2 m/s closing).** *Expect:* they bounce apart with no damage (over the 10 m/s `entityCollisionMinSpeed`); raise it if that feels wrong.
- [ ] **Mob-farm drop chute.** *Expect:* mobs landing on mobs at the bottom crush them ("crushed by", no player kill credit) - check your farms still give XP.
- [ ] **Supplementaries projectiles, wither skulls or blaze fireballs crossing paths, or fired into arrows.** *Expect:* they meet in the air - fireballs and skulls explode there, thrown things break there, arrows are knocked aside. Bobbers and fireworks still pass through everything (`#fullstop:no_projectile_collision`).
- [ ] **Two sand blocks meeting in the air** (two dispensers facing each other across a drop, or `/summon falling_block` with Motion). *Expect:* they bounce apart and still land as blocks. An anvil dropped onto a standing pig is vanilla's (it hurts it on landing); dropped onto a flying/hovering one it strikes it mid-air ("was hit by a falling Anvil").
- [ ] **Shoot an arrow into an arrow, an egg into an egg.** *Expect:* arrows head-on stop and drop, glancing ones deflect; eggs burst where they meet (the pieces drawn there, not where the egg was a tick later).
- [ ] **Shoot down a thrown ender pearl.** *Expect:* its thrower is teleported to the meeting point and takes vanilla's 5 damage (vanilla does the same when a pearl hits a flying mob).
- [ ] **Horse or boat on ice ramming a mob.** *Expect:* a hard hit, the mob knocked away; the rider stays mounted.

### Legacy entity collisions (set `enableEntityCollisions = false` and `entityCollisionDamage = true` in server config first)

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
- [ ] **Above Y 200 in VS Genesis's space (the Great Unknown), on the Nether roof, in the End.** *Expect:* no air loss at all (`highAltitudeDimensions` is the overworld only); the overworld unchanged.
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
- [ ] **Spin nausea:** see §27.

## 12. NoAI & immunity coherence `[FIX]`

- [ ] **`/summon zombie` then `/data merge entity <it> {NoAI:1b}` while it falls from height.** *Expect:* within ~1 second of the flag flip it becomes fully inert — it must NEVER take BOTH kinetic and vanilla fall damage (double), and never neither when the flag is OFF.
- [ ] **NoAI mob pushed off a ledge.** *Expect:* no kinetic damage, and vanilla fall damage still applies (unphysable = vanilla behavior).
- [ ] **kinetic_immune-tagged mob (e.g. bosses) falling.** *Expect:* no kinetic fall damage AND no vanilla fall damage (fully exempt).

## 13. Death messages & misc `[REG]`

- [ ] **Die to a wall impact / fall / flop / entity crush.** *Expect:* correct message with velocity appended (deathMessageAppend=true).
- [ ] **Killed by a ship you renamed (`/vs rename <ship> "Large Wooden Door"`).** *Expect:* "was hit by Large Wooden Door" (or crushed by / didn't survive the crash of, and "Alex's Large Wooden Door" with a pilot); a ship still named like `delivery-doe-leg` says "a ship".
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
- [ ] **Ship rays:** see §28.
- [ ] **F3+V at 1/16 (VS Genesis's space), on foot and next to a small ship.** *Expect:* rays the size of the body and the ship, starting at the feet and on the hull - not full-size lines floating around them.

## 18. Ship weight (formerly WeightRS) `[VS]`

Needs Valkyrien Skies, `enableValkyrienSkiesCompat` and `enableShipWeight` (both on by default). Remove the WeightRS jar first.

- [ ] **Launch with an old `config/weightrs-common.toml` present (WeightRS removed).** *Expect:* log "WeightRS is part of FullStop now: … renamed to weightrs-common.toml.migrated"; old-layout files also get a `.pre-camafy` backup and their weights appear in `config/camafy-weight.toml`. Launch again: no second import.
- [ ] **Open a world for the first time after that.** *Expect:* log "Imported N WeightRS ship-weight setting(s) into …/serverconfig/fullstop-server.toml"; tuned WeightRS values (e.g. `gravity = 10`) show in the new "Ship Weight" section and `configVersion = 2`. Reopen: no second import. A value you already changed in FullStop's file is never overwritten.
- [ ] **Keep the old WeightRS jar installed.** *Expect:* one log warning at startup ("WeightRS is installed: Full Stop's ship weight stays off…"); `/fullstop ship info` says "weight off (WeightRS installed)"; the ship sinks only as much as with WeightRS alone (nothing weighs twice); `/weightrs` is WeightRS's own command.
- [ ] **Stand still on a small floating ship; bring 10 friends / spawn 20 cows on deck.** *Expect:* the deck sinks/tilts toward the load; walking to one edge tips it that way. Leaving the ship: it recovers.
- [ ] **Drop a stack of iron blocks (items) on the deck.** *Expect:* the ship dips on the landing and stays lower while they lie there (`enableItemEntityPhysics`).
- [ ] **Jump on the deck repeatedly.** *Expect:* a small dip on every jump (the jump's reaction), and a bump on every landing. On a small raft: it rocks, it never spins up (§26).
- [ ] **Fall 10+ blocks onto a deck.** *Expect:* a clear slam, ONE push per landing — not two for the two-tick impact. On a big ship, FullStop fall damage as on the ground; on a small light raft the raft gives way (less damage, the raft dips hard).
- [ ] **Ride a boat / minecart / horse off a ledge onto a deck; drop a mob onto it.** *Expect:* each landing pushes the ship (WeightRS only handled living fall events; FullStop measures every body).
- [ ] **`includePlayers = false`.** *Expect:* players weigh nothing and their jumps/landings push nothing; mobs still do.
- [ ] **Under a Gravity Unbound field, walk on a ship's wall/underside.** *Expect:* the standing weight pushes along the field's pull, not world-down.
- [ ] **`/fullstop weigh @e[type=cow,limit=1,sort=nearest]`, `/fullstop weigh held`, `/fullstop ship info`, `/weightrs weigh @s`, `/weightrs held`.** *Expect:* weights with body/carried/basis, the held stack's mass, the nearest ship's mass/speed/grace; the `/weightrs` aliases work (only while WeightRS is absent).
- [ ] **`enableShipWeight = false` (edit the world's server config while playing).** *Expect:* within a tick or two the deck stops carrying any load (standing forces lapse on their own).

## 19. Ship collisions — entities `[VS]`

Setup: a ship you can drive (Eureka / VS helm), `/effect give @s resistance` for yourself while testing on foot. `debugShipCollisionLog = true` prints every hit.

- [ ] **Crouch and stand up repeatedly inside a roofed room (walls, floor, ceiling) on a plated ship spinning at 60 RPM and moving 30 m/s, and on one cruising at 100 m/s, first and third person.** *Expect:* no damage at all; walking into the walls at walking speed doesn't hurt either. Then get hit by a rotor arm on foot: that still hurts.
- [ ] **Ram a zombie standing in the open at ~8 m/s.** *Expect:* it is shoved away along the hull (no damage below the 10.5 m/s horizontal threshold); the ship barely slows.
- [ ] **Ram a zombie at 20+ m/s.** *Expect:* it flies off, takes damage (≈ (speed − 10.5) × hull-block hardness factor × 1.07; oak-plank hull at 20 m/s ≈ 10), hull-block particles and a thud. Death message "was hit by a ship" (+ "(at X m/s)").
- [ ] **Same while seated at the helm.** *Expect:* death message "was hit by <you>'s ship"; the kill counts for you; a mob that survives targets you.
- [ ] **Pin a zombie between the hull and a stone wall, keep pushing.** *Expect:* no knockback, "was crushed by a ship", damage ×1.5, repeating about every half second while the hull presses.
- [ ] **Survival player (second account or `/gamemode survival`) in the ship's path.** *Expect:* pushed and damaged like a mob; on a `pvp=false` server with a pilot seated, pushed but NOT damaged (the pilot is the attacker).
- [ ] **Creative/spectator player, a mob tagged `fullstop:ship_immune`, a `fullstop:physics_blacklist` entity in the path.** *Expect:* never pushed, never damaged.
- [ ] **Stand / sit / walk / jump on the moving ship's own deck, ride a boat parked on the deck.** *Expect:* never hit by your own ship `[REG]`.
- [ ] **A light ship (a few blocks) rams an iron golem.** *Expect:* the ship visibly slows (reaction impulse); `reactionImpulses = false` → it doesn't.
- [ ] **Assemble a ship right next to (or around) a mob; paste a moving ship; load a chunk with a moving ship; `/vs teleport` a ship.** *Expect:* no hits during the grace (`newShipGraceTicks`, 40 ticks for new ships, 10 after a teleport).
- [ ] **Walk into a slowly drifting hull on foot.** *Expect:* no ship "hit" (the ship must supply most of the closing speed for that); you stop at it like a wall. Faster collisions of yours with a hull: §25.
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
- [ ] **Drop a stone cube onto grass from 2, 5 and 10 blocks.** *Expect:* grass AND stone sounds at the contact each time, louder and higher-pitched the higher the drop; from ~6 m/s a harder hit layer, from ~12 m/s a crunch; block-crack particles of both from 4 m/s.
- [ ] **A heavy ship vs a light ship landing at the same speed.** *Expect:* the heavy one a little louder and deeper.
- [ ] **A ship resting / sliding / grinding along terrain.** *Expect:* one sound when it touches down, then quiet (no machine-gun).
- [ ] **`breakTerrain = false` and `hullDamage = false`.** *Expect:* impacts still sound.
- [ ] **`impactSounds = false`.** *Expect:* silent. `impactSoundVolume = 2`: heard from twice as far. `impactParticles = false`: no particles.

## 21. Ship collision checks in dev `[VS]`

- [ ] **Automated: `gradlew runGameTestServer -PshipTests -PvsBackend=classic`, then `=physx` and `=jolt`** (headless; its own `run-gametest/` directory and a fresh flat world each run, started on the backend asked for, PhysX when none is given; needs Camafy 0.1.3, which `gradle.properties` asks for). *Expect:* "All N required tests passed" on each. The material tests check the toughness rule, the calibration and the sound mapping against VS's real block masses. The ship tests assemble real VS ships and crash them: a plank cube into a stone wall, a stone cube onto grass, obsidian onto obsidian, a 16x2x16 raft landing flat, a stone raft hopping five times (one burst of contacts per landing), a plank cube onto a static ship (ship-to-ship), and a plank cube through leaves fast and slow. They also check that a ship resting or sliding on the ground gets no fallback contact, and that Camafy used the source the backend calls for, only that one (on PhysX/Jolt the fallback never runs, so nothing is reported twice). Entities and hulls (`ShipBodyTests`, batch `fullstop_ship_bodies`): cows thrown at 20, 40 and 80 m/s at a static obsidian hull and at 40 m/s at a floating one, a fake player flying at 22 and 60 m/s into plank hulls (static and floating) and a NoAI cow moved at 80 m/s must never end inside or behind the hull; a golem into a light raft, a villager into a heavy ship vs the same wall in the world (same damage), a golem dropped 2 blocks on a small raft's edge and one standing off-centre on it (never over 3.5 / 1.5 rad/s, calm after 15 s, golem unhurt), a ship into a boat with a villager (one body, launch rule), a boat crashing into a wall (its rider shares it), spin nausea on scripted turning, the thrown-rider guard, what counts as a body. Entity hits: a floating plank ship rammed into a villager at 8, 36 (twice, once with `damageEntities = false`), 60 and 80 m/s, plus 120 and 200 m/s as optional tests - each launch must match the rule within 5% and the ship must never enter jump grace; the pin check (open ground vs a wall ahead, pushes from level to straight down); `syncLaunchToPlayer`. Deck momentum (batch `fullstop_deck_momentum`, ship weight off for it): a raft stopped dead from 23 m/s throws the cow on its bare deck forward off the bow while a cow on honey and one in a VS seat stay; a raft braking gently, one resting on the ground and one turning at 0.5 rad/s throw nobody. Every contact is logged as a `[FullStop ship test] … contact …` line. `-PcollisionFallback=shadow` also logs what the fallback would have reported next to VS's events; `-PhideBackend` makes Camafy treat the backend as unknown (fallback until VS's first event).
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
- [ ] **Run into a parked kart and a driven kart on foot (the new collisions, and again with the legacy `entityCollisionDamage` ones).** *Expect:* no FullStop momentum exchange or collision damage with the kart or its driver.
- [ ] **A mob run over by a kart, then flying into a wall.** *Expect:* Automobility's run-over hit, then FullStop's wall damage for the mob's flight (the mob is FullStop's).
- [ ] **A VS ship rams a kart with a driver, in a race.** *Expect:* neither pushed nor damaged by the ship. A kart parked on a ship deck weighs on it; a kart landing on a deck thumps it. Free roam: §29.
- [ ] **Ride a boat as a PASSENGER (second seat) while a player drives fast and turns.** *Expect:* no g-force blindness/nausea from the smooth ride (passengers replay the vehicle's motion now).

## 23. Modpack rules `[FIX]`

- [ ] **A player drives a boat into another player on a `pvp=false` server (the new collisions, and again with the legacy ones).** *Expect:* no damage. With pvp on: "was hit by <driver>'s Boat", kill credit to the driver; a mob hit that way targets the driver.
- [ ] **`mobGriefing = false`: launch a mob into glass at speed; crash into glass yourself; fire your grappling hook into ice.** *Expect:* the mob breaks nothing; you and your hook still do.
- [ ] **Crash into blocks inside a claim you don't own / a Game Start no-break arena.** *Expect:* nothing breaks or cracks (protection mods see a break event from you, or from "[Full Stop]" for mobs).
- [ ] **`/gamerule drowningDamage false`, then high altitude / deep water.** *Expect:* no atmosphere/pressure damage to players (both are `#minecraft:is_drowning` now).
- [ ] **Blackout at full g next to other HUD layers (Space Events telescope glare, MKW HUD).** *Expect:* the blackout darkens the whole screen, HUD and glare included.
- [ ] **Gravity Unbound, non-default gravity: shift-dismount a moving boat.** *Expect:* no sideways veer (FullStop's push veto runs before Gravity Unbound's push rewrite).
- [ ] **Gravity Unbound surface-cling release / gravity flip at speed.** *Expect:* no phantom impact damage (client-side discontinuities reach the server now).
- [ ] **Client and server must both run this build** (network protocol 5). *Expect:* an older FullStop on either side is refused at login.

---

## 24. Ship riders (deck momentum) `[VS]`

Setup: a ship you can drive at 20+ m/s (Eureka / VS helm, or `/vs` commands), something solid to crash it into, `/effect give @s resistance`. `debugShipCollisionLog = true` logs every throw ("ship N threw …: deck lost (…) m/s, rider keeps (…) m/s").

- [ ] **Stand on the bare deck (not seated) while someone crashes the ship into a cliff at 20+ m/s.** *Expect:* you keep going: thrown forward off the bow at about the ship's old speed, with a little lift, into the cliff (FullStop impact damage). At 20 m/s a thrown player or mob flies roughly 4-5 blocks (vanilla air drag takes 9% of it every tick), at 80 m/s much further. No rubber-band on your client.
- [ ] **Same, standing on a slime or honey block on the deck.** *Expect:* you stay where you stand.
- [ ] **Same, standing on Gravity Unbound gravity plating on the deck (floor, or plating on a wall you walk on), and again wearing Surface Cling boots.** *Expect:* you stay put in both cases.
- [ ] **Same, seated in a helm or seat.** *Expect:* you stay seated. With `deckMomentumSeated = true`: thrown out of the seat and forward.
- [ ] **Cruise, brake gently, turn gently, drift and rest on the ground with mobs and dropped items on deck.** *Expect:* nobody slides or is thrown. Brake harder than 1 g, or throw the ship into a hard turn (or lower `deckGrip`): riders slide the other way; a ship lurching forward hard leaves them sliding aft. Once the ship holds its turn steadily, riders stay put again.
- [ ] **Mobs, a boat with a passenger, and dropped items on the deck when the ship crashes.** *Expect:* all thrown forward (the boat with its passenger aboard); NoAI mobs and `fullstop:physics_blacklist` entities stay.
- [ ] **Creative-flying above the deck, an MKW kart parked on the deck, a grappling player swinging from the ship.** *Expect:* never thrown by the deck.
- [ ] **Jump on the deck and have the ship stop while you are in the air.** *Expect:* you keep flying forward at the ship's old speed (VS keeps carrying riders for a moment after they leave the deck; that carry is what stops).
- [ ] **`/vs teleport` a moving ship with riders.** *Expect:* nobody is thrown (the teleport puts the ship in its short grace).
- [ ] **`deckMomentum = false`.** *Expect:* the old behaviour: riders stop dead with the ship.

## 25. Solid hulls, and entities hitting ships `[VS]` `[FIX]`

Setup: a ship made of planks (or anything), floating or parked; a second one made of a few light blocks (purpur: 100 kg each) floating; `/effect give @s resistance`; `debugShipCollisionLog = true` logs each collision ("… hit ship N: approach … entity … kg vs ship … kg (effective), e …, lost … kick … ship pushed … N s").

- [ ] **Creative: fly fast (sprint-fly, 20+ m/s) straight into the side of a ship, several times, from different angles; also start pressed flat against the hull and keep flying into it.** *Expect:* you stop at the hull every time, never pass through, never end up inside it. Glancing: you slide along it. The same with the ship moving slowly toward or away from you.
- [ ] **Elytra dive (40+ m/s) into a hull, survival.** *Expect:* stopped at the hull, hurt as by a world wall of the same block at that speed (a big ship); FullStop's impact sound and particles.
- [ ] **Throw / launch mobs into a hull at 40+ m/s (a ship ram, a slime launcher).** *Expect:* they stop at it or - fast and heavy enough, against planks or glass - smash through the block they hit like a world wall (and slow down doing it); they never pass through intact blocks.
- [ ] **Walk off the edge of a ship's deck, sprint and jump around the deck, climb stairs and slabs on the deck at speed.** *Expect:* all as before (the fix only changes how long moves near ships are resolved) `[REG]`.
- [ ] **An iron golem (or anything heavy) running or thrown into the light raft.** *Expect:* the raft is shoved away hard, the golem barely slows and isn't hurt. A player into it: the raft moves a little, the player stops.
- [ ] **A survival player sprinting / flying into a huge ship.** *Expect:* hurt as by a wall at that speed, rebounds a little (`entityRestitution`); the ship doesn't noticeably move.
- [ ] **Land from a height on a small floating raft vs on a big ship.** *Expect:* the raft dips hard and you take less than a ground landing; the big ship barely moves and you take ground-landing damage. You never bounce on a deck.
- [ ] **Run into a wall of the ship you stand on.** *Expect:* stopped like a wall; the ship isn't pushed.
- [ ] **`solidHulls = false`.** *Expect:* the old Valkyrien Skies behaviour: fast entities pass through hulls. `entityImpacts = false`: a hull is an immovable wall for entities (only landings push, through Ship Weight).

## 26. Heavy riders on small ships `[VS]` `[FIX]`

- [ ] **A player with a full inventory of heavy blocks (logs, stone - several stacks) standing still off-centre on a small floating ship (a few blocks, in water).** *Expect:* the ship lists toward you and settles at an angle (or, far enough out, capsizes slowly and you slide off) - it never spins up, never flings you, never kills you.
- [ ] **Same, walking from edge to edge and jumping.** *Expect:* the ship rocks with you; the rocking dies down when you stop. No growing oscillation.
- [ ] **Same, dropping onto the ship's edge from 4 blocks up.** *Expect:* a hard dip on that side, then it settles.
- [ ] **A ship that stops dead while you stand on it (deck momentum throws you), and you land back on its deck.** *Expect:* the landing doesn't kick the ship (`debugShipCollisionLog`: "(thrown by it: no push)").

## 27. Spin nausea `[FIX]`

- [ ] **Turn around quickly (a 180), look around hard in a fight, flick the mouse for a minute.** *Expect:* never dizzy.
- [ ] **Spin the view round and round fast (about 1.7 turns a second).** *Expect:* dizzy after about 4 seconds; at one turn a second, after about 10. Stop: the nausea lifts within a few seconds (the effect itself runs out after 6 s).
- [ ] **Sprint in tight circles.** *Expect:* dizzy only when it is fast and sustained.
- [ ] **`spinNauseaTurns = 0`.** *Expect:* dizzy as soon as the one-second average passes `spinNauseaThreshold` (15). `spinNauseaThreshold = 0`: never.
- [ ] **Clarity / Vertigo.** *Expect:* Vertigo makes it come sooner, Clarity later.

## 28. Ship velocity debug rays `[VS]`

- [ ] **F3+V, then drive a ship.** *Expect:* rays from its corners, face centres and centre of mass (the centre ray visible through the hull) pointing along the ship's motion, one tick of travel long, fainter when slow; a parked ship shows none.
- [ ] **Turn the ship on the spot.** *Expect:* the corner rays point sideways, opposite ways at opposite corners (the spin).
- [ ] **Drive it at a wall or another ship.** *Expect:* the rays about to hit turn red (yellow when merely touching at low speed), the others stay green.
- [ ] **On a dedicated server (a second client).** *Expect:* the same (the client's own ship data).
- [ ] **Freeze a moving ship (make it static, or `/vs` freeze it) while its rays show.** *Expect:* its rays disappear within a few ticks and don't come back until it moves again.

## 29. Vehicles and karts in free roam `[VS]` `[FIX]`

Needs MKW Mobility's free-roam claims (karts: G_FORCE, BOUNCE, CONTACT, BLOCKS, MOTION, ENVIRONMENT; riders: G_FORCE, BOUNCE, CONTACT, BLOCKS) and Camafy 0.1.5 (its `Impulses`); FullStop's `gradle.properties` still asks for Camafy 0.1.3 (see the report) - build against 0.1.5 to test this. A vanilla boat with a rider is the generic case.

- [ ] **A VS ship at 15+ m/s rams a kart you drive (free roam).** *Expect:* the kart is launched along the hull (MKW's own speed takes it: it keeps moving, isn't reset next tick) and you are hurt (your share of the kart's hit). In a race: nothing.
- [ ] **A ship rams an empty kart and one driven by an NPC.** *Expect:* launched on the server the same way.
- [ ] **Drive a kart into a stone wall at full speed (free roam).** *Expect:* you, the driver, are hurt (half the kart's crash), once - not twice.
- [ ] **Drive off a 10-block cliff (free roam).** *Expect:* the landing hurts you once.
- [ ] **A ship stops dead with a kart parked on its deck.** *Expect:* the kart is thrown forward (deck momentum, through MKW's speed).
- [ ] **A boat with a passenger crashing into a wall hard enough to break it.** *Expect:* the passenger is hurt too.
- [ ] **Drive a kart into a ship's hull fast (free roam).** *Expect:* stopped at the hull (never through it), the ship pushed by the two-body rule.

## 30. Spinning ships (Clockwork rotors) `[VS]` `[FIX]`

Setup: Clockwork (or any way to spin a ship steadily: a creative motor on a bearing), a sub-ship with a flat top and a long arm, `/effect give @s resistance`, `debugShipCollisionLog = true`.

- [ ] **Stand and walk on top of the spinning ship at 24 RPM, then at 64, 128 and 256.** *Expect:* you stay where you stand and walk normally, carried round with it, no sliding, stuttering or velocity packets fighting you - as without FullStop. Mobs and dropped items on it stay too. The log shows no "threw" lines while the speed is steady.
- [ ] **Change the motor's speed abruptly (0 -> 256, or 256 -> 0) while standing on it.** *Expect:* you slide (or are flung off) with what the deck gained or lost beyond your grip - that is a jolt. At a steady speed again, you stay put.
- [ ] **Walk up to the spinning arm at 24 RPM and let it hit you (and a mob).** *Expect:* the arm knocks you away and off your feet (a hop), flinging you clear of the circle it sweeps - never pushes you round the ground with it. The log shows one "hit" per sweep, maybe a "pushes on" if it catches you again.
- [ ] **Set it to 256 RPM and walk toward it from 20 blocks out.** *Expect:* nothing happens until you step into the circle its tips sweep; then you are hit (hard). Standing 1-3 blocks outside the circle: nothing, however long.
- [ ] **Stand inside the circle at 256 RPM with a shield / in creative.** *Expect:* creative: never hit. Survival: struck by each passing arm after the cooldown (half a second), flung out.

## 31. Ships breaking ships `[VS]` `[FIX]`

Setup: a ship of obsidian (or stone) blocks you can drive or push at 15+ m/s; a floating wooden raft or boat-ship; a second wooden ship.

- [ ] **Ram the obsidian ship into the wooden raft at 15-25 m/s.** *Expect:* planks break where they meet the obsidian (they drop), no obsidian breaks; both ships carry on slowed (the raft shoved ahead); crash sounds of both blocks. Faster = more planks.
- [ ] **Bump two wooden ships together, slow and fast.** *Expect:* they bounce; nothing breaks (equal blocks never break each other).
- [ ] **A glass porthole on one ship hits the other ship's stone.** *Expect:* the glass breaks.
- [ ] **Ram a parked (static) wooden ship with the obsidian one.** *Expect:* more planks break than against a floating raft at the same speed (a static ship takes the whole blow).
- [ ] **`shipVsShipBreaking = FRAGILE`**: only glass-like blocks break; **`NONE`**: nothing breaks, the ships only sound.
- [ ] **KRUNCH_CLASSIC backend (the VS default).** *Expect:* the same (Camafy's fallback reports ship-against-ship crashes).

## 32. Players and fast ships (round 3) `[VS]` `[FIX]`

Setup: Clockwork with a creative motor on a phys bearing, a sub-ship with a flat top and a long arm at body height; the VS backend you play on (KRUNCH_CLASSIC is the default). Diagnostics: open `saves/<world>/serverconfig/fullstop-server.toml`, set `debugShipContactLog = true` under `[Ship Collisions]` (it applies at once), reproduce, set it back to `false`, and send `logs/latest.log` (in single player it holds both the client's and the server's lines, all starting with `[FullStop]`). The raycast debug view helps too: ship blocks show magenta (a real collision), lavender (touching) or blue (only in the ray's way - never an impact).

- [ ] **Stand still on the rotor at 24, 64, 128 and 256 RPM for a minute each; then walk around on it.** *Expect:* you stay where you stand, never slide off, no stutter. With the log on: no "threw" lines at a steady speed (a "jolted ... for one frame and came back ... no throw" line is the filter at work); the "client: ship carry" lines show your client's carry next to the server's own reading (they differ at speed - that gap is why the server now uses your client's).
- [ ] **Stand in the arm's way at 64 RPM (and 24, 128).** *Expect:* the first touch knocks you clear of the arm at once (off your feet, away from the circle it sweeps), one collision sound, hurt once - never dragged along with repeated sounds. Log: "client: ship N hits you ... applied now" then "ship N hit <you> (reported by its client) ...". A second touch within half a second only pushes ("pushes on", no damage).
- [ ] **At 256 RPM, walk toward the rotor from 20 blocks out, and stand 1-3 blocks outside the circle its tips sweep.** *Expect:* nothing at all until you step into the circle - no damage, no fling, no sounds. The raycast view may show BLUE rays reaching the arm while you run at it: those are never impacts.
- [ ] **A mob next to the rotor at 256 RPM, 2 blocks outside its circle.** *Expect:* left alone; inside the circle it is struck and flung.
- [ ] **Stop the motor abruptly while standing on the arm.** *Expect:* thrown along the way the arm was moving (a real jolt still throws).
- [ ] **Jump off the spinning rotor.** *Expect:* VS carries you with it for about a second (vanilla VS); FullStop's ship code leaves you alone meanwhile. (If VS's own carry then flings you as it lets go, that is VS, not FullStop.)
- [ ] **Multiplayer (a second player on a dedicated or LAN server):** the same for the other player, seen from both clients.

## 33. Seated riders feel the ship (round 3) `[VS]`

- [ ] **Sit in a VS seat (or a Create / Clockwork seat) on the rotor, 3 blocks from the axis, at 24, 64, 128 RPM.** *Expect:* 24: nothing; 64: the vignette creeps in and the sound muffles a little; 128: blackout and nausea (the seat's centripetal pull). The effects fade within a couple of seconds after stopping the motor.
- [ ] **Sit in a helm and fly a ship through a hard turn, a hard stop (crash) and a fast spin-up.** *Expect:* g-force effects in proportion; cruising and gentle turns give none; no flicker while flying straight.
- [ ] **The same seated on the ship's centre of rotation.** *Expect:* much less than at the rim.
- [ ] **Stand (not seated) on the same rotor.** *Expect:* unchanged from before this build (no g-force from the spin while standing).
- [ ] **An MKW kart parked on a ship's deck, in a race.** *Expect:* no g-force effects from the ship (MKW owns them).

## 34. Zero gravity: VS Genesis space, Ad Astra orbit, a Gravity Unbound zero plate (round 4) `[VS]` `[FIX]`

Needs Camafy 0.1.7. Nothing here may change under normal gravity: if in doubt, repeat a step in the overworld.

- [ ] **Genesis space (`genesis:great_unknown`), an Ad Astra orbit (Earth orbit station), and a Gravity Unbound plate set to strength 0.** In each: drift feet first into a floor at walking pace, then fast (fly, push yourself, or a piston). *Expect:* the slow bump does nothing; the fast one is a wall hit ("slammed into a wall/the ground" messages are fine, but never fall damage: no Feather Falling help, no sprain, no slowness afterwards, armour does count). Compare the same speed into a wall: about the same damage.
- [ ] **Stand (float) on a ship's deck in space while it cruises, then stop it dead (crash it or stop the engine hard).** *Expect:* if the ship still carries you, the stop throws you forward at its old speed and LEVEL - never upward, never drifting up off into space afterwards. On slime or honey (or GU plating) you stay put. Seated: no change.
- [ ] **A heavy mob or a player with a full inventory floating on a small ship in space.** *Expect:* the ship does not sink or tip under it (no weight in zero gravity).
- [ ] **Let a moving ship's hull rise into a floating mob or player from below.** *Expect:* it is hit and knocked away like from any side (a small bounce), not scooped up as a "landing"; no lift beyond the hull's own push.
- [ ] **Fly around in space (jetpack, elytra, a ship seat) with hard turns.** *Expect:* g-force effects from your acceleration alone; floating still, none. Seated on a ship in space: the same.
- [ ] **Break the block under a slime or honey block in space; knock a dripstone spike sideways.** *Expect:* the slime/honey stays floating in place; the spike breaks off as an item instead of falling.
- [ ] **A loaded ride stack (cow on cow) in water in an orbit/space.** *Expect:* no forced sinking, no crush damage.

## 35. Low gravity: Ad Astra's Moon (and other planets) (round 4) `[FIX]`

- [ ] **Jump off heights on the Moon (5, 12, 30 blocks).** *Expect:* landings far gentler than on Earth (a 12-block drop is harmless); a fast one (elytra dive) still hurts like its speed says. Feather Falling and sprains work as usual.
- [ ] **Stand on a ship on the Moon; crash it.** *Expect:* the ship carries a sixth of your weight; a stop throws you with little lift (a sixth); braking gently still carries you.
- [ ] **Get hit sideways by a ship on the Moon.** *Expect:* knocked away with a small hop, not a big lift.
- [ ] **G-force on the Moon standing still / flying.** *Expect:* nothing at rest; effects from hard manoeuvres as usual.

## 36. Slime and honey on ships (round 4) `[VS]` `[FIX]`

- [ ] **Build a ship with slime and honey hanging under its hull, a slime block on its deck and a slime-only cluster attached to it; assemble it; fly it around; break the hull blocks next to the slime and honey (by hand, by ramming it into terrain, by ramming another ship into it).** *Expect:* no slime or honey block ever turns into a falling block or drops off the ship (it stays in place in the hull, even hanging from nothing); no falling blocks appear anywhere in the world.
- [ ] **The same slime under a block in the world, the block broken.** *Expect:* it still falls (unchanged); sand and gravel on ships behave as Valkyrien Skies makes them (unchanged).
- [ ] **Turn `enableValkyrienSkiesCompat` off and repeat the first test.** *Expect:* still no slime or honey falls off the ship.

## 37. Slime bounces and honey glue on ships `[VS]`

Needs Camafy 0.1.10. Test on the backend you play on (`physicsBackend` in `<world>/serverconfig/valkyrienskies/vs-core-server.toml`); PhysX and KRUNCH_CLASSIC are the two the automated tests cover best. Optional: `debugShipCollisionLog = true` logs each bounce ("slime against terrain met at X m/s ... wants Y") and each glue ("glued to the ground", "the honey glue ... tore: ...").

- [ ] **A small raft with a slime bottom, dropped from about 6 blocks onto stone.** *Expect:* it springs back up clearly (well over half the drop), then again lower, and comes to rest after several bounces; it doesn't stay bouncing to the same height.
- [ ] **A plank raft dropped onto a slime floor, and flown into a slime wall at 5-10 m/s.** *Expect:* the same bounce off the floor; off the wall it comes back at nearly the speed it hit with.
- [ ] **A slime-fronted ship into a slime wall, and two slime ships into each other.** *Expect:* the bounciest hits; two ships fly apart equally (the lighter one faster).
- [ ] **A plank raft onto stone (control).** *Expect:* nothing new: whatever small bounce VS gives, no glue.
- [ ] **Stand on a ship that bounces off slime.** *Expect:* a hard bounce throws you like any hard stop (deck momentum), unless you stand on slime or honey.
- [ ] **A raft with honey on its bottom, dropped onto stone.** *Expect:* no bounce, it stays where it landed; a softer crash (breaks less than the same raft of planks would).
- [ ] **Push the glued raft gently (walk into it, a mob bumps it, a weak engine).** *Expect:* it doesn't move.
- [ ] **Push it hard (a strong engine, ram it with another ship).** *Expect:* it tears free and moves off; it doesn't re-stick while it scrapes along the honey, only if it lands on honey again.
- [ ] **Break the honey block (or the block under it) of a glued raft.** *Expect:* the glue lets go at once (a light push moves it).
- [ ] **Two ships with honey on the faces that meet, driven gently together (1-3 m/s).** *Expect:* they stick; flying one drags the other along; a hard yank (full engine the other way, a ram) pulls them apart.
- [ ] **A ship glued to the ground: walk far away (over 200 blocks) and back, or save and quit and reload.** *Expect:* it is still glued when you come back (a gentle push doesn't move it).
- [ ] **Disassemble a glued ship.** *Expect:* no error, nothing left behind.
- [ ] **Zero gravity (VS Genesis space / Ad Astra orbit):** a slime ship bounces off a slime wall the same way; honey ships still glue.
- [ ] **Turn `slimeBounce` or `honeyGlue` off.** *Expect:* ships behave as before this change (VS's own bounce; no glue, existing glue lets go).

## 38. Spinning rotors, jumps and hull hits (round 5) `[VS]` `[FIX]`

Needs Camafy 0.1.12. A Clockwork phys bearing driven by a creative motor (or your own engine) is the setup to use: its rotor is what used to slide riders off. Optional: `debugShipContactLog = true` (the client lines "about to strike you", "you left ship N's deck"; the server lines "no physics frame for N s while moving; deck jolts ignored", and with `debugShipCollisionLog` "VS reported ... for a frame in which it moved on as before").

- [ ] **Stand still on a 9x9 rotor deck spinning at 32 and at 64 RPM, a few blocks from the middle, for half a minute.** *Expect:* you stay exactly where you stand; no sliding, no throws. (It used to throw you every time the game lagged, and whenever VS reported the rotor as not turning for a frame.)
- [ ] **Stand on the rotor while it's still, then start the motor.** *Expect:* one throw as it starts (the bearing snaps the rotor to full speed in one tick: at 64 RPM you slide a couple of blocks), then you stand still with it.
- [ ] **Step or jump onto the rotor while it spins.** *Expect:* you slide a little as the deck catches you up (about 1.5 blocks at 32 RPM, 3 blocks out), then stand still.
- [ ] **Walk around on that rotor.** *Expect:* ordinary walking, no drifting when you stop.
- [ ] **Jump on the spinning rotor.** *Expect:* you fly straight on from where you jumped (along the edge's motion) and the deck turns under you, so you land a little further round than you started; at 64 RPM near the edge you may fly off. You are not swung round with the deck in the air.
- [ ] **Jump on a ship cruising straight at a steady speed.** *Expect:* you land where you jumped, as before (the deck's speed isn't lost to air drag).
- [ ] **Jump just as a ship stops or crashes.** *Expect:* you fly on forward over the deck.
- [ ] **Stand on gravity plating on the rotor and jump.** *Expect:* unchanged from before: Gravity Unbound keeps you with the ship, no throws.
- [ ] **A 1-block-thick arm on a phys bearing at 64 and 128 RPM sweeping through where you stand.** *Expect:* the arm hits you on its first pass, never goes through you, and flings you straight out along its swing (a straight path, not a curve round the hub); you are hurt. Try it several times: you must never drop straight down after a hit (the hit's damage used to cancel the fling now and then).
- [ ] **The same arm hitting a cow or zombie.** *Expect:* flung straight out, not carried round with the arm.
- [ ] **Sit in a seat (VS, Create or Clockwork) on a small floating raft.** *Expect:* the raft sinks a little more than without you, as if you stood on it.
- [ ] **Turn `releaseLeavingRiders` off (Ship Riders).** *Expect:* jumps behave as before (swung round with a rotor while in the air).

## 39. Rotors clashing, slime hulls, the supersonic roar (round 6) `[VS]` `[FIX]`

Optional: `debugShipCollisionLog = true` (the lines "ships A and B crossed: N blocks ... met ... closing at up to V m/s -> K broken" and "ships A and B (crossed, ...): ... both give").

- [ ] **Your two 3x3x16 oak rotors on phys bearings at 256 RPM each, spinning opposite ways, tips overlapping by a few blocks.** *Expect:* on every pass the blocks that visibly cross break, on BOTH rotors (planks against planks: both give), with both hulls' sounds and particles; the rotors keep spinning and plough on; within a second or two their tips no longer reach each other and nothing more breaks. (They used to pass through each other with a sound now and then and a plank or two lost.) The count: each rotor loses the blocks that cross (a few dozen), never blocks the other's sweep can't reach.
- [ ] **One rotor at 256 RPM sweeping into a still plank arm.** *Expect:* the arm loses what the tips reach (its nearest columns), the rotor its outermost blocks there; nothing further along the arm.
- [ ] **An obsidian ship rammed into a wooden raft at 20 m/s (as before).** *Expect:* planks break, obsidian never; two plank rafts bumping at walking pace break nothing.
- [ ] **A ship whose hull is slime hits you (or a cow) at speed.** *Expect:* you are flung away, springier than by a plank hull (0.9 of the closing speed back instead of 0.3), and take NO damage; the slime's sound plays. A slime deck rising under you bounces you up. A plank ship hurts as before.
- [ ] **Slime on the underside or side of a ship: flip a raft with slime on it and fall onto the slime; run into slime on a ship's side.** *Expect:* you bounce off it as off slime on top of the ship (sound, particles and the bounce). Before, only slime on the ship's top bounced you.
- [ ] **A long rotor whose tips pass 343 m/s (a 16-block arm on a bearing at 256 RPM; at 128 RPM a 32-block one).** *Expect:* ONE boom as the tips go supersonic (heard when the shock reaches you, from where it came), then a continuous harsh roar from the rotor for as long as the tips stay supersonic - deeper for a single arm (4.3 pulses a second at 256 RPM), higher for two arms, following the rotor as it moves - and no more booms, however long it spins; stop the bearing and the roar fades within a second (a boom again only after a second below 90% of the barrier). Nothing of this in VS Genesis's space. Standing on the rotor's hub you hear the roar too.
- [ ] **Two such rotors clashing (the first item, with longer arms).** *Expect:* the roar goes on through the clash; a rotor slowed for a moment by what it hit doesn't boom again unless it stays below the barrier for a second.
- [ ] **Turn `shipClashSweep` off.** *Expect:* fast rotors pass through each other as before (VS's own contacts only); slime hulls and the roar are unaffected.

## 40. Craters, projectiles in the debug view, buttons (round 7) `[FIX]`

Optional: `debugImpactLog = true` (the line "<name> crashes at V m/s into the UP face: crater of N blocks in L layer(s) under an F-block footprint").

- [ ] **A giant dropped from 30 blocks (`/summon giant ~ ~30 ~`) onto dirt or grass.** *Expect:* on landing the whole area under its feet opens (a 4x4 or 5x5 hole one deep, a smaller bowl below it), not five scattered blocks; the block the energy ran out at shows a crack. Drop it from 5 blocks: nothing, or the centre block or two.
- [ ] **A pig or a cow dropped from the same height.** *Expect:* only the blocks its rays hit, as before (it is narrower than a block).
- [ ] **A giant into a wall at speed (a Physgun throw, or a ship carrying it into a cliff).** *Expect:* the wall opens across the giant's height and width, a bowl behind.
- [ ] **`impactCraters = false`.** *Expect:* the giant breaks its five ray blocks only, as before.
- [ ] **F3+V while shooting arrows, throwing snowballs and eggs, and near a blaze or a ghast.** *Expect:* every projectile in flight carries a line as thick as its body along the path it moved this tick and a thinner line ahead of it - cyan on arrows and tridents, orange on snowballs, eggs, potions and fireballs - and a grey one on an arrow stuck in a block or a fishing bobber. Nothing on projectiles with `enableProjectileCollisions = false` except grey.
- [ ] **Shoot arrows and throw snowballs at a stone button and at an oak button; run into a stone button; fling a mob into one.** *Expect:* the stone button never answers a projectile; the oak one does; you and the mob press the stone one.

## 40. The roar you can hear, leaves in a rotor's sweep, ships on buttons and plates (round 7) `[VS]` `[FIX]`

Optional: `debugShipCollisionLog = true` (the lines "shock train ON/OFF", "no shock train: fastest point ... m/s of 343", "swept N block(s) along its blocks' arcs", "pressed the button at", "rests on the pressure plate at").

- [ ] **A rotor whose tips really pass 343 m/s.** A tip's speed is the spin in rad/s (RPM x 0.105) times its distance from the axis: at 256 RPM (26.8 rad/s) a tip must be 13 blocks out or more - a 3x3x16 arm on a bearing at one END, or a 27-block arm about its middle. *Expect:* ONE boom as the tips go supersonic, then a harsh buzzing roar that follows the RPM, loud on the ship and audible up to 192 blocks off (half as loud at 96), fading over half a second once the rotor stays under 90% of the barrier for a second. A 3x3x16 arm about its MIDDLE at 256 RPM (tips at 214 m/s) makes neither, and the debug log says why every three seconds.
- [ ] **Your spinning arm, with leaves placed in its sweep** (and natural leaves, a tree in its way). *Expect:* every leaf the arm passes through is gone within a turn or two, with drops; leaves outside the circle stay; the rotor keeps its speed. A raft drifting into a hedge under 8 m/s leaves the hedge; at 8 m/s or more it clears it.
- [ ] **A ship pushed against a wall with a stone button on it** (a wooden one too). *Expect:* pressed (the click) as the hull reaches it, still pressed while the hull leans on it (well past the 20 ticks a button holds for), let go a tick after the hull leaves.
- [ ] **A ship set down on a stone or wooden pressure plate.** *Expect:* pressed while it rests there, released once lifted. **A gold plate:** a four-plank raft (2 t) gives 2 (one level per tonne), an obsidian cube 15; **iron:** ten times the mass per level.
- [ ] **`shipRedstone` off.** *Expect:* ships press nothing; what they held is let go.

## 41. Advancements and the button click (round 8) `[FIX]`

- [ ] **The click:** shoot an arrow into a wooden button, or crash a mob into a stone one. The button's press click plays at once (before, only the release clicked twenty ticks later); a sculk sensor nearby notices the press.
- [ ] **Advancements tab** (the elytra icon): take a hard landing (*Ouch, that hurts!*); land from 50+ m/s and live (*Terminal velocity*); belly flop into water from high (*Belly flop*); drop onto slime from 20+ blocks (*Bouncy*); land hard enough to dig a crater (*Crater maker*); get hit by a ship and live (*Hit by a ship*); get killed by one (*Flattened*, hidden until then); hear a sonic boom (*Boom*); stand ten seconds within 32 blocks of a supersonic rotor's roar (*Thunderscreech*); shoot two arrows into each other (*Mid-air collision*).
- [ ] Each toasts once and shows in the advancements screen under FullStop; none fires for a creative player who can't be hurt (the damage ones need damage to land).

## 42. Cracks instead of a roar, tiny ships, pistons (round 8) `[VS]` `[FIX]`

Optional: `debugShipCollisionLog = true` ("shock train ON/OFF", "piston: ship N ... one block along ...", "piston: ship N is too heavy for K piston(s)").

- [ ] **A rotor whose tips pass 343 m/s** (at 256 RPM a tip 13+ blocks out: a 16-block arm on a bearing at its end). *Expect:* one boom as the tips go supersonic, then a train of separate cracks at exactly the blade-passing rate (two tips at 256 RPM: 8.5 a second), closing up as the rotor speeds up and spreading out as it slows, with no loop and no jump in pitch; past 15 cracks a second one every tick, rising in pitch. Slow the bearing down: the cracks spread out and stop once the tips stay under 90% of 343 for a second. On the ship it is loud; 96 blocks away half as loud.
- [ ] **`/vs scale <ship> 0.25` on a one-block ship, then drop it on grass, roll it, push it against walls and onto slime.** *Expect:* Full Stop no longer launches it (its pushes were 64 times too strong at 0.25): it lands and its slime bounce is about 0.9 of its landing speed. Valkyrien Skies' own physics may still throw a ship scaled this far down (seen in the tests with Full Stop's responses off: a rolling quarter-scale block sped up by itself and sank through the floor); if it does, try the same with `/fullstop` ship collisions off to tell whose it is. (Needs Camafy 0.1.18.)
- [ ] **A piston in the world with its head against a raft's side, powered.** *Expect:* the raft moves one block the way the head goes, with the head, and stops with it (not slowly oozing out of the head afterwards). Blocks in front of the piston pushing on the raft: the same.
- [ ] **A sticky piston, powered then unpowered.** *Expect:* the raft comes back one block as the head clears (a moment after it starts retracting). Against a ship too heavy to pull, the piston still retracts and leaves the ship.
- [ ] **A piston on a ship, facing a wall, powered.** *Expect:* the ship is shoved a block away from the wall as the head extends (a sticky one pulls it back a block as the head retracts). Facing another ship: the other ship is pushed a block.
- [ ] **A heavy ship (more than `pistonPushMassKg` per piston, 6 t: a 5x5 plank deck is 12.5 t; a 3x3 plank raft, 4.5 t, moves with one).** *Expect:* one or two pistons don't extend at all against the deck (the debug line says how many it needs); three move it. A pinned (`/vs set-static`) ship: no piston extends into it.
- [ ] **A mob standing on a raft that stops dead against a wall.** *Expect:* the mob is thrown forward off the deck (it stayed put before: a counter that never said "long ago").
- [ ] **An obsidian (or any heavy) ship rammed into a floating plank raft at about 20 m/s, once on a quiet world and once while the server is busy** (another big contraption running, or spark showing ticks over 50 ms). *Expect:* planks break at the contact both times (on a busy server the crash used to be judged at the speed of the frames the game happened to see, under 5 m/s, and nothing broke). Needs Camafy 0.1.18.
- [ ] **`pistonsPushShips` off.** *Expect:* pistons extend into hulls and ships ooze out of them, as VS does by itself.

## 43. Joined ships, pistons that push without breaking (round 9) `[VS]` `[FIX]`

Optional: `debugShipCollisionLog = true` ("piston: ship N ... 1.00 block(s) along ...", "pushed off ship N by ...").

- [ ] **An obsidian ship with a Clockwork phys bearing inside it, its rotor's tips close to the walls; spin the rotor, then fly the main ship around.** *Expect:* neither ship loses a block, however the two knock together (before: the rotor's planks, then more, broke as crashes).
- [ ] **Two ships glued with honey, or welded with VMod, knocked into each other.** *Expect:* nothing breaks between them; they still crash into anything else as usual.
- [ ] **A piston on a ship, facing a wall, powered (stone, dirt, glass).** *Expect:* the ship is shoved a block off the wall as the head extends; the wall, the piston and the ship stay whole.
- [ ] **A sticky piston pulling a raft back, watched closely (F5, or slow it with `/tick` if a mod gives you one).** *Expect:* the raft comes back with the head, not a few frames after it, and stops exactly where it started. On VS's default KRUNCH_CLASSIC backend the pull comes back the whole block too, ending just short of the piston base; known gap: it starts about a tick (60 ms) after the head, while VS's world still has the retracted head.
- [ ] **A ship's piston extending into another ship of about the same size.** *Expect:* both move apart, about half a block each; a small raft against a big ship: the raft goes nearly the whole block.
- [ ] **A raft pushed by a piston into a second raft, or into you.** *Expect:* nothing breaks; you are pushed, not hit (no damage).
- [ ] **The wall push again while the server lags (a heavy build, a chunk-loading flight, or a pack with many mods).** *Expect:* the same one-block shove; the raft never jumps over the wall, up into the air or sideways (before: under lag the head turned solid inside the wall and flung the raft).

## Known cosmetic gaps (not bugs — no need to test)

- Clarity/Vertigo effect icons are still missing textures (art needed at
  `assets/fullstop/textures/mob_effect/clarity.png` / `vertigo.png`) — they'll show as purple/black squares.
- Debug F3 strings are English-only via fallback lang.

## When something fails

Note: **section number, what you did, what happened vs. the Expect line**, and if damage-related,
roughly your speed (elytra? sprint?) and the block/mob involved. That's enough for me to trace it.

## 44. Pistons that set a ship down, slime launchers, quieter cracks (round 10) `[VS]` `[FIX]`

Optional: `debugShipCollisionLog = true` ("piston: ship N ... pulling toward the terrain", "springing off the terrain", "launched off slime").

- [ ] **A ship of one sticky piston (or a small raft with one in the middle) placed upside down on the ground, a button on it, pressed.** *Expect:* the ship rises a block, standing on its head; when the button lets go the head pulls it back down onto the ground where it was - never into or through the ground. Try it on a raft that's slow to settle too.
- [ ] **A slime block under a ship's upside-down piston, resting on the ground, then power the piston.** *Expect:* the ship is flung up several blocks off the slime (not a one-block push), the slime never enters the ground, nothing breaks.
- [ ] **A world piston with a slime block in front of it, against a raft's side.** *Expect:* the raft is launched and slides several blocks, not one.
- [ ] **A rotor whose tips pass the speed of sound.** *Expect:* the cracks have no click at their start or end, and are a little quieter than before; `shockTrainVolume` (client config) scales them, 1 = the old level.

## 45. Landing on oobleck (round 10) `[FIX]`

Needs Flow Fun. Optional: F3+V shows a red ray on the oobleck's surface as you fall onto it.

- [ ] **Drop from 20 blocks onto a pool of oobleck (survival).** *Expect:* you stop dead on its surface like on stone, take about the damage stone would give (a mud thud, the impact particles), then slowly sink once still.
- [ ] **Walk into the pool, or stand still on its edge.** *Expect:* you sink, unhurt (walking is under oobleck's 5 m/s).
- [ ] **Sprint across it.** *Expect:* you run across the top (Flow Fun), and Full Stop counts no impact.
- [ ] **Drop a mob from high up onto it.** *Expect:* it stops on the surface and is hurt as on stone; the oobleck itself is never cracked or removed by the impact.

## 46. Soft blocks, spectators, punching ships, turning plates, teleports (round 11) `[VS]` `[FIX]` `[NEW]`

- [ ] **A wool arm on a bearing at 256 RPM (Create) swinging into an iron golem, then an iron-block arm.** *Expect:* the wool arm shoves the golem hard (nearly as far as the iron one) but takes about 4 health off it, never kills it; the iron arm still does heavy damage. At 64 RPM the wool arm doesn't hurt at all. `softStrikeDamage` (Ship Collisions, 0.1) is the soft block's share; 1 = as hard as before.
- [ ] **A small wool ship rammed into a mob at speed, then a stone one.** *Expect:* the wool ship pushes the mob away almost as fast as the stone one but barely hurts it.
- [ ] **In spectator mode, 20-30 blocks from a 14-block arm spinning at 256 RPM (or a supersonic ship).** *Expect:* you hear the boom when it goes supersonic and the shock train's cracks while it spins, as a survival player would.
- [ ] **A one-block plank ship floating in water: punch its side with an empty hand (survival).** *Expect:* it drifts away from you, slowly (about half a metre a second), turning a little if you hit it off-centre. A 3x3 raft barely moves, a big ship not at all.
- [ ] **The same while sneaking, with a pickaxe, in creative, and standing on the ship you punch.** *Expect:* no push in any of these. Clicking fast pushes at most every 8 ticks. `punchShips` / `punchShipImpulse` (Ship Collisions) switch and size it.
- [ ] **Stand on a bearing's horizontal plate turning at 16 RPM (and lead a pig onto it).** *Expect:* your view turns with the plate smoothly, as on a Valkyrien Skies ship (no stutter per tick), and the pig keeps facing the same way relative to the plate. A windmill or a sideways bearing doesn't turn you. `contraptionRidersTurn` (Create) switches it off.
- [ ] **Fall, `/tp` yourself 2 blocks against a wall or onto slime, an ender pearl next to a wall, chorus fruit.** *Expect:* the teleport itself never hurts you or bounces you; a real hit right after still does.
