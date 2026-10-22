package dev.bluesparksss.viavisuals.world;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkRefresher {

  private static final int INNER_RADIUS = 2;

  private final Map<UUID, ArrayDeque<int[]>> queues = new ConcurrentHashMap<>();
  private volatile int perTick;

  public ChunkRefresher(int perTick) {
    this.perTick = Math.max(1, perTick);
  }

  public void setPerTick(int perTick) {
    this.perTick = Math.max(1, perTick);
  }

  public int queue(Player player) {
    World world = player.getWorld();
    int cx = player.getLocation().getBlockX() >> 4;
    int cz = player.getLocation().getBlockZ() >> 4;
    int radius = viewRadius(player, world);
    List<int[]> chunks = new ArrayList<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        chunks.add(new int[] {cx + dx, cz + dz, dx * dx + dz * dz});
      }
    }
    chunks.sort(Comparator.comparingInt(chunk -> chunk[2]));
    ArrayDeque<int[]> queue = new ArrayDeque<>(chunks);
    queues.put(player.getUniqueId(), queue);
    int inner = (2 * INNER_RADIUS + 1) * (2 * INNER_RADIUS + 1);
    for (int i = 0; i < inner && !queue.isEmpty(); i++) {
      int[] chunk = queue.poll();
      world.refreshChunk(chunk[0], chunk[1]);
    }
    return chunks.size();
  }

  public void drain(Player player) {
    ArrayDeque<int[]> queue = queues.get(player.getUniqueId());
    if (queue == null) {
      return;
    }
    World world = player.getWorld();
    for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
      int[] chunk = queue.poll();
      world.refreshChunk(chunk[0], chunk[1]);
    }
    if (queue.isEmpty()) {
      queues.remove(player.getUniqueId());
    }
  }

  public boolean pending(UUID id) {
    return queues.containsKey(id);
  }

  public void forget(UUID id) {
    queues.remove(id);
  }

  public java.util.Set<UUID> pendingIds() {
    return queues.keySet();
  }

  private static int viewRadius(Player player, World world) {
    int dist = -1;
    try {
      dist = player.getSendViewDistance();
    } catch (NoSuchMethodError | RuntimeException ex) {
    }
    if (dist < 2) {
      dist = world.getViewDistance();
    }
    return Math.max(2, Math.min(dist, 32)) + 1;
  }
}
