package org.dynmap.fabric;

import org.dynmap.DynmapChunk;
import org.dynmap.Log;
import org.dynmap.common.chunk.GenericChunk;
import org.dynmap.common.chunk.GenericMapChunkCache;

import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkSource;

/**
 * Container for managing chunks - dependent upon using chunk snapshots, since rendering is off server thread
 */
public class FabricMapChunkCache extends GenericMapChunkCache {
    private Level world;
    private ServerChunkCache serverChunkManager;

    /**
     * Construct empty cache
     */
    public FabricMapChunkCache(DynmapPlugin plugin) {
        super(plugin.sscache);
    }

    public void setChunks(FabricWorld dw, List<DynmapChunk> chunks) {
        this.world = dw.getWorld();
        if (dw.isLoaded()) {
            /* Check if world's provider is ServerChunkManager */
            ChunkSource cp = this.world.getChunkSource();

            if (cp instanceof ServerChunkCache) {
                serverChunkManager = (ServerChunkCache) cp;
            } else {
                Log.severe(String.format("Error: world %s has unsupported chunk provider", dw.getName()));
            }
        }
        super.setChunks(dw, chunks);
    }

    protected GenericChunk getLoadedChunk(DynmapChunk chunk) {
        if (!serverChunkManager.hasChunk(chunk.x, chunk.z))
            return null;

        try {
            return parseChunkFromNBT(FabricAdapter.VERSION_SPECIFIC.WorldChunk_getGenericNbt(world, serverChunkManager.getChunk(chunk.x, chunk.z, false)));
        } catch (NullPointerException e) {
            // TODO: find out why this is happening and why it only seems to happen since 1.16.2
            Log.severe("ChunkSerializer.serialize threw a NullPointerException", e);
            return null;
        }
    }

    protected GenericChunk loadChunk(DynmapChunk chunk) {
        try {
            ChunkMap tacs = serverChunkManager.chunkMap;
            ChunkPos chunkPos = new ChunkPos(chunk.x, chunk.z);
            return parseChunkFromNBT(FabricAdapter.VERSION_SPECIFIC.ThreadedAnvilChunkStorage_getGenericNbt(tacs, chunkPos));
        } catch (Exception exc) {
            Log.severe(String.format("Error reading chunk: %s,%d,%d", dw.getName(), chunk.x, chunk.z), exc);
            return null;
        }
    }
}
