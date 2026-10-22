package dev.bluesparksss.viavisuals.build;

import com.google.gson.Gson;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Block carriers from resource-pack-src/block-pack.json (scripts/generate_block_catalog.py):
 * note_block / leaves / tripwire blockstates with carrier variants appended after the vanilla
 * legacy base (later matching variant keys win on every client), plus one item model per display
 * visual with multipart parts and x/y variant rotations baked into the geometry.
 */
final class BlockPackGenerator {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private final VanillaAssets assets;
  private final Path legacyBlockstates;
  private final ProxyOverrides overrides;
  private final Consumer<String> logger;

  BlockPackGenerator(VanillaAssets assets, Path legacyBlockstates, ProxyOverrides overrides, Consumer<String> logger) {
    this.assets = assets;
    this.legacyBlockstates = legacyBlockstates;
    this.overrides = overrides;
    this.logger = logger;
  }

  void generate(Path blockPackFile) throws IOException {
    JsonObject pack = readJson(blockPackFile);
    writeCarrier("note_block", pack.getAsJsonObject("note_block"));
    writeCarrier("tripwire", pack.getAsJsonObject("tripwire"));
    for (Map.Entry<String, JsonElement> leaves : pack.getAsJsonObject("leaves").entrySet()) {
      writeCarrier(leaves.getKey(), leaves.getValue().getAsJsonObject());
    }
    if (pack.has("spare")) {
      for (Map.Entry<String, JsonElement> ent : pack.getAsJsonObject("spare").entrySet()) {
        writeCarrier(ent.getKey(), ent.getValue().getAsJsonObject());
      }
    }
    if (pack.has("hidden")) {
      for (Map.Entry<String, JsonElement> ent : pack.getAsJsonObject("hidden").entrySet()) {
        writeHidden(ent.getKey(), ent.getValue().getAsJsonArray());
      }
    }
    int displays = 0;
    for (Map.Entry<String, JsonElement> ent : pack.getAsJsonObject("displays").entrySet()) {
      int cmd = Integer.parseInt(ent.getKey());
      JsonObject disp = ent.getValue().getAsJsonObject();
      try {
        String model = displayModel(cmd, disp);
        overrides.add(disp.get("proxy").getAsString(), cmd, model);
        displays++;
      } catch (IOException ex) {
        logger.accept("Warning: block display " + cmd + " skipped: " + ex.getMessage());
      }
    }
    logger.accept("Generated block carriers and " + displays + " block display model(s)");
  }

  /** Property space every supported client (1.16+) shares for each carrier block. */
  private static final List<String> INSTRUMENTS_116 = List.of("harp", "basedrum", "snare", "hat", "bass", "flute",
      "bell", "guitar", "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling");

  private static Map<String, List<String>> carrierProperties(String block) {
    Map<String, List<String>> props = new java.util.LinkedHashMap<>();
    List<String> bools = List.of("false", "true");
    if ("note_block".equals(block)) {
      props.put("instrument", INSTRUMENTS_116);
      List<String> notes = new ArrayList<>();
      for (int i = 0; i < 25; i++) {
        notes.add(String.valueOf(i));
      }
      props.put("note", notes);
      props.put("powered", bools);
    } else if (block.endsWith("_trapdoor")) {
      props.put("facing", List.of("north", "south", "west", "east"));
      props.put("half", List.of("top", "bottom"));
      props.put("open", bools);
      props.put("powered", bools);
      props.put("waterlogged", bools);
    } else if (block.endsWith("_door")) {
      props.put("facing", List.of("north", "south", "west", "east"));
      props.put("half", List.of("upper", "lower"));
      props.put("hinge", List.of("left", "right"));
      props.put("open", bools);
      props.put("powered", bools);
    } else if (block.endsWith("_fence_gate")) {
      props.put("facing", List.of("north", "south", "west", "east"));
      props.put("in_wall", bools);
      props.put("open", bools);
      props.put("powered", bools);
    } else if ("tripwire".equals(block)) {
      for (String name : List.of("attached", "disarmed", "east", "north", "powered", "south", "west")) {
        props.put(name, bools);
      }
    } else {
      props.put("distance", List.of("1", "2", "3", "4", "5", "6", "7"));
      props.put("persistent", bools);
    }
    return props;
  }

