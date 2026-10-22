package dev.bluesparksss.viavisuals.world;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.HeightmapType;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientGenerateStructure;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientQueryBlockNBT;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateCommandBlock;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateJigsawBlock;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnExperienceOrb;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPosition;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerVehicleMove;
import dev.bluesparksss.viavisuals.ViaVisualsPlugin;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class HeightShiftListener extends PacketListenerAbstract {

  private final ViaVisualsPlugin plugin;
  private final boolean outbound;

  public HeightShiftListener(ViaVisualsPlugin plugin, boolean outbound) {
    super(outbound ? PacketListenerPriority.HIGHEST : PacketListenerPriority.LOWEST);
    this.plugin = plugin;
    this.outbound = outbound;
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    var type = event.getPacketType();
    if (!outbound || !(type instanceof PacketType.Play.Server)) {
      return;
    }
    User user = event.getUser();
    if (type == PacketType.Play.Server.JOIN_GAME || type == PacketType.Play.Server.RESPAWN) {
      HeightWindow win = HeightWindow.get();
      if (win != null) {
        win.reset(user.getUUID());
      }
      return;
    }
    if (!shifted(type) && !tracked(type)) {
      return;
    }
    HeightWindow.Frame frame = HeightWindow.frame(user);
    if (frame == null) {
      return;
    }
    try {
      shiftOut(event, type, user, frame);
    } catch (RuntimeException ex) {
      plugin.getLogger().log(Level.WARNING, "Height shift failed for " + type, ex);
    }
  }

  private static boolean tracked(Object type) {
    return type == PacketType.Play.Server.SPAWN_PLAYER || type == PacketType.Play.Server.DESTROY_ENTITIES;
  }

  private static boolean shifted(Object type) {
    return type == PacketType.Play.Server.CHUNK_DATA || type == PacketType.Play.Server.UPDATE_LIGHT
        || type == PacketType.Play.Server.BLOCK_CHANGE || type == PacketType.Play.Server.MULTI_BLOCK_CHANGE
        || type == PacketType.Play.Server.BLOCK_ACTION || type == PacketType.Play.Server.BLOCK_ENTITY_DATA
        || type == PacketType.Play.Server.BLOCK_BREAK_ANIMATION || type == PacketType.Play.Server.EFFECT
        || type == PacketType.Play.Server.SOUND_EFFECT || type == PacketType.Play.Server.PARTICLE
        || type == PacketType.Play.Server.EXPLOSION || type == PacketType.Play.Server.SPAWN_ENTITY
        || type == PacketType.Play.Server.SPAWN_EXPERIENCE_ORB || type == PacketType.Play.Server.ENTITY_TELEPORT
        || type == PacketType.Play.Server.ENTITY_POSITION_SYNC || type == PacketType.Play.Server.VEHICLE_MOVE
        || type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK || type == PacketType.Play.Server.SPAWN_POSITION
        || type == PacketType.Play.Server.OPEN_SIGN_EDITOR;
  }

  private void shiftOut(PacketSendEvent event, Object type, User user, HeightWindow.Frame frame) {
    UUID uuid = user.getUUID();
    int lo = frame.outLo();
    if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
      WrapperPlayServerPlayerPositionAndLook pkt = new WrapperPlayServerPlayerPositionAndLook(event);
      HeightWindow win = HeightWindow.get();
      HeightWindow.Frame flipped = win == null ? null : win.flip(uuid, pkt.getTeleportId());
      lo = flipped == null ? lo : flipped.outLo();
      if (lo != 0 && !pkt.isRelativeFlag(RelativeFlag.Y)) {
        pkt.setY(pkt.getY() - lo);
        event.markForReEncode(true);
      }
      return;
    }
    HeightWindow win = HeightWindow.get();
    if (type == PacketType.Play.Server.SPAWN_PLAYER) {
      WrapperPlayServerSpawnPlayer pkt = new WrapperPlayServerSpawnPlayer(event);
      win.entities(uuid).add(pkt.getEntityId());
      if (lo != 0) {
        pkt.setPosition(down(pkt.getPosition(), lo));
        event.markForReEncode(true);
      }
      return;
    }
    if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
      Set<Integer> ids = win.entities(uuid);
      for (int ent : new WrapperPlayServerDestroyEntities(event).getEntityIds()) {
        ids.remove(ent);
      }
      return;
    }
    if (type == PacketType.Play.Server.SPAWN_ENTITY) {
      WrapperPlayServerSpawnEntity pkt = new WrapperPlayServerSpawnEntity(event);
      win.entities(uuid).add(pkt.getEntityId());
      if (lo != 0) {
        pkt.setPosition(down(pkt.getPosition(), lo));
        event.markForReEncode(true);
      }
      return;
    }
    if (type == PacketType.Play.Server.SPAWN_EXPERIENCE_ORB) {
      WrapperPlayServerSpawnExperienceOrb pkt = new WrapperPlayServerSpawnExperienceOrb(event);
      win.entities(uuid).add(pkt.getEntityId());
      if (lo != 0) {
        pkt.setY(pkt.getY() - lo);
        event.markForReEncode(true);
      }
      return;
    }
    if (lo != 0) {
      shiftOut(event, type, lo);
    }
  }

  private void shiftOut(PacketSendEvent event, Object type, int lo) {
    if (type == PacketType.Play.Server.CHUNK_DATA) {
      WrapperPlayServerChunkData pkt = new WrapperPlayServerChunkData(event);
      shiftColumn(pkt.getColumn(), lo);
      if (pkt.getLightData() != null) {
        shiftLight(pkt.getLightData(), lo / 16, pkt.getColumn().getChunks().length + 2);
      }
    } else if (type == PacketType.Play.Server.UPDATE_LIGHT) {
      WrapperPlayServerUpdateLight pkt = new WrapperPlayServerUpdateLight(event);
      shiftLight(pkt.getLightData(), lo / 16, event.getUser().getTotalWorldHeight() / 16 + 2);
    } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
      WrapperPlayServerBlockChange pkt = new WrapperPlayServerBlockChange(event);
      pkt.setBlockPosition(down(pkt.getBlockPosition(), lo));
    } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
      WrapperPlayServerMultiBlockChange pkt = new WrapperPlayServerMultiBlockChange(event);
      Vector3i sec = pkt.getChunkPosition();
      pkt.setChunkPosition(new Vector3i(sec.getX(), sec.getY() - lo / 16, sec.getZ()));
    } else if (type == PacketType.Play.Server.BLOCK_ACTION) {
      WrapperPlayServerBlockAction pkt = new WrapperPlayServerBlockAction(event);
      pkt.setBlockPosition(down(pkt.getBlockPosition(), lo));
    } else if (type == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
      WrapperPlayServerBlockEntityData pkt = new WrapperPlayServerBlockEntityData(event);
      pkt.setPosition(down(pkt.getPosition(), lo));
    } else if (type == PacketType.Play.Server.BLOCK_BREAK_ANIMATION) {
      WrapperPlayServerBlockBreakAnimation pkt = new WrapperPlayServerBlockBreakAnimation(event);
      pkt.setBlockPosition(down(pkt.getBlockPosition(), lo));
    } else if (type == PacketType.Play.Server.EFFECT) {
      WrapperPlayServerEffect pkt = new WrapperPlayServerEffect(event);
      pkt.setPosition(down(pkt.getPosition(), lo));
    } else if (type == PacketType.Play.Server.SOUND_EFFECT) {
      WrapperPlayServerSoundEffect pkt = new WrapperPlayServerSoundEffect(event);
      pkt.setPosition(down(pkt.getPosition(), lo));
    } else if (type == PacketType.Play.Server.PARTICLE) {
      WrapperPlayServerParticle pkt = new WrapperPlayServerParticle(event);
      pkt.setPosition(down(pkt.getPosition(), lo));
    } else if (type == PacketType.Play.Server.EXPLOSION) {
      WrapperPlayServerExplosion pkt = new WrapperPlayServerExplosion(event);
      pkt.setPosition(down(pkt.getPosition(), lo));
    } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
      WrapperPlayServerEntityTeleport pkt = new WrapperPlayServerEntityTeleport(event);
      RelativeFlag flags = pkt.getRelativeFlags();
      if (flags == null || !flags.has(RelativeFlag.Y)) {
        pkt.setPosition(down(pkt.getPosition(), lo));
      }
    } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
      WrapperPlayServerEntityPositionSync pkt = new WrapperPlayServerEntityPositionSync(event);
      EntityPositionData val = pkt.getValues();
      pkt.setValues(new EntityPositionData(down(val.getPosition(), lo), val.getDeltaMovement(), val.getYaw(), val.getPitch()));
    } else if (type == PacketType.Play.Server.VEHICLE_MOVE) {
      WrapperPlayServerVehicleMove pkt = new WrapperPlayServerVehicleMove(event);
      pkt.setPosition(down(pkt.getPosition(), lo));
    } else if (type == PacketType.Play.Server.SPAWN_POSITION) {
      WrapperPlayServerSpawnPosition pkt = new WrapperPlayServerSpawnPosition(event);
      if (pkt.getPosition() != null) {
        pkt.setPosition(down(pkt.getPosition(), lo));
      }
    } else if (type == PacketType.Play.Server.OPEN_SIGN_EDITOR) {
      WrapperPlayServerOpenSignEditor pkt = new WrapperPlayServerOpenSignEditor(event);
      pkt.setPosition(down(pkt.getPosition(), lo));
    }
    event.markForReEncode(true);
  }

  private static final boolean CONFIRM_CARRIES_POSITION =
      PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_26_3);

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    var type = event.getPacketType();
    if (outbound || !(type instanceof PacketType.Play.Client) || !unshifted(type)) {
      return;
    }
    User user = event.getUser();
    HeightWindow.Frame frame = HeightWindow.frame(user);
    if (frame == null) {
      return;
    }
    if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
      if (HeightWindow.get().confirm(user.getUUID(), new WrapperPlayClientTeleportConfirm(event).getTeleportId())) {
        event.setCancelled(true);
        return;
      }
      if (!CONFIRM_CARRIES_POSITION) {
        return;
      }
    }
    int lo = frame.inLo();
    if (lo == 0) {
      return;
    }
    try {
      shiftIn(event, type, lo);
    } catch (RuntimeException ex) {
      plugin.getLogger().log(Level.WARNING, "Height shift failed for " + type, ex);
    }
  }

  private static boolean unshifted(Object type) {
    return type == PacketType.Play.Client.TELEPORT_CONFIRM || type == PacketType.Play.Client.PLAYER_POSITION
        || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
        || type == PacketType.Play.Client.VEHICLE_MOVE || type == PacketType.Play.Client.PLAYER_DIGGING
        || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT || type == PacketType.Play.Client.UPDATE_SIGN
        || type == PacketType.Play.Client.QUERY_BLOCK_NBT || type == PacketType.Play.Client.UPDATE_COMMAND_BLOCK
        || type == PacketType.Play.Client.UPDATE_JIGSAW_BLOCK
        || type == PacketType.Play.Client.GENERATE_STRUCTURE;
  }

  private void shiftIn(PacketReceiveEvent event, Object type, int lo) {
    if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
      WrapperPlayClientTeleportConfirm pkt = new WrapperPlayClientTeleportConfirm(event);
      pkt.setY(pkt.getY() + lo);
    } else if (type == PacketType.Play.Client.PLAYER_POSITION) {
      WrapperPlayClientPlayerPosition pkt = new WrapperPlayClientPlayerPosition(event);
      pkt.setLocation(up(pkt.getLocation(), lo));
    } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
      WrapperPlayClientPlayerPositionAndRotation pkt = new WrapperPlayClientPlayerPositionAndRotation(event);
      pkt.setLocation(up(pkt.getLocation(), lo));
    } else if (type == PacketType.Play.Client.VEHICLE_MOVE) {
      WrapperPlayClientVehicleMove pkt = new WrapperPlayClientVehicleMove(event);
      pkt.setPosition(up(pkt.getPosition(), lo));
    } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
      WrapperPlayClientPlayerDigging pkt = new WrapperPlayClientPlayerDigging(event);
      pkt.setBlockPosition(up(pkt.getBlockPosition(), lo));
    } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
      WrapperPlayClientPlayerBlockPlacement pkt = new WrapperPlayClientPlayerBlockPlacement(event);
      pkt.setBlockPosition(up(pkt.getBlockPosition(), lo));
    } else if (type == PacketType.Play.Client.UPDATE_SIGN) {
      WrapperPlayClientUpdateSign pkt = new WrapperPlayClientUpdateSign(event);
      pkt.setBlockPosition(up(pkt.getBlockPosition(), lo));
    } else if (type == PacketType.Play.Client.QUERY_BLOCK_NBT) {
      WrapperPlayClientQueryBlockNBT pkt = new WrapperPlayClientQueryBlockNBT(event);
      pkt.setBlockPosition(up(pkt.getBlockPosition(), lo));
    } else if (type == PacketType.Play.Client.UPDATE_COMMAND_BLOCK) {
      WrapperPlayClientUpdateCommandBlock pkt = new WrapperPlayClientUpdateCommandBlock(event);
      pkt.setPosition(up(pkt.getPosition(), lo));
    } else if (type == PacketType.Play.Client.UPDATE_JIGSAW_BLOCK) {
      WrapperPlayClientUpdateJigsawBlock pkt = new WrapperPlayClientUpdateJigsawBlock(event);
      pkt.setPosition(up(pkt.getPosition(), lo));
    } else if (type == PacketType.Play.Client.GENERATE_STRUCTURE) {
      WrapperPlayClientGenerateStructure pkt = new WrapperPlayClientGenerateStructure(event);
      pkt.setBlockPosition(up(pkt.getBlockPosition(), lo));
    }
    event.markForReEncode(true);
  }

  private static Vector3i down(Vector3i pos, int lo) {
    return new Vector3i(pos.getX(), pos.getY() - lo, pos.getZ());
  }

  private static Vector3d down(Vector3d pos, int lo) {
    return new Vector3d(pos.getX(), pos.getY() - lo, pos.getZ());
  }

  private static Vector3i up(Vector3i pos, int lo) {
    return new Vector3i(pos.getX(), pos.getY() + lo, pos.getZ());
  }

  private static Vector3d up(Vector3d pos, int lo) {
    return new Vector3d(pos.getX(), pos.getY() + lo, pos.getZ());
  }

  private static Location up(Location loc, int lo) {
    return new Location(loc.getX(), loc.getY() + lo, loc.getZ(), loc.getYaw(), loc.getPitch());
  }

  private static void shiftColumn(Column col, int lo) {
    BaseChunk[] sections = col.getChunks();
    BaseChunk[] old = sections.clone();
    int shift = lo / 16;
    for (int j = 0; j < sections.length; j++) {
      sections[j] = old[Math.max(0, Math.min(old.length - 1, j + shift))];
    }
    for (TileEntity te : col.getTileEntities()) {
      te.setY(te.getY() - lo);
    }
    int total = sections.length * 16;
    int bits = 32 - Integer.numberOfLeadingZeros(total);
    for (Map.Entry<HeightmapType, long[]> map : col.getHeightmaps().entrySet()) {
      shiftHeightmap(map.getValue(), bits, lo, total);
    }
  }

  private static void shiftHeightmap(long[] packed, int bits, int lo, int total) {
    int perLong = 64 / bits;
    long mask = (1L << bits) - 1;
    for (int i = 0; i < 256; i++) {
      int idx = i / perLong;
      if (idx >= packed.length) {
        break;
      }
      int off = (i % perLong) * bits;
      long val = (packed[idx] >>> off) & mask;
      long moved = Math.max(0, Math.min(total, val - lo));
      packed[idx] = (packed[idx] & ~(mask << off)) | (moved << off);
    }
  }

  private record Moved(BitSet mask, byte[][] arrays) {
  }

  private static void shiftLight(LightData light, int shift, int bitCount) {
    if (light.getSkyLightMask() != null) {
      Moved sky = move(light.getSkyLightMask(), light.getSkyLightArray(), shift, bitCount);
      light.setSkyLightMask(sky.mask());
      light.setSkyLightArray(sky.arrays());
      light.setSkyLightCount(sky.arrays().length);
    }
    if (light.getBlockLightMask() != null) {
      Moved block = move(light.getBlockLightMask(), light.getBlockLightArray(), shift, bitCount);
      light.setBlockLightMask(block.mask());
      light.setBlockLightArray(block.arrays());
      light.setBlockLightCount(block.arrays().length);
    }
    light.setEmptySkyLightMask(move(light.getEmptySkyLightMask(), null, shift, bitCount).mask());
    light.setEmptyBlockLightMask(move(light.getEmptyBlockLightMask(), null, shift, bitCount).mask());
  }

  private static Moved move(BitSet mask, byte[][] arrays, int shift, int bitCount) {
    if (mask == null) {
      return new Moved(null, arrays);
    }
    BitSet out = new BitSet();
    List<byte[]> kept = new ArrayList<>();
    int n = 0;
    for (int b = mask.nextSetBit(0); b >= 0; b = mask.nextSetBit(b + 1)) {
      int nb = b - shift;
      if (nb >= 0 && nb < bitCount) {
        out.set(nb);
        if (arrays != null && n < arrays.length) {
          kept.add(arrays[n]);
        }
      }
      n++;
    }
    return new Moved(out, arrays == null ? null : kept.toArray(new byte[0][]));
  }
}
