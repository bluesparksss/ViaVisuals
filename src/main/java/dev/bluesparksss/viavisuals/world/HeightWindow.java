package dev.bluesparksss.viavisuals.world;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerVehicleMove;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.block.BlockDisplays;
import dev.bluesparksss.viavisuals.version.VersionManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class HeightWindow implements Listener {

  public static final ProtocolVersion SHORT_CLIENTS = ProtocolVersion.v1_17;
  private static final int WINDOW = 256;
  private static final long COOLDOWN_MS = 2000;
  private static final long CONFIRM_TIMEOUT_MS = 3000;
  private static final long KICK_GRACE_MS = 6000;
  private static final long STEP_UNITS = 32000;
  private static final int ALL_RELATIVE = 0x1FF;
  private static final int DOWN_AT = 30;
  private static final int UP_AT = 46;
  private static final int TOP_AT = 240;
  private static final int REFRESH_PER_TICK = 64;
  private static final int OLD_RELATIVE = 0x1F;

  public record Frame(int outLo, int inLo, int pendingLo, int pendingId, long movedAt, long sentAt) {
  }

  private static volatile HeightWindow instance;

  private final JavaPlugin plugin;
  private final BlockDisplays displays;
  private final Map<UUID, Frame> frames = new ConcurrentHashMap<>();
  private final Set<UUID> inert = ConcurrentHashMap.newKeySet();
  private final Set<UUID> needAbsolute = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Set<Integer>> known = new ConcurrentHashMap<>();
  private final ChunkRefresher refresh = new ChunkRefresher(REFRESH_PER_TICK);
  private final AtomicInteger teleportIds = new AtomicInteger(0x4B000000);
  private volatile boolean enabled;
  private volatile boolean debug;

  public HeightWindow(JavaPlugin plugin, BlockDisplays displays) {
    this.plugin = plugin;
    this.displays = displays;
    instance = this;
  }

  public void start(FileConfiguration conf) {
    configure(conf);
    Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
  }

  public void configure(FileConfiguration conf) {
    this.enabled = conf.getBoolean("legacy-height.enabled", true);
    this.debug = conf.getBoolean("debug.height-window", false);
  }

  public static HeightWindow get() {
    return instance;
  }

  public static Frame frame(User user) {
    HeightWindow win = instance;
    return win != null && win.enabled ? win.resolve(user.getUUID()) : null;
  }

  public static int low(User user) {
    Frame frame = frame(user);
    return frame == null ? 0 : frame.outLo();
  }

  public static boolean shifting(User user) {
    return frame(user) != null;
  }

  private Frame resolve(UUID id) {
    Frame frame = frames.get(id);
    if (frame != null) {
      return frame;
    }
    if (inert.contains(id)) {
      return null;
    }
    Player player = Bukkit.getPlayer(id);
    if (player == null) {
      return null;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetProtocolVersion(player);
    if (proto.isEmpty()) {
      return null;
    }
    World world = player.getWorld();
    if (!proto.get().olderThan(SHORT_CLIENTS) || (world.getMinHeight() >= 0 && world.getMaxHeight() <= WINDOW)) {
      inert.add(id);
      return null;
    }
    int lo = desired(base(world), player.getLocation().getY(), 0, world.getMinHeight(), world.getMaxHeight() - WINDOW);
    Frame made = new Frame(lo, lo, lo, 0, 0, 0);
    Frame prev = frames.putIfAbsent(id, made);
    if (prev != null) {
      return prev;
    }
    if (lo != 0) {
      needAbsolute.add(id);
    }
    if (debug) {
      plugin.getLogger().info("height window of " + player.getName() + " starts at " + lo);
    }
    return made;
  }

  private static int base(World world) {
    return Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - WINDOW, 0));
  }

  Frame flip(UUID id, int teleportId) {
    return frames.computeIfPresent(id, (key, frame) ->
        frame.pendingId() == teleportId && teleportId != 0 && frame.outLo() != frame.pendingLo()
            ? new Frame(frame.pendingLo(), frame.inLo(), frame.pendingLo(), frame.pendingId(), frame.movedAt(), frame.sentAt())
            : frame);
  }

  boolean confirm(UUID id, int teleportId) {
    boolean[] ours = {false};
    frames.computeIfPresent(id, (key, frame) -> {
      if (frame.pendingId() != teleportId || teleportId == 0) {
        return frame;
      }
      ours[0] = true;
      return new Frame(frame.outLo(), frame.outLo(), frame.outLo(), 0, frame.movedAt(), 0);
    });
    return ours[0];
  }

  Set<Integer> entities(UUID id) {
    return known.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet());
  }

  void forgetEntities(UUID id) {
    known.remove(id);
  }

  void reset(UUID id) {
    frames.remove(id);
    inert.remove(id);
    needAbsolute.remove(id);
    known.remove(id);
    refresh.forget(id);
  }

  public void reconsider(UUID id) {
    if (!frames.containsKey(id)) {
      inert.remove(id);
    }
  }

  static int desired(int cur, double y, double vy, int min, int max, int downAt, int upAt, int topAt) {
    if (max < min) {
      return min;
    }
    int base = Math.max(min, Math.min(max, 0));
    double ahead = y + Math.max(-40, Math.min(40, vy * 10));
    if (cur == base) {
      return ahead >= base + downAt && ahead <= base + topAt ? cur : centred(y, min, max);
    }
    if (ahead >= base + upAt && ahead <= base + topAt - 24) {
      return base;
    }
    if (y < cur + 2 || y > cur + WINDOW - 3) {
      return centred(y, min, max);
    }
    return cur;
  }

  private int desired(int cur, double y, double vy, int min, int max) {
    return desired(cur, y, vy, min, max, DOWN_AT, UP_AT, TOP_AT);
  }

  private static int centred(double y, int min, int max) {
    int lo = Math.floorDiv((int) Math.floor(y) - WINDOW / 2, 16) * 16;
    return Math.max(min, Math.min(max, lo));
  }

  private void tick() {
    if (!enabled) {
      return;
    }
    long now = System.currentTimeMillis();
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID id = player.getUniqueId();
      Frame frame = resolve(id);
      if (frame == null) {
        continue;
      }
      User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
      if (user == null) {
        continue;
      }
      displays.setWindow(id, true, frame.outLo());
      if (needAbsolute.remove(id)) {
        sendAbsolute(player, user);
      }
      if (frame.pendingId() != 0) {
        if (now - frame.sentAt() > CONFIRM_TIMEOUT_MS) {
          frames.put(id, new Frame(frame.outLo(), frame.outLo(), frame.outLo(), 0, frame.movedAt(), 0));
          plugin.getLogger().warning("height window move of " + player.getName() + " was not confirmed in time");
        }
        drain(player);
        continue;
      }
      drain(player);
      World world = player.getWorld();
      Location loc = player.getLocation();
      int min = world.getMinHeight();
      int max = world.getMaxHeight() - WINDOW;
      int next = desired(frame.outLo(), loc.getY(), player.getVelocity().getY(), min, max);
      if (next == frame.outLo()) {
        continue;
      }
      boolean forced = loc.getY() < frame.outLo() + 2 || loc.getY() > frame.outLo() + WINDOW - 3;
      if (forced || now - frame.movedAt() >= COOLDOWN_MS) {
        moveWindow(player, user, frame, next, now);
      }
    }
  }

  private void moveWindow(Player player, User user, Frame frame, int next, long now) {
    UUID uuid = player.getUniqueId();
    int delta = next - frame.outLo();
    int id = teleportIds.incrementAndGet();
    frames.put(uuid, new Frame(frame.outLo(), frame.inLo(), next, id, now, now));
    displays.setWindow(uuid, true, next);
    boolean deltas = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_2);
    user.sendPacket(new WrapperPlayServerPlayerPositionAndLook(id, new Vector3d(0, -delta, 0), new Vector3d(0, 0, 0),
        0f, 0f, new RelativeFlag(deltas ? ALL_RELATIVE : OLD_RELATIVE)));
    int moved = shiftEntities(user, uuid, -delta);
    Entity vehicle = player.getVehicle();
    if (vehicle != null) {
      Location at = vehicle.getLocation();
      user.sendPacket(new WrapperPlayServerVehicleMove(new Vector3d(at.getX(), at.getY(), at.getZ()), at.getYaw(), at.getPitch()));
    }
    int queued = queueRefresh(player);
    if (debug) {
      plugin.getLogger().info("height window of " + player.getName() + " " + frame.outLo() + " -> " + next
          + " (teleport " + id + "): " + moved + " entities shifted, " + queued + " chunks queued");
    }
  }

  private int shiftEntities(User user, UUID uuid, int dy) {
    Set<Integer> ids = known.get(uuid);
    if (ids == null || ids.isEmpty() || dy == 0) {
      return 0;
    }
    long units = (long) dy * 4096;
    int steps = (int) Math.ceil(Math.abs(units) / (double) STEP_UNITS);
    double[] moves = new double[steps];
    long done = 0;
    for (int i = 0; i < steps; i++) {
      long target = units * (i + 1) / steps;
      moves[i] = (target - done) / 4096.0;
      done = target;
    }
    int count = 0;
    for (int ent : ids) {
      for (double step : moves) {
        user.sendPacketSilently(new WrapperPlayServerEntityRelativeMove(ent, 0, step, 0, false));
      }
      count++;
    }
    return count;
  }

  private int queueRefresh(Player player) {
    return refresh.queue(player);
  }

  private void drain(Player player) {
    refresh.drain(player);
  }

  private void sendAbsolute(Player player, User user) {
    UUID uuid = player.getUniqueId();
    int id = teleportIds.incrementAndGet();
    long now = System.currentTimeMillis();
    frames.computeIfPresent(uuid, (key, frame) -> new Frame(frame.outLo(), frame.inLo(), frame.outLo(), id, frame.movedAt(), now));
    Location loc = player.getLocation();
    user.sendPacket(new WrapperPlayServerPlayerPositionAndLook(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(),
        loc.getPitch(), (byte) 0, id, false));
  }

  @EventHandler(ignoreCancelled = true)
  public void onKick(PlayerKickEvent event) {
    Player player = event.getPlayer();
    Frame frame = frames.get(player.getUniqueId());
    if (frame == null || System.currentTimeMillis() - frame.movedAt() > KICK_GRACE_MS) {
      return;
    }
    try {
      if (!"FLYING_PLAYER".equals(event.getCause().name())) {
        return;
      }
    } catch (NoSuchMethodError | RuntimeException ex) {
      return;
    }
    event.setCancelled(true);
    plugin.getLogger().warning("Cancelled a flying kick of " + player.getName() + " right after a height window move (frame "
        + frame + ")");
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user != null) {
      sendAbsolute(player, user);
    }
  }

  @EventHandler
  public void onWorld(PlayerChangedWorldEvent event) {
    redecide(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void onRespawn(PlayerRespawnEvent event) {
    redecide(event.getPlayer().getUniqueId());
  }

  private void redecide(UUID id) {
    frames.remove(id);
    inert.remove(id);
    needAbsolute.remove(id);
    refresh.forget(id);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    reset(event.getPlayer().getUniqueId());
    displays.setWindow(event.getPlayer().getUniqueId(), false, 0);
  }
}
