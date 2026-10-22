package dev.bluesparksss.viavisuals.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantOffer;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.mapper.MappedEntitySet;
import com.github.retrooper.packetevents.protocol.recipe.RecipeDisplayEntry;
import com.github.retrooper.packetevents.protocol.recipe.SingleInputOptionDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.CompositeSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.DyedSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.OnlyWithComponentSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.SmithingTrimSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.TagSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.WithAnyPotionSlotDisplay;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCraftRecipeResponse;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareRecipes;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.ItemSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.ItemStackSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.SlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.WithRemainderSlotDisplay;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRecipeBookAdd;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCooldown;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.item.ItemTranslator;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import java.util.logging.Level;

public final class ItemPacketListener extends PacketListenerAbstract {

  public interface HandContext {

    ItemTranslator.Context contextFor(Player viewer, int windowSlot);

    default ItemTranslator.Context heldBy(int entityId) {
      return ItemTranslator.Context.HELD;
    }

    default ItemTranslator.Context heldBy(int entityId, boolean offHand) {
      return offHand ? ItemTranslator.Context.HELD : heldBy(entityId);
    }
  }

  private final JavaPlugin plugin;
  private final ItemTranslator xlat;
  private volatile HandContext hands = (viewer, slot) -> ItemTranslator.Context.INVENTORY;
  private final ThreadLocal<Player> viewer = new ThreadLocal<>();

  public ItemPacketListener(JavaPlugin plugin, ItemTranslator xlat) {
    super(PacketListenerPriority.HIGH);
    this.plugin = plugin;
    this.xlat = xlat;
  }

