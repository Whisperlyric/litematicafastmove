package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat.IndexType;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


final class PlacementMesh {
    // 层范围裁剪：沿 axis 只保留区段内相对坐标 [lo, hi]；null = 不裁剪，lo > hi = 整段裁掉
    record Clip(Direction.Axis axis, int lo, int hi) {
        boolean isEmpty() {
            return this.lo > this.hi;
        }
    }

    // hash：顶点数据的内容哈希，与旧网格相同则直接沿用旧 buffer，不重新上传
    record CpuLayer(ChunkSectionLayer layer, OverlayRenderType overlay, ByteBuffer vertices, int indexCount,
                    ByteBuffer indices, IndexType indexType, MeshData.SortState sortState, long hash) {
        void free() {
            MemoryUtil.memFree(this.vertices);
            if (this.indices != null) {
                MemoryUtil.memFree(this.indices);
            }
        }
    }
    // clientEmpty：构建时对应的真实世界区域 (外扩 1 格) 全是空气
    // blockEntities：需要显示的方块实体位置 (构建坐标，BlockPos.asLong)，交给 Litematica 登记渲染
    record CpuSection(long key, BlockPos origin, boolean hasBlocks, boolean clientEmpty, Clip clip, List<CpuLayer> layers, long[] blockEntities, boolean missingOnly) {}

    static final class SortInfo {
        final MeshData.SortState state;
        double lastX, lastY, lastZ;
        boolean pending;

        SortInfo(MeshData.SortState state, double x, double y, double z) {
            this.state = state;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
        }
    }

    record GpuLayer(ChunkSectionLayer layer, OverlayRenderType overlay, GpuBuffer vertexBuffer, int indexCount,
                    GpuBuffer indexBuffer, IndexType indexType, SortInfo sort, int vertexBytes, long hash) {
        boolean sameContent(CpuLayer cpu) {
            return this.layer == cpu.layer() && this.overlay == cpu.overlay() && this.indexCount == cpu.indexCount() &&
                   this.vertexBytes == cpu.vertices().remaining() && this.hash == cpu.hash();
        }

        void close() {
            this.vertexBuffer.close();
            if (this.indexBuffer != null) {
                this.indexBuffer.close();
            }
        }
    }
    // hasBlocks：区段内有投影方块，即使被层范围裁空也保留，范围变化时据此判断是否重建
    record GpuSection(long key, BlockPos origin, boolean hasBlocks, boolean clientEmpty, Clip clip, List<GpuLayer> layers, long[] blockEntities, boolean missingOnly) {
        void close() {
            for (GpuLayer layer : this.layers) {
                layer.close();
            }
        }

        // 被新构建沿用的layer已转交给新区段，不能关闭
        void closeExcept(Set<GpuLayer> keep) {
            for (GpuLayer layer : this.layers) {
                if (!keep.contains(layer)) layer.close();
            }
        }
    }


    static final class BuiltPlacementBuffer {
        final int generation;
        final BlockPos buildOrigin;
        final Set<Long> keys;
        final List<CpuSection> pending;
        final List<GpuSection> uploaded = new ArrayList<>();
        // 从当前网格沿用的 layer (按引用比较)；应用前它们仍归旧区段所有
        final Set<GpuLayer> borrowed = Collections.newSetFromMap(new IdentityHashMap<>());
        int nextToUpload;

        BuiltPlacementBuffer(int generation, BlockPos buildOrigin, Set<Long> keys, List<CpuSection> pending) {
            this.generation = generation;
            this.buildOrigin = buildOrigin;
            this.keys = keys;
            this.pending = pending;
        }

        boolean isFull() {
            return this.keys == null;
        }

        boolean isFullyUploaded() {
            return this.nextToUpload >= this.pending.size();
        }

        void discard() {
            for (int i = this.nextToUpload; i < this.pending.size(); i++) {
                this.pending.get(i).layers().forEach(CpuLayer::free);
            }
            this.nextToUpload = this.pending.size();
            this.uploaded.forEach(section -> section.closeExcept(this.borrowed));
            this.uploaded.clear();
            this.borrowed.clear();
        }
    }

    volatile int requestedGeneration;
    volatile BlockPos targetBuildOrigin;
    volatile PlacementBlockAccessor targetAccessor;
    // 当前生效网格中各区段的方块实体 (构建坐标)，供 Litematica 区块重建时登记
    final Map<Long, long[]> blockEntities = new ConcurrentHashMap<>();
    volatile BlockPos blockEntityBuildOrigin;

