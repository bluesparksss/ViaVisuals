package dev.bluesparksss.viavisuals.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Sound events and translation keys added after 1.16:
 * every new (non-music) sound event is re-registered as {@code viavisuals:<event>} with its vanilla
 * .ogg files (Mojang asset objects, cached), and new en_us keys go into assets/minecraft/lang so
 * older clients show real names (they fall back to en_us for keys their language lacks).
 */
final class SoundLangGenerator {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private final VanillaAssets assets;
  private final VanillaCache vanilla;
  private final Consumer<String> logger;

  SoundLangGenerator(VanillaAssets assets, VanillaCache vanilla, Consumer<String> logger) {
    this.assets = assets;
    this.vanilla = vanilla;
    this.logger = logger;
  }

  void generate(Path registrySince, Path legacyLang, Path staging, boolean musicDiscs) throws IOException {
    JsonObject since = read(registrySince);
    writeLang(legacyLang, staging);
    JsonObject index = vanilla.assetIndex();
    JsonObject objects = index.getAsJsonObject("objects");
    JsonObject vanillaSounds = read(object(objects, "minecraft/sounds.json"));
    JsonObject out = new JsonObject();
    Set<String> files = new TreeSet<>();
    for (Map.Entry<String, JsonElement> ev : since.getAsJsonObject("sounds").entrySet()) {
      String event = ev.getKey();
      if (event.startsWith("music.") || (!musicDiscs && event.startsWith("music_disc."))) {
        continue;
      }
      JsonObject def = vanillaSounds.getAsJsonObject(event);
      if (def == null) {
        continue;
      }
      JsonObject copy = def.deepCopy();
      JsonArray list = new JsonArray();
      for (JsonElement s : def.getAsJsonArray("sounds")) {
        JsonObject entry = s.isJsonPrimitive() ? new JsonObject() : s.getAsJsonObject().deepCopy();
        String name = s.isJsonPrimitive() ? s.getAsString() : entry.get("name").getAsString();
        boolean isEvent = entry.has("type") && "event".equals(entry.get("type").getAsString());
        String path = VanillaAssets.path(name);
        if (!isEvent) {
          files.add(path);
        }
        entry.addProperty("name", VanillaAssets.NS + ":" + path);
        list.add(entry);
      }
      copy.add("sounds", list);
      copy.remove("replace");
      out.add(event, copy);
    }
    int copied = 0;
    for (String path : files) {
      Path dst = staging.resolve("assets/" + VanillaAssets.NS + "/sounds/" + path + ".ogg");
      Path src = object(objects, "minecraft/sounds/" + path + ".ogg");
      if (src != null) {
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        copied++;
      }
    }
    Path soundsJson = staging.resolve("assets/" + VanillaAssets.NS + "/sounds.json");
    Files.createDirectories(soundsJson.getParent());
    Files.writeString(soundsJson, GSON.toJson(out), StandardCharsets.UTF_8);
    logger.accept("Backported " + out.size() + " sound event(s) with " + copied + " sound file(s)");
  }

  /** en_us keys missing from the oldest supported client's language file. */
  private void writeLang(Path legacyLang, Path staging) throws IOException {
    JsonObject modern = assets.readJson("assets/minecraft/lang/en_us.json")
        .orElseThrow(() -> new IOException("vanilla en_us.json not found"));
    JsonObject old = read(legacyLang);
    JsonObject out = new JsonObject();
    for (Map.Entry<String, JsonElement> ent : modern.entrySet()) {
      if (!old.has(ent.getKey())) {
        out.add(ent.getKey(), ent.getValue());
      }
    }
    Path dst = staging.resolve("assets/minecraft/lang/en_us.json");
    Files.createDirectories(dst.getParent());
    Files.writeString(dst, GSON.toJson(out), StandardCharsets.UTF_8);
    logger.accept("Added " + out.size() + " translation key(s) to en_us");
  }

  private Path object(JsonObject objects, String name) throws IOException {
    JsonObject obj = objects.getAsJsonObject(name);
    if (obj == null) {
      return null;
    }
    String hash = obj.get("hash").getAsString();
    return vanilla.object(hash);
  }

  private static JsonObject read(Path path) throws IOException {
    return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
  }
}
