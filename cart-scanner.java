package releon.ru.utils.player;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class CartScanner {

    public static final double ARROW_V0 = 3.15D;
    public static final double ARROW_DRAG = 0.99D;
    public static final double ARROW_GRAVITY = 0.05D;
    public static final int MAX_SIMULATION_TICKS = 8;

    public static final int[][] CARDINAL_OFFSETS = {
            {0, -1}, {0, 1}, {1, 0}, {-1, 0}
    };

    public static final int[][] DIAGONAL_OFFSETS = {
            {1, -1}, {-1, -1}, {1, 1}, {-1, 1}
    };

    public static final int[][] ALL_HORIZONTAL_OFFSETS = {
            {0, -1}, {0, 1}, {1, 0}, {-1, 0},
            {1, -1}, {-1, -1}, {1, 1}, {-1, 1}
    };

    /**
     * DTO representing a validated cart setup candidate.
     * Matches the PROJECT.md specification contract.
     */
    public record CartSetup(
            BlockPos cartPos,          // Empty block where rail & minecart are placed
            BlockPos firePos,          // Strictly adjacent empty block where fire is placed/exists
            Angle aimAngle,            // Solved trajectory yaw & pitch for crossbow arrow
            BlockPos railSupportPos,   // Solid support block directly underneath cartPos (cartPos.below())
            boolean fireAlreadyExists  // True if firePos is already Blocks.FIRE
    ) {}

    /**
     * Intermediate candidate pair before trajectory solving.
     */
    public record CandidatePair(
            BlockPos cartPos,
            BlockPos firePos,
            BlockPos railSupportPos,
            BlockPos fireSupportPos,
            boolean fireAlreadyExists,
            double score
    ) {}

    public record YawInterval(float minYaw, float maxYaw, boolean valid) {}

    public record SimulationHit(
            boolean hitsFire,
            boolean hitsCart,
            double fireTime,
            double cartTime,
            boolean blocked
    ) {
        public boolean isSuccessful() {
            return hitsFire && hitsCart && fireTime <= cartTime && (cartTime - fireTime) <= 1.0D && !blocked;
        }
    }

    public static class FirePlacement {
        public final BlockPos firePos;
        public final Angle aimAngle;
        public final Vec3 aimPoint;
        public final boolean alreadyExists;

        public FirePlacement(BlockPos firePos, Angle aimAngle, Vec3 aimPoint, boolean alreadyExists) {
            this.firePos = firePos;
            this.aimAngle = aimAngle;
            this.aimPoint = aimPoint;
            this.alreadyExists = alreadyExists;
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Extracts the effective movement velocity vector of a player.
     * In Minecraft physics (Projectile.shootFromRotation), vertical velocity is zeroed if onGround is true.
     */
    public static Vec3 getEffectivePlayerVelocity(Player player) {
        if (player == null) return Vec3.ZERO;
        Vec3 movement = player.getDeltaMovement();
        if (movement == null) return Vec3.ZERO;
        double vy = player.onGround() ? 0.0D : movement.y;
        return new Vec3(movement.x, vy, movement.z);
    }

    public static boolean isRayClearOfBlocks(Level level, Vec3 from, Vec3 to, Entity player) {
        if (level == null) return true;
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }

    public static boolean isPositionBlockedByEntity(List<Entity> entities, BlockPos pos) {
        if (entities == null || pos == null) return false;
        AABB box = new AABB(
                pos.getX() + 0.01D, pos.getY(), pos.getZ() + 0.01D,
                pos.getX() + 0.99D, pos.getY() + 0.99D, pos.getZ() + 0.99D
        );
        for (Entity entity : entities) {
            if (entity == null || !entity.isAlive()) continue;
            if (entity instanceof AbstractArrow) continue;
            if (entity.getBoundingBox().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the best cart setup within the specified radius around the target.
     * Evaluates candidate pairs, checks strict adjacency, validates support surfaces,
     * and calculates continuous ballistic trajectory for a charged crossbow.
     */
    public static CartSetup findBestCartSetup(Minecraft client, Entity target, int radius, boolean requiresFire) {
        Vec3 vel = (client != null && client.player != null) ? getEffectivePlayerVelocity(client.player) : Vec3.ZERO;
        return findBestCartSetup(client, target, radius, requiresFire, vel, null);
    }

    public static CartSetup findBestCartSetup(Minecraft client, Entity target, int radius, boolean requiresFire, Vec3 playerVelocity) {
        return findBestCartSetup(client, target, radius, requiresFire, playerVelocity, null);
    }

    public static CartSetup findBestCartSetup(
            Minecraft client,
            Entity target,
            int radius,
            boolean requiresFire,
            Vec3 playerVelocity,
            Set<BlockPos> blacklistedPositions
    ) {
        if (client == null || client.player == null || client.level == null || target == null) return null;
        Vec3 playerEyes = client.player.getEyePosition();

        List<CandidatePair> candidates = findCandidatePairs(client, target, radius, requiresFire, blacklistedPositions);

        for (CandidatePair candidate : candidates) {
            BlockPos cartPos = candidate.cartPos();
            BlockPos firePos = candidate.firePos();

            AABB cartBox = new AABB(
                    cartPos.getX() + 0.01D, cartPos.getY() + 0.0625D, cartPos.getZ() + 0.01D,
                    cartPos.getX() + 0.99D, cartPos.getY() + 0.7625D, cartPos.getZ() + 0.99D
            );

            if (requiresFire && firePos != null) {
                AABB fireBox = new AABB(
                        firePos.getX(), firePos.getY(), firePos.getZ(),
                        firePos.getX() + 1.0D, firePos.getY() + 1.0D, firePos.getZ() + 1.0D
                );

                Optional<Angle> aimAngleOpt = solveTrajectoryAngle(client, playerEyes, playerVelocity, fireBox, cartBox, firePos, cartPos);
                if (aimAngleOpt.isPresent()) {
                    return new CartSetup(
                            cartPos,
                            firePos,
                            aimAngleOpt.get(),
                            candidate.railSupportPos(),
                            candidate.fireAlreadyExists()
                    );
                }
            } else {
                Optional<Angle> aimAngleOpt = solveDirectTrajectoryAngle(client, playerEyes, playerVelocity, cartBox, cartPos);
                if (aimAngleOpt.isPresent()) {
                    return new CartSetup(
                            cartPos,
                            null,
                            aimAngleOpt.get(),
                            candidate.railSupportPos(),
                            false
                    );
                }
            }
        }

        return null;
    }

    /**
     * Discovers all candidate adjacent block pairs within the search radius satisfying
     * spatial, support surface, and damage path criteria, sorted by heuristic score.
     */
    public static List<CandidatePair> findCandidatePairs(Minecraft client, Entity target, int radius, boolean requiresFire) {
        return findCandidatePairs(client, target, radius, requiresFire, null);
    }

    public static List<CandidatePair> findCandidatePairs(
            Minecraft client,
            Entity target,
            int radius,
            boolean requiresFire,
            Set<BlockPos> blacklistedPositions
    ) {
        List<CandidatePair> candidates = new ArrayList<>();
        if (client == null || client.player == null || client.level == null || target == null) return candidates;

        Vec3 playerEyes = client.player.getEyePosition();
        AABB targetBox = target.getBoundingBox();
        Vec3 targetFeet = new Vec3(target.getX(), targetBox.minY, target.getZ());
        BlockPos targetBlockPos = target.blockPosition();

        // Scan-Start Pre-Fetch: query local entities once to minimize performance overhead
        List<Entity> nearbyEntities = client.level.getEntities((Entity) null, targetBox.inflate(radius + 2.0D));

        // Uneven terrain search space: Y in [targetY - 3, targetY + 2]
        for (int x = targetBlockPos.getX() - radius; x <= targetBlockPos.getX() + radius; x++) {
            for (int z = targetBlockPos.getZ() - radius; z <= targetBlockPos.getZ() + radius; z++) {
                for (int y = targetBlockPos.getY() - 3; y <= targetBlockPos.getY() + 2; y++) {
                    BlockPos cartPos = new BlockPos(x, y, z);
                    BlockPos railSupportPos = cartPos.below();

                    // Blacklist filter
                    if (blacklistedPositions != null && blacklistedPositions.contains(cartPos)) continue;

                    // 1. Target spherical distance gate (<= radius)
                    if (Vec3.atCenterOf(cartPos).distanceToSqr(targetFeet) > (double) (radius * radius)) continue;

                    // 2. Player reach gate (<= 4.5 blocks)
                    if (playerEyes.distanceToSqr(Vec3.atCenterOf(cartPos)) > 20.25D) continue;

                    // 3. Rail support check (must be solid full block at Y - 1)
                    if (!isValidRailSupport(client.level, railSupportPos)) continue;

                    // 4. Cart space clearance check (must be empty/rail and space above clear, no fluids)
                    BlockState cartState = client.level.getBlockState(cartPos);
                    BlockState aboveState = client.level.getBlockState(cartPos.above());
                    if (!isValidEmptySpace(cartState, true) || !isValidEmptySpace(aboveState, false)) continue;
                    if (!cartState.getFluidState().isEmpty() || !aboveState.getFluidState().isEmpty()) continue;

                    // Entity obstruction check on cartPos
                    if (isPositionBlockedByEntity(nearbyEntities, cartPos)) continue;

                    // 5. In front of player check
                    Vec3 cartCenter = new Vec3(cartPos.getX() + 0.5D, cartPos.getY() + 0.4D, cartPos.getZ() + 0.5D);
                    Angle angleToCart = MathAngle.fromVec3d(cartCenter.subtract(playerEyes));
                    float yawDiff = Math.abs(Mth.wrapDegrees(angleToCart.getYaw() - client.player.getYRot()));
                    if (yawDiff > 80.0F) continue;

                    // 6. Direct Line-of-Sight Raycast check from player eyes to cart center
                    BlockHitResult losCheck = client.level.clip(new ClipContext(
                            playerEyes, cartCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player
                    ));
                    if (losCheck != null && losCheck.getType() == HitResult.Type.BLOCK
                            && !losCheck.getBlockPos().equals(railSupportPos) && !losCheck.getBlockPos().equals(cartPos)) {
                        continue;
                    }

                    // 7. Player placement visibility to rail support / surface
                    Vec3 surfacePos = new Vec3(cartPos.getX() + 0.5D, cartPos.getY(), cartPos.getZ() + 0.5D);
                    BlockHitResult placeCheck = client.level.clip(new ClipContext(
                            playerEyes, surfacePos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player
                    ));
                    if (placeCheck != null && placeCheck.getType() == HitResult.Type.BLOCK
                            && !placeCheck.getBlockPos().equals(railSupportPos) && !placeCheck.getBlockPos().equals(cartPos)) continue;
                    if (!isPathClearOfPlayers(client, playerEyes, surfacePos, target)) continue;

                    // 8. Tri-Ray Damage Exposure Check (Torso center, Lower body, Upper body)
                    Vec3 targetCenter = target.getBoundingBox().getCenter();
                    Vec3 targetLow = new Vec3(target.getX(), target.getBoundingBox().minY + 0.3D, target.getZ());
                    Vec3 targetHigh = new Vec3(target.getX(), target.getBoundingBox().maxY - 0.2D, target.getZ());

                    boolean dmgClear = isRayClearOfBlocks(client.level, cartCenter, targetCenter, client.player)
                                    || isRayClearOfBlocks(client.level, cartCenter, targetLow, client.player)
                                    || isRayClearOfBlocks(client.level, cartCenter, targetHigh, client.player);
                    if (!dmgClear) continue;

                    // 9. Discover adjacent fire placement
                    if (requiresFire) {
                        for (int[] offset : ALL_HORIZONTAL_OFFSETS) {
                            int dx = offset[0];
                            int dz = offset[1];
                            BlockPos firePos = cartPos.offset(dx, 0, dz);

                            // Blacklist filter on firePos
                            if (blacklistedPositions != null && blacklistedPositions.contains(firePos)) continue;

                            // Strict same Y-level and adjacent pair check (cardinal or diagonal)
                            if (!isValidAdjacentPair(cartPos, firePos)) continue;

                            // Fire position radius gate (<= radius)
                            if (Vec3.atCenterOf(firePos).distanceToSqr(targetFeet) > (double) (radius * radius)) continue;

                            BlockState fireState = client.level.getBlockState(firePos);
                            boolean fireAlreadyExists = fireState.is(Blocks.FIRE);
                            BlockPos fireSupportPos = firePos.below();

                            if (fireAlreadyExists) {
                                // Existing fire in world: valid
                            } else {
                                if (!isValidEmptySpace(fireState, false)) continue;
                                if (!fireState.getFluidState().isEmpty()) continue;
                                if (!isValidFireSupport(client.level, fireSupportPos)) continue;
                                if (playerEyes.distanceToSqr(Vec3.atCenterOf(firePos)) > 20.25D) continue;

                                // Entity obstruction filter on firePos
                                if (isPositionBlockedByEntity(nearbyEntities, firePos)) continue;

                                Vec3 fireSurface = new Vec3(firePos.getX() + 0.5D, firePos.getY(), firePos.getZ() + 0.5D);
                                BlockHitResult firePlaceCheck = client.level.clip(new ClipContext(
                                        playerEyes, fireSurface, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player
                                ));
                                if (firePlaceCheck != null && firePlaceCheck.getType() == HitResult.Type.BLOCK
                                        && !firePlaceCheck.getBlockPos().equals(fireSupportPos) && !firePlaceCheck.getBlockPos().equals(firePos)) continue;
                                if (!isPathClearOfPlayers(client, playerEyes, fireSurface, target)) continue;
                            }

                            // Prune closed corner pinch points for diagonal pairs
                            if (isDiagonalAdjacent(cartPos, firePos)) {
                                BlockPos c1 = new BlockPos(cartPos.getX() + dx, cartPos.getY(), cartPos.getZ());
                                BlockPos c2 = new BlockPos(cartPos.getX(), cartPos.getY(), cartPos.getZ() + dz);
                                BlockState s1 = client.level.getBlockState(c1);
                                BlockState s2 = client.level.getBlockState(c2);
                                if (s1.isSolid() && s1.isCollisionShapeFullBlock(client.level, c1)
                                        && s2.isSolid() && s2.isCollisionShapeFullBlock(client.level, c2)) {
                                    continue;
                                }
                            }

                            double distToTarget = cartCenter.distanceToSqr(targetFeet);
                            double distToPlayer = playerEyes.distanceToSqr(cartCenter);
                            double elevationPenalty = Math.abs(cartPos.getY() - targetBlockPos.getY()) * 0.75D;
                            boolean isDiagonal = (dx != 0 && dz != 0);
                            double diagonalPenalty = isDiagonal ? 0.5D : 0.0D;
                            double bonus = fireAlreadyExists ? 8.0D : 0.0D;
                            double score = distToTarget * 1.5D + distToPlayer * 0.5D + elevationPenalty + diagonalPenalty - bonus;

                            candidates.add(new CandidatePair(cartPos, firePos, railSupportPos, fireSupportPos, fireAlreadyExists, score));
                        }
                    } else {
                        double distToTarget = cartCenter.distanceToSqr(targetFeet);
                        double distToPlayer = playerEyes.distanceToSqr(cartCenter);
                        double elevationPenalty = Math.abs(cartPos.getY() - targetBlockPos.getY()) * 0.75D;
                        double score = distToTarget * 1.5D + distToPlayer * 0.5D + elevationPenalty;

                        candidates.add(new CandidatePair(cartPos, null, railSupportPos, null, false, score));
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(CandidatePair::score));
        return candidates;
    }

    /**
     * Validates a specific candidate position (used for manual CrossBow mode from crosshair).
     */
    public static CartSetup validateCartPosition(Minecraft client, BlockPos inputPos, Vec3 playerEyes, boolean requiresFire) {
        Vec3 vel = (client != null && client.player != null) ? getEffectivePlayerVelocity(client.player) : Vec3.ZERO;
        return validateCartPosition(client, inputPos, playerEyes, requiresFire, vel);
    }

    public static CartSetup validateCartPosition(Minecraft client, BlockPos inputPos, Vec3 playerEyes, boolean requiresFire, Vec3 playerVelocity) {
        if (client == null || client.level == null || client.player == null || inputPos == null) return null;

        BlockPos cartPos;
        BlockPos railSupportPos;
        if (client.level.getBlockState(inputPos).isSolid()) {
            railSupportPos = inputPos;
            cartPos = inputPos.above();
        } else {
            cartPos = inputPos;
            railSupportPos = inputPos.below();
        }

        if (!isValidRailSupport(client.level, railSupportPos)) return null;

        BlockState cartState = client.level.getBlockState(cartPos);
        BlockState aboveState = client.level.getBlockState(cartPos.above());
        if (!isValidEmptySpace(cartState, true) || !isValidEmptySpace(aboveState, false)) return null;
        if (!cartState.getFluidState().isEmpty() || !aboveState.getFluidState().isEmpty()) return null;

        Vec3 cartCenter = new Vec3(cartPos.getX() + 0.5D, cartPos.getY() + 0.4D, cartPos.getZ() + 0.5D);
        BlockHitResult losCheck = client.level.clip(new ClipContext(
                playerEyes, cartCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player
        ));
        if (losCheck != null && losCheck.getType() == HitResult.Type.BLOCK
                && !losCheck.getBlockPos().equals(railSupportPos) && !losCheck.getBlockPos().equals(cartPos)) {
            return null;
        }

        AABB cartBox = new AABB(
                cartPos.getX() + 0.01D, cartPos.getY() + 0.0625D, cartPos.getZ() + 0.01D,
                cartPos.getX() + 0.99D, cartPos.getY() + 0.7625D, cartPos.getZ() + 0.99D
        );

        if (requiresFire) {
            for (int[] offset : ALL_HORIZONTAL_OFFSETS) {
                int dx = offset[0];
                int dz = offset[1];
                BlockPos firePos = cartPos.offset(dx, 0, dz);
                if (!isValidAdjacentPair(cartPos, firePos)) continue;

                BlockState fireState = client.level.getBlockState(firePos);
                boolean fireAlreadyExists = fireState.is(Blocks.FIRE);
                BlockPos fireSupportPos = firePos.below();

                if (!fireAlreadyExists) {
                    if (!isValidEmptySpace(fireState, false)) continue;
                    if (!fireState.getFluidState().isEmpty()) continue;
                    if (!isValidFireSupport(client.level, fireSupportPos)) continue;
                    if (playerEyes.distanceToSqr(Vec3.atCenterOf(firePos)) > 20.25D) continue;
                }

                // Prune closed corner pinch points for diagonal pairs
                if (isDiagonalAdjacent(cartPos, firePos)) {
                    BlockPos c1 = new BlockPos(cartPos.getX() + dx, cartPos.getY(), cartPos.getZ());
                    BlockPos c2 = new BlockPos(cartPos.getX(), cartPos.getY(), cartPos.getZ() + dz);
                    BlockState s1 = client.level.getBlockState(c1);
                    BlockState s2 = client.level.getBlockState(c2);
                    if (s1.isSolid() && s1.isCollisionShapeFullBlock(client.level, c1)
                            && s2.isSolid() && s2.isCollisionShapeFullBlock(client.level, c2)) {
                        continue;
                    }
                }

                AABB fireBox = new AABB(
                        firePos.getX(), firePos.getY(), firePos.getZ(),
                        firePos.getX() + 1.0D, firePos.getY() + 1.0D, firePos.getZ() + 1.0D
                );

                Optional<Angle> aimAngleOpt = solveTrajectoryAngle(client, playerEyes, playerVelocity, fireBox, cartBox, firePos, cartPos);
                if (aimAngleOpt.isPresent()) {
                    return new CartSetup(cartPos, firePos, aimAngleOpt.get(), railSupportPos, fireAlreadyExists);
                }
            }
            return null;
        } else {
            Optional<Angle> aimAngleOpt = solveDirectTrajectoryAngle(client, playerEyes, playerVelocity, cartBox, cartPos);
            if (aimAngleOpt.isPresent()) {
                return new CartSetup(cartPos, null, aimAngleOpt.get(), railSupportPos, false);
            }
            return null;
        }
    }

    /**
     * Enforces that both positions are non-null and strictly at the exact same Y-level (height).
     */
    public static boolean isSameHeight(BlockPos a, BlockPos b) {
        return a != null && b != null && a.getY() == b.getY();
    }

    /**
     * Checks whether two block positions are horizontally diagonal neighbors at the exact same Y-level.
     * Euclidean distance = sqrt(2) (~1.414), |dx|=1, |dz|=1, dy=0.
     */
    public static boolean isDiagonalAdjacent(BlockPos a, BlockPos b) {
        if (!isSameHeight(a, b)) return false;
        long dx = Math.abs((long) a.getX() - b.getX());
        long dz = Math.abs((long) a.getZ() - b.getZ());
        return dx == 1L && dz == 1L;
    }

    /**
     * Checks whether two block positions form a valid placement pair:
     * Strictly at the same Y-level (cartPos.getY() == firePos.getY()) AND
     * either cardinal adjacent (|dx|+|dz|=1, dy=0) or diagonal adjacent (|dx|=1, |dz|=1, dy=0).
     */
    public static boolean isValidAdjacentPair(BlockPos a, BlockPos b) {
        if (!isSameHeight(a, b)) return false;
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        long distSq = dx * dx + dz * dz;
        return distSq == 1L || distSq == 2L;
    }

    /**
     * Validates whether two block positions are strictly adjacent with Euclidean distance = 1.0.
     * Maintained for backward compatibility with existing tests in Challenger1Oracle.java.
     */
    public static boolean isStrictlyAdjacent(BlockPos a, BlockPos b) {
        if (a == null || b == null) return false;
        long dx = (long) a.getX() - b.getX();
        long dy = (long) a.getY() - b.getY();
        long dz = (long) a.getZ() - b.getZ();
        return (dx * dx + dy * dy + dz * dz) == 1L;
    }

    public static boolean isValidRailSupport(Level level, BlockPos supportPos) {
        if (level == null || supportPos == null) return false;
        BlockState state = level.getBlockState(supportPos);
        return state.isSolid() && state.isCollisionShapeFullBlock(level, supportPos);
    }

    public static boolean isValidFireSupport(Level level, BlockPos supportPos) {
        if (level == null || supportPos == null) return false;
        BlockState state = level.getBlockState(supportPos);
        return state.isSolid() && state.isCollisionShapeFullBlock(level, supportPos);
    }

    // =========================================================================
    // Ballistic Trajectory Solver (v0 = 3.15, drag = 0.99, gravity = 0.05)
    // =========================================================================

    public static Optional<Angle> solveTrajectoryAngle(Vec3 eyePos, AABB fireBox, AABB cartBox) {
        return solveTrajectoryAngle(Minecraft.getInstance(), eyePos, Vec3.ZERO, fireBox, cartBox, null, null);
    }

    public static Optional<Angle> solveTrajectoryAngle(Minecraft client, Vec3 eyePos, AABB fireBox, AABB cartBox) {
        return solveTrajectoryAngle(client, eyePos, Vec3.ZERO, fireBox, cartBox, null, null);
    }

    public static Optional<Angle> solveTrajectoryAngle(Vec3 eyePos, Vec3 playerVelocity, AABB fireBox, AABB cartBox) {
        return solveTrajectoryAngle(Minecraft.getInstance(), eyePos, playerVelocity, fireBox, cartBox, null, null);
    }

    public static Optional<Angle> solveTrajectoryAngle(
            Minecraft client,
            Vec3 eyePos,
            AABB fireBox,
            AABB cartBox,
            BlockPos firePos,
            BlockPos cartPos
    ) {
        return solveTrajectoryAngle(client, eyePos, Vec3.ZERO, fireBox, cartBox, firePos, cartPos);
    }

    public static Optional<Angle> solveTrajectoryAngle(
            Minecraft client,
            Vec3 eyePos,
            Vec3 playerVelocity,
            AABB fireBox,
            AABB cartBox,
            BlockPos firePos,
            BlockPos cartPos
    ) {
        return solveOverhauledTrajectory(client, eyePos, playerVelocity, fireBox, cartBox, firePos, cartPos);
    }

    public static Optional<Angle> solveOverhauledTrajectory(
            Minecraft client,
            Vec3 eyePos,
            Vec3 playerVelocity,
            AABB fireBox,
            AABB cartBox,
            BlockPos firePos,
            BlockPos cartPos
    ) {
        if (eyePos == null || fireBox == null || cartBox == null) return Optional.empty();

        Vec3 fireCenter = fireBox.getCenter();
        Vec3 cartCenter = cartBox.getCenter();
        Vec3 midpoint = fireCenter.add(cartCenter).scale(0.5D);

        // Reference yaw toward midpoint
        float refYaw = calculateYaw(eyePos, midpoint.x, midpoint.z);

        // 1. Calculate exact 2D horizontal yaw intervals for fireBox and cartBox
        double[] fxs = {fireBox.minX, fireBox.maxX};
        double[] fzs = {fireBox.minZ, fireBox.maxZ};
        float fMin = Float.MAX_VALUE, fMax = -Float.MAX_VALUE;
        for (double x : fxs) {
            for (double z : fzs) {
                float rel = (float) Mth.wrapDegrees(calculateYaw(eyePos, x, z) - refYaw);
                if (rel < fMin) fMin = rel;
                if (rel > fMax) fMax = rel;
            }
        }
        if (fMax - fMin >= 180.0F) return Optional.empty();

        double[] cxs = {cartBox.minX, cartBox.maxX};
        double[] czs = {cartBox.minZ, cartBox.maxZ};
        float cMin = Float.MAX_VALUE, cMax = -Float.MAX_VALUE;
        for (double x : cxs) {
            for (double z : czs) {
                float rel = (float) Mth.wrapDegrees(calculateYaw(eyePos, x, z) - refYaw);
                if (rel < cMin) cMin = rel;
                if (rel > cMax) cMax = rel;
            }
        }
        if (cMax - cMin >= 180.0F) return Optional.empty();

        // 2. Overlap of net horizontal flight corridor
        float overlapMin = Math.max(fMin, cMin);
        float overlapMax = Math.min(fMax, cMax);
        if (overlapMin > overlapMax) return Optional.empty();

        // High-resolution quantile sampling of the overlap corridor
        float span = overlapMax - overlapMin;
        float[] sampleWeights = {0.50F, 0.35F, 0.65F, 0.20F, 0.80F, 0.10F, 0.90F, 0.05F, 0.95F};

        for (float w : sampleWeights) {
            float testRelYaw = overlapMin + span * w;
            float netYaw = (float) Mth.wrapDegrees(refYaw + testRelYaw);

            double rad = Math.toRadians(netYaw);
            double nx = -Math.sin(rad);
            double nz = Math.cos(rad);

            double[] fDist = rayBoxDist2D(eyePos.x, eyePos.z, nx, nz, fireBox.minX, fireBox.minZ, fireBox.maxX, fireBox.maxZ);
            double[] cDist = rayBoxDist2D(eyePos.x, eyePos.z, nx, nz, cartBox.minX, cartBox.minZ, cartBox.maxX, cartBox.maxZ);

            if (fDist == null || cDist == null) continue;
            if (fDist[0] > cDist[0]) continue; // Cart struck first! Reject reverse shot

            Optional<Float> pitchOpt = solveOptimalPitchForYaw(client, eyePos, playerVelocity, netYaw, fireBox, cartBox, firePos, cartPos);
            if (pitchOpt.isPresent()) {
                float pitch = pitchOpt.get();
                Angle launchAngle = invertVelocity(netYaw, pitch, playerVelocity);
                SimulationHit sim = simulateTrajectory(client, eyePos, playerVelocity, launchAngle.getYaw(), launchAngle.getPitch(), fireBox, cartBox, firePos, cartPos);
                if (sim.isSuccessful()) {
                    return Optional.of(launchAngle);
                }
            }
        }

        return Optional.empty();
    }

    public static Angle invertVelocity(float netYaw, float netPitch, Vec3 playerVelocity) {
        if (playerVelocity == null || (playerVelocity.x == 0 && playerVelocity.y == 0 && playerVelocity.z == 0)) {
            return new Angle(netYaw, netPitch);
        }

        Vec3 netVec = new Angle(netYaw, netPitch).toVector().scale(ARROW_V0);
        Vec3 launchVec = netVec.subtract(playerVelocity);

        float launchYaw = calculateYaw(Vec3.ZERO, launchVec.x, launchVec.z);
        float launchPitch = calculatePitch(Vec3.ZERO, launchVec);
        return new Angle(launchYaw, launchPitch);
    }

    public static Optional<Float> solveOptimalPitchForYaw(
            Minecraft client,
            Vec3 eyePos,
            Vec3 playerVelocity,
            float netYaw,
            AABB fireBox,
            AABB cartBox,
            BlockPos firePos,
            BlockPos cartPos
    ) {
        Vec3 cartCenter = cartBox.getCenter();
        Vec3 fireCenter = fireBox != null ? fireBox.getCenter() : cartCenter;
        float directCartPitch = calculatePitch(eyePos, cartCenter);
        float directFirePitch = calculatePitch(eyePos, fireCenter);

        float nominalPitchComp = 0.0F;
        if (playerVelocity != null && playerVelocity.y != 0) {
            double ratio = Mth.clamp(playerVelocity.y / ARROW_V0, -0.9, 0.9);
            nominalPitchComp = (float) Math.toDegrees(Math.asin(ratio));
        }

        float pMin = Math.min(directCartPitch, directFirePitch) - 16.0F + nominalPitchComp;
        float pMax = Math.max(directCartPitch, directFirePitch) + 12.0F + nominalPitchComp;
        pMin = Mth.clamp(pMin, -85.0F, 85.0F);
        pMax = Mth.clamp(pMax, -85.0F, 85.0F);

        float step = 0.20F;
        float firstValid = Float.NaN;
        float lastValid = Float.NaN;

        // Stage 1: Coarse sweep
        for (float p = pMin; p <= pMax; p += step) {
            Angle launchAngle = invertVelocity(netYaw, p, playerVelocity);
            SimulationHit sim = simulateTrajectory(client, eyePos, playerVelocity, launchAngle.getYaw(), launchAngle.getPitch(), fireBox, cartBox, firePos, cartPos);
            if (sim.isSuccessful()) {
                if (Float.isNaN(firstValid)) firstValid = p;
                lastValid = p;
            } else if (!Float.isNaN(firstValid)) {
                break;
            }
        }

        if (Float.isNaN(firstValid)) return Optional.empty();

        // Stage 2: Dual boundary bisection refinement (6 iterations each)
        float lowA = firstValid - step;
        float lowB = firstValid;
        for (int i = 0; i < 6; i++) {
            float mid = (lowA + lowB) * 0.5F;
            Angle angle = invertVelocity(netYaw, mid, playerVelocity);
            SimulationHit sim = simulateTrajectory(client, eyePos, playerVelocity, angle.getYaw(), angle.getPitch(), fireBox, cartBox, firePos, cartPos);
            if (sim.isSuccessful()) {
                lowB = mid;
            } else {
                lowA = mid;
            }
        }
        float thetaLow = lowB;

        float highA = lastValid;
        float highB = lastValid + step;
        for (int i = 0; i < 6; i++) {
            float mid = (highA + highB) * 0.5F;
            Angle angle = invertVelocity(netYaw, mid, playerVelocity);
            SimulationHit sim = simulateTrajectory(client, eyePos, playerVelocity, angle.getYaw(), angle.getPitch(), fireBox, cartBox, firePos, cartPos);
            if (sim.isSuccessful()) {
                highA = mid;
            } else {
                highB = mid;
            }
        }
        float thetaHigh = highA;

        // Stage 3: Midpoint centering
        float optimalPitch = (thetaLow + thetaHigh) * 0.5F;
        Angle optAngle = invertVelocity(netYaw, optimalPitch, playerVelocity);
        SimulationHit optSim = simulateTrajectory(client, eyePos, playerVelocity, optAngle.getYaw(), optAngle.getPitch(), fireBox, cartBox, firePos, cartPos);
        if (optSim.isSuccessful()) {
            return Optional.of(optimalPitch);
        }

        return Optional.of((firstValid + lastValid) * 0.5F);
    }

    public static double[] rayBoxDist2D(double ex, double ez, double nx, double nz, double minX, double minZ, double maxX, double maxZ) {
        double tMin = 0.0;
        double tMax = Double.MAX_VALUE;

        if (Math.abs(nx) < 1.0E-9) {
            if (ex < minX || ex > maxX) return null;
        } else {
            double t1 = (minX - ex) / nx;
            double t2 = (maxX - ex) / nx;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax || tMax < 0.0) return null;
        }

        if (Math.abs(nz) < 1.0E-9) {
            if (ez < minZ || ez > maxZ) return null;
        } else {
            double t1 = (minZ - ez) / nz;
            double t2 = (maxZ - ez) / nz;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax || tMax < 0.0) return null;
        }

        return new double[]{tMin, tMax};
    }

    public static Optional<Angle> solveDirectTrajectoryAngle(Minecraft client, Vec3 eyePos, AABB cartBox, BlockPos cartPos) {
        return solveDirectTrajectoryAngle(client, eyePos, Vec3.ZERO, cartBox, cartPos);
    }

    public static Optional<Angle> solveDirectTrajectoryAngle(Minecraft client, Vec3 eyePos, Vec3 playerVelocity, AABB cartBox, BlockPos cartPos) {
        if (eyePos == null || cartBox == null) {
            return Optional.empty();
        }

        Vec3 cartCenter = cartBox.getCenter();
        float refYaw = calculateYaw(eyePos, cartCenter.x, cartCenter.z);

        double[] cxs = {cartBox.minX, cartBox.maxX};
        double[] czs = {cartBox.minZ, cartBox.maxZ};
        float cMin = Float.MAX_VALUE, cMax = -Float.MAX_VALUE;
        for (double x : cxs) {
            for (double z : czs) {
                float rel = (float) Mth.wrapDegrees(calculateYaw(eyePos, x, z) - refYaw);
                if (rel < cMin) cMin = rel;
                if (rel > cMax) cMax = rel;
            }
        }
        if (cMax - cMin >= 180.0F) return Optional.empty();

        float span = cMax - cMin;
        float[] sampleWeights = {0.50F, 0.35F, 0.65F, 0.20F, 0.80F};

        for (float w : sampleWeights) {
            float testRelYaw = cMin + span * w;
            float netYaw = (float) Mth.wrapDegrees(refYaw + testRelYaw);

            Optional<Float> pitchOpt = solveOptimalPitchForYaw(client, eyePos, playerVelocity, netYaw, null, cartBox, null, cartPos);
            if (pitchOpt.isPresent()) {
                Angle launchAngle = invertVelocity(netYaw, pitchOpt.get(), playerVelocity);
                SimulationHit sim = simulateTrajectory(client, eyePos, playerVelocity, launchAngle.getYaw(), launchAngle.getPitch(), null, cartBox, null, cartPos);
                if (sim.isSuccessful()) {
                    return Optional.of(launchAngle);
                }
            }
        }

        return Optional.empty();
    }

    public static YawInterval computeYawInterval(Vec3 eyePos, AABB box, float refYaw) {
        return computeYawInterval(eyePos, Vec3.ZERO, box, refYaw);
    }

    public static YawInterval computeYawInterval(Vec3 eyePos, Vec3 playerVelocity, AABB box, float refYaw) {
        double[] xs = {box.minX, box.maxX};
        double[] zs = {box.minZ, box.maxZ};

        float minRel = Float.MAX_VALUE;
        float maxRel = -Float.MAX_VALUE;

        for (double x : xs) {
            for (double z : zs) {
                float cornerYaw = calculateYaw(eyePos, x, z);
                if (playerVelocity != null && (playerVelocity.x != 0 || playerVelocity.z != 0)) {
                    double dx = x - eyePos.x;
                    double dz = z - eyePos.z;
                    double distH = Math.hypot(dx, dz);
                    if (distH > 1.0E-4) {
                        double vPerp = (playerVelocity.x * dz - playerVelocity.z * dx) / distH;
                        double ratio = Mth.clamp(vPerp / ARROW_V0, -0.9, 0.9);
                        float deltaYaw = (float) Math.toDegrees(Math.asin(ratio));
                        cornerYaw = (float) Mth.wrapDegrees(cornerYaw + deltaYaw);
                    }
                }
                float relYaw = (float) Mth.wrapDegrees(cornerYaw - refYaw);
                if (relYaw < minRel) minRel = relYaw;
                if (relYaw > maxRel) maxRel = relYaw;
            }
        }

        if (minRel > maxRel || (maxRel - minRel) >= 180.0F) {
            return new YawInterval(0, 0, false);
        }

        return new YawInterval(minRel, maxRel, true);
    }

    public static SimulationHit simulateTrajectory(
            Minecraft client,
            Vec3 eyePos,
            float yaw,
            float pitch,
            AABB fireBox,
            AABB cartBox,
            BlockPos firePos,
            BlockPos cartPos
    ) {
        return simulateTrajectory(client, eyePos, Vec3.ZERO, yaw, pitch, fireBox, cartBox, firePos, cartPos);
    }

    public static SimulationHit simulateTrajectory(
            Minecraft client,
            Vec3 eyePos,
            Vec3 playerVelocity,
            float yaw,
            float pitch,
            AABB fireBox,
            AABB cartBox,
            BlockPos firePos,
            BlockPos cartPos
    ) {
        Vec3 launchVelocity = new Angle(yaw, pitch).toVector().scale(ARROW_V0);
        Vec3 velocity = (playerVelocity != null) ? launchVelocity.add(playerVelocity) : launchVelocity;

        Vec3 currentPos = eyePos;
        boolean hitFire = (fireBox == null);
        boolean hitCart = false;
        double fireTime = (fireBox == null) ? 0.0D : Double.MAX_VALUE;
        double cartTime = Double.MAX_VALUE;
        boolean blocked = false;

        for (int tick = 0; tick < MAX_SIMULATION_TICKS; tick++) {
            Vec3 nextPos = currentPos.add(velocity);

            // 1. Continuous intersection check with fireBox
            if (!hitFire && fireBox != null) {
                if (fireBox.contains(currentPos)) {
                    hitFire = true;
                    fireTime = tick;
                } else {
                    Optional<Vec3> clip = fireBox.clip(currentPos, nextPos);
                    if (clip.isPresent()) {
                        hitFire = true;
                        double frac = clip.get().distanceTo(currentPos) / velocity.length();
                        fireTime = tick + frac;
                    }
                }
            }

            // 2. Continuous intersection check with cartBox
            if (!hitCart) {
                if (cartBox.contains(currentPos)) {
                    hitCart = true;
                    cartTime = tick;
                } else {
                    Optional<Vec3> clip = cartBox.clip(currentPos, nextPos);
                    if (clip.isPresent()) {
                        hitCart = true;
                        double frac = clip.get().distanceTo(currentPos) / velocity.length();
                        cartTime = tick + frac;
                    }
                }
            }

            // 3. World block obstruction check
            if (client != null && client.level != null) {
                BlockHitResult blockHit = client.level.clip(new ClipContext(
                        currentPos,
                        nextPos,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        client.player
                ));

                if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
                    BlockPos hitBlockPos = blockHit.getBlockPos();
                    boolean isExpected = (firePos != null && hitBlockPos.equals(firePos))
                            || (cartPos != null && hitBlockPos.equals(cartPos));
                    if (!isExpected) {
                        double hitDist = blockHit.getLocation().distanceTo(currentPos);
                        double frac = hitDist / velocity.length();
                        double blockTime = tick + frac;

                        if (blockTime < cartTime) {
                            blocked = true;
                            break;
                        }
                    }
                }
            }

            if (hitCart) {
                break;
            }

            currentPos = nextPos;
            velocity = new Vec3(
                    velocity.x * ARROW_DRAG,
                    velocity.y * ARROW_DRAG - ARROW_GRAVITY,
                    velocity.z * ARROW_DRAG
            );
        }

        return new SimulationHit(hitFire, hitCart, fireTime, cartTime, blocked);
    }

    public static float calculateYaw(Vec3 from, double toX, double toZ) {
        double dx = toX - from.x;
        double dz = toZ - from.z;
        return (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    public static float calculatePitch(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizDist = Math.hypot(dx, dz);
        return (float) Mth.wrapDegrees(Math.toDegrees(-Math.atan2(dy, horizDist)));
    }

    public static FirePlacement findFirePlacement(Minecraft client, BlockPos cartPos, Vec3 cartCenter, Vec3 playerEyes, Entity target) {
        Vec3 vel = (client != null && client.player != null) ? getEffectivePlayerVelocity(client.player) : Vec3.ZERO;
        return findFirePlacement(client, cartPos, cartCenter, playerEyes, target, vel);
    }

    public static FirePlacement findFirePlacement(Minecraft client, BlockPos cartPos, Vec3 cartCenter, Vec3 playerEyes, Entity target, Vec3 playerVelocity) {
        AABB cartBox = new AABB(
                cartPos.getX() + 0.01D, cartPos.getY() + 0.0625D, cartPos.getZ() + 0.01D,
                cartPos.getX() + 0.99D, cartPos.getY() + 0.7625D, cartPos.getZ() + 0.99D
        );

        for (int[] offset : ALL_HORIZONTAL_OFFSETS) {
            int dx = offset[0];
            int dz = offset[1];
            BlockPos firePos = cartPos.offset(dx, 0, dz);
            if (!isValidAdjacentPair(cartPos, firePos)) continue;

            if (target != null) {
                Vec3 targetFeet = new Vec3(target.getX(), target.getBoundingBox().minY, target.getZ());
                if (Vec3.atCenterOf(firePos).distanceToSqr(targetFeet) > 36.0D) continue;
            }

            BlockState fireState = client.level.getBlockState(firePos);
            boolean fireAlreadyExists = fireState.is(Blocks.FIRE);
            BlockPos fireSupportPos = firePos.below();

            if (!fireAlreadyExists) {
                if (!isValidEmptySpace(fireState, false)) continue;
                if (!fireState.getFluidState().isEmpty()) continue;
                if (!isValidFireSupport(client.level, fireSupportPos)) continue;
                if (playerEyes.distanceToSqr(Vec3.atCenterOf(firePos)) > 20.25D) continue;
            }

            // Prune closed corner pinch points for diagonal pairs
            if (isDiagonalAdjacent(cartPos, firePos)) {
                BlockPos c1 = new BlockPos(cartPos.getX() + dx, cartPos.getY(), cartPos.getZ());
                BlockPos c2 = new BlockPos(cartPos.getX(), cartPos.getY(), cartPos.getZ() + dz);
                BlockState s1 = client.level.getBlockState(c1);
                BlockState s2 = client.level.getBlockState(c2);
                if (s1.isSolid() && s1.isCollisionShapeFullBlock(client.level, c1)
                        && s2.isSolid() && s2.isCollisionShapeFullBlock(client.level, c2)) {
                    continue;
                }
            }

            AABB fireBox = new AABB(
                    firePos.getX(), firePos.getY(), firePos.getZ(),
                    firePos.getX() + 1.0D, firePos.getY() + 1.0D, firePos.getZ() + 1.0D
            );

            Optional<Angle> aimAngleOpt = solveTrajectoryAngle(client, playerEyes, playerVelocity, fireBox, cartBox, firePos, cartPos);
            if (aimAngleOpt.isPresent()) {
                Vec3 aimPoint = cartCenter;
                return new FirePlacement(firePos, aimAngleOpt.get(), aimPoint, fireAlreadyExists);
            }
        }
        return null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public static boolean isValidEmptySpace(BlockState state, boolean allowRail) {
        if (!state.getFluidState().isEmpty()) return false;
        if (state.is(Blocks.COBWEB)) return false;
        if (allowRail && isRailBlock(state.getBlock())) return true;
        return state.canBeReplaced() || state.isAir() || !state.isSolid();
    }

    public static boolean isRailBlock(Block block) {
        return block == Blocks.RAIL || block == Blocks.POWERED_RAIL || block == Blocks.DETECTOR_RAIL || block == Blocks.ACTIVATOR_RAIL;
    }

    public static boolean isPathClearOfPlayers(Minecraft client, Vec3 from, Vec3 to, Entity exclude) {
        if (client == null || client.level == null) return true;
        for (Player player : client.level.players()) {
            if (player != client.player && player != exclude && player.getBoundingBox().clip(from, to).isPresent()) {
                return false;
            }
        }
        return true;
    }

    public static int[][] getApproachDirections(Vec3 direction) {
        return new int[][] {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
    }
}
