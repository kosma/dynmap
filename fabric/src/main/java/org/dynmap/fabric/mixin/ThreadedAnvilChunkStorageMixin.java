package org.dynmap.fabric.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.dynmap.fabric.access.ProtoChunkAccessor;
import org.dynmap.fabric.event.CustomServerChunkEvents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkMap.class, priority = 666 /* fire before Fabric API CHUNK_LOAD event */)
public abstract class ThreadedAnvilChunkStorageMixin {
    @Final
    @Shadow
    ServerLevel world;

    /* Mixin has some issues understanding this method's signature because it's a lambda.
       Nevertheless, it works perfectly if we silence the spurious warning. */
    @SuppressWarnings("target")
    @Inject(
            /* Same place as fabric-lifecycle-events-v1 event CHUNK_LOAD (we will fire before it) */
            method = "method_17227(Lnet/minecraft/server/world/ChunkHolder;Lnet/minecraft/world/chunk/Chunk;)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("TAIL")
    )
    private void onChunkGenerate(ChunkHolder chunkHolder, ChunkAccess protoChunk, CallbackInfoReturnable<ChunkAccess> callbackInfoReturnable) {
        if (((ProtoChunkAccessor)protoChunk).getTouchedByWorldGen()) {
            // We fire the event at TAIL since the chunk is guaranteed to be a WorldChunk then.
            CustomServerChunkEvents.CHUNK_GENERATE.invoker().onChunkGenerate(this.world, (LevelChunk) callbackInfoReturnable.getReturnValue());
        }
    }
}