  /**
   * Every state gets exactly one variant key: 1.19.3+ clients reject overlapping keys
   * ("Overlapping definition"), so the legacy base cannot simply be overridden by later keys.
   */
  private void writeCarrier(String block, JsonObject carriers) throws IOException {
    JsonObject legacy = readJson(legacyBlockstates.resolve(block + ".json")).getAsJsonObject("variants");
    Map<String, List<String>> props = carrierProperties(block);
    List<Map<String, String>> states = new ArrayList<>();
    states.add(new java.util.LinkedHashMap<>());
    for (Map.Entry<String, List<String>> prop : props.entrySet()) {
      List<Map<String, String>> next = new ArrayList<>();
      for (Map<String, String> st : states) {
        for (String val : prop.getValue()) {
          Map<String, String> copy = new java.util.LinkedHashMap<>(st);
          copy.put(prop.getKey(), val);
          next.add(copy);
        }
      }
      states = next;
    }
    Map<Map<String, String>, JsonElement> carrierByState = new HashMap<>();
    for (Map.Entry<String, JsonElement> ent : carriers.entrySet()) {
      carrierByState.put(parseKey(ent.getKey()), ent.getValue());
    }
    JsonObject variants = new JsonObject();
    for (Map<String, String> st : states) {
      JsonElement carrier = carrierByState.get(st);
      JsonObject vis;
      if (carrier != null) {
        vis = carrierVariant(carrier.getAsJsonObject());
      } else {
        vis = legacyVariant(legacy, st);
      }
      StringBuilder key = new StringBuilder();
      for (Map.Entry<String, String> prop : st.entrySet()) {
        if (key.length() > 0) {
          key.append(',');
        }
        key.append(prop.getKey()).append('=').append(prop.getValue());
      }
      variants.add(key.toString(), vis);
    }
    JsonObject root = new JsonObject();
    root.add("variants", variants);
    Path dst = assets.stagingRoot().resolve("assets/minecraft/blockstates/" + block + ".json");
    Files.createDirectories(dst.getParent());
    Files.writeString(dst, GSON.toJson(root), StandardCharsets.UTF_8);
  }

  /**
   * Vanilla blockstate with the listed variants drawn as nothing: those states carry a display model, which
   * would otherwise sit inside (or beside) the fallback block's own model.
   */
  private static final String HIDDEN_MODEL = VanillaAssets.NS + ":block_display/hidden";

  /** A model that draws nothing: carriers of blocks that an entity draws on their own. */
  private void writeHiddenModel() throws IOException {
    JsonObject model = new JsonObject();
    JsonObject textures = new JsonObject();
    textures.addProperty("particle", "minecraft:block/stone");
    model.add("textures", textures);
    model.add("elements", new JsonArray());
    assets.writeModel(HIDDEN_MODEL, model);
  }

  private void writeHidden(String block, JsonArray keys) throws IOException {
    JsonObject root = readJson(legacyBlockstates.resolve(block + ".json"));
    JsonObject variants = root.getAsJsonObject("variants");
    String ref = HIDDEN_MODEL;
    writeHiddenModel();
    for (JsonElement key : keys) {
      if (!variants.has(key.getAsString())) {
        throw new IOException(block + " has no variant '" + key.getAsString() + "' to hide");
      }
      JsonObject variant = new JsonObject();
      variant.addProperty("model", ref);
      variants.add(key.getAsString(), variant);
    }
    Path dst = assets.stagingRoot().resolve("assets/minecraft/blockstates/" + block + ".json");
    Files.createDirectories(dst.getParent());
    Files.writeString(dst, GSON.toJson(root), StandardCharsets.UTF_8);
  }

  private static Map<String, String> parseKey(String key) {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    if (key.isEmpty()) {
      return out;
    }
    for (String part : key.split(",")) {
      String[] kv = part.split("=", 2);
      out.put(kv[0], kv[1]);
    }
    return out;
  }

  /** The legacy variant whose (partial) key matches this full state. */
  private static JsonObject legacyVariant(JsonObject legacy, Map<String, String> st) throws IOException {
    for (Map.Entry<String, JsonElement> ent : legacy.entrySet()) {
      Map<String, String> want = parseKey(ent.getKey());
      boolean match = true;
      for (Map.Entry<String, String> w : want.entrySet()) {
        if (!w.getValue().equals(st.get(w.getKey()))) {
          match = false;
          break;
        }
      }
      if (match) {
        JsonElement val = ent.getValue();
        return (val.isJsonArray() ? val.getAsJsonArray().get(0) : val).getAsJsonObject().deepCopy();
      }
    }
    throw new IOException("no legacy variant for " + st);
  }

