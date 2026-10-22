package dev.bluesparksss.viavisuals.mechanics.wind;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.compat.RegistrySince;
import dev.bluesparksss.viavisuals.item.ItemTranslator;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Player;

import java.util.List;

public final class WindChargeLegacyPacketListener implements PacketListener {

  private static final org.bukkit.Material WIND_CHARGE = org.bukkit.Material.matchMaterial("wind_charge");
  private static final int NO_GRAVITY_INDEX = 5;
  private static final int SNOWBALL_ITEM_INDEX = 8;
  private static final double OUT_OF_RANGE = 100_000.0D;

  private final WindChargeEntityTracker tracker;
  private final WindExplosionEffects effects;
  private final ItemTranslator xlat;

  public WindChargeLegacyPacketListener(
      WindChargeEntityTracker tracker,
      WindExplosionEffects effects,
      ItemTranslator xlat
  ) {
    this.tracker = tracker;
    this.effects = effects;
    this.xlat = xlat;
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    if (!effects.isEnabled() || !effects.legacyClientProxy()) {
      return;
    }

    ProtocolVersion proto = resolveRecipientVersion(event);
    if (proto == null || proto.newerThanOrEqualTo(ProtocolVersion.v1_21)) {
      return;
    }

    if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
      if (!RegistrySince.get().isNative(RegistrySince.Kind.ENTITY, "wind_charge", proto)) {
        handleSpawnEntity(event, proto);
      }
      return;
    }

    if (event.getPacketType() == PacketType.Play.Server.EXPLOSION) {
      handleExplosion(event);
    }
  }

  private void handleSpawnEntity(PacketSendEvent event, ProtocolVersion proto) {
    WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(event);
    if (!isWindChargeEntityType(packet.getEntityType())) {
      return;
    }
    org.bukkit.inventory.ItemStack proxyItem =
        WIND_CHARGE == null ? null : xlat.toClient(new org.bukkit.inventory.ItemStack(WIND_CHARGE), proto);
    User user = event.getUser();
    if (proxyItem == null || proxyItem.getType().isAir() || user == null) {
      return;
    }
    int entId = packet.getEntityId();
    tracker.track(entId, packet.getUUID().orElse(null), user.getUUID());
    packet.setEntityType(EntityTypes.SNOWBALL);
    event.markForReEncode(true);

    List<EntityData<?>> meta = List.of(
        new EntityData<>(NO_GRAVITY_INDEX, EntityDataTypes.BOOLEAN, true),
        new EntityData<>(SNOWBALL_ITEM_INDEX, EntityDataTypes.ITEMSTACK, SpigotConversionUtil.fromBukkitItemStack(proxyItem)));
    event.getTasksAfterSend().add(() -> user.sendPacket(new WrapperPlayServerEntityMetadata(entId, meta)));
  }

  private static boolean isWindChargeEntityType(EntityType type) {
    if (type == null) {
      return false;
    }

    if (type == EntityTypes.WIND_CHARGE
        || type == EntityTypes.BREEZE_WIND_CHARGE
        || type == EntityTypes.ABSTRACT_WIND_CHARGE) {
      return true;
    }

    ResourceLocation name = type.getName();
    return name != null && name.getKey().contains("wind_charge");
  }

  private void handleExplosion(PacketSendEvent event) {
    WrapperPlayServerExplosion packet = new WrapperPlayServerExplosion(event);
    if (!isWindBurstExplosion(packet)) {
      return;
    }

    Vector3d at = packet.getPosition();
    packet.setPosition(new Vector3d(at.getX(), at.getY() + OUT_OF_RANGE, at.getZ()));
    packet.setRecords(List.of());
    packet.setBlockCount(0);
    Particle<?> poof = new Particle<>(ParticleTypes.POOF);
    if (packet.getSmallExplosionParticles() != null) {
      packet.setSmallExplosionParticles(poof);
    }
    if (packet.getLargeExplosionParticles() != null) {
      packet.setLargeExplosionParticles(poof);
    }
    if (packet.getParticle() != null) {
      packet.setParticle(poof);
    }
    event.markForReEncode(true);
  }

  private static boolean isWindBurstExplosion(WrapperPlayServerExplosion packet) {
    if (containsWindBurstSound(packet.getExplosionSoundKey())) {
      return true;
    }

    Sound explSou = packet.getExplosionSound();
    if (explSou != null && containsWindBurstSound(explSou.getSoundId())) {
      return true;
    }

    if (usesWindBurstParticles(packet.getSmallExplosionParticles())
        || usesWindBurstParticles(packet.getLargeExplosionParticles())
        || usesWindBurstParticles(packet.getParticle())) {
      return true;
    }

    WrapperPlayServerExplosion.BlockInteraction interaction = packet.getBlockInteraction();
    if (interaction != WrapperPlayServerExplosion.BlockInteraction.KEEP_BLOCKS
        && interaction != WrapperPlayServerExplosion.BlockInteraction.TRIGGER_BLOCKS) {
      return false;
    }

    return packet.getRecords().isEmpty();
  }

  private static boolean containsWindBurstSound(ResourceLocation sounKey) {
    return sounKey != null && sounKey.getKey().contains("wind_burst");
  }

  private static boolean usesWindBurstParticles(Particle<?> particle) {
    if (particle == null || particle.getType() == null) {
      return false;
    }

    if (particle.getType() == ParticleTypes.GUST
        || particle.getType() == ParticleTypes.GUST_EMITTER_LARGE
        || particle.getType() == ParticleTypes.GUST_EMITTER_SMALL) {
      return true;
    }

    ResourceLocation partNam = particle.getType().getName();
    return partNam != null && partNam.getKey().contains("gust");
  }

  private static ProtocolVersion resolveRecipientVersion(PacketSendEvent event) {
    Player player = event.getPlayer();
    if (player != null) {
      return VersionManager.tryGetProtocolVersion(player).orElse(null);
    }

    if (event.getUser() == null || event.getUser().getUUID() == null) {
      return null;
    }

    return VersionManager.tryGetProtocolVersion(event.getUser().getUUID()).orElse(null);
  }
}
