package dev.bluesparksss.viavisuals.mechanics;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.item.ItemTranslator;
import dev.bluesparksss.viavisuals.version.VersionManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class UseHoldSupport extends PacketListenerAbstract {

  private static final Material SPYGLASS = Material.matchMaterial("spyglass");
  private static final Set<Material> HOLD_ITEMS = java.util.stream.Stream.concat(
          java.util.stream.Stream.of(SPYGLASS, Material.matchMaterial("brush")),
          java.util.Arrays.stream(Material.values()).filter(mat -> isBundle(mat) || isSpear(mat)))
      .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
  private static final long RELEASE_GAP_MS = 300;
  private static final long BLOCK_CLICK_WINDOW_MS = 150;
  private static final long OTHER_HAND_WINDOW_MS = 120;

  private final JavaPlugin plugin;
  private final ItemTranslator xlat;
  private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastBlockClick = new ConcurrentHashMap<>();

  public UseHoldSupport(JavaPlugin plugin, ItemTranslator xlat) {
    super(PacketListenerPriority.NORMAL);
    this.plugin = plugin;
    this.xlat = xlat;
    Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    var type = event.getPacketType();
    boolean onBlock = type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT;
    if (!onBlock && type != PacketType.Play.Client.USE_ITEM) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    InteractionHand hand;
    int seq;
    if (onBlock) {
      WrapperPlayClientPlayerBlockPlacement pkt = new WrapperPlayClientPlayerBlockPlacement(event);
      hand = pkt.getHand();
      seq = pkt.getSequence();
    } else {
      WrapperPlayClientUseItem pkt = new WrapperPlayClientUseItem(event);
      hand = pkt.getHand();
      seq = pkt.getSequence();
    }
    ItemStack main = player.getInventory().getItemInMainHand();
    ItemStack off = player.getInventory().getItemInOffHand();
    ItemStack held = hand == InteractionHand.OFF_HAND ? off : main;
    if (held == null || !HOLD_ITEMS.contains(held.getType()) || !seesProxy(player, held)) {
      if (onBlock
          && (holdProxy(player, main) || holdProxy(player, off))
          && (usingHold(player) || usedWithin(player.getUniqueId(), OTHER_HAND_WINDOW_MS))) {
        event.setCancelled(true);
        if (seq > 0) {
          event.getUser().sendPacket(new WrapperPlayServerAcknowledgeBlockChanges(seq));
        }
      }
      return;
    }
    lastUse.put(player.getUniqueId(), System.currentTimeMillis());
    if (isBundle(held.getType()) || isSpear(held.getType())) {
      long now = System.currentTimeMillis();
      if (onBlock) {
        lastBlockClick.put(player.getUniqueId(), now);
      } else if (now - lastBlockClick.getOrDefault(player.getUniqueId(), 0L) < BLOCK_CLICK_WINDOW_MS) {
        ensureUse(player, hand == InteractionHand.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
      }
    }
    ItemStack active = player.isHandRaised() ? player.getActiveItem() : null;
    if (active != null && active.getType() == held.getType()) {
      event.setCancelled(true);
      if (seq > 0) {
        event.getUser().sendPacket(new WrapperPlayServerAcknowledgeBlockChanges(seq));
      }
    }
  }

  private static boolean isBundle(Material mat) {
    return mat.name().equals("BUNDLE") || mat.name().endsWith("_BUNDLE");
  }

  private static boolean isSpear(Material mat) {
    return mat.name().endsWith("_SPEAR");
  }

  public boolean usedWithin(UUID id, long ms) {
    Long last = lastUse.get(id);
    return last != null && System.currentTimeMillis() - last <= ms;
  }

  private void ensureUse(Player player, EquipmentSlot hand) {
    Bukkit.getScheduler().runTask(plugin, () -> {
      if (!player.isOnline() || player.isHandRaised() || player.isDead()) {
        return;
      }
      ItemStack now = hand == EquipmentSlot.OFF_HAND
          ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
      if (isBundle(now.getType()) || isSpear(now.getType())) {
        player.startUsingItem(hand);
      }
    });
  }

  private boolean holdProxy(Player player, ItemStack stack) {
    return stack != null && HOLD_ITEMS.contains(stack.getType()) && seesProxy(player, stack);
  }

  private static boolean usingHold(Player player) {
    if (!player.isHandRaised()) {
      return false;
    }
    ItemStack active = player.getActiveItem();
    return active != null && HOLD_ITEMS.contains(active.getType());
  }

  private boolean seesProxy(Player player, ItemStack held) {
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    return proto.isPresent() && xlat.needsTranslation(held, proto.get());
  }

  private void tick() {
    long now = System.currentTimeMillis();
    for (Map.Entry<UUID, Long> ent : lastUse.entrySet()) {
      Player player = Bukkit.getPlayer(ent.getKey());
      if (player == null) {
        lastUse.remove(ent.getKey());
        continue;
      }
      ItemStack active = player.isHandRaised() ? player.getActiveItem() : null;
      if (active == null || !HOLD_ITEMS.contains(active.getType())) {
        if (now - ent.getValue() > RELEASE_GAP_MS) {
          lastUse.remove(ent.getKey());
        }
        continue;
      }
      if (now - ent.getValue() > RELEASE_GAP_MS) {
        lastUse.remove(ent.getKey());
        player.clearActiveItem();
        if (active.getType() == SPYGLASS) {
          player.getWorld().playSound(player.getLocation(), "minecraft:item.spyglass.stop_using", SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
      }
    }
  }

  public void forget(UUID id) {
    lastUse.remove(id);
    lastBlockClick.remove(id);
  }

}