    //RenderThread only
    final Map<Long, GpuSection> active = new HashMap<>();
    int activeGeneration;
    BlockPos activeBuildOrigin;
    // 一次性构建 (小于阈值的投影)：整份建好后一次替换
    BuiltPlacementBuffer uploadingFull;
    final List<BuiltPlacementBuffer> deferredPartials = new ArrayList<>();
    final ArrayDeque<BuiltPlacementBuffer> uploadingPartials = new ArrayDeque<>();
    // 流式构建：只记录本代区段的包围盒 (构建坐标下的区段坐标)，按 chunk 列现算，不预生成索引；
    // inFlight 是已提交未落地的区段 (带认领时间，构建线程丢了批次时靠它超时回收)，refreshed 是已按当前原点建好的区段
    int streamGeneration;
    boolean streaming;
    int streamMinX, streamMinZ, streamMaxX, streamMaxZ;
    int streamMinY, streamMaxY;
    final Set<Long> inFlight = new HashSet<>();
    final Map<Long, Long> inFlightSince = new HashMap<>();
    final Set<Long> dirty = new HashSet<>();
    final Set<Long> refreshed = new HashSet<>();
    // 建出过内容的区段：如果它的 GPU 网格后来丢了 (被误关/批次交错)，自愈时据此补建
    final Set<Long> contentSections = new HashSet<>();
    // 每帧复用的提交缓冲，避免在渲染线程上反复分配
    final Set<Long> pumpBatch = new HashSet<>();
    // 构建失败只报一次，避免失败批次每帧刷屏
    volatile boolean failureLogged;
    // 移动中只重建玩家周边这一圈；记录上次刷新时的原点与时间，没动就不重复刷
    BlockPos coreOrigin;
    long coreMillis;
    // 拖动中：按"全部缺失"重建 (客户端世界当作空的)，与 Litematica 缺失方块完全一致
    volatile boolean missingMode;
    // 距离环扫描的游标：每帧从上一帧停下的环继续，保证外圈也能轮到
    int ringCursor;

    boolean isStreaming() {
        return this.streaming;
    }

    void stopStream() {
        this.streaming = false;
        this.inFlight.clear();
        this.inFlightSince.clear();
        this.dirty.clear();
        this.refreshed.clear();
        this.contentSections.clear();
        this.pumpBatch.clear();
    }

    void beginStream(int generation, int minX, int minZ, int maxX, int maxZ, int minY, int maxY) {
        this.streamGeneration = generation;
        this.streaming = true;
        this.streamMinX = minX;
        this.streamMinZ = minZ;
        this.streamMaxX = maxX;
        this.streamMaxZ = maxZ;
        this.streamMinY = minY;
        this.streamMaxY = maxY;
        this.inFlight.clear();
        this.inFlightSince.clear();
        this.dirty.clear();
        this.refreshed.clear();
        this.contentSections.clear();
        this.ringCursor = 0;
        this.failureLogged = false;
    }

    void claim(long key) {
        this.inFlight.add(key);
        this.inFlightSince.put(key, System.nanoTime());
    }

    void release(Set<Long> keys) {
        if (keys == null) return;
        for (Long key : keys) {
            this.inFlight.remove(key);
            this.inFlightSince.remove(key);
        }
    }

    // 批次被异常或失效的 generation 丢掉时没人回调，认领会一直挂着；超时回收，下一轮重新提交
    void expireClaims(long nowNanos, long timeoutNanos) {
        if (this.inFlightSince.isEmpty()) return;
        var iterator = this.inFlightSince.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (nowNanos - entry.getValue() > timeoutNanos) {
                this.inFlight.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    void close() {
        closeSome(Integer.MAX_VALUE);
    }

    /**
     * 分批关闭：一次关掉几千个 GPU buffer 会卡在驱动上 (关渲染/关模组时最明显)。
     * 返回 true 表示这个网格已经关干净
     */
    boolean closeSome(int maxSections) {
        var iterator = this.active.values().iterator();
        int closed = 0;
        while (iterator.hasNext() && closed < maxSections) {
            iterator.next().close();
            iterator.remove();
            closed++;
        }
        if (!this.active.isEmpty()) return false;

        if (this.uploadingFull != null) {
            this.uploadingFull.discard();
            this.uploadingFull = null;
        }
        this.deferredPartials.forEach(BuiltPlacementBuffer::discard);
        this.deferredPartials.clear();
        this.uploadingPartials.forEach(BuiltPlacementBuffer::discard);
        this.uploadingPartials.clear();
        this.stopStream();
        this.blockEntities.clear();
        return true;
    }
}
