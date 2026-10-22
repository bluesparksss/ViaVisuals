package dev.bluesparksss.viavisuals.block;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MiningDebug implements Listener {

  private final JavaPlugin plugin;
  private final Map<UUID, Integer> last = new ConcurrentHashMap<>();

  public MiningDebug(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    if (!plugin.getConfig().getBoolean("debug.mining", false)) {
      return;
    }
    int now = Bukkit.getCurrentTick();
    Integer prev = last.put(event.getPlayer().getUniqueId(), now);
    plugin.getLogger().info("break " + event.getPlayer().getName() + " " + event.getBlock().getType().name().toLowerCase(java.util.Locale.ROOT)
        + " tick " + now + " gap " + (prev == null ? "-" : String.valueOf(now - prev)));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    last.remove(event.getPlayer().getUniqueId());
  }
}
