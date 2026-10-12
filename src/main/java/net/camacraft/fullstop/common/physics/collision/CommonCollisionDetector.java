package net.camacraft.fullstop.common.physics.collision;

import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.FastRaycast;
import net.camacraft.fullstop.FullStopConfig;
import net.camacraft.fullstop.common.capability.FullStopCapability;
import net.camacraft.fullstop.common.compat.ShipCompat;
import net.camacraft.fullstop.common.data.Collision;
import net.camacraft.fullstop.common.physics.math.RaycastUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.material.FluidState;
import net.camacraft.camafy.liquid.LiquidProperties;
import net.camacraft.camafy.liquid.Liquids;

import java.util.ArrayList;
import java.util.List;

/**
 * Ray-based block collision classifier. Note: this identifies WHAT was hit; whether
 * an impact deals damage is decided by the measured stopping force plus the entity's
 * own collision flags (see KineticDamageCalculator).
 */
public class CommonCollisionDetector {

    /**
     * Minimum speed (m/s) INTO a block face for the hit to count as a collision.
     * The rays have a 0.15 minimum length, so an entity standing flush against a
     * wall "hits" it every tick with near-zero velocity — those touches spammed
     * the impact sound/particles on every small deceleration (walking back and
     * forth beside a wall, brushing along honey) and lit the debug rays red while
     * merely hugging a wall. Also shared with the debug renderer's coloring.
     */
    public static final double MIN_APPROACH_SPEED_MPS = 2.0;

    /**
     * Minimum overlap (blocks) between the entity's swept box and a hit block's
     * cell on the axes perpendicular to the hit face for the hit to be real.
     * Vanilla collision resolution parks an entity within 1.0e-7 of the surface
     * it slides along, so anything at or under that is "flush", not overlapping;
     * 1.0e-4 leaves comfortable float headroom while a genuine approach overlaps
     * a face's cross-section by far more.
     */
    private static final double MIN_CROSS_SECTION_OVERLAP = 1.0e-4;

    /**
     * Whether the entity's swept volume genuinely overlaps the hit block's cell
     * on the two axes perpendicular to the hit face.
     *
     * <p>This kills the seam-graze false positives: vanilla leaves an entity
     * flush against the surface it slides along (within 1.0e-7), so the rays
     * cast from the flush corners run exactly IN the surface plane. The block
     * traversal then walks cells on the far side of that plane, and the clip
     * registers a hit on the seam face of the NEXT block along the surface — a
     * face whose normal fully opposes travel, so its "approach speed" is the
     * entity's entire speed. At high speed that read as a head-on collision
     * while merely rubbing: slime walls mirror-bounced runners back and forth,
     * honey walls dead-stopped and released them in pulses, and the debug rays
     * lit red. A real approach to a face always overlaps its cross-section; a
     * rub along the surface overlaps it by exactly zero.
     */
    public static boolean crossSectionOverlaps(AABB swept, BlockPos hitPos, Direction hitFace) {
        Direction.Axis axis = hitFace.getAxis();
        if (axis != Direction.Axis.X
                && Math.min(swept.maxX, hitPos.getX() + 1.0) - Math.max(swept.minX, hitPos.getX()) <= MIN_CROSS_SECTION_OVERLAP) {
            return false;
        }
        if (axis != Direction.Axis.Y
                && Math.min(swept.maxY, hitPos.getY() + 1.0) - Math.max(swept.minY, hitPos.getY()) <= MIN_CROSS_SECTION_OVERLAP) {
            return false;
        }
        if (axis != Direction.Axis.Z
                && Math.min(swept.maxZ, hitPos.getZ() + 1.0) - Math.max(swept.minZ, hitPos.getZ()) <= MIN_CROSS_SECTION_OVERLAP) {
            return false;
        }
        return true;
    }

    /** {@link #scanSwept}: some cell of the volume isn't air. */
    private static final int SWEPT_BLOCKS = 1;
    /** {@link #scanSwept}: some cell holds a liquid that acts solid at the speed given. */
    private static final int SWEPT_SOLID_LIQUID = 2;
    /** Above this many cells the scan gives up and answers "maybe" for both (as FastRaycast.mayHitAnything does). */
    private static final int MAX_SWEPT_CELLS = 128;

