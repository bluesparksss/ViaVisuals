package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

public final class DisplayOcclusion {

  private static final double NEAR_SQ = 16.0;
  private static final double INSET = 0.12;
  private static final int MAX_STEPS = 160;

  private final ClientVersion version;
  private final Map<UUID, Map<Long, AtomicLongArray>> worlds = new ConcurrentHashMap<>();
  private final Map<UUID, int[]> heights = new ConcurrentHashMap<>();
  private volatile byte[] stateOpacity = new byte[32768];

  public DisplayOcclusion(ClientVersion version) {
    this.version = version;
  }

  public boolean ensure(World world, int cx, int cz) {
    Map<Long, AtomicLongArray> chunks = worlds.computeIfAbsent(world.getUID(), k -> new ConcurrentHashMap<>());
    long key = chunkKey(cx, cz);
    if (chunks.containsKey(key) || !world.isChunkLoaded(cx, cz)) {
      return false;
    }
    Chunk chunk = world.getChunkAt(cx, cz);
    ChunkSnapshot snap = chunk.getChunkSnapshot(false, false, false);
    int min = world.getMinHeight();
    int max = world.getMaxHeight();
    heights.put(world.getUID(), new int[] {min, max});
    AtomicLongArray bits = new AtomicLongArray(((max - min) * 256 + 63) / 64);
    for (int y = min; y < max; y++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          if (hidesDisplays(snap.getBlockType(x, y, z))) {
            int idx = ((y - min) << 8) | (z << 4) | x;
            bits.set(idx >> 6, bits.get(idx >> 6) | (1L << (idx & 63)));
          }
        }
      }
    }
    chunks.put(key, bits);
    return true;
  }

  public void onBlock(World world, int x, int y, int z, int serverState) {
    Map<Long, AtomicLongArray> chunks = worlds.get(world.getUID());
    if (chunks == null) {
      return;
    }
    AtomicLongArray bits = chunks.get(chunkKey(x >> 4, z >> 4));
    int[] range = heights.get(world.getUID());
    if (bits == null || range == null || y < range[0] || y >= range[1]) {
      return;
    }
    int idx = ((y - range[0]) << 8) | ((z & 15) << 4) | (x & 15);
    int word = idx >> 6;
    long mask = 1L << (idx & 63);
    if (opaqueState(serverState)) {
      bits.getAndUpdate(word, old -> old | mask);
    } else {
      bits.getAndUpdate(word, old -> old & ~mask);
    }
  }

  public void invalidate(World world, int cx, int cz) {
    Map<Long, AtomicLongArray> chunks = worlds.get(world.getUID());
    if (chunks != null) {
      chunks.remove(chunkKey(cx, cz));
    }
  }

  public void unloadChunk(World world, int cx, int cz) {
    invalidate(world, cx, cz);
  }

  public void forgetWorld(UUID world) {
    worlds.remove(world);
    heights.remove(world);
  }

  private static boolean hidesDisplays(Material mat) {
    return mat.isOccluding() && mat != Material.BARRIER;
  }

  private boolean opaqueState(int id) {
    byte[] table = stateOpacity;
    if (id < 0) {
      return false;
    }
    if (id >= table.length) {
      table = java.util.Arrays.copyOf(table, Math.max(id + 1, table.length * 2));
      stateOpacity = table;
    }
    byte val = table[id];
    if (val == 0) {
      boolean opaque = false;
      try {
        Material mat = SpigotConversionUtil.toBukkitBlockData(WrappedBlockState.getByGlobalId(version, id, false))
            .getMaterial();
        opaque = hidesDisplays(mat);
      } catch (RuntimeException ex) {
        opaque = false;
      }
      val = (byte) (opaque ? 2 : 1);
      table[id] = val;
    }
    return val == 2;
  }

  public boolean isOpaque(UUID world, int x, int y, int z) {
    Map<Long, AtomicLongArray> chunks = worlds.get(world);
    int[] range = heights.get(world);
    return chunks != null && range != null && opaque(chunks, range, x, y, z);
  }

  private boolean opaque(Map<Long, AtomicLongArray> chunks, int[] range, int x, int y, int z) {
    if (y < range[0] || y >= range[1]) {
      return false;
    }
    AtomicLongArray bits = chunks.get(chunkKey(x >> 4, z >> 4));
    if (bits == null) {
      return false;
    }
    int idx = ((y - range[0]) << 8) | ((z & 15) << 4) | (x & 15);
    return (bits.get(idx >> 6) & (1L << (idx & 63))) != 0;
  }

  public boolean visible(UUID world, double ex, double ey, double ez, int x, int y, int z) {
    Map<Long, AtomicLongArray> chunks = worlds.get(world);
    int[] range = heights.get(world);
    if (chunks == null || range == null) {
      return true;
    }
    double dx = x + 0.5 - ex;
    double dy = y + 0.5 - ey;
    double dz = z + 0.5 - ez;
    if (dx * dx + dy * dy + dz * dz < NEAR_SQ) {
      return true;
    }
    if (opaque(chunks, range, x + 1, y, z) && opaque(chunks, range, x - 1, y, z) && opaque(chunks, range, x, y + 1, z)
        && opaque(chunks, range, x, y - 1, z) && opaque(chunks, range, x, y, z + 1) && opaque(chunks, range, x, y, z - 1)) {
      return false;
    }
    if (!clear(chunks, range, ex, ey, ez, x + 0.5, y + 0.5, z + 0.5, x, y, z)) {
      for (int i = 0; i < 8; i++) {
        double px = x + ((i & 1) == 0 ? INSET : 1 - INSET);
        double py = y + ((i & 2) == 0 ? INSET : 1 - INSET);
        double pz = z + ((i & 4) == 0 ? INSET : 1 - INSET);
        if (clear(chunks, range, ex, ey, ez, px, py, pz, x, y, z)) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private boolean clear(Map<Long, AtomicLongArray> chunks, int[] range, double sx, double sy, double sz,
                        double tx, double ty, double tz, int bx, int by, int bz) {
    double dx = tx - sx;
    double dy = ty - sy;
    double dz = tz - sz;
    int ix = (int) Math.floor(sx);
    int iy = (int) Math.floor(sy);
    int iz = (int) Math.floor(sz);
    int endX = (int) Math.floor(tx);
    int endY = (int) Math.floor(ty);
    int endZ = (int) Math.floor(tz);
    int stepX = dx > 0 ? 1 : -1;
    int stepY = dy > 0 ? 1 : -1;
    int stepZ = dz > 0 ? 1 : -1;
    double tMaxX = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? ix + 1 - sx : sx - ix) / Math.abs(dx));
    double tMaxY = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? iy + 1 - sy : sy - iy) / Math.abs(dy));
    double tMaxZ = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? iz + 1 - sz : sz - iz) / Math.abs(dz));
    double tDeltaX = dx == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dx);
    double tDeltaY = dy == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dy);
    double tDeltaZ = dz == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dz);
    for (int step = 0; step < MAX_STEPS; step++) {
      if (tMaxX < tMaxY && tMaxX < tMaxZ) {
        ix += stepX;
        tMaxX += tDeltaX;
      } else if (tMaxY < tMaxZ) {
        iy += stepY;
        tMaxY += tDeltaY;
      } else {
        iz += stepZ;
        tMaxZ += tDeltaZ;
      }
      if ((ix == bx && iy == by && iz == bz) || (ix == endX && iy == endY && iz == endZ)) {
        return true;
      }
      if (opaque(chunks, range, ix, iy, iz)) {
        return false;
      }
    }
    return true;
  }

  private static long chunkKey(int cx, int cz) {
    return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
  }
}
