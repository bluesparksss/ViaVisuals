package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public final class CreativePick extends PacketListenerAbstract {

  private static final double REACH = 6.0;

  private final JavaPlugin plugin;
  private final BlockCatalog catalog;

  public CreativePick(JavaPlugin plugin, BlockCatalog catalog) {
    super(PacketListenerPriority.NORMAL);
    this.plugin = plugin;
    this.catalog = catalog;
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    if (event.getPacketType() != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null || player.getGameMode() != GameMode.CREATIVE) {
      return;
    }
    WrapperPlayClientCreativeInventoryAction pkt = new WrapperPlayClientCreativeInventoryAction(event);
    int slot = pkt.getSlot() - 36;
    if (slot < 0 || slot > 8 || pkt.getItemStack() == null || pkt.getItemStack().isEmpty()) {
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty() || !BlockCatalog.clientNeedsCarriers(proto.get()) || !proto.get().olderThan(ProtocolVersion.v1_21_4)) {
      return;
    }
    Material picked = SpigotConversionUtil.toBukkitItemStack(pkt.getItemStack()).getType();
    Bukkit.getScheduler().runTaskLater(plugin, () -> resolve(player, proto.get(), slot, picked), 2L);
  }

  private void resolve(Player player, ProtocolVersion client, int slot, Material picked) {
    if (!player.isOnline() || player.getGameMode() != GameMode.CREATIVE) {
      return;
    }
    Block target = player.getTargetBlockExact((int) REACH);
    if (target == null) {
      return;
    }
    int state = SpigotConversionUtil.fromBukkitBlockData(target.getBlockData()).getGlobalId();
    BlockCatalog.Mapping map = catalog.mappingFor(state, client);
    if (map == null) {
      return;
    }
    WrappedBlockState carrier = WrappedBlockState.getByGlobalId(catalog.serverVersion(), catalog.carrierFor(map, client), false);
    if (SpigotConversionUtil.toBukkitBlockData(carrier).getPlacementMaterial() != picked) {
      return;
    }
    Material real = target.getBlockData().getPlacementMaterial();
    if (!real.isItem()) {
      return;
    }
    player.getInventory().setItem(slot, new ItemStack(real));
    player.updateInventory();
  }
}
