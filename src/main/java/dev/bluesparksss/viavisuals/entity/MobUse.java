package dev.bluesparksss.viavisuals.entity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class MobUse extends PacketListenerAbstract implements Listener {

  private static final long SETTLE_TICKS = 2L;

  private record Snapshot(ItemStack held, boolean leashed, int passengers, boolean riding) {
    boolean sameAs(Snapshot other) {
      return leashed == other.leashed && passengers == other.passengers && riding == other.riding
          && Objects.equals(held, other.held);
    }
  }

  private final JavaPlugin plugin;
  private final MobBoxes boxes;
  private volatile boolean debug;

  public MobUse(JavaPlugin plugin, MobBoxes boxes) {
    super(PacketListenerPriority.MONITOR);
    this.plugin = plugin;
    this.boxes = boxes;
  }

  public void configure(org.bukkit.configuration.file.FileConfiguration conf) {
    debug = conf.getBoolean("debug.hitboxes", false);
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    if (!debug || event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
      return;
    }
    WrapperPlayClientInteractEntity pkt = new WrapperPlayClientInteractEntity(event);
    plugin.getLogger().info("hitbox: " + event.getUser().getName() + " " + pkt.getAction() + " " + pkt.getHand()
        + " entity " + pkt.getEntityId());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInteract(PlayerInteractEntityEvent event) {
    Player player = event.getPlayer();
    Entity ent = event.getRightClicked();
    if (!boxes.isBoxed(player.getUniqueId(), ent.getEntityId())) {
      return;
    }
    EquipmentSlot hand = event.getHand();
    if (debug) {
      plugin.getLogger().info("hitbox: interact event " + hand + " on " + ent.getType() + " " + ent.getEntityId()
          + " held " + held(player, hand).getType());
    }
    Snapshot before = snapshot(player, ent, hand);
    Bukkit.getScheduler().runTaskLater(plugin, () -> afterInteract(player, ent, hand, before), SETTLE_TICKS);
  }

  private static ItemStack held(Player player, EquipmentSlot hand) {
    return hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
  }

  private static ItemStack copy(ItemStack stack) {
    return stack.getType().isAir() ? null : stack.clone();
  }

  private static Snapshot snapshot(Player player, Entity ent, EquipmentSlot hand) {
    return new Snapshot(copy(held(player, hand)),
        ent instanceof LivingEntity liv && liv.isLeashed(), ent.getPassengers().size(), ent.equals(player.getVehicle()));
  }

  private void afterInteract(Player player, Entity ent, EquipmentSlot hand, Snapshot before) {
    if (!player.isOnline() || !ent.isValid()) {
      return;
    }
    if (!before.sameAs(snapshot(player, ent, hand))) {
      swing(player, hand);
      return;
    }
    ItemStack held = held(player, hand);
    if (player.isHandRaised()) {
      return;
    }
    if (held.getType().isAir()) {
      if (hand == EquipmentSlot.HAND) {
        offHand(player, ent);
      }
      return;
    }
    if (debug) {
      plugin.getLogger().info("hitbox: " + player.getName() + " right-click on " + ent.getType() + " did nothing, using "
          + held.getType() + " (" + hand + ")");
    }
    ItemStack used = copy(held);
    InteractionHand packetHand = hand == EquipmentSlot.OFF_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    PacketEvents.getAPI().getPlayerManager().receivePacket(player, new WrapperPlayClientUseItem(
        packetHand, 0, player.getLocation().getYaw(), player.getLocation().getPitch()));
    if (hand == EquipmentSlot.HAND) {
      Bukkit.getScheduler().runTaskLater(plugin, () -> {
        if (Objects.equals(used, copy(held(player, hand)))) {
          offHand(player, ent);
        }
      }, SETTLE_TICKS);
    }
  }

  private void offHand(Player player, Entity ent) {
    if (!player.isOnline() || !ent.isValid() || player.isHandRaised()
        || player.getInventory().getItemInOffHand().getType().isAir()) {
      return;
    }
    if (debug) {
      plugin.getLogger().info("hitbox: " + player.getName() + " trying the off hand on " + ent.getType());
    }
    PacketEvents.getAPI().getPlayerManager().receivePacket(player, new WrapperPlayClientInteractEntity(
        ent.getEntityId(), InteractionHand.OFF_HAND, new Vector3d(0, 0, 0), player.isSneaking()));
  }

  private static void swing(Player player, EquipmentSlot hand) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user == null) {
      return;
    }
    InteractionHand packetHand = hand == EquipmentSlot.OFF_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_26_3)) {
      user.sendPacket(new WrapperPlayServerSwingAnimation(player.getEntityId(), packetHand,
          new ItemSwingAnimation(ItemSwingAnimation.Type.WHACK, 6)));
    } else {
      user.sendPacket(new WrapperPlayServerEntityAnimation(player.getEntityId(), hand == EquipmentSlot.OFF_HAND
          ? WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND
          : WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
    }
  }
}
