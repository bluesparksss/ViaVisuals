package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.display.ProxyEntities;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockDisplays {

  private static final float COVER_SCALE = 1.0f;
  private static final double STAND_OFFSET = -(1.501 - 1.0 / 16.0 + 0.25);
  private static final int CULL_INTERVAL = 10;
  private static final double HYSTERESIS = 8.0;
  private static final int MAX_SPAWNS_PER_PASS = 400;
  private static final int MAX_GRIDS_PER_PASS = 2;
  private static final int CHECKS_PER_PASS = 1200;

  private record Shown(int entityId, int cmd) {
  }

  private static final class Known {
    private final int x;
    private final int y;
    private final int z;
    private final BlockCatalog.Mapping map;
    private int hidden;

    private Known(int x, int y, int z, BlockCatalog.Mapping map) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.map = map;
    }

    int x() {
      return x;
    }

    int y() {
      return y;
    }

    int z() {
      return z;
    }

    BlockCatalog.Mapping map() {
      return map;
    }
  }

  private static final class View {
    final Map<Long, Known> known = new HashMap<>();
    final Map<Long, Shown> shown = new HashMap<>();
    final Map<Long, Set<Long>> byChunk = new HashMap<>();
    double px = Double.NaN;
    double py;
    double pz;
    volatile boolean winOn;
    volatile int winLo;
    int cursor;
  }

  private final Map<UUID, View> views = new ConcurrentHashMap<>();
  private final ThreadLocal<List<PacketWrapper<?>>> pending = ThreadLocal.withInitial(ArrayList::new);
  private volatile double range = 48;
  private volatile DisplayOcclusion occlusion;
  private volatile boolean occlude = true;
  private volatile ShelfDisplays shelves;

  public void setRange(double blocks) {
    this.range = blocks;
  }

  public void setWindow(UUID viewer, boolean on, int lo) {
    if (!on) {
      View view = views.get(viewer);
      if (view != null) {
        view.winOn = false;
      }
      return;
    }
    View view = views.computeIfAbsent(viewer, k -> new View());
    view.winLo = lo;
    view.winOn = true;
  }

  public void setOcclusion(DisplayOcclusion occlusion, boolean enabled) {
    this.occlusion = occlusion;
    this.occlude = enabled;
  }

  public void setShelves(ShelfDisplays shelves) {
    this.shelves = shelves;
  }

  public DisplayOcclusion occlusion() {
    return occlusion;
  }

  public void start(JavaPlugin plugin) {
    Bukkit.getScheduler().runTaskTimer(plugin, this::cull, CULL_INTERVAL, CULL_INTERVAL);
  }

  public List<PacketWrapper<?>> drainPending() {
    List<PacketWrapper<?>> out = new ArrayList<>(pending.get());
    pending.get().clear();
    return out;
  }

  public void show(User user, ProtocolVersion client, int x, int y, int z, BlockCatalog.Mapping map, boolean bulk) {
    View view = views.computeIfAbsent(user.getUUID(), k -> new View());
    long key = pack(x, y, z);
    List<PacketWrapper<?>> out = pending.get();
    if (shelves != null && ShelfDisplays.isShelf(map.blockId())) {
      shelves.track(user, x, y, z);
    }
    synchronized (view) {
      view.known.put(key, new Known(x, y, z, map));
      view.byChunk.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> new HashSet<>()).add(key);
      Shown cur = view.shown.get(key);
      if (cur != null) {
        if (cur.cmd() != map.displayCmd()) {
          out.add(ProxyEntities.setItem(cur.entityId(), client, displayItem(map)));
          view.shown.put(key, new Shown(cur.entityId(), map.displayCmd()));
        }
        return;
      }
      if (bulk && occlude && occlusion != null) {
        return;
      }
      if (inRange(view, x, y, z, map, 0)) {
        spawn(view, client, key, x, y, z, map, out);
      }
    }
  }

  public void remove(User user, int x, int y, int z) {
    if (shelves != null) {
      shelves.untrack(user, x, y, z);
    }
    View view = views.get(user.getUUID());
    if (view == null) {
      return;
    }
    long key = pack(x, y, z);
    synchronized (view) {
      if (view.known.remove(key) != null) {
        Set<Long> chunk = view.byChunk.get(chunkKey(x >> 4, z >> 4));
        if (chunk != null) {
          chunk.remove(key);
        }
      }
      Shown cur = view.shown.remove(key);
      if (cur != null) {
        pending.get().add(ProxyEntities.destroy(cur.entityId()));
      }
    }
  }

  public void clearChunk(User user, int cx, int cz) {
    if (shelves != null) {
      shelves.clearChunk(user, cx, cz);
    }
    View view = views.get(user.getUUID());
    if (view == null) {
      return;
    }
    List<Integer> ids = new ArrayList<>();
    synchronized (view) {
      Set<Long> keys = view.byChunk.remove(chunkKey(cx, cz));
      if (keys != null) {
        for (long key : keys) {
          view.known.remove(key);
          Shown cur = view.shown.remove(key);
          if (cur != null) {
            ids.add(cur.entityId());
          }
        }
      }
    }
    if (!ids.isEmpty()) {
      pending.get().add(ProxyEntities.destroy(ids.stream().mapToInt(Integer::intValue).toArray()));
    }
  }

  public void forget(UUID viewer) {
    if (shelves != null) {
      shelves.forget(viewer);
    }
    views.remove(viewer);
  }

  public void destroyAll(User user) {
    if (shelves != null) {
      shelves.destroyAll(user);
    }
    View view = views.remove(user.getUUID());
    if (view == null) {
      return;
    }
    int[] ids;
    synchronized (view) {
      ids = view.shown.values().stream().mapToInt(Shown::entityId).toArray();
    }
    if (ids.length > 0) {
      ProxyEntities.sendOne(user, ProxyEntities.destroy(ids));
    }
  }

  public Map<Integer, Integer> shownByCmd(UUID viewer) {
    Map<Integer, Integer> out = new HashMap<>();
    View view = views.get(viewer);
    if (view != null) {
      synchronized (view) {
        for (Shown shown : view.shown.values()) {
          out.merge(shown.cmd(), 1, Integer::sum);
        }
      }
    }
    return out;
  }

  public int[] sampleOf(UUID viewer, int cmd) {
    View view = views.get(viewer);
    if (view == null) {
      return null;
    }
    synchronized (view) {
      for (Map.Entry<Long, Shown> ent : view.shown.entrySet()) {
        if (ent.getValue().cmd() == cmd) {
          long key = ent.getKey();
          return new int[] {(int) (key >> 38), (int) (key << 52 >> 52), (int) (key << 26 >> 38)};
        }
      }
    }
    return null;
  }

  public int knownCount(UUID viewer) {
    View view = views.get(viewer);
    if (view == null) {
      return 0;
    }
    synchronized (view) {
      return view.known.size();
    }
  }

  private void spawn(View view, ProtocolVersion client, long key, int x, int y, int z, BlockCatalog.Mapping map,
                     List<PacketWrapper<?>> out) {
    int id = ProxyEntities.nextId();
    view.shown.put(key, new Shown(id, map.displayCmd()));
    out.addAll(ProxyEntities.spawn(id, client, new Vector3d(x + 0.5, y + 0.5, z + 0.5), map.yaw(), displayItem(map),
        COVER_SCALE, new Vector3f(0f, 0f, 0f), STAND_OFFSET));
  }

  private boolean inRange(View view, int x, int y, int z, BlockCatalog.Mapping map, double extra) {
    if (view.winOn && (y < view.winLo || y > view.winLo + 255)) {
      return false;
    }
    double limit = range;
    if (limit <= 0 || !map.cullable() || Double.isNaN(view.px)) {
      return true;
    }
    limit += extra;
    double dx = x + 0.5 - view.px;
    double dy = y + 0.5 - view.py;
    double dz = z + 0.5 - view.pz;
    return dx * dx + dy * dy + dz * dz <= limit * limit;
  }

  private void cull() {
    DisplayOcclusion occ = occlude ? occlusion : null;
    for (Iterator<Map.Entry<UUID, View>> it = views.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<UUID, View> ent = it.next();
      Player player = Bukkit.getPlayer(ent.getKey());
      if (player == null) {
        it.remove();
        continue;
      }
      View view = ent.getValue();
      User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
      Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
      if (user == null || proto.isEmpty()) {
        continue;
      }
      Location loc = player.getLocation();
      Location eye = player.getEyeLocation();
      World world = player.getWorld();
      List<PacketWrapper<?>> out = new ArrayList<>();
      synchronized (view) {
        view.px = loc.getX();
        view.py = loc.getY();
        view.pz = loc.getZ();
        if (range <= 0 && !view.winOn && occ == null) {
          continue;
        }
        List<Long> chunks = nearChunks(view, loc);
        if (occ != null) {
          int built = 0;
          for (long ck : chunks) {
            if (built < MAX_GRIDS_PER_PASS && occ.ensure(world, (int) (ck >> 32), (int) ck)) {
              built++;
            }
          }
        }
        int[] budget = {occ == null ? 0 : CHECKS_PER_PASS};
        List<Long> drop = new ArrayList<>();
        for (Long key : view.shown.keySet()) {
          Known kn = view.known.get(key);
          if (kn == null) {
            continue;
          }
          if (!inRange(view, kn.x(), kn.y(), kn.z(), kn.map(), HYSTERESIS)) {
            drop.add(key);
          } else if (occ != null && budget[0] > CHECKS_PER_PASS / 2 && hiddenTwice(occ, world, eye, kn, budget)) {
            drop.add(key);
          }
        }
        if (!drop.isEmpty()) {
          int[] ids = new int[drop.size()];
          for (int i = 0; i < ids.length; i++) {
            ids[i] = view.shown.remove(drop.get(i)).entityId();
          }
          out.add(ProxyEntities.destroy(ids));
        }
        int spawned = 0;
        int n = chunks.size();
        int start = n == 0 ? 0 : Math.floorMod(view.cursor, n);
        int visited = 0;
        for (; visited < n && spawned < MAX_SPAWNS_PER_PASS; visited++) {
          Set<Long> keys = view.byChunk.get(chunks.get((start + visited) % n));
          if (keys == null) {
            continue;
          }
          for (long key : keys) {
            Known val = view.known.get(key);
            if (val == null || view.shown.containsKey(key) || !inRange(view, val.x(), val.y(), val.z(), val.map(), 0)) {
              continue;
            }
            if (occ != null) {
              if (budget[0] <= 0) {
                break;
              }
              budget[0]--;
              if (!occ.visible(world.getUID(), eye.getX(), eye.getY(), eye.getZ(), val.x(), val.y(), val.z())) {
                val.hidden = 2;
                continue;
              }
            }
            val.hidden = 0;
            spawn(view, proto.get(), key, val.x(), val.y(), val.z(), val.map(), out);
            spawned++;
            if (spawned >= MAX_SPAWNS_PER_PASS) {
              break;
            }
          }
          if (occ != null && budget[0] <= 0) {
            visited++;
            break;
          }
        }
        view.cursor = start + visited;
      }
      if (!out.isEmpty()) {
        ProxyEntities.send(user, out);
      }
    }
  }

  private static boolean hiddenTwice(DisplayOcclusion occ, World world, Location eye, Known kn, int[] budget) {
    budget[0]--;
    if (occ.visible(world.getUID(), eye.getX(), eye.getY(), eye.getZ(), kn.x(), kn.y(), kn.z())) {
      kn.hidden = 0;
      return false;
    }
    return ++kn.hidden >= 2;
  }

  private List<Long> nearChunks(View view, Location loc) {
    List<Long> chunks = new ArrayList<>();
    double reach = range > 0 ? range + HYSTERESIS + 16 : Double.MAX_VALUE;
    double reachSq = reach * reach;
    for (Long ck : view.byChunk.keySet()) {
      double dx = ((int) (ck >> 32) << 4) + 8 - loc.getX();
      double dz = ((int) ck.longValue() << 4) + 8 - loc.getZ();
      if (dx * dx + dz * dz <= reachSq) {
        chunks.add(ck);
      }
    }
    chunks.sort((a, b) -> Double.compare(chunkDistSq(a, loc), chunkDistSq(b, loc)));
    return chunks;
  }

  private static double chunkDistSq(long ck, Location loc) {
    double dx = ((int) (ck >> 32) << 4) + 8 - loc.getX();
    double dz = ((int) ck << 4) + 8 - loc.getZ();
    return dx * dx + dz * dz;
  }

  public int notShown(UUID viewer) {
    View view = views.get(viewer);
    if (view == null) {
      return 0;
    }
    synchronized (view) {
      return view.known.size() - view.shown.size();
    }
  }

  public int hiddenCount(UUID viewer) {
    View view = views.get(viewer);
    if (view == null) {
      return 0;
    }
    synchronized (view) {
      int count = 0;
      for (Map.Entry<Long, Known> ent : view.known.entrySet()) {
        if (ent.getValue().hidden >= 2 && !view.shown.containsKey(ent.getKey())) {
          count++;
        }
      }
      return count;
    }
  }

  private static ItemStack displayItem(BlockCatalog.Mapping map) {
    org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(map.displayProxy());
    ItemMeta meta = stack.getItemMeta();
    meta.setCustomModelData(map.displayCmd());
    stack.setItemMeta(meta);
    return SpigotConversionUtil.fromBukkitItemStack(stack);
  }

  private static long chunkKey(int cx, int cz) {
    return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
  }

  private static long pack(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }
}
