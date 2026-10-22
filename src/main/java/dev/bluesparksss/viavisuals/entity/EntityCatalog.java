package dev.bluesparksss.viavisuals.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EntityCatalog {

  public record Appearance(int cmd, ProtocolVersion since) {
  }

  public record Part(int cmd, ProtocolVersion since, Map<String, List<String>> when) {
  }

  public record Def(
      String id,
      String fallback,
      boolean spin,
      boolean vehicle,
      boolean babyOnly,
      LinkedHashMap<String, List<String>> dims,
      List<String> poses,
      Map<String, Appearance> appearances,
      Map<String, Part> parts
  ) {

    public String dimValue(String dim, String val) {
      List<String> vals = dims.get(dim);
      if (vals == null || vals.isEmpty()) {
        return val;
      }
      return val != null && vals.contains(val) ? val : vals.get(0);
    }
  }

  private final Map<String, Def> byId;
  private final int hurtOffset;

  private EntityCatalog(Map<String, Def> byId, int hurtOffset) {
    this.byId = byId;
    this.hurtOffset = hurtOffset;
  }

  public static EntityCatalog load(InputStream input) throws IOException {
    JsonObject root;
    try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      root = JsonParser.parseReader(reader).getAsJsonObject();
    }
    Map<String, Def> defs = new HashMap<>();
    for (Map.Entry<String, JsonElement> ent : root.getAsJsonObject("entities").entrySet()) {
      JsonObject obj = ent.getValue().getAsJsonObject();
      LinkedHashMap<String, List<String>> dims = new LinkedHashMap<>();
      for (Map.Entry<String, JsonElement> dim : obj.getAsJsonObject("dims").entrySet()) {
        dims.put(dim.getKey(), strings(dim.getValue().getAsJsonArray()));
      }
      Map<String, Appearance> apps = new HashMap<>();
      for (Map.Entry<String, JsonElement> app : obj.getAsJsonObject("appearances").entrySet()) {
        JsonObject a = app.getValue().getAsJsonObject();
        ProtocolVersion since = protocol(a.get("since").getAsString());
        apps.put(app.getKey(), new Appearance(a.get("cmd").getAsInt(), since));
      }
      Map<String, Part> parts = new HashMap<>();
      for (Map.Entry<String, JsonElement> part : obj.getAsJsonObject("parts").entrySet()) {
        JsonObject p = part.getValue().getAsJsonObject();
        Map<String, List<String>> when = new HashMap<>();
        if (p.has("when")) {
          for (Map.Entry<String, JsonElement> w : p.getAsJsonObject("when").entrySet()) {
            when.put(w.getKey(), strings(w.getValue().getAsJsonArray()));
          }
        }
        parts.put(part.getKey(), new Part(p.get("cmd").getAsInt(), protocol(p.get("since").getAsString()), when));
      }
      defs.put(ent.getKey(), new Def(
          ent.getKey(),
          obj.has("fallback") ? obj.get("fallback").getAsString() : null,
          obj.get("spin").getAsBoolean(),
          obj.get("vehicle").getAsBoolean(),
          obj.get("babyOnly").getAsBoolean(),
          dims,
          strings(obj.getAsJsonArray("poses")),
          apps,
          parts));
    }
    return new EntityCatalog(defs, root.get("hurtOffset").getAsInt());
  }

  private static List<String> strings(JsonArray arr) {
    List<String> out = new ArrayList<>();
    for (JsonElement el : arr) {
      out.add(el.getAsString());
    }
    return out;
  }

  private static ProtocolVersion protocol(String label) {
    ProtocolVersion newest = ProtocolVersion.v1_16;
    for (ProtocolVersion ver : ProtocolVersion.getProtocols()) {
      if (ver.getIncludedVersions().contains(label)) {
        return ver;
      }
      if (ver.isKnown() && ver.newerThan(newest)) {
        newest = ver;
      }
    }
    return newest;
  }

  public Def def(String entityId) {
    return byId.get(entityId);
  }

  public int hurtOffset() {
    return hurtOffset;
  }

  public int size() {
    return byId.size();
  }
}
