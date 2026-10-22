package dev.bluesparksss.viavisuals.item;

import com.google.common.collect.Multimap;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.item.ItemCatalog.ItemEntry;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ItemTranslator {

  public static final ProtocolVersion COMPONENTS = ProtocolVersion.v1_20_5;
  private static final boolean META_COMPONENTS =
      dev.bluesparksss.viavisuals.compat.RegistrySince.serverVersion().newerThanOrEqualTo(COMPONENTS);
  private static final int BUNDLE_LORE_LINES = 12;
  private static final boolean DATA_COMPONENT_API = classExists("io.papermc.paper.datacomponent.DataComponentTypes");

  public record Context(boolean inHand, int spearFrameCmd, boolean displayOnly) {

    public static final Context INVENTORY = new Context(false, -1);
    public static final Context HELD = new Context(true, -1);
    public static final Context RECIPE = new Context(false, -1, true);

    public Context(boolean inHand, int spearFrameCmd) {
      this(inHand, spearFrameCmd, false);
    }
  }

  private final ItemCatalog catalog;
  private final NamespacedKey origKey;
  private volatile ArmorTrims trims;

  public ItemTranslator(JavaPlugin plugin, ItemCatalog catalog) {
    this.catalog = catalog;
    this.origKey = new NamespacedKey(plugin, "orig");
  }

  public void setArmorTrims(ArmorTrims trims) {
    this.trims = trims;
  }

  public ItemCatalog catalog() {
    return catalog;
  }

  public Optional<ItemEntry> proxyEntry(ItemStack srv, ProtocolVersion client) {
    if (srv == null || srv.getType().isAir() || client == null) {
      return Optional.empty();
    }
    Optional<ItemEntry> ent = catalog.entry(srv.getType());
    Optional<ItemEntry> plain = ent.isEmpty() || ent.get().retexture() || ItemCatalog.isNative(ent.get(), client)
        ? Optional.empty() : ent;
    if (trims != null) {
      Optional<ItemEntry> keyed = trims.entryFor(srv, client, plain);
      if (keyed.isPresent()) {
        return keyed;
      }
    }
    return plain;
  }

  public boolean needsTranslation(ItemStack srv, ProtocolVersion client) {
    return proxyEntry(srv, client).isPresent();
  }

  public ItemStack toClient(ItemStack srv, ProtocolVersion client) {
    return toClient(srv, client, Context.INVENTORY);
  }

  public ItemStack toClient(ItemStack srv, ProtocolVersion client, Context ctx) {
    Optional<ItemEntry> entOpt = proxyEntry(srv, client);
    if (entOpt.isEmpty()) {
      return srv;
    }
    ItemEntry ent = entOpt.get();
    ItemStack out = new ItemStack(ent.proxy(), srv.getAmount());
    ItemMeta src = srv.getItemMeta();
    ItemMeta dst = out.getItemMeta();
    if (dst == null) {
      return srv;
    }

    int cmd = ent.cmd();
    if (ent.spear() != null) {
      if (ctx.spearFrameCmd() > 0) {
        cmd = ctx.spearFrameCmd();
      } else if (ctx.inHand()) {
        cmd = ent.spear().inHandCmd();
      }
    }
    dst.setCustomModelData(cmd);

    Component name = src != null && src.hasDisplayName() ? src.displayName() : null;
    if (name != null) {
      dst.displayName(name);
    } else {
      Component vanilla = Component.translatable(srv.getType().translationKey())
          .color(rarityColor(srv)).decoration(TextDecoration.ITALIC, false);
      if (META_COMPONENTS) {
        dst.itemName(vanilla);
      } else {
        dst.displayName(vanilla);
      }
    }
    if (src != null) {
      List<Component> lore = new ArrayList<>();
      if (src.hasLore()) {
        lore.addAll(src.lore());
      }
      if (src instanceof BundleMeta bundle) {
        lore.addAll(bundleLore(bundle));
      }
      if (trims != null) {
        lore.addAll(trims.tooltip(srv));
      }
      if (!lore.isEmpty()) {
        dst.lore(lore);
      }
      for (Map.Entry<Enchantment, Integer> ench : src.getEnchants().entrySet()) {
        dst.addEnchant(ench.getKey(), ench.getValue(), true);
      }
      for (ItemFlag flag : src.getItemFlags()) {
        dst.addItemFlags(flag);
      }
      if (META_COMPONENTS && src.hasEnchantmentGlintOverride()) {
        dst.setEnchantmentGlintOverride(src.getEnchantmentGlintOverride());
      }
      dst.setUnbreakable(src.isUnbreakable());
    }
    copyAttributes(srv, src, dst);
    applyDurability(srv, src, dst, ent, client);
    if (ent.tint() != null && dst instanceof LeatherArmorMeta leather) {
      leather.setColor(Color.fromRGB(ent.tint()));
      leather.addItemFlags(ItemFlag.HIDE_DYE);
    }
    if (!ctx.displayOnly()) {
      dst.getPersistentDataContainer().set(origKey, PersistentDataType.BYTE_ARRAY, srv.serializeAsBytes());
    }
    boolean equipment = trims != null && DATA_COMPONENT_API && trims.usesEquipment(srv, client);
    if (equipment) {
      trims.copyTrim(src, dst);
    }
    out.setItemMeta(dst);
    if (equipment) {
      trims.applyEquipment(out, srv);
    }
    return out;
  }

  private static List<Component> bundleLore(BundleMeta bundle) {
    List<Component> out = new ArrayList<>();
    for (ItemStack inner : bundle.getItems()) {
      if (out.size() == BUNDLE_LORE_LINES) {
        out.add(Component.text("...", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        break;
      }
      out.add(Component.text(inner.getAmount() + "x ", NamedTextColor.GRAY)
          .append(Component.translatable(inner.getType().translationKey()))
          .decoration(TextDecoration.ITALIC, false));
    }
    return out;
  }

  public ItemStack toServer(ItemStack client) {
    if (client == null || client.getType().isAir() || !client.hasItemMeta()) {
      return client;
    }
    PersistentDataContainer pdc = client.getItemMeta().getPersistentDataContainer();
    byte[] orig = pdc.get(origKey, PersistentDataType.BYTE_ARRAY);
    if (orig != null) {
      try {
        ItemStack srv = ItemStack.deserializeBytes(orig);
        srv.setAmount(client.getAmount());
        return srv;
      } catch (RuntimeException ignored) {
      }
    }
    ItemMeta meta = client.getItemMeta();
    if (meta == null || !meta.hasCustomModelData()) {
      return client;
    }
    Optional<ItemEntry> ent = catalog.byProxy(client.getType(), meta.getCustomModelData());
    if (ent.isEmpty()) {
      return client;
    }
    ItemStack srv = new ItemStack(ent.get().material(), client.getAmount());
    ItemMeta srvMeta = srv.getItemMeta();
    if (srvMeta != null) {
      for (Map.Entry<Enchantment, Integer> ench : meta.getEnchants().entrySet()) {
        srvMeta.addEnchant(ench.getKey(), ench.getValue(), true);
      }
      if (meta.hasDisplayName()) {
        srvMeta.displayName(meta.displayName());
      }
      srv.setItemMeta(srvMeta);
    }
    return srv;
  }

  public boolean isProxy(ItemStack client) {
    if (client == null || !client.hasItemMeta()) {
      return false;
    }
    ItemMeta meta = client.getItemMeta();
    return meta.getPersistentDataContainer().has(origKey, PersistentDataType.BYTE_ARRAY)
        || (meta.hasCustomModelData() && catalog.byProxy(client.getType(), meta.getCustomModelData()).isPresent());
  }

  private static TextColor rarityColor(ItemStack srv) {
    if (!DATA_COMPONENT_API) {
      return srv.getEnchantments().isEmpty() ? NamedTextColor.WHITE : NamedTextColor.AQUA;
    }
    ItemRarity rarity = srv.getData(DataComponentTypes.RARITY);
    if (rarity == null) {
      rarity = ItemRarity.COMMON;
    }
    if (!srv.getEnchantments().isEmpty() && (rarity == ItemRarity.COMMON || rarity == ItemRarity.UNCOMMON)) {
      rarity = ItemRarity.RARE;
    }
    return switch (rarity) {
      case UNCOMMON -> NamedTextColor.YELLOW;
      case RARE -> NamedTextColor.AQUA;
      case EPIC -> NamedTextColor.LIGHT_PURPLE;
      default -> NamedTextColor.WHITE;
    };
  }

  private static void copyAttributes(ItemStack srv, ItemMeta src, ItemMeta dst) {
    Multimap<Attribute, AttributeModifier> mods = src != null && src.hasAttributeModifiers()
        ? src.getAttributeModifiers()
        : defaultModifiers(srv.getType());
    if (mods == null || mods.isEmpty()) {
      dst.setAttributeModifiers(null);
      dst.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
      return;
    }
    dst.setAttributeModifiers(mods);
  }

  private static void applyDurability(ItemStack srv, ItemMeta src, ItemMeta dst, ItemEntry ent, ProtocolVersion client) {
    if (!(src instanceof Damageable srcDmg) || !(dst instanceof Damageable dstDmg)) {
      return;
    }
    int max = META_COMPONENTS && srcDmg.hasMaxDamage() ? srcDmg.getMaxDamage() : srv.getType().getMaxDurability();
    if (max <= 0 || src.isUnbreakable()) {
      return;
    }
    int damage = Math.max(0, srcDmg.getDamage());
    if (client.newerThanOrEqualTo(COMPONENTS)) {
      dstDmg.setMaxDamage(max);
      dstDmg.setDamage(damage);
      return;
    }
    int proxyMax = ent.proxy().getMaxDurability();
    if (proxyMax <= 0) {
      return;
    }
    int scaled = (int) Math.round(damage * (double) proxyMax / max);
    if (damage > 0) {
      scaled = Math.max(1, scaled);
    }
    dstDmg.setDamage(Math.min(proxyMax - 1, scaled));
  }

  @SuppressWarnings("deprecation")
  private static Multimap<Attribute, AttributeModifier> defaultModifiers(Material type) {
    try {
      return type.getDefaultAttributeModifiers();
    } catch (NoSuchMethodError ex) {
      return type.getDefaultAttributeModifiers(org.bukkit.inventory.EquipmentSlot.HAND);
    }
  }

  private static boolean classExists(String name) {
    try {
      Class.forName(name, false, ItemTranslator.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException ex) {
      return false;
    }
  }

  public void pointRecoveryCompass(ItemStack srv, ItemStack proxy, org.bukkit.entity.Player viewer) {
    if (viewer == null || !"RECOVERY_COMPASS".equals(srv.getType().name()) || proxy.getType() != Material.COMPASS) {
      return;
    }
    org.bukkit.Location death = viewer.getLastDeathLocation();
    if (death == null || !(proxy.getItemMeta() instanceof org.bukkit.inventory.meta.CompassMeta compass)) {
      return;
    }
    compass.setLodestone(death);
    compass.setLodestoneTracked(false);
    proxy.setItemMeta(compass);
  }
}
