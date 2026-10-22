package dev.bluesparksss.viavisuals.entity.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class AnimCatalog {

  public record Bone(String path, String proxy, int cmd, String hurtProxy, int hurtCmd, float fit) {
  }

  public record Appearance(String rigId, double frameY, List<Bone> bones) {
  }

  private final Map<String, Map<String, Appearance>> byEntity = new HashMap<>();
  private final Function<String, InputStream> resources;
  private final Map<String, RigData> rigs = new ConcurrentHashMap<>();
  private final Map<String, KeyframeAnim> animations = new ConcurrentHashMap<>();

  private AnimCatalog(Function<String, InputStream> resources) {
    this.resources = resources;
  }

  public static AnimCatalog load(Function<String, InputStream> resources) throws IOException {
    InputStream in = resources.apply("anim-catalog.json");
    if (in == null) {
      return null;
    }
    AnimCatalog cat = new AnimCatalog(resources);
    try (InputStream is = in) {
      JsonObject root = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
      List<String> proxies = new ArrayList<>();
      for (JsonElement el : root.getAsJsonArray("proxies")) {
        proxies.add(el.getAsString());
      }
      for (Map.Entry<String, JsonElement> ent : root.getAsJsonObject("entities").entrySet()) {
        Map<String, Appearance> apps = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> app : ent.getValue().getAsJsonObject().entrySet()) {
          JsonObject ao = app.getValue().getAsJsonObject();
          List<Bone> bones = new ArrayList<>();
          for (Map.Entry<String, JsonElement> be : ao.getAsJsonObject("bones").entrySet()) {
            JsonArray a = be.getValue().getAsJsonArray();
            bones.add(new Bone(be.getKey(), proxies.get(a.get(0).getAsInt()), a.get(1).getAsInt(),
                proxies.get(a.get(2).getAsInt()), a.get(3).getAsInt(), a.get(4).getAsFloat()));
          }
          apps.put(app.getKey(), new Appearance(ao.get("rig").getAsString(),
              ao.has("frameY") ? ao.get("frameY").getAsDouble() : PoseSolver.ROOT_Y, List.copyOf(bones)));
        }
        cat.byEntity.put(ent.getKey(), apps);
      }
    }
    return cat;
  }

  public Appearance appearance(String entityId, String key) {
    Map<String, Appearance> apps = byEntity.get(entityId);
    if (apps == null) {
      return null;
    }
    Appearance app = apps.get(key);
    if (app == null) {
      int pose = key.indexOf(";pose=");
      if (pose > 0) {
        app = apps.get(key.substring(0, pose));
      }
    }
    return app;
  }

  public boolean has(String entityId) {
    return byEntity.containsKey(entityId);
  }

  public RigData rig(String rigId) {
    return rigs.computeIfAbsent(rigId, id -> {
      try (InputStream in = resources.apply("anim/rigs/" + id + ".json")) {
        if (in == null) {
          throw new IllegalStateException("missing rig " + id);
        }
        return RigData.parse(id, JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    });
  }

  public KeyframeAnim animation(String id) {
    return animations.computeIfAbsent(id, key -> {
      try (InputStream in = resources.apply("anim/animations/" + key + ".json")) {
        if (in == null) {
          throw new IllegalStateException("missing animation " + key);
        }
        return KeyframeAnim.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    });
  }
}
