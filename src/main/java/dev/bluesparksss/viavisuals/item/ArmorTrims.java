package dev.bluesparksss.viavisuals.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.item.ItemCatalog.ItemEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ArmorTrims {

  private static final ProtocolVersion SHADER_FROM = ProtocolVersion.v1_17;
  private static final ProtocolVersion SHADER_UNTIL = ProtocolVersion.v1_21_2;
  private static final ProtocolVersion COPPER_ARMOR = ProtocolVersion.v1_21_9;
  private static final ProtocolVersion NATIVE_TRIMS = ProtocolVersion.v1_19_4;
  private static final ProtocolVersion V1_20 = ProtocolVersion.v1_20;
  private static final ProtocolVersion V1_21 = ProtocolVersion.v1_21;
  private static final ProtocolVersion V1_21_4 = ProtocolVersion.v1_21_4;
  private static final List<String> PATTERNS_1_20 = List.of("host", "raiser", "shaper", "silence", "wayfinder");
  private static final List<String> PATTERNS_1_21 = List.of("bolt", "flow");

  private final List<String> bases = new ArrayList<>();
  private final List<String> patterns = new ArrayList<>();
  private final List<String> palettes = new ArrayList<>();
  private final List<String> slots = new ArrayList<>();
  private final int[][] markers;
  private final int noPattern;
  private final int iconBase;
  private volatile boolean enabled = true;

  private ArmorTrims(JsonObject root) {
    fill(bases, root.getAsJsonArray("bases"));
    fill(patterns, root.getAsJsonArray("patterns"));
    fill(palettes, root.getAsJsonArray("palettes"));
    fill(slots, root.getAsJsonArray("slots"));
    JsonArray mk = root.getAsJsonArray("markers");
    markers = new int[mk.size()][3];
    for (int i = 0; i < markers.length; i++) {
      for (int c = 0; c < 3; c++) {
        markers[i][c] = mk.get(i).getAsJsonArray().get(c).getAsInt();
      }
    }
    noPattern = root.get("noPattern").getAsInt();
    iconBase = root.get("iconCmdBase").getAsInt();
  }

  private static void fill(List<String> out, JsonArray arr) {
    for (JsonElement el : arr) {
      out.add(el.getAsString());
    }
  }

  public static ArmorTrims load(InputStream in) throws IOException {
    try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return new ArmorTrims(JsonParser.parseReader(reader).getAsJsonObject());
    }
  }

  public void configure(org.bukkit.configuration.file.FileConfiguration conf) {
    enabled = conf.getBoolean("items.armor-trims", true);
  }

  public Optional<ItemEntry> entryFor(ItemStack srv, ProtocolVersion client, Optional<ItemEntry> catalogEntry) {
    if (!enabled || client.olderThan(SHADER_FROM)) {
      return Optional.empty();
    }
    if (!client.olderThan(SHADER_UNTIL)) {
      return usesEquipment(srv, client) ? catalogEntry.map(ent -> new ItemEntry(ent.id(), ent.material(), ent.since(),
          ent.proxy(), ent.cmd(), null, ent.spear(), ent.spawnEgg(), ent.retexture())) : Optional.empty();
    }
    String name = srv.getType().name();
    int us = name.lastIndexOf('_');
    if (us < 0) {
      return Optional.empty();
    }
    String slot = name.substring(us + 1).toLowerCase(Locale.ROOT);
    String prefix = name.substring(0, us).toLowerCase(Locale.ROOT);
    String base = switch (prefix) {
      case "golden" -> "gold";
      case "iron", "diamond", "netherite", "chainmail", "turtle", "copper" -> prefix;
      default -> null;
    };
    if (base == null || !slots.contains(slot) || !bases.contains(base)) {
      return Optional.empty();
    }
    String pattern = null;
    String material = null;
    ItemMeta meta = srv.getItemMeta();
    if (meta instanceof ArmorMeta armor && armor.hasTrim()) {
      ArmorTrim trim = armor.getTrim();
      pattern = key((Keyed) trim.getPattern());
      material = key((Keyed) trim.getMaterial());
    }
    boolean copperPlain = base.equals("copper") && pattern == null;
    if (pattern == null && !copperPlain) {
      return Optional.empty();
    }
    if (pattern != null && !needsProxy(client, pattern, material) && !base.equals("copper")) {
      return Optional.empty();
    }
    int patternIdx = pattern == null ? noPattern : patterns.indexOf(pattern);
    int row = material == null ? 0 : palettes.indexOf(darker(base, material));
    if (patternIdx < 0 || row < 0) {
      return Optional.empty();
    }
    Material proxy = Material.matchMaterial("LEATHER_" + slot.toUpperCase(Locale.ROOT));
    if (proxy == null) {
      return Optional.empty();
    }
    int key = keyColor(bases.indexOf(base), row, patternIdx);
    if (copperPlain) {
      return catalogEntry.map(ent -> new ItemEntry(ent.id(), ent.material(), ent.since(), ent.proxy(), ent.cmd(), key,
          ent.spear(), ent.spawnEgg(), ent.retexture()));
    }
    int cmd = iconBase + (slots.indexOf(slot) * bases.size() + bases.indexOf(base)) * 16 + row;
    return Optional.of(new ItemEntry(name.toLowerCase(Locale.ROOT), srv.getType(), client, proxy, cmd, key, null, false, false));
  }

  public boolean usesEquipment(ItemStack srv, ProtocolVersion client) {
    return enabled && !client.olderThan(SHADER_UNTIL) && client.olderThan(COPPER_ARMOR)
        && srv.getType().name().startsWith("COPPER_") && equipmentSlot(srv.getType()) != null;
  }

  private static org.bukkit.inventory.EquipmentSlot equipmentSlot(Material type) {
    String name = type.name();
    if (name.endsWith("_HELMET")) {
      return org.bukkit.inventory.EquipmentSlot.HEAD;
    }
    if (name.endsWith("_CHESTPLATE")) {
      return org.bukkit.inventory.EquipmentSlot.CHEST;
    }
    if (name.endsWith("_LEGGINGS")) {
      return org.bukkit.inventory.EquipmentSlot.LEGS;
    }
    return name.endsWith("_BOOTS") ? org.bukkit.inventory.EquipmentSlot.FEET : null;
  }

  public void copyTrim(ItemMeta src, ItemMeta dst) {
    if (src instanceof ArmorMeta from && from.hasTrim() && dst instanceof ArmorMeta to) {
      to.setTrim(from.getTrim());
    }
  }

  public void applyEquipment(ItemStack out, ItemStack srv) {
    org.bukkit.inventory.EquipmentSlot slot = equipmentSlot(srv.getType());
    if (slot == null) {
      return;
    }
    try {
      out.setData(io.papermc.paper.datacomponent.DataComponentTypes.EQUIPPABLE,
          io.papermc.paper.datacomponent.item.Equippable.equippable(slot)
              .assetId(net.kyori.adventure.key.Key.key("viavisuals", "copper")).build());
    } catch (NoSuchMethodError | NoClassDefFoundError ex) {
    }
  }

  private static String darker(String base, String material) {
    return base.equals(material) && !base.equals("copper") ? material + "_darker" : material;
  }

  private static String key(Keyed keyed) {
    return keyed.getKey().getKey();
  }

  private static boolean nativeTrim(ProtocolVersion client, String pattern, String material) {
    if (client.olderThan(NATIVE_TRIMS)) {
      return false;
    }
    if (PATTERNS_1_20.contains(pattern) && client.olderThan(V1_20)) {
      return false;
    }
    if (PATTERNS_1_21.contains(pattern) && client.olderThan(V1_21)) {
      return false;
    }
    return !material.equals("resin") || client.newerThanOrEqualTo(V1_21_4);
  }

  private static boolean needsProxy(ProtocolVersion client, String pattern, String material) {
    return !nativeTrim(client, pattern, material);
  }

  int keyColor(int base, int row, int pattern) {
    int check = (row + 3 * pattern + 5 * base) & 7;
    int[] hi = markers[base];
    int r = (hi[0] << 4) | row;
    int g = (hi[1] << 4) | (pattern & 15);
    int b = (hi[2] << 4) | (((pattern >> 4) & 1) | (check << 1));
    return (r << 16) | (g << 8) | b;
  }

  public List<Component> tooltip(ItemStack srv) {
    if (!(srv.getItemMeta() instanceof ArmorMeta armor) || !armor.hasTrim()) {
      return List.of();
    }
    ArmorTrim trim = armor.getTrim();
    String pattern = key((Keyed) trim.getPattern());
    String material = key((Keyed) trim.getMaterial());
    List<Component> lines = new ArrayList<>();
    lines.add(Component.translatable("item.minecraft.smithing_template.upgrade", NamedTextColor.GRAY)
        .decoration(TextDecoration.ITALIC, false));
    lines.add(Component.text(" ").append(Component.translatable("trim_pattern.minecraft." + pattern))
        .color(NamedTextColor.BLUE).decoration(TextDecoration.ITALIC, false));
    lines.add(Component.text(" ").append(Component.translatable("trim_material.minecraft." + material))
        .color(NamedTextColor.BLUE).decoration(TextDecoration.ITALIC, false));
    return lines;
  }
}
