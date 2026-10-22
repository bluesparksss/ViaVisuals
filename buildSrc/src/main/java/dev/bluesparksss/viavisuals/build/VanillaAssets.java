package dev.bluesparksss.viavisuals.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Copies vanilla models/textures from the newest release into the {@code viavisuals} namespace so
 * every supported client (1.16+) can load them, rewriting features older model parsers reject.
 */
final class VanillaAssets {

  static final String NS = "viavisuals";
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private static final double[] LEGACY_ANGLES = {-45.0, -22.5, 0.0, 22.5, 45.0};

  private final List<Path> roots;
  private final Path staging;
  private final Map<String, String> backportedModels = new HashMap<>();
  private final Map<String, String> backportedTextures = new HashMap<>();

  VanillaAssets(List<Path> roots, Path staging) {
    this.roots = roots;
    this.staging = staging;
  }

  Optional<Path> find(String relPath) {
    for (Path root : roots) {
      Path path = root.resolve(relPath);
      if (Files.exists(path)) {
        return Optional.of(path);
      }
    }
    return Optional.empty();
  }

  Optional<JsonObject> readJson(String relPath) throws IOException {
    Optional<Path> path = find(relPath);
    if (path.isEmpty()) {
      return Optional.empty();
    }
    try (Reader reader = Files.newBufferedReader(path.get(), StandardCharsets.UTF_8)) {
      return Optional.of(JsonParser.parseReader(reader).getAsJsonObject());
    }
  }

  Optional<JsonObject> itemDefinition(String itemId) throws IOException {
    return readJson("assets/minecraft/items/" + itemId + ".json");
  }

  static String path(String ref) {
    int colon = ref.indexOf(':');
    return colon < 0 ? ref : ref.substring(colon + 1);
  }

  static String namespace(String ref) {
    int colon = ref.indexOf(':');
    return colon < 0 ? "minecraft" : ref.substring(0, colon);
  }

  /** Backports a vanilla model and its parents/textures; returns the {@code viavisuals:} ref. */
  String backportModel(String ref) throws IOException {
    if (ref.startsWith("builtin/") || ref.startsWith("minecraft:builtin/")) {
      return ref;
    }
    if (NS.equals(namespace(ref))) {
      return ref;
    }
    String keyStr = path(ref);
    String done = backportedModels.get(keyStr);
    if (done != null) {
      return done;
    }
    String target = NS + ":" + keyStr;
    backportedModels.put(keyStr, target);

    JsonObject model = readJson("assets/minecraft/models/" + keyStr + ".json")
        .orElseThrow(() -> new IOException("vanilla model not found: " + ref));
    model = model.deepCopy();
    if (model.has("parent")) {
      model.addProperty("parent", backportModel(model.get("parent").getAsString()));
    }
    if (model.has("textures")) {
      JsonObject tex = model.getAsJsonObject("textures");
      for (Map.Entry<String, JsonElement> ent : tex.entrySet()) {
        String val = ent.getValue().getAsString();
        if (!val.startsWith("#")) {
          tex.addProperty(ent.getKey(), backportTexture(val));
        }
      }
    }
    sanitizeElements(model);
    writeModel(target, model);
    return target;
  }

  String backportTexture(String ref) throws IOException {
    if (NS.equals(namespace(ref))) {
      return ref;
    }
    String keyStr = path(ref);
    String done = backportedTextures.get(keyStr);
    if (done != null) {
      return done;
    }
    String rel = "assets/minecraft/textures/" + keyStr + ".png";
    Path src = find(rel).orElseThrow(() -> new IOException("vanilla texture not found: " + ref));
    String outPath = atlasPath(keyStr);
    Path dst = staging.resolve("assets/" + NS + "/textures/" + outPath + ".png");
    Files.createDirectories(dst.getParent());
    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
    Optional<Path> meta = find(rel + ".mcmeta");
    if (meta.isPresent()) {
      Files.copy(meta.get(), Path.of(dst + ".mcmeta"), StandardCopyOption.REPLACE_EXISTING);
    }
    String target = NS + ":" + outPath;
    backportedTextures.put(keyStr, target);
    return target;
  }

  /**
   * 1.19.3+ only stitch atlas source directories into the block/item atlas: anything outside
   * block/ or item/ (entity textures for mob models, chests...) is relocated under item/x/.
   */
  static String atlasPath(String path) {
    return path.startsWith("block/") || path.startsWith("item/") ? path : "item/x/" + path;
  }

