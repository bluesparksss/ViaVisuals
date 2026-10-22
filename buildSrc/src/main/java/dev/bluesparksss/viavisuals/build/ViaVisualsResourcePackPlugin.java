package dev.bluesparksss.viavisuals.build;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Registers {@code fetchVanillaAssets} (official Minecraft files into {@code .vanilla-cache/}) and
 * {@code assembleResourcePack} used by processResources / shadowJar.
 */
public final class ViaVisualsResourcePackPlugin implements Plugin<Project> {

  @Override
  public void apply(Project project) {
    TaskProvider<?> fetch = project.getTasks().register("fetchVanillaAssets", task -> {
      task.setGroup("build");
      task.setDescription("Download the official Minecraft client files the pack is built from into .vanilla-cache");
      task.doLast(t -> {
        try {
          cache(project).fetchAll();
        } catch (IOException ex) {
          throw new RuntimeException("fetchVanillaAssets failed: " + ex.getMessage(), ex);
        }
      });
    });

    TaskProvider<?> assemble = project.getTasks().register("assembleResourcePack", task -> {
      task.setGroup("build");
      task.setDescription("Assemble the ViaVisuals resource pack zip + SHA-1 + generated entity catalog");
      task.dependsOn(fetch);
      task.getOutputs().upToDateWhen(t -> false);
      task.doLast(t -> {
        Path dir = project.getProjectDir().toPath();
        Path build = project.getLayout().getBuildDirectory().get().getAsFile().toPath();
        Path src = dir.resolve("resource-pack-src");
        ResourcePackAssembler.Inputs in = new ResourcePackAssembler.Inputs(
            cache(project),
            src.resolve("overlay"),
            dir.resolve("src/main/resources/custom-items.json"),
            src.resolve("entity-specs.json"),
            src.resolve("vanilla-geometry"),
            src.resolve("vanilla-posed"),
            src.resolve("vanilla-rigs"),
            src.resolve("vanilla-animations"),
            src.resolve("texture-history.json"),
            src.resolve("block-pack.json"),
            dir.resolve("src/main/resources/registry-since.json"),
            Boolean.parseBoolean(prop(project, "viavisuals.backportMusicDiscs", "false")),
            build.resolve("resource-pack/staging"),
            build.resolve("libs/ViaVisuals-resource-pack.zip"),
            build.resolve("libs/ViaVisuals-resource-pack.zip.sha1"),
            build.resolve("generated/resources/entity-catalog.json"),
            build.resolve("generated/resources")
        );
        try {
          new ResourcePackAssembler(msg -> project.getLogger().lifecycle(msg)).assemble(in);
        } catch (Exception ex) {
          throw new RuntimeException("assembleResourcePack failed: " + ex.getMessage(), ex);
        }
      });
    });

    project.getTasks().matching(t -> t.getName().equals("processResources")).configureEach(t ->
        t.dependsOn(assemble)
    );
  }

  private static VanillaCache cache(Project project) {
    return new VanillaCache(project.getProjectDir().toPath().resolve(".vanilla-cache"),
        prop(project, "viavisuals.vanillaVersion", "26.3"), msg -> project.getLogger().lifecycle(msg));
  }

  private static String prop(Project project, String key, String fallback) {
    Object val = project.findProperty(key);
    if (val == null) {
      return fallback;
    }
    String text = String.valueOf(val).trim();
    return text.isEmpty() ? fallback : text;
  }
}
