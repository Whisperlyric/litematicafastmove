package ml.mypals.litematicafastmove.sync;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingMoveTracker {
    public static final class MoveState {
        public final BlockPos baseOrigin;
        public final Set<ChunkPos> baseChunks;
        public volatile long lastMoveMs;

        MoveState(BlockPos baseOrigin, Set<ChunkPos> baseChunks) {
            this.baseOrigin = baseOrigin;
            this.baseChunks = baseChunks;
        }
    }

    private static final Map<SchematicPlacement, MoveState> STATES = new ConcurrentHashMap<>();
    private static final Set<Long> SKIPPED_REBUILDS = ConcurrentHashMap.newKeySet();
    private static volatile LongSet frozenChunks = LongSets.EMPTY_SET;
    private static volatile List<ShiftedBox> shiftedBoxes = List.of();
    // 批量标记区块重建期间跳过守护线程的任务队列清理：每个区块都扫一遍队列是 O(区块数 × 队列长度)
    private static final ThreadLocal<int[]> BULK_REBUILD = ThreadLocal.withInitial(() -> new int[1]);

    public static void beginBulkRebuild() {
        BULK_REBUILD.get()[0]++;
    }

    public static void endBulkRebuild() {
        int[] depth = BULK_REBUILD.get();
        if (depth[0] > 0) depth[0]--;
    }

    public static boolean inBulkRebuild() {
        return BULK_REBUILD.get()[0] > 0;
    }

    private record ShiftedBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                              SchematicPlacement placement, BlockPos baseOrigin) {
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public static boolean hasAnyPending() {
        return !STATES.isEmpty();
    }

    public static MoveState get(SchematicPlacement placement) {
        return STATES.get(placement);
    }

    public static void onMoved(SchematicPlacement placement, BlockPos baseOrigin, Set<ChunkPos> baseChunks) {
        MoveState state = STATES.get(placement);
        if (state == null) {
            // 冻结集合只取决于"哪些投影在移动"和它们的基准区块，与移动了多少次无关：
            // 只在开始移动时重建一次。以前每次 nudge 都重建，Set.copyOf 在大区块数下是 O(n²)
            state = new MoveState(baseOrigin, baseChunks);
            state.lastMoveMs = System.currentTimeMillis();
            STATES.put(placement, state);
            rebuildFrozen();
            return;
        }
        state.lastMoveMs = System.currentTimeMillis();
    }

    public static MoveState remove(SchematicPlacement placement) {
        MoveState state = STATES.remove(placement);
        if (state != null) {
            rebuildFrozen();
        }
        return state;
    }

    public static List<SchematicPlacement> getPending() {
        return new ArrayList<>(STATES.keySet());
    }

    public static List<SchematicPlacement> getDueForCommit(long now, long settleMs) {
        List<SchematicPlacement> due = new ArrayList<>();
        STATES.forEach((placement, state) -> {
            if (now - state.lastMoveMs >= settleMs) due.add(placement);
        });
        return due;
    }

    public static void clear() {
        STATES.clear();
        SKIPPED_REBUILDS.clear();
        frozenChunks = LongSets.EMPTY_SET;
        shiftedBoxes = List.of();
    }

    public static boolean hasAnyObjectShift() {
        return !shiftedBoxes.isEmpty() || MoveInterpolator.hasLagging();
    }

    public static Vec3 getObjectShift(double x, double y, double z) {
        List<ShiftedBox> boxes = shiftedBoxes;
        if (boxes.isEmpty()) return MoveInterpolator.getLagShiftAt(x, y, z);

        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        for (ShiftedBox box : boxes) {
            if (box.contains(bx, by, bz)) {
                BlockPos delta = box.placement().getOrigin().subtract(box.baseOrigin());
                return MoveInterpolator.getLag(box.placement()).add(delta.getX(), delta.getY(), delta.getZ());
            }
        }
        return MoveInterpolator.getLagShiftAt(x, y, z);
    }

    public static boolean isFrozenChunk(int chunkX, int chunkZ) {
        LongSet frozen = frozenChunks;
        return !frozen.isEmpty() && frozen.contains(ChunkPos.pack(chunkX, chunkZ));
    }

    public static void markSkippedRebuild(int chunkX, int chunkZ) {
        SKIPPED_REBUILDS.add(ChunkPos.pack(chunkX, chunkZ));
    }

    public static List<ChunkPos> drainUnfrozenSkipped() {
        List<ChunkPos> result = new ArrayList<>();
        for (Long key : SKIPPED_REBUILDS) {
            ChunkPos pos = ChunkPos.unpack(key);
            if (!isFrozenChunk(pos.x(), pos.z()) && SKIPPED_REBUILDS.remove(key)) {
                result.add(pos);
            }
        }
        return result;
    }

    private static void rebuildFrozen() {
        LongSet frozen = new LongOpenHashSet();
        List<ShiftedBox> boxes = new ArrayList<>();

        STATES.forEach((placement, state) -> {
            BlockPos delta = placement.getOrigin().subtract(state.baseOrigin);

            for (var box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
                assert box.getPos1() != null;
                BlockPos min = box.getPos1().subtract(delta);
                assert box.getPos2() != null;
                BlockPos max = box.getPos2().subtract(delta);
                boxes.add(new ShiftedBox(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()),
                        Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()),
                        placement, state.baseOrigin));
            }

            for (ChunkPos pos : state.baseChunks) {
                frozen.add(pos.pack());
            }
        });

        // 直接换引用：LongOpenHashSet 的 contains 是 O(1)。Set.copyOf 会把整份集合转成
        // ImmutableCollections.SetN，构造时逐个线性探测，区块多的时候是 O(n²)
        frozenChunks = frozen;
        shiftedBoxes = List.copyOf(boxes);
    }
}
