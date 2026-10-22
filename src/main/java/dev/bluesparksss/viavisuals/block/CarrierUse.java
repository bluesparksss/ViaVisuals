package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CarrierUse extends PacketListenerAbstract implements Listener {

  private static final long SETTLE_TICKS = 2L;
  private static final long PLACED_WINDOW_TICKS = 4L;

  private record Click(InteractionHand hand, Vector3i pos, BlockFace face, Vector3f cursor, boolean inside, int seq) {
  }

  private final JavaPlugin plugin;
  private final BlockCatalog catalog;
  private final Map<UUID, Long> placedAt = new ConcurrentHashMap<>();

  public CarrierUse(JavaPlugin plugin, BlockCatalog catalog) {
    super(PacketListenerPriority.MONITOR);
    this.plugin = plugin;
    this.catalog = catalog;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    placedAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() / 50L);
  }

  @EventHandler
  public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
    placedAt.remove(event.getPlayer().getUniqueId());
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty() || !BlockCatalog.clientNeedsCarriers(proto.get())) {
      return;
    }
    WrapperPlayClientPlayerBlockPlacement pkt = new WrapperPlayClientPlayerBlockPlacement(event);
    if (pkt.getHand() != InteractionHand.MAIN_HAND) {
      return;
    }
    Click click = new Click(pkt.getHand(), pkt.getBlockPosition(), pkt.getFace(), pkt.getCursorPosition(),
        pkt.getInsideBlock().orElse(false), pkt.getSequence());
    long sent = System.currentTimeMillis() / 50L;
    Bukkit.getScheduler().runTaskLater(plugin, () -> settle(player, proto.get(), click, sent), SETTLE_TICKS);
  }

  private void settle(Player player, ProtocolVersion client, Click click, long sent) {
    if (!player.isOnline() || !player.getWorld().isChunkLoaded(click.pos().getX() >> 4, click.pos().getZ() >> 4)) {
      return;
    }
    Block block = player.getWorld().getBlockAt(click.pos().getX(), click.pos().getY(), click.pos().getZ());
    BlockCatalog.Mapping map = catalog.mappingFor(SpigotConversionUtil.fromBukkitBlockData(block.getBlockData()).getGlobalId(), client);
    if (map == null) {
      return;
    }
    Material carrier = SpigotConversionUtil.toBukkitBlockData(
        WrappedBlockState.getByGlobalId(catalog.serverVersion(), map.clientState(), false)).getMaterial();
    boolean wooden = openable(carrier);
    if (carrier != Material.NOTE_BLOCK && !wooden) {
      return;
    }
    if (wooden) {
      player.sendBlockChange(block.getLocation(), block.getBlockData());
    }
    ItemStack main = player.getInventory().getItemInMainHand();
    ItemStack off = player.getInventory().getItemInOffHand();
    boolean mainEmpty = main.getType().isAir();
    boolean offEmpty = off.getType().isAir();
    if (block.getType().isInteractable() || (player.isSneaking() && !(mainEmpty && offEmpty))) {
      return;
    }
    Long placed = placedAt.get(player.getUniqueId());
    if (placed != null && placed >= sent - 1 && placed <= sent + PLACED_WINDOW_TICKS) {
      return;
    }
    if (!mainEmpty) {
      use(player, InteractionHand.MAIN_HAND, click.seq());
    } else if (!offEmpty) {
      place(player, click);
      use(player, InteractionHand.OFF_HAND, click.seq());
    }
  }

  private static boolean openable(Material type) {
    String name = type.name();
    return (name.endsWith("_TRAPDOOR") || name.endsWith("_DOOR") || name.endsWith("_FENCE_GATE")) && !name.startsWith("IRON_");
  }

  private static void place(Player player, Click click) {
    PacketEvents.getAPI().getPlayerManager().receivePacket(player, new WrapperPlayClientPlayerBlockPlacement(
        InteractionHand.OFF_HAND, click.pos(), click.face(), click.cursor(),
        com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY, click.inside(), click.seq()));
  }

  private static void use(Player player, InteractionHand hand, int seq) {
    PacketEvents.getAPI().getPlayerManager().receivePacket(player,
        new WrapperPlayClientUseItem(hand, seq, player.getLocation().getYaw(), player.getLocation().getPitch()));
  }
}
