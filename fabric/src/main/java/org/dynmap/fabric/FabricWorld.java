package org.dynmap.fabric;

import org.dynmap.DynmapChunk;
import org.dynmap.DynmapLocation;
import org.dynmap.DynmapWorld;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.Polygon;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

public class FabricWorld extends DynmapWorld {
    // TODO: Store this relative to World saves for integrated server
    public static final String SAVED_WORLDS_FILE = "fabricworlds.yml";

    private final DynmapPlugin plugin;
    private Level world;
    private final boolean skylight;
    private final boolean isnether;
    private final boolean istheend;
    private final String env;
    private DynmapLocation spawnloc = new DynmapLocation();
    private static int maxWorldHeight = FabricAdapter.VERSION_SPECIFIC.maxWorldHeight();

    public static int getMaxWorldHeight() {
        return maxWorldHeight;
    }

    public static void setMaxWorldHeight(int h) {
        maxWorldHeight = h;
    }

    public static String getWorldName(DynmapPlugin plugin, Level w) {
        return FabricAdapter.VERSION_SPECIFIC.World_getDimensionName(w);
    }
    
    public void updateWorld(Level w) {
        this.updateWorldHeights(w.getMaxBuildHeight(), FabricAdapter.VERSION_SPECIFIC.World_getMinimumY(w), w.getSeaLevel());
    }

    public FabricWorld(DynmapPlugin plugin, Level w) {
        this(plugin, getWorldName(plugin, w), w.getMaxBuildHeight(),
                w.getSeaLevel(),
                FabricAdapter.VERSION_SPECIFIC.World_isNether(w),
                FabricAdapter.VERSION_SPECIFIC.World_isEnd(w),
                FabricAdapter.VERSION_SPECIFIC.World_getDefaultTitle(w),
                FabricAdapter.VERSION_SPECIFIC.World_getMinimumY(w));
        setWorldLoaded(w);
    }

    public FabricWorld(DynmapPlugin plugin, String name, int height, int sealevel, boolean nether, boolean the_end, String deftitle, int miny) {
        super(name, (height > maxWorldHeight) ? maxWorldHeight : height, sealevel, miny);
        this.plugin = plugin;
        world = null;
        setTitle(deftitle);
        isnether = nether;
        istheend = the_end;
        skylight = !(isnether || istheend);

        if (isnether) {
            env = "nether";
        } else if (istheend) {
            env = "the_end";
        } else {
            env = "normal";
        }

    }

    /* Test if world is nether */
    @Override
    public boolean isNether() {
        return isnether;
    }

    public boolean isTheEnd() {
        return istheend;
    }

    /* Get world spawn location */
    @Override
    public DynmapLocation getSpawnLocation() {
        if (world != null) {
            BlockPos pos = FabricAdapter.VERSION_SPECIFIC.World_getSpawnPos(world);
            spawnloc.x = pos.getX();
            spawnloc.y = pos.getY();
            spawnloc.z = pos.getZ();
            spawnloc.world = this.getName();
        }
        return spawnloc;
    }

    /* Get world time */
    @Override
    public long getTime() {
        if (world != null)
            return world.getDayTime();
        else
            return -1;
    }

    /* World is storming */
    @Override
    public boolean hasStorm() {
        if (world != null)
            return world.isRaining();
        else
            return false;
    }

    /* World is thundering */
    @Override
    public boolean isThundering() {
        if (world != null)
            return world.isThundering();
        else
            return false;
    }

    /* World is loaded */
    @Override
    public boolean isLoaded() {
        return (world != null);
    }

    /* Set world to unloaded */
    @Override
    public void setWorldUnloaded() {
        getSpawnLocation();
        world = null;
    }

    /* Set world to loaded */
    public void setWorldLoaded(Level w) {
        world = w;
        this.sealevel = w.getSeaLevel();   // Read actual current sealevel from world
        // Update lighting table
        float[] brightnessTable = FabricAdapter.VERSION_SPECIFIC.World_getBrightnessTable(w);
        for (int i = 0; i < 16; i++) {
            this.setBrightnessTableEntry(i, brightnessTable[i]);
        }
    }

    /* Get light level of block */
    @Override
    public int getLightLevel(int x, int y, int z) {
        if (world != null)
            return world.getMaxLocalRawBrightness(new BlockPos(x, y, z));
        else
            return -1;
    }

    /* Get highest Y coord of given location */
    @Override
    public int getHighestBlockYAt(int x, int z) {
        if (world != null) {
            return world.getChunk(x >> 4, z >> 4).getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING).getFirstAvailable(x & 15, z & 15);
        } else
            return -1;
    }

    /* Test if sky light level is requestable */
    @Override
    public boolean canGetSkyLightLevel() {
        return skylight;
    }

    /* Return sky light level */
    @Override
    public int getSkyLightLevel(int x, int y, int z) {
        if (world != null) {
            return world.getBrightness(LightLayer.SKY, new BlockPos(x, y, z));
        } else
            return -1;
    }

    /**
     * Get world environment ID (lower case - normal, the_end, nether)
     */
    @Override
    public String getEnvironment() {
        return env;
    }

    /**
     * Get map chunk cache for world
     */
    @Override
    public MapChunkCache getChunkCache(List<DynmapChunk> chunks) {
        if (world != null) {
            FabricMapChunkCache c = new FabricMapChunkCache(plugin);
            c.setChunks(this, chunks);
            return c;
        }
        return null;
    }

    public Level getWorld() {
        return world;
    }

    @Override
    public Polygon getWorldBorder() {
        if (world != null) {
            WorldBorder wb = world.getWorldBorder();
            if ((wb != null) && (wb.getSize() < 5.9E7)) {
                Polygon p = new Polygon();
                p.addVertex(wb.getMinX(), wb.getMinZ());
                p.addVertex(wb.getMinX(), wb.getMaxZ());
                p.addVertex(wb.getMaxX(), wb.getMaxZ());
                p.addVertex(wb.getMaxX(), wb.getMinZ());
                return p;
            }
        }
        return null;
    }
}
