package dev.bluesparksss.viavisuals.version;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.pack.PackManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public final class ConnectionDetailsListener implements PluginMessageListener {

  public static final String PROXY_CHANNEL = "vv:proxy_details";
  public static final String MOD_CHANNEL = "vv:mod_details";

  private final JavaPlugin plugin;
  private final PackManager packs;
  private final ClientResync resync;
  private volatile boolean proxy = true;
  private volatile boolean mod = true;
  private volatile boolean debug;

  public ConnectionDetailsListener(JavaPlugin plugin, PackManager packs, ClientResync resync) {
    this.plugin = plugin;
    this.packs = packs;
    this.resync = resync;
  }

  public void register() {
    plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, PROXY_CHANNEL, this);
    plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, MOD_CHANNEL, this);
  }

  public void unregister() {
    plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, PROXY_CHANNEL, this);
    plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, MOD_CHANNEL, this);
  }

  public void configure(org.bukkit.configuration.file.FileConfiguration conf) {
    this.proxy = conf.getBoolean("client-detection.proxy-details", true);
    this.mod = conf.getBoolean("client-detection.mod-details", true);
    this.debug = conf.getBoolean("debug.client-profiles", false);
  }

  @Override
  public void onPluginMessageReceived(String channel, Player player, byte[] message) {
    boolean fromMod = MOD_CHANNEL.equals(channel);
    if (fromMod ? !mod : !proxy) {
      return;
    }
    try {
      JsonObject json = JsonParser.parseString(new String(message, StandardCharsets.UTF_8)).getAsJsonObject();
      if (!json.has("version")) {
        return;
      }
      String type = json.has("versionType") ? json.get("versionType").getAsString() : null;
      ProtocolVersion version = ClientProfiles.parse(json.get("version").getAsInt(), type);
      if (version == null) {
        plugin.getLogger().fine("Unknown client version in " + channel + " from " + player.getName() + ": " + json);
        return;
      }
      ClientProfiles.Source source = fromMod ? ClientProfiles.Source.MOD : ClientProfiles.Source.PROXY;
      boolean changed = ClientProfiles.setDetails(player.getUniqueId(), version, source);
      if (debug) {
        plugin.getLogger().info(channel + " from " + player.getName() + ": " + version.getName()
            + (json.has("platformName") ? " via " + json.get("platformName").getAsString() : "")
            + (changed ? " (profile changed)" : ""));
      }
      if (changed) {
        packs.reassign(player);
        resync.run(player);
      }
    } catch (RuntimeException ex) {
      plugin.getLogger().log(Level.FINE, "Ignored a malformed " + channel + " payload from " + player.getName(), ex);
    }
  }
}
