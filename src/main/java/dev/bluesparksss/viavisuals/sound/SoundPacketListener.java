package dev.bluesparksss.viavisuals.sound;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.protocol.sound.StaticSound;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.compat.RegistrySince;
import dev.bluesparksss.viavisuals.version.VersionManager;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public final class SoundPacketListener extends PacketListenerAbstract {

  private final Predicate<UUID> packLoaded;

  public SoundPacketListener(Predicate<UUID> packLoaded) {
    super(PacketListenerPriority.HIGH);
    this.packLoaded = packLoaded;
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    var type = event.getPacketType();
    if (type != PacketType.Play.Server.SOUND_EFFECT && type != PacketType.Play.Server.ENTITY_SOUND_EFFECT) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null || !packLoaded.test(player.getUniqueId())) {
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty() || !proto.get().olderThan(RegistrySince.serverVersion())) {
      return;
    }
    if (type == PacketType.Play.Server.SOUND_EFFECT) {
      WrapperPlayServerSoundEffect pkt = new WrapperPlayServerSoundEffect(event);
      Sound out = remap(pkt.getSound(), proto.get());
      if (out != null) {
        pkt.setSound(out);
        event.markForReEncode(true);
      }
    } else {
      WrapperPlayServerEntitySoundEffect pkt = new WrapperPlayServerEntitySoundEffect(event);
      Sound out = remap(pkt.getSound(), proto.get());
      if (out != null) {
        pkt.setSound(out);
        event.markForReEncode(true);
      }
    }
  }

  private static Sound remap(Sound sound, ProtocolVersion client) {
    if (sound == null) {
      return null;
    }
    ResourceLocation id = sound.getSoundId();
    if (!"minecraft".equals(id.getNamespace())) {
      return null;
    }
    String path = id.getKey();
    if (path.startsWith("music.") || path.startsWith("music_disc.")) {
      return null;
    }
    if (!RegistrySince.get().isTracked(RegistrySince.Kind.SOUND, path)
        || RegistrySince.get().isNative(RegistrySince.Kind.SOUND, path, client)) {
      return null;
    }
    return new StaticSound(new ResourceLocation("viavisuals", path), sound.getRange());
  }

}
