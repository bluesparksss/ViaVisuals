package dev.bluesparksss.viavisuals.build;

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
import java.util.function.Consumer;

/**
 * Item proxies from custom-items.json: backports each vanilla item model (resolving 1.21.4+ item
 * definitions to a static legacy model), bakes tint sources, builds geometry for special renderers,
 * and registers CustomModelData overrides on the item's proxy.
 */
final class ItemPackGenerator {

  private final VanillaAssets assets;
  private final Path geometryDir;
  private final Path legacyAssets;
  private final ProxyOverrides overrides;
  private final Consumer<String> logger;

  ItemPackGenerator(VanillaAssets assets, Path geometryDir, Path legacyAssets, ProxyOverrides overrides, Consumer<String> logger) {
    this.assets = assets;
    this.geometryDir = geometryDir;
    this.legacyAssets = legacyAssets;
    this.overrides = overrides;
    this.logger = logger;
  }

  void generate(Path customItemsFile) throws IOException {
    JsonObject root;
    try (Reader reader = Files.newBufferedReader(customItemsFile, StandardCharsets.UTF_8)) {
      root = JsonParser.parseReader(reader).getAsJsonObject();
    }
    int count = 0;
    for (JsonElement el : root.getAsJsonArray("items")) {
      JsonObject item = el.getAsJsonObject();
      try {
        generateItem(item);
        count++;
      } catch (IOException ex) {
        logger.accept("Warning: item " + item.get("id").getAsString() + " skipped: " + ex.getMessage());
      }
    }
    logger.accept("Generated proxy models for " + count + " item(s)");
    generateTrimIcons(customItemsFile.resolveSibling("armor-trim-icons.json"));
  }

