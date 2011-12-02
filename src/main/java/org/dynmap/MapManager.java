package org.dynmap;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.command.CommandSender;
import org.dynmap.DynmapPlugin.CompassMode;
import org.dynmap.DynmapWorld.AutoGenerateOption;
import org.dynmap.debug.Debug;
import org.dynmap.hdmap.HDMapManager;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.NewMapChunkCache;
import org.dynmap.utils.SnapshotCache;
import org.dynmap.utils.TileFlags;

public class MapManager {
    public AsynchronousQueue<MapTile> tileQueue;

    private static final int DEFAULT_CHUNKS_PER_TICK = 200;
    private static final int DEFAULT_ZOOMOUT_PERIOD = 60;
    public List<DynmapWorld> worlds = new ArrayList<DynmapWorld>();
    public Map<String, DynmapWorld> worldsLookup = new HashMap<String, DynmapWorld>();
    private BukkitScheduler scheduler;
    private DynmapPlugin plug_in;
    private long timeslice_int = 0; /* In milliseconds */
    private int max_chunk_loads_per_tick = DEFAULT_CHUNKS_PER_TICK;
    private int parallelrendercnt = 0;
    private int progressinterval = 100;
    private boolean saverestorepending = true;
    private boolean hideores = false;
    
    private boolean pauseupdaterenders = false;
    private boolean pausefullrenders = false;
    
    private int zoomout_period = DEFAULT_ZOOMOUT_PERIOD;	/* Zoom-out tile processing period, in seconds */
    /* Which fullrenders are active */
    private HashMap<String, FullWorldRenderState> active_renders = new HashMap<String, FullWorldRenderState>();

    /* Chunk load handling */
    private Object loadlock = new Object();
    private int chunks_in_cur_tick = 0;
    private long cur_tick;

    /* Chunk load performance numbers */
    AtomicInteger chunk_caches_created = new AtomicInteger(0);
    AtomicInteger chunk_caches_attempted = new AtomicInteger(0);
    AtomicLong total_chunk_cache_loadtime_ns = new AtomicLong(0);
    AtomicInteger chunks_read = new AtomicInteger(0);;
    AtomicInteger chunks_attempted = new AtomicInteger(0);
    AtomicLong total_loadtime_ns = new AtomicLong(0L);
    AtomicLong total_exceptions = new AtomicLong(0L);

    /* Tile hash manager */
    public TileHashManager hashman;
    /* lock for our data structures */
    public static final Object lock = new Object();

    public static MapManager mapman;    /* Our singleton */
    public HDMapManager hdmapman;
    public SnapshotCache sscache;
    
    /* Thread pool for processing renders */
    private DynmapScheduledThreadPoolExecutor render_pool;
    private static final int POOL_SIZE = 3;    

    private HashMap<String, MapStats> mapstats = new HashMap<String, MapStats>();
    
    private static class MapStats {
        int loggedcnt;
        int renderedcnt;
        int updatedcnt;
        int transparentcnt;
    }
    
    private HashMap<String, TriggerStats> trigstats = new HashMap<String, TriggerStats>();
    
    private static class TriggerStats {
        long callsmade;
        long callswithtiles;
        long tilesqueued;
    }

    public DynmapWorld getWorld(String name) {
        DynmapWorld world = worldsLookup.get(name);
        return world;
    }
    
    public Collection<DynmapWorld> getWorlds() {
        return worlds;
    }

