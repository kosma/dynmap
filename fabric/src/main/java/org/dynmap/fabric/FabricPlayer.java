package org.dynmap.fabric;

import com.google.common.collect.Iterables;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.dynmap.DynmapLocation;
import org.dynmap.common.DynmapPlayer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

/**
 * Player access abstraction class
 */
public class FabricPlayer extends FabricCommandSender implements DynmapPlayer {
    private static final Gson GSON = new GsonBuilder().create();
    private final DynmapPlugin plugin;
    // FIXME: Proper setter
    ServerPlayer player;
    private final String skinurl;
    private final UUID uuid;

    public FabricPlayer(DynmapPlugin plugin, ServerPlayer player) {
        this.plugin = plugin;
        this.player = player;
        String url = null;
        if (this.player != null) {
            uuid = this.player.getUUID();
            GameProfile prof = this.player.getGameProfile();
            if (prof != null) {
                Property textureProperty = Iterables.getFirst(prof.getProperties().get("textures"), null);

                if (textureProperty != null) {
                    DynmapPlugin.TexturesPayload result = null;
                    try {
                        String json = new String(Base64.getDecoder().decode(textureProperty.getValue()), StandardCharsets.UTF_8);
                        result = GSON.fromJson(json, DynmapPlugin.TexturesPayload.class);
                    } catch (JsonParseException e) {
                    }
                    if ((result != null) && (result.textures != null) && (result.textures.containsKey("SKIN"))) {
                        url = result.textures.get("SKIN").url;
                    }
                }
            }
        } else {
            uuid = null;
        }
        skinurl = url;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public String getName() {
        if (player != null) {
            String n = player.getName().getString();
            ;
            return n;
        } else
            return "[Server]";
    }

    @Override
    public String getDisplayName() {
        if (player != null) {
            String n = player.getDisplayName().getString();
            return n;
        } else
            return "[Server]";
    }

    @Override
    public boolean isOnline() {
        return true;
    }

    @Override
    public DynmapLocation getLocation() {
        if (player == null) {
            return null;
        }

        Vec3 pos = player.position();
        return new DynmapLocation(plugin.getWorld(player.level).getName(), pos.x(), pos.y(), pos.z());
    }

    @Override
    public String getWorld() {
        if (player == null) {
            return null;
        }

        if (player.level != null) {
            return plugin.getWorld(player.level).getName();
        }

        return null;
    }

    @Override
    public InetSocketAddress getAddress() {
        if (player != null) {
            ServerGamePacketListenerImpl networkHandler = player.connection;
            if ((networkHandler != null) && (networkHandler.getConnection() != null)) {
                SocketAddress sa = networkHandler.getConnection().getRemoteAddress();
                if (sa instanceof InetSocketAddress) {
                    return (InetSocketAddress) sa;
                }
            }
        }
        return null;
    }

    @Override
    public boolean isSneaking() {
        if (player != null) {
            return player.isSneaking();
        }

        return false;
    }

    @Override
    public double getHealth() {
        if (player != null) {
            double h = player.getHealth();
            if (h > 20) h = 20;
            return h;  // Scale to 20 range
        } else {
            return 0;
        }
    }

    @Override
    public int getArmorPoints() {
        if (player != null) {
            return player.getArmorValue();
        } else {
            return 0;
        }
    }

    @Override
    public DynmapLocation getBedSpawnLocation() {
        return null;
    }

    @Override
    public long getLastLoginTime() {
        return 0;
    }

    @Override
    public long getFirstLoginTime() {
        return 0;
    }

    @Override
    public boolean hasPrivilege(String privid) {
        if (player != null)
            return plugin.hasPerm(player, privid);
        return false;
    }

    @Override
    public boolean isOp() {
        return plugin.isOp(player.getName().getString());
    }

    @Override
    public void sendMessage(String msg) {
        FabricAdapter.VERSION_SPECIFIC.ServerPlayerEntity_sendMessage(player, msg);
    }

    @Override
    public boolean isInvisible() {
        if (player != null) {
            return player.isInvisible();
        }
        return false;
    }

    @Override
    public int getSortWeight() {
        return plugin.getSortWeight(getName());
    }

    @Override
    public void setSortWeight(int wt) {
        if (wt == 0) {
            plugin.dropSortWeight(getName());
        } else {
            plugin.setSortWeight(getName(), wt);
        }
    }

    @Override
    public boolean hasPermissionNode(String node) {
        return player != null && plugin.hasPermNode(player, node);
    }

    @Override
    public String getSkinURL() {
        return skinurl;
    }

    @Override
    public UUID getUUID() {
        return uuid;
    }

    /**
     * Send title and subtitle text (called from server thread)
     */
    @Override
    public void sendTitleText(String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (player != null) {
            FabricAdapter.VERSION_SPECIFIC.ServerPlayerEntity_sendTitleText(player, title, subtitle, fadeInTicks, stayTicks, fadeOutTicks);
        }
    }
}