  /** Factor an oversized special model was compressed by (see VanillaGeometry.fitAroundCenter). */
  static final String FIT_KEY = "viavisuals_scale";

  private String displayModel(int cmd, JsonObject disp) throws IOException {
    String ref = VanillaAssets.NS + ":block_display/" + cmd;
    JsonObject model = new JsonObject();
    double fit = 1.0;
    if (disp.has("item") && !disp.get("item").isJsonNull()) {
      String special = VanillaAssets.NS + ":item/special/" + disp.get("item").getAsString();
      model.addProperty("parent", special);
      JsonObject parent = assets.readStagedModel(special);
      if (parent.has(FIT_KEY)) {
        fit = parent.get(FIT_KEY).getAsDouble();
      }
    } else {
      JsonObject merged = mergeParts(disp.getAsJsonArray("parts"), disp.has("tint") ? disp.getAsJsonObject("tint") : null);
      inflate(merged.getAsJsonArray("elements"));
      model.add("textures", merged.get("textures"));
      model.add("elements", merged.get("elements"));
      fit = VanillaGeometry.fitAroundCenter(merged.getAsJsonArray("elements"));
    }
    model.add("display", fit < 1.0 ? VanillaGeometry.scaleDisplay(blockDisplay(), 1.0 / fit) : blockDisplay());
    assets.writeModel(ref, model);
    return ref;
  }

  /** All parts of a visual (multipart blocks) as one model's textures and elements. */
  private JsonObject mergeParts(JsonArray parts, JsonObject tint) throws IOException {
    JsonObject textures = new JsonObject();
    JsonArray elements = new JsonArray();
    for (int i = 0; i < parts.size(); i++) {
      appendPart(parts.get(i).getAsJsonObject(), "p" + i + "_", textures, elements);
    }
    // states vanilla draws as nothing (pitcher crop top below age 3) keep an empty model
    if (!textures.has("particle")) {
      // clients warn about a model without particle; a display never shows it
      textures.addProperty("particle", textures.isEmpty() ? "minecraft:item/barrier" : firstTexture(textures));
    }
    if (tint != null) {
      bakeTints(textures, elements, tint);
    }
    JsonObject model = new JsonObject();
    model.add("textures", textures);
    model.add("elements", elements);
    return model;
  }

  /**
   * The carriers of these blocks have no colour handler on a 1.16 client (and an item display has none at all), so
   * faces with a tintindex get a copy of their texture multiplied by the plains colour and lose the index.
   */
  private void bakeTints(JsonObject textures, JsonArray elements, JsonObject tint) throws IOException {
    Map<String, String> baked = new HashMap<>();
    for (JsonElement el : elements) {
      for (Map.Entry<String, JsonElement> face : el.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
        JsonObject f = face.getValue().getAsJsonObject();
        if (!f.has("tintindex")) {
          continue;
        }
        String key = String.valueOf(f.get("tintindex").getAsInt());
        String var = f.get("texture").getAsString();
        if (!tint.has(key) || !var.startsWith("#") || !textures.has(var.substring(1))) {
          continue;
        }
        if ("none".equals(tint.get(key).getAsString())) {
          // vanilla leaves other than mangrove are not tinted, but their carrier's colour handler would tint them
          f.remove("tintindex");
          continue;
        }
        String name = var.substring(1) + "_t" + key;
        if (!textures.has(name)) {
          String source = textures.get(var.substring(1)).getAsString();
          String tinted = assets.tintedTexture(source, tintRgb(tint.get(key).getAsString()));
          textures.addProperty(name, tinted);
          baked.putIfAbsent(source, tinted);
        }
        f.addProperty("texture", "#" + name);
        f.remove("tintindex");
      }
    }
    // the client's break particles take the model's particle sprite and the carrier has no colour handler to tint it
    String particle = textures.has("particle") ? textures.get("particle").getAsString() : null;
    if (particle != null && baked.containsKey(particle)) {
      textures.addProperty("particle", baked.get(particle));
    }
  }

  /** Plains (temperature 0.8, downfall 0.4) like the default biome colours; water is the default water colour. */
  private int tintRgb(String source) throws IOException {
    return switch (source) {
      case "grass" -> assets.colormap("grass", 0.8, 0.4);
      case "foliage" -> assets.colormap("foliage", 0.8, 0.4);
      case "dry_foliage" -> assets.colormap("dry_foliage", 0.8, 0.4);
      case "water" -> 0x3F76E4;
      default -> throw new IOException("unknown tint source " + source);
    };
  }

