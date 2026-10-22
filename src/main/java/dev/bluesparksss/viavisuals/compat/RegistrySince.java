package dev.bluesparksss.viavisuals.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class RegistrySince {

  public enum Kind {
    ITEM("items"),
    BLOCK("blocks"),
    ENTITY("entities"),
    SOUND("sounds");

    private final String jsonKey;

    Kind(String jsonKey) {
      this.jsonKey = jsonKey;
    }
  }

  private static volatile RegistrySince instance = new RegistrySince(Map.of());

  private final Map<Kind, Map<String, ProtocolVersion>> since;

  private RegistrySince(Map<Kind, Map<String, ProtocolVersion>> since) {
    this.since = since;
  }

  public static RegistrySince get() {
    return instance;
  }

  public static void load(InputStream input) throws IOException {
    JsonObject root;
    try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      root = JsonParser.parseReader(reader).getAsJsonObject();
    }
    Map<Kind, Map<String, ProtocolVersion>> loaded = new HashMap<>();
    for (Kind kind : Kind.values()) {
      Map<String, ProtocolVersion> ids = new HashMap<>();
      JsonObject obj = root.getAsJsonObject(kind.jsonKey);
      if (obj != null) {
        for (Map.Entry<String, com.google.gson.JsonElement> ent : obj.entrySet()) {
          ProtocolVersion ver = protocolFor(ent.getValue().getAsString());
          if (ver != null) {
            ids.put(ent.getKey(), ver);
          }
        }
      }
      loaded.put(kind, Map.copyOf(ids));
    }
    instance = new RegistrySince(Map.copyOf(loaded));
  }

  public static ProtocolVersion protocolFor(String label) {
    for (ProtocolVersion ver : ProtocolVersion.getProtocols()) {
      if (ver.getIncludedVersions().contains(label)) {
        return ver;
      }
    }
    return null;
  }

  public Optional<ProtocolVersion> since(Kind kind, String id) {
    if (id == null) {
      return Optional.empty();
    }
    String keyStr = id.toLowerCase(Locale.ROOT);
    int colon = keyStr.indexOf(':');
    if (colon >= 0) {
      keyStr = keyStr.substring(colon + 1);
    }
    return Optional.ofNullable(since.getOrDefault(kind, Map.of()).get(keyStr));
  }

  public boolean isTracked(Kind kind, String id) {
    return since(kind, id).isPresent();
  }

  public boolean isNative(Kind kind, String id, ProtocolVersion client) {
    Optional<ProtocolVersion> added = since(kind, id);
    if (added.isEmpty()) {
      return true;
    }
    return client != null && client.newerThanOrEqualTo(added.get());
  }

  public boolean existsOnServer(Kind kind, String id) {
    Optional<ProtocolVersion> added = since(kind, id);
    return added.isEmpty() || serverVersion().newerThanOrEqualTo(added.get());
  }

  public static ProtocolVersion serverVersion() {
    return Via.getAPI().getServerVersion().highestSupportedProtocolVersion();
  }
}