  public void setHandContext(HandContext hands) {
    this.hands = hands;
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    Player player = event.getPlayer();
    var kind = event.getPacketType();
    boolean early = kind == PacketType.Play.Server.DECLARE_RECIPES || kind == PacketType.Play.Server.RECIPE_BOOK_ADD;
    if (player == null && !early) {
      return;
    }
    Optional<ProtocolVersion> proto = player != null ? VersionManager.tryGetVisualVersion(player)
        : Optional.ofNullable(ProtocolVersion.getProtocol(event.getUser().getClientVersion().getProtocolVersion()));
    if (early && plugin.getConfig().getBoolean("debug.recipe-book", false)) {
      plugin.getLogger().info("early " + kind + " player=" + (player != null) + " client=" + proto.map(ProtocolVersion::getName).orElse("?")
          + " userClient=" + event.getUser().getClientVersion());
    }
    if (proto.isEmpty() || !proto.get().olderThan(serverVersion())) {
      return;
    }
    ProtocolVersion client = proto.get();
    viewer.set(player);
    try {
      var type = event.getPacketType();
      if (type == PacketType.Play.Server.SET_SLOT) {
        WrapperPlayServerSetSlot pkt = new WrapperPlayServerSetSlot(event);
        ItemTranslator.Context ctx = pkt.getWindowId() == 0 ? hands.contextFor(player, pkt.getSlot()) : ItemTranslator.Context.INVENTORY;
        ItemStack out = convert(pkt.getItem(), client, ctx);
        if (out != pkt.getItem()) {
          pkt.setItem(out);
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
        WrapperPlayServerWindowItems pkt = new WrapperPlayServerWindowItems(event);
        boolean playerInv = pkt.getWindowId() == 0;
        boolean dirty = rewriteList(pkt.getItems(), client, slot -> slot, playerInv, player);
        Optional<ItemStack> carried = pkt.getCarriedItem();
        if (carried.isPresent()) {
          ItemStack out = convert(carried.get(), client, ItemTranslator.Context.INVENTORY);
          if (out != carried.get()) {
            pkt.setCarriedItem(out);
            dirty = true;
          }
        }
        if (dirty) {
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.SET_CURSOR_ITEM) {
        WrapperPlayServerSetCursorItem pkt = new WrapperPlayServerSetCursorItem(event);
        ItemStack out = convert(pkt.getStack(), client, ItemTranslator.Context.INVENTORY);
        if (out != pkt.getStack()) {
          pkt.setStack(out);
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
        WrapperPlayServerSetPlayerInventory pkt = new WrapperPlayServerSetPlayerInventory(event);
        ItemStack out = convert(pkt.getStack(), client, hands.contextFor(player, toWindowSlot(pkt.getSlot())));
        if (out != pkt.getStack()) {
          pkt.setStack(out);
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
        WrapperPlayServerEntityEquipment pkt = new WrapperPlayServerEntityEquipment(event);
        List<Equipment> list = new ArrayList<>(pkt.getEquipment().size());
        boolean dirty = false;
        for (Equipment eq : pkt.getEquipment()) {
          boolean hand = eq.getSlot() == EquipmentSlot.MAIN_HAND || eq.getSlot() == EquipmentSlot.OFF_HAND;
          ItemTranslator.Context ctx = !hand ? ItemTranslator.Context.INVENTORY
              : hands.heldBy(pkt.getEntityId(), eq.getSlot() == EquipmentSlot.OFF_HAND);
          ItemStack out = convert(eq.getItem(), client, ctx);
          dirty |= out != eq.getItem();
          list.add(new Equipment(eq.getSlot(), out));
        }
        if (dirty) {
          pkt.setEquipment(list);
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
        WrapperPlayServerEntityMetadata pkt = new WrapperPlayServerEntityMetadata(event);
        boolean dirty = false;
        List<EntityData<?>> rewritten = new ArrayList<>(pkt.getEntityMetadata().size());
        for (EntityData<?> data : pkt.getEntityMetadata()) {
          if (data.getType() == EntityDataTypes.ITEMSTACK && data.getValue() instanceof ItemStack stack) {
            ItemStack out = convert(stack, client, ItemTranslator.Context.INVENTORY);
            if (out != stack) {
              rewritten.add(new EntityData<>(data.getIndex(), EntityDataTypes.ITEMSTACK, out));
              dirty = true;
              continue;
            }
          }
          rewritten.add(data);
        }
        if (dirty) {
          pkt.setEntityMetadata(rewritten);
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.RECIPE_BOOK_ADD) {
        WrapperPlayServerRecipeBookAdd pkt = new WrapperPlayServerRecipeBookAdd(event);
        boolean dirty = false;
        for (WrapperPlayServerRecipeBookAdd.AddEntry entry : pkt.getEntries()) {
          dirty |= rewriteDisplay(entry.getContents().getDisplay(), client);
          if (plugin.getConfig().getBoolean("debug.recipe-book", false)) {
            debugResult(entry.getContents().getDisplay());
          }
          dirty |= rewriteIngredients(entry.getContents(), client);
        }
        if (dirty) {
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.CRAFT_RECIPE_RESPONSE) {
        WrapperPlayServerCraftRecipeResponse pkt = new WrapperPlayServerCraftRecipeResponse(event);
        if (pkt.getRecipeDisplay() != null && rewriteDisplay(pkt.getRecipeDisplay(), client)) {
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.DECLARE_RECIPES) {
        WrapperPlayServerDeclareRecipes pkt = new WrapperPlayServerDeclareRecipes(event);
        boolean dirty = false;
        if (plugin.getConfig().getBoolean("debug.recipe-book", false)) {
          plugin.getLogger().info("declare recipes: " + (pkt.getStonecutterRecipes() == null ? -1 : pkt.getStonecutterRecipes().size())
              + " stonecutter option(s)");
        }
        if (pkt.getStonecutterRecipes() != null) {
          for (SingleInputOptionDisplay opt : pkt.getStonecutterRecipes()) {
            SlotDisplay<?> out = fixSlot(opt.getOptionDisplay(), client);
            if (out != opt.getOptionDisplay()) {
              if (plugin.getConfig().getBoolean("debug.recipe-book", false) && out instanceof ItemStackSlotDisplay st) {
                org.bukkit.inventory.ItemStack b = SpigotConversionUtil.toBukkitItemStack(st.getStack());
                plugin.getLogger().info("stonecutter option " + opt.getOptionDisplay().getClass().getSimpleName() + " -> " + b.getType()
                    + " cmd " + (b.hasItemMeta() && b.getItemMeta().hasCustomModelData() ? b.getItemMeta().getCustomModelData() : -1));
              }
              opt.setOptionDisplay(out);
              dirty = true;
            }
          }
        }
        if (dirty) {
          event.markForReEncode(true);
        }
      } else if (type == PacketType.Play.Server.SET_COOLDOWN) {
        mirrorCooldown(event, client);
      } else if (type == PacketType.Play.Server.MERCHANT_OFFERS) {
        WrapperPlayServerMerchantOffers pkt = new WrapperPlayServerMerchantOffers(event);
        boolean dirty = false;
        for (MerchantOffer offer : pkt.getMerchantOffers()) {
          ItemStack first = convert(offer.getFirstInputItem(), client, ItemTranslator.Context.INVENTORY);
          ItemStack second = convert(offer.getSecondInputItem(), client, ItemTranslator.Context.INVENTORY);
          ItemStack result = convert(offer.getOutputItem(), client, ItemTranslator.Context.INVENTORY);
          if (first != offer.getFirstInputItem() || second != offer.getSecondInputItem() || result != offer.getOutputItem()) {
            offer.setFirstInputItem(first);
            offer.setSecondInputItem(second);
            offer.setOutputItem(result);
            dirty = true;
          }
        }
        if (dirty) {
          event.markForReEncode(true);
        }
      }
    } catch (RuntimeException ex) {
      plugin.getLogger().log(Level.WARNING, "Item rewrite failed for " + event.getPacketType()
          + " to " + event.getUser().getName() + "; sending it unchanged.", ex);
    } finally {
      viewer.remove();
    }
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    if (event.getPacketType() != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
      return;
    }
    WrapperPlayClientCreativeInventoryAction pkt = new WrapperPlayClientCreativeInventoryAction(event);
    ItemStack stack = pkt.getItemStack();
    if (stack == null || stack.isEmpty()) {
      return;
    }
    org.bukkit.inventory.ItemStack bukkit = SpigotConversionUtil.toBukkitItemStack(stack);
    if (!xlat.isProxy(bukkit)) {
      return;
    }
    pkt.setItemStack(SpigotConversionUtil.fromBukkitItemStack(xlat.toServer(bukkit)));
    event.markForReEncode(true);
  }

  private boolean rewriteDisplay(Object display, ProtocolVersion client) {
    if (display == null) {
      return false;
    }
    boolean dirty = false;
    for (java.lang.reflect.Method getter : display.getClass().getMethods()) {
      if (!getter.getName().startsWith("get") || getter.getParameterCount() != 0) {
        continue;
      }
      try {
        Object val = getter.invoke(display);
        java.lang.reflect.Method setter;
        if (val instanceof SlotDisplay<?> slot) {
          SlotDisplay<?> out = fixSlot(slot, client);
          if (out != slot) {
            setter = display.getClass().getMethod("set" + getter.getName().substring(3), getter.getReturnType());
            setter.invoke(display, out);
            dirty = true;
          }
        } else if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof SlotDisplay<?>) {
          @SuppressWarnings("unchecked")
          List<SlotDisplay<?>> slots = (List<SlotDisplay<?>>) list;
          for (int i = 0; i < slots.size(); i++) {
            SlotDisplay<?> out = fixSlot(slots.get(i), client);
            if (out != slots.get(i)) {
              slots.set(i, out);
              dirty = true;
            }
          }
        }
      } catch (ReflectiveOperationException | UnsupportedOperationException ignored) {
      }
    }
    return dirty;
  }

  private SlotDisplay<?> fixSlot(SlotDisplay<?> slot, ProtocolVersion client) {
    if (slot instanceof ItemSlotDisplay item) {
      ItemStack stack = ItemStack.builder().type(item.getItem()).amount(1).build();
      ItemStack out = convert(stack, client, ItemTranslator.Context.RECIPE);
      if (out != stack && plugin.getConfig().getBoolean("debug.recipe-book", false)) {
        org.bukkit.inventory.ItemStack dbg = SpigotConversionUtil.toBukkitItemStack(out);
        plugin.getLogger().info("recipe item " + item.getItem().getName() + " -> " + dbg.getType() + " cmd "
            + (dbg.hasItemMeta() && dbg.getItemMeta().hasCustomModelData() ? dbg.getItemMeta().getCustomModelData() : -1));
      }
      return out != stack ? new ItemStackSlotDisplay(out) : slot;
    }
    if (slot instanceof ItemStackSlotDisplay stackSlot) {
      ItemStack out = convert(stackSlot.getStack(), client, ItemTranslator.Context.RECIPE);
      return out != stackSlot.getStack() ? new ItemStackSlotDisplay(out) : slot;
    }
    if (slot instanceof CompositeSlotDisplay comp) {
      List<SlotDisplay<?>> contents = new ArrayList<>(comp.getContents());
      boolean dirty = false;
      for (int i = 0; i < contents.size(); i++) {
        SlotDisplay<?> out = fixSlot(contents.get(i), client);
        dirty |= out != contents.get(i);
        contents.set(i, out);
      }
      return dirty ? new CompositeSlotDisplay(contents) : slot;
    }
    if (slot instanceof WithRemainderSlotDisplay rem) {
      SlotDisplay<?> in = fixSlot(rem.getInput(), client);
      SlotDisplay<?> left = fixSlot(rem.getRemainder(), client);
      if (in == rem.getInput() && left == rem.getRemainder()) {
        return slot;
      }
      return client.olderThan(ProtocolVersion.v1_21_2) ? in : new WithRemainderSlotDisplay(in, left);
    }
    if (slot instanceof TagSlotDisplay tag) {
      return fixTag(tag, client);
    }
    if (slot instanceof SmithingTrimSlotDisplay trim) {
      SlotDisplay<?> base = fixSlot(trim.getBase(), client);
      SlotDisplay<?> material = fixSlot(trim.getMaterial(), client);
      SlotDisplay<?> pattern = fixSlot(trim.getPattern(), client);
      if (base == trim.getBase() && material == trim.getMaterial() && pattern == trim.getPattern()) {
        return slot;
      }
      return client.olderThan(ProtocolVersion.v1_21_2) ? base : new SmithingTrimSlotDisplay(base, material, pattern);
    }
    if (slot instanceof DyedSlotDisplay dyed) {
      SlotDisplay<?> src = fixSlot(dyed.getSource(), client);
      SlotDisplay<?> target = fixSlot(dyed.getTarget(), client);
      return src != dyed.getSource() || target != dyed.getTarget() ? new DyedSlotDisplay(src, target) : slot;
    }
    if (slot instanceof OnlyWithComponentSlotDisplay only) {
      SlotDisplay<?> src = fixSlot(only.getSource(), client);
      return src != only.getSource() ? new OnlyWithComponentSlotDisplay(src, only.getComponent()) : slot;
    }
    if (slot instanceof WithAnyPotionSlotDisplay potion) {
      SlotDisplay<?> disp = fixSlot(potion.getDisplay(), client);
      return disp != potion.getDisplay() ? new WithAnyPotionSlotDisplay(disp) : slot;
    }
    return slot;
  }

  private SlotDisplay<?> fixTag(TagSlotDisplay tag, ProtocolVersion client) {
    java.util.Collection<org.bukkit.Material> members;
    MappedEntitySet<ItemType> listed = tag.getItems();
    if (listed != null && listed.getTagKey() == null) {
      members = new ArrayList<>();
      for (ItemType type : listed.getEntities()) {
        members.add(SpigotConversionUtil.toBukkitItemMaterial(type));
      }
    } else {
      org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(tag.getItemTag().toString());
      org.bukkit.Tag<org.bukkit.Material> named = key == null ? null
          : org.bukkit.Bukkit.getTag(org.bukkit.Tag.REGISTRY_ITEMS, key, org.bukkit.Material.class);
      if (named == null) {
        return tag;
      }
      members = named.getValues();
    }
    List<SlotDisplay<?>> out = new ArrayList<>();
    SlotDisplay<?> firstChanged = null;
    for (org.bukkit.Material mat : members) {
      ItemStack stack = SpigotConversionUtil.fromBukkitItemStack(new org.bukkit.inventory.ItemStack(mat));
      ItemStack conv = convert(stack, client, ItemTranslator.Context.RECIPE);
      ItemStackSlotDisplay disp = new ItemStackSlotDisplay(conv);
      out.add(disp);
      if (firstChanged == null && conv != stack) {
        firstChanged = disp;
      }
    }
    if (firstChanged == null) {
      return tag;
    }
    return client.olderThan(ProtocolVersion.v1_21_2) ? firstChanged : new CompositeSlotDisplay(out);
  }

  private boolean rewriteIngredients(RecipeDisplayEntry entry, ProtocolVersion client) {
    List<MappedEntitySet<ItemType>> sets = entry.getIngredients();
    if (sets == null || sets.isEmpty()) {
      return false;
    }
    List<MappedEntitySet<ItemType>> out = new ArrayList<>(sets.size());
    boolean dirty = false;
    for (MappedEntitySet<ItemType> set : sets) {
      MappedEntitySet<ItemType> mapped = proxySet(set, client);
      dirty |= mapped != set;
      out.add(mapped);
    }
    if (dirty) {
      entry.setIngredients(out);
    }
    return dirty;
  }

  private MappedEntitySet<ItemType> proxySet(MappedEntitySet<ItemType> set, ProtocolVersion client) {
    if (set == null || set.getTagKey() != null || set.getEntities() == null) {
      return set;
    }
    List<ItemType> types = new ArrayList<>(set.getEntities().size());
    boolean dirty = false;
    for (ItemType type : set.getEntities()) {
      ItemStack stack = ItemStack.builder().type(type).amount(1).build();
      ItemStack conv = convert(stack, client, ItemTranslator.Context.RECIPE);
      types.add(conv == stack ? type : conv.getType());
      dirty |= conv != stack;
    }
    return dirty ? new MappedEntitySet<>(types) : set;
  }

  private void debugResult(Object display) {
    try {
      Object res = display.getClass().getMethod("getResult").invoke(display);
      String desc = res.getClass().getSimpleName();
      if (res instanceof ItemStackSlotDisplay st) {
        org.bukkit.inventory.ItemStack b = SpigotConversionUtil.toBukkitItemStack(st.getStack());
        desc += " " + b.getType() + " cmd " + (b.hasItemMeta() && b.getItemMeta().hasCustomModelData() ? b.getItemMeta().getCustomModelData() : -1);
      } else if (res instanceof ItemSlotDisplay it) {
        desc += " " + it.getItem().getName();
      }
      plugin.getLogger().info("recipe result " + display.getClass().getSimpleName() + " " + desc);
    } catch (ReflectiveOperationException | RuntimeException ex) {
      plugin.getLogger().info("recipe result unreadable for " + display.getClass().getSimpleName());
    }
  }

  private void mirrorCooldown(PacketSendEvent event, ProtocolVersion client) {
    WrapperPlayServerSetCooldown pkt = new WrapperPlayServerSetCooldown(event);
    var group = pkt.getCooldownGroup();
    if (group == null || !"minecraft".equals(group.getNamespace())) {
      return;
    }
    org.bukkit.Material mat = org.bukkit.Material.matchMaterial(group.getKey());
    if (mat == null) {
      return;
    }
    var ent = xlat.proxyEntry(new org.bukkit.inventory.ItemStack(mat), client);
    if (ent.isEmpty() || ent.get().proxy() == org.bukkit.Material.PAPER) {
      return;
    }
    var proxyType = com.github.retrooper.packetevents.protocol.item.type.ItemTypes.getByName(
        "minecraft:" + ent.get().proxy().getKey().getKey());
    if (proxyType == null) {
      return;
    }
    int ticks = pkt.getCooldownTicks();
    var user = event.getUser();
    event.getTasksAfterSend().add(() -> user.sendPacketSilently(new WrapperPlayServerSetCooldown(proxyType, ticks)));
  }

  private boolean rewriteList(List<ItemStack> items, ProtocolVersion client, IntUnaryOperator slotMap,
                              boolean playerInv, Player player) {
    boolean dirty = false;
    for (int i = 0; i < items.size(); i++) {
      ItemTranslator.Context ctx = playerInv ? hands.contextFor(player, slotMap.applyAsInt(i)) : ItemTranslator.Context.INVENTORY;
      ItemStack out = convert(items.get(i), client, ctx);
      if (out != items.get(i)) {
        items.set(i, out);
        dirty = true;
      }
    }
    return dirty;
  }

  private ItemStack convert(ItemStack stack, ProtocolVersion client, ItemTranslator.Context ctx) {
    if (stack == null || stack.isEmpty()) {
      return stack;
    }
    org.bukkit.inventory.ItemStack bukkit = SpigotConversionUtil.toBukkitItemStack(stack);
    if (!xlat.needsTranslation(bukkit, client)) {
      return stack;
    }
    org.bukkit.inventory.ItemStack out = xlat.toClient(bukkit, client, ctx);
    xlat.pointRecoveryCompass(bukkit, out, viewer.get());
    return SpigotConversionUtil.fromBukkitItemStack(out);
  }

  private static int toWindowSlot(int invIdx) {
    if (invIdx < 9) {
      return invIdx + 36;
    }
    if (invIdx < 36) {
      return invIdx;
    }
    if (invIdx < 40) {
      return 44 - invIdx;
    }
    return invIdx == 40 ? 45 : -1;
  }

  private static ProtocolVersion serverVersion() {
    return dev.bluesparksss.viavisuals.compat.RegistrySince.serverVersion();
  }
}
