package dev.bluesparksss.viavisuals.entity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.display.ProxyEntities;
import dev.bluesparksss.viavisuals.version.VersionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

public final class NameTags {

  private static final int ENTITY_FLAGS = 0;
  private static final int CUSTOM_NAME = 2;
  private static final int CUSTOM_NAME_VISIBLE = 3;
  private static final int NO_GRAVITY = 5;
  private static final int POS_ROT_INTERP = 10;
  private static final int BILLBOARD = 15;
  private static final int TEXT = 23;
  private static final int TEXT_STYLE = 27;
  private static final int STAND_FLAGS = 15;
  private static final byte INVISIBLE = 0x20;
  private static final byte BILLBOARD_CENTER = 3;
  private static final byte DEFAULT_BACKGROUND = 4;
  private static final byte STAND_MARKER_NOBASE = 0x10 | 0x08;
  private static final double RANGE_SQ = 64.0 * 64.0;
  private static final double LIFT = 0.5;
  private static final ProtocolVersion TEXT_DISPLAY = ProtocolVersion.v1_19_4;

  private static final class Tag {
    final int id;
    final boolean display;
    String sig;
    double x;
    double y;
    double z;

    Tag(int id, boolean display) {
      this.id = id;
      this.display = display;
    }
  }

  private final BiPredicate<UUID, Integer> standIn;
  private final BiFunction<UUID, Integer, double[]> where;
  private final Map<UUID, Map<Integer, Tag>> tags = new ConcurrentHashMap<>();
  private volatile boolean enabled = true;
  private long tick;
  private long targetTick = -1;
  private final Map<UUID, Integer> targets = new ConcurrentHashMap<>();

  public NameTags(BiPredicate<UUID, Integer> standIn, BiFunction<UUID, Integer, double[]> where) {
    this.standIn = standIn;
    this.where = where;
  }

  public void configure(org.bukkit.configuration.file.FileConfiguration conf) {
    enabled = conf.getBoolean("entities.name-tags", true);
  }

