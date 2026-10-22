package dev.bluesparksss.viavisuals.build;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Per-limb models for animated mobs. For every static entity appearance this also builds one item model per
 * rig part that owns cubes, in part-local space (axis aligned, pivot at the item centre), so the runtime can
 * pose each limb with a display transform from the rig data of tools/vanilla-models/DumpRig. Both model sets
 * live in the same pack; {@code animated-mobs} picks one at runtime.
 *
 * <p>Writes {@code anim-catalog.json} (appearance key to rig and bone models) and the rig / keyframe
 * resources the plugin loads ({@code anim/rigs/**}, {@code anim/animations/**}).
 */
final class AnimatedPackGenerator {

  /** Neutral items (all exist in 1.16) that carry bone models; legacy clients scan overrides linearly per render. */
  static final List<String> PROXIES = List.of(
      "STICK", "STRING", "FEATHER", "COAL", "CHARCOAL", "GOLD_NUGGET", "IRON_NUGGET", "GUNPOWDER", "BLAZE_POWDER",
      "GHAST_TEAR", "MAGMA_CREAM", "SPIDER_EYE", "SLIME_BALL", "LEATHER", "RABBIT_FOOT", "NAUTILUS_SHELL",
      "INK_SAC", "BONE", "FLINT_AND_STEEL", "SNOWBALL", "ENDER_PEARL", "FERMENTED_SPIDER_EYE", "GLISTERING_MELON_SLICE",
      "GOLDEN_CARROT", "REDSTONE", "LAPIS_LAZULI", "BONE_MEAL", "MELON_SLICE", "PUMPKIN_SEEDS", "WHEAT_SEEDS");
  private static final int SHARD = 450;
  private static final int CMD_TOP = 9000;
  static final String MODEL_DIR = "entity_bone";

  record Layer(String rigModel, String texVar, double inflate) {
  }

  private final EntityPackGenerator host;
  private final VanillaAssets assets;
  private final Path rigsDir;
  private final Path animationsDir;
  private final Consumer<String> logger;
  private final Map<String, JsonObject> rigs = new HashMap<>();
  private final Set<String> usedRigs = new TreeSet<>();
  private final Map<String, int[]> modelCache = new HashMap<>();
  private final JsonObject entities = new JsonObject();
  private int allocatedNormal;
  private int allocatedHurt;
  private int bones;
  private final Set<String> warned = new TreeSet<>();

  AnimatedPackGenerator(EntityPackGenerator host, VanillaAssets assets, Path rigsDir, Path animationsDir,
                        Consumer<String> logger) {
    this.host = host;
    this.assets = assets;
    this.rigsDir = rigsDir;
    this.animationsDir = animationsDir;
    this.logger = logger;
  }

  boolean available() {
    return Files.isDirectory(rigsDir);
  }

  private JsonObject rig(String model) throws IOException {
    JsonObject cached = rigs.get(model);
    if (cached != null) {
      return cached;
    }
    Path file = rigsDir.resolve(model + ".json");
    if (!Files.exists(file)) {
      if (warned.add(model)) {
        logger.accept("animated: no rig for " + model);
      }
      return null;
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      JsonObject doc = JsonParser.parseReader(reader).getAsJsonObject();
      rigs.put(model, doc);
      return doc;
    }
  }

  private static void collect(JsonObject node, String path, Map<String, JsonObject> out) {
    out.put(path, node);
    for (JsonElement kid : node.getAsJsonArray("children")) {
      JsonObject kidObj = kid.getAsJsonObject();
      collect(kidObj, path + "/" + kidObj.get("name").getAsString(), out);
    }
  }

  /** Cumulative rest scale of every node (the renderer's own scale rides on the root part), keyed like {@link #collect}. */
  private static void restScales(JsonObject node, String path, double parent, Map<String, Double> out) {
    JsonArray pose = node.getAsJsonArray("pose");
    double own = Math.cbrt(Math.abs(pose.get(6).getAsDouble() * pose.get(7).getAsDouble() * pose.get(8).getAsDouble()));
    double scale = parent * (own < 1e-6 ? 1.0 : own);
    out.put(path, scale);
    for (JsonElement kid : node.getAsJsonArray("children")) {
      JsonObject kidObj = kid.getAsJsonObject();
      restScales(kidObj, path + "/" + kidObj.get("name").getAsString(), scale, out);
    }
  }

  private static boolean samePose(JsonObject a, JsonObject b) {
    for (String field : new String[] {"rest", "pose"}) {
      JsonArray x = a.getAsJsonArray(field);
      JsonArray y = b.getAsJsonArray(field);
      for (int i = 0; i < x.size(); i++) {
        // overlay models sit a fraction of a pixel off their base part (bogged clothing legs: 0.1)
        double tol = i < 3 ? 0.15 : 0.01;
        if (Math.abs(x.get(i).getAsDouble() - y.get(i).getAsDouble()) > tol) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Builds the bone models of one appearance. {@code texMap} maps "0" (base) and layer variables to staged
   * texture refs, exactly as in the static model.
   */
  void addAppearance(String entityId, String key, String rigModel, List<Layer> layers, JsonObject texMap, double frameY)
      throws IOException {
    JsonObject rig = rig(rigModel);
    if (rig == null) {
      return;
    }
    Map<String, JsonObject> baseNodes = new LinkedHashMap<>();
    collect(rig.getAsJsonObject("root"), "", baseNodes);
    Map<String, Double> rests = new LinkedHashMap<>();
    restScales(rig.getAsJsonObject("root"), "", 1.0, rests);
    Map<String, List<VanillaGeometry.Cube>> cubes = new LinkedHashMap<>();
    Map<String, JsonArray> elementsByBone = new LinkedHashMap<>();
    for (Map.Entry<String, JsonObject> ent : baseNodes.entrySet()) {
      JsonArray raw = ent.getValue().getAsJsonArray("cubes");
      if (raw.isEmpty()) {
        continue;
      }
      JsonArray elements = new JsonArray();
      VanillaGeometry.appendElements(elements, VanillaGeometry.cubesOfNode(raw, ent.getKey()),
          VanillaGeometry.Space.BONE, "#0", 0, null);
      elementsByBone.put(ent.getKey(), elements);
    }
    for (Layer layer : layers) {
      JsonObject layerRig = rig(layer.rigModel());
      if (layerRig == null) {
        continue;
      }
      Map<String, JsonObject> layerNodes = new LinkedHashMap<>();
      collect(layerRig.getAsJsonObject("root"), "", layerNodes);
      for (Map.Entry<String, JsonObject> ent : layerNodes.entrySet()) {
        JsonArray raw = ent.getValue().getAsJsonArray("cubes");
        if (raw.isEmpty()) {
          continue;
        }
        JsonObject baseNode = baseNodes.get(ent.getKey());
        if (baseNode == null || !samePose(baseNode, ent.getValue())) {
          if (warned.add(layer.rigModel() + ent.getKey())) {
            logger.accept("animated: layer " + layer.rigModel() + " part " + ent.getKey()
                + " does not match the base rig, skipped");
          }
          continue;
        }
        JsonArray elements = elementsByBone.computeIfAbsent(ent.getKey(), k -> new JsonArray());
        VanillaGeometry.appendElements(elements, VanillaGeometry.cubesOfNode(raw, ent.getKey()),
            VanillaGeometry.Space.BONE, layer.texVar(), layer.inflate(), null);
      }
    }
    if (elementsByBone.isEmpty()) {
      return;
    }
    JsonObject hurtTex = host.hurtTextures(texMap);
    JsonObject boneOut = new JsonObject();
    for (Map.Entry<String, JsonArray> ent : elementsByBone.entrySet()) {
      JsonArray elements = ent.getValue();
      if (elements.isEmpty()) {
        continue;
      }
      VanillaGeometry.addBackFaces(elements, host.stagedTextures(texMap));
      double rest = rests.getOrDefault(ent.getKey(), 1.0);
      double fit = fitForStand(elements, rest);
      String sig = sha1((rigModel + "|" + ent.getKey() + "|" + elements + "|" + texMap).getBytes(StandardCharsets.UTF_8))
          .substring(0, 14);
      int[] ids = modelCache.get(sig);
      if (ids == null) {
        int[] normal = allocate(false);
        int[] hurt = allocate(true);
        String ref = VanillaAssets.NS + ":" + MODEL_DIR + "/" + sig;
        assets.writeModel(ref, model(elements, texMap, fit, rest));
        assets.writeModel(ref + "_h", model(elements, hurtTex, fit, rest));
        host.overrides().add(new EntityPackGenerator.Override(PROXIES.get(normal[0]), normal[1], ref, false));
        host.overrides().add(new EntityPackGenerator.Override(PROXIES.get(hurt[0]), hurt[1], ref + "_h", false));
        ids = new int[] {normal[0], normal[1], hurt[0], hurt[1], Float.floatToIntBits((float) fit)};
        modelCache.put(sig, ids);
        bones++;
      }
      JsonArray rec = new JsonArray();
      rec.add(ids[0]);
      rec.add(ids[1]);
      rec.add(ids[2]);
      rec.add(ids[3]);
      rec.add(Float.intBitsToFloat(ids[4]));
      boneOut.add(ent.getKey(), rec);
    }
    JsonObject app = new JsonObject();
    app.addProperty("rig", rigModel);
    if (Math.abs(frameY - 1.501) > 1e-6) {
      app.addProperty("frameY", frameY);
    }
    app.add("bones", boneOut);
    usedRigs.add(rigModel);
    for (Layer layer : layers) {
      usedRigs.add(layer.rigModel());
    }
    JsonObject entity = entities.has(entityId) ? entities.getAsJsonObject(entityId) : new JsonObject();
    entities.add(entityId, entity);
    entity.add(key, app);
  }

  /**
   * {proxy index, custom model data}: highest CMD first within a shard (legacy clients test from the last
   * override). Hurt twins fill shards from the end of {@link #PROXIES} so they never slow down normal lookups.
   */
  private int[] allocate(boolean hurt) {
    int idx = hurt ? allocatedHurt++ : allocatedNormal++;
    int shard = idx / SHARD;
    int proxy = hurt ? PROXIES.size() - 1 - shard : shard;
    if (proxy < 0 || proxy >= PROXIES.size() || usedShards() > PROXIES.size()) {
      throw new IllegalStateException("animated bone models exceed " + PROXIES.size() * SHARD + " entries");
    }
    return new int[] {proxy, CMD_TOP - (idx % SHARD)};
  }

  private static final String TINT_PROXY = "LEATHER_HORSE_ARMOR";
  private static final int TINT_CMD_BASE = 600000;
  private int tintBones;

  /**
   * Overlay limbs for a tinted part (collar, dyed wool): the base rig's cubes with the overlay texture, kept only
   * where the texture has pixels, on a dyed leather horse armor item. Catalog key = the static part key.
   */
  void addTintedPart(String entityId, String partKey, String rigModel, String baseRigModel, String texRef, double inflate)
      throws IOException {
    JsonObject rig = rig(rigModel);
    JsonObject baseRig = rig(baseRigModel);
    if (rig == null || baseRig == null) {
      return;
    }
    Map<String, JsonObject> baseNodes = new LinkedHashMap<>();
    collect(baseRig.getAsJsonObject("root"), "", baseNodes);
    Map<String, Double> rests = new LinkedHashMap<>();
    restScales(baseRig.getAsJsonObject("root"), "", 1.0, rests);
    JsonObject texMap = new JsonObject();
    texMap.addProperty("0", texRef);
    Map<String, JsonObject> nodes = new LinkedHashMap<>();
    collect(rig.getAsJsonObject("root"), "", nodes);
    JsonObject hurtTex = host.hurtTextures(texMap);
    JsonObject boneOut = new JsonObject();
    for (Map.Entry<String, JsonObject> ent : nodes.entrySet()) {
      JsonArray raw = ent.getValue().getAsJsonArray("cubes");
      JsonObject baseNode = baseNodes.get(ent.getKey());
      if (raw.isEmpty() || baseNode == null || baseNode.getAsJsonArray("cubes").isEmpty()
          || !samePose(baseNode, ent.getValue())) {
        continue;
      }
      JsonArray elements = new JsonArray();
      VanillaGeometry.appendElements(elements, VanillaGeometry.cubesOfNode(raw, ent.getKey()),
          VanillaGeometry.Space.BONE, "#0", inflate, null);
      VanillaGeometry.dropTransparent(elements, host.stagedTextures(texMap));
      if (elements.isEmpty()) {
        continue;
      }
      for (JsonElement el : elements) {
        for (Map.Entry<String, JsonElement> face : el.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
          face.getValue().getAsJsonObject().addProperty("tintindex", 0);
        }
      }
      double rest = rests.getOrDefault(ent.getKey(), 1.0);
      double fit = fitForStand(elements, rest);
      int cmd = TINT_CMD_BASE + tintBones++;
      String sig = sha1((rigModel + "|" + ent.getKey() + "|" + partKey + "|" + elements).getBytes(StandardCharsets.UTF_8))
          .substring(0, 14);
      String ref = VanillaAssets.NS + ":" + MODEL_DIR + "/t_" + sig;
      assets.writeModel(ref, model(elements, texMap, fit, rest));
      assets.writeModel(ref + "_h", model(elements, hurtTex, fit, rest));
      host.overrides().add(new EntityPackGenerator.Override(TINT_PROXY, cmd, ref, true));
      host.overrides().add(new EntityPackGenerator.Override(TINT_PROXY, cmd + EntityPackGenerator.HURT_OFFSET, ref + "_h", true));
      JsonArray rec = new JsonArray();
      rec.add(PROXIES.size());
      rec.add(cmd);
      rec.add(PROXIES.size());
      rec.add(cmd + EntityPackGenerator.HURT_OFFSET);
      rec.add((float) fit);
      boneOut.add(ent.getKey(), rec);
    }
    if (boneOut.isEmpty()) {
      return;
    }
    JsonObject app = new JsonObject();
    app.addProperty("rig", baseRigModel);
    app.addProperty("tint", true);
    app.add("bones", boneOut);
    usedRigs.add(baseRigModel);
    JsonObject entity = entities.has(entityId) ? entities.getAsJsonObject(entityId) : new JsonObject();
    entities.add(entityId, entity);
    entity.add(partKey, app);
  }

  private int usedShards() {
    return (allocatedNormal + SHARD - 1) / SHARD + (allocatedHurt + SHARD - 1) / SHARD;
  }

  /**
   * Fits the limb into the legacy bounds and returns the total compression (below 1) or enlargement (above 1). A helmet
   * model can be scaled by at most 4, so a limb whose renderer scale is large (happy ghast 4) is drawn bigger in
   * the model instead.
   */
  private static double fitForStand(JsonArray elements, double rest) {
    double fit = VanillaGeometry.fitAroundCenter(elements);
    double needed = 0.4 * rest;
    if (fit < needed) {
      fit *= VanillaGeometry.growAroundCenter(elements, needed / fit);
    }
    return fit;
  }

  private static JsonObject model(JsonArray elements, JsonObject texMap, double fit, double rest) {
    JsonObject model = new JsonObject();
    JsonObject textures = texMap.deepCopy();
    textures.addProperty("particle", texMap.get("0").getAsString());
    model.add("textures", textures);
    model.add("elements", elements.deepCopy());
    JsonObject display = new JsonObject();
    JsonObject fixed = new JsonObject();
    fixed.add("rotation", VanillaGeometry.vec(0, 180, 0));
    fixed.add("translation", VanillaGeometry.vec(0, 0, 0));
    fixed.add("scale", VanillaGeometry.vec(1, 1, 1));
    display.add("fixed", fixed);
    // armor-stand helmet (clients without displays): the limb's pivot, the item centre, sits on the stand's neck
    // (CustomHeadLayer raises the item 0.25 blocks at scale 0.625, hence -6.4 units); 1.6 makes the scale 1.0, and
    // the part's rest scale (the renderer's own scale) rides on it because a stand cannot take the solved scale
    double headScale = Math.min(4.0, 1.6 * rest / fit);
    JsonObject head = new JsonObject();
    head.add("rotation", VanillaGeometry.vec(0, 180, 0));
    head.add("translation", VanillaGeometry.vec(0, -6.4, 0));
    head.add("scale", VanillaGeometry.vec(headScale, headScale, headScale));
    display.add("head", head);
    model.add("display", display);
    return model;
  }

  /** Writes anim-catalog.json plus the stripped rigs and keyframe animations under {@code outDir}. */
  void write(Path outDir) throws IOException {
    verifyBones();
    JsonObject catalog = new JsonObject();
    JsonArray proxies = new JsonArray();
    PROXIES.forEach(proxies::add);
    proxies.add(TINT_PROXY);
    catalog.add("proxies", proxies);
    catalog.add("entities", entities);
    Files.createDirectories(outDir);
    Files.writeString(outDir.resolve("anim-catalog.json"),
        new GsonBuilder().disableHtmlEscaping().create().toJson(catalog), StandardCharsets.UTF_8);
    Path rigOut = outDir.resolve("anim/rigs");
    for (String model : usedRigs) {
      JsonObject doc = rig(model);
      if (doc == null) {
        continue;
      }
      JsonObject copy = doc.deepCopy();
      copy.remove("golden");
      dropKeyframeWalk(copy);
      stripCubes(copy.getAsJsonObject("root"));
      Path dst = rigOut.resolve(model + ".json");
      Files.createDirectories(dst.getParent());
      Files.writeString(dst, copy.toString(), StandardCharsets.UTF_8);
    }
    if (Files.isDirectory(animationsDir)) {
      Path animOut = outDir.resolve("anim/animations");
      Files.createDirectories(animOut);
      try (var stream = Files.list(animationsDir)) {
        for (Path file : stream.filter(f -> f.toString().endsWith(".json")).toList()) {
          Files.copy(file, animOut.resolve(file.getFileName().toString()),
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    logger.accept("Generated " + bones + " animated bone model(s) across " + usedShards() + " proxy shard(s) for "
        + entities.size() + " entity type(s), " + usedRigs.size() + " rig(s)");
  }

  /** Every limb in the catalog must be a part of its rig, or the runtime would silently skip it. */
  private void verifyBones() throws IOException {
    for (Map.Entry<String, JsonElement> ent : entities.entrySet()) {
      for (Map.Entry<String, JsonElement> app : ent.getValue().getAsJsonObject().entrySet()) {
        JsonObject ao = app.getValue().getAsJsonObject();
        JsonObject rigDoc = rig(ao.get("rig").getAsString());
        Map<String, JsonObject> nodes = new LinkedHashMap<>();
        collect(rigDoc.getAsJsonObject("root"), "", nodes);
        for (String path : ao.getAsJsonObject("bones").keySet()) {
          if (!nodes.containsKey(path)) {
            throw new IOException("animated " + ent.getKey() + " / " + app.getKey() + ": no part " + path + " in rig "
                + ao.get("rig").getAsString());
          }
        }
      }
    }
  }

  /** Model fields whose keyframe walk replaces the sampled sinusoid walk (the driver plays them at runtime). */
  private static final Set<String> KEYFRAME_WALK = Set.of("walkAnimation", "swimAnimation", "walkWithItemAnimation",
      "sniffSearchAnimation", "babyWalkAnimation");

  private static void dropKeyframeWalk(JsonObject rig) {
    JsonObject kf = rig.has("keyframes") && rig.get("keyframes").isJsonObject() ? rig.getAsJsonObject("keyframes") : null;
    if (kf == null || KEYFRAME_WALK.stream().noneMatch(kf::has)) {
      return;
    }
    removeWalk(rig);
    if (rig.has("flags") && rig.get("flags").isJsonObject()) {
      for (JsonElement fe : rig.getAsJsonObject("flags").asMap().values()) {
        for (JsonElement ve : fe.getAsJsonObject().getAsJsonObject("values").asMap().values()) {
          JsonObject mode = ve.getAsJsonObject().has("mode") ? ve.getAsJsonObject().getAsJsonObject("mode") : null;
          if (mode != null) {
            removeWalk(mode);
          }
        }
      }
    }
  }

  private static void removeWalk(JsonObject o) {
    o.remove("walk");
    o.remove("walkLevels");
  }

  private static void stripCubes(JsonObject node) {
    node.add("cubes", new JsonArray());
    for (JsonElement kid : node.getAsJsonArray("children")) {
      stripCubes(kid.getAsJsonObject());
    }
  }

  private static String sha1(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** Proxy material names, for the runtime catalog consumer. */
  static List<String> proxies() {
    return new ArrayList<>(PROXIES);
  }
}
