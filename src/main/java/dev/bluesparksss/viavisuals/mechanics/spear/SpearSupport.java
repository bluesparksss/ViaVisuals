package dev.bluesparksss.viavisuals.mechanics.spear;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.compat.RegistrySince;
import dev.bluesparksss.viavisuals.item.ItemCatalog;
import dev.bluesparksss.viavisuals.item.ItemTranslator;
import dev.bluesparksss.viavisuals.listener.ItemPacketListener;
import dev.bluesparksss.viavisuals.mechanics.UseHoldSupport;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SpearSupport extends PacketListenerAbstract implements ItemPacketListener.HandContext, org.bukkit.event.Listener {

  public static final ProtocolVersion NATIVE_SPEARS = ProtocolVersion.v1_21_11;
  private static final int LIVING_FLAGS = 8;
  private static final int USING_ITEM = 0x01;
  private static final int USING_OFF_HAND = 0x02;
  private static final long USE_SWING_MS = 120;
  private static final int OFF_HAND_SLOT = 40;
  private static final int OFF_HAND_WINDOW_SLOT = 45;

  private final JavaPlugin plugin;
  private final ItemTranslator xlat;
  private final UseHoldSupport useHold;
  private record Charge(int frame, boolean offHand) {
  }

  private final Map<UUID, Charge> charging = new ConcurrentHashMap<>();
  private final Map<Integer, UUID> byEntityId = new ConcurrentHashMap<>();
  private final Map<UUID, long[]> swapped = new ConcurrentHashMap<>();
  private final Map<UUID, Long> handSwap = new ConcurrentHashMap<>();
  private final Map<UUID, Long> clicked = new ConcurrentHashMap<>();
  private final java.util.Set<UUID> digging = ConcurrentHashMap.newKeySet();

  public SpearSupport(JavaPlugin plugin, ItemTranslator xlat, UseHoldSupport useHold) {
    super(PacketListenerPriority.HIGH);
    this.plugin = plugin;
    this.xlat = xlat;
    this.useHold = useHold;
    Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
  }

  private static boolean isSpear(ItemStack stk) {
    return stk != null && stk.getType().name().endsWith("_SPEAR");
  }

  private static boolean legacy(Player player) {
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    return proto.isPresent() && proto.get().olderThan(NATIVE_SPEARS);
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    var type = event.getPacketType();
    if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
      if (legacy(player)) {
        swapped.put(player.getUniqueId(),
            new long[] {new WrapperPlayClientHeldItemChange(event).getSlot(), System.currentTimeMillis() / 50L});
        digging.remove(player.getUniqueId());
      }
      return;
    }
    boolean candidate = type == PacketType.Play.Client.INTERACT_ENTITY || type == PacketType.Play.Client.ANIMATION
        || type == PacketType.Play.Client.PUNCH || type == PacketType.Play.Client.PLAYER_DIGGING;
    if (!candidate || !legacy(player)) {
      return;
    }
    UUID id = player.getUniqueId();
    long now = System.currentTimeMillis() / 50L;
    if (type == PacketType.Play.Client.INTERACT_ENTITY) {
      WrapperPlayClientInteractEntity pkt = new WrapperPlayClientInteractEntity(event);
      if (pkt.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK && maySpear(player)) {
        if (isSpear(held(player)) && (!swapInFlight(id, now) || RegistrySince.serverVersion().olderThan(ProtocolVersion.v26_1))) {
          event.setCancelled(true);
        }
        clicked.put(id, now);
        stab(player);
      }
    } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
      WrapperPlayClientPlayerDigging pkt = new WrapperPlayClientPlayerDigging(event);
      DiggingAction action = pkt.getAction();
      if (action == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
        handSwap.put(id, now);
        digging.remove(id);
        return;
      }
      if (action != DiggingAction.START_DIGGING && action != DiggingAction.FINISHED_DIGGING
          && action != DiggingAction.CANCELLED_DIGGING) {
        return;
      }
      digging.remove(id);
      if (!isSpear(held(player))) {
        return;
      }
      event.setCancelled(true);
      if (action == DiggingAction.START_DIGGING) {
        clicked.put(id, now);
        digging.add(id);
        stab(player);
      }
      if (action != DiggingAction.CANCELLED_DIGGING) {
        restore(player, pkt.getBlockPosition(), pkt.getSequence());
      }
    } else {
      Long click = clicked.remove(id);
      if (click != null && now - click <= 1) {
        return;
      }
      if (!digging.contains(id) && maySpear(player) && !player.isHandRaised()
          && !useHold.usedWithin(id, USE_SWING_MS)) {
        stab(player);
      }
    }
  }

  private boolean maySpear(Player player) {
    if (isSpear(player.getInventory().getItemInMainHand()) || isSpear(held(player))) {
      return true;
    }
    Long swap = handSwap.get(player.getUniqueId());
    return swap != null && System.currentTimeMillis() / 50L - swap <= 2
        && isSpear(player.getInventory().getItemInOffHand());
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null || !legacy(player)) {
      return;
    }
    WrapperPlayServerEntityMetadata pkt = new WrapperPlayServerEntityMetadata(event);
    if (pkt.getEntityId() != player.getEntityId()) {
      return;
    }
    List<EntityData<?>> meta = pkt.getEntityMetadata();
    for (int i = 0; i < meta.size(); i++) {
      EntityData<?> data = meta.get(i);
      if (data.getIndex() != LIVING_FLAGS || !(data.getValue() instanceof Byte flags) || (flags & USING_ITEM) == 0) {
        continue;
      }
      ItemStack used = (flags & USING_OFF_HAND) != 0
          ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
      if (isSpear(used)) {
        meta.set(i, new EntityData<>(LIVING_FLAGS, EntityDataTypes.BYTE, (byte) (flags & ~(USING_ITEM | USING_OFF_HAND))));
        event.markForReEncode(true);
      }
    }
  }

  private boolean swapInFlight(UUID id, long now) {
    long[] swap = swapped.get(id);
    Long hand = handSwap.get(id);
    return swap != null && now - swap[1] <= 2 || hand != null && now - hand <= 2;
  }

  private ItemStack held(Player player) {
    UUID id = player.getUniqueId();
    long now = System.currentTimeMillis() / 50L;
    long[] swap = swapped.get(id);
    if (swap != null && now - swap[1] > 2) {
      swapped.remove(id);
      swap = null;
    }
    Long hand = handSwap.get(id);
    if (hand != null && now - hand > 2) {
      handSwap.remove(id);
      hand = null;
    }
    if (swap != null && swap[0] >= 0 && swap[0] <= 8 && (hand == null || hand < swap[1])) {
      return player.getInventory().getItem((int) swap[0]);
    }
    return hand != null ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
  }

  private void restore(Player player, Vector3i pos, int seq) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user != null && seq > 0) {
      user.sendPacket(new WrapperPlayServerAcknowledgeBlockChanges(seq));
    }
    Bukkit.getScheduler().runTask(plugin, () -> {
      if (player.isOnline() && player.getWorld().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
        Block block = player.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
        player.sendBlockChange(block.getLocation(), block.getBlockData());
      }
    });
  }

  @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
  public void onClickBlock(org.bukkit.event.player.PlayerInteractEvent event) {
    if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK && legacy(event.getPlayer())
        && isSpear(event.getPlayer().getInventory().getItemInMainHand())) {
      event.setCancelled(true);
    }
  }

  @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
  public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
    if (legacy(event.getPlayer()) && isSpear(event.getPlayer().getInventory().getItemInMainHand())) {
      event.setCancelled(true);
    }
  }

  private void stab(Player player) {
    if (plugin.getConfig().getBoolean("debug.spear", false)) {
      plugin.getLogger().info("spear stab by " + player.getName() + " cooldown " + player.getAttackCooldown());
    }
    PacketEvents.getAPI().getPlayerManager().receivePacket(player,
        new WrapperPlayClientPlayerDigging(DiggingAction.STAB, new Vector3i(0, 0, 0), BlockFace.DOWN, 0));
  }

  private void tick() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID id = player.getUniqueId();
      ItemStack active = player.isHandRaised() ? player.getActiveItem() : null;
      Charge charge = null;
      if (isSpear(active)) {
        Optional<ItemCatalog.ItemEntry> ent = xlat.catalog().entry(active.getType());
        if (ent.isPresent() && ent.get().spear() != null) {
          charge = new Charge(ent.get().spear().frameCmdAt(player.getActiveItemUsedTime()),
              player.getHandRaised() == org.bukkit.inventory.EquipmentSlot.OFF_HAND);
        }
      }
      Charge prev = charge == null ? charging.remove(id) : charging.put(id, charge);
      if (charge != null) {
        byEntityId.put(player.getEntityId(), id);
      }
      if (java.util.Objects.equals(prev, charge)) {
        continue;
      }
      if (prev != null && (charge == null || prev.offHand() != charge.offHand())) {
        pushFrame(player, prev.offHand());
      }
      if (charge != null) {
        pushFrame(player, charge.offHand());
      }
    }
  }

  private void pushFrame(Player player, boolean offHand) {
    ItemStack held = offHand ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
    if (!isSpear(held)) {
      return;
    }
    var pe = SpigotConversionUtil.fromBukkitItemStack(held);
    User self = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (self != null && legacy(player)) {
      self.sendPacket(new WrapperPlayServerSetPlayerInventory(offHand ? OFF_HAND_SLOT : player.getInventory().getHeldItemSlot(), pe));
    }
    WrapperPlayServerEntityEquipment eq = new WrapperPlayServerEntityEquipment(player.getEntityId(),
        List.of(new Equipment(offHand ? EquipmentSlot.OFF_HAND : EquipmentSlot.MAIN_HAND, pe)));
    for (Player viewer : player.getWorld().getPlayers()) {
      if (viewer == player || !legacy(viewer) || viewer.getLocation().distanceSquared(player.getLocation()) > 64 * 64) {
        continue;
      }
      User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
      if (user != null) {
        user.sendPacket(eq);
      }
    }
  }

  @Override
  public ItemTranslator.Context contextFor(Player viewer, int windowSlot) {
    boolean off = windowSlot == OFF_HAND_WINDOW_SLOT;
    if (!off && windowSlot != 36 + viewer.getInventory().getHeldItemSlot()) {
      return ItemTranslator.Context.INVENTORY;
    }
    Charge charge = charging.get(viewer.getUniqueId());
    return charge != null && charge.offHand() == off ? new ItemTranslator.Context(true, charge.frame()) : ItemTranslator.Context.HELD;
  }

  @Override
  public ItemTranslator.Context heldBy(int entityId, boolean offHand) {
    UUID id = byEntityId.get(entityId);
    Charge charge = id == null ? null : charging.get(id);
    return charge != null && charge.offHand() == offHand
        ? new ItemTranslator.Context(true, charge.frame()) : ItemTranslator.Context.HELD;
  }

  @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
  public void onHeld(org.bukkit.event.player.PlayerItemHeldEvent event) {
    Player player = event.getPlayer();
    if (!legacy(player)) {
      return;
    }
    Bukkit.getScheduler().runTask(plugin, () -> {
      User self = PacketEvents.getAPI().getPlayerManager().getUser(player);
      if (self == null) {
        return;
      }
      for (int slot : new int[] {event.getPreviousSlot(), event.getNewSlot()}) {
        ItemStack stk = player.getInventory().getItem(slot);
        if (isSpear(stk)) {
          self.sendPacket(new WrapperPlayServerSetPlayerInventory(slot, SpigotConversionUtil.fromBukkitItemStack(stk)));
        }
      }
    });
  }

  @org.bukkit.event.EventHandler
  public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
    forget(event.getPlayer().getUniqueId());
  }

  public void forget(UUID id) {
    charging.remove(id);
    swapped.remove(id);
    handSwap.remove(id);
    clicked.remove(id);
    digging.remove(id);
    byEntityId.values().remove(id);
  }
}