  /** Copy of a texture multiplied by an RGB tint (bakes item tint sources legacy proxies can't apply). */
  String tintedTexture(String ref, int rgb) throws IOException {
    String base = backportTexture(ref);
    String keyStr = path(base) + "_tint_" + String.format(Locale.ROOT, "%06x", rgb & 0xFFFFFF);
    Path dst = staging.resolve("assets/" + NS + "/textures/" + keyStr + ".png");
    if (!Files.exists(dst)) {
      BufferedImage img = ImageIO.read(staging.resolve("assets/" + NS + "/textures/" + path(base) + ".png").toFile());
      ImageIO.write(Images.multiply(img, rgb), "png", dst.toFile());
    }
    return NS + ":" + keyStr;
  }

  /** GrassColor/FoliageColor.get: colormap pixel at ((1-t)*255, (1-d*t)*255). */
  int colormap(String name, double temp, double downfall) throws IOException {
    BufferedImage map = ImageIO.read(find("assets/minecraft/textures/colormap/" + name + ".png").orElseThrow().toFile());
    temp = Math.max(0, Math.min(1, temp));
    downfall = Math.max(0, Math.min(1, downfall)) * temp;
    return map.getRGB((int) ((1.0 - temp) * 255.0), (int) ((1.0 - downfall) * 255.0)) & 0xFFFFFF;
  }

  Path stagingRoot() {
    return staging;
  }

  Path stagingTexture(String ref) {
    return staging.resolve("assets/" + namespace(ref) + "/textures/" + path(ref) + ".png");
  }

  void writeModel(String ref, JsonObject model) throws IOException {
    Path dst = staging.resolve("assets/" + namespace(ref) + "/models/" + path(ref) + ".json");
    Files.createDirectories(dst.getParent());
    Files.writeString(dst, GSON.toJson(model), StandardCharsets.UTF_8);
  }

  void writeOverlayModel(String overlay, String ref, JsonObject model) throws IOException {
    Path dst = staging.resolve(overlay).resolve("assets/" + namespace(ref) + "/models/" + path(ref) + ".json");
    Files.createDirectories(dst.getParent());
    Files.writeString(dst, GSON.toJson(model), StandardCharsets.UTF_8);
  }

  JsonObject readStagedModel(String ref) throws IOException {
    Path src = staging.resolve("assets/" + namespace(ref) + "/models/" + path(ref) + ".json");
    try (Reader reader = Files.newBufferedReader(src, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  /** Resolves display transforms through the parent chain of a staged/backported model. */
  JsonObject resolveDisplay(String ref) throws IOException {
    String cur = ref;
    while (cur != null && !cur.contains("builtin/")) {
      JsonObject model = readStagedModel(cur);
      if (model.has("display")) {
        return model.getAsJsonObject("display");
      }
      cur = model.has("parent") ? model.get("parent").getAsString() : null;
    }
    return new JsonObject();
  }

  static void sanitizeElements(JsonObject model) {
    JsonArray elements = model.getAsJsonArray("elements");
    model.remove("groups");
    if (elements == null) {
      return;
    }
    for (JsonElement el : elements) {
      JsonObject elem = el.getAsJsonObject();
      elem.remove("light_emission");
      elem.remove("shade_direction_override");
      elem.remove("__comment");
      elem.remove("name");
      JsonObject faces = elem.getAsJsonObject("faces");
      if (faces != null) {
        for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
          JsonObject f = face.getValue().getAsJsonObject();
          if (f.has("texture")) {
            f.addProperty("texture", textureRef(f.get("texture").getAsString()));
          }
        }
      }
      JsonObject rot = elem.getAsJsonObject("rotation");
      if (rot == null) {
        continue;
      }
      if (!rot.has("axis")) {
        String axis = "y";
        double angle = 0;
        for (String ax : new String[] {"x", "y", "z"}) {
          if (rot.has(ax) && Math.abs(rot.get(ax).getAsDouble()) > Math.abs(angle)) {
            axis = ax;
            angle = rot.get(ax).getAsDouble();
          }
        }
        rot.remove("x");
        rot.remove("y");
        rot.remove("z");
        rot.addProperty("axis", axis);
        rot.addProperty("angle", angle);
      }
      rot.addProperty("angle", nearestLegacyAngle(rot.get("angle").getAsDouble()));
    }
  }

  /** Newer models may name a face texture without '#'; legacy parsers only resolve "#key" references. */
  static String textureRef(String tex) {
    return tex.startsWith("#") || tex.contains(":") || tex.contains("/") ? tex : "#" + tex;
  }

  static double nearestLegacyAngle(double angle) {
    double best = 0;
    for (double cand : LEGACY_ANGLES) {
      if (Math.abs(cand - angle) < Math.abs(best - angle)) {
        best = cand;
      }
    }
    return best;
  }
}
