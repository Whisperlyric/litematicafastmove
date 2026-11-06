package ml.mypals.litematicafastmove.mesh;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


public final class PlacementMeshManager {
    private static final Map<SchematicPlacement, PlacementMesh> MESHES = new ConcurrentHashMap<>();
    private static final Set<Long> DIRTY_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> DIRTY_BLOCKS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> DIRTY_SCHEMATIC_BLOCKS = ConcurrentHashMap.newKeySet();
    private static final Set<SchematicPlacement> DIRTY_PLACEMENTS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static int streamFrames;
    private static double lastCameraX, lastCameraZ;
    private static int idleFrames;


    private PlacementMeshManager() {}

    static Map<SchematicPlacement, PlacementMesh> meshes() {
        return MESHES;
    }

    public static boolean isDetached(SchematicPlacement placement) {
        return FastMoveConfig.isRenderTakenOver(placement);
    }

    public static void requestBuild(SchematicPlacement placement) {
        if (placement == null) return;
        if (!FastMoveConfig.isFastMoveEnabled() || !isDetached(placement)) {
            dispose(placement);
            return;
        }

        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (world == null || clientLevel == null) return; // 由 tick() 稍后重试

        PlacementMesh mesh = MESHES.computeIfAbsent(placement, p -> new PlacementMesh());
        int generation = GENERATION.incrementAndGet();
        PlacementBlockAccessor accessor = new PlacementBlockAccessor(placement);
        BlockPos buildOrigin = placement.getOrigin();

        mesh.targetAccessor = accessor;
        mesh.targetBuildOrigin = buildOrigin;
        mesh.requestedGeneration = generation;

        // 默认所有接管的投影都流式；打开"仅大投影流式"后，体积不足的投影恢复一次性构建
        boolean stream = !FastMoveConfig.streamOnlyLarge() || FastMoveConfig.shouldDeferCommit(placement);
        if (stream) {
            mesh.activeGeneration = generation;
            mesh.activeBuildOrigin = buildOrigin;
            mesh.beginStream(generation,
                    accessor.minPos.getX() >> 4, accessor.minPos.getZ() >> 4,
                    accessor.maxPos.getX() >> 4, accessor.maxPos.getZ() >> 4,
                    accessor.minPos.getY() >> 4, accessor.maxPos.getY() >> 4);
            return;
        }

        mesh.stopStream();
        MeshSectionBuilder.submit(placement, mesh, generation, accessor, buildOrigin, null, BlockPos.ZERO, world, clientLevel,
                built -> MeshUploader.enqueueBuilt(mesh, built));
        MeshDebugGizmos.fullBuild(accessor, BlockPos.ZERO, false);
    }

    /**
     * 标记需要重建的区段；流式网格由渲染线程的队列按每帧上限提交
     */
    static void markSectionsDirty(PlacementMesh mesh, Set<Long> keys) {
        mesh.dirty.addAll(keys);
    }

    // 一次性构建的投影 (小于阈值) 直接提交，不经过流式队列
    static void requestPartialBuild(SchematicPlacement placement, PlacementMesh mesh, Set<Long> keys) {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        ClientLevel clientLevel = Minecraft.getInstance().level;
        PlacementBlockAccessor accessor = mesh.targetAccessor;
        BlockPos buildOrigin = mesh.targetBuildOrigin;
        if (world == null || clientLevel == null || accessor == null || buildOrigin == null) return;

        BlockPos clientDelta = placement.getOrigin().subtract(buildOrigin);
        MeshSectionBuilder.submit(placement, mesh, mesh.requestedGeneration, accessor, buildOrigin, keys, clientDelta, world, clientLevel,
                built -> MeshUploader.enqueueBuilt(mesh, built));
        MeshDebugGizmos.sectionsQueued(accessor, keys, clientDelta);
    }

    public static void rebuildAllOf(LitematicaSchematic schematic) {
        for (SchematicPlacement placement : new ArrayList<>(MESHES.keySet())) {
            if (placement.getSchematic() == schematic) {
                requestBuild(placement);
            }
        }
    }

