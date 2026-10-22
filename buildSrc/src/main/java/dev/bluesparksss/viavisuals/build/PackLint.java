package dev.bluesparksss.viavisuals.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Fails the build on dangling references in the staged pack: every {@code viavisuals:} model/texture a
 * model, blockstate or item definition points at must be shipped, and {@code minecraft:} references
 * outside the 1.21.4+ overlay must exist for the oldest supported client (or be shipped by us). Also
 * checks that every CustomModelData the runtime can send has a legacy override and a 1.21.4+ entry.
 */
final class PackLint {

  private static final String OVERLAY = "modern_item_select";

  private final Path staging;
  private final Path oldestClient;
  private final List<String> problems = new ArrayList<>();
  /** proxy material (lower case) -> CMDs the runtime sends on it to pre-1.21.4 / 1.21.4+ clients. */
  private final Map<String, Set<Integer>> sentCmds = new TreeMap<>();
  private final Map<String, Set<Integer>> sentModern = new TreeMap<>();
  /** base-proxy CMD -> appearance key, for the duplicate-model warning. */
  private final Map<Integer, String> appearanceByCmd = new TreeMap<>();
  private final List<String> warnings = new ArrayList<>();

  PackLint(Path staging, Path oldestClient) {
    this.staging = staging;
    this.oldestClient = oldestClient;
  }

  /** {@code since}: first release with the content natively; 1.21.4+ clients only get CMDs for newer content. */
  void expectCmd(String proxy, int cmd, String since) {
    String key = proxy.toLowerCase(Locale.ROOT);
    sentCmds.computeIfAbsent(key, k -> new HashSet<>()).add(cmd);
    if (newerThan(since, "1.21.4")) {
      sentModern.computeIfAbsent(key, k -> new HashSet<>()).add(cmd);
    }
  }

