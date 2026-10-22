package dev.bluesparksss.viavisuals.version;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import dev.bluesparksss.viavisuals.ViaVisualsPlugin;
import dev.bluesparksss.viavisuals.world.ChunkRefresher;
import dev.bluesparksss.viavisuals.world.HeightWindow;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ClientResync {

  private static final double ENTITY_RADIUS = 96.0;
  private static final int CHUNKS_PER_TICK = 16;

  private final ViaVisualsPlugin plugin;
  private final ChunkRefresher chunks = new ChunkRefresher(CHUNKS_PER_TICK);
  private volatile boolean debug;

  public ClientResync(ViaVisualsPlugin plugin) {
    this.plugin = plugin;
    Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
  }

  public void configure(org.bukkit.configuration.file.FileConfiguration conf) {
    debug = conf.getBoolean("debug.client-profiles", false);
  }

  public void run(Player player) {
    UUID id = player.getUniqueId();
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      Player plr = Bukkit.getPlayer(id);
      if (plr != null && plr.isOnline()) {
        begin(plr);
      }
    }, 1L);
  }

  private void begin(Player player) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user == null) {
      return;
    }
    if (plugin.entityProxies() != null) {
      plugin.entityProxies().forgetViewer(player);
    }
    if (plugin.blockDisplays() != null) {
      plugin.blockDisplays().destroyAll(user);
    }
    List<Entity> hidden = new ArrayList<>();
    for (Entity ent : player.getNearbyEntities(ENTITY_RADIUS, ENTITY_RADIUS, ENTITY_RADIUS)) {
      if (ent instanceof Player || ent.equals(player.getVehicle()) || !player.canSee(ent)) {
        continue;
      }
      player.hideEntity(plugin, ent);
      hidden.add(ent);
    }
    UUID id = player.getUniqueId();
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      Player plr = Bukkit.getPlayer(id);
      if (plr == null || !plr.isOnline()) {
        return;
      }
      for (Entity ent : hidden) {
        if (ent.isValid()) {
          plr.showEntity(plugin, ent);
        }
      }
      HeightWindow win = HeightWindow.get();
      if (win != null) {
        win.reconsider(id);
      }
      int queued = chunks.queue(plr);
      plr.updateInventory();
      plr.updateCommands();
      if (debug) {
        plugin.getLogger().info("resync of " + plr.getName() + ": " + hidden.size() + " entities re-shown, " + queued
            + " chunks queued, profile " + ClientProfiles.of(id).map(Object::toString).orElse("?"));
      }
    }, 2L);
  }

  private void drain() {
    if (chunks.pendingIds().isEmpty()) {
      return;
    }
    for (UUID id : new ArrayList<>(chunks.pendingIds())) {
      Player player = Bukkit.getPlayer(id);
      if (player == null) {
        chunks.forget(id);
      } else {
        chunks.drain(player);
      }
    }
  }

  public void forget(UUID id) {
    chunks.forget(id);
  }
}
