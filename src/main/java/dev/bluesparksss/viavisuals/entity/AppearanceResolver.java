package dev.bluesparksss.viavisuals.entity;

import org.bukkit.DyeColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Steerable;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class AppearanceResolver {

  public record State(String key, Map<String, Integer> parts, boolean baby) {
  }

  private AppearanceResolver() {
  }

  public static State resolve(Entity ent, EntityCatalog.Def def) {
    boolean baby = isBaby(ent);
    if (def.babyOnly() && !baby) {
      return null;
    }
    Map<String, String> dims = new LinkedHashMap<>();
    String pose = "";
    Map<String, Integer> dyes = new LinkedHashMap<>();
    switch (def.id()) {
      case "cat" -> {
        dims.put("type", keyOf(call(ent, "getCatType")));
        if (bool(ent, "isLyingDown")) {
          pose = "lying";
        } else if (bool(ent, "isSitting")) {
          pose = "sitting";
        }
        if (bool(ent, "isTamed")) {
          dyes.put("collar", dye(call(ent, "getCollarColor")));
        }
      }
      case "wolf" -> {
        dims.put("variant", keyOf(call(ent, "getVariant")));
        boolean tame = bool(ent, "isTamed");
        dims.put("state", bool(ent, "isAngry") ? "angry" : tame ? "tame" : "wild");
        if (bool(ent, "isSitting")) {
          pose = "sitting";
        }
        if (tame) {
          dyes.put("collar", dye(call(ent, "getCollarColor")));
        }
      }
      case "fox" -> {
        dims.put("type", keyOf(call(ent, "getFoxType")));
        if (bool(ent, "isSleeping")) {
          pose = "sleeping";
        } else if (bool(ent, "isSitting")) {
          pose = "sitting";
        } else if (bool(ent, "isCrouching")) {
          pose = "crouching";
        }
      }
      case "axolotl", "frog", "mooshroom", "salmon", "zombie_nautilus", "chicken", "cow" ->
          dims.put(def.dims().containsKey("size") ? "size" : "variant", keyOf(call(ent, "getVariant")));
      case "pig" -> {
        dims.put("variant", keyOf(call(ent, "getVariant")));
        dims.put("saddle", saddled(ent) ? "yes" : "no");
      }
      case "bee" -> {
        Object anger = call(ent, "getAnger");
        dims.put("mood", anger instanceof Integer i && i > 0 ? "angry" : "calm");
        dims.put("nectar", bool(ent, "hasNectar") ? "yes" : "no");
      }
      case "camel", "camel_husk" -> dims.put("saddle", saddled(ent) ? "yes" : "no");
      case "copper_golem" -> dims.put("oxidation", keyOf(first(ent, "getWeatheringState", "getOxidizationState", "getOxidation")));
      case "cushion" -> dims.put("color", keyOf(first(ent, "getColor", "getDyeColor")));
      case "happy_ghast" -> dims.put("harness", harnessColor(ent));
      case "horse" -> {
        dims.put("color", keyOf(call(ent, "getColor")));
        dims.put("markings", switch (keyOf(call(ent, "getStyle"))) {
          case "white" -> "white";
          case "whitefield" -> "white_field";
          case "white_dots" -> "white_dots";
          case "black_dots" -> "black_dots";
          default -> "none";
        });
      }
      case "llama" -> {
        dims.put("color", keyOf(call(ent, "getColor")));
        dims.put("decor", llamaDecor(ent));
      }
      case "trader_llama" -> dims.put("color", keyOf(call(ent, "getColor")));
      case "nautilus" -> {
        dims.put("saddle", saddled(ent) ? "yes" : "no");
        dims.put("armor", nautilusArmor(ent));
      }
      case "panda" -> {
        dims.put("gene", keyOf(call(ent, "getCombinedGene")));
        if (bool(ent, "isSitting")) {
          pose = "sitting";
        }
      }
      case "rabbit" -> dims.put("type", "Toast".equals(ent.getName()) ? "toast" : keyOf(call(ent, "getRabbitType")));
      case "sheep" -> {
        Sheep sheep = (Sheep) ent;
        dims.put("wool", sheep.isSheared() ? "sheared" : keyOf(sheep.getColor()));
        if (!sheep.isSheared()) {
          int rgb = sheep.getColor() == null ? 0xF9FFFE : sheep.getColor().getColor().asRGB();
          dyes.put("wool", rgb);
          dyes.put("undercoat", rgb);
        }
      }
      case "strider" -> {
        dims.put("cold", bool(ent, "isShivering") ? "yes" : "no");
        dims.put("saddle", saddled(ent) ? "yes" : "no");
      }
      case "sulfur_cube" -> dims.put("size", baby ? "small" : "normal");
      case "vex" -> dims.put("charging", bool(ent, "isCharging") ? "yes" : "no");
      case "villager", "zombie_villager" -> dims.put("biome", keyOf(call(ent, "getVillagerType")));
      case "armadillo" -> {
        Object state = call(ent, "getState");
        if (state != null && !"idle".equals(keyOf(state))) {
          pose = "rolled";
        }
      }
      default -> {
      }
    }

    String age = baby && !"sulfur_cube".equals(def.id()) ? "baby" : "adult";
    StringBuilder key = new StringBuilder(def.id()).append(';').append(age);
    for (String dim : def.dims().keySet()) {
      key.append(';').append(dim).append('=').append(def.dimValue(dim, dims.get(dim)));
    }
    if (!pose.isEmpty() && def.poses().contains(pose)) {
      key.append(";pose=").append(pose);
    }
    String poseSuffix = !pose.isEmpty() && def.poses().contains(pose) ? ";pose=" + pose : "";
    Map<String, Integer> parts = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> dye : dyes.entrySet()) {
      parts.put(def.id() + ";" + age + ";#" + dye.getKey() + poseSuffix, dye.getValue());
    }
    return new State(key.toString(), parts, baby);
  }

  public static boolean isBaby(Entity ent) {
    if (ent instanceof Ageable ageable) {
      return !ageable.isAdult();
    }
    return bool(ent, "isBaby");
  }

  private static boolean saddled(Entity ent) {
    if (ent instanceof Steerable steer) {
      return steer.hasSaddle();
    }
    if (ent instanceof AbstractHorse horse) {
      ItemStack saddle = horse.getInventory().getSaddle();
      return saddle != null && saddle.getType() == Material.SADDLE;
    }
    if (ent instanceof LivingEntity living) {
      EntityEquipment eq = living.getEquipment();
      if (eq != null) {
        Object saddle = equipmentIn(eq, "SADDLE");
        return saddle instanceof ItemStack stk && stk.getType() == Material.SADDLE;
      }
    }
    return false;
  }

  private static String harnessColor(Entity ent) {
    Object body = ent instanceof LivingEntity living && living.getEquipment() != null ? equipmentIn(living.getEquipment(), "BODY") : null;
    if (body instanceof ItemStack stk && stk.getType().name().endsWith("_HARNESS")) {
      return stk.getType().name().replace("_HARNESS", "").toLowerCase(Locale.ROOT);
    }
    return "none";
  }

  private static String nautilusArmor(Entity ent) {
    Object body = ent instanceof LivingEntity living && living.getEquipment() != null ? equipmentIn(living.getEquipment(), "BODY") : null;
    if (body instanceof ItemStack stk && stk.getType().name().endsWith("_NAUTILUS_ARMOR")) {
      return stk.getType().name().replace("_NAUTILUS_ARMOR", "").toLowerCase(Locale.ROOT);
    }
    return "none";
  }

  private static String llamaDecor(Entity ent) {
    Object inv = call(ent, "getInventory");
    Object decor = inv == null ? null : call(inv, "getDecor");
    if (decor instanceof ItemStack stk && stk.getType().name().endsWith("_CARPET")) {
      return stk.getType().name().replace("_CARPET", "").toLowerCase(Locale.ROOT);
    }
    return "none";
  }

  private static Object equipmentIn(EntityEquipment eq, String slotName) {
    try {
      org.bukkit.inventory.EquipmentSlot slot = org.bukkit.inventory.EquipmentSlot.valueOf(slotName);
      return eq.getItem(slot);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static int dye(Object color) {
    return color instanceof DyeColor dc ? dc.getColor().asRGB() : DyeColor.RED.getColor().asRGB();
  }

  static String keyOf(Object val) {
    if (val == null) {
      return null;
    }
    if (val instanceof Keyed keyed) {
      return keyed.getKey().getKey();
    }
    if (val instanceof Enum<?> en) {
      return en.name().toLowerCase(Locale.ROOT);
    }
    Object key = call(val, "getKey");
    if (key instanceof org.bukkit.NamespacedKey nk) {
      return nk.getKey();
    }
    return val.toString().toLowerCase(Locale.ROOT);
  }

  private static Object first(Object target, String... names) {
    for (String name : names) {
      Object val = call(target, name);
      if (val != null) {
        return val;
      }
    }
    return null;
  }

  private static boolean bool(Object target, String method) {
    return call(target, method) instanceof Boolean b && b;
  }

  static Object call(Object target, String method) {
    if (target == null) {
      return null;
    }
    try {
      Method m = target.getClass().getMethod(method);
      return m.invoke(target);
    } catch (ReflectiveOperationException | RuntimeException ex) {
      return null;
    }
  }

}
