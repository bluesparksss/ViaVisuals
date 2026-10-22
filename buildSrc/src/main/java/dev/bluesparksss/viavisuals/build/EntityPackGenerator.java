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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Builds entity proxy models from resource-pack-src/entity-specs.json + vanilla posed geometry and
 * writes the runtime catalog (appearance key -> CMD, since) as src resources' generated entity-catalog.json.
 *
 * <p>Appearance keys: {@code entity;adult|baby;dim=value...;pose=name}. Tinted overlay parts:
 * {@code entity;age;#part;pose=name}, rendered on a dyed LEATHER_HORSE_ARMOR proxy.
 */
final class EntityPackGenerator {

  static final int CMD_BASE = 100000;
  static final int HURT_OFFSET = 400000;
  static final String TINT_PROXY = "LEATHER_HORSE_ARMOR";
  static final String BASE_PROXY = "PAPER";

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final String FIRST_VERSION = "1.16";

  record Override(String proxy, int cmd, String model, boolean tinted) {
  }

  private final VanillaAssets assets;
  private final Path posedDir;
  private final Map<String, String> textureHistory;
  private final Set<String> postClientEntities = new HashSet<>();
  private final Consumer<String> logger;
  private final List<Override> overrides = new ArrayList<>();
  private int nextCmd = CMD_BASE;
  private AnimatedPackGenerator animated;

  EntityPackGenerator(VanillaAssets assets, Path posedDir, Path historyFile, Path registrySinceFile, Consumer<String> logger)
      throws IOException {
    this.assets = assets;
    this.posedDir = posedDir;
    this.logger = logger;
    this.textureHistory = new TreeMap<>();
    JsonObject hist = readJson(historyFile);
    for (Map.Entry<String, JsonElement> ent : hist.entrySet()) {
      textureHistory.put(ent.getKey(), ent.getValue().getAsString());
    }
    postClientEntities.addAll(readJson(registrySinceFile).getAsJsonObject("entities").keySet());
  }

  List<Override> overrides() {
    return overrides;
  }

  void enableAnimated(AnimatedPackGenerator generator) {
    this.animated = generator;
  }

  JsonObject generate(Path specsFile) throws IOException {
    JsonObject specs = readJson(specsFile);
    JsonObject catalog = new JsonObject();
    catalog.addProperty("hurtOffset", HURT_OFFSET);
    JsonObject entities = new JsonObject();
    int models = 0;
    for (Map.Entry<String, JsonElement> ent : specs.entrySet()) {
      if (ent.getKey().startsWith("_")) {
        continue;
      }
      JsonObject spec = expandShorthand(ent.getKey(), ent.getValue().getAsJsonObject());
      JsonObject out = generateEntity(ent.getKey(), spec);
      if (out != null) {
        entities.add(ent.getKey(), out);
        models += out.getAsJsonObject("appearances").size() + out.getAsJsonObject("parts").size();
      }
    }
    catalog.add("entities", entities);
    logger.accept("Generated " + models + " entity proxy appearance(s) for " + entities.size() + " entity type(s)");
    return catalog;
  }

  private JsonObject expandShorthand(String id, JsonObject spec) {
    if (!spec.has("boat")) {
      return spec;
    }
    String wood = spec.get("boat").getAsString();
    boolean chest = spec.has("chest") && spec.get("chest").getAsBoolean();
    JsonObject adult = new JsonObject();
    adult.addProperty("model", id + "/model");
    adult.addProperty("tex", "entity/" + (chest ? "chest_boat" : "boat") + "/" + wood);
    JsonObject out = new JsonObject();
    out.add("adult", adult);
    out.addProperty("vehicle", true);
    return out;
  }

  private JsonObject generateEntity(String id, JsonObject spec) throws IOException {
    LinkedHashMap<String, Map<String, String>> dims = new LinkedHashMap<>();
    if (spec.has("dims")) {
      for (Map.Entry<String, JsonElement> dim : spec.getAsJsonObject("dims").entrySet()) {
        Map<String, String> values = new LinkedHashMap<>();
        if (dim.getValue().isJsonArray()) {
          for (JsonElement v : dim.getValue().getAsJsonArray()) {
            values.put(v.getAsString(), v.getAsString());
          }
        } else {
          for (Map.Entry<String, JsonElement> v : dim.getValue().getAsJsonObject().entrySet()) {
            values.put(v.getKey(), v.getValue().getAsString());
          }
        }
        dims.put(dim.getKey(), values);
      }
    }
    List<String> poses = new ArrayList<>();
    poses.add("");
    if (spec.has("poses")) {
      for (JsonElement p : spec.getAsJsonArray("poses")) {
        poses.add(p.getAsString());
      }
    }

    JsonObject appearances = new JsonObject();
    JsonObject parts = new JsonObject();
    List<Map<String, String>> combos = combos(dims);
    for (String age : new String[] {"adult", "baby"}) {
      if (!spec.has(age)) {
        continue;
      }
      JsonObject ageSpec = spec.getAsJsonObject(age);
      for (Map<String, String> combo : combos) {
        for (String pose : poses) {
          buildAppearance(id, spec, age, ageSpec, dims, combo, pose, appearances);
        }
      }
      if (spec.has("tinted")) {
        for (Map.Entry<String, JsonElement> part : spec.getAsJsonObject("tinted").entrySet()) {
          for (String pose : poses) {
            buildTintedPart(id, spec, age, ageSpec, part.getKey(), part.getValue().getAsJsonObject(), pose, parts);
          }
        }
      }
    }
    if (appearances.isEmpty() && parts.isEmpty()) {
      return null;
    }
    JsonObject out = new JsonObject();
    if (spec.has("fallback")) {
      out.addProperty("fallback", spec.get("fallback").getAsString());
    }
    out.addProperty("spin", spec.has("spin") && spec.get("spin").getAsBoolean());
    out.addProperty("vehicle", spec.has("vehicle") && spec.get("vehicle").getAsBoolean());
    out.addProperty("babyOnly", spec.has("babyOnly") && spec.get("babyOnly").getAsBoolean());
    JsonObject dimsOut = new JsonObject();
    for (Map.Entry<String, Map<String, String>> dim : dims.entrySet()) {
      JsonArray vals = new JsonArray();
      dim.getValue().keySet().forEach(vals::add);
      dimsOut.add(dim.getKey(), vals);
    }
    out.add("dims", dimsOut);
    JsonArray posesOut = new JsonArray();
    poses.stream().filter(p -> !p.isEmpty()).forEach(posesOut::add);
    out.add("poses", posesOut);
    out.add("appearances", appearances);
    out.add("parts", parts);
    return out;
  }

  private void buildAppearance(
      String id,
      JsonObject spec,
      String age,
      JsonObject ageSpec,
      Map<String, Map<String, String>> dims,
      Map<String, String> combo,
      String pose,
      JsonObject appearances
  ) throws IOException {
    boolean baby = age.equals("baby");
    String baseModel = withPose(pick(ageSpec, "model", "modelBy", combo), pose);
    if (baseModel == null) {
      return;
    }
    String baseTex = texture(ageSpec, combo, dims, pose);
    List<String> textures = new ArrayList<>();
    textures.add(baseTex);

    JsonArray elements = new JsonArray();
    JsonArray exact = new JsonArray();
    VanillaGeometry.Space space = spec.has("vehicle") && spec.get("vehicle").getAsBoolean()
        ? VanillaGeometry.Space.BOAT
        : spec.has("space") ? VanillaGeometry.Space.valueOf(spec.get("space").getAsString()) : VanillaGeometry.Space.ENTITY;
    List<VanillaGeometry.Cube> baseCubes = VanillaGeometry.load(posedFile(baseModel));
    VanillaGeometry.appendElements(elements, baseCubes, space, "#0", 0, null);
    VanillaGeometry.appendElements(exact, baseCubes, space, "#0", 0, null, true);

    // composite textures over the base sprite (same UV layout)
    List<String> composites = new ArrayList<>();
    if (spec.has("composite")) {
      for (JsonElement el : spec.getAsJsonArray("composite")) {
        JsonObject comp = el.getAsJsonObject();
        if (!matches(comp, combo, baby)) {
          continue;
        }
        String key = baby && comp.has("babyTex") ? "babyTex" : "tex";
        if (!comp.has(key)) {
          continue;
        }
        composites.add(fill(comp.get(key).getAsString(), combo, dims, comp));
      }
    }
    textures.addAll(composites);

    JsonObject texMap = new JsonObject();
    texMap.addProperty("0", composites.isEmpty() ? backport(baseTex) : composite(baseTex, composites));

    int layerIdx = 1;
    List<AnimatedPackGenerator.Layer> animatedLayers = new ArrayList<>();
    String rigModel = pick(ageSpec, "model", "modelBy", combo);
    if (spec.has("layers")) {
      for (JsonElement el : spec.getAsJsonArray("layers")) {
        JsonObject layer = el.getAsJsonObject();
        if (!matches(layer, combo, baby)) {
          continue;
        }
        String layerModel = layerModel(layer, baby, baseModel, pose);
        String layerTex = fill(layer.get(baby && layer.has("babyTex") ? "babyTex" : "tex").getAsString(), combo, dims, layer);
        if (layerModel == null || assets.find("assets/minecraft/textures/" + layerTex + ".png").isEmpty()) {
          continue;
        }
        double inflate = layer.has("inflate") ? layer.get("inflate").getAsDouble() : 0.02;
        String var = "#" + layerIdx;
        List<VanillaGeometry.Cube> layerCubes = VanillaGeometry.load(posedFile(layerModel));
        VanillaGeometry.appendElements(elements, layerCubes, VanillaGeometry.Space.ENTITY, var, inflate, null);
        VanillaGeometry.appendElements(exact, layerCubes, VanillaGeometry.Space.ENTITY, var, inflate, null, true);
        texMap.addProperty(String.valueOf(layerIdx), backport(layerTex));
        textures.add(layerTex);
        animatedLayers.add(new AnimatedPackGenerator.Layer(layerModel(layer, baby, rigModel, ""), var, inflate));
        layerIdx++;
      }
    }

    String rootY = pick(ageSpec, "rootY", "rootYBy", combo);
    if (rootY != null) {
      // renderers that move the model root (SulfurCubeRenderer.scale) instead of the living-entity 1.501
      double shift = (Double.parseDouble(rootY) - 1.501) * 16;
      shiftY(elements, shift);
      shiftY(exact, shift);
    }
    if (!baby && keepsVanillaAdult(id, spec, combo)) {
      return;
    }
    String since = since(textures);
    if (!isAfter(since, FIRST_VERSION)) {
      return;
    }
    String key = appearanceKey(id, age, combo, pose);
    VanillaGeometry.addBackFaces(elements, stagedTextures(texMap));
    VanillaGeometry.addBackFaces(exact, stagedTextures(texMap));
    Fit fit = fit(elements);
    scaleElements(exact, fit.scale);
    String modelRef = writeEntityModel(id, key, elements, exact, texMap, fit, false);
    String hurtRef = writeEntityModel(id, key, elements, exact, hurtTextures(texMap), fit, true);
    int cmd = nextCmd++;
    overrides.add(new Override(BASE_PROXY, cmd, modelRef, false));
    overrides.add(new Override(BASE_PROXY, cmd + HURT_OFFSET, hurtRef, false));
    boolean poseTex = pose.isEmpty() || (ageSpec.has("texByPose") && ageSpec.getAsJsonObject("texByPose").has(pose));
    if (animated != null && poseTex && space == VanillaGeometry.Space.ENTITY && rigModel != null) {
      animated.addAppearance(id, key, rigModel, animatedLayers, texMap,
          rootY == null ? 1.501 : Double.parseDouble(rootY));
    }

    JsonObject app = new JsonObject();
    app.addProperty("cmd", cmd);
    app.addProperty("since", since);
    app.addProperty("height", fit.height / 16.0);
    appearances.add(key, app);
  }

  private void buildTintedPart(
      String id,
      JsonObject spec,
      String age,
      JsonObject ageSpec,
      String partName,
      JsonObject part,
      String pose,
      JsonObject parts
  ) throws IOException {
    boolean baby = age.equals("baby");
    if (baby && part.has("adultOnly") && part.get("adultOnly").getAsBoolean()) {
      return;
    }
    String baseModel = withPose(ageSpec.get("model").getAsString(), pose);
    String model = layerModel(part, baby, baseModel, pose);
    String tex = part.get(baby && part.has("babyTex") ? "babyTex" : "tex").getAsString();
    if (model == null || assets.find("assets/minecraft/textures/" + tex + ".png").isEmpty()) {
      return;
    }
    String since = since(List.of(tex, texture(ageSpec, Map.of(), Map.of(), pose)));
    if (!isAfter(since, FIRST_VERSION)) {
      return;
    }
    JsonArray elements = new JsonArray();
    JsonArray exact = new JsonArray();
    List<VanillaGeometry.Cube> partCubes = VanillaGeometry.load(posedFile(model));
    VanillaGeometry.appendElements(elements, partCubes, VanillaGeometry.Space.ENTITY, "#0", 0.05, null);
    VanillaGeometry.appendElements(exact, partCubes, VanillaGeometry.Space.ENTITY, "#0", 0.05, null, true);
    for (JsonArray list : List.of(elements, exact)) {
      for (JsonElement el : list) {
        for (Map.Entry<String, JsonElement> face : el.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
          face.getValue().getAsJsonObject().addProperty("tintindex", 0);
        }
      }
    }
    JsonObject texMap = new JsonObject();
    texMap.addProperty("0", backport(tex));
    String key = id + ";" + age + ";#" + partName + (pose.isEmpty() ? "" : ";pose=" + pose);
    VanillaGeometry.addBackFaces(elements, stagedTextures(texMap));
    VanillaGeometry.addBackFaces(exact, stagedTextures(texMap));
    Fit fit = fit(elements);
    scaleElements(exact, fit.scale);
    String modelRef = writeEntityModel(id, key, elements, exact, texMap, fit, false);
    String hurtRef = writeEntityModel(id, key, elements, exact, hurtTextures(texMap), fit, true);
    int cmd = nextCmd++;
    if (animated != null && pose.isEmpty()) {
      animated.addTintedPart(id, key, model, ageSpec.get("model").getAsString(), backport(tex), 0.05);
    }
    overrides.add(new Override(TINT_PROXY, cmd, modelRef, true));
    overrides.add(new Override(TINT_PROXY, cmd + HURT_OFFSET, hurtRef, true));
    JsonObject out = new JsonObject();
    out.addProperty("cmd", cmd);
    out.addProperty("since", since);
    if (part.has("when")) {
      out.add("when", part.get("when"));
    }
    parts.add(key, out);
  }

  /**
   * Adults of mobs the oldest client already has stay vanilla (pixel diffs of unused texture regions and
   * later remodels are not worth replacing them) unless the spec marks a "remodel" or the combo has a
   * variant value listed in "newValues".
   */
  private boolean keepsVanillaAdult(String id, JsonObject spec, Map<String, String> combo) {
    if (postClientEntities.contains(id) || (spec.has("vehicle") && spec.get("vehicle").getAsBoolean())
        || (spec.has("remodel") && spec.get("remodel").getAsBoolean())) {
      return false;
    }
    if (spec.has("newValues")) {
      for (Map.Entry<String, JsonElement> dim : spec.getAsJsonObject("newValues").entrySet()) {
        for (JsonElement val : dim.getValue().getAsJsonArray()) {
          if (val.getAsString().equals(combo.get(dim.getKey()))) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private String layerModel(JsonObject layer, boolean baby, String baseModel, String pose) {
    if (baby && layer.has("adultOnly") && layer.get("adultOnly").getAsBoolean()) {
      return null;
    }
    String raw;
    if (baby) {
      raw = layer.has("babyModel") ? layer.get("babyModel").getAsString() : null;
      if (raw == null && "@base".equals(layer.get("model").getAsString())) {
        raw = "@base";
      }
    } else {
      raw = layer.get("model").getAsString();
    }
    if (raw == null) {
      return null;
    }
    if ("@base".equals(raw)) {
      return baseModel;
    }
    return withPose(raw, pose);
  }

  private String withPose(String model, String pose) {
    if (model == null || pose.isEmpty()) {
      return model;
    }
    String posed = model + "@" + pose;
    return Files.exists(posedFile(posed)) ? posed : model;
  }

  private Path posedFile(String model) {
    return posedDir.resolve(model.replace('/', java.io.File.separatorChar).replace("~", "~") + ".json");
  }

  private static String pick(JsonObject ageSpec, String field, String byField, Map<String, String> combo) {
    if (ageSpec.has(byField)) {
      for (Map.Entry<String, JsonElement> by : ageSpec.getAsJsonObject(byField).entrySet()) {
        String val = combo.get(by.getKey());
        JsonObject options = by.getValue().getAsJsonObject();
        if (val != null && options.has(val)) {
          return options.get(val).getAsString();
        }
      }
    }
    return ageSpec.has(field) ? ageSpec.get(field).getAsString() : null;
  }

  private String texture(JsonObject ageSpec, Map<String, String> combo, Map<String, Map<String, String>> dims, String pose) {
    String raw = pick(ageSpec, "tex", "texBy", combo);
    if (!pose.isEmpty() && ageSpec.has("texByPose") && ageSpec.getAsJsonObject("texByPose").has(pose)) {
      raw = ageSpec.getAsJsonObject("texByPose").get(pose).getAsString();
    }
    return fill(raw, combo, dims, ageSpec);
  }

  private static String fill(String template, Map<String, String> combo, Map<String, Map<String, String>> dims, JsonObject owner) {
    String out = template;
    for (Map.Entry<String, String> ent : combo.entrySet()) {
      String frag = dims.containsKey(ent.getKey()) ? dims.get(ent.getKey()).get(ent.getValue()) : ent.getValue();
      if (owner != null && owner.has("texMap") && owner.getAsJsonObject("texMap").has(ent.getKey())) {
        JsonObject map = owner.getAsJsonObject("texMap").getAsJsonObject(ent.getKey());
        if (map.has(ent.getValue())) {
          frag = map.get(ent.getValue()).getAsString();
        }
      }
      out = out.replace("{" + ent.getKey() + "}", frag == null ? ent.getValue() : frag);
    }
    return out;
  }

  private static boolean matches(JsonObject obj, Map<String, String> combo, boolean baby) {
    if (baby && obj.has("adultOnly") && obj.get("adultOnly").getAsBoolean()) {
      return false;
    }
    if (!obj.has("when")) {
      return true;
    }
    for (Map.Entry<String, JsonElement> cond : obj.getAsJsonObject("when").entrySet()) {
      String val = combo.get(cond.getKey());
      boolean any = false;
      for (JsonElement opt : cond.getValue().getAsJsonArray()) {
        String o = opt.getAsString();
        if (o.startsWith("!") ? !o.substring(1).equals(val) : o.equals(val)) {
          any = true;
        }
      }
      if (!any) {
        return false;
      }
    }
    return true;
  }

  static String appearanceKey(String id, String age, Map<String, String> combo, String pose) {
    StringBuilder sb = new StringBuilder(id).append(';').append(age);
    for (Map.Entry<String, String> ent : combo.entrySet()) {
      sb.append(';').append(ent.getKey()).append('=').append(ent.getValue());
    }
    if (!pose.isEmpty()) {
      sb.append(";pose=").append(pose);
    }
    return sb.toString();
  }

  private static List<Map<String, String>> combos(LinkedHashMap<String, Map<String, String>> dims) {
    List<Map<String, String>> out = new ArrayList<>();
    out.add(new LinkedHashMap<>());
    for (Map.Entry<String, Map<String, String>> dim : dims.entrySet()) {
      List<Map<String, String>> next = new ArrayList<>();
      for (Map<String, String> base : out) {
        for (String val : dim.getValue().keySet()) {
          Map<String, String> c = new LinkedHashMap<>(base);
          c.put(dim.getKey(), val);
          next.add(c);
        }
      }
      out = next;
    }
    return out;
  }

  private String since(List<String> textures) {
    String best = FIRST_VERSION;
    for (String tex : textures) {
      String ver = textureHistory.getOrDefault("minecraft:" + tex, "26.3");
      if (isAfter(ver, best)) {
        best = ver;
      }
    }
    return best;
  }

  static boolean isAfter(String a, String b) {
    String[] pa = a.split("\\.");
    String[] pb = b.split("\\.");
    for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
      int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
      int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
      if (va != vb) {
        return va > vb;
      }
    }
    return false;
  }

  private String backport(String tex) throws IOException {
    return assets.backportTexture("minecraft:" + tex);
  }

  private String composite(String baseTex, List<String> overlays) throws IOException {
    BufferedImage img = ImageIO.read(assets.find("assets/minecraft/textures/" + baseTex + ".png").orElseThrow().toFile());
    for (String overlay : overlays) {
      var path = assets.find("assets/minecraft/textures/" + overlay + ".png");
      if (path.isPresent()) {
        img = Images.over(img, firstFrame(ImageIO.read(path.get().toFile())));
      }
    }
    return writeGenerated(firstFrame(img), "composite");
  }

  /** Texture variable -> staged image (first animation frame), for coverage checks. */
  java.util.function.Function<String, BufferedImage> stagedTextures(JsonObject texMap) {
    Map<String, BufferedImage> cache = new java.util.HashMap<>();
    return var -> cache.computeIfAbsent(var, k -> {
      if (!texMap.has(k)) {
        return null;
      }
      try {
        java.nio.file.Path path = assets.stagingTexture(texMap.get(k).getAsString());
        return java.nio.file.Files.exists(path) ? firstFrame(ImageIO.read(path.toFile())) : null;
      } catch (IOException ex) {
        return null;
      }
    });
  }

  private static BufferedImage firstFrame(BufferedImage img) {
    if (img.getHeight() > img.getWidth() && img.getHeight() % img.getWidth() == 0) {
      return img.getSubimage(0, 0, img.getWidth(), img.getWidth());
    }
    return img;
  }

  JsonObject hurtTextures(JsonObject texMap) throws IOException {
    JsonObject out = new JsonObject();
    for (Map.Entry<String, JsonElement> ent : texMap.entrySet()) {
      String ref = ent.getValue().getAsString();
      Path src = assets.stagingTexture(ref);
      BufferedImage img = firstFrame(ImageIO.read(src.toFile()));
      out.addProperty(ent.getKey(), writeGenerated(Images.hurtOverlay(img), "hurt"));
    }
    return out;
  }

  private String writeGenerated(BufferedImage img, String kind) throws IOException {
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    ImageIO.write(img, "png", buf);
    String hash = sha1(buf.toByteArray()).substring(0, 16);
    String ref = VanillaAssets.NS + ":item/x/gen/" + kind + "_" + hash;
    Path dst = assets.stagingTexture(ref);
    if (!Files.exists(dst)) {
      Files.createDirectories(dst.getParent());
      Files.write(dst, buf.toByteArray());
    }
    return ref;
  }

  record Fit(double scale, double height) {
  }

  /** Compresses around the feet centre (8,0,8) so the model fits the legacy -16..32 element bounds. */
  private static Fit fit(JsonArray elements) {
    double halfXz = 0;
    double maxY = 0;
    double minY = 0;
    for (JsonElement el : elements) {
      JsonObject e = el.getAsJsonObject();
      for (String k : new String[] {"from", "to"}) {
        JsonArray v = e.getAsJsonArray(k);
        halfXz = Math.max(halfXz, Math.max(Math.abs(v.get(0).getAsDouble() - 8), Math.abs(v.get(2).getAsDouble() - 8)));
        maxY = Math.max(maxY, v.get(1).getAsDouble());
        minY = Math.min(minY, v.get(1).getAsDouble());
      }
    }
    double scale = 1.0;
    scale = Math.min(scale, 23.5 / Math.max(halfXz, 1e-6));
    scale = Math.min(scale, 31.5 / Math.max(maxY, 1e-6));
    if (minY < 0) {
      scale = Math.min(scale, 15.5 / -minY);
    }
    if (scale < 1.0) {
      for (JsonElement el : elements) {
        JsonObject e = el.getAsJsonObject();
        for (String k : new String[] {"from", "to"}) {
          e.add(k, compress(e.getAsJsonArray(k), scale));
        }
        if (e.has("rotation")) {
          JsonObject rot = e.getAsJsonObject("rotation");
          rot.add("origin", compress(rot.getAsJsonArray("origin"), scale));
        }
      }
    }
    return new Fit(scale, maxY);
  }

  private static JsonArray compress(JsonArray v, double s) {
    return VanillaGeometry.vec(
        8 + (v.get(0).getAsDouble() - 8) * s,
        v.get(1).getAsDouble() * s,
        8 + (v.get(2).getAsDouble() - 8) * s);
  }

  /**
   * Writes the legacy model and, when it differs, the exact-rotation twin into the 1.21.11+ overlay
   * (same path, so those clients load it instead).
   */
  private String writeEntityModel(String id, String key, JsonArray elements, JsonArray exact, JsonObject texMap, Fit fit,
                                  boolean hurt) throws IOException {
    JsonObject model = new JsonObject();
    JsonObject textures = texMap.deepCopy();
    textures.addProperty("particle", texMap.get("0").getAsString());
    model.add("textures", textures);
    model.add("elements", elements);
    model.add("display", display(fit.scale));
    String ref = VanillaAssets.NS + ":entity/" + id + "/" + sha1(key.getBytes(StandardCharsets.UTF_8)).substring(0, 12)
        + (hurt ? "_hurt" : "");
    assets.writeModel(ref, model);
    if (!exact.equals(elements)) {
      JsonObject precise = model.deepCopy();
      precise.add("elements", exact);
      assets.writeOverlayModel(ResourcePackAssembler.EXACT_ROTATION_OVERLAY, ref, precise);
    }
    return ref;
  }

  private static void shiftY(JsonArray elements, double px) {
    for (JsonElement el : elements) {
      JsonObject e = el.getAsJsonObject();
      for (String k : new String[] {"from", "to"}) {
        JsonArray v = e.getAsJsonArray(k);
        v.set(1, new com.google.gson.JsonPrimitive(v.get(1).getAsDouble() + px));
      }
      if (e.has("rotation")) {
        JsonArray origin = e.getAsJsonObject("rotation").getAsJsonArray("origin");
        origin.set(1, new com.google.gson.JsonPrimitive(origin.get(1).getAsDouble() + px));
      }
    }
  }

  private static void scaleElements(JsonArray elements, double scale) {
    if (scale >= 1.0) {
      return;
    }
    for (JsonElement el : elements) {
      JsonObject e = el.getAsJsonObject();
      for (String k : new String[] {"from", "to"}) {
        e.add(k, compress(e.getAsJsonArray(k), scale));
      }
      if (e.has("rotation")) {
        JsonObject rot = e.getAsJsonObject("rotation");
        rot.add("origin", compress(rot.getAsJsonArray("origin"), scale));
      }
    }
  }

  /**
   * fixed: ItemDisplay renders rotate(Y 180) then this transform; feet at the display origin.
   * head: armor-stand helmet (CustomHeadLayer 0.625 flip) calibrated by the runtime's armor-stand offset.
   */
  private static JsonObject display(double s) {
    double inv = 1.0 / s;
    JsonObject display = new JsonObject();
    JsonObject fixed = new JsonObject();
    fixed.add("rotation", VanillaGeometry.vec(0, 180, 0));
    fixed.add("translation", VanillaGeometry.vec(0, clamp(8 * inv), 0));
    fixed.add("scale", VanillaGeometry.vec(inv, inv, inv));
    display.add("fixed", fixed);
    double headScale = 1.6 * inv;
    JsonObject head = new JsonObject();
    head.add("rotation", VanillaGeometry.vec(0, 180, 0));
    head.add("translation", VanillaGeometry.vec(0, clamp(-6.5 + 8 * headScale - 12.8), 0));
    head.add("scale", VanillaGeometry.vec(headScale, headScale, headScale));
    display.add("head", head);
    return display;
  }

  private static double clamp(double v) {
    return Math.max(-80, Math.min(80, v));
  }

  private static String sha1(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static JsonObject readJson(Path path) throws IOException {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  static void writeCatalog(JsonObject catalog, Path dst) throws IOException {
    Files.createDirectories(dst.getParent());
    Files.writeString(dst, GSON.toJson(catalog), StandardCharsets.UTF_8);
  }

  static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }
}