  /**
   * Display elements grow by this many model pixels on every side: the fallback block under a display has the
   * same faces, and coplanar faces z-fight on every client (a uniform scale about the centre only moves the
   * faces that point away from it).
   */
  private static final double INFLATE = 0.15;
  private static final String TINT_MARK = "|tint";

  private static void inflate(JsonArray elements) {
    for (JsonElement el : elements) {
      JsonObject elem = el.getAsJsonObject();
      JsonArray from = elem.getAsJsonArray("from");
      JsonArray to = elem.getAsJsonArray("to");
      for (int i = 0; i < 3; i++) {
        from.set(i, new com.google.gson.JsonPrimitive(Math.max(-16.0, from.get(i).getAsDouble() - INFLATE)));
        to.set(i, new com.google.gson.JsonPrimitive(Math.min(32.0, to.get(i).getAsDouble() + INFLATE)));
      }
    }
  }

  private int carrierModels;

  /** Blockstate variant for one carrier visual: a single vanilla model, or several parts merged into one model. */
  private JsonObject carrierVariant(JsonObject carrier) throws IOException {
    if (!carrier.has("parts")) {
      JsonObject vis = carrier.deepCopy();
      String model = vis.get("model").getAsString();
      if (model.equals(HIDDEN_MODEL)) {
        writeHiddenModel();
      } else {
        vis.addProperty("model", assets.backportModel(model));
      }
      return vis;
    }
    String ref = VanillaAssets.NS + ":block_carrier/" + carrierModels++;
    assets.writeModel(ref, mergeParts(carrier.getAsJsonArray("parts"),
        carrier.has("tint") ? carrier.getAsJsonObject("tint") : null));
    JsonObject vis = new JsonObject();
    vis.addProperty("model", ref);
    return vis;
  }

  private static String firstTexture(JsonObject textures) {
    for (Map.Entry<String, JsonElement> ent : textures.entrySet()) {
      return ent.getValue().getAsString();
    }
    return "minecraft:block/stone";
  }

  /** Resolves a part model (parent chain), rotates it by the variant x/y and appends its elements. */
  private void appendPart(JsonObject part, String prefix, JsonObject textures, JsonArray elements) throws IOException {
    String ref = assets.backportModel(part.get("model").getAsString());
    Map<String, String> texVars = new HashMap<>();
    JsonArray partElements = null;
    String cur = ref;
    while (cur != null && !cur.contains("builtin/")) {
      JsonObject m = assets.readStagedModel(cur);
      if (m.has("textures")) {
        for (Map.Entry<String, JsonElement> ent : m.getAsJsonObject("textures").entrySet()) {
          texVars.putIfAbsent(ent.getKey(), ent.getValue().getAsString());
        }
      }
      if (partElements == null && m.has("elements")) {
        partElements = m.getAsJsonArray("elements");
      }
      cur = m.has("parent") ? m.get("parent").getAsString() : null;
    }
    if (partElements == null) {
      return;
    }
    for (Map.Entry<String, String> ent : texVars.entrySet()) {
      String val = resolve(texVars, ent.getValue());
      if (val != null) {
        textures.addProperty(prefix + ent.getKey(), val);
      }
    }
    if (texVars.containsKey("particle") && !textures.has("particle")) {
      String particle = resolve(texVars, texVars.get("particle"));
      if (particle != null) {
        textures.addProperty("particle", particle);
      }
    }
    int rx = part.has("x") ? part.get("x").getAsInt() : 0;
    int ry = part.has("y") ? part.get("y").getAsInt() : 0;
    boolean uvlock = part.has("uvlock") && part.get("uvlock").getAsBoolean();
    for (JsonElement el : partElements) {
      JsonObject elem = el.getAsJsonObject();
      if (rx == 0 && ry == 0) {
        JsonObject copy = elem.deepCopy();
        prefixFaces(copy, prefix);
        elements.add(copy);
        continue;
      }
      JsonObject rotated = rotateElement(elem, rx, ry, uvlock, prefix);
      if (rotated != null) {
        elements.add(rotated);
      }
    }
  }

  private static String resolve(Map<String, String> vars, String val) {
    int guard = 0;
    while (val != null && val.startsWith("#") && guard++ < 10) {
      val = vars.get(val.substring(1));
    }
    return val;
  }

