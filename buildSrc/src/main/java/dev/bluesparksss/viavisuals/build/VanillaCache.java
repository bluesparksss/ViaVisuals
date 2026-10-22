package dev.bluesparksss.viavisuals.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Official Minecraft files the pack is built from, downloaded from Mojang into {@code .vanilla-cache/} and checked
 * against the SHA-1s Mojang publishes. Nothing from Minecraft is stored in the repository.
 *
 * <p>Where each input comes from:
 * <ul>
 *   <li>{@code <main>/assets/minecraft/**}: models, items, textures, lang of the newest supported release.</li>
 *   <li>{@value #OLDEST}: the oldest supported client. Its blockstates are the bases of the carrier blocks, its
 *       en_us.json tells which translation keys old clients lack, and the pack lint checks references against it.</li>
 *   <li>{@value #LEGACY_MODELS} {@code models/item/}: the item models the legacy CustomModelData overrides extend.</li>
 *   <li>{@value #MODERN_ITEMS} {@code items/}: the item definitions the 1.21.4+ overlay extends, and
 *       {@code rendertype_armor_translucent.json}, restored for 1.21.2 - 1.21.5 clients as the armor shader json.</li>
 *   <li>Sounds: Mojang's asset index and objects of the main release.</li>
 * </ul>
 */
final class VanillaCache {

  static final String OLDEST = "1.16.5";
  static final String LEGACY_MODELS = "1.20.4";
  static final String MODERN_ITEMS = "1.21.4";
  static final String ARMOR_SHADER_JSON = "assets/minecraft/shaders/core/rendertype_armor_translucent.json";

  private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
  private static final String OBJECTS = "https://resources.download.minecraft.net/";
  private static final String MARKER = ".complete";

  private final Path root;
  private final String main;
  private final Consumer<String> logger;
  private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

  VanillaCache(Path root, String main, Consumer<String> logger) {
    this.root = root;
    this.main = main;
    this.logger = logger;
  }

  /** Version -> jar entry prefixes extracted from its client jar. */
  Map<String, List<String>> extracts() {
    Map<String, List<String>> out = new LinkedHashMap<>();
    out.put(main, List.of("assets/minecraft/"));
    out.put(OLDEST, List.of("assets/minecraft/"));
    out.put(LEGACY_MODELS, List.of("assets/minecraft/models/item/"));
    out.put(MODERN_ITEMS, List.of("assets/minecraft/items/", ARMOR_SHADER_JSON));
    return out;
  }

  void fetchAll() throws IOException {
    for (Map.Entry<String, List<String>> ent : extracts().entrySet()) {
      extract(ent.getKey(), ent.getValue());
    }
  }

  /** The extracted client of a version: a folder holding {@code assets/minecraft/...}. */
  Path client(String version) throws IOException {
    Path dir = root.resolve(version);
    if (!Files.exists(dir.resolve(MARKER))) {
      throw new IOException("Minecraft " + version + " files are missing from " + root + " (run ./gradlew fetchVanillaAssets)");
    }
    return dir;
  }

  Path mainClient() throws IOException {
    return client(main);
  }

  private void extract(String version, List<String> prefixes) throws IOException {
    Path dir = root.resolve(version);
    JsonObject jar = versionJson(version).getAsJsonObject("downloads").getAsJsonObject("client");
    String sha1 = jar.get("sha1").getAsString();
    String marker = sha1 + "\n" + String.join("\n", prefixes);
    if (Files.exists(dir.resolve(MARKER)) && Files.readString(dir.resolve(MARKER)).equals(marker)) {
      return;
    }
    ResourcePackAssembler.deleteRecursively(dir);
    Path file = download(jar.get("url").getAsString(), root.resolve("jars").resolve(version + ".jar"), sha1);
    int count = 0;
    try (ZipFile zip = new ZipFile(file.toFile())) {
      for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
        String name = entry.getName();
        if (entry.isDirectory() || prefixes.stream().noneMatch(name::startsWith)) {
          continue;
        }
        Path dst = dir.resolve(name).normalize();
        if (!dst.startsWith(dir)) {
          throw new IOException("Unexpected jar entry " + name);
        }
        Files.createDirectories(dst.getParent());
        try (InputStream in = zip.getInputStream(entry)) {
          Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        count++;
      }
    }
    Files.delete(file);
    Files.writeString(dir.resolve(MARKER), marker);
    logger.accept("Extracted " + count + " files from the official Minecraft " + version + " client jar");
  }

  /** Mojang asset index of the main release (sounds and their objects). */
  JsonObject assetIndex() throws IOException {
    JsonObject ref = versionJson(main).getAsJsonObject("assetIndex");
    return read(download(ref.get("url").getAsString(),
        root.resolve("indexes").resolve(ref.get("id").getAsString() + ".json"), ref.get("sha1").getAsString()));
  }

  Path object(String hash) throws IOException {
    return download(OBJECTS + hash.substring(0, 2) + "/" + hash, root.resolve("objects").resolve(hash), hash);
  }

  private JsonObject versionJson(String version) throws IOException {
    Path cached = root.resolve("versions").resolve(version + ".json");
    if (Files.exists(cached)) {
      return read(cached);
    }
    JsonObject entry = manifestEntry(version, false);
    if (entry == null) {
      entry = manifestEntry(version, true);
    }
    if (entry == null) {
      throw new IOException("Mojang's version manifest has no Minecraft " + version);
    }
    return read(download(entry.get("url").getAsString(), cached, entry.get("sha1").getAsString()));
  }

  private JsonObject manifestEntry(String version, boolean refresh) throws IOException {
    Path file = root.resolve("version_manifest_v2.json");
    if (refresh) {
      Files.deleteIfExists(file);
    }
    for (JsonElement el : read(download(MANIFEST, file, null)).getAsJsonArray("versions")) {
      JsonObject obj = el.getAsJsonObject();
      if (version.equals(obj.get("id").getAsString())) {
        return obj;
      }
    }
    return null;
  }

  /** Downloads {@code url} to {@code dst} unless a verified copy exists; {@code sha1} null = the manifest itself. */
  private Path download(String url, Path dst, String sha1) throws IOException {
    if (Files.exists(dst) && (sha1 == null || sha1.equals(ResourcePackAssembler.sha1Hex(dst)))) {
      return dst;
    }
    Files.createDirectories(dst.getParent());
    Path tmp = Files.createTempFile(dst.getParent(), "dl", ".tmp");
    try {
      HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
          HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = resp.body()) {
        if (resp.statusCode() != 200) {
          throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
      }
      String got = sha1 == null ? null : ResourcePackAssembler.sha1Hex(tmp);
      if (sha1 != null && !sha1.equals(got)) {
        throw new IOException("SHA-1 mismatch for " + url + " (expected " + sha1 + ", got " + got + ")");
      }
      Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
      return dst;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException(ex);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static JsonObject read(Path path) throws IOException {
    return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
  }
}
