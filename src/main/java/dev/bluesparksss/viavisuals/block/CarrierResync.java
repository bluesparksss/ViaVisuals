package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CarrierResync extends PacketListenerAbstract {

  private static final int MAX_BLOCKS = 384;
  private static final int MAX_RADIUS = 8;
  private static final long AGAIN_TICKS = 2L;
  private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

  private final JavaPlugin plugin;
  private final BlockCatalog catalog;
  private final Map<UUID, Set<Long>> pending = new ConcurrentHashMap<>();

  public CarrierResync(JavaPlugin plugin, BlockCatalog catalog) {
    super(PacketListenerPriority.MONITOR);
    this.plugin = plugin;
    this.catalog = catalog;
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    var type = event.getPacketType();
    Vector3i pos;
    Vector3i alt = null;
    if (type == PacketType.Play.Client.PLAYER_DIGGING) {
      WrapperPlayClientPlayerDigging pkt = new WrapperPlayClientPlayerDigging(event);
      if (pkt.getAction() != DiggingAction.START_DIGGING && pkt.getAction() != DiggingAction.FINISHED_DIGGING) {
        return;
      }
      pos = pkt.getBlockPosition();
    } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
      WrapperPlayClientPlayerBlockPlacement pkt = new WrapperPlayClientPlayerBlockPlacement(event);
      pos = pkt.getBlockPosition();
      alt = new Vector3i(pos.getX() + pkt.getFace().getModX(), pos.getY() + pkt.getFace().getModY(),
          pos.getZ() + pkt.getFace().getModZ());
    } else {
      return;
    }
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty() || !BlockCatalog.clientNeedsCarriers(proto.get())) {
      return;
    }
    UUID id = player.getUniqueId();
    Set<Long> set = pending.get(id);
    if (set == null) {
      set = ConcurrentHashMap.newKeySet();
      Set<Long> prev = pending.putIfAbsent(id, set);
      if (prev == null) {
        Bukkit.getScheduler().runTask(plugin, () -> flush(id));
      } else {
        set = prev;
      }
    }
    set.add(pack(pos.getX(), pos.getY(), pos.getZ()));
    if (alt != null) {
      set.add(pack(alt.getX(), alt.getY(), alt.getZ()));
    }
  }

  private void flush(UUID id) {
    Set<Long> seeds = pending.remove(id);
    if (seeds == null) {
      return;
    }
    pass(id, seeds);
    Bukkit.getScheduler().runTaskLater(plugin, () -> pass(id, seeds), AGAIN_TICKS);
  }

  private void pass(UUID id, Set<Long> seeds) {
    Player player = Bukkit.getPlayer(id);
    if (seeds == null || player == null || !player.isOnline()) {
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty()) {
      return;
    }
    World world = player.getWorld();
    Set<Long> seen = new HashSet<>();
    List<Block> resend = new ArrayList<>();
    for (long seed : seeds) {
      int sx = unpackX(seed);
      int sy = unpackY(seed);
      int sz = unpackZ(seed);
      ArrayDeque<int[]> queue = new ArrayDeque<>();
      queue.add(new int[] {sx, sy, sz});
      seen.add(seed);
      boolean first = true;
      while (!queue.isEmpty() && resend.size() < MAX_BLOCKS) {
        int[] cur = queue.poll();
        if (first) {
          first = false;
        } else if (!tryAdd(world, cur, proto.get(), resend)) {
          continue;
        }
        for (int[] side : SIDES) {
          int nx = cur[0] + side[0];
          int ny = cur[1] + side[1];
          int nz = cur[2] + side[2];
          if (Math.abs(nx - sx) > MAX_RADIUS || Math.abs(ny - sy) > MAX_RADIUS || Math.abs(nz - sz) > MAX_RADIUS
              || !seen.add(pack(nx, ny, nz))) {
            continue;
          }
          queue.add(new int[] {nx, ny, nz});
        }
      }
    }
    for (Block blk : resend) {
      player.sendBlockChange(blk.getLocation(), blk.getBlockData());
    }
  }

  private boolean tryAdd(World world, int[] pos, ProtocolVersion client, List<Block> out) {
    if (pos[1] < world.getMinHeight() || pos[1] >= world.getMaxHeight() || !world.isChunkLoaded(pos[0] >> 4, pos[2] >> 4)) {
      return false;
    }
    Block blk = world.getBlockAt(pos[0], pos[1], pos[2]);
    int state = SpigotConversionUtil.fromBukkitBlockData(blk.getBlockData()).getGlobalId();
    if (catalog.mappingFor(state, client) == null) {
      return false;
    }
    out.add(blk);
    return true;
  }

  public void forget(UUID id) {
    pending.remove(id);
  }

  private static long pack(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
  }

  private static int unpackX(long val) {
    return (int) (val >> 38);
  }

  private static int unpackY(long val) {
    return (int) (val << 52 >> 52);
  }

  private static int unpackZ(long val) {
    return (int) (val << 26 >> 38);
  }
}