  private static void prefixFaces(JsonObject elem, String prefix) {
    for (Map.Entry<String, JsonElement> face : elem.getAsJsonObject("faces").entrySet()) {
      JsonObject f = face.getValue().getAsJsonObject();
      String tex = VanillaAssets.textureRef(f.get("texture").getAsString());
      if (tex.startsWith("#")) {
        f.addProperty("texture", "#" + prefix + tex.substring(1));
      }
      f.remove("cullface");
    }
  }

  private static final Map<String, int[]> FACE_CORNERS = Map.of(
      "down", new int[] {0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1},
      "up", new int[] {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
      "north", new int[] {1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0},
      "south", new int[] {0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1},
      "west", new int[] {0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1},
      "east", new int[] {1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0}
  );
  private static final Map<String, double[]> FACE_NORMAL = Map.of(
      "down", new double[] {0, -1, 0}, "up", new double[] {0, 1, 0}, "north", new double[] {0, 0, -1},
      "south", new double[] {0, 0, 1}, "west", new double[] {-1, 0, 0}, "east", new double[] {1, 0, 0});

  /** Element → polygons (vanilla FaceInfo/CuboidFace rules) → BlockModelRotation(x,y) → element again. */
  private static JsonObject rotateElement(JsonObject elem, int rx, int ry, boolean uvlock, String prefix) {
    double[] from = arr(elem.getAsJsonArray("from"));
    double[] to = arr(elem.getAsJsonArray("to"));
    double[][] elemRot = null;
    double[] elemOrigin = null;
    if (elem.has("rotation")) {
      JsonObject r = elem.getAsJsonObject("rotation");
      elemRot = axisMatrix(r.get("axis").getAsString(), r.get("angle").getAsDouble());
      elemOrigin = arr(r.getAsJsonArray("origin"));
    }
    // BlockModelRotation: rotate around the block centre by -x about X, then -y about Y
    double[][] variant = mul(axisMatrix("y", -ry), axisMatrix("x", -rx));
    List<VanillaGeometry.Poly> polys = new ArrayList<>();
    for (Map.Entry<String, JsonElement> faceEnt : elem.getAsJsonObject("faces").entrySet()) {
      String dir = faceEnt.getKey();
      JsonObject face = faceEnt.getValue().getAsJsonObject();
      int[] c = FACE_CORNERS.get(dir);
      double[] uv = face.has("uv") ? arr(face.getAsJsonArray("uv")) : defaultUv(from, to, dir);
      int shift = face.has("rotation") ? (face.get("rotation").getAsInt() / 90) % 4 : 0;
      double[][] uvList = {{uv[0], uv[1]}, {uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}};
      double[][] pos = new double[4][];
      double[][] uvs = new double[4][];
      for (int i = 0; i < 4; i++) {
        double[] p = {c[i * 3] == 1 ? to[0] : from[0], c[i * 3 + 1] == 1 ? to[1] : from[1], c[i * 3 + 2] == 1 ? to[2] : from[2]};
        p = add(apply(variant, sub(p, new double[] {8, 8, 8})), new double[] {8, 8, 8});
        pos[i] = new double[] {p[0] / 16, p[1] / 16, p[2] / 16};
        double[] t = uvList[(i + shift) % 4];
        uvs[i] = new double[] {t[0] / 16, t[1] / 16};
      }
      double[] normal = apply(variant, FACE_NORMAL.get(dir));
      String tex = VanillaAssets.textureRef(face.get("texture").getAsString());
      if (tex.startsWith("#")) {
        tex = "#" + prefix + tex.substring(1);
      }
      if (face.has("tintindex")) {
        // the cube conversion rebuilds the faces: the index rides along in the texture name
        tex += TINT_MARK + face.get("tintindex").getAsInt();
      }
      polys.add(new VanillaGeometry.Poly(pos, uvs, normal, tex));
    }
    JsonObject out = VanillaGeometry.convertCube(new VanillaGeometry.Cube("", polys), VanillaGeometry.Space.BLOCK_ENTITY, "#missing", 0);
    if (out != null) {
      for (Map.Entry<String, JsonElement> faceEnt : out.getAsJsonObject("faces").entrySet()) {
        JsonObject f = faceEnt.getValue().getAsJsonObject();
        String tex = f.get("texture").getAsString();
        int mark = tex.indexOf(TINT_MARK);
        if (mark >= 0) {
          f.addProperty("tintindex", Integer.parseInt(tex.substring(mark + TINT_MARK.length())));
          f.addProperty("texture", tex.substring(0, mark));
        }
      }
    }
    if (out != null && elemRot != null) {
      // carry the element's own rotation through the variant rotation: axis' = R·axis, same angle
      JsonObject r = elem.getAsJsonObject("rotation");
      String axis = r.get("axis").getAsString();
      double[] axisVec = {axis.equals("x") ? 1 : 0, axis.equals("y") ? 1 : 0, axis.equals("z") ? 1 : 0};
      double[] mapped = apply(variant, axisVec);
      int idx = 0;
      for (int k = 1; k < 3; k++) {
        if (Math.abs(mapped[k]) > Math.abs(mapped[idx])) {
          idx = k;
        }
      }
      double angle = r.get("angle").getAsDouble() * Math.signum(mapped[idx]);
      double[] origin = add(apply(variant, sub(elemOrigin, new double[] {8, 8, 8})), new double[] {8, 8, 8});
      JsonObject nr = new JsonObject();
      nr.add("origin", VanillaGeometry.vec(origin));
      nr.addProperty("axis", "xyz".substring(idx, idx + 1));
      nr.addProperty("angle", angle);
      if (r.has("rescale")) {
        nr.add("rescale", r.get("rescale"));
      }
      out.add("rotation", nr);
    }
    if (out != null && uvlock) {
      double[] nf = arr(out.getAsJsonArray("from"));
      double[] nt = arr(out.getAsJsonArray("to"));
      for (Map.Entry<String, JsonElement> face : out.getAsJsonObject("faces").entrySet()) {
        JsonObject f = face.getValue().getAsJsonObject();
        f.add("uv", VanillaGeometry.vec(defaultUv(nf, nt, face.getKey())));
        f.remove("rotation");
      }
    }
    return out;
  }

  /** FaceBakery.defaultFaceUV. */
  private static double[] defaultUv(double[] f, double[] t, String dir) {
    return switch (dir) {
      case "down" -> new double[] {f[0], 16 - t[2], t[0], 16 - f[2]};
      case "up" -> new double[] {f[0], f[2], t[0], t[2]};
      case "north" -> new double[] {16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
      case "south" -> new double[] {f[0], 16 - t[1], t[0], 16 - f[1]};
      case "west" -> new double[] {f[2], 16 - t[1], t[2], 16 - f[1]};
      default -> new double[] {16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
    };
  }

  /** ItemDisplay at the block centre: cancel its built-in Y 180; armor-stand helmets undo the 0.625 head scale. */
  private static JsonObject blockDisplay() {
    JsonObject display = new JsonObject();
    JsonObject fixed = new JsonObject();
    fixed.add("rotation", VanillaGeometry.vec(0, 180, 0));
    fixed.add("translation", VanillaGeometry.vec(0, 0, 0));
    fixed.add("scale", VanillaGeometry.vec(1, 1, 1));
    display.add("fixed", fixed);
    JsonObject head = new JsonObject();
    head.add("rotation", VanillaGeometry.vec(0, 180, 0));
    head.add("translation", VanillaGeometry.vec(0, 0, 0));
    head.add("scale", VanillaGeometry.vec(1.6, 1.6, 1.6));
    display.add("head", head);
    return display;
  }

  private static double[][] axisMatrix(String axis, double deg) {
    double r = Math.toRadians(deg);
    double c = Math.cos(r);
    double s = Math.sin(r);
    return switch (axis) {
      case "x" -> new double[][] {{1, 0, 0}, {0, c, -s}, {0, s, c}};
      case "y" -> new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
      default -> new double[][] {{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
    };
  }

  private static double[][] mul(double[][] a, double[][] b) {
    double[][] out = new double[3][3];
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        for (int k = 0; k < 3; k++) {
          out[i][j] += a[i][k] * b[k][j];
        }
      }
    }
    return out;
  }

  private static double[] apply(double[][] m, double[] v) {
    return new double[] {
        m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
        m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
        m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
    };
  }

  private static double[] add(double[] a, double[] b) {
    return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
  }

  private static double[] sub(double[] a, double[] b) {
    return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
  }

  private static double[] arr(JsonArray a) {
    double[] out = new double[a.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = a.get(i).getAsDouble();
    }
    return out;
  }

  private static JsonObject readJson(Path path) throws IOException {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }
}
