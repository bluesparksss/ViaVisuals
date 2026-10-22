package dev.bluesparksss.viavisuals.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the single ViaVisuals pack served to every client older than the server. */
final class ResourcePackAssembler {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  /** 1.21.4 introduced assets/minecraft/items definitions. */
  /** Clients 1.21.2 - 1.21.5 still read the armor shader json; the base pack's version is for 1.17 - 1.21.1 only. */
  private static final String ARMOR_SHADER_RESTORE = "armor_shader_restore";
  private static final int MODERN_ITEM_FORMAT = 46;
  /** 1.21.11 (format 75) parses Euler x/y/z element rotations at any angle. */
  private static final int EXACT_ROTATION_FORMAT = 75;
  static final String EXACT_ROTATION_OVERLAY = "exact_rotations";

  record Inputs(
      VanillaCache vanilla,
      Path overlayDir,
      Path customItemsFile,
      Path entitySpecsFile,
      Path vanillaGeometryDir,
      Path vanillaPosedDir,
      Path vanillaRigsDir,
      Path vanillaAnimationsDir,
      Path textureHistoryFile,
      Path blockPackFile,
      Path registrySinceFile,
      boolean musicDiscs,
      Path stagingDir,
      Path outputZip,
      Path sha1File,
      Path entityCatalogOut,
      Path animOutDir
  ) {
  }

  private final Consumer<String> logger;

  ResourcePackAssembler(Consumer<String> logger) {
    this.logger = logger;
  }

  void assemble(Inputs in) throws IOException {
    Path main = in.vanilla().mainClient();
    Path oldest = in.vanilla().client(VanillaCache.OLDEST);
    Path legacy = in.vanilla().client(VanillaCache.LEGACY_MODELS).resolve("assets");
    Path modern = in.vanilla().client(VanillaCache.MODERN_ITEMS).resolve("assets");
    deleteRecursively(in.stagingDir());
    Files.createDirectories(in.stagingDir());
    copyDirectory(in.overlayDir(), in.stagingDir());
    copyVanillaCompanions(in.stagingDir(), main, in.vanilla().client(VanillaCache.MODERN_ITEMS));

    VanillaAssets assets = new VanillaAssets(List.of(main), in.stagingDir());
    ProxyOverrides overrides = new ProxyOverrides();

    new ItemPackGenerator(assets, in.vanillaGeometryDir(), legacy, overrides, logger).generate(in.customItemsFile());

    EntityPackGenerator entities = new EntityPackGenerator(assets, in.vanillaPosedDir(), in.textureHistoryFile(),
        in.registrySinceFile(), logger);
    AnimatedPackGenerator animated = new AnimatedPackGenerator(entities, assets, in.vanillaRigsDir(),
        in.vanillaAnimationsDir(), logger);
    if (animated.available()) {
      entities.enableAnimated(animated);
    }
    JsonObject catalog = entities.generate(in.entitySpecsFile());
    if (animated.available()) {
      animated.write(in.animOutDir());
    }
    for (EntityPackGenerator.Override ov : entities.overrides()) {
      JsonObject modernNode = ProxyOverrides.modelNode(ov.model());
      if (ov.tinted()) {
        JsonArray tints = new JsonArray();
        JsonObject dye = new JsonObject();
        dye.addProperty("type", "minecraft:dye");
        dye.addProperty("default", -6265536);
        tints.add(dye);
        modernNode.add("tints", tints);
      }
      overrides.add(ov.proxy(), ov.cmd(), ov.model(), null, modernNode, false);
    }
    EntityPackGenerator.writeCatalog(catalog, in.entityCatalogOut());

    new BlockPackGenerator(assets, oldest.resolve("assets/minecraft/blockstates"), overrides, logger).generate(in.blockPackFile());

    new SoundLangGenerator(assets, in.vanilla(), logger).generate(in.registrySinceFile(),
        oldest.resolve("assets/minecraft/lang/en_us.json"), in.stagingDir(), in.musicDiscs());

    if (!VanillaGeometry.POOR_FIT.isEmpty()) {
      logger.accept("poor single-axis fits (part, degrees): "
          + String.join("; ", VanillaGeometry.POOR_FIT.stream().distinct().toList()));
    }
    if (!VanillaGeometry.DROPPED_FACES.isEmpty()) {
      logger.accept("WARNING cube faces dropped (part:face): "
          + String.join("; ", VanillaGeometry.DROPPED_FACES.stream().distinct().toList()));
    }
    overrides.write(in.stagingDir(), legacy, modern, logger);
    normalizeTextures(in.stagingDir());
    writePackMcmeta(in.stagingDir());
    PackLint lint = new PackLint(in.stagingDir(), oldest);
    lint.expectCatalogs(PackLint.read(in.customItemsFile()), catalog,
        PackLint.read(in.customItemsFile().resolveSibling("block-catalog.json")));
    lint.run();
    for (String warn : lint.warnings()) {
      logger.accept("lint warning: duplicate entity model " + warn);
    }

    Files.createDirectories(in.outputZip().getParent());
    zipDirectory(in.stagingDir(), in.outputZip());
    String sha1 = sha1Hex(in.outputZip());
    Files.writeString(in.sha1File(), sha1 + System.lineSeparator(), StandardCharsets.UTF_8);
    logger.accept("Resource pack " + in.outputZip() + " SHA-1 " + sha1);
  }

