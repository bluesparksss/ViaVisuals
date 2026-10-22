package dev.bluesparksss.viavisuals.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

import dev.bluesparksss.viavisuals.world.HeightWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProxyEntities {

  public static final ProtocolVersion ITEM_DISPLAY = ProtocolVersion.v1_19_4;

  private static final int ENTITY_FLAGS = 0;
  private static final int SILENT = 4;
  private static final int NO_GRAVITY = 5;
  private static final int INTERP_START = 8;
  private static final int INTERP_DURATION = 9;
  private static final int TRANSLATION = 11;
  private static final int SCALE = 12;
  private static final int LEFT_ROTATION = 13;
  private static final int BILLBOARD = 15;
  private static final int BRIGHTNESS = 16;
  private static final int VIEW_RANGE = 17;
  private static final int SHADOW_RADIUS = 18;
  private static final int SHADOW_STRENGTH = 19;
  private static final int ITEM = 23;
  private static final int DISPLAY_CONTEXT = 24;
  private static final int ARMOR_STAND_FLAGS = 15;
  private static final int ARMOR_STAND_HEAD_POSE = 16;

  private static final byte FLAG_INVISIBLE = 0x20;
  private static final byte STAND_SMALL = 0x01;
  private static final byte STAND_NO_BASEPLATE = 0x08;
  private static final byte STAND_MARKER = 0x10;
  private static final byte CONTEXT_FIXED = 8;

  private static final AtomicInteger IDS = new AtomicInteger(2_000_000_000);

  private ProxyEntities() {
  }

  public static int nextId() {
    return IDS.decrementAndGet();
  }

  public static boolean usesItemDisplay(ProtocolVersion client) {
    return client.newerThanOrEqualTo(ITEM_DISPLAY);
  }

  public static List<PacketWrapper<?>> spawn(int id, ProtocolVersion client, Vector3d pos, float yaw, ItemStack item,
                                             float scale, Vector3f translation, double standYOffset) {
    return spawn(id, client, pos, yaw, item, scale, translation, standYOffset, false);
  }

  public static List<PacketWrapper<?>> spawn(int id, ProtocolVersion client, Vector3d pos, float yaw, ItemStack item,
                                             float scale, Vector3f translation, double standYOffset, boolean smallStand) {
    List<PacketWrapper<?>> out = new ArrayList<>();
    UUID uuid = UUID.randomUUID();
    if (usesItemDisplay(client)) {
      out.add(new WrapperPlayServerSpawnEntity(id, Optional.of(uuid), EntityTypes.ITEM_DISPLAY, pos, 0f, yaw, 0f, 0, Optional.empty()));
      List<EntityData<?>> meta = new ArrayList<>();
      meta.add(new EntityData<>(POS_ROT_INTERP, EntityDataTypes.INT, 3));
      meta.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, translation));
      meta.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(scale, scale, scale)));
      meta.add(new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, (byte) 0));
      meta.add(new EntityData<>(VIEW_RANGE, EntityDataTypes.FLOAT, 2.0f));
      meta.add(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item));
      meta.add(new EntityData<>(DISPLAY_CONTEXT, EntityDataTypes.BYTE, CONTEXT_FIXED));
      out.add(new WrapperPlayServerEntityMetadata(id, meta));
    } else {
      Vector3d standPos = new Vector3d(pos.getX(), pos.getY() + standYOffset, pos.getZ());
      out.add(new WrapperPlayServerSpawnEntity(id, Optional.of(uuid), EntityTypes.ARMOR_STAND, standPos, 0f, yaw, yaw, 0, Optional.empty()));
      List<EntityData<?>> meta = new ArrayList<>();
      meta.add(new EntityData<>(ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_INVISIBLE));
      meta.add(new EntityData<>(SILENT, EntityDataTypes.BOOLEAN, true));
      meta.add(new EntityData<>(NO_GRAVITY, EntityDataTypes.BOOLEAN, true));
      meta.add(new EntityData<>(ARMOR_STAND_FLAGS, EntityDataTypes.BYTE, (byte) (STAND_MARKER | STAND_NO_BASEPLATE | (smallStand ? STAND_SMALL : 0))));
      out.add(new WrapperPlayServerEntityMetadata(id, meta));
      out.add(new WrapperPlayServerEntityEquipment(id, List.of(new Equipment(EquipmentSlot.HELMET, item))));
    }
    return out;
  }

  private static final int POS_ROT_INTERP = 10;

  public static PacketWrapper<?> setItem(int id, ProtocolVersion client, ItemStack item) {
    if (usesItemDisplay(client)) {
      return new WrapperPlayServerEntityMetadata(id, List.of(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item)));
    }
    return new WrapperPlayServerEntityEquipment(id, List.of(new Equipment(EquipmentSlot.HELMET, item)));
  }

  public static PacketWrapper<?> transform(int id, Vector3f translation, Quaternion4f leftRotation, float scale, int ticks) {
    List<EntityData<?>> meta = new ArrayList<>();
    meta.add(new EntityData<>(INTERP_START, EntityDataTypes.INT, 0));
    meta.add(new EntityData<>(INTERP_DURATION, EntityDataTypes.INT, ticks));
    meta.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, translation));
    meta.add(new EntityData<>(LEFT_ROTATION, EntityDataTypes.QUATERNION, leftRotation));
    meta.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(scale, scale, scale)));
    return new WrapperPlayServerEntityMetadata(id, meta);
  }

  public static PacketWrapper<?> headPose(int id, float x, float y, float z) {
    return new WrapperPlayServerEntityMetadata(id, List.of(new EntityData<>(ARMOR_STAND_HEAD_POSE,
        EntityDataTypes.ROTATION, new Vector3f(x, y, z))));
  }

  public static List<PacketWrapper<?>> spawnRoot(int id, Vector3d pos) {
    return spawnRoot(id, pos, false, 0f);
  }

  public static List<PacketWrapper<?>> spawnRoot(int id, Vector3d pos, boolean stand, float shadow) {
    if (stand) {
      List<PacketWrapper<?>> out = new ArrayList<>();
      out.add(new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.ARMOR_STAND, pos, 0f, 0f, 0f, 0,
          Optional.empty()));
      List<EntityData<?>> meta = new ArrayList<>();
      meta.add(new EntityData<>(ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_INVISIBLE));
      meta.add(new EntityData<>(SILENT, EntityDataTypes.BOOLEAN, true));
      meta.add(new EntityData<>(NO_GRAVITY, EntityDataTypes.BOOLEAN, true));
      meta.add(new EntityData<>(ARMOR_STAND_FLAGS, EntityDataTypes.BYTE, (byte) (STAND_MARKER | STAND_NO_BASEPLATE)));
      out.add(new WrapperPlayServerEntityMetadata(id, meta));
      return out;
    }
    List<PacketWrapper<?>> out = new ArrayList<>();
    out.add(new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.ITEM_DISPLAY, pos, 0f, 0f, 0f, 0,
        Optional.empty()));
    List<EntityData<?>> meta = new ArrayList<>();
    meta.add(new EntityData<>(POS_ROT_INTERP, EntityDataTypes.INT, 3));
    meta.add(new EntityData<>(VIEW_RANGE, EntityDataTypes.FLOAT, 2.0f));
    if (shadow > 0f) {
      meta.add(new EntityData<>(SHADOW_RADIUS, EntityDataTypes.FLOAT, shadow));
      meta.add(new EntityData<>(SHADOW_STRENGTH, EntityDataTypes.FLOAT, 1.0f));
    }
    out.add(new WrapperPlayServerEntityMetadata(id, meta));
    return out;
  }

  public static List<PacketWrapper<?>> spawnLimb(int id, Vector3d pos, ItemStack item, Vector3f translation,
                                                 Quaternion4f rotation, Vector3f scale) {
    List<PacketWrapper<?>> out = new ArrayList<>();
    out.add(new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.ITEM_DISPLAY, pos, 0f, 0f, 0f, 0,
        Optional.empty()));
    List<EntityData<?>> meta = new ArrayList<>();
    meta.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, translation));
    meta.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, scale));
    meta.add(new EntityData<>(LEFT_ROTATION, EntityDataTypes.QUATERNION, rotation));
    meta.add(new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, (byte) 0));
    meta.add(new EntityData<>(VIEW_RANGE, EntityDataTypes.FLOAT, 2.0f));
    meta.add(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item));
    meta.add(new EntityData<>(DISPLAY_CONTEXT, EntityDataTypes.BYTE, CONTEXT_FIXED));
    out.add(new WrapperPlayServerEntityMetadata(id, meta));
    return out;
  }

  public static PacketWrapper<?> limbUpdate(int id, int ticks, Vector3f translation, Quaternion4f rotation, Vector3f scale) {
    List<EntityData<?>> meta = new ArrayList<>(5);
    meta.add(new EntityData<>(INTERP_START, EntityDataTypes.INT, 0));
    meta.add(new EntityData<>(INTERP_DURATION, EntityDataTypes.INT, ticks));
    if (translation != null) {
      meta.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, translation));
    }
    if (scale != null) {
      meta.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, scale));
    }
    if (rotation != null) {
      meta.add(new EntityData<>(LEFT_ROTATION, EntityDataTypes.QUATERNION, rotation));
    }
    return new WrapperPlayServerEntityMetadata(id, meta);
  }

  public static PacketWrapper<?> passengers(int vehicle, int... riders) {
    return new WrapperPlayServerSetPassengers(vehicle, riders);
  }

  public static PacketWrapper<?> destroy(int... ids) {
    return new WrapperPlayServerDestroyEntities(ids);
  }

  public static void send(User user, List<PacketWrapper<?>> packets) {
    for (PacketWrapper<?> pkt : packets) {
      sendOne(user, pkt);
    }
  }

  public static void sendOne(User user, PacketWrapper<?> pkt) {
    if (HeightWindow.shifting(user)) {
      user.sendPacket(pkt);
    } else {
      user.sendPacketSilently(pkt);
    }
  }

}
