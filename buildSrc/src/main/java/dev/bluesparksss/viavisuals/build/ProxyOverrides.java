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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Collects CustomModelData overrides per proxy item and writes them for both pack eras:
 * legacy {@code models/item/<proxy>.json} overrides (base file and predicates from the 1.20.4 client)
 * and the 1.21.4+ {@code items/<proxy>.json} range_dispatch in the modern overlay (fallback from the
 * 1.21.4 client, the oldest format every overlay reader understands).
 */
final class ProxyOverrides {

  static final String MODERN_OVERLAY_DIR = "modern_item_select";
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  record Entry(int cmd, String model, JsonObject predicate, JsonObject modern, boolean legacyOnly) {
  }

  private final Map<String, List<Entry>> byProxy = new LinkedHashMap<>();

  void add(String proxy, int cmd, String model) {
    add(proxy, cmd, model, null, null, false);
  }

  void add(String proxy, int cmd, String model, JsonObject extraPredicate, JsonObject modern, boolean legacyOnly) {
    byProxy.computeIfAbsent(proxy.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
        .add(new Entry(cmd, model, extraPredicate, modern, legacyOnly));
  }

  void write(Path staging, Path legacyAssets, Path modernAssets, Consumer<String> logger) throws IOException {
    for (Map.Entry<String, List<Entry>> ent : byProxy.entrySet()) {
      String proxy = ent.getKey();
      List<Entry> entries = new ArrayList<>(ent.getValue());
      entries.sort(Comparator.comparingInt(Entry::cmd));
      checkDuplicates(proxy, entries);
      // legacy clients test overrides from the last one up and stop at the first match, and a custom_model_data
      // predicate matches every value >= its own: the list must stay ascending, and the cheapest CMDs to find are
      // the highest ones (the catalog generators give the common blocks those)
      writeLegacy(staging, legacyAssets, proxy, entries);
      writeModern(staging, modernAssets, proxy, entries);
      logger.accept("Proxy " + proxy + ": " + entries.size() + " CustomModelData override(s)");
    }
  }

  private static void checkDuplicates(String proxy, List<Entry> entries) throws IOException {
    Set<String> seen = new HashSet<>();
    for (Entry e : entries) {
      String keyStr = e.cmd() + "|" + (e.predicate() == null ? "" : e.predicate().toString());
      if (!seen.add(keyStr)) {
        throw new IOException("Duplicate CustomModelData " + e.cmd() + " on proxy " + proxy);
      }
    }
  }

  private static void writeLegacy(Path staging, Path legacyAssets, String proxy, List<Entry> entries) throws IOException {
    Path src = legacyAssets.resolve("minecraft/models/item/" + proxy + ".json");
    if (!Files.exists(src)) {
      throw new IOException("No legacy base model for proxy " + proxy + " at " + src);
    }
    JsonObject root = readJson(src);
    JsonArray overrides = root.has("overrides") ? root.getAsJsonArray("overrides") : new JsonArray();
    for (Entry e : entries) {
      JsonObject pred = e.predicate() == null ? new JsonObject() : e.predicate().deepCopy();
      pred.addProperty("custom_model_data", e.cmd());
      JsonObject ov = new JsonObject();
      ov.add("predicate", pred);
      ov.addProperty("model", e.model());
      overrides.add(ov);
    }
    root.add("overrides", overrides);
    Path dst = staging.resolve("assets/minecraft/models/item/" + proxy + ".json");
    Files.createDirectories(dst.getParent());
    Files.writeString(dst, GSON.toJson(root), StandardCharsets.UTF_8);
  }

  private static void writeModern(Path staging, Path modernAssets, String proxy, List<Entry> entries) throws IOException {
    Path fallbackSrc = modernAssets.resolve("minecraft/items/" + proxy + ".json");
    if (!Files.exists(fallbackSrc)) {
      throw new IOException("No 1.21.4 item definition for proxy " + proxy + " at " + fallbackSrc);
    }
    JsonObject fallback = readJson(fallbackSrc).getAsJsonObject("model");
    Set<Integer> used = new HashSet<>();
    for (Entry e : entries) {
      if (!e.legacyOnly()) {
        used.add(e.cmd());
      }
    }
    JsonArray dispatch = new JsonArray();
    for (Entry e : entries) {
      if (e.legacyOnly()) {
        continue;
      }
      JsonObject entry = new JsonObject();
      entry.addProperty("threshold", e.cmd());
      JsonObject model = e.modern();
      if (model == null) {
        model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", e.model());
      }
      entry.add("model", model);
      dispatch.add(entry);
      // range_dispatch picks the highest threshold <= value: close the gap so unknown CMDs fall back
      if (!used.contains(e.cmd() + 1)) {
        JsonObject gap = new JsonObject();
        gap.addProperty("threshold", e.cmd() + 1);
        gap.add("model", fallback);
        dispatch.add(gap);
      }
    }
    JsonObject range = new JsonObject();
    range.addProperty("type", "minecraft:range_dispatch");
    range.addProperty("property", "minecraft:custom_model_data");
    range.addProperty("index", 0);
    range.add("entries", dispatch);
    range.add("fallback", fallback);
    JsonObject root = new JsonObject();
    root.add("model", range);
    Path dst = staging.resolve(MODERN_OVERLAY_DIR + "/assets/minecraft/items/" + proxy + ".json");
    Files.createDirectories(dst.getParent());
    Files.writeString(dst, GSON.toJson(root), StandardCharsets.UTF_8);
  }

  static JsonObject modelNode(String model) {
    JsonObject node = new JsonObject();
    node.addProperty("type", "minecraft:model");
    node.addProperty("model", model);
    return node;
  }

  private static JsonObject readJson(Path path) throws IOException {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      JsonElement el = JsonParser.parseReader(reader);
      return el.getAsJsonObject();
    }
  }
}
