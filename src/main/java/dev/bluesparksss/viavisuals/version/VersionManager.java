package dev.bluesparksss.viavisuals.version;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public final class VersionManager {

  public static final ProtocolVersion MIN_SUPPORTED_VERSION = ProtocolVersion.v1_16;

  private VersionManager() {
  }

  public static Optional<ProtocolVersion> tryGetProtocolVersion(Player player) {
    if (player == null) {
      return Optional.empty();
    }
    return tryGetProtocolVersion(player.getUniqueId());
  }

  public static Optional<ProtocolVersion> tryGetProtocolVersion(UUID plrId) {
    return ClientProfiles.of(plrId).map(ClientProfiles.Profile::client);
  }

  public static Optional<ProtocolVersion> tryGetVisualVersion(Player player) {
    return player == null ? Optional.empty() : tryGetVisualVersion(player.getUniqueId());
  }

  public static Optional<ProtocolVersion> tryGetVisualVersion(UUID plrId) {
    return ClientProfiles.of(plrId)
        .filter(prof -> prof.tier() == ClientProfiles.Tier.VISUAL || prof.tier() == ClientProfiles.Tier.NATIVE)
        .map(ClientProfiles.Profile::client);
  }

  public static ProtocolVersion getProtocolVersion(Player player) {
    return tryGetProtocolVersion(player).orElseThrow(() ->
        new IllegalStateException("Could not resolve protocol version for "
            + (player != null ? player.getName() : "null")));
  }

  public static boolean isSupported(Player player) {
    return tryGetProtocolVersion(player).map(VersionManager::isSupported).orElse(false);
  }

  public static boolean isSupported(ProtocolVersion version) {
    return version != null
        && version.isKnown()
        && !version.equalTo(ProtocolVersion.unknown)
        && version.newerThanOrEqualTo(MIN_SUPPORTED_VERSION);
  }
}