  /** Icons of trimmed armor on its leather proxy (armor-trim-icons.json, models written by generate_armor_trims.py). */
  private void generateTrimIcons(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    JsonObject root;
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      root = JsonParser.parseReader(reader).getAsJsonObject();
    }
    int count = 0;
    for (JsonElement el : root.getAsJsonArray("icons")) {
      JsonObject icon = el.getAsJsonObject();
      overrides.add(icon.get("proxy").getAsString(), icon.get("cmd").getAsInt(), icon.get("model").getAsString(), null, null, true);
      count++;
    }
    logger.accept("Registered " + count + " armor trim icon override(s)");
  }

  private void generateItem(JsonObject item) throws IOException {
    String id = item.get("id").getAsString();
    String proxy = item.get("proxy").getAsString();
    int cmd = item.get("cmd").getAsInt();
    // 1.21.4+ clients only read the modern overlay; items they already know never need it there.
    boolean legacyOnly = !EntityPackGenerator.isAfter(item.get("since").getAsString(), "1.21.4");
    JsonObject def = assets.itemDefinition(id).orElseThrow(() -> new IOException("no item definition"))
        .getAsJsonObject("model");

    if (item.has("spear")) {
      generateSpear(id, proxy, cmd, item.getAsJsonObject("spear"), def, legacyOnly);
      return;
    }
    if ("recovery_compass".equals(id)) {
      generateCompassLike(id, proxy, cmd, def);
      return;
    }

    String model = legacyModel(id, def);
    if (item.has("tint")) {
      model = untintedLayerModel(id, model);
    }
    overrides.add(proxy, cmd, model, null, null, legacyOnly);
    if (item.has("retexture") && item.get("retexture").getAsBoolean()) {
      // the one deliberate vanilla shadow: 1.21.5's per-mob spawn egg sprite for the egg the client already has;
      // PackManager drops these files when items.new-spawn-egg-textures is false
      JsonObject shadow = new JsonObject();
      shadow.addProperty("parent", model);
      assets.writeModel("minecraft:item/" + id, shadow);
    }
  }

  /** Static model for legacy overrides, following the item definition's default branch. */
  private String legacyModel(String id, JsonObject node) throws IOException {
    String type = node.get("type").getAsString().replace("minecraft:", "");
    switch (type) {
      case "model": {
        String ref = assets.backportModel(node.get("model").getAsString());
        if (node.has("tints")) {
          ref = bakeTints(id, ref, node.getAsJsonArray("tints"));
        }
        return ref;
      }
      case "select": {
        if (node.has("block_state_property")) {
          // block displays need every block-state variant (statue poses), not just the item's default
          for (JsonElement c : node.getAsJsonArray("cases")) {
            JsonObject cs = c.getAsJsonObject();
            JsonObject model = cs.getAsJsonObject("model");
            if (model.get("type").getAsString().endsWith("special")) {
              specialModel(id + "_" + cs.get("when").getAsString(), model);
            }
          }
          JsonObject fb = node.getAsJsonObject("fallback");
          if (fb != null && fb.get("type").getAsString().endsWith("special") && fb.getAsJsonObject("model").has("pose")) {
            specialModel(id + "_" + fb.getAsJsonObject("model").get("pose").getAsString(), fb);
          }
        }
        for (JsonElement c : node.getAsJsonArray("cases")) {
          JsonObject cs = c.getAsJsonObject();
          if (whenContains(cs.get("when"), "gui")) {
            return legacyModel(id, cs.getAsJsonObject("model"));
          }
        }
        if (node.has("fallback")) {
          return legacyModel(id, node.getAsJsonObject("fallback"));
        }
        return legacyModel(id, node.getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("model"));
      }
      case "condition":
        return legacyModel(id, node.getAsJsonObject("on_false"));
      case "range_dispatch":
        if (node.has("fallback")) {
          return legacyModel(id, node.getAsJsonObject("fallback"));
        }
        return legacyModel(id, node.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("model"));
      case "composite":
        return legacyModel(id, node.getAsJsonArray("models").get(0).getAsJsonObject());
      case "special":
        return specialModel(id, node);
      default:
        throw new IOException("unsupported item model type " + type);
    }
  }

  private static boolean whenContains(JsonElement when, String val) {
    if (when.isJsonArray()) {
      for (JsonElement e : when.getAsJsonArray()) {
        if (val.equals(e.getAsString())) {
          return true;
        }
      }
      return false;
    }
    return val.equals(when.getAsString());
  }

  /** Bakes item tint sources into texture copies; legacy PAPER proxies have no item colour handler. */
  private String bakeTints(String id, String ref, JsonArray tints) throws IOException {
    JsonObject textures = new JsonObject();
    boolean generated = isGeneratedChain(ref);
    String cur = ref;
    JsonObject merged = new JsonObject();
    while (cur != null && !cur.contains("builtin/")) {
      JsonObject m = assets.readStagedModel(cur);
      if (m.has("textures")) {
        for (var ent : m.getAsJsonObject("textures").entrySet()) {
          if (!merged.has(ent.getKey())) {
            merged.add(ent.getKey(), ent.getValue());
          }
        }
      }
      cur = m.has("parent") ? m.get("parent").getAsString() : null;
    }
    for (int i = 0; i < tints.size(); i++) {
      int rgb = tintColor(tints.get(i).getAsJsonObject());
      if (generated) {
        String layer = "layer" + i;
        if (merged.has(layer)) {
          textures.addProperty(layer, assets.tintedTexture(resolveVar(merged, layer), rgb));
        }
      } else {
        for (String var : tintedVars(ref, i)) {
          if (merged.has(var)) {
            textures.addProperty(var, assets.tintedTexture(resolveVar(merged, var), rgb));
          }
        }
      }
    }
    JsonObject out = new JsonObject();
    out.addProperty("parent", ref);
    out.add("textures", textures);
    if (!generated) {
      // tintindex faces stay tinted by the proxy's colour handler (white for PAPER), so strip it
      JsonArray elements = elementsOf(ref);
      if (elements != null) {
        JsonArray copy = elements.deepCopy();
        for (JsonElement el : copy) {
          for (var face : el.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
            face.getValue().getAsJsonObject().remove("tintindex");
          }
        }
        out.add("elements", copy);
      }
    }
    String target = VanillaAssets.NS + ":item/tinted/" + id;
    assets.writeModel(target, out);
    return target;
  }

  private boolean isGeneratedChain(String ref) throws IOException {
    String cur = ref;
    while (cur != null) {
      if (cur.endsWith("builtin/generated")) {
        return true;
      }
      if (cur.contains("builtin/")) {
        return false;
      }
      JsonObject m = assets.readStagedModel(cur);
      if (m.has("elements")) {
        return false;
      }
      cur = m.has("parent") ? m.get("parent").getAsString() : null;
    }
    return false;
  }

  private JsonArray elementsOf(String ref) throws IOException {
    String cur = ref;
    while (cur != null && !cur.contains("builtin/")) {
      JsonObject m = assets.readStagedModel(cur);
      if (m.has("elements")) {
        return m.getAsJsonArray("elements");
      }
      cur = m.has("parent") ? m.get("parent").getAsString() : null;
    }
    return null;
  }

  private java.util.Set<String> tintedVars(String ref, int tintIndex) throws IOException {
    java.util.Set<String> vars = new java.util.HashSet<>();
    JsonArray elements = elementsOf(ref);
    if (elements == null) {
      return vars;
    }
    for (JsonElement el : elements) {
      for (var face : el.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
        JsonObject f = face.getValue().getAsJsonObject();
        if (f.has("tintindex") && f.get("tintindex").getAsInt() == tintIndex) {
          vars.add(f.get("texture").getAsString().substring(1));
        }
      }
    }
    return vars;
  }

  private static String resolveVar(JsonObject textures, String var) {
    String val = textures.get(var).getAsString();
    int guard = 0;
    while (val.startsWith("#") && guard++ < 10) {
      val = textures.get(val.substring(1)).getAsString();
    }
    return val;
  }

  private int tintColor(JsonObject tint) throws IOException {
    String type = tint.get("type").getAsString().replace("minecraft:", "");
    switch (type) {
      case "constant":
        return tint.get("value").getAsInt() & 0xFFFFFF;
      case "grass":
        return colormap("grass", tint.get("temperature").getAsDouble(), tint.get("downfall").getAsDouble());
      case "foliage":
        return colormap("foliage", 0.5, 1.0);
      case "dye":
        return tint.has("default") ? tint.get("default").getAsInt() & 0xFFFFFF : 0xFFFFFF;
      default:
        return tint.has("default") ? tint.get("default").getAsInt() & 0xFFFFFF : 0xFFFFFF;
    }
  }

  private int colormap(String name, double temp, double downfall) throws IOException {
    return assets.colormap(name, temp, downfall);
  }

  /**
   * Leather proxies tint layer0 with the armor colour; move the icon to layer1 (untinted) so the
   * inventory sprite keeps its own colours while the worn armor still shows the dye.
   */
  private String untintedLayerModel(String id, String model) throws IOException {
    JsonObject src = assets.readStagedModel(model);
    JsonObject tex = src.has("textures") ? src.getAsJsonObject("textures") : new JsonObject();
    if (!tex.has("layer0")) {
      return model;
    }
    String empty = emptyTexture();
    JsonObject out = new JsonObject();
    out.addProperty("parent", assets.backportModel("minecraft:item/generated"));
    JsonObject textures = new JsonObject();
    textures.addProperty("layer0", empty);
    textures.addProperty("layer1", tex.get("layer0").getAsString());
    out.add("textures", textures);
    String target = VanillaAssets.NS + ":item/untinted/" + id;
    assets.writeModel(target, out);
    return target;
  }

  private String emptyTexture() throws IOException {
    String ref = VanillaAssets.NS + ":item/empty";
    Path dst = assets.stagingTexture(ref);
    if (!Files.exists(dst)) {
      Files.createDirectories(dst.getParent());
      ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", dst.toFile());
    }
    return ref;
  }

  private void generateSpear(String id, String proxy, int cmd, JsonObject spear, JsonObject def, boolean legacyOnly)
      throws IOException {
    String icon = assets.backportModel("minecraft:item/" + id);
    String inHand = assets.backportModel("minecraft:item/" + id + "_in_hand");
    JsonObject modernBase = new JsonObject();
    modernBase.addProperty("type", "minecraft:select");
    modernBase.addProperty("property", "minecraft:display_context");
    JsonArray cases = new JsonArray();
    JsonObject guiCase = new JsonObject();
    JsonArray when = new JsonArray();
    for (String ctx : new String[] {"gui", "ground", "fixed"}) {
      when.add(ctx);
    }
    guiCase.add("when", when);
    guiCase.add("model", ProxyOverrides.modelNode(icon));
    cases.add(guiCase);
    modernBase.add("cases", cases);
    modernBase.add("fallback", ProxyOverrides.modelNode(inHand));
    overrides.add(proxy, cmd, icon, null, modernBase, legacyOnly);
    overrides.add(proxy, spear.get("inHandCmd").getAsInt(), inHand, null, null, legacyOnly);

    int frameBase = spear.get("frameCmdBase").getAsInt();
    JsonArray frames = spear.getAsJsonArray("frames");
    for (int i = 0; i < frames.size(); i++) {
      JsonObject frameModel = new JsonObject();
      frameModel.addProperty("parent", inHand);
      frameModel.add("display", frames.get(i).getAsJsonObject());
      String ref = VanillaAssets.NS + ":item/spear_use/" + id + "_" + i;
      assets.writeModel(ref, frameModel);
      overrides.add(proxy, frameBase + i, ref, null, null, legacyOnly);
    }
  }

  /** COMPASS proxy: one override per legacy compass angle bucket so the needle keeps working. */
  private void generateCompassLike(String id, String proxy, int cmd, JsonObject def) throws IOException {
    JsonObject legacyCompass;
    try (Reader reader = Files.newBufferedReader(legacyAssets.resolve("minecraft/models/item/compass.json"), StandardCharsets.UTF_8)) {
      legacyCompass = JsonParser.parseReader(reader).getAsJsonObject();
    }
    for (JsonElement el : legacyCompass.getAsJsonArray("overrides")) {
      JsonObject ov = el.getAsJsonObject();
      String vanillaModel = ov.get("model").getAsString();
      String frame = vanillaModel.endsWith("compass") ? "16" : vanillaModel.substring(vanillaModel.lastIndexOf('_') + 1);
      String ref = assets.backportModel("minecraft:item/" + id + "_" + frame);
      overrides.add(proxy, cmd, ref, ov.getAsJsonObject("predicate").deepCopy(), null, true);
    }
  }

  /** Geometry for item definitions drawn by special renderers (chests, decorated pot, heads). */
  private String specialModel(String id, JsonObject node) throws IOException {
    JsonObject special = node.getAsJsonObject("model");
    String kind = special.get("type").getAsString().replace("minecraft:", "");
    JsonArray elements = new JsonArray();
    JsonObject textures = new JsonObject();
    switch (kind) {
      case "chest": {
        String tex = VanillaAssets.path(special.get("texture").getAsString());
        appendGeometry(elements, "chest#main", VanillaGeometry.Space.BLOCK_ENTITY, "#0");
        textures.addProperty("0", assets.backportTexture("minecraft:entity/chest/" + tex));
        if (!id.endsWith("_left") && !id.endsWith("_right")) {
          // halves of a double chest, for block displays (type=left/right)
          for (String half : new String[] {"left", "right"}) {
            if (assets.find("assets/minecraft/textures/entity/chest/" + tex + "_" + half + ".png").isPresent()) {
              JsonObject sub = node.deepCopy();
              sub.getAsJsonObject("model").addProperty("texture", "minecraft:" + tex + "_" + half);
              doubleChestHalf(id + "_" + half, sub, half);
            }
          }
        }
        break;
      }
      case "decorated_pot":
        appendGeometry(elements, "decorated_pot_base#main", VanillaGeometry.Space.BLOCK_ENTITY, "#0");
        appendGeometry(elements, "decorated_pot_sides#main", VanillaGeometry.Space.BLOCK_ENTITY, "#1");
        textures.addProperty("0", assets.backportTexture("minecraft:entity/decorated_pot/decorated_pot_base"));
        textures.addProperty("1", assets.backportTexture("minecraft:entity/decorated_pot/decorated_pot_side"));
        break;
      case "head": {
        String headKind = special.get("kind").getAsString();
        appendGeometry(elements, headKind + "_head#main", VanillaGeometry.Space.SKULL, "#0");
        textures.addProperty("0", assets.backportTexture("minecraft:entity/" + headTexture(headKind)));
        break;
      }
      case "copper_golem_statue": {
        String pose = special.get("pose").getAsString();
        appendGeometry(elements, "standing".equals(pose) ? "copper_golem#main" : "copper_golem_" + pose + "#main",
            VanillaGeometry.Space.ENTITY, "#0");
        if (!"standing".equals(pose)) {
          // CopperGolemStatueModel zeroes root.y; only the standing (entity) layer has its root at y 24,
          // the pose layers are built around 0, so entity space would lift them 24px off the block
          for (JsonElement el : elements) {
            JsonObject elem = el.getAsJsonObject();
            lower(elem.getAsJsonArray("from"));
            lower(elem.getAsJsonArray("to"));
            if (elem.has("rotation")) {
              lower(elem.getAsJsonObject("rotation").getAsJsonArray("origin"));
            }
          }
        }
        String tex = VanillaAssets.path(special.get("texture").getAsString())
            .replaceFirst("^textures/", "").replaceFirst("[.]png$", "");
        textures.addProperty("0", assets.backportTexture("minecraft:" + tex));
        break;
      }
      default:
        throw new IOException("unsupported special model " + kind);
    }
    textures.addProperty("particle", textures.get("0").getAsString());
    JsonObject out = new JsonObject();
    String parent = assets.backportModel(node.get("base").getAsString());
    out.addProperty("parent", parent);
    out.add("textures", textures);
    out.add("elements", elements);
    fitSpecial(out, parent);
    String target = VanillaAssets.NS + ":item/special/" + id;
    assets.writeModel(target, out);
    if ("head".equals(kind) && id.endsWith("_head")) {
      // SkullBlockRenderer: a wall head sits 4px up and 4px toward the wall (north at display yaw 0)
      JsonObject wall = out.deepCopy();
      for (JsonElement el : wall.getAsJsonArray("elements")) {
        JsonObject elem = el.getAsJsonObject();
        shift(elem.getAsJsonArray("from"));
        shift(elem.getAsJsonArray("to"));
        if (elem.has("rotation")) {
          shift(elem.getAsJsonObject("rotation").getAsJsonArray("origin"));
        }
      }
      assets.writeModel(VanillaAssets.NS + ":item/special/" + id.replace("_head", "_wall_head"), wall);
    }
    return target;
  }

  private static void lower(JsonArray vec) {
    vec.set(1, new com.google.gson.JsonPrimitive(vec.get(1).getAsDouble() - 24));
  }

  private static void shift(JsonArray vec) {
    vec.set(1, new com.google.gson.JsonPrimitive(vec.get(1).getAsDouble() + 4));
    vec.set(2, new com.google.gson.JsonPrimitive(vec.get(2).getAsDouble() - 4));
  }

  private void doubleChestHalf(String id, JsonObject node, String half) throws IOException {
    JsonArray elements = new JsonArray();
    appendGeometry(elements, "double_chest_" + half + "#main", VanillaGeometry.Space.BLOCK_ENTITY, "#0");
    JsonObject textures = new JsonObject();
    String tex = VanillaAssets.path(node.getAsJsonObject("model").get("texture").getAsString());
    textures.addProperty("0", assets.backportTexture("minecraft:entity/chest/" + tex));
    textures.addProperty("particle", textures.get("0").getAsString());
    JsonObject out = new JsonObject();
    String parent = assets.backportModel(node.get("base").getAsString());
    out.addProperty("parent", parent);
    out.add("textures", textures);
    out.add("elements", elements);
    fitSpecial(out, parent);
    assets.writeModel(VanillaAssets.NS + ":item/special/" + id, out);
  }

  /** Keeps special models inside the legacy element bounds (statue poses reach y=36). */
  private void fitSpecial(JsonObject model, String parent) throws IOException {
    double scale = VanillaGeometry.fitAroundCenter(model.getAsJsonArray("elements"));
    if (scale < 1.0) {
      model.addProperty(BlockPackGenerator.FIT_KEY, scale);
      model.add("display", VanillaGeometry.scaleDisplay(assets.resolveDisplay(parent), 1.0 / scale));
    }
  }

  private static String headTexture(String kind) {
    return switch (kind) {
      case "piglin" -> "piglin/piglin";
      default -> kind + "/" + kind;
    };
  }

  private void appendGeometry(JsonArray elements, String dump, VanillaGeometry.Space space, String texVar) throws IOException {
    VanillaGeometry.appendElements(elements, VanillaGeometry.load(geometryDir.resolve("minecraft").resolve(dump + ".json")),
        space, texVar, 0, null);
  }
}
