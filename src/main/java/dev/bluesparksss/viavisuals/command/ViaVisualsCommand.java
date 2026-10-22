package dev.bluesparksss.viavisuals.command;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.ViaVisualsPlugin;
import dev.bluesparksss.viavisuals.compat.RegistrySince;
import dev.bluesparksss.viavisuals.item.ItemCatalog;
import dev.bluesparksss.viavisuals.version.ClientProfiles;
import dev.bluesparksss.viavisuals.version.VersionManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ViaVisualsCommand implements TabExecutor {

  private final ViaVisualsPlugin plugin;
  private final ItemCatalog items;

  public ViaVisualsCommand(ViaVisualsPlugin plugin, ItemCatalog items) {
    this.plugin = plugin;
    this.items = items;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
      sender.sendMessage("/" + label + " reload | info [player] | profile <player> <version|reset> | resync <player> | give <player> <item> [amount] | enchant <player> <enchantment> [level] | summon <entity> [amount]");
      return true;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "reload" -> {
        if (deny(sender, "viavisuals.reload")) {
          return true;
        }
        plugin.reloadViaVisualsConfig();
        sender.sendMessage("ViaVisuals config reloaded.");
      }
      case "info" -> info(sender, args);
      case "profile" -> profile(sender, args);
      case "resync" -> resync(sender, args);
      case "packstate" -> packState(sender, args);
      case "locatortest" -> {
        Player tgt = args.length > 2 ? Bukkit.getPlayerExact(args[1]) : null;
        if (tgt != null && plugin.locatorBar() != null) {
          plugin.locatorBar().test(tgt, args[2].equals("bar"));
        }
      }
      case "displays" -> displays(sender, args);
      case "occlusion" -> occlusion(sender, args);
      case "give" -> give(sender, args);
      case "enchant" -> enchant(sender, args);
      case "summon" -> summon(sender, args);
      case "anim" -> sender.sendMessage(plugin.entityProxies().animDebug(sender, args));
      default -> sender.sendMessage("Unknown subcommand.");
    }
    return true;
  }

  private static boolean deny(CommandSender sender, String perm) {
    if (sender.hasPermission(perm)) {
      return false;
    }
    sender.sendMessage("You do not have permission (" + perm + ").");
    return true;
  }

  private void info(CommandSender sender, String[] args) {
    Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
    if (target == null) {
      sender.sendMessage("Player not found.");
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetProtocolVersion(target);
    Optional<ClientProfiles.Profile> prof = ClientProfiles.of(target.getUniqueId());
    sender.sendMessage(target.getName() + ": client " + proto.map(ProtocolVersion::getName).orElse("unknown")
        + ", server " + RegistrySince.serverVersion().getName()
        + ", proxied items: " + proto.map(p -> items.entries().stream().filter(e -> !ItemCatalog.isNative(e, p)).count()).orElse(0L));
    prof.ifPresent(p -> sender.sendMessage("  connection " + p.connection().getName() + ", real " + p.real().getName()
        + ", source " + p.source() + ", pack " + p.pack() + ", tier " + p.tier()));
  }

  private void profile(CommandSender sender, String[] args) {
    if (deny(sender, "viavisuals.reload")) {
      return;
    }
    Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
    if (target == null || args.length < 3) {
      sender.sendMessage("Usage: profile <player> <version|reset>");
      return;
    }
    ProtocolVersion version = null;
    if (!args[2].equalsIgnoreCase("reset")) {
      for (ProtocolVersion known : ProtocolVersion.getProtocols()) {
        if (known.getIncludedVersions().contains(args[2]) || known.getName().equalsIgnoreCase(args[2])) {
          version = known;
          break;
        }
      }
      if (version == null) {
        sender.sendMessage("Unknown version " + args[2]);
        return;
      }
    }
    ClientProfiles.setOverride(target.getUniqueId(), version);
    plugin.packManager().reassign(target);
    plugin.clientResync().run(target);
    sender.sendMessage(target.getName() + " is now treated as " + (version == null ? "its real version" : version.getName()));
  }

  private void packState(CommandSender sender, String[] args) {
    if (deny(sender, "viavisuals.reload")) {
      return;
    }
    Player target = args.length > 2 ? Bukkit.getPlayerExact(args[1]) : null;
    ClientProfiles.Pack state = null;
    if (args.length > 2) {
      try {
        state = ClientProfiles.Pack.valueOf(args[2].toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        state = null;
      }
    }
    if (target == null || state == null) {
      sender.sendMessage("Usage: packstate <player> <loaded|failed|pending|none>");
      return;
    }
    plugin.packManager().forceState(target, state);
    sender.sendMessage(target.getName() + ": pack " + state + ", tier "
        + ClientProfiles.of(target.getUniqueId()).map(p -> p.tier().toString()).orElse("?"));
  }

  private void resync(CommandSender sender, String[] args) {
    if (deny(sender, "viavisuals.reload")) {
      return;
    }
    Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
    if (target == null) {
      sender.sendMessage("Usage: resync <player>");
      return;
    }
    plugin.clientResync().run(target);
    sender.sendMessage("Resyncing " + target.getName());
  }

  private void give(CommandSender sender, String[] args) {
    if (deny(sender, "viavisuals.give")) {
      return;
    }
    if (args.length < 3) {
      sender.sendMessage("Usage: give <player> <item> [amount]");
      return;
    }
    Player target = Bukkit.getPlayerExact(args[1]);
    Material mat = Material.matchMaterial(args[2]);
    if (target == null || mat == null || !mat.isItem()) {
      sender.sendMessage("Unknown player or item.");
      return;
    }
    int amount = args.length > 3 ? parseInt(args[3], 1) : 1;
    target.getInventory().addItem(new ItemStack(mat, Math.max(1, amount)));
    sender.sendMessage("Gave " + amount + " " + mat.getKey() + " to " + target.getName());
  }

  private void enchant(CommandSender sender, String[] args) {
    if (deny(sender, "viavisuals.enchant")) {
      return;
    }
    if (args.length < 3) {
      sender.sendMessage("Usage: enchant <player> <enchantment> [level]");
      return;
    }
    Player target = Bukkit.getPlayerExact(args[1]);
    NamespacedKey key = NamespacedKey.fromString(args[2].toLowerCase(Locale.ROOT));
    Enchantment ench = key == null ? null : Registry.ENCHANTMENT.get(key);
    if (target == null || ench == null) {
      sender.sendMessage("Unknown player or enchantment.");
      return;
    }
    ItemStack held = target.getInventory().getItemInMainHand();
    if (held.getType().isAir() || !ench.canEnchantItem(held)) {
      sender.sendMessage("That enchantment can't be applied to the held item.");
      return;
    }
    int level = Math.min(ench.getMaxLevel(), args.length > 3 ? parseInt(args[3], 1) : 1);
    held.addEnchantment(ench, Math.max(1, level));
    sender.sendMessage("Enchanted " + target.getName() + "'s " + held.getType().getKey() + " with " + key + " " + level);
  }

  private void summon(CommandSender sender, String[] args) {
    if (deny(sender, "viavisuals.summon")) {
      return;
    }
    if (!(sender instanceof Player player) || args.length < 2) {
      sender.sendMessage("Usage (in game): summon <entity> [amount]");
      return;
    }
    NamespacedKey key = NamespacedKey.fromString(args[1].toLowerCase(Locale.ROOT));
    EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
    if (type == null || !type.isSpawnable()) {
      sender.sendMessage("Unknown entity.");
      return;
    }
    int amount = Math.min(64, args.length > 2 ? parseInt(args[2], 1) : 1);
    Location loc = player.getLocation();
    for (int i = 0; i < amount; i++) {
      player.getWorld().spawnEntity(loc, type);
    }
  }

  private static int parseInt(String raw, int fallback) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private void occlusion(CommandSender sender, String[] args) {
    Player target = args.length > 4 ? Bukkit.getPlayerExact(args[1]) : null;
    var occ = plugin.blockDisplays().occlusion();
    if (target == null || occ == null) {
      sender.sendMessage("occlusion <player> <x> <y> <z>");
      return;
    }
    int x = Integer.parseInt(args[2]);
    int y = Integer.parseInt(args[3]);
    int z = Integer.parseInt(args[4]);
    var world = target.getWorld();
    var type = world.getBlockAt(x, y, z).getType();
    var eye = target.getEyeLocation();
    sender.sendMessage(x + " " + y + " " + z + ": block " + type + " occluding=" + type.isOccluding() + " grid="
        + occ.isOpaque(world.getUID(), x, y, z) + " visible=" + occ.visible(world.getUID(), eye.getX(), eye.getY(), eye.getZ(), x, y, z)
        + " eye=" + eye.getX() + "," + eye.getY() + "," + eye.getZ());
  }

  private void displays(CommandSender sender, String[] args) {
    Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player plr ? plr : null;
    if (target == null) {
      sender.sendMessage("displays [player]");
      return;
    }
    var shown = plugin.blockDisplays().shownByCmd(target.getUniqueId());
    int total = shown.values().stream().mapToInt(Integer::intValue).sum();
    sender.sendMessage(target.getName() + ": " + total + " display entities of " + plugin.blockDisplays().knownCount(target.getUniqueId())
        + " display blocks known (" + plugin.blockDisplays().hiddenCount(target.getUniqueId()) + " hidden behind blocks, "
        + plugin.blockDisplays().notShown(target.getUniqueId()) + " not spawned)");
    java.util.Map<String, Integer> byBlock = new java.util.TreeMap<>();
    java.util.Map<String, String> where = new java.util.HashMap<>();
    shown.forEach((cmd, n) -> {
      String name = plugin.blockCatalog().blockOfCmd(cmd);
      byBlock.merge(name, n, Integer::sum);
      int[] at = where.containsKey(name) ? null : plugin.blockDisplays().sampleOf(target.getUniqueId(), cmd);
      if (at != null) {
        where.put(name, at[0] + " " + at[1] + " " + at[2]);
      }
    });
    StringBuilder log = new StringBuilder("displays of " + target.getName() + ": " + total + " shown");
    byBlock.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(15).forEach(ent -> {
      String at = where.getOrDefault(ent.getKey(), "?");
      sender.sendMessage("  " + ent.getKey() + " " + ent.getValue() + " @ " + at);
      log.append(", ").append(ent.getKey()).append(' ').append(ent.getValue()).append(" @ ").append(at);
    });
    plugin.getLogger().info(log.toString());
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    List<String> out = new ArrayList<>();
    if (args.length == 1) {
      for (String sub : List.of("reload", "info", "profile", "resync", "packstate", "displays", "give", "enchant", "summon", "anim")) {
        if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
          out.add(sub);
        }
      }
    } else if (args.length == 3 && "give".equalsIgnoreCase(args[0])) {
      for (ItemCatalog.ItemEntry ent : items.entries()) {
        if (ent.id().startsWith(args[2].toLowerCase(Locale.ROOT))) {
          out.add(ent.id());
        }
      }
    } else if (args.length == 2 && "summon".equalsIgnoreCase(args[0])) {
      for (EntityType type : Registry.ENTITY_TYPE) {
        String id = type.getKey().getKey();
        if (type.isSpawnable() && id.startsWith(args[1].toLowerCase(Locale.ROOT))) {
          out.add(id);
        }
      }
    } else if (args.length == 2) {
      for (Player p : Bukkit.getOnlinePlayers()) {
        out.add(p.getName());
      }
    }
    return out;
  }
}
