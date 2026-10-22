package dev.bluesparksss.viavisuals.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.compat.RegistrySince;
import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public final class ItemCatalog {

  public record SpearSpec(int inHandCmd, int frameCmdBase, int[] timeline, int delayTicks, int attackDurationTicks) {

    public int frameCmdAt(int ticksHeld) {
      int idx = Math.max(0, Math.min(timeline.length - 1, ticksHeld));
      return frameCmdBase + timeline[idx];
    }
  }

  public record ItemEntry(
      String id,
      Material material,
      ProtocolVersion since,
      Material proxy,
      int cmd,
      Integer tint,
      SpearSpec spear,
      boolean spawnEgg,
      boolean retexture
  ) {
  }

  private final Map<Material, ItemEntry> byMaterial;
  private final Map<Long, ItemEntry> byProxy;

  private ItemCatalog(Map<Material, ItemEntry> byMaterial, Map<Long, ItemEntry> byProxy) {
    this.byMaterial = byMaterial;
    this.byProxy = byProxy;
  }

  public static ItemCatalog load(InputStream input, Logger logger) throws IOException {
    JsonObject root;
    try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      root = JsonParser.parseReader(reader).getAsJsonObject();
    }
    Map<Material, ItemEntry> byMat = new EnumMap<>(Material.class);
    Map<Long, ItemEntry> byPrx = new HashMap<>();
    int skipped = 0;
    for (JsonElement el : root.getAsJsonArray("items")) {
      JsonObject obj = el.getAsJsonObject();
      String id = obj.get("id").getAsString();
      Material mat = Material.matchMaterial(id);
      Material proxy = Material.matchMaterial(obj.get("proxy").getAsString());
      ProtocolVersion since = RegistrySince.protocolFor(obj.get("since").getAsString());
      if (mat == null || proxy == null || since == null) {
        skipped++;
        continue;
      }
      SpearSpec spear = null;
      if (obj.has("spear")) {
        JsonObject sp = obj.getAsJsonObject("spear");
        JsonArray tl = sp.getAsJsonArray("timeline");
        int[] timeline = new int[tl.size()];
        for (int i = 0; i < timeline.length; i++) {
          timeline[i] = tl.get(i).getAsInt();
        }
        spear = new SpearSpec(sp.get("inHandCmd").getAsInt(), sp.get("frameCmdBase").getAsInt(), timeline,
            sp.get("delayTicks").getAsInt(), sp.get("attackDurationTicks").getAsInt());
      }
      ItemEntry ent = new ItemEntry(
          id, mat, since, proxy, obj.get("cmd").getAsInt(),
          obj.has("tint") ? obj.get("tint").getAsInt() : null,
          spear,
          obj.has("spawnEgg") && obj.get("spawnEgg").getAsBoolean(),
          obj.has("retexture") && obj.get("retexture").getAsBoolean());
      byMat.put(mat, ent);
      byPrx.put(proxyKey(proxy, ent.cmd()), ent);
      if (spear != null) {
        byPrx.put(proxyKey(proxy, spear.inHandCmd()), ent);
        for (int frame = 0; frame < timelineFrames(spear); frame++) {
          byPrx.put(proxyKey(proxy, spear.frameCmdBase() + frame), ent);
        }
      }
    }
    logger.info("Loaded " + byMat.size() + " backported item(s)" + (skipped > 0 ? " (" + skipped + " not on this server)" : ""));
    return new ItemCatalog(byMat, byPrx);
  }

  private static int timelineFrames(SpearSpec spear) {
    int max = 0;
    for (int frame : spear.timeline()) {
      max = Math.max(max, frame);
    }
    return max + 1;
  }

  static long proxyKey(Material proxy, int cmd) {
    return ((long) proxy.ordinal() << 32) | (cmd & 0xFFFFFFFFL);
  }

  public Optional<ItemEntry> entry(Material mat) {
    return Optional.ofNullable(byMaterial.get(mat));
  }

  public Optional<ItemEntry> byProxy(Material proxy, int cmd) {
    return Optional.ofNullable(byProxy.get(proxyKey(proxy, cmd)));
  }

  public Collection<ItemEntry> entries() {
    return byMaterial.values();
  }

  public static boolean isNative(ItemEntry ent, ProtocolVersion client) {
    return client.newerThanOrEqualTo(ent.since())
        || !RegistrySince.get().existsOnServer(RegistrySince.Kind.ITEM, ent.id());
  }
}
