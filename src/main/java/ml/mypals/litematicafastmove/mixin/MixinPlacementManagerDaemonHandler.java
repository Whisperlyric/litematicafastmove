package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlacementManagerDaemonHandler.class, remap = false)
public abstract class MixinPlacementManagerDaemonHandler {

    /**
     * 提交移动时原版会给投影覆盖的每个区块调用 markChunkForRebuild，而它每次都要扫一遍延迟任务队列，
     * 于是代价变成"区块数 × 队列长度"（队列本身又是这些区块的任务，接近 O(n²)）。批量标记期间
     * 跳过这次清理：任务照常入队，由守护线程按自己的预算执行，重复任务只是重做一次同样的活
     */
    @Inject(method = "removeAllTasksFor(II)V", at = @At("HEAD"), cancellable = true)
    private void litematicafastmove$skipBulkQueueScan(int chunkX, int chunkZ, CallbackInfo ci) {
        if (PendingMoveTracker.inBulkRebuild()) {
            ci.cancel();
        }
    }
}
