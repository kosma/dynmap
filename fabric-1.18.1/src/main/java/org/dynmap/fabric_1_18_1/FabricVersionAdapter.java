package org.dynmap.fabric_1_18_1;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.dynmap.common.chunk.GenericBitStorage;
import org.dynmap.common.chunk.GenericNBTCompound;
import org.dynmap.common.chunk.GenericNBTList;
import org.dynmap.fabric_1_18_1.mixin.BiomeEffectsAccessor;
import org.dynmap.fabric.FabricAdapter;
import org.dynmap.fabric.FabricVersionInterface;

import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class FabricVersionAdapter implements FabricVersionInterface, ModInitializer {

    @Override
    public void onInitialize() {
        FabricAdapter.VERSION_SPECIFIC = new FabricVersionAdapter();
    }

    @Override
    public float[] World_getBrightnessTable(Level world) {
        float brightnessTable[] = new float[16];
        for (int i=0; i<16; i++) {
            brightnessTable[i] = world.dimensionType().brightness(i);
        }
        return brightnessTable;
    }

    @Override
    public GenericNBTCompound ThreadedAnvilChunkStorage_getGenericNbt(ChunkMap tacs, ChunkPos chunkPos) throws IOException {
        return NBTCompound.newOrNull(tacs.read(chunkPos));
    }

    @Override
    public GenericNBTCompound WorldChunk_getGenericNbt(Level world, LevelChunk chunk) {
        return NBTCompound.newOrNull(ChunkSerializer.write((ServerLevel) world, chunk));
    }

    @Override
    public void ServerPlayerEntity_sendMessage(ServerPlayer player, String message) {
        player.sendMessage(new TextComponent(message), Util.NIL_UUID);
    }

    @Override
    public void MinecraftServer_broadcastMessage(MinecraftServer server, String message) {
        server.getPlayerList().broadcastMessage(new TextComponent(message), ChatType.SYSTEM, Util.NIL_UUID);
    }

    @Override
    public void ServerPlayerEntity_sendTitleText(ServerPlayer player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeInTicks, stayTicks, fadeOutTicks));
        if (title != null) {
            player.connection.send(new ClientboundSetTitleTextPacket(new TextComponent(title)));
        }
        if (subtitle != null) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(new TextComponent(subtitle)));
        }
    }

    @Override
    public String World_getDimensionName(Level world) {
        ResourceKey<Level> registryKey = world.dimension();
        if (registryKey == Level.OVERWORLD) {
            return world.getServer().getWorldData().getLevelName();
        } else if (registryKey == Level.END) {
            return "DIM1";
        } else if (registryKey == Level.NETHER) {
            return "DIM-1";
        } else {
            return registryKey.location().getNamespace() + "_" + registryKey.location().getPath();
        }
    }

    @Override
    public int BlockState_getRawId(BlockState blockState) {
        return Block.BLOCK_STATE_REGISTRY.getId(blockState);
    }

    @Override
    public boolean World_isNether(Level world) {
        return world.dimension() == Level.NETHER;
    }

    @Override
    public boolean World_isEnd(Level world) {
        return world.dimension() == Level.END;
    }

    @Override
    public String World_getDefaultTitle(Level world) {
        return world.dimension().location().getPath();
    }

    @Override
    public int World_getMinimumY(Level world) {
        return world.dimensionType().minY();
    }

    @Override
    public int maxWorldHeight() {
        return 4064;
    }

    @Override
    public boolean BlockState_isOpaqueFullCube(BlockState blockState) {
        return blockState.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    @Override
    public Optional<GameProfile> MinecraftServer_getProfileByName(MinecraftServer server, String username) {
        return server.getProfileCache().get(username);
    }

    @Override
    public boolean MinecraftServer_isSinglePlayer(MinecraftServer server) {
        return server.isSingleplayer();
    }

    @Override
    public String MinecraftServer_getSinglePlayerName(MinecraftServer server) {
        return server.getSingleplayerName();
    }

    @Override
    public Registry<Biome> MinecraftServer_getBiomeRegistry(MinecraftServer server) {
        return server.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
    }

    @Override
    public float Biome_getPrecipitation(Biome biome) {
        return biome.getDownfall();
    }

    @Override
    public int Biome_getWaterColor(Biome biome) {
        return ((BiomeEffectsAccessor) biome.getSpecialEffects()).getWaterColor();
    }

    @Override
    public CompletableFuture<ChunkAccess> ChunkHolder_getSavingFuture(ChunkHolder chunk) {
        return chunk.getChunkToSave();
    }

    @Override
    public Iterator<BlockState> getBlockStateIdsIterator() {
        return Block.BLOCK_STATE_REGISTRY.iterator();
    }

    @Override
    public boolean BlockState_isWaterlogged(BlockState blockState) {
        return ((!blockState.getFluidState().isEmpty()) && !(blockState.getBlock() instanceof LiquidBlock));
    }

    @Override
    public String BlockState_getStateName(BlockState blockState) {
        String statename = "";
        for (net.minecraft.world.level.block.state.properties.Property<?> p : blockState.getProperties()) {
            if (statename.length() > 0) {
                statename += ",";
            }
            statename += p.getName() + "=" + blockState.getValue(p).toString();
        }
        return statename;
    }

    @Override
    public BlockPos World_getSpawnPos(Level world) {
        return new BlockPos(world.getLevelData().getXSpawn(),
                world.getLevelData().getYSpawn(),
                world.getLevelData().getZSpawn());
    }

    public static class NBTCompound implements GenericNBTCompound {
        private final CompoundTag obj;
        public static NBTCompound newOrNull(CompoundTag t) {
            return (t != null) ? new NBTCompound(t) : null;
        }
        public NBTCompound(CompoundTag t) {
            this.obj = t;
        }
        @Override
        public Set<String> getAllKeys() {
            return obj.getAllKeys();
        }
        @Override
        public boolean contains(String s) {
            return obj.contains(s);
        }
        @Override
        public boolean contains(String s, int i) {
            return obj.contains(s, i);
        }
        @Override
        public byte getByte(String s) {
            return obj.getByte(s);
        }
        @Override
        public short getShort(String s) {
            return obj.getShort(s);
        }
        @Override
        public int getInt(String s) {
            return obj.getInt(s);
        }
        @Override
        public long getLong(String s) {
            return obj.getLong(s);
        }
        @Override
        public float getFloat(String s) {
            return obj.getFloat(s);
        }
        @Override
        public double getDouble(String s) {
            return obj.getDouble(s);
        }
        @Override
        public String getString(String s) {
            return obj.getString(s);
        }
        @Override
        public byte[] getByteArray(String s) {
            return obj.getByteArray(s);
        }
        @Override
        public int[] getIntArray(String s) {
            return obj.getIntArray(s);
        }
        @Override
        public long[] getLongArray(String s) {
            return obj.getLongArray(s);
        }
        @Override
        public GenericNBTCompound getCompound(String s) {
            return new NBTCompound(obj.getCompound(s));
        }
        @Override
        public GenericNBTList getList(String s, int i) {
            return new NBTList(obj.getList(s, i));
        }
        @Override
        public boolean getBoolean(String s) {
            return obj.getBoolean(s);
        }
        @Override
        public String getAsString(String s) {
            return obj.get(s).getAsString();
        }
        @Override
        public GenericBitStorage makeBitStorage(int bits, int count, long[] data) {
            return new OurBitStorage(bits, count, data);
        }
        public String toString() {
            return obj.toString();
        }
    }

    public static class NBTList implements GenericNBTList {
        private final ListTag obj;
        public NBTList(ListTag t) {
            obj = t;
        }
        @Override
        public int size() {
            return obj.size();
        }
        @Override
        public String getString(int idx) {
            return obj.getString(idx);
        }
        @Override
        public GenericNBTCompound getCompound(int idx) {
            return new NBTCompound(obj.getCompound(idx));
        }
        public String toString() {
            return obj.toString();
        }
    }

    public static class OurBitStorage implements GenericBitStorage {
        private final SimpleBitStorage bs;
        public OurBitStorage(int bits, int count, long[] data) {
            bs = new SimpleBitStorage(bits, count, data);
        }
        @Override
        public int get(int idx) {
            return bs.get(idx);
        }
    }
}
