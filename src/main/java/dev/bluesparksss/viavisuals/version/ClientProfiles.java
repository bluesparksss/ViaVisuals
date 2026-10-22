package dev.bluesparksss.viavisuals.version;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import dev.bluesparksss.viavisuals.compat.RegistrySince;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientProfiles {

  public enum Source { VIA, PROXY, MOD, OVERRIDE }

  public enum Pack { NONE, PENDING, LOADED, FAILED }

  public enum Tier { NATIVE, UNSUPPORTED, VISUAL, PLAIN }

  public record Profile(ProtocolVersion connection, ProtocolVersion client, ProtocolVersion real, Source source,
                        Pack pack, Tier tier) {
  }

  private record Details(ProtocolVersion version, Source source) {
  }

  private static final Map<UUID, Details> DETAILS = new ConcurrentHashMap<>();
  private static final Map<UUID, ProtocolVersion> OVERRIDES = new ConcurrentHashMap<>();
  private static final Map<UUID, Pack> PACKS = new ConcurrentHashMap<>();

  private ClientProfiles() {
  }

  public static Optional<Profile> of(UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    ProtocolVersion conn = connection(id);
    if (conn == null) {
      return Optional.empty();
    }
    ProtocolVersion client = conn;
    ProtocolVersion real = conn;
    Source source = Source.VIA;
    Details det = DETAILS.get(id);
    if (det != null) {
      if (det.version().olderThan(conn)) {
        client = det.version();
        real = det.version();
        source = det.source();
      } else if (det.source() == Source.MOD) {
        real = det.version();
        source = Source.MOD;
      }
    }
    ProtocolVersion forced = OVERRIDES.get(id);
    if (forced != null) {
      client = forced;
      real = forced;
      source = Source.OVERRIDE;
    }
    Pack pack = PACKS.getOrDefault(id, Pack.NONE);
    return Optional.of(new Profile(conn, client, real, source, pack, tier(client, pack)));
  }

  private static Tier tier(ProtocolVersion client, Pack pack) {
    if (!client.olderThan(RegistrySince.serverVersion())) {
      return Tier.NATIVE;
    }
    if (!VersionManager.isSupported(client)) {
      return Tier.UNSUPPORTED;
    }
    return pack == Pack.FAILED ? Tier.PLAIN : Tier.VISUAL;
  }

  private static ProtocolVersion connection(UUID id) {
    try {
      ProtocolVersion version = Via.getAPI().getPlayerProtocolVersion(id);
      if (!version.isKnown() || version.equalTo(ProtocolVersion.unknown)) {
        return null;
      }
      return version;
    } catch (RuntimeException ign) {
      return null;
    }
  }

  public static boolean setDetails(UUID id, ProtocolVersion version, Source source) {
    ProtocolVersion before = of(id).map(Profile::client).orElse(null);
    Details prev = DETAILS.put(id, new Details(version, source));
    if (prev != null && prev.version().equalTo(version) && prev.source() == source) {
      return false;
    }
    ProtocolVersion after = of(id).map(Profile::client).orElse(null);
    return before == null || after == null || !before.equalTo(after);
  }

  public static void setOverride(UUID id, ProtocolVersion version) {
    if (version == null) {
      OVERRIDES.remove(id);
    } else {
      OVERRIDES.put(id, version);
    }
  }

  public static boolean setPack(UUID id, Pack pack) {
    Tier before = of(id).map(Profile::tier).orElse(null);
    PACKS.put(id, pack);
    Tier after = of(id).map(Profile::tier).orElse(null);
    return before != after;
  }

  public static Pack pack(UUID id) {
    return PACKS.getOrDefault(id, Pack.NONE);
  }

  public static void forget(UUID id) {
    DETAILS.remove(id);
    OVERRIDES.remove(id);
    PACKS.remove(id);
  }

  public static ProtocolVersion parse(int version, String type) {
    ProtocolVersion found = null;
    if (type != null) {
      try {
        found = ProtocolVersion.getProtocol(VersionType.valueOf(type), version);
      } catch (IllegalArgumentException | NullPointerException ex) {
        found = null;
      }
    }
    if (found == null || !found.isKnown()) {
      found = ProtocolVersion.getProtocol(version);
    }
    return found != null && found.isKnown() ? found : null;
  }
}
