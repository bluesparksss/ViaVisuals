package dev.bluesparksss.viavisuals.hud;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.waypoint.AzimuthWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.ChunkWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.TrackedWaypoint;
import com.github.retrooper.packetevents.protocol.world.waypoint.Vec3iWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.WaypointInfo;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetExperience;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.version.ClientProfiles;
import dev.bluesparksss.viavisuals.version.VersionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LocatorBar extends PacketListenerAbstract implements Listener {

  private static final ProtocolVersion NATIVE_BAR = ProtocolVersion.v1_21_6;
  private static final int VISIBLE_DEGREES = 60;
  private static final int XP_PRIORITY_TICKS = 100;
  private static final int RESEND_TICKS = 20;
  private static final int FOREIGN_TICKS = 60;
  private static final int JUMP_COOLDOWN_TICKS = 10;
  private static final int NET_ADVANCE = -4;
  private static final int START = -NET_ADVANCE / 2;
  private static final TextColor XP_GREEN = TextColor.color(0x80FF20);
  private static final TextColor BLACK = TextColor.color(0x000000);

  private enum Kind { VEC3I, CHUNK, AZIMUTH }

  private record Wp(String key, UUID entity, String style, Integer color, Kind kind, double x, double y, double z, double angle) {
  }

  private static final class State {
    final Map<String, Wp> waypoints = new ConcurrentHashMap<>();
    volatile long xpChangedAt = Long.MIN_VALUE / 2;
    volatile float lastProgress = Float.NaN;
    volatile long foreignUntil;
    volatile long jumpUntil;
    volatile boolean jumping;
    boolean shown;
    String lastSig = "";
    long lastSent;
  }

  private final JavaPlugin plugin;
  private final LocatorFont font;
  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private volatile boolean enabled = true;
  private volatile double fov = 70;
  private volatile boolean debug;
  private long tick;

  public LocatorBar(JavaPlugin plugin, LocatorFont font) {
    super(PacketListenerPriority.HIGH);
    this.plugin = plugin;
    this.font = font;
    Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
  }

  public void configure(org.bukkit.configuration.file.FileConfiguration conf) {
    enabled = conf.getBoolean("locator-bar.enabled", true);
    fov = conf.getDouble("locator-bar.assumed-fov", 70.0);
    debug = conf.getBoolean("debug.locator-bar", false);
  }

  private boolean needed(Player player) {
    return enabled && VersionManager.tryGetVisualVersion(player)
        .map(v -> v.olderThan(NATIVE_BAR)).orElse(false);
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    var type = event.getPacketType();
    if (type == PacketType.Play.Server.WAYPOINT) {
      if (needed(player)) {
        track(player, new WrapperPlayServerWaypoint(event));
        event.setCancelled(true);
      }
    } else if (type == PacketType.Play.Server.SET_EXPERIENCE) {
      State st = states.get(player.getUniqueId());
      float progress = new WrapperPlayServerSetExperience(event).getExperienceBar();
      if (st != null) {
        if (!Float.isNaN(st.lastProgress) && st.lastProgress != progress) {
          st.xpChangedAt = tick;
        }
        st.lastProgress = progress;
      }
    } else if (type == PacketType.Play.Server.RESPAWN || type == PacketType.Play.Server.JOIN_GAME) {
      State st = states.get(player.getUniqueId());
      if (st != null) {
        st.waypoints.clear();
      }
    } else if (type == PacketType.Play.Server.ACTION_BAR
        || (type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE && new WrapperPlayServerSystemChatMessage(event).isOverlay())) {
      State st = states.get(player.getUniqueId());
      if (st != null && !st.waypoints.isEmpty()) {
        Component text = type == PacketType.Play.Server.ACTION_BAR
            ? new WrapperPlayServerActionBar(event).getActionBarText()
            : new WrapperPlayServerSystemChatMessage(event).getMessage();
        boolean empty = text == null || net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(text).isEmpty();
        st.foreignUntil = empty ? 0 : tick + FOREIGN_TICKS;
      }
    }
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    Player player = event.getPlayer();
    if (event.getPacketType() != PacketType.Play.Client.ENTITY_ACTION || player == null) {
      return;
    }
    State st = states.get(player.getUniqueId());
    if (st == null) {
      return;
    }
    var action = new WrapperPlayClientEntityAction(event).getAction();
    if (action == WrapperPlayClientEntityAction.Action.START_JUMPING_WITH_HORSE) {
      st.jumping = true;
    } else if (action == WrapperPlayClientEntityAction.Action.STOP_JUMPING_WITH_HORSE) {
      st.jumping = false;
      st.jumpUntil = tick + JUMP_COOLDOWN_TICKS;
    }
  }

  private void track(Player player, WrapperPlayServerWaypoint pkt) {
    State st = states.computeIfAbsent(player.getUniqueId(), k -> new State());
    TrackedWaypoint wp = pkt.getWaypoint();
    String key = wp.getIdentifier().isLeft() ? wp.getIdentifier().getLeft().toString() : "n:" + wp.getIdentifier().getRight();
    if (pkt.getOperation() == WrapperPlayServerWaypoint.Operation.UNTRACK) {
      st.waypoints.remove(key);
      return;
    }
    WaypointInfo info = wp.getInfo();
    String style = wp.getIcon() == null || wp.getIcon().getStyle() == null ? "default" : wp.getIcon().getStyle().getKey();
    Integer color = null;
    if (wp.getIcon() != null && wp.getIcon().getColor() != null) {
      var col = wp.getIcon().getColor();
      color = (col.red() << 16) | (col.green() << 8) | col.blue();
    }
    UUID entity = wp.getIdentifier().isLeft() ? wp.getIdentifier().getLeft() : null;
    Wp made;
    if (info instanceof Vec3iWaypointInfo vec) {
      made = new Wp(key, entity, style, color, Kind.VEC3I, vec.getPosition().getX(), vec.getPosition().getY(), vec.getPosition().getZ(), 0);
    } else if (info instanceof ChunkWaypointInfo chunk) {
      made = new Wp(key, entity, style, color, Kind.CHUNK, chunk.getChunkX(), 0, chunk.getChunkZ(), 0);
    } else if (info instanceof AzimuthWaypointInfo az) {
      made = new Wp(key, entity, style, color, Kind.AZIMUTH, 0, 0, 0, az.getAngle());
    } else {
      st.waypoints.remove(key);
      return;
    }
    st.waypoints.put(key, made);
    if (debug) {
      plugin.getLogger().info("locator: " + player.getName() + " " + pkt.getOperation() + " " + made);
    }
  }

  private void tick() {
    tick++;
    for (Map.Entry<UUID, State> ent : states.entrySet()) {
      Player player = Bukkit.getPlayer(ent.getKey());
      if (player == null) {
        continue;
      }
      try {
        draw(player, ent.getValue());
      } catch (RuntimeException ex) {
        plugin.getLogger().log(java.util.logging.Level.WARNING, "Locator bar failed for " + player.getName(), ex);
      }
    }
  }

  private void draw(Player player, State st) {
    ClientProfiles.Pack pack = ClientProfiles.pack(player.getUniqueId());
    boolean ready = needed(player) && pack == ClientProfiles.Pack.LOADED && !st.waypoints.isEmpty();
    GameMode mode = player.getGameMode();
    boolean survivalBar = mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    boolean xpFirst = survivalBar && tick - st.xpChangedAt < XP_PRIORITY_TICKS;
    boolean jumpFirst = st.jumping || tick < st.jumpUntil;
    boolean show = ready && !xpFirst && !jumpFirst && tick >= st.foreignUntil;
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user == null) {
      return;
    }
    if (!show) {
      if (st.shown) {
        st.shown = false;
        st.lastSig = "";
        user.sendPacketSilently(new WrapperPlayServerActionBar(Component.empty()));
      }
      return;
    }
    List<Item> items = new ArrayList<>();
    String sig = layout(player, st, items);
    if (!st.shown || !sig.equals(st.lastSig) || tick - st.lastSent >= RESEND_TICKS) {
      user.sendPacketSilently(new WrapperPlayServerActionBar(compose(items, survivalBar ? player.getLevel() : 0)));
      st.shown = true;
      st.lastSig = sig;
      st.lastSent = tick;
    }
  }

  private record Item(int x, LocatorFont.Glyph glyph, TextColor tint) {
  }

  private String layout(Player player, State st, List<Item> out) {
    Location eye = player.getEyeLocation();
    Location feet = player.getLocation();
    float yaw = eye.getYaw();
    float pitch = eye.getPitch();
    int frame = font.arrowFrame(tick);
    StringBuilder sig = new StringBuilder();
    for (Wp wp : st.waypoints.values()) {
      if (wp.entity() != null && wp.entity().equals(player.getUniqueId())) {
        continue;
      }
      double[] at = position(wp, player, eye, feet);
      double angle = yawAngle(wp, at, eye, yaw);
      if (Double.isNaN(angle) || angle <= -VISIBLE_DEGREES || angle > VISIBLE_DEGREES) {
        continue;
      }
      double dist = distance(wp, feet);
      LocatorFont.SpriteStyle style = font.style(wp.style());
      String sprite = sprite(style, (float) dist);
      LocatorFont.Glyph dot = font.glyph("dot_" + sprite);
      int dotPos = (int) Math.floor(angle * 173.0 / 2.0 / VISIBLE_DEGREES);
      int x = -4 + dotPos;
      TextColor tint = TextColor.color(color(wp));
      out.add(new Item(x, dot, tint));
      int dir = pitchDirection(wp, at, eye, pitch, yaw);
      if (dir != 0) {
        out.add(new Item(x + 1, font.glyph((dir > 0 ? "arrow_up_" : "arrow_down_") + frame), NamedTextColor.WHITE));
      }
      sig.append(x).append(sprite).append(tint.value()).append(dir).append(dir != 0 ? frame : 0).append(';');
    }
    out.sort(Comparator.comparingInt(Item::x));
    return sig.toString();
  }

  private Component compose(List<Item> items, int level) {
    LocatorFont.Run run = font.run();
    LocatorFont.Glyph bg = font.glyph("background");
    run.moveTo(-font.barWidth() / 2 - START);
    run.glyph(bg, NamedTextColor.WHITE);
    for (Item item : items) {
      run.moveTo(item.x() - START);
      run.glyph(item.glyph(), item.tint());
    }
    if (level > 0) {
      String number = Integer.toString(level);
      int width = font.digitsWidth(number);
      int left = -((width + 1) / 2);
      int[][] outline = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      for (int[] off : outline) {
        run.moveTo(left + off[0] - START);
        run.digits(number, BLACK, off[1], true);
      }
      run.moveTo(left - START);
      run.digits(number, XP_GREEN, 0, false);
    }
    run.moveTo(NET_ADVANCE);
    return run.build();
  }

  private static String sprite(LocatorFont.SpriteStyle style, float distance) {
    List<String> sprites = style.sprites();
    if (distance < style.near()) {
      return sprites.get(0);
    }
    if (distance >= style.far()) {
      return sprites.get(sprites.size() - 1);
    }
    if (sprites.size() == 1) {
      return sprites.get(0);
    }
    if (sprites.size() == 3) {
      return sprites.get(1);
    }
    float t = (distance - style.near()) / (style.far() - style.near());
    int index = 1 + (int) Math.floor(t * (sprites.size() - 2));
    return sprites.get(index);
  }

  private static int color(Wp wp) {
    if (wp.color() != null) {
      return wp.color() & 0xFFFFFF;
    }
    int hash = wp.entity() != null ? wp.entity().hashCode() : wp.key().substring(2).hashCode();
    return setBrightness(hash & 0xFFFFFF, 0.9f);
  }

  static int setBrightness(int rgb, float brightness) {
    int red = (rgb >> 16) & 255;
    int green = (rgb >> 8) & 255;
    int blue = rgb & 255;
    int max = Math.max(Math.max(red, green), blue);
    int min = Math.min(Math.min(red, green), blue);
    float range = max - min;
    float saturation = max != 0 ? range / max : 0.0f;
    float hue;
    if (saturation == 0.0f) {
      hue = 0.0f;
    } else {
      float cr = (max - red) / range;
      float cg = (max - green) / range;
      float cb = (max - blue) / range;
      if (red == max) {
        hue = cb - cg;
      } else if (green == max) {
        hue = 2.0f + cr - cb;
      } else {
        hue = 4.0f + cg - cr;
      }
      hue /= 6.0f;
      if (hue < 0.0f) {
        hue++;
      }
    }
    if (saturation == 0.0f) {
      int v = Math.round(brightness * 255.0f);
      return (v << 16) | (v << 8) | v;
    }
    float segment = (hue - (float) Math.floor(hue)) * 6.0f;
    float offset = segment - (float) Math.floor(segment);
    float primary = brightness * (1.0f - saturation);
    float secondary = brightness * (1.0f - saturation * offset);
    float tertiary = brightness * (1.0f - saturation * (1.0f - offset));
    int r;
    int g;
    int b;
    switch ((int) segment) {
      case 0 -> {
        r = Math.round(brightness * 255.0f);
        g = Math.round(tertiary * 255.0f);
        b = Math.round(primary * 255.0f);
      }
      case 1 -> {
        r = Math.round(secondary * 255.0f);
        g = Math.round(brightness * 255.0f);
        b = Math.round(primary * 255.0f);
      }
      case 2 -> {
        r = Math.round(primary * 255.0f);
        g = Math.round(brightness * 255.0f);
        b = Math.round(tertiary * 255.0f);
      }
      case 3 -> {
        r = Math.round(primary * 255.0f);
        g = Math.round(secondary * 255.0f);
        b = Math.round(brightness * 255.0f);
      }
      case 4 -> {
        r = Math.round(tertiary * 255.0f);
        g = Math.round(primary * 255.0f);
        b = Math.round(brightness * 255.0f);
      }
      default -> {
        r = Math.round(brightness * 255.0f);
        g = Math.round(primary * 255.0f);
        b = Math.round(secondary * 255.0f);
      }
    }
    return (r << 16) | (g << 8) | b;
  }

  private static double[] position(Wp wp, Player player, Location eye, Location feet) {
    if (wp.kind() == Kind.VEC3I) {
      if (wp.entity() != null) {
        Entity ent = Bukkit.getEntity(wp.entity());
        if (ent != null && ent.getWorld().equals(player.getWorld())) {
          Location loc = ent.getLocation();
          int manhattan = Math.abs(loc.getBlockX() - (int) wp.x()) + Math.abs(loc.getBlockY() - (int) wp.y())
              + Math.abs(loc.getBlockZ() - (int) wp.z());
          if (manhattan <= 3) {
            Location head = ent instanceof org.bukkit.entity.LivingEntity living ? living.getEyeLocation() : loc;
            return new double[] {head.getX(), head.getY(), head.getZ()};
          }
        }
      }
      return new double[] {wp.x() + 0.5, wp.y() + 0.5, wp.z() + 0.5};
    }
    if (wp.kind() == Kind.CHUNK) {
      return new double[] {wp.x() * 16 + 8.5, Math.floor(eye.getY()) + 0.5, wp.z() * 16 + 8.5};
    }
    return null;
  }

  private static double yawAngle(Wp wp, double[] at, Location eye, float cameraYaw) {
    float waypointAngle;
    if (wp.kind() == Kind.AZIMUTH) {
      waypointAngle = (float) wp.angle() * (180.0f / (float) Math.PI);
    } else {
      double dx = eye.getX() - at[0];
      double dz = eye.getZ() - at[2];
      waypointAngle = (float) Math.atan2(dx, -dz) * (180.0f / (float) Math.PI);
    }
    return wrapDegrees(waypointAngle - cameraYaw);
  }

  private static float wrapDegrees(float angle) {
    float norm = angle % 360.0f;
    if (norm >= 180.0f) {
      norm -= 360.0f;
    }
    if (norm < -180.0f) {
      norm += 360.0f;
    }
    return norm;
  }

  private static double distance(Wp wp, Location feet) {
    return switch (wp.kind()) {
      case VEC3I -> Math.sqrt(sq(feet.getX() - (wp.x() + 0.5)) + sq(feet.getY() - (wp.y() + 0.5)) + sq(feet.getZ() - (wp.z() + 0.5)));
      case CHUNK -> Math.sqrt(sq(feet.getX() - (wp.x() * 16 + 8.5)) + sq(feet.getY() - (feet.getBlockY() + 0.5)) + sq(feet.getZ() - (wp.z() * 16 + 8.5)));
      case AZIMUTH -> Double.POSITIVE_INFINITY;
    };
  }

  private static double sq(double v) {
    return v * v;
  }

  private int pitchDirection(Wp wp, double[] at, Location eye, float pitch, float yaw) {
    double tanHalf = Math.tan(Math.toRadians(fov / 2.0));
    if (wp.kind() != Kind.VEC3I) {
      if (pitch <= -90.0f) {
        return -1;
      }
      if (pitch >= 90.0f) {
        return 1;
      }
      double horizon = Math.tan(Math.toRadians(pitch)) / tanHalf;
      return horizon < -1.0 ? -1 : horizon > 1.0 ? 1 : 0;
    }
    double yawRad = Math.toRadians(yaw);
    double pitchRad = Math.toRadians(pitch);
    double fx = -Math.sin(yawRad) * Math.cos(pitchRad);
    double fy = -Math.sin(pitchRad);
    double fz = Math.cos(yawRad) * Math.cos(pitchRad);
    double rx = -Math.cos(yawRad);
    double rz = -Math.sin(yawRad);
    double ux = -fy * rz;
    double uy = -(fz * rx - fx * rz);
    double uz = fy * rx;
    double dx = at[0] - eye.getX();
    double dy = at[1] - eye.getY();
    double dz = at[2] - eye.getZ();
    double depth = dx * fx + dy * fy + dz * fz;
    double up = dx * ux + dy * uy + dz * uz;
    boolean behind = depth < 0;
    if (depth == 0) {
      return 0;
    }
    double yn = up / (Math.abs(depth) * tanHalf);
    if (yn < -1.0) {
      return -1;
    }
    if (yn > 1.0) {
      return 1;
    }
    if (behind) {
      if (-yn > 0.0) {
        return 1;
      }
      if (-yn < 0.0) {
        return -1;
      }
    }
    return 0;
  }

  public void test(Player player, boolean barFirst) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user == null) {
      return;
    }
    LocatorFont.Run run = font.run();
    LocatorFont.Glyph bg = font.glyph("background");
    LocatorFont.Glyph dot = font.glyph("dot_default_0");
    TextColor red = TextColor.color(0xFF3030);
    if (barFirst) {
      run.moveTo(-font.barWidth() / 2 - START);
      run.glyph(bg, NamedTextColor.WHITE);
      run.moveTo(-4 - START);
      run.glyph(dot, red);
    } else {
      run.moveTo(-4 - START);
      run.glyph(dot, red);
      run.moveTo(-font.barWidth() / 2 - START);
      run.glyph(bg, NamedTextColor.WHITE);
    }
    run.moveTo(NET_ADVANCE);
    user.sendPacketSilently(new WrapperPlayServerActionBar(run.build()));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    states.remove(event.getPlayer().getUniqueId());
  }
}
