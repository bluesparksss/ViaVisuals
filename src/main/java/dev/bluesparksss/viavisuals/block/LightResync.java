package dev.bluesparksss.viavisuals.block;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LightResync {

  private static final long DELAY_TICKS = 3L;

  private final JavaPlugin plugin;
  private final Map<UUID, Map<Long, Set<Long>>> emitters = new ConcurrentHashMap<>();
  private final Set<String> pending = ConcurrentHashMap.newKeySet();

  public LightResync(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public void seen(UUID viewer, int x, int y, int z, boolean emits, boolean bulk) {
    long chunk = chunkKey(x >> 4, z >> 4);
    long pos = pack(x, y, z);
    Map<Long, Set<Long>> mine = emitters.computeIfAbsent(viewer, k -> new ConcurrentHashMap<>());
    if (emits) {
      mine.computeIfAbsent(chunk, k -> ConcurrentHashMap.newKeySet()).add(pos);
      if (!bulk) {
        request(viewer, x, z);
      }
      return;
    }
    if (bulk) {
      return;
    }
    Set<Long> inChunk = mine.get(chunk);
    if (inChunk != null && inChunk.remove(pos)) {
      request(viewer, x, z);
    }
  }

  public void unload(UUID viewer, int cx, int cz) {
    Map<Long, Set<Long>> mine = emitters.get(viewer);
    if (mine != null) {
      mine.remove(chunkKey(cx, cz));
    }
  }

  public void forget(UUID viewer) {
    emitters.remove(viewer);
  }

  private void request(UUID viewer, int x, int z) {
    int cx = x >> 4;
    int cz = z >> 4;
    int lx = x & 15;
    int lz = z & 15;
    for (int dx = lx <= 14 ? -1 : 0; dx <= (lx >= 1 ? 1 : 0); dx++) {
      for (int dz = lz <= 14 ? -1 : 0; dz <= (lz >= 1 ? 1 : 0); dz++) {
        requestChunk(viewer, cx + dx, cz + dz);
      }
    }
  }

  private void requestChunk(UUID viewer, int cx, int cz) {
    String key = viewer + ":" + cx + ":" + cz;
    if (!pending.add(key)) {
      return;
    }
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      pending.remove(key);
      Player player = Bukkit.getPlayer(viewer);
      if (player != null && player.isOnline()) {
        World world = player.getWorld();
        if (world.isChunkLoaded(cx, cz)) {
          world.refreshChunk(cx, cz);
        }
      }
    }, DELAY_TICKS);
  }

  private static long chunkKey(int cx, int cz) {
    return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
  }

  private static long pack(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }
}
