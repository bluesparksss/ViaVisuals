package dev.bluesparksss.viavisuals.entity;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MobBoxes {

  public static final ProtocolVersion MIN_CLIENT = ProtocolVersion.v1_19_4;
  private static final int WIDTH = 8;
  private static final int HEIGHT = 9;
  private static final float SIZE_EPS = 0.001f;

  private volatile boolean enabled;
  private volatile boolean debug;
  private final java.util.logging.Logger log = java.util.logging.Logger.getLogger("ViaVisuals");
  private final Map<UUID, Set<Integer>> boxed = new ConcurrentHashMap<>();
  private final Map<UUID, Set<Integer>> living = new ConcurrentHashMap<>();
  private final Map<UUID, Map<Integer, float[]>> sent = new ConcurrentHashMap<>();

  public void configure(org.bukkit.configuration.file.FileConfiguration conf) {
    enabled = "interaction".equalsIgnoreCase(conf.getString("entities.hitbox-mode", "interaction"));
    debug = conf.getBoolean("debug.hitboxes", false);
  }

  public boolean enabled() {
    return enabled;
  }

  public boolean wants(UUID viewer, int entId, ProtocolVersion client) {
    return enabled && !client.olderThan(MIN_CLIENT) && !set(living, viewer).contains(entId);
  }

  public boolean isBoxed(UUID viewer, int entId) {
    Set<Integer> ids = boxed.get(viewer);
    return ids != null && ids.contains(entId);
  }

  public void markBoxed(UUID viewer, int entId) {
    if (debug) {
      log.info("hitbox: mob " + entId + " sent to " + viewer + " as an interaction entity");
    }
    set(boxed, viewer).add(entId);
    Map<Integer, float[]> mine = sent.get(viewer);
    if (mine != null) {
      mine.remove(entId);
    }
  }

  public void remove(UUID viewer, int entId) {
    Set<Integer> ids = boxed.get(viewer);
    if (ids != null) {
      ids.remove(entId);
    }
    Map<Integer, float[]> mine = sent.get(viewer);
    if (mine != null) {
      mine.remove(entId);
    }
  }

  public void forget(UUID viewer) {
    boxed.remove(viewer);
    living.remove(viewer);
    sent.remove(viewer);
  }

  public float[] size(UUID viewer, int entId) {
    Map<Integer, float[]> mine = sent.get(viewer);
    return mine == null ? null : mine.get(entId);
  }

  public static PacketWrapper<?> poof(double x, double y, double z, float width, float height) {
    return new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle(
        new com.github.retrooper.packetevents.protocol.particle.Particle<>(
            com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes.POOF),
        false, new com.github.retrooper.packetevents.util.Vector3d(x, y + height / 2.0, z),
        new com.github.retrooper.packetevents.util.Vector3f(width / 2f, height / 2f, width / 2f), 0.02f, 20);
  }

  public static PacketWrapper<?> sizeData(int entId, float width, float height) {
    return new WrapperPlayServerEntityMetadata(entId, List.of(
        new EntityData<>(WIDTH, EntityDataTypes.FLOAT, width),
        new EntityData<>(HEIGHT, EntityDataTypes.FLOAT, height)));
  }

  public boolean dropFor(PacketSendEvent event, UUID viewer) {
    Set<Integer> ids = boxed.get(viewer);
    if (ids == null || ids.isEmpty()) {
      return false;
    }
    var type = event.getPacketType();
    int target;
    if (type == PacketType.Play.Server.UPDATE_ATTRIBUTES) {
      target = new WrapperPlayServerUpdateAttributes(event).getEntityId();
    } else if (type == PacketType.Play.Server.COLLECT_ITEM) {
      target = new WrapperPlayServerCollectItem(event).getCollectorEntityId();
    } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
      target = new WrapperPlayServerEntityEquipment(event).getEntityId();
    } else if (type == PacketType.Play.Server.ENTITY_ANIMATION) {
      WrapperPlayServerEntityAnimation pkt = new WrapperPlayServerEntityAnimation(event);
      boolean swing = pkt.getType() == WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM
          || pkt.getType() == WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND;
      target = swing ? pkt.getEntityId() : -1;
    } else if (type == PacketType.Play.Server.SWING_ANIMATION) {
      target = new WrapperPlayServerSwingAnimation(event).getEntityId();
    } else {
      return false;
    }
    if (ids.contains(target)) {
      event.setCancelled(true);
      return true;
    }
    return false;
  }

  public void tick(int entId, Entity entity, Set<UUID> viewers, java.util.function.Consumer<Player> respawn) {
    if (!enabled) {
      return;
    }
    float width = (float) entity.getWidth();
    float height = (float) entity.getHeight();
    boolean leashed = entity instanceof LivingEntity liv && liv.isLeashed();
    for (UUID viewer : viewers) {
      Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      boolean wantLiving = leashed || entity.equals(player.getVehicle());
      Set<Integer> liv = set(living, viewer);
      if (wantLiving != liv.contains(entId)) {
        if (wantLiving) {
          liv.add(entId);
        } else {
          liv.remove(entId);
        }
        respawn.accept(player);
        continue;
      }
      if (!isBoxed(viewer, entId)) {
        continue;
      }
      Map<Integer, float[]> mine = sent.computeIfAbsent(viewer, k -> new ConcurrentHashMap<>());
      float[] last = mine.get(entId);
      if (last == null || Math.abs(last[0] - width) > SIZE_EPS || Math.abs(last[1] - height) > SIZE_EPS) {
        mine.put(entId, new float[] {width, height});
        if (debug) {
          log.info("hitbox: mob " + entId + " " + entity.getType() + " box " + width + " x " + height);
        }
        com.github.retrooper.packetevents.protocol.player.User user =
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user != null) {
          dev.bluesparksss.viavisuals.display.ProxyEntities.sendOne(user, sizeData(entId, width, height));
        }
      }
    }
  }

  private static Set<Integer> set(Map<UUID, Set<Integer>> map, UUID viewer) {
    return map.computeIfAbsent(viewer, k -> ConcurrentHashMap.newKeySet());
  }
}