  static boolean newerThan(String ver, String base) {
    String[] a = ver.split("[.]");
    String[] b = base.split("[.]");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = i < a.length ? Integer.parseInt(a[i]) : 0;
      int y = i < b.length ? Integer.parseInt(b[i]) : 0;
      if (x != y) {
        return x > y;
      }
    }
    return false;
  }

  /** CMDs from custom-items.json (incl. spear in-hand/frames), entity-catalog.json and block-catalog.json. */
  void expectCatalogs(JsonObject items, JsonObject entities, JsonObject blocks) {
    JsonElement list = items.has("items") ? items.get("items") : items;
    for (JsonElement el : list.getAsJsonArray()) {
      JsonObject it = el.getAsJsonObject();
      String proxy = it.get("proxy").getAsString();
      String since = it.get("since").getAsString();
      expectCmd(proxy, it.get("cmd").getAsInt(), since);
      if (it.has("spear")) {
        JsonObject spear = it.getAsJsonObject("spear");
        expectCmd(proxy, spear.get("inHandCmd").getAsInt(), since);
        int frames = spear.getAsJsonArray("frames").size();
        for (int i = 0; i < frames; i++) {
          expectCmd(proxy, spear.get("frameCmdBase").getAsInt() + i, since);
        }
      }
    }
    int hurt = entities.get("hurtOffset").getAsInt();
    for (Map.Entry<String, JsonElement> ent : entities.getAsJsonObject("entities").entrySet()) {
      JsonObject def = ent.getValue().getAsJsonObject();
      for (Map.Entry<String, JsonElement> app : def.getAsJsonObject("appearances").entrySet()) {
        JsonObject a = app.getValue().getAsJsonObject();
        int cmd = a.get("cmd").getAsInt();
        appearanceByCmd.put(cmd, app.getKey());
        expectCmd(EntityPackGenerator.BASE_PROXY, cmd, a.get("since").getAsString());
        expectCmd(EntityPackGenerator.BASE_PROXY, cmd + hurt, a.get("since").getAsString());
      }
      for (Map.Entry<String, JsonElement> part : def.getAsJsonObject("parts").entrySet()) {
        JsonObject p = part.getValue().getAsJsonObject();
        int cmd = p.get("cmd").getAsInt();
        expectCmd(EntityPackGenerator.TINT_PROXY, cmd, p.get("since").getAsString());
        expectCmd(EntityPackGenerator.TINT_PROXY, cmd + hurt, p.get("since").getAsString());
      }
    }
    for (Map.Entry<String, JsonElement> blk : blocks.getAsJsonObject("blocks").entrySet()) {
      String since = blk.getValue().getAsJsonObject().get("since").getAsString();
      for (Map.Entry<String, JsonElement> st : blk.getValue().getAsJsonObject().getAsJsonObject("states").entrySet()) {
        JsonObject map = st.getValue().getAsJsonObject();
        if (map.has("display")) {
          expectCmd(map.get("proxy").getAsString(), map.get("display").getAsInt(), since);
        }
      }
    }
  }

  private void lintCmds() throws IOException {
    for (Map.Entry<String, Set<Integer>> ent : sentCmds.entrySet()) {
      String proxy = ent.getKey();
      Set<Integer> legacy = new HashSet<>();
      Path model = staging.resolve("assets/minecraft/models/item/" + proxy + ".json");
      if (Files.exists(model)) {
        JsonObject json = read(model);
        if (json.has("overrides")) {
          for (JsonElement ov : json.getAsJsonArray("overrides")) {
            JsonObject pred = ov.getAsJsonObject().getAsJsonObject("predicate");
            if (pred != null && pred.has("custom_model_data")) {
              legacy.add(pred.get("custom_model_data").getAsInt());
            }
          }
        }
      }
      Set<Integer> modern = new HashSet<>();
      Path items = staging.resolve(OVERLAY + "/assets/minecraft/items/" + proxy + ".json");
      if (Files.exists(items)) {
        collectThresholds(read(items), modern);
      }
      int missLegacy = 0;
      int missModern = 0;
      for (int cmd : ent.getValue()) {
        if (!legacy.contains(cmd)) {
          if (missLegacy++ < 5) {
            problems.add("cmd " + cmd + " on " + proxy + ": no legacy override");
          }
        }
        if (sentModern.getOrDefault(proxy, Set.of()).contains(cmd) && !modern.contains(cmd)) {
          if (missModern++ < 5) {
            problems.add("cmd " + cmd + " on " + proxy + ": no 1.21.4+ items entry");
          }
        }
      }
      if (missLegacy > 5 || missModern > 5) {
        problems.add(proxy + ": " + missLegacy + " legacy / " + missModern + " modern CMDs missing in total");
      }
    }
  }

  /**
   * Different appearance keys of one entity pointing at the same model file usually mean a dimension
   * (variant, colour, collar...) never reached the model, e.g. every baby cat rendering as tabby.
   */
  private void lintDuplicateModels() throws IOException {
    Path model = staging.resolve("assets/minecraft/models/item/" + EntityPackGenerator.BASE_PROXY.toLowerCase(Locale.ROOT) + ".json");
    if (!Files.exists(model) || appearanceByCmd.isEmpty()) {
      return;
    }
    Map<String, List<String>> byModel = new TreeMap<>();
    for (JsonElement ov : read(model).getAsJsonArray("overrides")) {
      JsonObject obj = ov.getAsJsonObject();
      JsonObject pred = obj.getAsJsonObject("predicate");
      if (pred == null || !pred.has("custom_model_data")) {
        continue;
      }
      String key = appearanceByCmd.get(pred.get("custom_model_data").getAsInt());
      if (key != null) {
        byModel.computeIfAbsent(obj.get("model").getAsString(), k -> new ArrayList<>()).add(key);
      }
    }
    for (Map.Entry<String, List<String>> ent : byModel.entrySet()) {
      if (ent.getValue().size() > 1) {
        warnings.add(ent.getKey() + " shared by " + ent.getValue());
      }
    }
  }

  List<String> warnings() {
    return warnings;
  }

  private static void collectThresholds(JsonElement el, Set<Integer> out) {
    if (el.isJsonArray()) {
      for (JsonElement child : el.getAsJsonArray()) {
        collectThresholds(child, out);
      }
    } else if (el.isJsonObject()) {
      JsonObject obj = el.getAsJsonObject();
      if (obj.has("threshold") && obj.has("model")) {
        out.add((int) obj.get("threshold").getAsFloat());
      }
      for (Map.Entry<String, JsonElement> ent : obj.entrySet()) {
        collectThresholds(ent.getValue(), out);
      }
    }
  }

  /** True for a PNG a 1.16 client would hold as a one-channel image (L or LA) or at 16 bits per channel. */
  static boolean needsRgba(Path png) throws IOException {
    byte[] head = new byte[26];
    try (var in = Files.newInputStream(png)) {
      if (in.readNBytes(head, 0, head.length) < head.length) {
        return false;
      }
    }
    int depth = head[24] & 0xFF;
    int color = head[25] & 0xFF;
    return color == 0 || color == 4 || depth == 16;
  }

  static JsonObject read(Path file) throws IOException {
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  void run() throws IOException {
    lintCmds();
    lintDuplicateModels();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(staging)) {
      files = walk.filter(p -> p.toString().endsWith(".json") && staging.relativize(p).toString().contains("assets")).toList();
    }
    for (Path file : files) {
      String rel = staging.relativize(file).toString().replace('\\', '/');
      boolean modern = rel.startsWith(OVERLAY + "/") || rel.startsWith(ResourcePackAssembler.EXACT_ROTATION_OVERLAY + "/");
      String kind = kindOf(rel);
      if (kind == null) {
        continue;
      }
      JsonObject json;
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        json = JsonParser.parseReader(reader).getAsJsonObject();
      } catch (RuntimeException ex) {
        problems.add(rel + ": unreadable json (" + ex.getMessage() + ")");
        continue;
      }
      switch (kind) {
        case "models" -> lintModel(rel, json, modern);
        case "blockstates" -> collectModels(json, rel, modern);
        case "items" -> collectModels(json, rel, true);
        default -> {
        }
      }
    }
    try (Stream<Path> walk = Files.walk(staging)) {
      for (Path png : walk.filter(p -> p.toString().endsWith(".png")).toList()) {
        if (needsRgba(png)) {
          problems.add(staging.relativize(png).toString().replace(java.io.File.separatorChar, '/') + ": grayscale or 16-bit png (shows red on 1.16)");
        }
      }
    }
    if (!problems.isEmpty()) {
      int shown = Math.min(40, problems.size());
      throw new IOException("Resource pack lint: " + problems.size() + " dangling reference(s):\n  "
          + String.join("\n  ", problems.subList(0, shown)) + (shown < problems.size() ? "\n  ..." : ""));
    }
  }

  private static String kindOf(String rel) {
    String[] parts = rel.split("/");
    for (int i = 0; i + 2 < parts.length; i++) {
      if (parts[i].equals("assets")) {
        return parts[i + 2];
      }
    }
    return null;
  }

  private void lintModel(String rel, JsonObject model, boolean modern) {
    if (model.has("parent")) {
      String parent = model.get("parent").getAsString();
      if (!parent.startsWith("builtin/") && !parent.startsWith("minecraft:builtin/")) {
        require(rel, "models", parent, ".json", modern);
      }
    }
    if (model.has("textures")) {
      for (Map.Entry<String, JsonElement> tex : model.getAsJsonObject("textures").entrySet()) {
        String val = tex.getValue().getAsString();
        if (!val.startsWith("#")) {
          require(rel, "textures", val, ".png", modern);
        }
      }
    }
    if (model.has("overrides")) {
      for (JsonElement ov : model.getAsJsonArray("overrides")) {
        JsonObject pred = ov.getAsJsonObject().getAsJsonObject("predicate");
        // vanilla 1.20 trim overrides only ever match on clients that ship those models
        boolean trim = pred != null && pred.has("trim_type");
        require(rel, "models", ov.getAsJsonObject().get("model").getAsString(), ".json", modern || trim);
      }
    }
  }

  /** Every "model" string anywhere in a blockstate or item definition. */
  private void collectModels(JsonElement el, String rel, boolean modern) {
    if (el.isJsonArray()) {
      for (JsonElement child : el.getAsJsonArray()) {
        collectModels(child, rel, modern);
      }
    } else if (el.isJsonObject()) {
      for (Map.Entry<String, JsonElement> ent : el.getAsJsonObject().entrySet()) {
        if (ent.getKey().equals("model") && ent.getValue().isJsonPrimitive()) {
          require(rel, "models", ent.getValue().getAsString(), ".json", modern);
        } else {
          collectModels(ent.getValue(), rel, modern);
        }
      }
    }
  }

  private void require(String from, String dir, String ref, String ext, boolean modern) {
    int colon = ref.indexOf(':');
    String ns = colon < 0 ? "minecraft" : ref.substring(0, colon);
    String path = colon < 0 ? ref : ref.substring(colon + 1);
    String rel = "assets/" + ns + "/" + dir + "/" + path + ext;
    if (Files.exists(staging.resolve(rel)) || Files.exists(staging.resolve(OVERLAY).resolve(rel))) {
      return;
    }
    if (ns.equals("minecraft") && (modern || oldestClient == null || Files.exists(oldestClient.resolve(rel)))) {
      return;
    }
    problems.add(from + " -> " + ref);
  }
}