    /**
     * What the cells of {@code swept} hold, in one pass: {@link #SWEPT_BLOCKS} when any isn't air (FastRaycast's
     * all-air test), {@link #SWEPT_SOLID_LIQUID} when any holds a liquid that acts solid at {@code solidLiquidMps}
     * (negative: not looked for). Conservative: both when the volume is too big to scan.
     */
    private static int scanSwept(Level level, AABB swept, double solidLiquidMps) {
        int minX = net.minecraft.util.Mth.floor(swept.minX), maxX = net.minecraft.util.Mth.floor(swept.maxX);
        int minY = net.minecraft.util.Mth.floor(swept.minY), maxY = net.minecraft.util.Mth.floor(swept.maxY);
        int minZ = net.minecraft.util.Mth.floor(swept.minZ), maxZ = net.minecraft.util.Mth.floor(swept.maxZ);
        long cells = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cells > MAX_SWEPT_CELLS) return SWEPT_BLOCKS | SWEPT_SOLID_LIQUID;
        boolean liquids = solidLiquidMps >= 0.0;
        int found = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (state.isAir()) continue;
                    found |= SWEPT_BLOCKS;
                    if (!liquids) return found;
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty() && Liquids.actsSolid(fluid, solidLiquidMps)) return found | SWEPT_SOLID_LIQUID;
                }
            }
        }
        return found;
    }

    public static Collision detectBlocks(Entity entity, FullStopCapability fullstop) {
        // Pre-impact velocity (faster of the last two ticks): the damage tick of a
        // two-tick impact must still see the block, even though last tick's travel
        // was only the small post-contact remnant.
        Vec3 velocity = fullstop.getPreImpactNativeVelocity();
        if (velocity.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Vec3 direction = RaycastUtil.getRayDirection(fullstop);
        if (direction.lengthSqr() == 0) {
            return Collision.NONE;
        }

        Level level = entity.level();
        if (!RaycastUtil.raysFor(entity)) {
            return Collision.NONE;
        }
        // read once: a size mod's dimensions aren't free, and both the reach and the starts scale with it
        double size = RaycastUtil.sizeFactor(entity);
        double rayLength = RaycastUtil.getRayLength(entity, fullstop, size);
        if (rayLength <= 0) {
            return Collision.NONE;
        }

        // Which way is up for THIS entity. Every floor/wall/ceiling judgement
        // below is made against it: under a Gravity Unbound field the world's
        // UP face may be a wall to the entity and a WEST face its floor.
        GravityFrame frame = fullstop.getFrame();

        // Every ray lies inside the ray-start envelope swept along the travel
        // direction. If that volume is provably all air — and no VS ship (whose
        // blocks live outside the world grid) is near it — no clip can hit
        // anything, so the rays are skipped wholesale. The envelope floor is
        // minY + 0.1 (where the bottom rays actually start, see RaycastUtil),
        // NOT the box floor: using the box floor would pull the block layer a
        // grounded entity stands on into the scan and defeat the skip for every
        // sprinting player and galloping horse on flat ground.
        AABB box = entity.getBoundingBox();
        // Effective floor, not the raw box floor: a riding passenger's box dips
        // below their vehicle's hull into the floor layer (see effectiveFloorY).
        double effectiveFloor = RaycastUtil.effectiveFloorY(entity);
        // Under a rotated frame the box is only the tilted body's world-aligned
        // envelope and its floor is a side, not the feet: sweep the whole
        // envelope (conservative — it can only say "might hit" more often).
        AABB rayEnvelope = frame.isDefault()
                ? new AABB(box.minX, Math.min(effectiveFloor + 0.1, box.maxY), box.minZ,
                        box.maxX, box.maxY, box.maxZ)
                : box;
        AABB swept = rayEnvelope.expandTowards(direction.scale(rayLength)).inflate(0.001);
        // A liquid that turns solid under a sudden blow (Flow Fun's oobleck, Camafy's
        // LiquidProperties.solidAboveMps) is met as a block by a body moving into it fast enough:
        // looked for only when this body is that fast, so nothing changes for anyone else.
        double speedMps = velocity.length() * 20.0;
        boolean solidLiquidsPossible = speedMps >= minSolidLiquidMps();
        int nearby = scanSwept(level, swept, solidLiquidsPossible ? speedMps : -1.0);
        boolean worldBlocksNearby = (nearby & SWEPT_BLOCKS) != 0;
        boolean shipsNearby = ShipCompat.anyShipsNearby(level, swept);
        if (!worldBlocksNearby && !shipsNearby) {
            return Collision.NONE;
        }
        // every cell a ray crosses lies in the swept volume: no liquid there that a blow this fast makes solid, no
        // second traversal per ray looking for one
        if (!net.camacraft.fullstop.common.physics.PerfSwitches.alwaysClipLiquids()) {
            solidLiquidsPossible &= (nearby & SWEPT_SOLID_LIQUID) != 0;
        }

        FullStopConfig.RaycastMode mode = FullStopConfig.SERVER.raycastMode.get();
        List<Vec3> rayStarts = RaycastUtil.getRayStarts(entity, mode, frame, size);
        // Full entity box swept along the travel direction, for the seam-graze
        // cross-section test (unlike rayEnvelope, this one keeps the true floor).
        AABB sweptBox = box.expandTowards(direction.scale(rayLength));
        // One shape context for all rays; each ClipContext would otherwise build
        // its own EntityCollisionContext (SynchedEntityData reads) per ray.
        CollisionContext shapeContext = CollisionContext.of(entity);
        Vec3 preImpactVelocity = fullstop.getPreImpactScaledVelocity();
        // The floor-hit gate below (blocks per tick into the floor): a landing's speed grows
        // with the square root of gravity, so the gate shrinks with it on the Moon, and in zero
        // gravity nobody walks on a floor: every floor hit counts.
        double floorGate = fullstop.isWeightless() ? Double.POSITIVE_INFINITY
                : -0.5 * Math.sqrt(Math.min(1.0, fullstop.getGravityStrength()));

        List<BlockState> collidedBlockStates = new ArrayList<>();
        List<BlockPos> collidedBlockPositions = new ArrayList<>();
        List<BlockHitResult> collidedBlockHits = new ArrayList<>();
        Collision.CollisionType impactType = Collision.CollisionType.NONE;
        // Per ship: whether the entity really meets it this tick (see shipHitIsContact).
        java.util.Map<Long, Boolean> shipContacts = null;
        // A body plunging down through water looks THROUGH it (see looksThroughWater).
        boolean throughWater = looksThroughWater(entity, frame, velocity);

        for (Vec3 start : rayStarts) {
            Vec3 end = start.add(direction.scale(rayLength));

            // Ship blocks are only reachable through the (expensive, VS-wrapped)
            // Level.clip; far from ships the vanilla-equivalent fast path is used.
            BlockHitResult blockHit = shipsNearby
                    ? level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                            throughWater ? ClipContext.Fluid.NONE : ClipContext.Fluid.SOURCE_ONLY, entity))
                    : throughWater ? clipBlocksOnly(level, start, end, shapeContext)
                    : FastRaycast.clip(level, start, end, shapeContext);

            // A liquid acting solid at this body's speed into it, nearer than whatever the ray met.
            boolean solidLiquid = false;
            if (solidLiquidsPossible) {
                BlockHitResult liquidHit = clipSolidLiquid(level, start, end, velocity);
                if (liquidHit != null && (blockHit.getType() != HitResult.Type.BLOCK
                        || start.distanceToSqr(liquidHit.getLocation()) <= start.distanceToSqr(blockHit.getLocation()))) {
                    blockHit = liquidHit;
                    solidLiquid = true;
                }
            }

            if (blockHit.getType() != HitResult.Type.BLOCK) continue;

            BlockPos hitPos = blockHit.getBlockPos();
            if (shipsNearby && !solidLiquid && ShipCompat.isShipBlock(level, hitPos)) {
                // A block of a moving hull in the ray's way now is not necessarily one the entity
                // meets - a hull moving away, a rotor's arm sweeping past: only a real contact
                // within a tick, judged with the two bodies' relative motion in the ship's frame.
                ShipRef ship = Ships.managing(level, hitPos);
                if (ship != null) {
                    if (shipContacts == null) shipContacts = new java.util.HashMap<>();
                    Boolean real = shipContacts.get(ship.id());
                    if (real == null) {
                        real = shipHitIsContact(level, entity, fullstop, hitPos);
                        shipContacts.put(ship.id(), real);
                        if (!real) ShipRayLog.rejected(entity, ship, hitPos, fullstop);
                    }
                    if (!real) continue;
                }
            }
            BlockState hitState = level.getBlockState(hitPos);
            Direction hitFace = blockHit.getDirection();

            // (a ray looking through water only ever hits block shapes: a waterlogged slab at the
            // bottom is the bottom, not a surface)
            boolean isWater = !throughWater && !solidLiquid
                    && !hitState.getFluidState().isEmpty() && hitState.getFluidState().is(FluidTags.WATER);
            boolean isOpposing;

            if (isWater) {
                // A water surface counts when skimming across it (water-skip) or
                // plunging into it fast (-0.35 blocks/tick ≈ 7 m/s downward —
                // stepping off a 2-block ledge into a pond must stay silent).
                // The old mostly-horizontal-only rule made vertical dives
                // undetectable, which killed the belly-flop feature outright.
                // "Surface" and "downward" are the frame's: inside a gravity
                // field the liquid's surface is the face across the field.
                boolean skimming = fullstop.isMostlyHorizontal();
                boolean plunging = frame.vertical(velocity) < -0.35;
                if (!frame.isFloorFace(hitFace) || (!skimming && !plunging)) {
                    continue;
                }
                isOpposing = true;
            } else {
                isOpposing = opposesAsSurface(level, entity, frame, direction, preImpactVelocity,
                        sweptBox, effectiveFloor, hitPos, hitFace);

                // Wall faces are ignored while mostly ascending so jumping flush
                // against a wall never reads as a wall impact — but ONLY below the
                // horizontal damage threshold. Unconditional, this blinded the
                // detector to genuine high-speed climbing impacts (elytra swooping
                // up into a cliff face dealt zero damage).
                if (isOpposing && fullstop.isMostlyUpward() && frame.isWallFace(hitFace)
                        && frame.horizontalDistance(preImpactVelocity)
                                < FullStopConfig.SERVER.velocityDamageThresholdHorizontal.get()) {
                    isOpposing = false;
                }

                // Ignore floor hits unless actually falling with some speed (velocity here
                // is native blocks/tick; -0.5 ≈ falling faster than 10 m/s under normal
                // gravity, see floorGate).
                // Projectiles are exempt from this walking-artifact rule: a fired
                // hook arcing down into floor ice is a real approach — the
                // approach-speed gate above still filters near-flat grazes.
                if (isOpposing && frame.isFloorFace(hitFace) && frame.vertical(velocity) > floorGate
                        && !(entity instanceof net.minecraft.world.entity.projectile.Projectile)) {
                    isOpposing = false;
                }

                // A face BURIED against a neighboring block is not a real
                // surface. Last check deliberately: it costs a getBlockState.
                if (isOpposing && isBuriedFace(level, hitPos, hitState, hitFace)) {
                    isOpposing = false;
                }
            }

            if (!isOpposing || collidedBlockPositions.contains(hitPos)) continue;

            collidedBlockStates.add(hitState);
            collidedBlockPositions.add(hitPos);
            collidedBlockHits.add(blockHit);

            Collision.CollisionType typeHere;
            if (isWater) {
                typeHere = Collision.CollisionType.WATER;
            } else if (hitState.isStickyBlock()) {
                // instanceof rather than a Blocks reference so modded SlimeBlock
                // subclasses bounce instead of behaving like honey.
                if (hitState.getBlock() instanceof SlimeBlock) {
                    typeHere = Collision.CollisionType.SLIME;
                } else {
                    typeHere = Collision.CollisionType.HONEY;
                }
            } else if (hitState.getBlock() instanceof BedBlock) {
                typeHere = Collision.CollisionType.BED;
            } else {
                typeHere = Collision.CollisionType.SOLID;
            }

            if (impactType.priority < typeHere.priority) {
                impactType = typeHere;
            }
        }

        if (impactType == Collision.CollisionType.NONE) {
            return Collision.NONE;
        }

        return new Collision(impactType, collidedBlockStates, List.of(), collidedBlockPositions, collidedBlockHits);
    }

    /**
     * Where the ray {@code from -> to} first meets a liquid that acts as a solid block for this
     * body: a liquid whose Camafy description declares {@link LiquidProperties#solidAboveMps()}
     * (Flow Fun's oobleck), met at a face the body moves into at or above that speed
     * ({@link Liquids#actsSolid}; the speed is the body's world velocity into the face, m/s). Any
     * fluid cell counts, flowing or source, at the height its fluid shape has; null when the ray
     * meets none. World grid only (a liquid carried on a ship is not looked for).
     *
     * <p>Such a hit is judged exactly as a block's: the same surface, floor and buried-face gates,
     * a SOLID impact, the same damage (the liquid counts as stone in the hardness curve, see
     * KineticDamageCalculator) - the body is stopped there by the liquid's own collision shape
     * (Flow Fun makes oobleck's a full block for a fast body). Below the speed it is a liquid
     * again and the body sinks, as before.</p>
     *
     * @param velocityNative the body's velocity going into the impact, blocks per tick
     */
    public static BlockHitResult clipSolidLiquid(Level level, Vec3 from, Vec3 to, Vec3 velocityNative) {
        double speedMps = velocityNative.length() * 20.0;
        BlockHitResult hit = net.minecraft.world.level.BlockGetter.traverseBlocks(from, to, null, (unused, pos) -> {
            FluidState fluid = level.getFluidState(pos);
            // the cheap test first: not even the body's whole speed makes it solid
            if (fluid.isEmpty() || !Liquids.actsSolid(fluid, speedMps)) return null;
            BlockHitResult fluidHit = fluid.getShape(level, pos).clip(from, to, pos);
            if (fluidHit == null) return null;
            Vec3 normal = Vec3.atLowerCornerOf(fluidHit.getDirection().getNormal());
            double intoMps = -velocityNative.dot(normal) * 20.0;
            return Liquids.actsSolid(fluid, intoMps) ? fluidHit : null;
        }, unused -> null);
        return hit;
    }

    /**
     * The lowest speed (m/s) at which any described liquid acts solid, or +infinity when none does
     * (then no ray looks for one). Recomputed at most once a second (descriptions are registered at
     * start-up, and one can be replaced under the same id), so it costs nothing per ray.
     */
    public static double minSolidLiquidMps() {
        long now = System.nanoTime();
        if (now - minSolidLiquidAt > 1_000_000_000L || minSolidLiquidAt == 0L) {
            double min = Double.POSITIVE_INFINITY;
            for (LiquidProperties p : Liquids.all()) {
                if (p.solidAboveMps() > 0.0) min = Math.min(min, p.solidAboveMps());
            }
            minSolidLiquid = min;
            minSolidLiquidAt = now;
        }
        return minSolidLiquid;
    }

    private static volatile double minSolidLiquid = Double.POSITIVE_INFINITY;
    private static volatile long minSolidLiquidAt = 0L;

    /**
     * Whether {@code state} is a liquid's own block (vanilla's or a Forge fluid's), not a block that
     * merely holds a fluid (a waterlogged slab). Impacts on one never crack or break it.
     */
    public static boolean isLiquidBlock(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                || state.getBlock() instanceof net.minecraftforge.fluids.IFluidBlock;
    }

    /**
     * Whether a body's rays look through the water it is in: a body already in water and still
     * plunging down through it (faster than the water-entry gate, -0.35 blocks a tick along its
     * frame's down). From inside a pool every fluid-picking ray stops at the water it starts in (an
     * "inside" hit), so the bottom was never seen: a fall into a puddle met water, never the stone
     * under it, and dealt no damage however fast the body still was when it got there (the owner's
     * report of 2026-10-01; Full Stop looked through water for any body in it until the 2.0 refactor
     * of 2026-07-05 made every ray SOURCE_ONLY). Only a plunge does: a swimmer, a boat or a ship's
     * crew in the water keeps the old rays, so nothing changes for them. The surface hit (water
     * entry, belly flops, skipping) is the ray of a body NOT yet in water, unchanged; the water's
     * braking over the depth actually crossed is vanilla's drag, so a one-block puddle barely slows
     * a fall and deep water stops it before the bottom.
     */
    public static boolean looksThroughWater(Entity entity, GravityFrame frame, Vec3 velocity) {
        return entity.isInWater() && frame.vertical(velocity) < -0.35;
    }

    /**
     * {@link FastRaycast#clip} for a body in water: block collision shapes only, fluids ignored
     * (as {@code level.clip} with {@code Fluid.NONE}), with the same shared shape context and no
     * per-ray allocations. Only valid when no ship is within the ray's reach.
     */
    static BlockHitResult clipBlocksOnly(Level level, Vec3 from, Vec3 to, CollisionContext shapeContext) {
        return net.minecraft.world.level.BlockGetter.traverseBlocks(from, to, null, (unused, pos) -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return null;
            }
            net.minecraft.world.phys.shapes.VoxelShape shape = ClipContext.Block.COLLIDER.get(state, level, pos, shapeContext);
            return level.clipWithInteractionOverride(from, to, pos, shape, state);
        }, unused -> {
            Vec3 back = from.subtract(to);
            return BlockHitResult.miss(to, Direction.getNearest(back.x, back.y, back.z), BlockPos.containing(to));
        });
    }

    /**
     * Whether a ray hit on the ship block {@code hitPos} is a contact the entity really makes: its
     * box, moving at its world velocity going into the impact (its own plus a ship's carry)
     * RELATIVE to that ship, comes within touching distance of the ship's blocks within a tick
     * either way ({@link ShipCompat#meetsShip}). The entity's rays look a tick of its travel
     * ahead, in a straight line; against a hull that moves, what is in the way now can be gone
     * by then, and a hull that isn't met is never an impact (no damage, no block breaking, no
     * bounce). Shared with the debug ray view.
     */
    public static boolean shipHitIsContact(Level level, Entity entity, FullStopCapability fullstop, BlockPos hitPos) {
        return ShipCompat.meetsShip(level, entity, hitPos, fullstop.getPreImpactWorldVelocity());
    }

    /** Diagnostics of rejected ship ray hits (debugShipContactLog), players only, once a second. */
    static final class ShipRayLog {
        private static final java.util.Map<Entity, Long> LAST = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        static void rejected(Entity entity, ShipRef ship, BlockPos hitPos, FullStopCapability fullstop) {
            if (!(entity instanceof net.minecraft.world.entity.player.Player) || entity.level().isClientSide
                    || !FullStopConfig.SERVER.debugShipContactLog.get()) return;
            long now = entity.level().getGameTime();
            Long last = LAST.get(entity);
            if (last != null && now - last < 20) return;
            LAST.put(entity, now);
            Vec3 v = fullstop.getPreImpactWorldVelocity();
            org.slf4j.LoggerFactory.getLogger(CommonCollisionDetector.class).info(
                    "[FullStop] a ray of {}'s own motion ({} m/s) hits ship {} at {}, but the two don't meet within a tick "
                            + "(the hull moves on): not an impact", entity.getName().getString(),
                    String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z), ship.id(), hitPos);
        }
    }

    /**
     * The face-level rules shared by the gameplay classifier and the debug ray
     * view — whether a solid ray hit is a surface the entity is genuinely
     * running/falling INTO (as opposed to a face merely touched, grazed along,
     * or standing ahead as the next floor block):
     * <ol>
     * <li>the face opposes the travel direction and is approached with
     *     collision-grade speed (see {@link #MIN_APPROACH_SPEED_MPS});</li>
     * <li>the entity's swept volume overlaps the face's cross-section (see
     *     {@link #crossSectionOverlaps}) — for a Valkyrien Skies ship block,
     *     tested in the ship's own coordinates: hits on ship blocks come back
     *     addressed in shipyard space, which a world-space box never overlaps,
     *     so every ship impact used to be thrown away right here;</li>
     * <li>a wall-like face on a block whose top sits at the entity's foot level
     *     is the floor ahead, not a wall (see {@link #isFloorAhead}).</li>
     * </ol>
     * All judged in the entity's gravity frame. The buried-face rule is NOT
     * included (it costs a block read; callers run {@link #isBuriedFace} last).
     */
    public static boolean opposesAsSurface(Level level, Entity entity, GravityFrame frame, Vec3 direction,
                                           Vec3 preImpactVelocityMps, AABB sweptBox, double effectiveFloor,
                                           BlockPos hitPos, Direction hitFace) {
        // A ship block's face comes back in ship space: its WORLD normal turns with the ship.
        boolean shipHit = ShipCompat.isShipBlock(level, hitPos);
        Vec3 hitNormal = shipHit ? ShipCompat.worldFaceNormal(level, hitPos, hitFace) : Vec3.atLowerCornerOf(hitFace.getNormal());
        if (direction.dot(hitNormal) >= -0.1) {
            return false;
        }

        // A face only counts when actually approached with some speed — the
        // 0.15 minimum ray reach otherwise reports a "collision" with the wall
        // the entity is standing flush against every single tick.
        if (-preImpactVelocityMps.dot(hitNormal) < MIN_APPROACH_SPEED_MPS) {
            return false;
        }

        AABB sweptForHit = sweptBox;
        double floorForHit = effectiveFloor;
        if (shipHit) {
            AABB shipBox = ShipCompat.worldToShipBox(level, hitPos, sweptBox);
            if (shipBox != null) {
                sweptForHit = shipBox;
                floorForHit = shipBox.minY;
            }
        }

        // Rays grazing exactly along a surface hit the seam faces of the next
        // blocks along it, whose normals oppose travel at full speed. Only
        // faces whose cross-section the entity's swept box genuinely overlaps
        // are real — see crossSectionOverlaps.
        if (!crossSectionOverlaps(sweptForHit, hitPos, hitFace)) {
            return false;
        }

        // A side face on a block whose TOP is level with the entity's feet is
        // the floor ahead, not a wall. The bottom rays start only 0.1 above the
        // surface, so any tiny downward tilt in the travel direction (boat
        // gravity drift, a micro-hop at speed) dips them below that margin and
        // into the NEXT floor block through its seam-side face — which then
        // reads as a wall hit at full horizontal speed. Boats gliding on ice
        // hit this every seam: phantom collision sounds, smashed ice under the
        // boat, and boat-breaking "wall" damage while driving on flat ground.
        // Feet = the EFFECTIVE floor: a boat passenger's own box floor is 0.45
        // inside the ice layer, which made this rule miss.
        if (frame.isWallFace(hitFace) && isFloorAhead(frame, entity, hitPos, floorForHit, shipHit)) {
            return false;
        }

        return true;
    }

    /**
     * Whether a wall-like face belongs to a block whose top is at or below the
     * entity's feet (+0.15) as the frame sees it — the next floor block, not a
     * wall. World frame: block top vs the effective floor Y. Rotated frame:
     * the cell's furthest reach along up vs the feet point along up. A ship
     * block under a rotated frame has no comparable plane (shipyard cell vs a
     * world frame), so the rule stands down and the hit is kept.
     */
    private static boolean isFloorAhead(GravityFrame frame, Entity entity, BlockPos hitPos, double floor, boolean shipHit) {
        if (frame.isDefault()) {
            return hitPos.getY() + 1.0 <= floor + 0.15;
        }
        if (shipHit) {
            return false;
        }
        return frame.blockTop(hitPos) <= frame.vertical(entity.position()) + 0.15;
    }

    /**
     * A face BURIED against a neighboring block is not a real surface. A
     * shallow-angle approach sweeps genuinely INTO a wall column, so rays can
     * graze the INTERNAL seams between the wall's blocks — faces whose normals
     * oppose the TANGENTIAL travel at full speed. mostOpposedNormal then
     * preferred a seam over the true wall surface and mirrored shallow slime
     * hits BACKWARDS (then back and forth until the speed died).
     */
    public static boolean isBuriedFace(Level level, BlockPos hitPos, BlockState hitState, Direction hitFace) {
        BlockPos facingPos = hitPos.relative(hitFace);
        BlockState facingState = level.getBlockState(facingPos);
        return facingState.getBlock() == hitState.getBlock()
                || facingState.isFaceSturdy(level, facingPos, hitFace.getOpposite());
    }
}