  /**
   * A 1.16 client keeps a grayscale PNG as a one-channel image, and its texture upload shows it as red (block
   * break particles of leaf litter and flowers came out red). Every L/LA or 16-bit PNG is rewritten as 8-bit RGBA,
   * which is what newer clients convert it to anyway.
   */
  private void normalizeTextures(Path stagingDir) throws IOException {
    List<Path> files;
    try (var stream = Files.walk(stagingDir)) {
      files = stream.filter(p -> p.toString().endsWith(".png")).toList();
    }
    int converted = 0;
    for (Path file : files) {
      if (!PackLint.needsRgba(file)) {
        continue;
      }
      java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(file.toFile());
      if (img != null) {
        javax.imageio.ImageIO.write(Images.copy(img), "png", file.toFile());
        converted++;
      }
    }
    logger.accept("normalized " + converted + " grayscale or 16-bit PNG(s) to RGBA");
  }

  /**
   * supported_formats/min/max cover every client; pack_format must be >= 15 or 1.21.9+ clients reject a
   * multi-version pack outright (PackFormat.validatePackFormatForRange). Older clients only use it to
   * flag the pack as made for a newer version, which server packs ignore.
   */
  private void writePackMcmeta(Path stagingDir) throws IOException {
    JsonObject pack = new JsonObject();
    pack.addProperty("pack_format", 15);
    pack.addProperty("description", "ViaVisuals: newer items, blocks and mobs for older clients");
    JsonObject supported = new JsonObject();
    supported.addProperty("min_inclusive", 6);
    supported.addProperty("max_inclusive", 9999);
    pack.add("supported_formats", supported);
    pack.addProperty("min_format", 6);
    pack.addProperty("max_format", 9999);

    JsonArray entries = new JsonArray();
    entries.add(overlayEntry(ProxyOverrides.MODERN_OVERLAY_DIR, MODERN_ITEM_FORMAT));
    if (Files.isDirectory(stagingDir.resolve(EXACT_ROTATION_OVERLAY))) {
      entries.add(overlayEntry(EXACT_ROTATION_OVERLAY, EXACT_ROTATION_FORMAT));
    }
    if (Files.isDirectory(stagingDir.resolve(ARMOR_SHADER_RESTORE))) {
      entries.add(overlayEntry(ARMOR_SHADER_RESTORE, 42, 54));
    }
    JsonObject overlays = new JsonObject();
    overlays.add("entries", entries);

    JsonObject root = new JsonObject();
    root.add("pack", pack);
    root.add("overlays", overlays);
    Files.writeString(stagingDir.resolve("pack.mcmeta"), GSON.toJson(root), StandardCharsets.UTF_8);
  }

  /** Vanilla files the overlay ships unchanged: the restored armor shader json and the emissive maps' animation metadata. */
  private static void copyVanillaCompanions(Path staging, Path main, Path modernClient) throws IOException {
    Path shader = staging.resolve(ARMOR_SHADER_RESTORE + "/assets/minecraft/shaders/core/rendertype_armor_cutout_no_cull.json");
    Files.createDirectories(shader.getParent());
    Files.copy(modernClient.resolve(VanillaCache.ARMOR_SHADER_JSON), shader, StandardCopyOption.REPLACE_EXISTING);
    Path blocks = staging.resolve("assets/" + VanillaAssets.NS + "/textures/block");
    if (!Files.isDirectory(blocks)) {
      return;
    }
    List<Path> maps;
    try (var stream = Files.list(blocks)) {
      maps = stream.filter(p -> p.getFileName().toString().matches(".+_[es]\\.png")).sorted().toList();
    }
    for (Path map : maps) {
      String name = map.getFileName().toString();
      Path meta = main.resolve("assets/minecraft/textures/block/" + name.substring(0, name.length() - 6) + ".png.mcmeta");
      if (Files.exists(meta)) {
        Files.copy(meta, map.resolveSibling(name + ".mcmeta"), StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static void copyDirectory(Path source, Path target) throws IOException {
    if (!Files.isDirectory(source)) {
      return;
    }
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        Files.createDirectories(target.resolve(source.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void zipDirectory(Path sourceDir, Path zipFile) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
      List<Path> files;
      try (var stream = Files.walk(sourceDir)) {
        files = stream.filter(Files::isRegularFile).sorted().toList();
      }
      for (Path file : files) {
        ZipEntry entry = new ZipEntry(sourceDir.relativize(file).toString().replace('\\', '/'));
        entry.setTime(0L);
        zip.putNextEntry(entry);
        Files.copy(file, zip);
        zip.closeEntry();
      }
    }
  }

  private static JsonObject overlayEntry(String dir, int minFormat) {
    return overlayEntry(dir, minFormat, 9999);
  }

  private static JsonObject overlayEntry(String dir, int minFormat, int maxFormat) {
    JsonObject formats = new JsonObject();
    formats.addProperty("min_inclusive", minFormat);
    formats.addProperty("max_inclusive", maxFormat);
    JsonObject entry = new JsonObject();
    entry.addProperty("directory", dir);
    entry.add("formats", formats);
    entry.addProperty("min_format", minFormat);
    entry.addProperty("max_format", maxFormat);
    return entry;
  }

  static String sha1Hex(Path file) throws IOException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] buf = new byte[65536];
      int n;
      while ((n = in.read(buf)) > 0) {
        digest.update(buf, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IOException(ex);
    }
  }

  static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (var stream = Files.walk(path)) {
      for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