  public void tick(int entId, Entity entity, Set<UUID> viewers) {
    if (!enabled) {
      return;
    }
    Component name = entity.customName();
    for (UUID viewer : viewers) {
      Player player = Bukkit.getPlayer(viewer);
      Optional<ProtocolVersion> client = VersionManager.tryGetVisualVersion(viewer);
      if (player == null || client.isEmpty()) {
        continue;
      }
      boolean want = name != null && standIn.test(viewer, entId) && shown(player, entity) && inRange(player, entity);
      Map<Integer, Tag> mine = tags.computeIfAbsent(viewer, k -> new ConcurrentHashMap<>());
      Tag tag = mine.get(entId);
      User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
      if (user == null) {
        continue;
      }
      if (!want) {
        if (tag != null) {
          mine.remove(entId);
          ProxyEntities.sendOne(user, ProxyEntities.destroy(tag.id));
        }
        continue;
      }
      double[] at = where.apply(viewer, entId);
      double x = at != null ? at[0] : entity.getLocation().getX();
      double y = (at != null ? at[1] : entity.getLocation().getY()) + entity.getHeight();
      double z = at != null ? at[2] : entity.getLocation().getZ();
      boolean display = !client.get().olderThan(TEXT_DISPLAY);
      String sig = GsonComponentSerializer.gson().serialize(name);
      if (tag == null) {
        tag = new Tag(ProxyEntities.nextId(), display);
        mine.put(entId, tag);
        tag.sig = sig;
        tag.x = x;
        tag.y = y;
        tag.z = z;
        ProxyEntities.send(user, spawn(tag, name, x, y, z));
        continue;
      }
      List<PacketWrapper<?>> out = new ArrayList<>(2);
      if (!sig.equals(tag.sig)) {
        tag.sig = sig;
        out.add(display ? new WrapperPlayServerEntityMetadata(tag.id, List.of(new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, name)))
            : new WrapperPlayServerEntityMetadata(tag.id, List.of(
                new EntityData<>(CUSTOM_NAME, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(name)))));
      }
      if (Math.abs(tag.x - x) > 0.001 || Math.abs(tag.y - y) > 0.001 || Math.abs(tag.z - z) > 0.001) {
        tag.x = x;
        tag.y = y;
        tag.z = z;
        out.add(new WrapperPlayServerEntityTeleport(tag.id, new Vector3d(x, tagY(tag, y), z), 0f, 0f, false));
      }
      ProxyEntities.send(user, out);
    }
  }

  private static double tagY(Tag tag, double top) {
    return tag.display ? top + LIFT : top;
  }

  private List<PacketWrapper<?>> spawn(Tag tag, Component name, double x, double y, double z) {
    List<PacketWrapper<?>> out = new ArrayList<>(2);
    Vector3d pos = new Vector3d(x, tagY(tag, y), z);
    if (tag.display) {
      out.add(new WrapperPlayServerSpawnEntity(tag.id, Optional.of(UUID.randomUUID()), EntityTypes.TEXT_DISPLAY, pos, 0f, 0f, 0f, 0,
          Optional.empty()));
      out.add(new WrapperPlayServerEntityMetadata(tag.id, List.of(
          new EntityData<>(POS_ROT_INTERP, EntityDataTypes.INT, 1),
          new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
          new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, name),
          new EntityData<>(TEXT_STYLE, EntityDataTypes.BYTE, DEFAULT_BACKGROUND))));
    } else {
      out.add(new WrapperPlayServerSpawnEntity(tag.id, Optional.of(UUID.randomUUID()), EntityTypes.ARMOR_STAND, pos, 0f, 0f, 0f, 0,
          Optional.empty()));
      out.add(new WrapperPlayServerEntityMetadata(tag.id, List.of(
          new EntityData<>(ENTITY_FLAGS, EntityDataTypes.BYTE, INVISIBLE),
          new EntityData<>(CUSTOM_NAME, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(name)),
          new EntityData<>(CUSTOM_NAME_VISIBLE, EntityDataTypes.BOOLEAN, true),
          new EntityData<>(NO_GRAVITY, EntityDataTypes.BOOLEAN, true),
          new EntityData<>(STAND_FLAGS, EntityDataTypes.BYTE, STAND_MARKER_NOBASE))));
    }
    return out;
  }

  private boolean shown(Player viewer, Entity entity) {
    if (entity.isCustomNameVisible()) {
      return true;
    }
    if (targetTick != tick) {
      targets.clear();
      targetTick = tick;
    }
    Integer target = targets.computeIfAbsent(viewer.getUniqueId(), id -> {
      Entity hit = viewer.getTargetEntity(64);
      return hit == null ? -1 : hit.getEntityId();
    });
    return target == entity.getEntityId();
  }

  private static boolean inRange(Player viewer, Entity entity) {
    return viewer.getWorld().equals(entity.getWorld()) && viewer.getLocation().distanceSquared(entity.getLocation()) < RANGE_SQ;
  }

  public void nextTick() {
    tick++;
  }

  public void removeEntity(UUID viewer, int entId) {
    Map<Integer, Tag> mine = tags.get(viewer);
    Tag tag = mine == null ? null : mine.remove(entId);
    if (tag != null) {
      Player player = Bukkit.getPlayer(viewer);
      User user = player == null ? null : PacketEvents.getAPI().getPlayerManager().getUser(player);
      if (user != null) {
        ProxyEntities.sendOne(user, ProxyEntities.destroy(tag.id));
      }
    }
  }

  public void forget(UUID viewer, boolean destroy) {
    Map<Integer, Tag> mine = tags.remove(viewer);
    if (mine == null || !destroy) {
      return;
    }
    Player player = Bukkit.getPlayer(viewer);
    User user = player == null ? null : PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user != null && !mine.isEmpty()) {
      ProxyEntities.sendOne(user, ProxyEntities.destroy(mine.values().stream().mapToInt(t -> t.id).toArray()));
    }
  }
}
