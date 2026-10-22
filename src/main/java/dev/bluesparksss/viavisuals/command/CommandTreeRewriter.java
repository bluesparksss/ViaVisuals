package dev.bluesparksss.viavisuals.command;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.chat.Node;
import com.github.retrooper.packetevents.protocol.chat.Parsers;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareCommands;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.compat.RegistrySince;
import dev.bluesparksss.viavisuals.version.VersionManager;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;

public final class CommandTreeRewriter extends PacketListenerAbstract {

  private static final ResourceLocation ASK_SERVER = new ResourceLocation("minecraft", "ask_server");

  private static final Set<Parsers.Parser> REGISTRY_PARSERS = Set.of(
      Parsers.ITEM_STACK, Parsers.ITEM_PREDICATE, Parsers.ITEM_ENCHANTMENT, Parsers.ENTITY_SUMMON,
      Parsers.BLOCK_STATE, Parsers.BLOCK_PREDICATE, Parsers.MOB_EFFECT, Parsers.PARTICLE,
      Parsers.RESOURCE, Parsers.RESOURCE_KEY, Parsers.RESOURCE_OR_TAG, Parsers.RESOURCE_OR_TAG_KEY);

  public CommandTreeRewriter() {
    super(PacketListenerPriority.HIGH);
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    if (event.getPacketType() != PacketType.Play.Server.DECLARE_COMMANDS) {
      return;
    }
    Player player = event.getPlayer();
    Optional<ProtocolVersion> proto = player == null ? Optional.empty() : VersionManager.tryGetProtocolVersion(player);
    if (proto.isEmpty() || !proto.get().olderThan(RegistrySince.serverVersion())) {
      return;
    }
    WrapperPlayServerDeclareCommands pkt = new WrapperPlayServerDeclareCommands(event);
    boolean dirty = false;
    for (Node node : pkt.getNodes()) {
      if ((node.getFlags() & Node.TYPE_MASK) != Node.TYPE_ARGUMENT) {
        continue;
      }
      Optional<Parsers.Parser> parser = node.getParser();
      if (parser.isEmpty() || !REGISTRY_PARSERS.contains(parser.get())) {
        continue;
      }
      node.setParser(Optional.of(Parsers.RESOURCE_LOCATION));
      node.setProperties(Optional.empty());
      node.setFlags((byte) (node.getFlags() | Node.FLAG_CUSTOM_SUGGESTIONS));
      node.setSuggestionsType(Optional.of(ASK_SERVER));
      dirty = true;
    }
    if (dirty) {
      pkt.setNodes(pkt.getNodes());
      event.markForReEncode(true);
    }
  }
}
