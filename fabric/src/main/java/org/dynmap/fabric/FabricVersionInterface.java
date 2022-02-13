package org.dynmap.fabric;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.dynmap.common.chunk.GenericNBTCompound;

import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction interface for version-specific Minecraft logic.
 */
public interface FabricVersionInterface {

    float[] World_getBrightnessTable(Level world);

    GenericNBTCompound ThreadedAnvilChunkStorage_getGenericNbt(ChunkMap tacs, ChunkPos chunkPos) throws IOException;

    GenericNBTCompound WorldChunk_getGenericNbt(Level world, LevelChunk chunk);

    void ServerPlayerEntity_sendMessage(ServerPlayer player, String message);

    void MinecraftServer_broadcastMessage(MinecraftServer server, String message);

    void ServerPlayerEntity_sendTitleText(ServerPlayer player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks);

    String World_getDimensionName(Level world);

    int BlockState_getRawId(BlockState blockState);

    boolean World_isNether(Level world);

    boolean World_isEnd(Level world);

    String World_getDefaultTitle(Level world);

    int World_getMinimumY(Level world);

    /* FIXME: Pull this from somewhere in vanilla server? */
    int maxWorldHeight();

    /* FIXME: Pull this from somewhere in vanilla server? */
    boolean BlockState_isOpaqueFullCube(BlockState blockState);

    Optional<GameProfile> MinecraftServer_getProfileByName(MinecraftServer server, String username);

    boolean MinecraftServer_isSinglePlayer(MinecraftServer server);

    String MinecraftServer_getSinglePlayerName(MinecraftServer server);

    Registry<Biome> MinecraftServer_getBiomeRegistry(MinecraftServer server);

    float Biome_getPrecipitation(Biome biome);

    int Biome_getWaterColor(Biome biome);

    CompletableFuture<ChunkAccess> ChunkHolder_getSavingFuture(ChunkHolder chunk);

    /* This interface is needed because even though the STATE_IDS field doesn't
       change the name throughout version, it does change its type (due to IdList
       mapping changing). */
    Iterator<BlockState> getBlockStateIdsIterator();

    boolean BlockState_isWaterlogged(BlockState blockState);

    String BlockState_getStateName(BlockState blockState);

    BlockPos World_getSpawnPos(Level world);

}
