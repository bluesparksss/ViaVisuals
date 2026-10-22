package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.display.ProxyEntities;
import dev.bluesparksss.viavisuals.item.ItemTranslator;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShelfDisplays {

  private static final int SLOTS = 3;
  private static final long SCAN_TICKS = 5L;
  private static final double RANGE = 32.0;
  private static final float ITEM_SCALE = 0.5f;
  private static final double SLOT_SPACING = 0.3125;
  private static final double BACK_OFFSET = 0.25;
  private static final double SMALL_HEAD_Y = 0.901;
  private static final double SMALL_FLAT_LIFT = 13.0 / 16.0 * 0.625 * 0.7;
  private static final double SMALL_FLAT_BEHIND = 7.0 / 16.0 * 0.625 * 0.7;
  private static final double LEGACY_BACK_OFFSET = 0.18;

  private record Slotted(int entityId, String sig) {
  }

  private static final class Shelf {
    final int x;
    final int y;
    final int z;
    final Slotted[] shown = new Slotted[SLOTS];

    Shelf(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }

  private final JavaPlugin plugin;
  private final ItemTranslator xlat;
  private final Map<UUID, Map<Long, Shelf>> byViewer = new ConcurrentHashMap<>();

  public ShelfDisplays(JavaPlugin plugin, ItemTranslator xlat) {
    this.plugin = plugin;
    this.xlat = xlat;
  }

  public void start() {
    Bukkit.getScheduler().runTaskTimer(plugin, this::scan, SCAN_TICKS, SCAN_TICKS);
  }

  public static boolean isShelf(String blockId) {
    return blockId != null && blockId.endsWith("_shelf");
  }

  public void track(User user, int x, int y, int z) {
    byViewer.computeIfAbsent(user.getUUID(), k -> new ConcurrentHashMap<>()).putIfAbsent(pack(x, y, z), new Shelf(x, y, z));
  }

  public void untrack(User user, int x, int y, int z) {
    Map<Long, Shelf> shelves = byViewer.get(user.getUUID());
    Shelf gone = shelves == null ? null : shelves.remove(pack(x, y, z));
    if (gone != null) {
      destroy(user, gone);
    }
  }

  public void clearChunk(User user, int cx, int cz) {
    Map<Long, Shelf> shelves = byViewer.get(user.getUUID());
    if (shelves == null) {
      return;
    }
    shelves.values().removeIf(shelf -> {
      if ((shelf.x >> 4) == cx && (shelf.z >> 4) == cz) {
        destroy(user, shelf);
        return true;
      }
      return false;
    });
  }

  public void forget(UUID viewer) {
    byViewer.remove(viewer);
  }

  public void destroyAll(User user) {
    Map<Long, Shelf> shelves = byViewer.remove(user.getUUID());
    if (shelves != null) {
      shelves.values().forEach(shelf -> destroy(user, shelf));
    }
  }

  private void destroy(User user, Shelf shelf) {
    List<Integer> ids = new ArrayList<>();
    synchronized (shelf) {
      for (int i = 0; i < SLOTS; i++) {
        if (shelf.shown[i] != null) {
          ids.add(shelf.shown[i].entityId());
          shelf.shown[i] = null;
        }
      }
    }
    if (!ids.isEmpty()) {
      ProxyEntities.sendOne(user, ProxyEntities.destroy(ids.stream().mapToInt(Integer::intValue).toArray()));
    }
  }

  private void scan() {
    for (Map.Entry<UUID, Map<Long, Shelf>> ent : byViewer.entrySet()) {
      Player player = Bukkit.getPlayer(ent.getKey());
      if (player == null) {
        byViewer.remove(ent.getKey());
        continue;
      }
      User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
      Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
      if (user == null || proto.isEmpty()) {
        continue;
      }
      World world = player.getWorld();
      double px = player.getLocation().getX();
      double py = player.getLocation().getY();
      double pz = player.getLocation().getZ();
      for (Shelf shelf : ent.getValue().values()) {
        double dx = shelf.x + 0.5 - px;
        double dy = shelf.y + 0.5 - py;
        double dz = shelf.z + 0.5 - pz;
        boolean near = dx * dx + dy * dy + dz * dz <= RANGE * RANGE && world.isChunkLoaded(shelf.x >> 4, shelf.z >> 4);
        if (!near) {
          destroy(user, shelf);
          continue;
        }
        synchronized (shelf) {
          refresh(user, proto.get(), world.getBlockAt(shelf.x, shelf.y, shelf.z), shelf);
        }
      }
    }
  }

  private void refresh(User user, ProtocolVersion client, Block block, Shelf shelf) {
    if (!isShelf(block.getType().name().toLowerCase(java.util.Locale.ROOT))) {
      destroy(user, shelf);
      return;
    }
    ItemStack[] items = contents(block);
    org.bukkit.block.BlockFace face = block.getBlockData() instanceof Directional dir ? dir.getFacing()
        : org.bukkit.block.BlockFace.NORTH;
    List<PacketWrapper<?>> out = new ArrayList<>();
    for (int slot = 0; slot < SLOTS; slot++) {
      ItemStack item = items[slot];
      String sig = item == null || item.getType().isAir() ? null : item.getType().name() + "x" + item.getAmount() + face;
      Slotted cur = shelf.shown[slot];
      if (cur != null && java.util.Objects.equals(cur.sig(), sig)) {
        continue;
      }
      if (cur != null) {
        out.add(ProxyEntities.destroy(cur.entityId()));
        shelf.shown[slot] = null;
      }
      if (sig == null) {
        continue;
      }
      int id = ProxyEntities.nextId();
      shelf.shown[slot] = new Slotted(id, sig);
      ItemStack shown = xlat.toClient(item, client);
      Vector3d at = slotPosition(shelf, face, slot);
      if (plugin.getConfig().getBoolean("debug.block-effects", false)) {
        plugin.getLogger().info("shelf " + shelf.x + " " + shelf.y + " " + shelf.z + " slot " + slot + " " + sig + " as entity " + id
            + " at " + at + " for " + client.getName());
      }
      boolean legacy = !ProxyEntities.usesItemDisplay(client);
      boolean flat = !shown.getType().isBlock();
      double standY = -(SMALL_HEAD_Y + (flat ? SMALL_FLAT_LIFT : 0.0));
      if (legacy) {
        double depth = LEGACY_BACK_OFFSET - BACK_OFFSET;
        double lead = flat ? SMALL_FLAT_BEHIND : 0.0;
        at = new Vector3d(at.getX() - face.getModX() * depth + face.getModX() * lead, at.getY(),
            at.getZ() - face.getModZ() * depth + face.getModZ() * lead);
      }
      out.addAll(ProxyEntities.spawn(id, client, at, yaw(face), SpigotConversionUtil.fromBukkitItemStack(shown),
          ITEM_SCALE, new Vector3f(0f, 0f, 0f), standY, true));
    }
    if (!out.isEmpty()) {
      ProxyEntities.send(user, out);
    }
  }

  private static Vector3d slotPosition(Shelf shelf, org.bukkit.block.BlockFace face, int slot) {
    double theta = Math.toRadians(-toYRot(face));
    double lx = (slot - 1) * SLOT_SPACING;
    double lz = -BACK_OFFSET;
    double wx = lx * Math.cos(theta) + lz * Math.sin(theta);
    double wz = -lx * Math.sin(theta) + lz * Math.cos(theta);
    return new Vector3d(shelf.x + 0.5 + wx, shelf.y + 0.5, shelf.z + 0.5 + wz);
  }

  private static float toYRot(org.bukkit.block.BlockFace face) {
    return switch (face) {
      case NORTH -> 180f;
      case WEST -> 90f;
      case EAST -> 270f;
      default -> 0f;
    };
  }

  private static float yaw(org.bukkit.block.BlockFace face) {
    return toYRot(face);
  }

  private static ItemStack[] contents(Block block) {
    ItemStack[] out = new ItemStack[SLOTS];
    try {
      BlockState state = block.getState(false);
      Object inv = state.getClass().getMethod("getInventory").invoke(state);
      if (inv instanceof Inventory inventory) {
        for (int i = 0; i < Math.min(SLOTS, inventory.getSize()); i++) {
          out[i] = inventory.getItem(i);
        }
      }
    } catch (ReflectiveOperationException | RuntimeException ex) {
    }
    return out;
  }

  private static long pack(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }
}
