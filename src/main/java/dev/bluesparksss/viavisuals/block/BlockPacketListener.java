package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleBlockStateData;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.version.VersionManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

public final class BlockPacketListener extends PacketListenerAbstract {

  private final JavaPlugin plugin;
  private final BlockCatalog catalog;
  private final BlockDisplays displays;
  private final DisplayOcclusion occlusion;
  private final LightResync light;

  public BlockPacketListener(JavaPlugin plugin, BlockCatalog catalog, BlockDisplays displays, DisplayOcclusion occlusion,
                             LightResync light) {
    super(PacketListenerPriority.HIGH);
    this.plugin = plugin;
    this.catalog = catalog;
    this.displays = displays;
    this.occlusion = occlusion;
    this.light = light;
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    var type = event.getPacketType();
    if (type != PacketType.Play.Server.CHUNK_DATA && type != PacketType.Play.Server.BLOCK_CHANGE
        && type != PacketType.Play.Server.MULTI_BLOCK_CHANGE && type != PacketType.Play.Server.UNLOAD_CHUNK
        && type != PacketType.Play.Server.PARTICLE && type != PacketType.Play.Server.SPAWN_ENTITY
        && type != PacketType.Play.Server.ENTITY_METADATA) {
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
    ProtocolVersion client = proto.get();
    User user = event.getUser();
    try {
      if (type == PacketType.Play.Server.CHUNK_DATA) {
        handleChunk(event, user, client, player);
      } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
        WrapperPlayServerBlockChange pkt = new WrapperPlayServerBlockChange(event);
        Vector3i pos = pkt.getBlockPosition();
        occlusion.onBlock(player.getWorld(), pos.getX(), pos.getY(), pos.getZ(), pkt.getBlockId());
        int out = rewrite(user, client, pkt.getBlockId(), pos.getX(), pos.getY(), pos.getZ(), false);
        if (out != pkt.getBlockId()) {
          pkt.setBlockID(out);
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        WrapperPlayServerMultiBlockChange pkt = new WrapperPlayServerMultiBlockChange(event);
        boolean dirty = false;
        for (WrapperPlayServerMultiBlockChange.EncodedBlock blk : pkt.getBlocks()) {
          occlusion.onBlock(player.getWorld(), blk.getX(), blk.getY(), blk.getZ(), blk.getBlockId());
          int out = rewrite(user, client, blk.getBlockId(), blk.getX(), blk.getY(), blk.getZ(), false);
          if (out != blk.getBlockId()) {
            blk.setBlockId(out);
            dirty = true;
          }
        }
        if (dirty) {
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.PARTICLE) {
        WrapperPlayServerParticle pkt = new WrapperPlayServerParticle(event);
        if (pkt.getParticle().getData() instanceof ParticleBlockStateData data) {
          int in = data.getBlockState().getGlobalId();
          int out = carrierState(client, in);
          if (out != in) {
            data.setBlockState(WrappedBlockState.getByGlobalId(catalog.serverVersion(), out, false));
            event.markForReEncode(true);
          }
        }
      } else if (type == PacketType.Play.Server.SPAWN_ENTITY) {
        WrapperPlayServerSpawnEntity pkt = new WrapperPlayServerSpawnEntity(event);
        if (pkt.getEntityType() == EntityTypes.FALLING_BLOCK) {
          int out = carrierState(client, pkt.getData());
          if (out != pkt.getData()) {
            pkt.setData(out);
            event.markForReEncode(true);
          }
        }
      } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
        WrapperPlayServerEntityMetadata pkt = new WrapperPlayServerEntityMetadata(event);
        List<EntityData<?>> data = pkt.getEntityMetadata();
        boolean dirty = false;
        for (int i = 0; i < data.size(); i++) {
          EntityData<?> ent = data.get(i);
          if ((ent.getType() == EntityDataTypes.BLOCK_STATE || ent.getType() == EntityDataTypes.OPTIONAL_BLOCK_STATE)
              && ent.getValue() instanceof Integer id && id > 0) {
            int out = carrierState(client, id);
            if (out != id) {
              data.set(i, blockStateData(ent.getIndex(), ent.getType() == EntityDataTypes.OPTIONAL_BLOCK_STATE, out));
              dirty = true;
            }
          }
        }
        if (dirty) {
          event.markForReEncode(true);
        }
      } else {
        WrapperPlayServerUnloadChunk pkt = new WrapperPlayServerUnloadChunk(event);
        displays.clearChunk(user, pkt.getChunkX(), pkt.getChunkZ());
        light.unload(user.getUUID(), pkt.getChunkX(), pkt.getChunkZ());
        occlusion.unloadChunk(player.getWorld(), pkt.getChunkX(), pkt.getChunkZ());
      }
    } catch (RuntimeException ex) {
      plugin.getLogger().log(Level.WARNING, "Block rewrite failed for " + type + " to " + player.getName(), ex);
    }
    var queued = displays.drainPending();
    if (!queued.isEmpty()) {
      event.getTasksAfterSend().add(() -> dev.bluesparksss.viavisuals.display.ProxyEntities.send(user, queued));
    }
  }

  private void handleChunk(PacketSendEvent event, User user, ProtocolVersion client, Player player) {
    WrapperPlayServerChunkData pkt = new WrapperPlayServerChunkData(event);
    Column col = pkt.getColumn();
    int minY = user.getMinWorldHeight();
    BaseChunk[] sections = col.getChunks();
    boolean dirty = false;
    displays.clearChunk(user, col.getX(), col.getZ());
    occlusion.invalidate(player.getWorld(), col.getX(), col.getZ());
    for (int sec = 0; sec < sections.length; sec++) {
      BaseChunk section = sections[sec];
      if (section == null || section.isEmpty() || !mayContainRelevant(section)) {
        continue;
      }
      int baseY = minY + sec * 16;
      for (int y = 0; y < 16; y++) {
        for (int z = 0; z < 16; z++) {
          for (int x = 0; x < 16; x++) {
            int id = section.getBlockId(x, y, z);
            if (!catalog.isRelevant(id)) {
              continue;
            }
            int out = rewrite(user, client, id, (col.getX() << 4) + x, baseY + y, (col.getZ() << 4) + z, true);
            if (out != id) {
              section.set(x, y, z, out);
              dirty = true;
            }
          }
        }
      }
    }
    if (dirty) {
      event.markForReEncode(true);
    }
  }

  private boolean mayContainRelevant(BaseChunk section) {
    if (!(section instanceof Chunk_v1_18 chunk)) {
      return true;
    }
    DataPalette data = chunk.getChunkData();
    if (data == null || data.palette instanceof GlobalPalette) {
      return true;
    }
    for (int i = 0; i < data.palette.size(); i++) {
      if (catalog.isRelevant(data.palette.idToState(i))) {
        return true;
      }
    }
    return false;
  }

  private static EntityData<Integer> blockStateData(int index, boolean optional, int state) {
    return new EntityData<>(index, optional ? EntityDataTypes.OPTIONAL_BLOCK_STATE : EntityDataTypes.BLOCK_STATE, state);
  }

  private int carrierState(ProtocolVersion client, int state) {
    BlockCatalog.Mapping map = catalog.mappingFor(state, client);
    if (map != null) {
      if (map.fallingState() >= 0) {
        return map.fallingState();
      }
      return map.hasDisplay() && map.cullable() ? state : catalog.carrierFor(map, client);
    }
    int canon = catalog.canonicalFor(state);
    return canon >= 0 ? canon : state;
  }

  private int rewrite(User user, ProtocolVersion client, int state, int x, int y, int z, boolean bulk) {
    BlockCatalog.Mapping map = catalog.mappingFor(state, client);
    if (client.olderThan(ProtocolVersion.v1_17)) {
      light.seen(user.getUUID(), x, y, z, map != null && map.emits(), bulk);
    }
    if (map == null) {
      displays.remove(user, x, y, z);
      int canon = catalog.canonicalFor(state);
      return canon >= 0 ? canon : state;
    }
    if (map.hasDisplay()) {
      displays.show(user, client, x, y, z, map, bulk);
    } else {
      displays.remove(user, x, y, z);
    }
    return catalog.carrierFor(map, client);
  }
}
