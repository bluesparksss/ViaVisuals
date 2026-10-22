package dev.bluesparksss.viavisuals.entity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.compat.RegistrySince;
import dev.bluesparksss.viavisuals.display.ProxyEntities;
import dev.bluesparksss.viavisuals.entity.anim.AnimCatalog;
import dev.bluesparksss.viavisuals.entity.anim.AnimDriver;
import dev.bluesparksss.viavisuals.entity.anim.AnimLook;
import dev.bluesparksss.viavisuals.entity.anim.AnimStandView;
import dev.bluesparksss.viavisuals.entity.anim.AnimView;
import dev.bluesparksss.viavisuals.entity.anim.AnimatedMob;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class EntityProxies extends PacketListenerAbstract implements Listener {

  private static final ProtocolVersion SMOOTH_DISPLAYS = ProtocolVersion.v1_20_2;
  private static final double STAND_OFFSET = -(1.501 - 1.0 / 16.0 + 0.25 - 0.625 * 19.3 / 16.0);
  private static final float SPIN_RESEND_DEG = 0.5f;
  private static final int HURT_TICKS = 10;
  private static final int DEATH_TICKS = 20;
  private static final byte INVISIBLE = 0x20;
  private static final int LAST_MOB_INDEX = 15;
  private static final int LAST_ENTITY_INDEX = 7;
  private static final ProtocolVersion INTERACTION_ENTITY = ProtocolVersion.v1_19_4;
  private static final int INTERACTION_WIDTH = 8;
  private static final int INTERACTION_HEIGHT = 9;
  private static final float BOAT_WIDTH = 1.375f;
  private static final float BOAT_HEIGHT = 0.5625f;
  private static final int STAND_NO_GRAVITY = 5;
  private static final int STAND_FLAGS = 15;
  private static final byte STAND_NO_BASEPLATE = 0x08;
  private static final double FULL_RATE_RANGE = 16.0;
  private static final int MID_RATE_TICKS = 2;

  private static final class Proxy {
    final ProtocolVersion client;
    final int mainId;
    final double[] at;
    final Map<String, Integer> partIds = new HashMap<>();
    String shownKey;
    int shownCmd;
    AnimLook anim;
    int every = 1;

    Proxy(ProtocolVersion client, int mainId, double[] at) {
      this.client = client;
      this.mainId = mainId;
      this.at = at;
    }

    Vector3d basePos() {
      synchronized (at) {
        return new Vector3d(at[0], at[1], at[2]);
      }
    }

    int[] moveIds() {
      return anim != null ? anim.moveIds() : allIds();
    }

    int[] allIds() {
      if (anim != null) {
        return anim.allIds();
      }
      int[] out = new int[1 + partIds.size()];
      out[0] = mainId;
      int i = 1;
      for (int id : partIds.values()) {
        out[i++] = id;
      }
      return out;
    }
  }

  private static final class Tracked {
    final UUID uuid;
    final EntityCatalog.Def def;
    AppearanceResolver.State state;
    int hurtTicks;
    int deathTicks = -1;
    float xBodyRot;
    float zBodyRot;
    float rotateSpeed;
    float tentacle;
    float tentacleSpeed;
    Quaternion4f lastRot;
    double px;
    double py;
    double pz;
    float yaw;
    final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    AnimatedMob anim;
    boolean animFailed;
    String animKey;
    float lastHead = Float.NaN;
    float deathRoll;
    final Set<Integer> events = ConcurrentHashMap.newKeySet();

    Tracked(UUID uuid, EntityCatalog.Def def) {
      this.uuid = uuid;
      this.def = def;
    }
  }

  private final JavaPlugin plugin;
  private final EntityCatalog catalog;
  private final AnimCatalog animCatalog;
  private final Map<Integer, Tracked> tracked = new ConcurrentHashMap<>();
  private final Map<UUID, Map<Integer, Proxy>> byViewer = new ConcurrentHashMap<>();
  private final Map<UUID, Map<Integer, double[]>> clientAt = new ConcurrentHashMap<>();
  private final Map<UUID, Set<Integer>> disguised = new ConcurrentHashMap<>();
  private final Map<UUID, Set<Integer>> riding = new ConcurrentHashMap<>();
  private final Map<UUID, Set<Integer>> retyped = new ConcurrentHashMap<>();
  private final Map<UUID, Set<Integer>> retypedPlain = new ConcurrentHashMap<>();
  private final Map<Integer, Float> headYaws = new ConcurrentHashMap<>();
  private final MobBoxes boxes = new MobBoxes();
  private final NameTags names = new NameTags(
      (viewer, id) -> {
        Set<Integer> ids = retyped.get(viewer);
        return ids != null && ids.contains(id) && !boxes.isBoxed(viewer, id);
      },
      (viewer, id) -> {
        Map<Integer, double[]> mine = clientAt.get(viewer);
        double[] at = mine == null ? null : mine.get(id);
        if (at == null) {
          return null;
        }
        synchronized (at) {
          return new double[] {at[0], at[1], at[2]};
        }
      });
  private int tick;

  public EntityProxies(JavaPlugin plugin, EntityCatalog catalog, AnimCatalog animCatalog) {
    super(PacketListenerPriority.HIGH);
    this.plugin = plugin;
    boxes.configure(plugin.getConfig());
    names.configure(plugin.getConfig());
    this.catalog = catalog;
    this.animCatalog = animCatalog;
    Bukkit.getScheduler().runTaskTimer(plugin, this::tickMain, 1L, 1L);
  }

  public MobBoxes boxes() {
    return boxes;
  }

  public void configure(org.bukkit.configuration.file.FileConfiguration conf) {
    boxes.configure(conf);
    names.configure(conf);
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty() || !proto.get().olderThan(RegistrySince.serverVersion())) {
      return;
    }
    try {
      handleSend(event, player, proto.get());
    } catch (RuntimeException ex) {
      plugin.getLogger().log(Level.WARNING, "Entity rewrite failed for " + event.getPacketType(), ex);
    }
  }

  private void handleSend(PacketSendEvent event, Player player, ProtocolVersion client) {
    var type = event.getPacketType();
    User user = event.getUser();
    Map<Integer, Proxy> proxies = byViewer.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
    if (type != PacketType.Play.Server.ENTITY_ANIMATION && type != PacketType.Play.Server.SWING_ANIMATION
        && boxes.dropFor(event, player.getUniqueId())) {
      return;
    }
    if (type == PacketType.Play.Server.SPAWN_ENTITY) {
      WrapperPlayServerSpawnEntity pkt = new WrapperPlayServerSpawnEntity(event);
      String id = pkt.getEntityType().getName().getKey();
      EntityCatalog.Def def = catalog.def(id);
      if (def == null) {
        return;
      }
      Vector3d spawnAt = pkt.getPosition();
      headYaws.putIfAbsent(pkt.getEntityId(), Float.NaN);
      viewerAt(player).put(pkt.getEntityId(), new double[] {spawnAt.getX(), spawnAt.getY(), spawnAt.getZ()});
      if (def.vehicle()) {
        if (!disguiseVehicle(event, pkt, player, client, def)) {
          return;
        }
      } else if (!RegistrySince.get().isNative(RegistrySince.Kind.ENTITY, id, client) && def.fallback() != null) {
        EntityType fallback = EntityTypes.getByName("minecraft:" + def.fallback().toLowerCase(java.util.Locale.ROOT));
        if (fallback != null) {
          boolean box = boxes.wants(player.getUniqueId(), pkt.getEntityId(), client);
          if (box || !EntityTypes.isTypeInstanceOf(pkt.getEntityType(), EntityTypes.LIVINGENTITY)) {
            viewerSet(retypedPlain, player).add(pkt.getEntityId());
          }
          if (box) {
            int boxId = pkt.getEntityId();
            pkt.setEntityType(EntityTypes.INTERACTION);
            boxes.markBoxed(player.getUniqueId(), boxId);
            event.getTasksAfterSend().add(() -> ProxyEntities.sendOne(user, MobBoxes.sizeData(boxId, 0.6f, 1.8f)));
          } else {
            pkt.setEntityType(fallback);
          }
          event.markForReEncode(true);
          viewerSet(retyped, player).add(pkt.getEntityId());
        }
      }
      int entId = pkt.getEntityId();
      Optional<UUID> uuid = pkt.getUUID();
      UUID viewer = player.getUniqueId();
      if (uuid.isPresent()) {
        Bukkit.getScheduler().runTask(plugin, () -> onSpawnSeen(viewer, client, entId, uuid.get(), def));
      }
      return;
    }
    if (type == PacketType.Play.Server.ENTITY_METADATA) {
      WrapperPlayServerEntityMetadata pkt = new WrapperPlayServerEntityMetadata(event);
      if (viewerSet(disguised, player).contains(pkt.getEntityId())) {
        pkt.getEntityMetadata().removeIf(data -> data.getIndex() != 0);
        for (int i = 0; i < pkt.getEntityMetadata().size(); i++) {
          if (pkt.getEntityMetadata().get(i).getValue() instanceof Byte flags) {
            pkt.getEntityMetadata().set(i, new EntityData<>(0, EntityDataTypes.BYTE, (byte) (flags | INVISIBLE)));
          }
        }
        if (pkt.getEntityMetadata().isEmpty()) {
          event.setCancelled(true);
        } else {
          event.markForReEncode(true);
        }
        return;
      }
      int lastShared = viewerSet(retypedPlain, player).contains(pkt.getEntityId()) ? LAST_ENTITY_INDEX : LAST_MOB_INDEX;
      if (viewerSet(retyped, player).contains(pkt.getEntityId())
          && pkt.getEntityMetadata().removeIf(data -> data.getIndex() > lastShared)) {
        event.markForReEncode(true);
        if (pkt.getEntityMetadata().isEmpty()) {
          event.setCancelled(true);
          return;
        }
      }
      if (!proxies.containsKey(pkt.getEntityId()) || boxes.isBoxed(player.getUniqueId(), pkt.getEntityId())) {
        return;
      }
      for (int i = 0; i < pkt.getEntityMetadata().size(); i++) {
        EntityData<?> data = pkt.getEntityMetadata().get(i);
        if (data.getIndex() == 0 && data.getValue() instanceof Byte flags) {
          pkt.getEntityMetadata().set(i, new EntityData<>(0, EntityDataTypes.BYTE, (byte) (flags | INVISIBLE)));
          event.markForReEncode(true);
        }
      }
      return;
    }
    if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
      WrapperPlayServerDestroyEntities pkt = new WrapperPlayServerDestroyEntities(event);
      List<Integer> drop = new ArrayList<>();
      for (int id : pkt.getEntityIds()) {
        if (!tracked.containsKey(id)) {
          headYaws.remove(id);
        }
        viewerSet(disguised, player).remove(id);
        viewerSet(riding, player).remove(id);
        viewerSet(retyped, player).remove(id);
        viewerSet(retypedPlain, player).remove(id);
        boxes.remove(player.getUniqueId(), id);
        names.removeEntity(player.getUniqueId(), id);
        viewerAt(player).remove(id);
        Proxy prx = proxies.remove(id);
        if (prx != null) {
          for (int pid : prx.allIds()) {
            drop.add(pid);
          }
        }
        Tracked trk = tracked.get(id);
        if (trk != null) {
          trk.viewers.remove(player.getUniqueId());
        }
      }
      if (!drop.isEmpty()) {
        event.getTasksAfterSend().add(() -> ProxyEntities.sendOne(user, 
            ProxyEntities.destroy(drop.stream().mapToInt(Integer::intValue).toArray())));
      }
      return;
    }
    if (type == PacketType.Play.Server.SET_PASSENGERS) {
      WrapperPlayServerSetPassengers pkt = new WrapperPlayServerSetPassengers(event);
      if (swapRiddenVehicle(event, pkt, player, client)) {
        return;
      }
      List<Integer> ids = new ArrayList<>();
      boolean dirty = false;
      for (int pid : pkt.getPassengers()) {
        ids.add(pid);
        Proxy prx = proxies.get(pid);
        if (prx != null) {
          for (int extra : prx.moveIds()) {
            ids.add(extra);
          }
          dirty = true;
        }
      }
      if (dirty) {
        pkt.setPassengers(ids.stream().mapToInt(Integer::intValue).toArray());
        event.markForReEncode(true);
      }
      return;
    }
    if (type == PacketType.Play.Server.ENTITY_ANIMATION || type == PacketType.Play.Server.SWING_ANIMATION) {
      int swingId;
      boolean swing = true;
      if (type == PacketType.Play.Server.ENTITY_ANIMATION) {
        WrapperPlayServerEntityAnimation pkt = new WrapperPlayServerEntityAnimation(event);
        swingId = pkt.getEntityId();
        swing = pkt.getType() == WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM
            || pkt.getType() == WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND;
      } else {
        swingId = new WrapperPlayServerSwingAnimation(event).getEntityId();
      }
      Tracked swinger = tracked.get(swingId);
      if (swing && swinger != null && swinger.anim != null) {
        swinger.events.add(AnimDriver.EVENT_SWING);
      }
      boxes.dropFor(event, player.getUniqueId());
      return;
    }
    if (type == PacketType.Play.Server.ENTITY_STATUS) {
      WrapperPlayServerEntityStatus pkt = new WrapperPlayServerEntityStatus(event);
      Tracked statusTrk = tracked.get(pkt.getEntityId());
      if (statusTrk != null && statusTrk.anim != null) {
        statusTrk.events.add((int) pkt.getStatus());
      }
      if (pkt.getStatus() == 60 && boxes.isBoxed(player.getUniqueId(), pkt.getEntityId())) {
        float[] box = boxes.size(player.getUniqueId(), pkt.getEntityId());
        double[] at = viewerAt(player).get(pkt.getEntityId());
        if (box != null && at != null) {
          event.getTasksAfterSend().add(() -> ProxyEntities.sendOne(user, MobBoxes.poof(at[0], at[1], at[2], box[0], box[1])));
        }
      }
      if (pkt.getStatus() == 3 && proxies.containsKey(pkt.getEntityId())) {
        int entId = pkt.getEntityId();
        Bukkit.getScheduler().runTask(plugin, () -> startDeath(entId));
      }
      return;
    }
    if (type == PacketType.Play.Server.DAMAGE_EVENT || type == PacketType.Play.Server.HURT_ANIMATION) {
      int entId = type == PacketType.Play.Server.DAMAGE_EVENT
          ? new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent(event).getEntityId()
          : new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation(event).getEntityId();
      if (proxies.containsKey(entId)) {
        Bukkit.getScheduler().runTask(plugin, () -> startHurt(entId));
      }
      return;
    }
    if (type == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
      WrapperPlayServerEntityHeadLook pkt = new WrapperPlayServerEntityHeadLook(event);
      if (headYaws.containsKey(pkt.getEntityId())) {
        headYaws.put(pkt.getEntityId(), pkt.getHeadYaw());
      }
      return;
    }
    mirrorMovement(event, user, proxies);
  }

  private void mirrorMovement(PacketSendEvent event, User user, Map<Integer, Proxy> proxies) {
    var type = event.getPacketType();
    List<PacketWrapper<?>> out = new ArrayList<>();
    Map<Integer, double[]> known = viewerAt(event.getPlayer());
    if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
      WrapperPlayServerEntityRelativeMove pkt = new WrapperPlayServerEntityRelativeMove(event);
      moveKnown(known, pkt.getEntityId(), pkt.getDeltaX(), pkt.getDeltaY(), pkt.getDeltaZ());
      Proxy prx = proxies.get(pkt.getEntityId());
      if (prx != null) {
        for (int id : prx.moveIds()) {
          out.add(new WrapperPlayServerEntityRelativeMove(id, pkt.getDeltaX(), pkt.getDeltaY(), pkt.getDeltaZ(), pkt.isOnGround()));
        }
      }
    } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
      WrapperPlayServerEntityRelativeMoveAndRotation pkt = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
      moveKnown(known, pkt.getEntityId(), pkt.getDeltaX(), pkt.getDeltaY(), pkt.getDeltaZ());
      Proxy prx = proxies.get(pkt.getEntityId());
      if (prx != null) {
        for (int id : prx.moveIds()) {
          out.add(new WrapperPlayServerEntityRelativeMoveAndRotation(id, pkt.getDeltaX(), pkt.getDeltaY(), pkt.getDeltaZ(),
              pkt.getYaw(), 0f, pkt.isOnGround()));
        }
      }
    } else if (type == PacketType.Play.Server.ENTITY_ROTATION) {
      WrapperPlayServerEntityRotation pkt = new WrapperPlayServerEntityRotation(event);
      Proxy prx = proxies.get(pkt.getEntityId());
      if (prx != null) {
        for (int id : prx.moveIds()) {
          out.add(new WrapperPlayServerEntityRotation(id, pkt.getYaw(), 0f, pkt.isOnGround()));
        }
      }
    } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
      WrapperPlayServerEntityTeleport pkt = new WrapperPlayServerEntityTeleport(event);
      mirrorAbsolute(out, known, proxies, pkt.getEntityId(), pkt.getPosition(), pkt.getRelativeFlags(), pkt.getYaw(), pkt.isOnGround());
    } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
      WrapperPlayServerEntityPositionSync pkt = new WrapperPlayServerEntityPositionSync(event);
      mirrorAbsolute(out, known, proxies, pkt.getId(), pkt.getValues().getPosition(), null, pkt.getValues().getYaw(), pkt.isOnGround());
    }
    if (!out.isEmpty()) {
      event.getTasksAfterSend().add(() -> {
        for (PacketWrapper<?> pkt : out) {
          ProxyEntities.sendOne(user, pkt);
        }
      });
    }
  }

  private void mirrorAbsolute(List<PacketWrapper<?>> out, Map<Integer, double[]> known, Map<Integer, Proxy> proxies,
                              int entId, Vector3d pos, RelativeFlag flags, float yaw, boolean onGround) {
    double[] at = known.get(entId);
    double x = pos.getX();
    double y = pos.getY();
    double z = pos.getZ();
    if (flags != null && at != null) {
      synchronized (at) {
        x += flags.has(RelativeFlag.X) ? at[0] : 0;
        y += flags.has(RelativeFlag.Y) ? at[1] : 0;
        z += flags.has(RelativeFlag.Z) ? at[2] : 0;
      }
    }
    if (at != null) {
      synchronized (at) {
        at[0] = x;
        at[1] = y;
        at[2] = z;
      }
    }
    Proxy prx = proxies.get(entId);
    if (prx == null) {
      return;
    }
    Vector3d dst = isStand(prx) && prx.anim == null ? new Vector3d(x, y + STAND_OFFSET, z) : new Vector3d(x, y, z);
    for (int id : prx.moveIds()) {
      out.add(new WrapperPlayServerEntityTeleport(id, dst, yaw, 0f, onGround));
    }
  }

  private static void moveKnown(Map<Integer, double[]> known, int entId, double dx, double dy, double dz) {
    double[] at = known.get(entId);
    if (at != null) {
      synchronized (at) {
        at[0] += dx;
        at[1] += dy;
        at[2] += dz;
      }
    }
  }

  private Map<Integer, double[]> viewerAt(Player player) {
    return clientAt.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
  }

  private static boolean isStand(Proxy prx) {
    return prx.client.olderThan(SMOOTH_DISPLAYS);
  }

  private static Set<Integer> viewerSet(Map<UUID, Set<Integer>> map, Player player) {
    return map.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
  }

  private boolean disguiseVehicle(PacketSendEvent event, WrapperPlayServerSpawnEntity pkt, Player player,
                                  ProtocolVersion client, EntityCatalog.Def def) {
    EntityCatalog.Appearance app = def.appearances().values().iterator().next();
    if (!client.olderThan(app.since())) {
      return false;
    }
    int entId = pkt.getEntityId();
    viewerSet(disguised, player).add(entId);
    pkt.setEntityType(disguiseType(client));
    event.markForReEncode(true);
    User user = event.getUser();
    event.getTasksAfterSend().add(() -> ProxyEntities.sendOne(user, disguiseData(entId, client)));
    return true;
  }

  private static EntityType disguiseType(ProtocolVersion client) {
    return client.olderThan(INTERACTION_ENTITY) ? EntityTypes.ARMOR_STAND : EntityTypes.INTERACTION;
  }

  private static WrapperPlayServerEntityMetadata disguiseData(int entId, ProtocolVersion client) {
    if (client.olderThan(INTERACTION_ENTITY)) {
      return new WrapperPlayServerEntityMetadata(entId, List.of(
          new EntityData<>(0, EntityDataTypes.BYTE, INVISIBLE),
          new EntityData<>(STAND_NO_GRAVITY, EntityDataTypes.BOOLEAN, true),
          new EntityData<>(STAND_FLAGS, EntityDataTypes.BYTE, STAND_NO_BASEPLATE)));
    }
    return new WrapperPlayServerEntityMetadata(entId, List.of(
        new EntityData<>(INTERACTION_WIDTH, EntityDataTypes.FLOAT, BOAT_WIDTH),
        new EntityData<>(INTERACTION_HEIGHT, EntityDataTypes.FLOAT, BOAT_HEIGHT)));
  }

  private boolean swapRiddenVehicle(PacketSendEvent event, WrapperPlayServerSetPassengers pkt, Player player,
                                    ProtocolVersion client) {
    int vehId = pkt.getEntityId();
    boolean aboard = false;
    for (int pid : pkt.getPassengers()) {
      aboard |= pid == player.getEntityId();
    }
    Set<Integer> dis = viewerSet(disguised, player);
    Set<Integer> rid = viewerSet(riding, player);
    boolean mount = aboard && dis.contains(vehId);
    boolean dismount = !aboard && rid.contains(vehId);
    if (!mount && !dismount) {
      return false;
    }
    event.setCancelled(true);
    int[] passengers = pkt.getPassengers();
    UUID viewer = player.getUniqueId();
    Bukkit.getScheduler().runTask(plugin, () -> {
      Player plr = Bukkit.getPlayer(viewer);
      Tracked trk = tracked.get(vehId);
      User user = plr == null ? null : PacketEvents.getAPI().getPlayerManager().getUser(plr);
      Entity veh = trk == null ? null : Bukkit.getEntity(trk.uuid);
      if (user == null || veh == null) {
        return;
      }
      List<PacketWrapper<?>> out = new ArrayList<>();
      Proxy prx = byViewer.computeIfAbsent(viewer, k -> new ConcurrentHashMap<>()).remove(vehId);
      out.add(ProxyEntities.destroy(prx == null ? new int[] {vehId} : concat(vehId, prx.allIds())));
      Location loc = veh.getLocation();
      double[] seen = viewerAt(plr).get(vehId);
      Vector3d pos = seen == null ? new Vector3d(loc.getX(), loc.getY(), loc.getZ())
          : new Vector3d(seen[0], seen[1], seen[2]);
      EntityType type = mount ? EntityTypes.getByName("minecraft:" + trk.def.id()) : disguiseType(client);
      out.add(new WrapperPlayServerSpawnEntity(vehId, Optional.of(veh.getUniqueId()), type, pos,
          loc.getPitch(), loc.getYaw(), loc.getYaw(), 0, Optional.empty()));
      if (mount) {
        dis.remove(vehId);
        rid.add(vehId);
      } else {
        rid.remove(vehId);
        dis.add(vehId);
        out.add(disguiseData(vehId, client));
      }
      out.add(new WrapperPlayServerSetPassengers(vehId, passengers));
      ProxyEntities.send(user, out);
      if (!mount) {
        refreshViewer(plr, client, vehId, veh, trk);
      }
    });
    return true;
  }

  private static int[] concat(int first, int[] rest) {
    int[] out = new int[rest.length + 1];
    out[0] = first;
    System.arraycopy(rest, 0, out, 1, rest.length);
    return out;
  }

  private void onSpawnSeen(UUID viewer, ProtocolVersion client, int entId, UUID uuid, EntityCatalog.Def def) {
    Player player = Bukkit.getPlayer(viewer);
    Entity ent = Bukkit.getEntity(uuid);
    if (player == null || ent == null || !ent.isValid()) {
      return;
    }
    Tracked trk = tracked.computeIfAbsent(entId, k -> new Tracked(uuid, def));
    if (trk.state == null) {
      trk.state = AppearanceResolver.resolve(ent, def);
    }
    trk.viewers.add(viewer);
    refreshViewer(player, client, entId, ent, trk);
  }

  private void tickMain() {
    tick++;
    names.nextTick();
    for (Map.Entry<Integer, Tracked> ent : tracked.entrySet()) {
      int entId = ent.getKey();
      Tracked trk = ent.getValue();
      if (trk.viewers.isEmpty()) {
        tracked.remove(entId);
        headYaws.remove(entId);
        continue;
      }
      Entity entity = Bukkit.getEntity(trk.uuid);
      if (trk.deathTicks >= 0) {
        tickDeath(entId, trk);
        continue;
      }
      if (entity == null || !entity.isValid()) {
        continue;
      }
      Location here = entity.getLocation();
      trk.px = here.getX();
      trk.py = here.getY();
      trk.pz = here.getZ();
      trk.yaw = entity instanceof LivingEntity living ? living.getBodyYaw() : here.getYaw();
      boxes.tick(entId, entity, trk.viewers, plr -> respawnFor(plr, entity));
      names.tick(entId, entity, trk.viewers);
      if ((tick + entId) % 5 == 0) {
        AppearanceResolver.State now = AppearanceResolver.resolve(entity, trk.def);
        if (!java.util.Objects.equals(now, trk.state)) {
          trk.state = now;
          for (UUID viewer : trk.viewers) {
            Player player = Bukkit.getPlayer(viewer);
            Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
            if (player != null && proto.isPresent()) {
              refreshViewer(player, proto.get(), entId, entity, trk);
            }
          }
        }
      }
      if (trk.hurtTicks > 0 && --trk.hurtTicks == 0) {
        pushItems(entId, trk, false);
      }
      if (trk.anim != null) {
        animTick(entId, trk, entity);
      }
      if ((tick + entId) % 10 == 0) {
        lodPass(entId, trk, entity);
      }
      if (trk.def.spin()) {
        tickSpin(entId, trk, entity);
      } else if (trk.state != null && trk.state.key().endsWith(";pose=lying") && "cat".equals(trk.def.id())) {
        if ((tick + entId) % 20 == 0) {
          pushCatLying(entId, trk);
        }
      }
    }
  }

  private void respawnFor(Player player, Entity entity) {
    player.hideEntity(plugin, entity);
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      if (player.isOnline() && entity.isValid()) {
        player.showEntity(plugin, entity);
      }
    }, 2L);
  }

  private void refreshViewer(Player player, ProtocolVersion client, int entId, Entity ent, Tracked trk) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user == null) {
      return;
    }
    Map<Integer, Proxy> proxies = byViewer.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
    Proxy cur = proxies.get(entId);
    EntityCatalog.Appearance app = trk.state == null ? null : trk.def.appearances().get(trk.state.key());
    boolean lacksType = !RegistrySince.get().isNative(RegistrySince.Kind.ENTITY, trk.def.id(), client);
    boolean onServer = app != null && !RegistrySince.serverVersion().olderThan(app.since());
    boolean want = onServer && (trk.def.vehicle()
        ? viewerSet(disguised, player).contains(entId)
        : lacksType || client.olderThan(app.since()));
    if (!want) {
      if (cur != null) {
        proxies.remove(entId);
        ProxyEntities.sendOne(user, ProxyEntities.destroy(cur.allIds()));
        ProxyEntities.sendOne(user, new WrapperPlayServerEntityMetadata(entId,
            List.of(new EntityData<>(0, EntityDataTypes.BYTE, baseFlags(ent, false)))));
      }
      return;
    }
    boolean hurt = trk.hurtTicks > 0 || trk.deathTicks >= 0;
    AnimCatalog.Appearance animApp = animatedLook(player, client, ent, trk, cur);
    if (cur != null && (cur.anim != null) != (animApp != null)) {
      proxies.remove(entId);
      ProxyEntities.sendOne(user, ProxyEntities.destroy(cur.allIds()));
      cur = null;
    }
    if (cur == null && animApp != null) {
      try {
        spawnAnimated(player, user, client, entId, ent, trk, animApp, hurt, proxies);
        return;
      } catch (RuntimeException ex) {
        trk.animFailed = true;
        trk.anim = null;
        plugin.getLogger().log(Level.WARNING, "Animated look failed for " + trk.def.id() + ", using the static model", ex);
      }
    }
    if (cur == null) {
      Location loc = ent.getLocation();
      float yaw = ent instanceof LivingEntity living ? living.getBodyYaw() : loc.getYaw();
      double[] at = viewerAt(player).computeIfAbsent(entId, k -> new double[] {loc.getX(), loc.getY(), loc.getZ()});
      cur = new Proxy(client, ProxyEntities.nextId(), at);
      trk.lastRot = null;
      Vector3d pos = cur.basePos();
      if (plugin.getConfig().getBoolean("debug.entity-proxies", false)) {
        plugin.getLogger().info("proxy " + trk.state.key() + " cmd=" + app.cmd() + (hurt ? "+hurt" : "")
            + " base=" + entId + " display=" + cur.mainId + " for " + player.getName() + " (" + client.getName() + ")");
      }
      List<PacketWrapper<?>> out = new ArrayList<>(spawnFor(client, cur.mainId, pos, yaw,
          paperItem(app.cmd() + (hurt ? catalog.hurtOffset() : 0))));
      for (Map.Entry<String, Integer> part : trk.state.parts().entrySet()) {
        EntityCatalog.Part def = trk.def.parts().get(part.getKey());
        if (def == null || !(lacksType || client.olderThan(def.since()))) {
          continue;
        }
        int pid = ProxyEntities.nextId();
        cur.partIds.put(part.getKey(), pid);
        out.addAll(spawnFor(client, pid, pos, yaw, dyedItem(def.cmd() + (hurt ? catalog.hurtOffset() : 0), part.getValue())));
      }
      cur.shownKey = trk.state.key();
      cur.shownCmd = app.cmd();
      proxies.put(entId, cur);
      out.add(new WrapperPlayServerEntityMetadata(entId, List.of(new EntityData<>(0, EntityDataTypes.BYTE,
          baseFlags(ent, !boxes.isBoxed(player.getUniqueId(), entId))))));
      ProxyEntities.send(user, out);
      return;
    }
    if (!trk.state.key().equals(cur.shownKey) || (cur.anim == null && !partsMatch(cur, trk))
        || (cur.anim != null && !partColors(animatedParts(client, trk)).equals(cur.anim.partColors()))) {
      proxies.remove(entId);
      ProxyEntities.sendOne(user, ProxyEntities.destroy(cur.allIds()));
      refreshViewer(player, client, entId, ent, trk);
    }
  }

  private boolean animatedEnabled() {
    return animCatalog != null && plugin.getConfig().getBoolean("animated-mobs", true);
  }

  private AnimCatalog.Appearance animatedLook(Player player, ProtocolVersion client, Entity ent, Tracked trk, Proxy cur) {
    if (!animatedEnabled() || trk.animFailed || trk.state == null || trk.def.spin() || trk.def.vehicle()
        || (client.olderThan(ProxyEntities.ITEM_DISPLAY) && !plugin.getConfig().getBoolean("animated-mobs-legacy", true))
        || animatedParts(client, trk) == null) {
      return null;
    }
    AnimCatalog.Appearance app = animCatalog.appearance(trk.def.id(), trk.state.key());
    if (app == null) {
      return null;
    }
    double range = plugin.getConfig().getDouble("animation.range", 32.0);
    if (range <= 0) {
      return app;
    }
    double limit = cur != null && cur.anim != null ? range + 6 : range;
    Location at = ent.getLocation();
    Location viewer = player.getLocation();
    if (at.getWorld() != viewer.getWorld() || at.distanceSquared(viewer) > limit * limit) {
      return null;
    }
    return app;
  }

  private Map<String, AnimLook.PartLimbs> animatedParts(ProtocolVersion client, Tracked trk) {
    Map<String, AnimLook.PartLimbs> out = new java.util.LinkedHashMap<>();
    boolean lacksType = !RegistrySince.get().isNative(RegistrySince.Kind.ENTITY, trk.def.id(), client);
    for (Map.Entry<String, Integer> part : trk.state.parts().entrySet()) {
      EntityCatalog.Part def = trk.def.parts().get(part.getKey());
      if (def == null || !(lacksType || client.olderThan(def.since()))) {
        continue;
      }
      AnimCatalog.Appearance pa = animCatalog.appearance(trk.def.id(), part.getKey());
      if (pa == null) {
        return null;
      }
      out.put(part.getKey(), new AnimLook.PartLimbs(pa.bones(), part.getValue()));
    }
    return out;
  }

  private void ensureAnim(int entId, Entity ent, Tracked trk, AnimCatalog.Appearance app) {
    if (trk.anim != null && trk.anim.app == app) {
      return;
    }
    boolean baby = trk.state != null && trk.state.baby();
    trk.anim = new AnimatedMob(trk.def.id(), app, animCatalog.rig(app.rigId()), baby, entId * 37, animCatalog);
    Location loc = ent.getLocation();
    trk.px = loc.getX();
    trk.py = loc.getY();
    trk.pz = loc.getZ();
    trk.anim.tickInputs(loc.getX(), loc.getY(), loc.getZ(), true, false);
    Float head = headYaws.get(entId);
    if (head != null && !head.isNaN()) {
      trk.anim.setHeadTarget(head);
      trk.lastHead = head;
    } else {
      trk.anim.setHeadTarget(trk.yaw);
    }
    solveAnim(trk, ent);
  }

  private void solveAnim(Tracked trk, Entity ent) {
    float pitch = ent == null ? 0f : ent.getLocation().getPitch();
    float body = trk.anim.forced && ent != null ? ent.getLocation().getYaw() : trk.yaw;
    trk.anim.solve(body, pitch, trk.deathRoll, null);
  }

  private void spawnAnimated(Player player, User user, ProtocolVersion client, int entId, Entity ent, Tracked trk,
                             AnimCatalog.Appearance app, boolean hurt, Map<Integer, Proxy> proxies) {
    Location loc = ent.getLocation();
    trk.yaw = ent instanceof LivingEntity living ? living.getBodyYaw() : loc.getYaw();
    ensureAnim(entId, ent, trk, app);
    double[] at = viewerAt(player).computeIfAbsent(entId, k -> new double[] {loc.getX(), loc.getY(), loc.getZ()});
    Map<String, AnimLook.PartLimbs> parts = animatedParts(client, trk);
    AnimLook view = client.olderThan(ProxyEntities.ITEM_DISPLAY)
        ? new AnimStandView(app.bones(), parts)
        : new AnimView(app.bones(), parts, client.newerThanOrEqualTo(ProtocolVersion.v1_19_4), client.olderThan(SMOOTH_DISPLAYS));
    Proxy prx = new Proxy(client, view.mainId(), at);
    prx.anim = view;
    prx.shownKey = trk.state.key();
    if (plugin.getConfig().getBoolean("debug.entity-proxies", false)) {
      plugin.getLogger().info("animated proxy " + trk.state.key() + " rig=" + app.rigId() + " bones=" + app.bones().size()
          + " base=" + entId + " root=" + view.mainId() + " for " + player.getName() + " (" + client.getName() + ")");
    }
    trk.animKey = trk.state.key();
    List<PacketWrapper<?>> out = new ArrayList<>(view.spawn(prx.basePos(), trk.anim, hurt));
    out.add(new WrapperPlayServerEntityMetadata(entId, List.of(new EntityData<>(0, EntityDataTypes.BYTE,
        baseFlags(ent, !boxes.isBoxed(player.getUniqueId(), entId))))));
    proxies.put(entId, prx);
    ProxyEntities.send(user, out);
  }

  public String animDebug(org.bukkit.command.CommandSender sender, String[] args) {
    if (args.length < 2) {
      return "/vv anim off | stop | play <animation> | pose <walkPos> <speed> [headYaw] [pitch] [age] | flag <name> <value> | list | mode static|animated";
    }
    if (args[1].equalsIgnoreCase("stats")) {
      return animStats();
    }
    if (args[1].equalsIgnoreCase("mode")) {
      boolean on = args.length > 2 && args[2].equalsIgnoreCase("animated");
      plugin.getConfig().set("animated-mobs", on);
      return "animated-mobs = " + on + " (until the next reload)";
    }
    List<Tracked> targets = new ArrayList<>();
    for (Tracked trk : tracked.values()) {
      Entity ent = Bukkit.getEntity(trk.uuid);
      if (trk.anim == null || ent == null) {
        continue;
      }
      boolean tagged = ent.getScoreboardTags().contains("viavisuals_qa");
      boolean near = sender instanceof Player plr && plr.getWorld() == ent.getWorld()
          && plr.getLocation().distanceSquared(ent.getLocation()) < 256;
      if (tagged || near) {
        targets.add(trk);
      }
    }
    if (targets.isEmpty()) {
      return "no animated mob in range";
    }
    String sub = args[1].toLowerCase(java.util.Locale.ROOT);
    StringBuilder msg = new StringBuilder();
    if (sub.equals("stats")) {
      return animStats();
    }
    for (Tracked trk : targets) {
      AnimatedMob am = trk.anim;
      switch (sub) {
        case "off" -> {
          am.forced = false;
          am.forcedFlags.clear();
        }
        case "pose" -> {
          if (args.length < 4) {
            return "pose <walkPos> <speed> [headYaw] [pitch] [age]";
          }
          am.forced = true;
          am.forcedPos = Double.parseDouble(args[2]);
          am.forcedSpeed = Double.parseDouble(args[3]);
          am.forcedYaw = args.length > 4 ? Double.parseDouble(args[4]) : 0;
          am.forcedPitch = args.length > 5 ? Double.parseDouble(args[5]) : 0;
          am.forcedAge = args.length > 6 ? Double.parseDouble(args[6]) : 0;
        }
        case "flag" -> {
          if (args.length < 4) {
            return "flag <name> <value>";
          }
          String raw = args[3];
          if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
            am.forcedFlags.put(args[2], Boolean.parseBoolean(raw));
          } else {
            try {
              am.forcedFlags.put(args[2], Double.parseDouble(raw));
            } catch (NumberFormatException ex) {
              am.forcedFlags.put(args[2], raw.toUpperCase(java.util.Locale.ROOT));
            }
          }
        }
        case "play" -> {
          if (args.length < 3) {
            return "play <animation field or id>";
          }
          String field = am.driver.forcePlay(args[2]);
          msg.append(field == null ? "no such animation on " + am.rig.id + ": " + am.rig.keyframes : "playing " + field)
              .append('\n');
        }
        case "stop" -> am.driver.stopForced();
        case "list" -> {
          msg.append(trk.def.id()).append(" rig=").append(am.rig.id).append(" bones=").append(am.boneCount)
              .append(" flags=").append(am.rig.flags.keySet()).append(" keyframes=").append(am.rig.keyframes)
              .append('\n');
        }
        default -> {
          return "unknown anim subcommand";
        }
      }
    }
    return msg.length() > 0 ? msg.toString() : "ok (" + targets.size() + " mob(s))";
  }

  private long statsAt = System.nanoTime();
  private long statsPackets;
  private long statsBundles;

  private String animStats() {
    int mobs = 0;
    int views = 0;
    int bones = 0;
    for (Tracked trk : tracked.values()) {
      if (trk.anim != null) {
        mobs++;
        bones += trk.anim.boneCount;
      }
    }
    for (Map<Integer, Proxy> per : byViewer.values()) {
      for (Proxy prx : per.values()) {
        if (prx.anim != null) {
          views++;
        }
      }
    }
    long now = System.nanoTime();
    long packets = AnimLook.LIMB_PACKETS.get();
    long bundles = AnimLook.BUNDLES.get();
    double secs = Math.max(0.001, (now - statsAt) / 1e9);
    String res = String.format(java.util.Locale.ROOT,
        "animated mobs=%d views=%d bones=%d | limb packets/s=%.0f bundles/s=%.0f (last %.1fs)",
        mobs, views, bones, (packets - statsPackets) / secs, (bundles - statsBundles) / secs, secs);
    statsAt = now;
    statsPackets = packets;
    statsBundles = bundles;
    return res;
  }

  private void lodPass(int entId, Tracked trk, Entity entity) {
    if (animCatalog == null || !animCatalog.has(trk.def.id())) {
      return;
    }
    for (UUID viewer : trk.viewers) {
      Player player = Bukkit.getPlayer(viewer);
      Optional<ProtocolVersion> proto = player == null ? Optional.empty() : VersionManager.tryGetVisualVersion(player);
      if (proto.isEmpty() || !proto.get().olderThan(RegistrySince.serverVersion())) {
        continue;
      }
      Map<Integer, Proxy> proxies = byViewer.get(viewer);
      Proxy prx = proxies == null ? null : proxies.get(entId);
      if (prx == null) {
        continue;
      }
      boolean wantAnim = animatedLook(player, proto.get(), entity, trk, prx) != null;
      if (wantAnim != (prx.anim != null)) {
        refreshViewer(player, proto.get(), entId, entity, trk);
      } else if (prx.anim != null) {
        Location at = entity.getLocation();
        Location here = player.getLocation();
        boolean near = at.getWorld() == here.getWorld() && at.distanceSquared(here) <= FULL_RATE_RANGE * FULL_RATE_RANGE;
        prx.every = near ? 1 : MID_RATE_TICKS;
      }
    }
  }

  private void animTick(int entId, Tracked trk, Entity entity) {
    try {
      animStep(entId, trk, entity);
    } catch (RuntimeException ex) {
      trk.animFailed = true;
      trk.anim = null;
      plugin.getLogger().log(Level.WARNING, "Animation failed for " + trk.def.id() + ", switching it to the static model", ex);
    }
  }

  private void animStep(int entId, Tracked trk, Entity entity) {
    AnimatedMob am = trk.anim;
    if (am == null) {
      return;
    }
    if (entity != null) {
      boolean alive = !(entity instanceof LivingEntity living) || !living.isDead();
      am.tickInputs(trk.px, trk.py, trk.pz, alive, entity.isInsideVehicle());
      int[] ev = new int[trk.events.size()];
      int n = 0;
      for (java.util.Iterator<Integer> it = trk.events.iterator(); it.hasNext() && n < ev.length; ) {
        ev[n++] = it.next();
        it.remove();
      }
      am.tickDriver(entity, ev, n);
      Float head = headYaws.get(entId);
      if (head != null && !head.isNaN() && !head.equals(trk.lastHead)) {
        trk.lastHead = head;
        am.setHeadTarget(head);
      }
    }
    solveAnim(trk, entity);
    forEachProxy(entId, trk, (user, prx) -> {
      if (prx.anim == null) {
        return;
      }
      if (prx.every > 1 && (tick + entId) % prx.every != 0) {
        return;
      }
      prx.anim.update(user, am, prx.every, prx.basePos());
    });
  }

  private static Map<String, Integer> partColors(Map<String, AnimLook.PartLimbs> parts) {
    Map<String, Integer> out = new java.util.LinkedHashMap<>();
    if (parts != null) {
      parts.forEach((key, limbs) -> out.put(key, limbs.rgb()));
    }
    return out;
  }

  private static boolean partsMatch(Proxy cur, Tracked trk) {
    return cur.partIds.keySet().equals(trk.state.parts().keySet());
  }

  private List<PacketWrapper<?>> spawnFor(ProtocolVersion client, int id, Vector3d pos, float yaw, ItemStack item) {
    if (client.olderThan(SMOOTH_DISPLAYS)) {
      return ProxyEntities.spawn(id, ProtocolVersion.v1_19_3, pos, yaw, item, 1f, new Vector3f(0, 0, 0), STAND_OFFSET);
    }
    return ProxyEntities.spawn(id, client, pos, yaw, item, 1f, new Vector3f(0, 0, 0), 0);
  }

  private void startHurt(int entId) {
    Tracked trk = tracked.get(entId);
    if (trk == null || trk.deathTicks >= 0) {
      return;
    }
    boolean wasRed = trk.hurtTicks > 0;
    trk.hurtTicks = HURT_TICKS;
    if (!wasRed) {
      pushItems(entId, trk, true);
    }
  }

  private void startDeath(int entId) {
    Tracked trk = tracked.get(entId);
    if (trk == null || trk.deathTicks >= 0) {
      return;
    }
    trk.deathTicks = 0;
    pushItems(entId, trk, true);
  }

  private void tickDeath(int entId, Tracked trk) {
    trk.deathTicks++;
    if (trk.deathTicks > DEATH_TICKS + 2) {
      return;
    }
    float f = (float) Math.sqrt(Math.max(0, (trk.deathTicks - 1) / 20.0f * 1.6f));
    float deg = Math.min(1f, f) * 90f;
    if (trk.anim != null) {
      trk.deathRoll = deg;
      animTick(entId, trk, null);
    }
    forEachProxy(entId, trk, (user, prx) -> {
      if (prx.anim != null) {
        return;
      }
      for (int id : prx.allIds()) {
        if (isStand(prx)) {
          standPose(user, id, prx, trk, quatZ(deg), new Vector3f(0, 0, 0));
        } else {
          ProxyEntities.sendOne(user, ProxyEntities.transform(id, new Vector3f(0, 0, 0), quatZ(deg), 1f, 1));
        }
      }
    });
  }

  private void tickSpin(int entId, Tracked trk, Entity entity) {
    Vector vel = entity.getVelocity();
    if (trk.tentacleSpeed == 0f) {
      trk.tentacleSpeed = 1.0f / (new java.util.Random(entId).nextFloat() + 1.0f) * 0.2f;
    }
    trk.tentacle += trk.tentacleSpeed;
    if (trk.tentacle > Math.PI * 2) {
      trk.tentacle -= (float) (Math.PI * 2);
      if (java.util.concurrent.ThreadLocalRandom.current().nextInt(10) == 0) {
        trk.tentacleSpeed = 1.0f / (java.util.concurrent.ThreadLocalRandom.current().nextFloat() + 1.0f) * 0.2f;
      }
    }
    if (entity.isInWater()) {
      if (trk.tentacle < Math.PI) {
        if (trk.tentacle / (float) Math.PI > 0.75) {
          trk.rotateSpeed = 1.0f;
        } else {
          trk.rotateSpeed *= 0.8f;
        }
      } else {
        trk.rotateSpeed *= 0.99f;
      }
      double horizontal = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ());
      trk.zBodyRot = (trk.zBodyRot + (float) Math.PI * trk.rotateSpeed * 1.5f) % 360f;
      trk.xBodyRot += (float) ((-Math.toDegrees(Math.atan2(horizontal, vel.getY())) - trk.xBodyRot) * 0.1);
    } else {
      trk.xBodyRot += (-90.0f - trk.xBodyRot) * 0.02f;
    }
    boolean baby = trk.state != null && trk.state.baby();
    float up = baby ? 0.25f : 0.5f;
    float down = baby ? 0.6f : 1.2f;
    Quaternion4f rot = mul(quatX(-trk.xBodyRot), quatY(trk.zBodyRot));
    if (trk.lastRot != null && angleBetween(trk.lastRot, rot) < SPIN_RESEND_DEG) {
      return;
    }
    trk.lastRot = rot;
    float[] offset = rotate(rot, 0f, -down, 0f);
    Vector3f translation = new Vector3f(offset[0], up + offset[1], offset[2]);
    forEachProxy(entId, trk, (user, prx) -> {
      if (prx.anim != null) {
        return;
      }
      for (int id : prx.allIds()) {
        if (isStand(prx)) {
          standPose(user, id, prx, trk, rot, translation);
        } else {
          ProxyEntities.sendOne(user, ProxyEntities.transform(id, translation, rot, 1f, 1));
        }
      }
    });
  }

  private static void standPose(User user, int id, Proxy prx, Tracked trk, Quaternion4f rot, Vector3f trans) {
    StandPose.Pose pose = StandPose.solve(rot, trans, trk.yaw, STAND_OFFSET);
    Vector3d base = prx.basePos();
    ProxyEntities.sendOne(user, ProxyEntities.headPose(id, pose.headX(), pose.headY(), pose.headZ()));
    ProxyEntities.sendOne(user, new WrapperPlayServerEntityTeleport(id,
        new Vector3d(base.getX() + pose.dx(), base.getY() + pose.dy(), base.getZ() + pose.dz()), trk.yaw, 0f, false));
  }

  private void pushCatLying(int entId, Tracked trk) {
    Vector3f trans = new Vector3f(0.4f, 0.15f, 0.1f);
    forEachProxy(entId, trk, (user, prx) -> {
      if (prx.anim != null) {
        return;
      }
      for (int id : prx.allIds()) {
        if (isStand(prx)) {
          standPose(user, id, prx, trk, quatZ(90f), trans);
        } else {
          ProxyEntities.sendOne(user, ProxyEntities.transform(id, trans, quatZ(90f), 1f, 0));
        }
      }
    });
  }

  private void pushItems(int entId, Tracked trk, boolean hurt) {
    forEachProxy(entId, trk, (user, prx) -> {
      if (prx.anim != null) {
        prx.anim.setHurt(user, trk.anim, hurt);
        return;
      }
      int off = hurt ? catalog.hurtOffset() : 0;
      ProtocolVersion ver = isStand(prx) ? ProtocolVersion.v1_19_3 : prx.client;
      ProxyEntities.sendOne(user, ProxyEntities.setItem(prx.mainId, ver, paperItem(prx.shownCmd + off)));
      for (Map.Entry<String, Integer> part : prx.partIds.entrySet()) {
        EntityCatalog.Part def = trk.def.parts().get(part.getKey());
        Integer rgb = trk.state == null ? null : trk.state.parts().get(part.getKey());
        if (def != null && rgb != null) {
          ProxyEntities.sendOne(user, ProxyEntities.setItem(part.getValue(), ver, dyedItem(def.cmd() + off, rgb)));
        }
      }
    });
  }

  private void forEachProxy(int entId, Tracked trk, java.util.function.BiConsumer<User, Proxy> action) {
    for (UUID viewer : trk.viewers) {
      Map<Integer, Proxy> proxies = byViewer.get(viewer);
      Proxy prx = proxies == null ? null : proxies.get(entId);
      Player player = Bukkit.getPlayer(viewer);
      if (prx == null || player == null) {
        continue;
      }
      User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
      if (user != null) {
        action.accept(user, prx);
      }
    }
  }

  private static byte baseFlags(Entity ent, boolean hidden) {
    byte flags = 0;
    if (ent.getFireTicks() > 0 || ent.isVisualFire()) {
      flags |= 0x01;
    }
    if (ent.isGlowing()) {
      flags |= 0x40;
    }
    if (hidden || (ent instanceof LivingEntity living && living.isInvisible())) {
      flags |= INVISIBLE;
    }
    return flags;
  }

  private static ItemStack paperItem(int cmd) {
    org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(Material.PAPER);
    ItemMeta meta = stack.getItemMeta();
    meta.setCustomModelData(cmd);
    stack.setItemMeta(meta);
    return SpigotConversionUtil.fromBukkitItemStack(stack);
  }

  private static ItemStack dyedItem(int cmd, int rgb) {
    org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(Material.LEATHER_HORSE_ARMOR);
    LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
    meta.setCustomModelData(cmd);
    meta.setColor(Color.fromRGB(rgb & 0xFFFFFF));
    stack.setItemMeta(meta);
    return SpigotConversionUtil.fromBukkitItemStack(stack);
  }

  private static Quaternion4f quatX(float deg) {
    double h = Math.toRadians(deg) / 2;
    return new Quaternion4f((float) Math.sin(h), 0, 0, (float) Math.cos(h));
  }

  private static Quaternion4f quatY(float deg) {
    double h = Math.toRadians(deg) / 2;
    return new Quaternion4f(0, (float) Math.sin(h), 0, (float) Math.cos(h));
  }

  private static Quaternion4f quatZ(float deg) {
    double h = Math.toRadians(deg) / 2;
    return new Quaternion4f(0, 0, (float) Math.sin(h), (float) Math.cos(h));
  }

  private static float angleBetween(Quaternion4f a, Quaternion4f b) {
    double dot = Math.abs(a.getX() * b.getX() + a.getY() * b.getY() + a.getZ() * b.getZ() + a.getW() * b.getW());
    return (float) Math.toDegrees(2 * Math.acos(Math.min(1.0, dot)));
  }

  private static Quaternion4f mul(Quaternion4f a, Quaternion4f b) {
    return new Quaternion4f(
        a.getW() * b.getX() + a.getX() * b.getW() + a.getY() * b.getZ() - a.getZ() * b.getY(),
        a.getW() * b.getY() - a.getX() * b.getZ() + a.getY() * b.getW() + a.getZ() * b.getX(),
        a.getW() * b.getZ() + a.getX() * b.getY() - a.getY() * b.getX() + a.getZ() * b.getW(),
        a.getW() * b.getW() - a.getX() * b.getX() - a.getY() * b.getY() - a.getZ() * b.getZ());
  }

  private static float[] rotate(Quaternion4f q, float x, float y, float z) {
    Quaternion4f v = new Quaternion4f(x, y, z, 0);
    Quaternion4f conj = new Quaternion4f(-q.getX(), -q.getY(), -q.getZ(), q.getW());
    Quaternion4f r = mul(mul(q, v), conj);
    return new float[] {r.getX(), r.getY(), r.getZ()};
  }

  public void forgetViewer(Player player) {
    UUID id = player.getUniqueId();
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    Map<Integer, Proxy> mine = byViewer.remove(id);
    if (mine != null && user != null) {
      for (Proxy prox : mine.values()) {
        ProxyEntities.sendOne(user, ProxyEntities.destroy(prox.allIds()));
      }
    }
    clientAt.remove(id);
    disguised.remove(id);
    riding.remove(id);
    retyped.remove(id);
    retypedPlain.remove(id);
    boxes.forget(id);
    names.forget(id, true);
    for (Tracked trk : tracked.values()) {
      trk.viewers.remove(id);
    }
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    byViewer.remove(id);
    clientAt.remove(id);
    disguised.remove(id);
    riding.remove(id);
    retyped.remove(id);
    retypedPlain.remove(id);
    boxes.forget(id);
    names.forget(id, false);
    for (Tracked trk : tracked.values()) {
      trk.viewers.remove(id);
    }
  }

}