    private static class OurThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.setName("Dynmap Render Thread");
            return t;
        }
    }
    
    private class DynmapScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
        DynmapScheduledThreadPoolExecutor() {
            super(POOL_SIZE + parallelrendercnt);
            this.setThreadFactory(new OurThreadFactory());
            /* Set shutdown policy to stop everything */
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        protected void afterExecute(Runnable r, Throwable x) {
            if(r instanceof FullWorldRenderState) {
                ((FullWorldRenderState)r).cleanup();
            }
            if(x != null) {
                Log.severe("Exception during render job: " + r);
                x.printStackTrace();
            }
        }
        @Override
        public void execute(final Runnable r) {
            try {
                super.execute(new Runnable() {
                    public void run() {
                        try {
                            r.run();
                        } catch (Exception x) {
                            Log.severe("Exception during render job: " + r);
                            x.printStackTrace();                        
                        }
                    }
                });
            } catch (RejectedExecutionException rxe) {  /* Pool shutdown - nominal for reload or unload */
            }    
        }
        @Override
        public ScheduledFuture<?> schedule(final Runnable command, long delay, TimeUnit unit) {
            try {
                return super.schedule(new Runnable() {
                    public void run() {
                        try {
                            command.run();
                        } catch (Exception x) {
                            Log.severe("Exception during render job: " + command);
                            x.printStackTrace();                        
                        }
                    }
                }, delay, unit);
            } catch (RejectedExecutionException rxe) {
                return null;    /* Pool shut down when we reload or unload */
            }
        }
    }
    /* This always runs on render pool threads - no bukkit calls from here */ 
    private class FullWorldRenderState implements Runnable {
        DynmapWorld world;    /* Which world are we rendering */
        Location loc;        
        int    map_index = -1;    /* Which map are we on */
        MapType map;
        TileFlags found = null;
        TileFlags rendered = null;
        LinkedList<MapTile> renderQueue = null;
        MapTile tile0 = null;
        int rendercnt = 0;
        CommandSender sender;
        long timeaccum;
        HashSet<MapType> renderedmaps = new HashSet<MapType>();
        String activemaps;
        int activemapcnt;
        /* Min and max limits for chunk coords (for radius limit) */
        int cxmin, cxmax, czmin, czmax;
        String rendertype;
        boolean cancelled;
        String mapname;
        AtomicLong total_render_ns = new AtomicLong(0L);
        AtomicInteger rendercalls = new AtomicInteger(0);

        /* Full world, all maps render */
        FullWorldRenderState(DynmapWorld dworld, Location l, CommandSender sender, String mapname) {
            this(dworld, l, sender, mapname, -1);
            rendertype = "Full render";
        }
        
        /* Full world, all maps render, with optional render radius */
        FullWorldRenderState(DynmapWorld dworld, Location l, CommandSender sender, String mapname, int radius) {
            world = dworld;
            loc = l;
            found = new TileFlags();
            rendered = new TileFlags();
            renderQueue = new LinkedList<MapTile>();
            this.sender = sender;
            if(radius < 0) {
                cxmin = czmin = Integer.MIN_VALUE;
                cxmax = czmax = Integer.MAX_VALUE;
                rendertype = "Full render";
            }
            else {
                cxmin = (l.getBlockX() - radius)>>4;
                czmin = (l.getBlockZ() - radius)>>4;
                cxmax = (l.getBlockX() + radius+15)>>4;
                czmax = (l.getBlockZ() + radius+15)>>4;
                rendertype = "Radius render";
            }
            this.mapname = mapname;
        }

        /* Single tile render - used for incremental renders */
        FullWorldRenderState(MapTile t) {
            world = getWorld(t.getWorld().getName());
            tile0 = t;
            cxmin = czmin = Integer.MIN_VALUE;
            cxmax = czmax = Integer.MAX_VALUE;
        }

        FullWorldRenderState(ConfigurationNode n) throws Exception {
            String w = n.getString("world", "");
            world = getWorld(w);
            if(world == null) throw new Exception();
            loc = new Location(world.world, n.getDouble("locX", 0.0), n.getDouble("locY", 0.0), n.getDouble("locZ", 0.0));
            String m = n.getString("map","");
            map_index = n.getInteger("mapindex", -1);
            map = world.maps.get(map_index);
            if((map == null) || (map.getName().equals(m) == false)) throw new Exception();
            found = new TileFlags();
            List<String> sl = n.getStrings("found", null);
            if(sl != null)
                found.load(sl);
            rendered = new TileFlags();
            sl = n.getStrings("rendered", null);
            if(sl != null)
                rendered.load(sl);
            renderQueue = new LinkedList<MapTile>();
            List<ConfigurationNode> tl = n.getNodes("queue");
            if(tl != null) {
                for(ConfigurationNode cn : tl) {
                    MapTile mt = MapTile.restoreTile(world, cn);
                    if(mt != null) {
                        renderQueue.add(mt);
                    }
                }
            }
            rendercnt = n.getInteger("count", 0);
            timeaccum = n.getInteger("timeaccum", 0);
            renderedmaps = new HashSet<MapType>();
            sl = n.getStrings("renderedmaps", null);
            if(sl != null) {
                for(String s : sl) {
                    for(int i = 0; i < world.maps.size(); i++) {
                        MapType mt = world.maps.get(i);
                        if(mt.getName().equals(s)) {
                            renderedmaps.add(mt);
                            break;
                        }
                    }
                }
                if(sl.size() > renderedmaps.size()) {   /* Missed one or more? */
                    throw new Exception();
                }
            }
            activemaps = n.getString("activemaps", "");
            activemapcnt = n.getInteger("activemapcnt", 0);
            cxmin = n.getInteger("cxmin", 0);
            cxmax = n.getInteger("cxmax", 0);
            czmin = n.getInteger("czmin", 0);
            czmax = n.getInteger("czmax", 0);
            rendertype = n.getString("rendertype", "");
            mapname = n.getString("mapname", null);
            sender = null;
        }
        
        public HashMap<String,Object> saveState() {
            HashMap<String,Object> v = new HashMap<String,Object>();
            
            v.put("world", world.world.getName());
            v.put("locX", loc.getBlockX());
            v.put("locY", loc.getBlockY());
            v.put("locZ", loc.getBlockZ());
            v.put("mapindex", map_index);
            v.put("map", map.getName());
            v.put("found", found.save());
            v.put("rendered", rendered.save());
            LinkedList<ConfigurationNode> queue = new LinkedList<ConfigurationNode>();
            for(MapTile tq : renderQueue) {
                ConfigurationNode n = tq.saveTile();
                if(n != null)
                    queue.add(n);
            }
            v.put("queue", queue);
            v.put("count", rendercnt);
            v.put("timeaccum", timeaccum);
            LinkedList<String> rmaps = new LinkedList<String>();
            for(MapType mt : renderedmaps) {
                rmaps.add(mt.getName());
            }
            v.put("renderedmaps", rmaps);
            v.put("activemaps", activemaps);
            v.put("activemapcnt", activemapcnt);
            v.put("cxmin", cxmin);
            v.put("cxmax", cxmax);
            v.put("czmin", czmin);
            v.put("czmax", czmax);
            v.put("rendertype", rendertype);
            if(mapname != null)
                v.put("mapname", mapname);
            
            return v;
        }
        
        public String toString() {
            return "world=" + world.world.getName() + ", map=" + map;
        }
        
        public void cleanup() {
            if(tile0 == null) {
                synchronized(lock) {
                    active_renders.remove(world.world.getName());
                }
            }
            else {
                tileQueue.done(tile0);            	
            }
        }
        public void run() {
            long tstart = System.currentTimeMillis();
            MapTile tile = null;
            List<MapTile> tileset = null;
            
            if(cancelled) {
            	cleanup();
            	return;
            }
            if(tile0 == null) {    /* Not single tile render */
                if(pausefullrenders) {    /* Update renders are paused? */
                    scheduleDelayedJob(this, 20*5); /* Delay 5 seconds and retry */
                    return;
                }
                /* If render queue is empty, start next map */
                if(renderQueue.isEmpty()) {
                    if(map_index >= 0) { /* Finished a map? */
                        double msecpertile = (double)timeaccum / (double)((rendercnt>0)?rendercnt:1)/(double)activemapcnt;
                        double rendtime = total_render_ns.doubleValue() * 0.000001 / rendercalls.get();
                        if(activemapcnt > 1) 
                            sendMessage(rendertype + " of maps [" + activemaps + "] of '" +
                               world.world.getName() + "' completed - " + rendercnt + " tiles rendered each (" + String.format("%.2f", msecpertile) + " msec/map-tile, "
                               + rendtime +" msec per render)");
                        else
                            sendMessage(rendertype + " of map '" + activemaps + "' of '" +
                                 world.world.getName() + "' completed - " + rendercnt + " tiles rendered (" + String.format("%.2f", msecpertile) + " msec/tile. "
                                 + rendtime +" msec per render)");

                    }                	
                    found.clear();
                    rendered.clear();
                    rendercnt = 0;
                    timeaccum = 0;
                    total_render_ns.set(0);
                    rendercalls.set(0);
                    /* Advance to next unrendered map */
                    while(map_index < world.maps.size()) {
                        map_index++;    /* Move to next one */
                        if(map_index >= world.maps.size()) break;
                        /* If single map render, see if this is our target */
                        if(mapname != null) {
                            if(world.maps.get(map_index).getName().equals(mapname)) {
                                break;
                            }
                        }
                        else {
                            if(renderedmaps.contains(world.maps.get(map_index)) == false)
                                break;
                        }
                    }
                    if(map_index >= world.maps.size()) {    /* Last one done? */
                        sendMessage(rendertype + " of '" + world.world.getName() + "' finished.");
                        cleanup();
                        return;
                    }
                    map = world.maps.get(map_index);
                    List<String> activemaplist = map.getMapNamesSharingRender(world);
                    /* Build active map list */
                    activemaps = "";
                    if(mapname != null) {
                        activemaps = mapname;
                        activemapcnt = 1;
                    }
                    else {
                        activemapcnt = 0;
                        for(String n : activemaplist) {
                            if(activemaps.length() > 0)
                                activemaps += ",";
                            activemaps += n;
                            activemapcnt++;
                        }
                    }
                    /* Mark all the concurrently rendering maps rendered */
                    renderedmaps.addAll(map.getMapsSharingRender(world));

                    /* Now, prime the render queue */
                    for (MapTile mt : map.getTiles(loc)) {
                        if (!found.getFlag(mt.tileOrdinalX(), mt.tileOrdinalY())) {
                            found.setFlag(mt.tileOrdinalX(), mt.tileOrdinalY(), true);
                            renderQueue.add(mt);
                        }
                    }
                    /* Add spawn location too (helps with some worlds where 0,64,0 may not be generated */
                    Location sloc = world.world.getSpawnLocation();
                    for (MapTile mt : map.getTiles(sloc)) {
                        if (!found.getFlag(mt.tileOrdinalX(), mt.tileOrdinalY())) {
                            found.setFlag(mt.tileOrdinalX(), mt.tileOrdinalY(), true);
                            renderQueue.add(mt);
                        }
                    }
                    if(world.seedloc != null) {
                        for(Location seed : world.seedloc) {
                            for (MapTile mt : map.getTiles(seed)) {
                                if (!found.getFlag(mt.tileOrdinalX(),mt.tileOrdinalY())) {
                                    found.setFlag(mt.tileOrdinalX(),mt.tileOrdinalY(), true);
                                    renderQueue.add(mt);
                                }
                            }
                        }
                    }
                }
                if(parallelrendercnt > 1) { /* Doing parallel renders? */
                    tileset = new ArrayList<MapTile>();
                    for(int i = 0; i < parallelrendercnt; i++) {
                        tile = renderQueue.pollFirst();
                        if(tile != null)
                            tileset.add(tile);
                    }
                }
                else {
                    tile = renderQueue.pollFirst();
                }
            }
            else {    /* Else, single tile render */
                if(pauseupdaterenders) {
                    scheduleDelayedJob(this, 5*20); /* Retry after 5 seconds */
                    return;
                }
                tile = tile0;
            }
            World w = world.world;
 
            boolean notdone = true;
            
            if(tileset != null) {
                long save_timeaccum = timeaccum;
                List<Future<Boolean>> rslt = new ArrayList<Future<Boolean>>();
                final int cnt = tileset.size();
                for(int i = 1; i < cnt; i++) {   /* Do all but first on other threads */
                    final MapTile mt = tileset.get(i);
                    if((mapman != null) && (mapman.render_pool != null)) {
                        final long ts = tstart;
                        Future<Boolean> future = mapman.render_pool.submit(new Callable<Boolean>() {
                            public Boolean call() {
                                return processTile(mt, mt.world.world, ts, cnt);
                            }
                        });
                        rslt.add(future);
                    }
                }
                /* Now, do our render (first one) */
                notdone = processTile(tileset.get(0), w, tstart, cnt);
                /* Now, join with others */
                for(int i = 0; i < rslt.size(); i++) {
                    try {
                        notdone = notdone && rslt.get(i).get();
                    } catch (ExecutionException xx) {
                        Log.severe(xx);
                        notdone = false;
                    } catch (InterruptedException ix) {
                        notdone = false;
                    }
                }
                timeaccum = save_timeaccum + System.currentTimeMillis() - tstart;
            }
            else {
                notdone = processTile(tile, w, tstart, 1);
            }
            
            if(notdone) {
                if(tile0 == null) {    /* fullrender */
                    long tend = System.currentTimeMillis();
                    if(timeslice_int > (tend-tstart)) { /* We were fast enough */
                        scheduleDelayedJob(this, timeslice_int - (tend-tstart));
                    }
                    else {  /* Schedule to run ASAP */
                        scheduleDelayedJob(this, 0);
                    }
                }
                else {
                    cleanup();
                }
            }
            else {
                cleanup();
            }
        }

        private boolean processTile(MapTile tile, World w, long tstart, int parallelcnt) {
            /* Get list of chunks required for tile */
            List<DynmapChunk> requiredChunks = tile.getRequiredChunks();
            /* If we are doing radius limit render, see if any are inside limits */
            if(cxmin != Integer.MIN_VALUE) {
                boolean good = false;
                for(DynmapChunk c : requiredChunks) {
                    if((c.x >= cxmin) && (c.x <= cxmax) && (c.z >= czmin) && (c.z <= czmax)) {
                        good = true;
                        break;
                    }
                }
                if(!good) requiredChunks = Collections.emptyList();
            }
            /* Fetch chunk cache from server thread */
            long clt0 = System.nanoTime();
            MapChunkCache cache = createMapChunkCache(world, requiredChunks, tile.isBlockTypeDataNeeded(), 
                                                      tile.isHightestBlockYDataNeeded(), tile.isBiomeDataNeeded(), 
                                                      tile.isRawBiomeDataNeeded());
            total_chunk_cache_loadtime_ns.addAndGet(System.nanoTime() - clt0);
            chunk_caches_attempted.incrementAndGet();
            if(cache == null) {
                return false; /* Cancelled/aborted */
            }
            /* Update stats */
            chunk_caches_created.incrementAndGet();
            chunks_read.addAndGet(cache.getChunksLoaded());
            chunks_attempted.addAndGet(cache.getChunkLoadsAttempted());
            total_loadtime_ns.addAndGet(cache.getTotalRuntimeNanos());
            total_exceptions.addAndGet(cache.getExceptionCount());
            if(tile0 != null) {    /* Single tile? */
                if(cache.isEmpty() == false)
                    tile.render(cache, null);
            }
            else {
        		/* Remove tile from tile queue, since we're processing it already */
            	tileQueue.remove(tile);
                /* Switch to not checking if rendered tile is blank - breaks us on skylands, where tiles can be nominally blank - just work off chunk cache empty */
                if (cache.isEmpty() == false) {
                    long rt0 = System.nanoTime();
                    tile.render(cache, mapname);
                    total_render_ns.addAndGet(System.nanoTime()-rt0);
                    rendercalls.incrementAndGet();
                    synchronized(lock) {
//                        found.setFlag(tile.tileOrdinalX(),tile.tileOrdinalY(),false);
                        rendered.setFlag(tile.tileOrdinalX(), tile.tileOrdinalY(), true);
                        for (MapTile adjTile : map.getAdjecentTiles(tile)) {
                            if (!found.getFlag(adjTile.tileOrdinalX(),adjTile.tileOrdinalY()) && 
                                !rendered.getFlag(adjTile.tileOrdinalX(),adjTile.tileOrdinalY())) {
                                found.setFlag(adjTile.tileOrdinalX(), adjTile.tileOrdinalY(), true);
                                renderQueue.add(adjTile);
                            }
                        }
                    }
                }
                synchronized(lock) {
//                    found.setFlag(tile.tileOrdinalX(), tile.tileOrdinalY(), false);
                    if(!cache.isEmpty()) {
                        rendercnt++;
                        timeaccum += System.currentTimeMillis() - tstart;
                        if((rendercnt % progressinterval) == 0) {
                            double rendtime = total_render_ns.doubleValue() * 0.000001 / rendercalls.get();
                            double msecpertile = (double)timeaccum / (double)rendercnt / (double)activemapcnt;
                            if(activemapcnt > 1) 
                                sendMessage(rendertype + " of maps [" + activemaps + "] of '" +
                                               w.getName() + "' in progress - " + rendercnt + " tiles rendered each (" + String.format("%.2f", msecpertile) + " msec/map-tile, "
                                               + rendtime +" msec per render)");
                            else
                                sendMessage(rendertype + " of map '" + activemaps + "' of '" +
                                               w.getName() + "' in progress - " + rendercnt + " tiles rendered (" + String.format("%.2f", msecpertile) + " msec/tile. "
                                               + rendtime +" msec per render)");
                        }
                    }
                }
            }
            /* And unload what we loaded */
            cache.unloadChunks();
            
            return true;
        }
        
        public void cancelRender() {
        	cancelled = true;
        }
        
        public void sendMessage(String msg) {
            if(sender != null) {
                sender.sendMessage(msg);
            }
            else {
                Log.info(msg);
            }
        }
    }

    private class CheckWorldTimes implements Runnable {
        public void run() {
            Future<Integer> f = scheduler.callSyncMethod(plug_in, new Callable<Integer>() {
                public Integer call() throws Exception {
                    for(DynmapWorld w : worlds) {
                        int new_servertime = (int)(w.world.getTime() % 24000);
                        /* Check if we went from night to day */
                        boolean wasday = w.servertime >= 0 && w.servertime < 13700;
                        boolean isday = new_servertime >= 0 && new_servertime < 13700;
                        w.servertime = new_servertime;
                        if(wasday != isday) {
                            pushUpdate(w.world, new Client.DayNight(isday));            
                        }
                    }
                    return 0;
                }
            });
            try {
                f.get();
            } catch (Exception ix) {
                Log.severe(ix);
            }
            scheduleDelayedJob(this, 5000);
        }
    }
    
    private class DoZoomOutProcessing implements Runnable {
        public void run() {
            Debug.debug("DoZoomOutProcessing started");
            ArrayList<DynmapWorld> wl = new ArrayList<DynmapWorld>(worlds);
            for(DynmapWorld w : wl) {
                w.freshenZoomOutFiles();
            }
            scheduleDelayedJob(this, zoomout_period*1000);
            Debug.debug("DoZoomOutProcessing finished");
        }
    }
    
    public MapManager(DynmapPlugin plugin, ConfigurationNode configuration) {
        plug_in = plugin;
        mapman = this;

        /* Get block hiding data, if any */
        hideores = configuration.getBoolean("hideores", false);

        /* Clear color scheme */
        ColorScheme.reset();
        
        /* Initialize HD map manager */
        hdmapman = new HDMapManager();  
        hdmapman.loadHDShaders(plugin);
        hdmapman.loadHDPerspectives(plugin);
        hdmapman.loadHDLightings(plugin);
        sscache = new SnapshotCache(configuration.getInteger("snapshotcachesize", 500));
        parallelrendercnt = configuration.getInteger("parallelrendercnt", 0);
        progressinterval = configuration.getInteger("progressloginterval", 100);
        if(progressinterval < 100) progressinterval = 100;
        saverestorepending = configuration.getBoolean("saverestorepending", true);
        
        this.tileQueue = new AsynchronousQueue<MapTile>(
                new Handler<MapTile>() {
                @Override
                public void handle(MapTile t) {
                    FullWorldRenderState job = new FullWorldRenderState(t);
                    if(!scheduleDelayedJob(job, 0))
                        job.cleanup();
                }
            }, 
            (int) (configuration.getDouble("renderinterval", 0.5) * 1000),
            configuration.getInteger("renderacceleratethreshold", 30),
            (int)(configuration.getDouble("renderaccelerateinterval", 0.2) * 1000), (Runtime.getRuntime().availableProcessors()+1)/2);

        /* On dedicated thread, so default to no delays */
        timeslice_int = (long)(configuration.getDouble("timesliceinterval", 0.0) * 1000);
        max_chunk_loads_per_tick = configuration.getInteger("maxchunkspertick", DEFAULT_CHUNKS_PER_TICK);
        if(max_chunk_loads_per_tick < 5) max_chunk_loads_per_tick = 5;
        /* Get zoomout processing periond in seconds */
        zoomout_period = configuration.getInteger("zoomoutperiod", DEFAULT_ZOOMOUT_PERIOD);
        if(zoomout_period < 5) zoomout_period = 5;
        
        scheduler = plugin.getServer().getScheduler();

        hashman = new TileHashManager(DynmapPlugin.tilesDirectory, configuration.getBoolean("enabletilehash", true));
        
        tileQueue.start();
        
        for (World world : plug_in.getServer().getWorlds()) {
            activateWorld(world);
        }        
    }

    void renderFullWorld(Location l, CommandSender sender, String mapname) {
        DynmapWorld world = getWorld(l.getWorld().getName());
        if (world == null) {
            sender.sendMessage("Could not render: world '" + l.getWorld().getName() + "' not defined in configuration.");
            return;
        }
        String wname = l.getWorld().getName();
        FullWorldRenderState rndr;
        synchronized(lock) {
            rndr = active_renders.get(wname);
            if(rndr != null) {
                sender.sendMessage(rndr.rendertype + " of world '" + wname + "' already active.");
                return;
            }
            rndr = new FullWorldRenderState(world,l,sender, mapname);    /* Make new activation record */
            active_renders.put(wname, rndr);    /* Add to active table */
        }
        /* Schedule first tile to be worked */
        scheduleDelayedJob(rndr, 0);

        sender.sendMessage("Full render starting on world '" + wname + "'...");
    }

    void renderWorldRadius(Location l, CommandSender sender, String mapname, int radius) {
        DynmapWorld world = getWorld(l.getWorld().getName());
        if (world == null) {
            sender.sendMessage("Could not render: world '" + l.getWorld().getName() + "' not defined in configuration.");
            return;
        }
        String wname = l.getWorld().getName();
        FullWorldRenderState rndr;
        synchronized(lock) {
            rndr = active_renders.get(wname);
            if(rndr != null) {
                sender.sendMessage(rndr.rendertype + " of world '" + wname + "' already active.");
                return;
            }
            rndr = new FullWorldRenderState(world,l,sender, mapname, radius);    /* Make new activation record */
            active_renders.put(wname, rndr);    /* Add to active table */
        }
        /* Schedule first tile to be worked */
        scheduleDelayedJob(rndr, 0);
        sender.sendMessage("Render of " + radius + " block radius starting on world '" + wname + "'...");
    }

    void cancelRender(World w, CommandSender sender) {
    	synchronized(lock) {
    		if(w != null) {
    			FullWorldRenderState rndr;
    			rndr = active_renders.get(w.getName());
    			if(rndr != null) {
    				rndr.cancelRender();	/* Cancel render */
    				if(sender != null) {
    					sender.sendMessage("Cancelled render for '" + w.getName() + "'");
    				}
    			}
    		}
    		else {	/* Else, cancel all */
    			for(String wid : active_renders.keySet()) {
    				FullWorldRenderState rnd = active_renders.get(wid);
					rnd.cancelRender();
    				if(sender != null) {
    					sender.sendMessage("Cancelled render for '" + wid + "'");
    				}
    			}
    		}
    	}
    }
    
    void purgeQueue(CommandSender sender) {
        if(tileQueue != null) {
            int cnt = 0;
            List<MapTile> popped = tileQueue.popAll();
            if(popped != null) {
                cnt = popped.size();
                popped.clear();
            }
            sender.sendMessage("Purged " + cnt + " tiles from queue");
        }
    }
    
    public void activateWorld(World w) {
        ConfigurationNode worldConfiguration = plug_in.getWorldConfiguration(w);
        if (!worldConfiguration.getBoolean("enabled", false)) {
            Log.info("World '" + w.getName() + "' disabled");
            return;
        }
        String worldName = w.getName();

        DynmapWorld dynmapWorld = new DynmapWorld();
        dynmapWorld.world = w;
        dynmapWorld.configuration = worldConfiguration;
        Log.verboseinfo("Loading maps of world '" + worldName + "'...");
        for(MapType map : worldConfiguration.<MapType>createInstances("maps", new Class<?>[0], new Object[0])) {
            if(map.getName() != null)
                dynmapWorld.maps.add(map);
        }
        Log.info("Loaded " + dynmapWorld.maps.size() + " maps of world '" + worldName + "'.");
        
        List<ConfigurationNode> loclist = worldConfiguration.getNodes("fullrenderlocations");
        dynmapWorld.seedloc = new ArrayList<Location>();
        dynmapWorld.servertime = (int)(w.getTime() % 24000);
        dynmapWorld.sendposition = worldConfiguration.getBoolean("sendposition", true);
        dynmapWorld.sendhealth = worldConfiguration.getBoolean("sendhealth", true);
        dynmapWorld.bigworld = worldConfiguration.getBoolean("bigworld", false);
        dynmapWorld.setExtraZoomOutLevels(worldConfiguration.getInteger("extrazoomout", 0));
        dynmapWorld.worldtilepath = new File(DynmapPlugin.tilesDirectory, w.getName());
        if(loclist != null) {
            for(ConfigurationNode loc : loclist) {
                Location lx = new Location(w, loc.getDouble("x", 0), loc.getDouble("y", 64), loc.getDouble("z", 0));
                dynmapWorld.seedloc.add(lx);
            }
        }
        /* Load visibility limits, if any are defined */
        List<ConfigurationNode> vislimits = worldConfiguration.getNodes("visibilitylimits");
        if(vislimits != null) {
            dynmapWorld.visibility_limits = new ArrayList<MapChunkCache.VisibilityLimit>();
            for(ConfigurationNode vis : vislimits) {
                MapChunkCache.VisibilityLimit lim = new MapChunkCache.VisibilityLimit();
                lim.x0 = vis.getInteger("x0", 0);
                lim.x1 = vis.getInteger("x1", 0);
                lim.z0 = vis.getInteger("z0", 0);
                lim.z1 = vis.getInteger("z1", 0);
                dynmapWorld.visibility_limits.add(lim);
                /* Also, add a seed location for the middle of each visible area */
                dynmapWorld.seedloc.add(new Location(w, (lim.x0+lim.x1)/2, 64, (lim.z0+lim.z1)/2));
            }            
        }
        /* Load hidden limits, if any are defined */
        List<ConfigurationNode> hidelimits = worldConfiguration.getNodes("hiddenlimits");
        if(hidelimits != null) {
            dynmapWorld.hidden_limits = new ArrayList<MapChunkCache.VisibilityLimit>();
            for(ConfigurationNode vis : hidelimits) {
                MapChunkCache.VisibilityLimit lim = new MapChunkCache.VisibilityLimit();
                lim.x0 = vis.getInteger("x0", 0);
                lim.x1 = vis.getInteger("x1", 0);
                lim.z0 = vis.getInteger("z0", 0);
                lim.z1 = vis.getInteger("z1", 0);
                dynmapWorld.hidden_limits.add(lim);
            }            
        }
        String autogen = worldConfiguration.getString("autogenerate-to-visibilitylimits", "none");
        if(autogen.equals("permanent")) {
            dynmapWorld.do_autogenerate = AutoGenerateOption.PERMANENT;
        }
        else if(autogen.equals("map-only")) {
            dynmapWorld.do_autogenerate = AutoGenerateOption.FORMAPONLY;
        }
        else {
            dynmapWorld.do_autogenerate = AutoGenerateOption.NONE;
        }
        if((dynmapWorld.do_autogenerate != AutoGenerateOption.NONE) && (dynmapWorld.visibility_limits == null)) {
            Log.info("Warning: Automatic world generation to visible limits option requires that visibitylimits be set - option disabled");
            dynmapWorld.do_autogenerate = AutoGenerateOption.NONE;
        }
        String hiddenchunkstyle = worldConfiguration.getString("hidestyle", "stone");
        if(hiddenchunkstyle.equals("air"))
            dynmapWorld.hiddenchunkstyle = MapChunkCache.HiddenChunkStyle.FILL_AIR;
        else if(hiddenchunkstyle.equals("ocean"))
            dynmapWorld.hiddenchunkstyle = MapChunkCache.HiddenChunkStyle.FILL_OCEAN;
        else
            dynmapWorld.hiddenchunkstyle = MapChunkCache.HiddenChunkStyle.FILL_STONE_PLAIN;
            
    
        // TODO: Make this less... weird...
        // Insert the world on the same spot as in the configuration.
        HashMap<String, Integer> indexLookup = new HashMap<String, Integer>();
        List<ConfigurationNode> nodes = plug_in.configuration.getNodes("worlds");
        for (int i = 0; i < nodes.size(); i++) {
            ConfigurationNode node = nodes.get(i);
            indexLookup.put(node.getString("name"), i);
        }
        Integer worldIndex = indexLookup.get(worldName);
        if(worldIndex == null) {
        	worlds.add(dynmapWorld);	/* Put at end if no world section */
        }
        else {
        	int insertIndex;
        	for(insertIndex = 0; insertIndex < worlds.size(); insertIndex++) {
        		Integer nextWorldIndex = indexLookup.get(worlds.get(insertIndex).world.getName());
        		if (nextWorldIndex == null || worldIndex < nextWorldIndex.intValue()) {
        			break;
       			}
        	}
        	worlds.add(insertIndex, dynmapWorld);
        }
        worldsLookup.put(w.getName(), dynmapWorld);
        plug_in.events.trigger("worldactivated", dynmapWorld);
        /* Now, restore any pending renders for this world */
        if(saverestorepending)
            loadPending(dynmapWorld);
    }
    
    private void loadPending(DynmapWorld w) {
        String wname = w.world.getName();
        File f = new File(plug_in.getDataFolder(), wname + ".pending");
        if(f.exists()) {
            org.bukkit.util.config.Configuration saved = new org.bukkit.util.config.Configuration(f);
            saved.load();
            ConfigurationNode cn = new ConfigurationNode(saved);
            /* Get the saved tile definitions */
            List<ConfigurationNode> tiles = cn.getNodes("tiles");
            if(tiles != null) {
                int cnt = 0;
                for(ConfigurationNode tile : tiles) {
                    MapTile mt = MapTile.restoreTile(w, tile);  /* Restore tile, if possible */
                    if(mt != null) {
                        if(invalidateTile(mt))
                            cnt++;
                    }
                }
                if(cnt > 0)
                    Log.info("Loaded " + cnt + " pending tile renders for world '" + wname);
            }
            /* Get saved render job, if any */
            ConfigurationNode job = cn.getNode("job");
            if(job != null) {
                try {
                    FullWorldRenderState j = new FullWorldRenderState(job);
                    active_renders.put(wname, j);
                } catch (Exception x) {
                    Log.info("Unable to restore render job for world '" + wname + "' - map configuration changed");
                }
            }
            
            f.delete(); /* And clean it up */
        }
    }
    
    private void savePending() {
        List<MapTile> mt = tileQueue.popAll();
        for(DynmapWorld w : worlds) {
            boolean dosave = false;
            File f = new File(plug_in.getDataFolder(), w.world.getName() + ".pending");
            org.bukkit.util.config.Configuration saved = new org.bukkit.util.config.Configuration(f);
            ArrayList<ConfigurationNode> savedtiles = new ArrayList<ConfigurationNode>();
            for(MapTile tile : mt) {
                if(tile.getDynmapWorld() != w) continue;
                ConfigurationNode tilenode = tile.saveTile();
                if(tilenode != null) {
                    savedtiles.add(tilenode);
                }
            }
            if(savedtiles.size() > 0) { /* Something to save? */
                saved.setProperty("tiles", savedtiles);
                dosave = true;
                Log.info("Saved " + savedtiles.size() + " pending tile renders in world '" + w.world.getName());
            }
            FullWorldRenderState job = active_renders.get(w.world.getName());
            if(job != null) {
                saved.setProperty("job", job.saveState());
                dosave = true;
                Log.info("Saved active render job in world '" + w.world.getName());
            }
            if(dosave) {
                saved.save();
                Log.info("Saved " + savedtiles.size() + " pending tile renders in world '" + w.world.getName());
            }
        }
    }

    public int touch(Location l, String reason) {
        DynmapWorld world = getWorld(l.getWorld().getName());
        if (world == null)
            return 0;
        int invalidates = 0;
        for (int i = 0; i < world.maps.size(); i++) {
            MapTile[] tiles = world.maps.get(i).getTiles(l);
            for (int j = 0; j < tiles.length; j++) {
                if(invalidateTile(tiles[j]))
                    invalidates++;
            }
        }
        if(reason != null) {
            TriggerStats ts = trigstats.get(reason);
            if(ts == null) {
                ts = new TriggerStats();
                trigstats.put(reason, ts);
            }
            ts.callsmade++;
            if(invalidates > 0) {
                ts.callswithtiles++;
                ts.tilesqueued += invalidates;
            }
        }
        return invalidates;
    }

    public int touchVolume(Location l0, Location l1, String reason) {
        DynmapWorld world = getWorld(l0.getWorld().getName());
        if (world == null)
            return 0;
        int invalidates = 0;
        for (int i = 0; i < world.maps.size(); i++) {
            MapTile[] tiles = world.maps.get(i).getTiles(l0, l1);
            for (int j = 0; j < tiles.length; j++) {
                if(invalidateTile(tiles[j]))
                    invalidates++;
            }
        }
        if(reason != null) {
            TriggerStats ts = trigstats.get(reason);
            if(ts == null) {
                ts = new TriggerStats();
                trigstats.put(reason, ts);
            }
            ts.callsmade++;
            if(invalidates > 0) {
                ts.callswithtiles++;
                ts.tilesqueued += invalidates;
            }
        }
        return invalidates;
    }

    public boolean invalidateTile(MapTile tile) {
        return tileQueue.push(tile);
    }

    public static boolean scheduleDelayedJob(Runnable job, long delay_in_msec) {
        if((mapman != null) && (mapman.render_pool != null)) {
            if(delay_in_msec > 0)
                mapman.render_pool.schedule(job, delay_in_msec, TimeUnit.MILLISECONDS);
            else
                mapman.render_pool.execute(job);
            return true;
        }
        else
            return false;
    }
                                                                       
    public void startRendering() {
        render_pool = new DynmapScheduledThreadPoolExecutor();
        tileQueue.start();
        scheduleDelayedJob(new DoZoomOutProcessing(), 60000);
        scheduleDelayedJob(new CheckWorldTimes(), 5000);
        /* Resume pending jobs */
        for(FullWorldRenderState job : active_renders.values()) {
            scheduleDelayedJob(job, 5000);
            Log.info("Resumed render starting on world '" + job.world.world.getName() + "'...");
        }
    }

    public void stopRendering() {
        /* Tell all worlds to cancel any zoom out processing */
        for(DynmapWorld w: worlds)
            w.cancelZoomOutFreshen();
        render_pool.shutdown();
        try {
            render_pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ix) {
        }
        tileQueue.stop();
        mapman = null;
        hdmapman = null;
        
        if(saverestorepending)
            savePending();
    }

    private HashMap<World, File> worldTileDirectories = new HashMap<World, File>();
    public File getTileFile(MapTile tile) {
        World world = tile.getWorld();
        File worldTileDirectory = worldTileDirectories.get(world);
        if (worldTileDirectory == null) {
            worldTileDirectory = new File(DynmapPlugin.tilesDirectory, tile.getWorld().getName());
            worldTileDirectories.put(world, worldTileDirectory);
        }
        if (!worldTileDirectory.isDirectory() && !worldTileDirectory.mkdirs()) {
            Log.warning("Could not create directory for tiles ('" + worldTileDirectory + "').");
        }
        return new File(worldTileDirectory, tile.getFilename());
    }

    public void pushUpdate(Object update) {
        for(DynmapWorld world : getWorlds()) {
            world.updates.pushUpdate(update);
        }
    }

    public void pushUpdate(World world, Object update) {
        pushUpdate(world.getName(), update);
    }

    public void pushUpdate(String worldName, Object update) {
        DynmapWorld world = getWorld(worldName);
        if(world != null)
            world.updates.pushUpdate(update);
    }

    public Object[] getWorldUpdates(String worldName, long since) {
        DynmapWorld world = getWorld(worldName);
        if (world == null)
            return new Object[0];
        return world.updates.getUpdatedObjects(since);
    }

    private static boolean use_legacy = false;
    /**
     * Render processor helper - used by code running on render threads to request chunk snapshot cache from server/sync thread
     */
    public MapChunkCache createMapChunkCache(DynmapWorld w, List<DynmapChunk> chunks,
            boolean blockdata, boolean highesty, boolean biome, boolean rawbiome) {
        MapChunkCache c = new NewMapChunkCache();
        if(w.visibility_limits != null) {
            for(MapChunkCache.VisibilityLimit limit: w.visibility_limits) {
                c.setVisibleRange(limit);
            }
            c.setHiddenFillStyle(w.hiddenchunkstyle);
            c.setAutoGenerateVisbileRanges(w.do_autogenerate);
        }
        if(w.hidden_limits != null) {
            for(MapChunkCache.VisibilityLimit limit: w.hidden_limits) {
                c.setHiddenRange(limit);
            }
            c.setHiddenFillStyle(w.hiddenchunkstyle);
        }

        c.setChunks(w.world, chunks);
        if(c.setChunkDataTypes(blockdata, biome, highesty, rawbiome) == false)
            Log.severe("CraftBukkit build does not support biome APIs");
        if(chunks.size() == 0) {    /* No chunks to get? */
            return c;
        }

        final MapChunkCache cc = c;

        while(!cc.isDoneLoading()) {
            synchronized(loadlock) {
                long now = System.currentTimeMillis();
                
                if(cur_tick != (now/50)) {  /* New tick? */
                    chunks_in_cur_tick = max_chunk_loads_per_tick;
                    cur_tick = now/50;
                }
            }
        	Future<Boolean> f = scheduler.callSyncMethod(plug_in, new Callable<Boolean>() {
        		public Boolean call() throws Exception {
        		    boolean exhausted;
        		    synchronized(loadlock) {
        		        if(chunks_in_cur_tick > 0)
        		            chunks_in_cur_tick -= cc.loadChunks(chunks_in_cur_tick);
        		        exhausted = (chunks_in_cur_tick == 0);
        		    }
        		    return exhausted;
        		}
        	});
        	boolean delay;
        	try {
    	        delay = f.get();
        	} catch (Exception ix) {
        		Log.severe(ix);
        		return null;
        	}
            if(delay)
                try { Thread.sleep(50); } catch (InterruptedException ix) {}
    	}
        return c;
    }
    /**
     *  Update map tile statistics
     */
    public void updateStatistics(MapTile tile, String subtype, boolean rendered, boolean updated, boolean transparent) {
        synchronized(lock) {
            String k = tile.getKey();
            if(subtype != null)
                k += "." + subtype;
            MapStats ms = mapstats.get(k);
            if(ms == null) {
                ms = new MapStats();
                mapstats.put(k, ms);
            }
            ms.loggedcnt++;
            if(rendered)
                ms.renderedcnt++;
            if(updated)
                ms.updatedcnt++;
            if(transparent)
                ms.transparentcnt++;
        }
    }
    /**
     * Print statistics command
     */
    public void printStats(CommandSender sender, String prefix) {
        sender.sendMessage("Tile Render Statistics:");
        MapStats tot = new MapStats();
        synchronized(lock) {
            for(String k: new TreeSet<String>(mapstats.keySet())) {
                if((prefix != null) && !k.startsWith(prefix))
                    continue;
                MapStats ms = mapstats.get(k);
                sender.sendMessage("  " + k + ": processed=" + ms.loggedcnt + ", rendered=" + ms.renderedcnt + 
                               ", updated=" + ms.updatedcnt + ", transparent=" + ms.transparentcnt);
                tot.loggedcnt += ms.loggedcnt;
                tot.renderedcnt += ms.renderedcnt;
                tot.updatedcnt += ms.updatedcnt;
                tot.transparentcnt += ms.transparentcnt;
            }
        }
        sender.sendMessage("  TOTALS: processed=" + tot.loggedcnt + ", rendered=" + tot.renderedcnt + 
                           ", updated=" + tot.updatedcnt + ", transparent=" + tot.transparentcnt);
        sender.sendMessage("  Triggered update queue size: " + tileQueue.size());
        String act = "";
        for(String wn : active_renders.keySet())
        	act += wn + " ";
        sender.sendMessage("  Active render jobs: " + act);
        /* Chunk load stats */
        sender.sendMessage("Chunk Loading Statistics:");
        sender.sendMessage("  Cache hit rate: " + sscache.getHitRate() + "%");
        int setcnt = chunk_caches_attempted.get();
        sender.sendMessage("  Chunk sets: created=" + chunk_caches_created.get() + ", attempted=" + chunk_caches_attempted.get());
        int readcnt = chunks_read.get();
        sender.sendMessage("  Chunk: loaded=" + readcnt + ", attempted=" + chunks_attempted.get());
        double ns = total_loadtime_ns.doubleValue() * 0.000001;    /* Convert to milliseconds */
        double chunkloadns = total_chunk_cache_loadtime_ns.doubleValue() * 0.000001;
        if(readcnt == 0) readcnt = 1;
        if(setcnt == 0) setcnt = 1;
        sender.sendMessage("  Chunk load times: " + ns + " msec (" + (ns / readcnt) + " msec/chunk)");
        sender.sendMessage("  Chunk set load times: " + chunkloadns + " msec (" + (chunkloadns / setcnt) + " msec/set)");
        sender.sendMessage("  Chunk set delay times: " + (chunkloadns-ns) + " msec (" + ((chunkloadns-ns) / setcnt) + " msec/set)");
        sender.sendMessage("  Chunk set exceptions: " + total_exceptions.get());
    }
    /**
     * Print trigger statistics command
     */
    public void printTriggerStats(CommandSender sender) {
        sender.sendMessage("Render Trigger Statistics:");
        synchronized(lock) {
            for(String k: new TreeSet<String>(trigstats.keySet())) {
                TriggerStats ts = trigstats.get(k);
                sender.sendMessage("  " + k + ": calls=" + ts.callsmade + ", calls-adding-tiles=" + ts.callswithtiles + ", tiles-added=" + ts.tilesqueued);
            }
        }
    }

    /**
     * Reset statistics
     */
    public void resetStats(CommandSender sender, String prefix) {
        synchronized(lock) {
            for(String k : mapstats.keySet()) {
                if((prefix != null) && !k.startsWith(prefix))
                    continue;
                MapStats ms = mapstats.get(k);
                ms.loggedcnt = 0;
                ms.renderedcnt = 0;
                ms.updatedcnt = 0;
                ms.transparentcnt = 0;
            }
            for(String k : trigstats.keySet()) {
                TriggerStats ts = trigstats.get(k);
                ts.callsmade = 0;
                ts.callswithtiles = 0;
                ts.tilesqueued = 0;
            }
            chunk_caches_created.set(0);
            chunk_caches_attempted.set(0);
            chunks_read.set(0);
            chunks_attempted.set(0);
            total_loadtime_ns.set(0);
            total_chunk_cache_loadtime_ns.set(0);
            total_exceptions.set(0);
        }
        sscache.resetStats();
        sender.sendMessage("Tile Render Statistics reset");
    }    
    
    public boolean getSwampShading() {
        return plug_in.swampshading;
    }

    public boolean getWaterBiomeShading() {
        return plug_in.waterbiomeshading;
    }

    public boolean getFenceJoin() {
        return plug_in.fencejoin;
    }
    
    public CompassMode getCompassMode() {
        return plug_in.compassmode;
    }

    public boolean getHideOres() {
        return hideores;
    }
    /* Map block ID to aliased ID - used to hide ores */
    public int getBlockIDAlias(int id) {
        if(!hideores) return id;
        switch(id) {
            case 14:    /* Gold Ore */
            case 15:    /* Iron Ore */
            case 16:    /* Coal Ore */
            case 21:    /* Lapis Lazuli Ore */
            case 56:    /* Diamond Ore */
            case 73:    /* Redstone ore */
                return 1;   /* Stone */
        }
        return id;
    }
    /*
     * Pause full/radius render processing
     * @param dopause - true to pause, false to unpause
     */
    void setPauseFullRadiusRenders(boolean dopause) {
        if(dopause != pausefullrenders) {
            pausefullrenders = dopause;
            Log.info("Full/radius render pause set to " + dopause);
        }
    }
    /*
     * Test if full renders are paused
     */
    boolean getPauseFullRadiusRenders() {
        return pausefullrenders;
    }
    /*
     * Pause update render processing
     * @param dopause - true to pause, false to unpause
     */
    void setPauseUpdateRenders(boolean dopause) {
        if(dopause != pauseupdaterenders) {
            pauseupdaterenders = dopause;
            Log.info("Update render pause set to " + dopause);
        }
    }
    /*
     * Test if update renders are paused
     */
    boolean getPauseUpdateRenders() {
        return pauseupdaterenders;
    }
}