    public static void dispose(SchematicPlacement placement) {
        PlacementMesh mesh = MESHES.remove(placement);
        MoveInterpolator.forget(placement);
        if (mesh != null) {
            mesh.requestedGeneration = -1;
            MeshUploader.enqueueClose(mesh);
        }
    }

    public static void disposeAll() {
        for (SchematicPlacement placement : new ArrayList<>(MESHES.keySet())) {
            dispose(placement);
        }
    }

    /**
     * 周期性同步，补建缺失的网格 (例如进入世界时WorldSchematic尚未创建)
     * 释放已移除或者不再被接管的投影的网格
     */
    public static void tick() {
        var manager = DataManager.getSchematicPlacementManager();
        if (manager == null) return;

        Collection<SchematicPlacement> all = manager.getAllSchematicsPlacements();
        for (SchematicPlacement placement : new ArrayList<>(MESHES.keySet())) {
            if (!all.contains(placement)) {
                dispose(placement);
            }
        }

        if (!FastMoveConfig.isFastMoveEnabled()) {
            disposeAll();
            return;
        }

        for (SchematicPlacement placement : all) {
            boolean detached = isDetached(placement);
            boolean hasMesh = MESHES.containsKey(placement);
            if (detached && !hasMesh) {
                requestBuild(placement);
            } else if (!detached && hasMesh) {
                dispose(placement);
            }
        }
    }

    /**
     * 真实世界单个方块变化时只重建包含它及相邻一格的区段
     */
    public static void markBlockDirty(BlockPos pos) {
        if (!MESHES.isEmpty()) {
            DIRTY_BLOCKS.add(pos.asLong());
        }
    }

    /** A block of the schematic itself was edited: its section is rebuilt even where the real world is all air. */
    public static void markSchematicBlockDirty(BlockPos pos) {
        if (!MESHES.isEmpty()) {
            DIRTY_SCHEMATIC_BLOCKS.add(pos.asLong());
        }
    }

    /**
     * 真实世界区块加载，整列都变化
     */
    public static void markChunkDirty(int chunkX, int chunkZ) {
        if (!MESHES.isEmpty()) {
            DIRTY_CHUNKS.add(ChunkPos.pack(chunkX, chunkZ));
        }
    }

    /**
     * 投影移到新位置：所有区段都要按新位置重新对照，但只作废"已建好"标记，
     * 视距内的区段由流式队列在后续帧里逐步重建
     */
    public static void markPlacementDirty(SchematicPlacement placement) {
        if (placement != null && MESHES.containsKey(placement)) {
            DIRTY_PLACEMENTS.add(placement);
        }
    }

    /**
     * 主线程每 tick 只做队列搬运 (收批次、关网格)，GPU 上传留给渲染那一帧，避免一帧里传两次
     */
    public static void processUploads() {
        MeshUploader.processQueues(false);
    }

