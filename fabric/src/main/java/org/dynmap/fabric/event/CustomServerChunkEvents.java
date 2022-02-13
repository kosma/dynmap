package org.dynmap.fabric.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public class CustomServerChunkEvents {
    public static Event<ChunkGenerate> CHUNK_GENERATE = EventFactory.createArrayBacked(ChunkGenerate.class,
            (listeners) -> (world, chunk) -> {
                for (ChunkGenerate callback : listeners) {
                    callback.onChunkGenerate(world, chunk);
                }
            }
    );

    @FunctionalInterface
    public interface ChunkGenerate {
        void onChunkGenerate(ServerLevel world, LevelChunk chunk);
    }
}