    public static void processDirty() {
        if (DIRTY_CHUNKS.isEmpty() && DIRTY_BLOCKS.isEmpty() && DIRTY_SCHEMATIC_BLOCKS.isEmpty() && DIRTY_PLACEMENTS.isEmpty()) return;

        List<Long> chunks = drain(DIRTY_CHUNKS);
        List<Long> blocks = drain(DIRTY_BLOCKS);
        List<Long> schematicBlocks = drain(DIRTY_SCHEMATIC_BLOCKS);
        List<SchematicPlacement> placements = drain(DIRTY_PLACEMENTS);

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : MESHES.entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            PlacementBlockAccessor accessor = mesh.targetAccessor;
            BlockPos buildOrigin = mesh.targetBuildOrigin;
            if (accessor == null || buildOrigin == null) continue;

            BlockPos delta = placement.getOrigin().subtract(buildOrigin);
            Set<Long> keys = new HashSet<>();
            if (placements.contains(placement)) {
                if (mesh.isStreaming()) {
                    // 大投影移动后：只作废"已建好"标记，视距内的区段由流式队列按新原点逐步重建
                    mesh.refreshed.clear();
                } else {
                    // 小投影保持原版：整份重新对照
                    keys.addAll(allSections(accessor));
                }
            }

            addSectionsInChunks(accessor, chunks, delta, keys);
            addSectionsAroundBlocks(accessor, blocks, delta, keys);
            skipStillEmptySections(mesh, accessor, keys, delta);
            addSectionsAroundBlocks(accessor, schematicBlocks, delta, keys);

            if (!keys.isEmpty()) {
                if (mesh.isStreaming()) {
                    invalidateSections(mesh, delta, keys);
                } else {
                    requestPartialBuild(placement, mesh, keys);
                }
            }
        }
    }

    /**
     * 脏区段在视距内才立刻重建；视距外只作废"已建好"标记，等走近了由流式队列补建。
     * 玩家走动时新区块会不断加载，如果连远处的都重建，就会持续吃 CPU 掉帧
     */
    private static void invalidateSections(PlacementMesh mesh, BlockPos delta, Set<Long> keys) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        int distance = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 2) * 16;
        double maxDistanceSq = (double) distance * distance;
        for (long key : keys) {
            mesh.refreshed.remove(key);
            double dx = (SectionPos.x(key) << 4) + 8 + delta.getX() - camera.x;
            double dy = (SectionPos.y(key) << 4) + 8 + delta.getY() - camera.y;
            double dz = (SectionPos.z(key) << 4) + 8 + delta.getZ() - camera.z;
            if (dx * dx + dy * dy + dz * dz <= maxDistanceSq) {
                mesh.dirty.add(key);
            }
        }
    }

    private static Set<Long> allSections(PlacementBlockAccessor accessor) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;
        Set<Long> keys = new HashSet<>();
        for (int sx = min.getX() >> 4; sx <= max.getX() >> 4; sx++) {
            for (int sz = min.getZ() >> 4; sz <= max.getZ() >> 4; sz++) {
                for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
                    keys.add(SectionPos.asLong(sx, sy, sz));
                }
            }
        }
        return keys;
    }

    /**
     * 每帧推进一次流式构建：先补脏区段，再按与相机的距离由近到远补齐视距内的区段。
     * 只遍历视距内的 chunk 列，开销与可见范围成正比，不随投影整体大小增长
     */
    static void stream(double cameraX, double cameraY, double cameraZ) {
        if (MESHES.isEmpty()) return;
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (world == null || clientLevel == null) return;

        boolean evictFrame = ++streamFrames % 30 == 0;
        boolean repairFrame = streamFrames % 120 == 0;

        // 站着不动时没人会看到掉帧，可以放宽提交预算把初进世界补快一点；
        // 走动/拖动时用正常预算，避免和渲染抢 CPU
        double movedX = cameraX - lastCameraX;
        double movedZ = cameraZ - lastCameraZ;
        lastCameraX = cameraX;
        lastCameraZ = cameraZ;
        idleFrames = movedX * movedX + movedZ * movedZ < 0.0625 ? Math.min(idleFrames + 1, 100) : 0;
        int maxBudget = FastMoveConfig.streamSectionsPerFrame();
        if (idleFrames > 20) {
            maxBudget = Math.min(512, maxBudget * 2);
        }

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : MESHES.entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            if (!placement.isEnabled() || !placement.isRenderingEnabled()) continue;
            if (!mesh.isStreaming()) continue; // 小投影一次性建，不参与流式/逐出
            if (repairFrame) {
                repairMissingSections(mesh);
            }
            pump(placement, mesh, world, clientLevel, cameraX, cameraY, cameraZ, maxBudget);
            refreshMoveCore(placement, mesh, cameraX, cameraZ);
            if (evictFrame) {
                evict(placement, mesh, cameraX, cameraY, cameraZ);
            }
        }
    }

    /**
     * 自愈：某区段建出过内容，但它的 GPU 网格不在 active 里 (被误关、批次交错、上传失败后丢失)，
     * 就重新标脏。没有这一步时这种区段会一直缺着，只能靠 F3+A 或重载资源包才能补上
     */
    private static void repairMissingSections(PlacementMesh mesh) {
        if (mesh.contentSections.isEmpty()) return;
        for (long key : mesh.contentSections) {
            if (mesh.active.containsKey(key) || mesh.inFlight.contains(key)) continue;
            mesh.refreshed.remove(key);
            mesh.dirty.add(key);
        }
    }

    /**
     * 移动中不重建整份投影，但玩家身边这一圈要跟得上：把它标脏交给流式队列，
     * 松手后 {@link #markPlacementDirty} 会把整份都标脏，由队列按视距逐步补齐
     */
    private static void refreshMoveCore(SchematicPlacement placement, PlacementMesh mesh, double cameraX, double cameraZ) {
        if (PendingMoveTracker.get(placement) == null) return;

        int radius = FastMoveConfig.moveCoreChunks();
        if (radius <= 0) return;

        BlockPos origin = placement.getOrigin();
        long now = System.currentTimeMillis();
        if (origin.equals(mesh.coreOrigin) && now - mesh.coreMillis < 500) return;
        mesh.coreOrigin = origin;
        mesh.coreMillis = now;

        BlockPos offset = placement.getOrigin().subtract(mesh.targetBuildOrigin);
        int centerX = SectionPos.blockToSectionCoord((int) Math.floor(cameraX)) - (offset.getX() >> 4);
        int centerZ = SectionPos.blockToSectionCoord((int) Math.floor(cameraZ)) - (offset.getZ() >> 4);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                if (cx < mesh.streamMinX || cx > mesh.streamMaxX || cz < mesh.streamMinZ || cz > mesh.streamMaxZ) continue;
                for (int sy = mesh.streamMinY; sy <= mesh.streamMaxY; sy++) {
                    long key = SectionPos.asLong(cx, sy, cz);
                    mesh.refreshed.remove(key);
                    mesh.dirty.add(key);
                }
            }
        }
    }

    private static void pump(SchematicPlacement placement, PlacementMesh mesh, WorldSchematic world, ClientLevel clientLevel,
                             double cameraX, double cameraY, double cameraZ, int maxBudget) {
        if (!mesh.isStreaming() || mesh.streamGeneration != mesh.requestedGeneration) return;
        if (mesh.targetAccessor == null || mesh.targetBuildOrigin == null) return;
        mesh.expireClaims(System.nanoTime(), 5_000_000_000L);

        int inFlightLimit = FastMoveConfig.streamInFlightSections();
        int budget = maxBudget;
        int residentLimit = FastMoveConfig.residentSectionLimit();
        if (inFlightLimit > 0) {
            budget = Math.min(budget, inFlightLimit - mesh.inFlight.size());
        }
        if (budget <= 0) return;

        Set<Long> batch = mesh.pumpBatch;
        batch.clear();

        // 脏区段最多占一半预算：走动时新区块会不断标脏，如果让它们吃掉整份预算，
        // 距离环扫描永远轮不到，外面那一圈就永远建不出来
        int dirtyBudget = Math.max(4, budget / 2);
        int dirtyUsed = 0;
        var dirty = mesh.dirty.iterator();
        while (dirty.hasNext() && budget > 0 && dirtyUsed < dirtyBudget) {
            long key = dirty.next();
            dirty.remove();
            if (!claim(mesh, key, residentLimit)) continue;
            batch.add(key);
            budget--;
            dirtyUsed++;
        }

        if (budget > 0) {
            int radius = Minecraft.getInstance().options.getEffectiveRenderDistance() + 2;
            // 区段坐标是构建坐标，相机是世界坐标：投影平移过之后必须减掉 delta，
            // 否则扫的是错位的区块列 (缺的那一块正好偏出一个移动距离)
            BlockPos offset = placement.getOrigin().subtract(mesh.targetBuildOrigin);
            int centerX = SectionPos.blockToSectionCoord((int) Math.floor(cameraX)) - (offset.getX() >> 4);
            int centerZ = SectionPos.blockToSectionCoord((int) Math.floor(cameraZ)) - (offset.getZ() >> 4);
            // 从上一帧停下的那一环继续，转满一圈再从头开始：近处仍然优先，
            // 但每一环都会轮到，不会被近处的活饿死
            int start = Math.floorMod(mesh.ringCursor, radius + 1);
            for (int i = 0; i <= radius && budget > 0; i++) {
                int r = start + i;
                if (r > radius) {
                    r -= radius + 1;
                }
                budget -= collectRing(mesh, centerX, centerZ, r, batch, budget, residentLimit);
            }
            mesh.ringCursor = start + 1;
        }

        if (batch.isEmpty()) return;

        BlockPos clientDelta = placement.getOrigin().subtract(mesh.targetBuildOrigin);
        MeshSectionBuilder.submit(placement, mesh, mesh.requestedGeneration, mesh.targetAccessor, mesh.targetBuildOrigin,
                batch, clientDelta, world, clientLevel, built -> MeshUploader.enqueueBuilt(mesh, built));
        MeshDebugGizmos.sectionsQueued(mesh.targetAccessor, batch, clientDelta);
        batch.clear();
    }

    // 一圈 chunk 列 (正方形环)，从最近的一圈开始填，预算用完就停
    private static int collectRing(PlacementMesh mesh, int centerX, int centerZ, int r, Set<Long> batch, int budget, int residentLimit) {
        int added = 0;
        for (int dx = -r; dx <= r && added < budget; dx++) {
            added += collect(mesh, centerX + dx, centerZ - r, batch, budget - added, residentLimit);
            if (r > 0) {
                added += collect(mesh, centerX + dx, centerZ + r, batch, budget - added, residentLimit);
            }
        }
        for (int dz = -r + 1; dz <= r - 1 && added < budget; dz++) {
            added += collect(mesh, centerX - r, centerZ + dz, batch, budget - added, residentLimit);
            added += collect(mesh, centerX + r, centerZ + dz, batch, budget - added, residentLimit);
        }
        return added;
    }

    private static int collect(PlacementMesh mesh, int chunkX, int chunkZ, Set<Long> batch, int budget, int residentLimit) {
        if (budget <= 0) return 0;
        if (chunkX < mesh.streamMinX || chunkX > mesh.streamMaxX) return 0;
        if (chunkZ < mesh.streamMinZ || chunkZ > mesh.streamMaxZ) return 0;

        int added = 0;
        for (int sy = mesh.streamMinY; sy <= mesh.streamMaxY && added < budget; sy++) {
            long key = SectionPos.asLong(chunkX, sy, chunkZ);
            if (!claim(mesh, key, residentLimit)) continue;
            batch.add(key);
            added++;
        }
        return added;
    }

    /** 区段是否可以提交：没建好、不在构建中、常驻上限还够 (已在常驻里的区段重建不受上限限制) */
    private static boolean claim(PlacementMesh mesh, long key, int residentLimit) {
        // refreshed 覆盖空区段：它们建完不会进 active，但同样不该反复重建
        if (mesh.refreshed.contains(key) || mesh.inFlight.contains(key)) return false;
        if (residentLimit > 0 && !mesh.active.containsKey(key) && mesh.active.size() >= residentLimit) return false;
        mesh.claim(key);
        return true;
    }

    // 视距 1.5 倍以外的已建区段关掉，把常驻显存约束在视距内 (带滞回，避免边界抖动)
    private static void evict(SchematicPlacement placement, PlacementMesh mesh, double cameraX, double cameraY, double cameraZ) {
        if (mesh.active.isEmpty() || mesh.activeBuildOrigin == null) return;
        if (!mesh.uploadingPartials.isEmpty() || !mesh.deferredPartials.isEmpty()) return; // 上传中的批次可能引用这些 layer

        int distance = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 2) * 16;
        double evictDistanceSq = (double) distance * distance * 2.25;
        BlockPos delta = placement.getOrigin().subtract(mesh.activeBuildOrigin);

        var iterator = mesh.active.entrySet().iterator();
        int closed = 0;
        long deadline = System.nanoTime() + 2_000_000L;
        // 分批 + 限时：一帧里关掉太多 buffer 会卡在驱动上
        while (iterator.hasNext() && closed < 64 && System.nanoTime() < deadline) {
            var entry = iterator.next();
            PlacementMesh.GpuSection section = entry.getValue();
            if (mesh.inFlight.contains(entry.getKey())) continue;
            double dx = section.origin().getX() + delta.getX() + 8 - cameraX;
            double dy = section.origin().getY() + delta.getY() + 8 - cameraY;
            double dz = section.origin().getZ() + delta.getZ() + 8 - cameraZ;
            if (dx * dx + dy * dy + dz * dz <= evictDistanceSq) continue;
            iterator.remove();
            mesh.refreshed.remove(entry.getKey());
            section.close();
            closed++;
        }
    }

    private static <T> List<T> drain(Set<T> set) {
        List<T> drained = new ArrayList<>(set);
        drained.forEach(set::remove);
        return drained;
    }

    private static void addSectionsInChunks(PlacementBlockAccessor accessor, List<Long> chunks, BlockPos delta, Set<Long> keys) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;

        for (long packed : chunks) {
            ChunkPos chunk = ChunkPos.unpack(packed);
            int bx0 = Math.max((chunk.x() << 4) - delta.getX(), min.getX());
            int bx1 = Math.min((chunk.x() << 4) + 15 - delta.getX(), max.getX());
            int bz0 = Math.max((chunk.z() << 4) - delta.getZ(), min.getZ());
            int bz1 = Math.min((chunk.z() << 4) + 15 - delta.getZ(), max.getZ());
            if (bx0 > bx1 || bz0 > bz1) continue;

            for (int sx = bx0 >> 4; sx <= bx1 >> 4; sx++) {
                for (int sz = bz0 >> 4; sz <= bz1 >> 4; sz++) {
                    for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
                        keys.add(SectionPos.asLong(sx, sy, sz));
                    }
                }
            }
        }
    }

    // overlay 判断边线与面时会看相邻一格的真实方块，所以变化位置周围一格所在的区段都要重建
    private static void addSectionsAroundBlocks(PlacementBlockAccessor accessor, List<Long> blocks, BlockPos delta, Set<Long> keys) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;

        for (long packed : blocks) {
            int bx = BlockPos.getX(packed) - delta.getX();
            int by = BlockPos.getY(packed) - delta.getY();
            int bz = BlockPos.getZ(packed) - delta.getZ();
            int x0 = Math.max(bx - 1, min.getX()), x1 = Math.min(bx + 1, max.getX());
            int y0 = Math.max(by - 1, min.getY()), y1 = Math.min(by + 1, max.getY());
            int z0 = Math.max(bz - 1, min.getZ()), z1 = Math.min(bz + 1, max.getZ());
            if (x0 > x1 || y0 > y1 || z0 > z1) continue;

            for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                        keys.add(SectionPos.asLong(sx, sy, sz));
                    }
                }
            }
        }
    }

    /**
     * 区段的网格只取决于投影方块和对应的真实方块；构建时与现在对应的真实区域都是纯空气时，
     * 重建结果完全相同 (例如在空中挪动投影选位置)，不必重建
     */
    private static void skipStillEmptySections(PlacementMesh mesh, PlacementBlockAccessor accessor, Set<Long> keys, BlockPos delta) {
        if (keys.isEmpty() || mesh.activeBuildOrigin == null || !mesh.activeBuildOrigin.equals(mesh.targetBuildOrigin)) return;

        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) return;

        List<Long> skipped = new ArrayList<>();
        keys.removeIf(key -> {
            PlacementMesh.GpuSection section = mesh.active.get(key);
            boolean wasEmpty = section == null || section.clientEmpty();
            boolean skip = wasEmpty && ClientRegions.isSectionEmpty(clientLevel, accessor.minPos, accessor.maxPos, key, delta);
            if (skip) skipped.add(key);
            return skip;
        });
        MeshDebugGizmos.sectionsSkipped(accessor, skipped, delta);
    }
}
